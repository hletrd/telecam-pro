package me.hletrd.telecampro.camera

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal enum class RecordingPreNativeDispatch {
    ACCEPTED,
    OVERFLOW,
    SHUTDOWN,
}

internal fun interface RecordingPreNativeCancellation {
    fun cancel()
}

internal data class RecordingPreNativeSubmission(
    val dispatch: RecordingPreNativeDispatch,
    val cancellation: RecordingPreNativeCancellation? = null,
)

/**
 * Finite lane for MediaProvider work that must finish before recorder-native setup can begin.
 *
 * Provider Binder calls have no cancellation signal. Cancellation therefore removes a queued task
 * when possible and makes an already-running task's eventual return stale; it never interrupts a
 * provider call. Fixed workers plus a bounded queue prevent repeated outages from creating an
 * unbounded thread population.
 */
internal class RecordingPreNativeAllocationDispatcher(
    workerCount: Int,
    backlogCapacity: Int,
    threadFactory: ThreadFactory = recordingPreNativeThreadFactory(),
) {
    private val executor: ThreadPoolExecutor

    init {
        require(workerCount > 0)
        require(backlogCapacity > 0)
        executor = ThreadPoolExecutor(
            workerCount,
            workerCount,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(backlogCapacity),
            threadFactory,
            ThreadPoolExecutor.AbortPolicy(),
        )
    }

    fun dispatch(task: () -> Unit): RecordingPreNativeSubmission {
        val future = FutureTask(task, Unit)
        return try {
            executor.execute(future)
            RecordingPreNativeSubmission(
                dispatch = RecordingPreNativeDispatch.ACCEPTED,
                cancellation = RecordingPreNativeCancellation {
                    future.cancel(false)
                    executor.remove(future)
                },
            )
        } catch (_: RejectedExecutionException) {
            RecordingPreNativeSubmission(
                dispatch = if (executor.isShutdown) {
                    RecordingPreNativeDispatch.SHUTDOWN
                } else {
                    RecordingPreNativeDispatch.OVERFLOW
                },
            )
        }
    }

    fun shutdown() {
        // Running provider calls are uncancellable. Daemon workers may return later and their
        // attempt owner will classify the result stale; queued tasks are canceled by retirement.
        executor.shutdown()
    }

    internal fun activeTaskCount(): Int = executor.activeCount

    internal fun queuedTaskCount(): Int = executor.queue.size
}

private fun recordingPreNativeThreadFactory(): ThreadFactory {
    val sequence = AtomicInteger()
    return ThreadFactory { task ->
        Thread(task, "recording-pre-native-${sequence.incrementAndGet()}").apply { isDaemon = true }
    }
}

internal enum class RecordingPreNativeDelivery {
    READY,
    FAILED,
    STALE,
}

internal enum class RecorderSetupFinalization { PENDING, RELEASED, QUARANTINED }

internal data class RecorderSetupQuarantine<T>(
    val claimed: Boolean,
    val resource: T?,
)

/**
 * Release-visible owner for the gap between allocation claim and recorder publication.
 *
 * The resource is bound before the first vendor-native setup call. Release can therefore either
 * observe a completed transfer/cleanup or atomically revoke setup and retain the exact native owner.
 */
internal class RecorderSetupFinalizationOwner<T : Any> {
    private val lock = Any()
    private val terminal = CountDownLatch(1)
    private var state = RecorderSetupFinalization.PENDING
    private var resource: T? = null

    fun bind(value: T): Boolean = synchronized(lock) {
        if (state != RecorderSetupFinalization.PENDING || resource != null) return false
        resource = value
        true
    }

    fun release(): Boolean = classify(RecorderSetupFinalization.RELEASED)

    fun quarantine(): RecorderSetupQuarantine<T> {
        val result = synchronized(lock) {
            if (state != RecorderSetupFinalization.PENDING) {
                RecorderSetupQuarantine<T>(claimed = false, resource = null)
            } else {
                state = RecorderSetupFinalization.QUARANTINED
                RecorderSetupQuarantine(claimed = true, resource = resource)
            }
        }
        if (result.claimed) terminal.countDown()
        return result
    }

    fun await(timeout: Long, unit: TimeUnit): RecorderSetupFinalization {
        if (current() == RecorderSetupFinalization.PENDING) {
            try {
                terminal.await(timeout.coerceAtLeast(0L), unit)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        return current()
    }

    fun current(): RecorderSetupFinalization = synchronized(lock) { state }

    private fun classify(candidate: RecorderSetupFinalization): Boolean {
        require(candidate != RecorderSetupFinalization.PENDING)
        val changed = synchronized(lock) {
            if (state != RecorderSetupFinalization.PENDING) false else {
                state = candidate
                true
            }
        }
        if (changed) terminal.countDown()
        return changed
    }
}

/**
 * First-wins owner for one pending-video allocation.
 *
 * [retire] is shared by Stop, timeout, lifecycle pause/release, and dispatch rejection. It releases
 * admission through [onRetired] without waiting for provider work. A value delivered after that
 * edge, or one allocated just before retirement, is handed to [onLateValue] exactly once and can
 * never be claimed for native setup.
 */
internal class RecordingPreNativeAllocationAttempt<T : Any>(
    private val onRetired: () -> Unit,
    private val onLateValue: (T) -> Unit,
) {
    private enum class State { WAITING, ALLOCATED, CLAIMED, RETIRED }

    private val lock = Any()
    private var state = State.WAITING
    private var allocated: T? = null
    private var cancellation: RecordingPreNativeCancellation? = null

    fun attachCancellation(value: RecordingPreNativeCancellation) {
        val cancelNow = synchronized(lock) {
            when (state) {
                State.WAITING -> {
                    cancellation = value
                    false
                }
                State.RETIRED -> true
                State.ALLOCATED, State.CLAIMED -> false
            }
        }
        if (cancelNow) value.cancel()
    }

    fun deliver(
        result: Result<T?>,
        onRetirementClaimed: () -> Unit = {},
    ): RecordingPreNativeDelivery {
        var late: T? = null
        var retire = false
        val delivery = synchronized(lock) {
            if (state != State.WAITING) {
                late = result.getOrNull()
                RecordingPreNativeDelivery.STALE
            } else {
                val value = result.getOrNull()
                if (value == null) {
                    state = State.RETIRED
                    cancellation = null
                    retire = true
                    RecordingPreNativeDelivery.FAILED
                } else {
                    allocated = value
                    state = State.ALLOCATED
                    cancellation = null
                    RecordingPreNativeDelivery.READY
                }
            }
        }
        late?.let(onLateValue)
        if (retire) completeRetirement(onRetirementClaimed)
        return delivery
    }

    /** Transfers the allocated value to native setup only while this exact attempt still owns it. */
    fun claim(): T? = synchronized(lock) {
        if (state != State.ALLOCATED) return null
        state = State.CLAIMED
        allocated.also { allocated = null }
    }

    /** Returns true only for the edge that retired this attempt and released its admission owners. */
    fun retire(onRetirementClaimed: () -> Unit = {}): Boolean {
        var late: T? = null
        var cancel: RecordingPreNativeCancellation? = null
        val retired = synchronized(lock) {
            if (state == State.CLAIMED || state == State.RETIRED) {
                false
            } else {
                state = State.RETIRED
                late = allocated
                allocated = null
                cancel = cancellation
                cancellation = null
                true
            }
        }
        if (!retired) return false
        cancel?.cancel()
        try {
            late?.let(onLateValue)
        } finally {
            // Storage cleanup is best-effort work over already-durable recovery truth. It must
            // never suppress the terminal that releases DNG/REC admission and higher-level owners.
            completeRetirement(onRetirementClaimed)
        }
        return true
    }

    /** Publishes winner-only terminal effects before releasing result/admission observers. */
    private fun completeRetirement(onRetirementClaimed: () -> Unit) {
        try {
            onRetirementClaimed()
        } finally {
            onRetired()
        }
    }

    internal fun isRetired(): Boolean = synchronized(lock) { state == State.RETIRED }
}

internal const val RECORDING_PRE_NATIVE_WORKER_COUNT = 2
internal const val RECORDING_PRE_NATIVE_BACKLOG_CAPACITY = 4

/**
 * Process-lifetime capacity owner for MediaProvider allocation that must precede native/camera work.
 * Recording and DNG share this one worker/queue ceiling, so Engine replacement or simultaneous
 * capture types cannot multiply blocked Binder calls.
 */
internal object ProcessPreNativeMediaAllocator {
    private val dispatcher = RecordingPreNativeAllocationDispatcher(
        workerCount = RECORDING_PRE_NATIVE_WORKER_COUNT,
        backlogCapacity = RECORDING_PRE_NATIVE_BACKLOG_CAPACITY,
    )

    fun dispatch(task: () -> Unit): RecordingPreNativeSubmission = dispatcher.dispatch(task)
}

/** Existing recording-facing name retained as a narrow facade over the shared process owner. */
internal object ProcessRecordingPreNativeAllocator {
    fun dispatch(task: () -> Unit): RecordingPreNativeSubmission =
        ProcessPreNativeMediaAllocator.dispatch(task)
}
