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

/**
 * One ordered publication owner for an Engine's combined still-admission truth.
 *
 * [snapshot] and [deliver] run inside the same monitor. An older process-signal callback therefore
 * cannot compute one value, pause, and arrive after a newer constituent edge. Engine-local owners
 * use the same entry point, so no direct publication can leave the delivered-state cache stale.
 */
internal class StillAdmissionPublication(
    private val snapshot: () -> Boolean,
    private val deliver: (Boolean) -> Unit,
) {
    private val lock = Any()
    private var delivered: Boolean? = null

    fun publish() = synchronized(lock) {
        val current = snapshot()
        if (delivered == current) return@synchronized
        delivered = current
        deliver(current)
    }

    fun reset() = synchronized(lock) {
        delivered = null
    }
}

/** Android-free exactly-once lease for one DNG allocation + Camera2/save lifetime. */
internal class DngPreCaptureAdmission {
    private val occupied = AtomicBoolean(false)
    private val admissionSignal = ProcessAdmissionSignal(initial = true)

    // Both edges publish through refresh, which re-reads the live lease INSIDE the signal monitor
    // (TE5-12, the AGG4-35 race): with a computed publish, a release preempted between its CAS and
    // its `publish(true)` could land after a racing acquire's change-gated `publish(false)` and
    // leave the shutter reading open while the slot was held.
    fun tryAcquire(): Lease? = if (occupied.compareAndSet(false, true)) {
        admissionSignal.refresh { !occupied.get() }
        Lease(this)
    } else {
        null
    }

    fun canAdmit(): Boolean = !occupied.get()

    fun subscribe(listener: (Boolean) -> Unit): ProcessAdmissionSubscription =
        admissionSignal.subscribe(listener)

    private fun release() {
        check(occupied.compareAndSet(true, false)) { "DNG pre-capture admission underflow" }
        admissionSignal.refresh { !occupied.get() }
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
    private val cancelRequested = AtomicBoolean(false)
    private val deadline = AtomicReference<RecordingOperationDeadline?>(null)

    /**
     * Built at construction, not inside [start] (AGG4-21). The engine registers this owner for
     * [cancel] BEFORE it starts it, so a cancel from `invalidateCameraReady()` on another thread
     * could land between `start()`'s CAS and a deferred assignment: reading an unassigned
     * `lateinit` threw out of the invalidation before Ready was cleared, and a non-volatile write
     * was not even guaranteed visible after it. A final field is safely published with the object.
     */
    private val attempt = RecordingPreNativeAllocationAttempt<T>(
        onRetired = {
            deadline.get()?.complete()
            onRetired()
        },
        onLateValue = onLateValue,
    )

    fun start(): RecordingPreNativeDispatch {
        check(started.compareAndSet(false, true)) { "DNG pre-capture allocation already started" }
        // A cancel that landed before start() already retired (and settled) this owner. Never begin
        // a provider allocation for it: report the same synchronous non-dispatch the scheduler
        // refusal does, so [StillContinuationHandoff] answers from the settle that already ran.
        if (cancelRequested.get()) return RecordingPreNativeDispatch.SHUTDOWN
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
        // DB5-13: a cancel that landed after the latch check above already retired this attempt,
        // but its `deadline.get()?.complete()` ran against a deadline that was not yet armed (a
        // no-op), so the armed timer would fire 8 s later for nothing. Complete it here and never
        // dispatch provider work for a retired owner.
        if (cancelRequested.get()) {
            allocationDeadline?.complete()
            return RecordingPreNativeDispatch.SHUTDOWN
        }
        val submission = dispatch {
            // The dispatcher only cancels a task it has not dequeued yet. A worker that dequeued
            // this one after the cancel must not insert a row: the retirement already released the
            // process DNG slot and its rejected-cleanup reservation, so the late row would have no
            // cleanup capacity and the next shot could overtake it (DB5-13).
            if (attempt.isRetired()) return@dispatch
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

    /**
     * Returns promptly even when allocation is already blocked in MediaProvider. Safe at any point
     * of the owner's life: before [start] it latches the request (so start never allocates) and
     * retires the attempt exactly once, which settles the shot through [onRetired].
     */
    fun cancel(): Boolean {
        cancelRequested.set(true)
        return attempt.retire()
    }
}

/**
 * Exactly-once owner of a still chain's continuation across one DNG pre-allocation.
 *
 * Chain callers (BURST/AEB/timelapse) read `dispatchStillCapture`'s Boolean as "true: `onDone`
 * owns the next step; false: it never will, continue yourself". A SYNCHRONOUS allocator rejection
 * (OVERFLOW/SHUTDOWN, or a deadline that cannot be armed) retires the attempt inside `start()`, and
 * that retirement settles the shot before `start()` returns non-ACCEPTED. The dispatcher then ALSO
 * returned false, so timelapse scheduled every tick twice (2^n live tasks while the shared
 * allocator stayed saturated) and AEB reset the preview to base controls in the middle of the
 * bracket step `onDone` had already fired (RPL cycle 2, AGG2-3).
 *
 * The first fix let whichever side claimed first own the continuation, so a synchronous rejection
 * ran `onDone` from inside `start()` and the dispatcher answered TRUE (AGG6-23). That kept one
 * continuation but answered the wrong question for a BURST/AEB HEAD: the press read as taken, the
 * shutter animated success, and `fire(1..n)` walked the rest of the chain recursively on the same
 * stack against the same saturated allocator. A settle that lands while `start()` is still running
 * is therefore DEFERRED: a non-ACCEPTED start answers false and drops it (the caller continues
 * itself, or — for a head — refuses the press), and an ACCEPTED start runs the deferred
 * continuation before answering true. A settle after the answer follows it: once after true,
 * never after false.
 */
internal class StillContinuationHandoff(private val onDone: (() -> Unit)?) {
    private enum class Phase { STARTING, OWNED_BY_SETTLE, REFUSED }

    private val lock = Any()
    private var phase = Phase.STARTING
    private var deferredSettle = false
    private var ran = false

    /** The settle path's continuation: runs [onDone] at most once, and never after a false return. */
    fun settle() {
        val done = onDone ?: return
        val run = synchronized(lock) {
            when (phase) {
                Phase.STARTING -> {
                    deferredSettle = true
                    false
                }
                Phase.OWNED_BY_SETTLE -> claimLocked()
                Phase.REFUSED -> false
            }
        }
        if (run) done()
    }

    /** The dispatcher's return value for [dispatch]: true means [onDone] owns the next step. */
    fun dispatchResult(dispatch: RecordingPreNativeDispatch): Boolean {
        val accepted = dispatch == RecordingPreNativeDispatch.ACCEPTED
        val runDeferred = synchronized(lock) {
            phase = if (accepted) Phase.OWNED_BY_SETTLE else Phase.REFUSED
            accepted && deferredSettle && onDone != null && claimLocked()
        }
        if (runDeferred) checkNotNull(onDone)()
        return accepted
    }

    private fun claimLocked(): Boolean {
        if (ran) return false
        ran = true
        return true
    }
}

/**
 * The ONE wiring of a DNG pre-allocation into a still chain (AGG3-39): one
 * [StillContinuationHandoff] per shot, its [StillContinuationHandoff.settle] handed to [build] as
 * the settle path's continuation (the owner's retirement), the owner [register]ed before it starts,
 * and the chain's Boolean answered by [StillContinuationHandoff.dispatchResult] over `start()`. The
 * engine and DngPreCaptureAllocationTest both call this, so the AGG2-3 exactly-one-continuation
 * proof covers the production wiring rather than a test-side copy of it.
 */
internal fun <T : Any> dispatchDngPreCaptureAllocation(
    onDone: (() -> Unit)?,
    register: (DngPreCaptureAllocation<T>) -> Unit = {},
    build: (settle: () -> Unit) -> DngPreCaptureAllocation<T>,
): Boolean {
    val continuation = StillContinuationHandoff(onDone)
    val owner = build(continuation::settle)
    register(owner)
    return continuation.dispatchResult(owner.start())
}

/**
 * The engine's DNG owner retirement: the settle-before-camera terminal of one pre-allocation
 * (AGG4-46). Unregister the owner, release its reservations, settle the registered shot with the
 * handoff's [settle] — NEVER the chain's raw `onDone`, which is exactly the AGG2-3 defect (a
 * synchronous rejection then continued the chain twice: 2^n timelapse ticks, AEB resetting
 * mid-bracket) — and republish admission. Extracted so the test drives this production line
 * rather than a test-side `onRetired = settle` copy of it.
 */
internal fun dngOwnerRetirement(
    settle: () -> Unit,
    unregister: () -> Unit,
    releaseReservations: () -> Unit,
    settleRegisteredShot: (continuation: () -> Unit) -> Unit,
    publishAdmission: () -> Unit,
): () -> Unit = {
    unregister()
    releaseReservations()
    settleRegisteredShot(settle)
    publishAdmission()
}

internal const val DNG_PRE_CAPTURE_ALLOCATION_TIMEOUT_MS = 8_000L
