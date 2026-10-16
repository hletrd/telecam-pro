package me.hletrd.telecampro.ui

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal enum class ViewModelMediaDeleteDispatch {
    ACCEPTED,
    OVERFLOW,
    SHUTDOWN,
}

/**
 * Closeable per-ViewModel admission facade over the process-lifetime review-delete lane.
 *
 * Accepted provider work keeps its exact family/URI identity after ViewModel replacement. Refused
 * work is never run inline: whole-family work already has a durable family tombstone, and rejected
 * late siblings are covered by that same tombstone until launch recovery retries them.
 */
internal class ViewModelMediaDeleteDispatcher internal constructor(
    private val capacityOwner: ViewModelMediaDeleteCapacityOwner,
) {
    private val admissionLock = Any()
    private var accepting = true

    constructor(workerCount: Int, backlogCapacity: Int) : this(
        ProcessViewModelMediaDeleteOwner.capacity(workerCount, backlogCapacity),
    )

    internal constructor(
        workerCount: Int,
        backlogCapacity: Int,
        threadFactory: ThreadFactory,
    ) : this(ViewModelMediaDeleteCapacityOwner(workerCount, backlogCapacity, threadFactory))

    fun dispatch(task: Runnable): ViewModelMediaDeleteDispatch = synchronized(admissionLock) {
        if (!accepting) ViewModelMediaDeleteDispatch.SHUTDOWN else capacityOwner.dispatch(task)
    }

    fun shutdown() {
        synchronized(admissionLock) { accepting = false }
    }

    internal fun activeTaskCount(): Int = capacityOwner.activeTaskCount()

    internal fun queuedTaskCount(): Int = capacityOwner.queuedTaskCount()
}

/** The single finite worker/queue capacity shared by every ViewModel generation. */
internal class ViewModelMediaDeleteCapacityOwner(
    workerCount: Int,
    backlogCapacity: Int,
    threadFactory: ThreadFactory = viewModelMediaDeleteThreadFactory(),
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

    fun dispatch(task: Runnable): ViewModelMediaDeleteDispatch = try {
        executor.execute(task)
        ViewModelMediaDeleteDispatch.ACCEPTED
    } catch (_: RejectedExecutionException) {
        ViewModelMediaDeleteDispatch.OVERFLOW
    }

    internal fun activeTaskCount(): Int = executor.activeCount

    internal fun queuedTaskCount(): Int = executor.queue.size
}

internal const val VIEW_MODEL_MEDIA_DELETE_WORKER_COUNT = 1
internal const val VIEW_MODEL_MEDIA_DELETE_BACKLOG_CAPACITY = 8

/** Process ownership prevents blocked MediaProvider calls multiplying across Activity recreation. */
internal object ProcessViewModelMediaDeleteOwner {
    private val capacityOwner = ViewModelMediaDeleteCapacityOwner(
        workerCount = VIEW_MODEL_MEDIA_DELETE_WORKER_COUNT,
        backlogCapacity = VIEW_MODEL_MEDIA_DELETE_BACKLOG_CAPACITY,
    )

    fun capacity(workerCount: Int, backlogCapacity: Int): ViewModelMediaDeleteCapacityOwner {
        require(workerCount == VIEW_MODEL_MEDIA_DELETE_WORKER_COUNT) {
            "Process ViewModel media-delete worker count must be $VIEW_MODEL_MEDIA_DELETE_WORKER_COUNT"
        }
        require(backlogCapacity == VIEW_MODEL_MEDIA_DELETE_BACKLOG_CAPACITY) {
            "Process ViewModel media-delete backlog must be $VIEW_MODEL_MEDIA_DELETE_BACKLOG_CAPACITY"
        }
        return capacityOwner
    }
}

private fun viewModelMediaDeleteThreadFactory(): ThreadFactory {
    val sequence = AtomicInteger()
    return ThreadFactory { task ->
        Thread(task, "vm-media-delete-${sequence.incrementAndGet()}").apply { isDaemon = true }
    }
}
