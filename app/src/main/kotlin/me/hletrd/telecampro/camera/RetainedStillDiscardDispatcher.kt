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

    /**
     * Retirement overflow is different from an ordinary retained-row discard: the durable family
     * marker is intentionally invisible to launch recovery for the rest of this process. Retain one
     * conflated process rescan signal so a worker completion rechecks every current-process marker.
     */
    fun dispatchRetirement(
        task: Runnable,
        overflowRescan: Runnable,
    ): RetainedStillDiscardDispatch = synchronized(admissionLock) {
        if (!accepting) {
            RetainedStillDiscardDispatch.SHUTDOWN
        } else {
            capacityOwner.dispatchRetirement(task, overflowRescan)
        }
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
    private val retirementRetryLock = Any()
    private var pendingRetirementRescan: Runnable? = null
    private var retirementRescanAccepted = false

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
        executor.execute(workerTask(task))
        RetainedStillDiscardDispatch.ACCEPTED
    } catch (_: RejectedExecutionException) {
        RetainedStillDiscardDispatch.OVERFLOW
    }

    /**
     * Keeps constant overflow memory: all retirement failures request the same semantic operation,
     * a fresh bounded scan of current-process durable markers. The newest request replaces the
     * pending closure, while an already accepted rescan is followed by at most one more rescan.
     */
    fun dispatchRetirement(
        task: Runnable,
        overflowRescan: Runnable,
    ): RetainedStillDiscardDispatch {
        val result = dispatch(task)
        if (result == RetainedStillDiscardDispatch.OVERFLOW) {
            synchronized(retirementRetryLock) {
                pendingRetirementRescan = overflowRescan
            }
            armRetirementRescan()
        }
        return result
    }

    internal fun activeTaskCount(): Int = executor.activeCount

    internal fun queuedTaskCount(): Int = executor.queue.size

    internal fun retirementRescanCount(): Int = synchronized(retirementRetryLock) {
        (if (pendingRetirementRescan != null) 1 else 0) +
            (if (retirementRescanAccepted) 1 else 0)
    }

    private fun workerTask(task: Runnable): Runnable = Runnable {
        try {
            task.run()
        } finally {
            // ThreadPoolExecutor moves the next queued item to this worker only after run returns.
            // A direct submission can therefore still reject here; every later completion re-arms
            // the signal until a slot is authoritatively accepted.
            armRetirementRescan()
        }
    }

    private fun armRetirementRescan() {
        val rescan = synchronized(retirementRetryLock) {
            if (retirementRescanAccepted) return
            val pending = pendingRetirementRescan ?: return
            pendingRetirementRescan = null
            retirementRescanAccepted = true
            pending
        }
        try {
            executor.execute(
                Runnable {
                    try {
                        rescan.run()
                    } finally {
                        synchronized(retirementRetryLock) {
                            retirementRescanAccepted = false
                        }
                        armRetirementRescan()
                    }
                },
            )
        } catch (_: RejectedExecutionException) {
            synchronized(retirementRetryLock) {
                retirementRescanAccepted = false
                // A newer overflow request is already a complete rescan, so preserve it. Otherwise
                // restore this signal and let the next worker completion re-arm it.
                if (pendingRetirementRescan == null) pendingRetirementRescan = rescan
            }
        }
    }
}

internal const val RETAINED_STILL_DISCARD_WORKER_COUNT = 2
internal const val RETAINED_STILL_DISCARD_BACKLOG_CAPACITY = 8

/**
 * One finite dispatch boundary for deletion retirement, including a producer that becomes terminal
 * after its old Engine facade has closed. Overflow never falls back inline; its process-conflated
 * rescan owns the in-process retry while the durable marker remains the safety boundary.
 */
internal fun dispatchDeletedFamilyRetirement(
    facade: RetainedStillDiscardDispatcher,
    task: Runnable,
    overflowRescan: Runnable,
): RetainedStillDiscardDispatch = when (val result = facade.dispatchRetirement(task, overflowRescan)) {
    RetainedStillDiscardDispatch.SHUTDOWN ->
        ProcessRetainedStillDiscardOwner.dispatchRegisteredProducerTerminal(task, overflowRescan)
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
     * finite process lane; overflow leaves the marker durable and requests the conflated rescan.
     */
    fun dispatchRegisteredProducerTerminal(
        task: Runnable,
        overflowRescan: Runnable,
    ): RetainedStillDiscardDispatch = capacityOwner.dispatchRetirement(task, overflowRescan)
}

private fun retainedStillDiscardThreadFactory(): ThreadFactory {
    val sequence = AtomicInteger()
    return ThreadFactory { task ->
        Thread(task, "retained-still-discard-${sequence.incrementAndGet()}").apply {
            isDaemon = true
        }
    }
}
