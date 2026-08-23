package me.hletrd.telecampro.camera

import java.util.concurrent.ArrayBlockingQueue
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

    fun deliver(result: Result<T?>): RecordingPreNativeDelivery {
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
        if (retire) onRetired()
        return delivery
    }

    /** Transfers the allocated value to native setup only while this exact attempt still owns it. */
    fun claim(): T? = synchronized(lock) {
        if (state != State.ALLOCATED) return null
        state = State.CLAIMED
        allocated.also { allocated = null }
    }

    /** Returns true only for the edge that retired this attempt and released its admission owners. */
    fun retire(): Boolean {
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
        late?.let(onLateValue)
        onRetired()
        return true
    }

    internal fun isRetired(): Boolean = synchronized(lock) { state == State.RETIRED }
}

internal const val RECORDING_PRE_NATIVE_WORKER_COUNT = 2
internal const val RECORDING_PRE_NATIVE_BACKLOG_CAPACITY = 4

/** Process-lifetime capacity owner so repeated Engine recreation cannot multiply blocked workers. */
internal object ProcessRecordingPreNativeAllocator {
    private val dispatcher = RecordingPreNativeAllocationDispatcher(
        workerCount = RECORDING_PRE_NATIVE_WORKER_COUNT,
        backlogCapacity = RECORDING_PRE_NATIVE_BACKLOG_CAPACITY,
    )

    fun dispatch(task: () -> Unit): RecordingPreNativeSubmission = dispatcher.dispatch(task)
}
