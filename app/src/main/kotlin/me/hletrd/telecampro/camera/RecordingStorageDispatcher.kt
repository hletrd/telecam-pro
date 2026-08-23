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
 * Finite owner for post-native recording storage work.
 *
 * Admission never blocks and rejection never runs the task on the submitting REC/native lane. A
 * rejected task deliberately leaves its already-finalized MediaStore row pending: launch recovery
 * is the durable overflow lane, so provider failure cannot turn into either unbounded threads or
 * deletion of valid clip bytes.
 */
internal class RecordingStorageDispatcher(
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
        if (executor.isShutdown) RecordingStorageDispatch.SHUTDOWN else RecordingStorageDispatch.OVERFLOW
    }

    fun shutdown() {
        // Accepted tails remain owned and may finish. shutdownNow() would interrupt provider calls
        // and make their completion disposition ambiguous during ordinary Activity teardown.
        executor.shutdown()
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
