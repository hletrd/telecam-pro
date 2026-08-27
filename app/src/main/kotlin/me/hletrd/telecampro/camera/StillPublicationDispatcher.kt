package me.hletrd.telecampro.camera

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal enum class StillPublicationDispatch {
    ACCEPTED,
    OVERFLOW,
    SHUTDOWN,
}

/** Where a completed DNG enters the process-finite publication owner. */
internal enum class DngPublicationTransfer {
    NONE,
    DIRECT,
    AFTER_PROCESSED,
}

/**
 * Every completed DNG is process-owned. Mixed output waits behind its processed sibling on the
 * Engine's ordered save lane; RAW-only output has no sibling and transfers immediately.
 */
internal fun dngPublicationTransfer(formats: PhotoFormats): DngPublicationTransfer = when {
    !formats.dngRaw -> DngPublicationTransfer.NONE
    formats.wantsProcessedStill -> DngPublicationTransfer.AFTER_PROCESSED
    else -> DngPublicationTransfer.DIRECT
}

/**
 * Transfers one completed DNG either now or through the processed sibling's ordered executor.
 *
 * [enqueueAfterProcessed] must enqueue behind that sibling's already-submitted save. A rejected
 * transfer never runs [publication] inline: [onTransferRejected] leaves the complete private row
 * to launch recovery and the caller settles its family continuation.
 */
internal fun transferCompletedDngPublication(
    order: DngPublicationTransfer,
    enqueueAfterProcessed: (Runnable) -> Boolean,
    publication: () -> Unit,
    onTransferRejected: () -> Unit,
): Boolean = when (order) {
    DngPublicationTransfer.NONE -> false
    DngPublicationTransfer.DIRECT -> {
        publication()
        true
    }
    DngPublicationTransfer.AFTER_PROCESSED -> {
        val accepted = enqueueAfterProcessed(Runnable(publication))
        if (!accepted) onTransferRejected()
        accepted
    }
}

/**
 * Per-Engine admission facade over the process-lifetime completed-DNG publication capacity.
 *
 * DNG bytes and their bounded COMPLETE-marker result already exist before work reaches this seam.
 * Rejection therefore never runs publication inline and never deletes the private row: launch
 * recovery is the durable overflow owner. Accepted work keeps the originating Engine's exact
 * callback/family identity after that Engine closes, while a stale facade cannot add more work.
 */
internal class StillPublicationDispatcher internal constructor(
    private val capacityOwner: StillPublicationCapacityOwner,
) {
    private val admissionLock = Any()
    private var accepting = true

    constructor(workerCount: Int, backlogCapacity: Int) : this(
        ProcessStillPublicationOwner.capacity(workerCount, backlogCapacity),
    )

    /** Isolated capacity seam for deterministic unit tests. */
    internal constructor(
        workerCount: Int,
        backlogCapacity: Int,
        threadFactory: ThreadFactory,
    ) : this(StillPublicationCapacityOwner(workerCount, backlogCapacity, threadFactory))

    fun dispatch(task: Runnable): StillPublicationDispatch = synchronized(admissionLock) {
        if (!accepting) StillPublicationDispatch.SHUTDOWN else capacityOwner.dispatch(task)
    }

    /**
     * Transfers one live capture-family continuation either to finite publication capacity or to
     * recovery. [onTerminal] is delivered exactly once even if publication or rejection reporting
     * throws, so neither path can retain the family owner indefinitely.
     */
    fun dispatchRecoverable(
        publication: () -> Unit,
        onRejected: (StillPublicationDispatch) -> Unit,
        onTerminal: () -> Unit,
    ): StillPublicationDispatch {
        val terminalDelivered = AtomicBoolean(false)
        val terminalOnce = {
            if (terminalDelivered.compareAndSet(false, true)) onTerminal()
            Unit
        }
        val disposition = dispatch(
            Runnable {
                try {
                    publication()
                } finally {
                    terminalOnce()
                }
            },
        )
        if (disposition != StillPublicationDispatch.ACCEPTED) {
            try {
                onRejected(disposition)
            } finally {
                terminalOnce()
            }
        }
        return disposition
    }

    fun shutdown() {
        // Accepted tails remain process-owned and finish with their original callback identity.
        synchronized(admissionLock) { accepting = false }
    }

    internal fun activeTaskCount(): Int = capacityOwner.activeTaskCount()

    internal fun queuedTaskCount(): Int = capacityOwner.queuedTaskCount()
}

/** The only active+queued completed-DNG publication capacity across all Engine generations. */
internal class StillPublicationCapacityOwner(
    workerCount: Int,
    backlogCapacity: Int,
    threadFactory: ThreadFactory = stillPublicationThreadFactory(),
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

    fun dispatch(task: Runnable): StillPublicationDispatch = try {
        executor.execute(task)
        StillPublicationDispatch.ACCEPTED
    } catch (_: RejectedExecutionException) {
        StillPublicationDispatch.OVERFLOW
    }

    internal fun activeTaskCount(): Int = executor.activeCount

    internal fun queuedTaskCount(): Int = executor.queue.size
}

internal const val STILL_PUBLICATION_WORKER_COUNT = 2
internal const val STILL_PUBLICATION_BACKLOG_CAPACITY = 2

/** Process lifetime is the reclamation boundary for a provider call that never returns. */
internal object ProcessStillPublicationOwner {
    private val capacityOwner = StillPublicationCapacityOwner(
        workerCount = STILL_PUBLICATION_WORKER_COUNT,
        backlogCapacity = STILL_PUBLICATION_BACKLOG_CAPACITY,
    )

    fun capacity(workerCount: Int, backlogCapacity: Int): StillPublicationCapacityOwner {
        require(workerCount == STILL_PUBLICATION_WORKER_COUNT) {
            "Process still-publication worker count must be $STILL_PUBLICATION_WORKER_COUNT"
        }
        require(backlogCapacity == STILL_PUBLICATION_BACKLOG_CAPACITY) {
            "Process still-publication backlog must be $STILL_PUBLICATION_BACKLOG_CAPACITY"
        }
        return capacityOwner
    }
}

private fun stillPublicationThreadFactory(): ThreadFactory {
    val sequence = AtomicInteger()
    return ThreadFactory { task ->
        Thread(task, "still-publication-${sequence.incrementAndGet()}").apply { isDaemon = true }
    }
}
