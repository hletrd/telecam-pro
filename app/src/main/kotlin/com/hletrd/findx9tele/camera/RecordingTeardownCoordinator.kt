package com.hletrd.findx9tele.camera

import java.util.concurrent.TimeoutException

internal enum class RecordingTeardownTerminal { FINALIZE, QUARANTINE }

internal fun interface RecordingTeardownCancellation {
    fun cancel()
}

internal fun interface RecordingTeardownScheduler {
    /** Returns null, or throws, when the watchdog cannot accept this deadline. */
    fun schedule(delayMs: Long, action: () -> Unit): RecordingTeardownCancellation?
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
