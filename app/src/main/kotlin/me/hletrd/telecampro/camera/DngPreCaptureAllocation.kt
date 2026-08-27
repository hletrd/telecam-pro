package me.hletrd.telecampro.camera

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import me.hletrd.telecampro.ProcessAdmissionSignal
import me.hletrd.telecampro.ProcessAdmissionSubscription

/** One process-wide DNG shutter admission so provider preallocation cannot reorder RAW requests. */
internal object ProcessDngPreCaptureAdmission {
    val owner = DngPreCaptureAdmission()
}

internal fun allStillOutputOwnersAvailable(
    dng: Boolean,
    retainedFamily: Boolean,
    rejectedCleanup: Boolean,
): Boolean = dng && retainedFamily && rejectedCleanup

/** Android-free exactly-once lease for one DNG allocation + Camera2/save lifetime. */
internal class DngPreCaptureAdmission {
    private val occupied = AtomicBoolean(false)
    private val admissionSignal = ProcessAdmissionSignal(initial = true)

    fun tryAcquire(): Lease? = if (occupied.compareAndSet(false, true)) {
        admissionSignal.publish(false)
        Lease(this)
    } else {
        null
    }

    fun canAdmit(): Boolean = !occupied.get()

    fun subscribe(listener: (Boolean) -> Unit): ProcessAdmissionSubscription =
        admissionSignal.subscribe(listener)

    private fun release() {
        check(occupied.compareAndSet(true, false)) { "DNG pre-capture admission underflow" }
        admissionSignal.publish(true)
    }

    internal class Lease internal constructor(private val owner: DngPreCaptureAdmission) {
        private val released = AtomicBoolean(false)

        fun release(): Boolean {
            if (!released.compareAndSet(false, true)) return false
            owner.release()
            return true
        }
    }
}

/**
 * Cancellable owner for one provider allocation that must complete before Camera2 sees the request.
 *
 * Provider Binder calls cannot be interrupted. [cancel] therefore retires caller/capture ownership
 * immediately; an allocation that returns later is delivered only to [onLateValue]. A claimed value
 * is handed to [onReady] exactly once. The process dispatcher is finite and shared with recording.
 */
internal class DngPreCaptureAllocation<T : Any>(
    private val dispatch: ((() -> Unit) -> RecordingPreNativeSubmission),
    private val allocate: () -> T?,
    private val isCurrent: () -> Boolean,
    private val onReady: (T) -> Unit,
    private val onLateValue: (T) -> Unit,
    private val onFailure: (Throwable?) -> Unit,
    private val onRetired: () -> Unit,
    private val onClaimed: () -> Unit = {},
    private val deadlineScheduler: RecordingTeardownScheduler? = null,
    private val deadlineMs: Long = DNG_PRE_CAPTURE_ALLOCATION_TIMEOUT_MS,
    private val beforeDeadlineCompletion: () -> Unit = {},
) {
    private val started = AtomicBoolean(false)
    private lateinit var attempt: RecordingPreNativeAllocationAttempt<T>
    private val deadline = AtomicReference<RecordingOperationDeadline?>(null)

    fun start(): RecordingPreNativeDispatch {
        check(started.compareAndSet(false, true)) { "DNG pre-capture allocation already started" }
        attempt = RecordingPreNativeAllocationAttempt(
            onRetired = {
                deadline.get()?.complete()
                onRetired()
            },
            onLateValue = onLateValue,
        )
        val allocationDeadline = deadlineScheduler?.let { scheduler ->
            RecordingOperationDeadline(
                scheduler = scheduler,
                timeoutMs = deadlineMs,
                failure = { java.util.concurrent.TimeoutException("DNG allocation timed out") },
                onTimeout = { failure -> attempt.retire { onFailure(failure) } },
            )
        }
        deadline.set(allocationDeadline)
        if (allocationDeadline != null && !allocationDeadline.arm()) {
            return RecordingPreNativeDispatch.SHUTDOWN
        }
        val submission = dispatch {
            val result = runCatching(allocate)
            when (attempt.deliver(result) {
                onFailure(result.exceptionOrNull())
            }) {
                RecordingPreNativeDelivery.READY -> {
                    // Provider return and timeout race independently. Only the deadline winner may
                    // transfer the row to Camera2; a losing return becomes ordinary late cleanup.
                    beforeDeadlineCompletion()
                    if (allocationDeadline != null && !allocationDeadline.complete()) {
                        attempt.retire()
                        return@dispatch
                    }
                    if (!runCatching(isCurrent).getOrDefault(false)) {
                        attempt.retire()
                        return@dispatch
                    }
                    val value = attempt.claim() ?: return@dispatch
                    onClaimed()
                    runCatching { onReady(value) }.exceptionOrNull()?.let { failure ->
                        try {
                            runCatching { onLateValue(value) }
                        } finally {
                            try {
                                runCatching { onFailure(failure) }
                            } finally {
                                onRetired()
                            }
                        }
                    }
                }
                RecordingPreNativeDelivery.FAILED,
                RecordingPreNativeDelivery.STALE,
                -> Unit
            }
        }
        submission.cancellation?.let(attempt::attachCancellation)
        if (submission.dispatch != RecordingPreNativeDispatch.ACCEPTED) {
            attempt.retire { onFailure(null) }
        }
        return submission.dispatch
    }

    /** Returns promptly even when allocation is already blocked in MediaProvider. */
    fun cancel(): Boolean = started.get() && attempt.retire()
}

internal const val DNG_PRE_CAPTURE_ALLOCATION_TIMEOUT_MS = 8_000L
