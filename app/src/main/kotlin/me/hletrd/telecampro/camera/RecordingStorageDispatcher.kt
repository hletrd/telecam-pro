package me.hletrd.telecampro.camera

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal enum class RecordingStorageDispatch {
    ACCEPTED,
    OVERFLOW,
    SHUTDOWN,
}

/**
 * Per-Engine admission facade for process-owned post-native recording storage work.
 *
 * Admission never blocks and rejection never runs the task on the submitting REC/native lane. A
 * rejected task deliberately leaves its already-finalized MediaStore row pending: launch recovery
 * is the durable overflow lane, so provider failure cannot turn into either unbounded threads or
 * deletion of valid clip bytes. Closing one facade rejects later work from that Engine without
 * interrupting accepted work or shutting down the process-lifetime capacity owner.
 */
internal class RecordingStorageDispatcher internal constructor(
    private val capacityOwner: RecordingStorageCapacityOwner,
) {
    private val admissionLock = Any()
    private var accepting = true

    /** Production constructor: all Engine generations share the one process owner. */
    constructor(workerCount: Int, backlogCapacity: Int) : this(
        ProcessRecordingStorageOwner.capacity(
            workerCount = workerCount,
            backlogCapacity = backlogCapacity,
        ),
    )

    /** Isolated capacity seam for deterministic unit tests. */
    internal constructor(
        workerCount: Int,
        backlogCapacity: Int,
        threadFactory: ThreadFactory,
    ) : this(RecordingStorageCapacityOwner(workerCount, backlogCapacity, threadFactory))

    fun dispatch(task: Runnable): RecordingStorageDispatch = synchronized(admissionLock) {
        if (!accepting) RecordingStorageDispatch.SHUTDOWN else capacityOwner.dispatch(task)
    }

    fun shutdown() {
        // The process owner deliberately stays alive. Accepted tails retain their exact Runnable
        // (and capture/callback identity) and may finish without interruption after Engine release.
        synchronized(admissionLock) { accepting = false }
    }

    internal fun activeTaskCount(): Int = capacityOwner.activeTaskCount()

    internal fun queuedTaskCount(): Int = capacityOwner.queuedTaskCount()
}

/** The only worker/queue capacity behind one or more Engine admission facades. */
internal class RecordingStorageCapacityOwner(
    workerCount: Int,
    backlogCapacity: Int,
    threadFactory: ThreadFactory = recordingStorageThreadFactory(),
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

    fun dispatch(task: Runnable): RecordingStorageDispatch = try {
        executor.execute(task)
        RecordingStorageDispatch.ACCEPTED
    } catch (_: RejectedExecutionException) {
        RecordingStorageDispatch.OVERFLOW
    }

    internal fun activeTaskCount(): Int = executor.activeCount

    internal fun queuedTaskCount(): Int = executor.queue.size
}

private fun recordingStorageThreadFactory(): ThreadFactory {
    val sequence = AtomicInteger()
    return ThreadFactory { task ->
        Thread(task, "recording-storage-${sequence.incrementAndGet()}").apply { isDaemon = true }
    }
}

internal enum class RecordingStorageTerminalDisposition {
    SAVED,
    RETAINED_PENDING,
    FAILED,
}

/** Maps recorder/provider truth without collapsing a recoverable pending row into save failure. */
internal fun recordingStorageTerminalDisposition(
    storageDisposition: me.hletrd.telecampro.video.VideoRecorder.StorageDisposition,
    hasUri: Boolean,
): RecordingStorageTerminalDisposition = when (storageDisposition) {
    me.hletrd.telecampro.video.VideoRecorder.StorageDisposition.PUBLISHED -> {
        if (hasUri) RecordingStorageTerminalDisposition.SAVED
        else RecordingStorageTerminalDisposition.FAILED
    }
    me.hletrd.telecampro.video.VideoRecorder.StorageDisposition.RETAINED_MARKER_UNAVAILABLE,
    me.hletrd.telecampro.video.VideoRecorder.StorageDisposition.RETAINED_PUBLICATION_UNAVAILABLE,
    -> RecordingStorageTerminalDisposition.RETAINED_PENDING
    me.hletrd.telecampro.video.VideoRecorder.StorageDisposition.NOT_APPLICABLE ->
        RecordingStorageTerminalDisposition.FAILED
}

/** Capture identity stays attached through provider completion and presentation. */
internal data class RecordingStorageTerminalResult<T>(
    val captureId: Int,
    val outputUri: T?,
    val disposition: RecordingStorageTerminalDisposition,
    val error: Throwable? = null,
)

/**
 * First-order monotonic fold shared by review publication and transient storage status.
 *
 * [observeCapture] advances ownership as soon as any capture is admitted. A late recording result
 * remains logged/durable at the storage layer but cannot publish either review media or a status
 * belonging to an older take. The decision and [publish] callback run under one lock: returning an
 * accepted value and publishing later would let A pause, B publish, then A overwrite B. Calling
 * [publish] twice for one result is harmless only to ordering, not intended as event deduplication;
 * the Engine calls it exactly once per terminal result.
 */
internal class RecordingStoragePresentationReducer<T> {
    private val lock = Any()
    private var newestCaptureId = Int.MIN_VALUE

    fun observeCapture(captureId: Int) {
        synchronized(lock) {
            if (captureId > newestCaptureId) newestCaptureId = captureId
        }
    }

    fun publish(
        result: RecordingStorageTerminalResult<T>,
        present: (RecordingStorageTerminalResult<T>) -> Unit,
    ): Boolean = synchronized(lock) {
        if (result.captureId < newestCaptureId) {
            false
        } else {
            newestCaptureId = result.captureId
            present(result)
            true
        }
    }

    internal fun newestCaptureId(): Int = synchronized(lock) { newestCaptureId }
}

internal const val RECORDING_STORAGE_WORKER_COUNT = 2
internal const val RECORDING_STORAGE_BACKLOG_CAPACITY = 8

/** Process-lifetime owner so Engine recreation cannot multiply blocked workers or queued tails. */
internal object ProcessRecordingStorageOwner {
    private val capacityOwner = RecordingStorageCapacityOwner(
        workerCount = RECORDING_STORAGE_WORKER_COUNT,
        backlogCapacity = RECORDING_STORAGE_BACKLOG_CAPACITY,
    )

    fun capacity(workerCount: Int, backlogCapacity: Int): RecordingStorageCapacityOwner {
        require(workerCount == RECORDING_STORAGE_WORKER_COUNT) {
            "Process recording-storage worker count must be $RECORDING_STORAGE_WORKER_COUNT"
        }
        require(backlogCapacity == RECORDING_STORAGE_BACKLOG_CAPACITY) {
            "Process recording-storage backlog must be $RECORDING_STORAGE_BACKLOG_CAPACITY"
        }
        return capacityOwner
    }
}
