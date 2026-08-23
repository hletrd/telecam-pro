package me.hletrd.telecampro.camera

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import me.hletrd.telecampro.video.VideoRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStorageDispatcherTest {
    @Test
    fun `saturation keeps a strict worker and backlog bound without blocking REC lane`() {
        val releaseWorkers = CountDownLatch(1)
        val workersEntered = CountDownLatch(2)
        val createdThreads = AtomicInteger()
        val overflowRan = AtomicBoolean()
        val dispatcher = RecordingStorageDispatcher(
            workerCount = 2,
            backlogCapacity = 2,
            threadFactory = ThreadFactory { task ->
                Thread(task, "test-recording-storage-${createdThreads.incrementAndGet()}")
            },
        )
        val recLane = Executors.newSingleThreadExecutor()
        val recLaneFree = CountDownLatch(1)

        try {
            repeat(2) {
                assertEquals(
                    RecordingStorageDispatch.ACCEPTED,
                    dispatcher.dispatch(
                        Runnable {
                            workersEntered.countDown()
                            releaseWorkers.await()
                        },
                    ),
                )
            }
            assertTrue(workersEntered.await(5, TimeUnit.SECONDS))
            repeat(2) {
                assertEquals(
                    RecordingStorageDispatch.ACCEPTED,
                    dispatcher.dispatch(Runnable {}),
                )
            }

            recLane.execute {
                assertEquals(
                    RecordingStorageDispatch.OVERFLOW,
                    dispatcher.dispatch(Runnable { overflowRan.set(true) }),
                )
                // Represents next-REC admission on the same serial native lane. If overflow ran
                // inline (or admission blocked), this marker could not be reached.
                recLaneFree.countDown()
            }

            assertTrue(recLaneFree.await(5, TimeUnit.SECONDS))
            assertEquals(2, dispatcher.activeTaskCount())
            assertEquals(2, dispatcher.queuedTaskCount())
            assertEquals(2, createdThreads.get())
            assertFalse(overflowRan.get())
        } finally {
            releaseWorkers.countDown()
            recLane.shutdownNow()
            dispatcher.shutdown()
        }
    }

    @Test
    fun `overflow work is left to an explicit recovery owner and is never lost or run inline`() {
        val releaseWorker = CountDownLatch(1)
        val workerEntered = CountDownLatch(1)
        val overflowRan = AtomicBoolean()
        val dispatcher = RecordingStorageDispatcher(workerCount = 1, backlogCapacity = 1)

        try {
            assertEquals(
                RecordingStorageDispatch.ACCEPTED,
                dispatcher.dispatch(
                    Runnable {
                        workerEntered.countDown()
                        releaseWorker.await()
                    },
                ),
            )
            assertTrue(workerEntered.await(5, TimeUnit.SECONDS))
            assertEquals(RecordingStorageDispatch.ACCEPTED, dispatcher.dispatch(Runnable {}))
            val overflow = Runnable { overflowRan.set(true) }

            assertEquals(RecordingStorageDispatch.OVERFLOW, dispatcher.dispatch(overflow))
            assertFalse(overflowRan.get())

            // Mirrors next-launch MediaStore recovery claiming the retained pending row.
            releaseWorker.countDown()
            val recovered = CountDownLatch(1)
            val recoveryOwner = RecordingStorageDispatcher(workerCount = 1, backlogCapacity = 1)
            try {
                assertEquals(
                    RecordingStorageDispatch.ACCEPTED,
                    recoveryOwner.dispatch(Runnable { overflow.run(); recovered.countDown() }),
                )
                assertTrue(recovered.await(5, TimeUnit.SECONDS))
                assertTrue(overflowRan.get())
            } finally {
                recoveryOwner.shutdown()
            }
        } finally {
            releaseWorker.countDown()
            dispatcher.shutdown()
        }
    }

    @Test
    fun `single worker preserves accepted backlog order`() {
        val releaseWorker = CountDownLatch(1)
        val workerEntered = CountDownLatch(1)
        val finished = CountDownLatch(3)
        val order = CopyOnWriteArrayList<Int>()
        val dispatcher = RecordingStorageDispatcher(workerCount = 1, backlogCapacity = 3)

        try {
            assertEquals(
                RecordingStorageDispatch.ACCEPTED,
                dispatcher.dispatch(
                    Runnable {
                        workerEntered.countDown()
                        releaseWorker.await()
                        order += 0
                        finished.countDown()
                    },
                ),
            )
            assertTrue(workerEntered.await(5, TimeUnit.SECONDS))
            for (id in 1..2) {
                assertEquals(
                    RecordingStorageDispatch.ACCEPTED,
                    dispatcher.dispatch(Runnable { order += id; finished.countDown() }),
                )
            }
            releaseWorker.countDown()

            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(0, 1, 2), order.toList())
        } finally {
            releaseWorker.countDown()
            dispatcher.shutdown()
        }
    }

    @Test
    fun `shutdown rejection never starts or inlines provider work`() {
        val ran = AtomicBoolean()
        val dispatcher = RecordingStorageDispatcher(workerCount = 1, backlogCapacity = 1)
        dispatcher.shutdown()

        assertEquals(
            RecordingStorageDispatch.SHUTDOWN,
            dispatcher.dispatch(Runnable { ran.set(true) }),
        )
        assertFalse(ran.get())
        assertEquals(0, dispatcher.activeTaskCount())
        assertEquals(0, dispatcher.queuedTaskCount())
    }

    @Test
    fun `newer terminal owns media and status regardless of A B completion order`() {
        val a = terminal(10, RecordingStorageTerminalDisposition.FAILED)
        val b = terminal(11, RecordingStorageTerminalDisposition.SAVED)

        RecordingStoragePresentationReducer<String>().also { reducer ->
            reducer.observeCapture(10)
            reducer.observeCapture(11)
            val presented = mutableListOf<RecordingStorageTerminalResult<String>>()
            assertTrue(reducer.publish(b, presented::add))
            assertFalse(reducer.publish(a, presented::add))
            assertEquals(listOf(b), presented)
        }
        RecordingStoragePresentationReducer<String>().also { reducer ->
            reducer.observeCapture(10)
            val presented = mutableListOf<RecordingStorageTerminalResult<String>>()
            assertTrue(reducer.publish(a, presented::add))
            reducer.observeCapture(11)
            assertTrue(reducer.publish(b, presented::add))
            assertEquals(listOf(a, b), presented)
            assertEquals(11, reducer.newestCaptureId())
        }
    }

    @Test
    fun `newer failure suppresses older success in either completion order`() {
        val a = terminal(20, RecordingStorageTerminalDisposition.SAVED)
        val b = terminal(21, RecordingStorageTerminalDisposition.FAILED)

        RecordingStoragePresentationReducer<String>().also { reducer ->
            reducer.observeCapture(20)
            reducer.observeCapture(21)
            val presented = mutableListOf<RecordingStorageTerminalResult<String>>()
            assertTrue(reducer.publish(b, presented::add))
            assertFalse(reducer.publish(a, presented::add))
            assertEquals(listOf(b), presented)
        }
        RecordingStoragePresentationReducer<String>().also { reducer ->
            reducer.observeCapture(20)
            val presented = mutableListOf<RecordingStorageTerminalResult<String>>()
            assertTrue(reducer.publish(a, presented::add))
            reducer.observeCapture(21)
            assertTrue(reducer.publish(b, presented::add))
            assertEquals(listOf(a, b), presented)
        }
    }

    @Test
    fun `active C owns presentation before its storage tail exists`() {
        val reducer = RecordingStoragePresentationReducer<String>()
        reducer.observeCapture(30)
        reducer.observeCapture(31)
        reducer.observeCapture(32)

        val presented = mutableListOf<RecordingStorageTerminalResult<String>>()
        assertFalse(
            reducer.publish(terminal(30, RecordingStorageTerminalDisposition.FAILED), presented::add),
        )
        assertFalse(
            reducer.publish(terminal(31, RecordingStorageTerminalDisposition.SAVED), presented::add),
        )
        val c = terminal(32, RecordingStorageTerminalDisposition.RETAINED_PENDING)
        assertTrue(reducer.publish(c, presented::add))
        assertEquals(listOf(c), presented)
    }

    @Test
    fun `terminal decision and callback are serialized against a newer completion`() {
        val reducer = RecordingStoragePresentationReducer<String>()
        val aCallbackEntered = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val bPublished = CountDownLatch(1)
        val order = CopyOnWriteArrayList<Int>()
        val a = Thread {
            reducer.publish(terminal(40, RecordingStorageTerminalDisposition.FAILED)) {
                aCallbackEntered.countDown()
                releaseA.await()
                order += it.captureId
            }
        }
        val b = Thread {
            reducer.publish(terminal(41, RecordingStorageTerminalDisposition.SAVED)) {
                order += it.captureId
                bPublished.countDown()
            }
        }

        try {
            a.start()
            assertTrue(aCallbackEntered.await(5, TimeUnit.SECONDS))
            b.start()
            assertFalse(bPublished.await(25, TimeUnit.MILLISECONDS))
            releaseA.countDown()
            assertTrue(bPublished.await(5, TimeUnit.SECONDS))
            a.join(5_000)
            b.join(5_000)
            assertEquals(listOf(40, 41), order.toList())
        } finally {
            releaseA.countDown()
            a.join(5_000)
            b.join(5_000)
        }
    }

    @Test
    fun `recoverable recorder outcomes stay pending while only publication is saved`() {
        assertEquals(
            RecordingStorageTerminalDisposition.SAVED,
            recordingStorageTerminalDisposition(VideoRecorder.StorageDisposition.PUBLISHED, hasUri = true),
        )
        assertEquals(
            RecordingStorageTerminalDisposition.FAILED,
            recordingStorageTerminalDisposition(VideoRecorder.StorageDisposition.PUBLISHED, hasUri = false),
        )
        assertEquals(
            RecordingStorageTerminalDisposition.RETAINED_PENDING,
            recordingStorageTerminalDisposition(
                VideoRecorder.StorageDisposition.RETAINED_MARKER_UNAVAILABLE,
                hasUri = true,
            ),
        )
        assertEquals(
            RecordingStorageTerminalDisposition.RETAINED_PENDING,
            recordingStorageTerminalDisposition(
                VideoRecorder.StorageDisposition.RETAINED_PUBLICATION_UNAVAILABLE,
                hasUri = true,
            ),
        )
        assertEquals(
            RecordingStorageTerminalDisposition.FAILED,
            recordingStorageTerminalDisposition(VideoRecorder.StorageDisposition.NOT_APPLICABLE, hasUri = true),
        )
    }

    private fun terminal(
        captureId: Int,
        disposition: RecordingStorageTerminalDisposition,
    ) = RecordingStorageTerminalResult(
        captureId = captureId,
        outputUri = "clip-$captureId",
        disposition = disposition,
    )
}
