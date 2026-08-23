package me.hletrd.telecampro.camera

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

internal enum class RecordingTeardownTerminal { FINALIZE, QUARANTINE }

internal fun interface RecordingTeardownCancellation {
    fun cancel()
}

internal fun interface RecordingTeardownScheduler {
    /** Returns null, or throws, when the watchdog cannot accept this deadline. */
    fun schedule(delayMs: Long, action: () -> Unit): RecordingTeardownCancellation?
}

internal enum class RecordingOperationState { NEW, ACTIVE, COMPLETED, TIMED_OUT }

internal enum class RecorderNativeFinalization { PENDING, RELEASED, QUARANTINED }

/**
 * First-wins classification of the recorder's native graph, independent from the later storage tail.
 *
 * RELEASED means every native owner was checked closed and replacement process admission is safe.
 * QUARANTINED means at least one native owner remains uncertain and is retained process-long. The
 * task that publishes/deletes the MediaStore row may continue after either classification.
 */
internal class RecorderNativeFinalizationGate {
    private val state = AtomicReference(RecorderNativeFinalization.PENDING)
    private val classified = CountDownLatch(1)

    fun classify(candidate: RecorderNativeFinalization): Boolean {
        require(candidate != RecorderNativeFinalization.PENDING)
        if (!state.compareAndSet(RecorderNativeFinalization.PENDING, candidate)) return false
        classified.countDown()
        return true
    }

    fun current(): RecorderNativeFinalization = state.get()

    fun await(timeout: Long, unit: TimeUnit): RecorderNativeFinalization {
        if (state.get() == RecorderNativeFinalization.PENDING) {
            try {
                classified.await(timeout.coerceAtLeast(0L), unit)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        return state.get()
    }
}

/** Native release owns publication only when both the deadline and first-wins classification agree. */
internal fun completeRecorderNativeRelease(
    deadlineComplete: () -> Boolean,
    classification: RecorderNativeFinalizationGate,
    releaseProcessAdmission: () -> Unit,
): Boolean {
    if (!deadlineComplete()) return false
    if (!classification.classify(RecorderNativeFinalization.RELEASED)) return false
    releaseProcessAdmission()
    return true
}

/**
 * Engine release waits only for native classification. A still-pending graph is synchronously
 * claimed for quarantine; an already RELEASED graph is never reclassified because storage is slow.
 */
internal fun nativeFinalizationAtEngineRelease(
    classification: RecorderNativeFinalizationGate,
    timeout: Long,
    unit: TimeUnit,
): RecorderNativeFinalization {
    val observed = classification.await(timeout, unit)
    if (observed != RecorderNativeFinalization.PENDING) return observed
    classification.classify(RecorderNativeFinalization.QUARANTINED)
    return classification.current()
}

/** One hard deadline for a native operation whose late completion must become inert. */
internal class RecordingOperationDeadline(
    private val scheduler: RecordingTeardownScheduler,
    private val timeoutMs: Long,
    private val failure: () -> Throwable,
    private val onTimeout: (Throwable) -> Unit,
) {
    private val lock = Any()
    private var state = RecordingOperationState.NEW
    private var cancellation: RecordingTeardownCancellation? = null

    init {
        require(timeoutMs > 0L)
    }

    fun arm(): Boolean {
        val scheduled = runCatching {
            scheduler.schedule(timeoutMs) { timeout() }
        }.getOrNull()
        if (scheduled == null) {
            val claimed = synchronized(lock) {
                if (state != RecordingOperationState.NEW) false else {
                    state = RecordingOperationState.TIMED_OUT
                    true
                }
            }
            if (claimed) onTimeout(failure())
            return false
        }
        val installed = synchronized(lock) {
            if (state != RecordingOperationState.NEW) false else {
                state = RecordingOperationState.ACTIVE
                cancellation = scheduled
                true
            }
        }
        if (!installed) scheduled.cancel()
        return installed
    }

    /** True only for the operation-completion winner; false makes a post-timeout return inert. */
    fun complete(): Boolean {
        val toCancel = synchronized(lock) {
            if (state != RecordingOperationState.ACTIVE) return false
            state = RecordingOperationState.COMPLETED
            cancellation.also { cancellation = null }
        }
        runCatching { toCancel?.cancel() }
        return true
    }

    fun current(): RecordingOperationState = synchronized(lock) { state }

    private fun timeout() {
        val claimed = synchronized(lock) {
            if (state != RecordingOperationState.ACTIVE) false else {
                state = RecordingOperationState.TIMED_OUT
                cancellation = null
                true
            }
        }
        if (claimed) onTimeout(failure())
    }
}

/**
 * Owns the complete detach/recovery decision for one recorder graph.
 *
 * The scheduler and effects are injected so wedges, rejection, abandonment, and late callbacks can
 * be tested without Android. Decisions are selected under [lock], while cancellation and effects run
 * outside it so a recovery effect may synchronously report strict release without deadlocking.
 */
internal class RecordingTeardownCoordinator(
    private val scheduler: RecordingTeardownScheduler,
    private val detachTimeoutMs: Long,
    private val hardTimeoutMs: Long,
    private val onRecoveryRequired: (Throwable) -> Unit,
    private val onTerminal: (RecordingTeardownTerminal, Throwable?) -> Unit,
) {
    private val lock = Any()
    private var terminal: RecordingTeardownTerminal? = null
    private var recoveryStarted = false
    private var detachDeadline: RecordingTeardownCancellation? = null
    private var hardDeadline: RecordingTeardownCancellation? = null

    init {
        require(detachTimeoutMs > 0L)
        require(hardTimeoutMs > detachTimeoutMs)
    }

    /** Arms both watchdogs before submitting detach, so an accepted-but-never-run callback is bounded. */
    fun start(submitDetach: ((Result<Unit>) -> Unit) -> Unit): Boolean {
        if (!armDeadline(
                delayMs = detachTimeoutMs,
                hard = false,
                action = {
                    requestRecovery(TimeoutException("Encoder detach did not complete"))
                },
            )
        ) {
            finish(
                RecordingTeardownTerminal.QUARANTINE,
                IllegalStateException("Recording detach watchdog unavailable"),
            )
            return false
        }
        if (!armDeadline(
                delayMs = hardTimeoutMs,
                hard = true,
                action = {
                    finish(
                        RecordingTeardownTerminal.QUARANTINE,
                        TimeoutException("Encoder detach did not complete"),
                    )
                },
            )
        ) {
            finish(
                RecordingTeardownTerminal.QUARANTINE,
                IllegalStateException("Recording quarantine watchdog unavailable"),
            )
            return false
        }

        return runCatching { submitDetach(::onDetachResult) }
            .fold(
                onSuccess = { true },
                onFailure = {
                    requestRecovery(it)
                    false
                },
            )
    }

    /** Strict EGL resource-release proof is the only recovery event allowed to finalize the recorder. */
    fun resourcesReleased() {
        finish(RecordingTeardownTerminal.FINALIZE, null)
    }

    /** ABANDONED/no-proof recovery must retain the native recorder graph process-long. */
    fun recoveryAbandoned(failure: Throwable) {
        finish(RecordingTeardownTerminal.QUARANTINE, failure)
    }

    internal fun current(): RecordingTeardownTerminal? = synchronized(lock) { terminal }

    internal fun hasStartedRecovery(): Boolean = synchronized(lock) { recoveryStarted }

    private fun onDetachResult(result: Result<Unit>) {
        result.fold(
            onSuccess = { finish(RecordingTeardownTerminal.FINALIZE, null) },
            onFailure = ::requestRecovery,
        )
    }

    private fun requestRecovery(failure: Throwable) {
        val admitted = synchronized(lock) {
            if (terminal != null || recoveryStarted) {
                false
            } else {
                recoveryStarted = true
                true
            }
        }
        if (!admitted) return
        runCatching { onRecoveryRequired(failure) }
            .onFailure(::recoveryAbandoned)
    }

    private fun armDeadline(delayMs: Long, hard: Boolean, action: () -> Unit): Boolean {
        val cancellation = runCatching { scheduler.schedule(delayMs, action) }.getOrNull() ?: return false
        val installed = synchronized(lock) {
            if (terminal != null) {
                false
            } else {
                if (hard) hardDeadline = cancellation else detachDeadline = cancellation
                true
            }
        }
        if (!installed) cancellation.cancel()
        return installed
    }

    private fun finish(candidate: RecordingTeardownTerminal, failure: Throwable?) {
        val cancellations = synchronized(lock) {
            if (terminal != null) return
            terminal = candidate
            listOfNotNull(detachDeadline, hardDeadline).also {
                detachDeadline = null
                hardDeadline = null
            }
        }
        cancellations.forEach { runCatching { it.cancel() } }
        onTerminal(candidate, failure)
    }
}
