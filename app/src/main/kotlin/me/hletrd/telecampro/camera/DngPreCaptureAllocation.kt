package me.hletrd.telecampro.camera

import java.util.concurrent.atomic.AtomicBoolean

/** One process-wide DNG shutter admission so provider preallocation cannot reorder RAW requests. */
internal object ProcessDngPreCaptureAdmission {
    val owner = DngPreCaptureAdmission()
}

/** Android-free exactly-once lease for one DNG allocation + Camera2/save lifetime. */
internal class DngPreCaptureAdmission {
    private val occupied = AtomicBoolean(false)

    fun tryAcquire(): Lease? = if (occupied.compareAndSet(false, true)) Lease(this) else null

    fun canAdmit(): Boolean = !occupied.get()

    private fun release() {
        check(occupied.compareAndSet(true, false)) { "DNG pre-capture admission underflow" }
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
    private val dispatch: ((() -> Unit) -> RecordingPreNativeSubmission) =
        ProcessPreNativeMediaAllocator::dispatch,
    private val allocate: () -> T?,
    private val isCurrent: () -> Boolean,
    private val onReady: (T) -> Unit,
    private val onLateValue: (T) -> Unit,
    private val onFailure: (Throwable?) -> Unit,
    private val onRetired: () -> Unit,
    private val onClaimed: () -> Unit = {},
) {
    private val started = AtomicBoolean(false)
    private lateinit var attempt: RecordingPreNativeAllocationAttempt<T>

    fun start(): RecordingPreNativeDispatch {
        check(started.compareAndSet(false, true)) { "DNG pre-capture allocation already started" }
        attempt = RecordingPreNativeAllocationAttempt(
            onRetired = onRetired,
            onLateValue = onLateValue,
        )
        val submission = dispatch {
            val result = runCatching(allocate)
            when (attempt.deliver(result) {
                onFailure(result.exceptionOrNull())
            }) {
                RecordingPreNativeDelivery.READY -> {
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
