package me.hletrd.telecampro.camera

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal enum class RetainedStillDiscardDispatch {
    ACCEPTED,
    OVERFLOW,
    SHUTDOWN,
}

/**
 * Per-Engine admission facade over the one process-lifetime deleted-still provider lane.
 *
 * Engine shutdown rejects only new work from that stale facade. Accepted tasks retain their exact
 * deletion-owner identity and finish on the shared finite capacity; neither overflow nor shutdown
 * runs provider work inline. The already-durable capture-family tombstone remains launch recovery's
 * owner when admission is refused.
 */
internal class RetainedStillDiscardDispatcher internal constructor(
    private val capacityOwner: RetainedStillDiscardCapacityOwner,
) {
    private val admissionLock = Any()
    private var accepting = true

    constructor(workerCount: Int, backlogCapacity: Int) : this(
        ProcessRetainedStillDiscardOwner.capacity(workerCount, backlogCapacity),
    )

    internal constructor(
        workerCount: Int,
        backlogCapacity: Int,
        threadFactory: ThreadFactory,
    ) : this(RetainedStillDiscardCapacityOwner(workerCount, backlogCapacity, threadFactory))

    fun dispatch(task: Runnable): RetainedStillDiscardDispatch = synchronized(admissionLock) {
        if (!accepting) RetainedStillDiscardDispatch.SHUTDOWN else capacityOwner.dispatch(task)
    }

    fun shutdown() {
        synchronized(admissionLock) { accepting = false }
    }

    internal fun activeTaskCount(): Int = capacityOwner.activeTaskCount()

    internal fun queuedTaskCount(): Int = capacityOwner.queuedTaskCount()
}

/** The only worker/queue capacity behind every Engine's teardown-discard facade. */
internal class RetainedStillDiscardCapacityOwner(
    workerCount: Int,
    backlogCapacity: Int,
    threadFactory: ThreadFactory = retainedStillDiscardThreadFactory(),
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

    fun dispatch(task: Runnable): RetainedStillDiscardDispatch = try {
        executor.execute(task)
        RetainedStillDiscardDispatch.ACCEPTED
    } catch (_: RejectedExecutionException) {
        RetainedStillDiscardDispatch.OVERFLOW
    }

    internal fun activeTaskCount(): Int = executor.activeCount

    internal fun queuedTaskCount(): Int = executor.queue.size
}

internal const val RETAINED_STILL_DISCARD_WORKER_COUNT = 2
internal const val RETAINED_STILL_DISCARD_BACKLOG_CAPACITY = 8

/**
 * One finite dispatch boundary for deletion retirement, including a producer that becomes terminal
 * after its old Engine facade has closed. Live overflow deliberately does not fall back elsewhere:
 * a durable marker remains launch recovery's owner, and an absent marker needs no retirement work.
 */
internal fun dispatchDeletedFamilyRetirement(
    facade: RetainedStillDiscardDispatcher,
    task: Runnable,
): RetainedStillDiscardDispatch = when (val result = facade.dispatch(task)) {
    RetainedStillDiscardDispatch.SHUTDOWN ->
        ProcessRetainedStillDiscardOwner.dispatchRegisteredProducerTerminal(task)
    else -> result
}

/** Process-lifetime capacity prevents blocked ContentResolver calls multiplying with Engines. */
internal object ProcessRetainedStillDiscardOwner {
    private val capacityOwner = RetainedStillDiscardCapacityOwner(
        workerCount = RETAINED_STILL_DISCARD_WORKER_COUNT,
        backlogCapacity = RETAINED_STILL_DISCARD_BACKLOG_CAPACITY,
    )

    fun capacity(workerCount: Int, backlogCapacity: Int): RetainedStillDiscardCapacityOwner {
        require(workerCount == RETAINED_STILL_DISCARD_WORKER_COUNT) {
            "Process retained-still discard worker count must be $RETAINED_STILL_DISCARD_WORKER_COUNT"
        }
        require(backlogCapacity == RETAINED_STILL_DISCARD_BACKLOG_CAPACITY) {
            "Process retained-still discard backlog must be $RETAINED_STILL_DISCARD_BACKLOG_CAPACITY"
        }
        return capacityOwner
    }

    /**
     * A still-family producer registered before Engine release may become terminal only after its
     * old facade closes. Admit that already-owned retirement continuation directly to the same
     * finite process lane; overflow leaves the durable family marker for launch recovery.
     */
    fun dispatchRegisteredProducerTerminal(task: Runnable): RetainedStillDiscardDispatch =
        capacityOwner.dispatch(task)
}

private fun retainedStillDiscardThreadFactory(): ThreadFactory {
    val sequence = AtomicInteger()
    return ThreadFactory { task ->
        Thread(task, "retained-still-discard-${sequence.incrementAndGet()}").apply {
            isDaemon = true
        }
    }
}
