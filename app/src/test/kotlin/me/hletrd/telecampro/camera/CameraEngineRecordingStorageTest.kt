package me.hletrd.telecampro.camera

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import me.hletrd.telecampro.ui.RobolectricEglSentinels
import me.hletrd.telecampro.video.VideoRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Production CameraEngine composition coverage for the post-native storage owner. */
@RunWith(RobolectricTestRunner::class)
class CameraEngineRecordingStorageTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    init {
        RobolectricEglSentinels.ensure()
    }

    @Test
    fun `Engine generations share one bound and preserve tail plus callback ownership`() {
        val releaseWorkers = CountDownLatch(1)
        val workersEntered = CountDownLatch(RECORDING_STORAGE_WORKER_COUNT)
        val oldTaskCount = RECORDING_STORAGE_WORKER_COUNT + RECORDING_STORAGE_BACKLOG_CAPACITY
        val oldTasksFinished = CountDownLatch(oldTaskCount)
        val replacementFinished = CountDownLatch(1)
        val replacementCallback = CountDownLatch(1)
        val executedTails = CopyOnWriteArrayList<String>()
        val oldMediaCallbacks = CopyOnWriteArrayList<Uri>()
        val replacementMediaCallbacks = CopyOnWriteArrayList<Uri>()
        val oldStatuses = CopyOnWriteArrayList<CameraStatusMessage>()
        val replacementStatuses = CopyOnWriteArrayList<CameraStatusMessage>()
        val overflowRan = AtomicBoolean()
        val createdThreads = AtomicInteger()
        val owner = RecordingStorageCapacityOwner(
            workerCount = RECORDING_STORAGE_WORKER_COUNT,
            backlogCapacity = RECORDING_STORAGE_BACKLOG_CAPACITY,
            threadFactory = ThreadFactory { task ->
                Thread(task, "engine-storage-${createdThreads.incrementAndGet()}").apply {
                    isDaemon = true
                }
            },
        )
        val override = RecordingStorageEngineOverrides(owner)
        val oldEngine = CameraEngine(app, recordingStorageOverrides = override)
        val replacementEngine = CameraEngine(app, recordingStorageOverrides = override)
        oldEngine.onMediaSaved = { uri, _ -> oldMediaCallbacks += uri }
        replacementEngine.onMediaSaved = { uri, _ ->
            replacementMediaCallbacks += uri
            replacementCallback.countDown()
        }
        oldEngine.onStatus = { it?.message?.let(oldStatuses::add) }
        replacementEngine.onStatus = { it?.message?.let(replacementStatuses::add) }

        fun oldTail(id: Int, blocked: Boolean) = RecordingStorageCompletion {
            if (blocked) {
                workersEntered.countDown()
                releaseWorkers.await()
            }
            executedTails += "old-$id"
            oldTasksFinished.countDown()
            VideoRecorder.StopResult(saved = true)
        }

        try {
            repeat(RECORDING_STORAGE_WORKER_COUNT) { id ->
                assertEquals(
                    RecordingStorageDispatch.ACCEPTED,
                    oldEngine.dispatchRecordingStorageTail(
                        oldTail(id, blocked = true),
                        Uri.parse("content://old/$id"),
                        captureId = id,
                    ),
                )
            }
            assertTrue(workersEntered.await(5, TimeUnit.SECONDS))
            repeat(RECORDING_STORAGE_BACKLOG_CAPACITY) { offset ->
                val id = RECORDING_STORAGE_WORKER_COUNT + offset
                assertEquals(
                    RecordingStorageDispatch.ACCEPTED,
                    oldEngine.dispatchRecordingStorageTail(
                        oldTail(id, blocked = false),
                        Uri.parse("content://old/$id"),
                        captureId = id,
                    ),
                )
            }
            assertEquals(RECORDING_STORAGE_WORKER_COUNT, owner.activeTaskCount())
            assertEquals(RECORDING_STORAGE_BACKLOG_CAPACITY, owner.queuedTaskCount())
            assertEquals(RECORDING_STORAGE_WORKER_COUNT, createdThreads.get())

            // A real replacement Engine reaches the shared admission boundary and returns without
            // running the provider continuation inline or waiting for old-generation workers.
            val attempted = CountDownLatch(1)
            val returned = CountDownLatch(1)
            val overflowResult = AtomicReference<RecordingStorageDispatch>()
            Thread {
                attempted.countDown()
                overflowResult.set(
                    replacementEngine.dispatchRecordingStorageTail(
                        RecordingStorageCompletion {
                            overflowRan.set(true)
                            VideoRecorder.StopResult(saved = true)
                        },
                        Uri.parse("content://replacement/overflow"),
                        captureId = 500,
                    ),
                )
                returned.countDown()
            }.start()
            assertTrue(attempted.await(5, TimeUnit.SECONDS))
            assertTrue(returned.await(5, TimeUnit.SECONDS))
            assertEquals(RecordingStorageDispatch.OVERFLOW, overflowResult.get())
            assertFalse(overflowRan.get())
            assertEquals(listOf(CameraStatusMessage.VIDEO_SAVE_DELAYED), replacementStatuses.toList())
            assertTrue(oldStatuses.isEmpty())

            // Closing the old facade cannot interrupt its accepted tails or shut down the shared
            // owner, and it rejects any later old-Engine submission deterministically.
            oldEngine.release()
            assertEquals(
                RecordingStorageDispatch.SHUTDOWN,
                oldEngine.dispatchRecordingStorageTail(
                    RecordingStorageCompletion { VideoRecorder.StopResult(saved = true) },
                    Uri.parse("content://old/after-release"),
                    captureId = 600,
                ),
            )

            releaseWorkers.countDown()
            assertTrue(oldTasksFinished.await(5, TimeUnit.SECONDS))
            assertEquals(oldTaskCount, executedTails.size)
            assertTrue(replacementMediaCallbacks.isEmpty())
            assertTrue(oldMediaCallbacks.isNotEmpty())
            assertTrue(oldMediaCallbacks.all { it.authority == "old" })

            assertEquals(
                RecordingStorageDispatch.ACCEPTED,
                replacementEngine.dispatchRecordingStorageTail(
                    RecordingStorageCompletion {
                        executedTails += "replacement"
                        replacementFinished.countDown()
                        VideoRecorder.StopResult(saved = true)
                    },
                    Uri.parse("content://replacement/saved"),
                    captureId = 1_000,
                ),
            )
            assertTrue(replacementFinished.await(5, TimeUnit.SECONDS))
            assertTrue(replacementCallback.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(Uri.parse("content://replacement/saved")), replacementMediaCallbacks.toList())
            assertTrue(oldMediaCallbacks.none { it.authority == "replacement" })
            assertEquals(RECORDING_STORAGE_WORKER_COUNT, createdThreads.get())
        } finally {
            releaseWorkers.countDown()
            runCatching { oldEngine.release() }
            replacementEngine.release()
        }
    }
}
