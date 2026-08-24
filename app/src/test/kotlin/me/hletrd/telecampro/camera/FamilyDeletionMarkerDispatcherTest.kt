package me.hletrd.telecampro.camera

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import me.hletrd.telecampro.storage.CaptureFamilyKey
import me.hletrd.telecampro.storage.CaptureFamilyMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilyDeletionMarkerDispatcherTest {
    @Test
    fun `blocked first family has one fixed backlog and overflow never runs inline`() {
        val releaseFirst = CountDownLatch(1)
        val firstEntered = CountDownLatch(1)
        val allFinished = CountDownLatch(2)
        val executed = CopyOnWriteArrayList<Long>()
        val createdThreads = AtomicInteger()
        val dispatcher = isolatedDispatcher(
            workerCount = 1,
            backlogCapacity = 1,
            createdThreads = createdThreads,
        )
        val caller = Thread.currentThread()

        try {
            val first = dispatcher.reserve(family(1))
            assertEquals(FamilyDeletionMarkerDispatch.ACCEPTED, first.dispatch)
            assertTrue(
                checkNotNull(first.reservation).submit(
                    Runnable {
                        assertFalse(Thread.currentThread() === caller)
                        firstEntered.countDown()
                        releaseFirst.await()
                        executed += 1L
                        allFinished.countDown()
                    },
                ),
            )
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))

            val queued = dispatcher.reserve(family(2))
            assertEquals(FamilyDeletionMarkerDispatch.ACCEPTED, queued.dispatch)
            assertTrue(
                checkNotNull(queued.reservation).submit(
                    Runnable {
                        executed += 2L
                        allFinished.countDown()
                    },
                ),
            )

            repeat(32) { index ->
                val overflow = dispatcher.reserve(family(100 + index.toLong()))
                assertEquals(FamilyDeletionMarkerDispatch.OVERFLOW, overflow.dispatch)
                assertNull(overflow.reservation)
            }
            assertEquals(1, dispatcher.activeTaskCount())
            assertEquals(1, dispatcher.queuedTaskCount())
            assertEquals(2, dispatcher.admittedFamilyCount())
            assertEquals(1, createdThreads.get())
            releaseFirst.countDown()
            assertTrue(allFinished.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(1L, 2L), executed.toList())
            assertEquals(0, dispatcher.admittedFamilyCount())
        } finally {
            releaseFirst.countDown()
            dispatcher.shutdown()
        }
    }

    @Test
    fun `accepted reservation keeps exact callback ownership after facade shutdown`() {
        val capacity = FamilyDeletionMarkerCapacityOwner(
            workerCount = 1,
            backlogCapacity = 1,
            threadFactory = ThreadFactory { task -> Thread(task, "marker-retention-test").apply { isDaemon = true } },
        )
        val oldEngine = FamilyDeletionMarkerDispatcher(capacity)
        val replacementEngine = FamilyDeletionMarkerDispatcher(capacity)
        val callbackCount = AtomicInteger()
        val finished = CountDownLatch(1)

        val admitted = oldEngine.reserve(family(7))
        val reservation = checkNotNull(admitted.reservation)
        oldEngine.shutdown()
        assertEquals(
            FamilyDeletionMarkerDispatch.SHUTDOWN,
            oldEngine.reserve(family(8)).dispatch,
        )
        assertTrue(
            reservation.submit(
                Runnable {
                    callbackCount.incrementAndGet()
                    finished.countDown()
                },
            ),
        )
        assertFalse(reservation.submit(Runnable { callbackCount.incrementAndGet() }))
        assertFalse(reservation.cancel())
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertEquals(1, callbackCount.get())

        val replacement = replacementEngine.reserve(family(9))
        assertEquals(FamilyDeletionMarkerDispatch.ACCEPTED, replacement.dispatch)
        assertTrue(checkNotNull(replacement.reservation).cancel())
        assertFalse(replacement.reservation.cancel())
        assertEquals(0, replacementEngine.admittedFamilyCount())
        replacementEngine.shutdown()
    }

    @Test
    fun `process owner is shared and rejects capacity drift`() {
        val first = ProcessFamilyDeletionMarkerOwner.capacity(
            FAMILY_DELETION_MARKER_WORKER_COUNT,
            FAMILY_DELETION_MARKER_BACKLOG_CAPACITY,
        )
        val second = ProcessFamilyDeletionMarkerOwner.capacity(
            FAMILY_DELETION_MARKER_WORKER_COUNT,
            FAMILY_DELETION_MARKER_BACKLOG_CAPACITY,
        )
        assertTrue(first === second)
        assertThrows(IllegalArgumentException::class.java) {
            ProcessFamilyDeletionMarkerOwner.capacity(
                FAMILY_DELETION_MARKER_WORKER_COUNT + 1,
                FAMILY_DELETION_MARKER_BACKLOG_CAPACITY,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProcessFamilyDeletionMarkerOwner.capacity(
                FAMILY_DELETION_MARKER_WORKER_COUNT,
                FAMILY_DELETION_MARKER_BACKLOG_CAPACITY + 1,
            )
        }
    }

    @Test
    fun `invalid capacity and unavailable executor reject without leaking reservation`() {
        assertThrows(IllegalArgumentException::class.java) {
            FamilyDeletionMarkerCapacityOwner(workerCount = 0, backlogCapacity = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FamilyDeletionMarkerCapacityOwner(workerCount = 1, backlogCapacity = 0)
        }
        val owner = FamilyDeletionMarkerCapacityOwner(workerCount = 1, backlogCapacity = 1)
        val reservation = checkNotNull(owner.reserve(family(10)))
        owner.shutdownNowForTest()
        assertFalse(reservation.submit(Runnable { error("rejected executor ran work inline") }))
        assertEquals(0, owner.admittedFamilyCount())
    }

    @Test
    fun `reservation accounting fails closed on an impossible extra release`() {
        val owner = FamilyDeletionMarkerCapacityOwner(workerCount = 1, backlogCapacity = 1)
        try {
            assertThrows(IllegalStateException::class.java) {
                owner.releaseReservation()
            }
        } finally {
            owner.shutdownNowForTest()
        }
    }

    @Test
    fun `unused reservation cancellation releases capacity without executing work`() {
        val dispatcher = isolatedDispatcher(workerCount = 1, backlogCapacity = 1)
        try {
            val admission = dispatcher.reserve(family(11))
            assertEquals(1, dispatcher.admittedFamilyCount())
            val reservation = checkNotNull(admission.reservation)
            assertTrue(reservation.cancel())
            assertEquals(0, dispatcher.admittedFamilyCount())
            assertFalse(reservation.submit(Runnable { error("canceled work ran") }))
        } finally {
            dispatcher.shutdown()
        }
    }

    @Test
    fun `completion registry delivers once and detach drains then drops stale callbacks`() {
        val registry = FamilyDeletionCompletionRegistry<String>()
        val delivered = CopyOnWriteArrayList<String>()
        val first = checkNotNull(registry.register(delivered::add))
        assertEquals(1, registry.callbackCount())
        assertTrue(registry.complete(first, "first"))
        assertFalse(registry.complete(first, "duplicate"))
        assertEquals(listOf("first"), delivered.toList())
        val canceled = checkNotNull(registry.register { error("canceled completion ran") })
        assertTrue(registry.cancel(canceled))
        assertFalse(registry.cancel(canceled))

        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val closeFinished = CountDownLatch(1)
        val callbackFinished = CountDownLatch(1)
        val second = checkNotNull(
            registry.register {
                callbackEntered.countDown()
                releaseCallback.await()
                delivered += it
            },
        )
        val thirdCalled = AtomicBoolean(false)
        val third = checkNotNull(registry.register { thirdCalled.set(true) })
        Thread {
            registry.complete(second, "second")
            callbackFinished.countDown()
        }.apply { isDaemon = true }.start()
        assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
        Thread {
            registry.closeAndDrain()
            closeFinished.countDown()
        }.apply { isDaemon = true }.start()
        assertFalse(closeFinished.await(50, TimeUnit.MILLISECONDS))

        releaseCallback.countDown()
        assertTrue(callbackFinished.await(5, TimeUnit.SECONDS))
        assertTrue(closeFinished.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("first", "second"), delivered.toList())
        assertFalse(thirdCalled.get())
        assertFalse(registry.complete(third, "stale"))
        assertNull(registry.register { error("closed registry admitted callback") })
        assertEquals(0, registry.callbackCount())
    }

    private fun isolatedDispatcher(
        workerCount: Int,
        backlogCapacity: Int,
        createdThreads: AtomicInteger = AtomicInteger(),
    ) = FamilyDeletionMarkerDispatcher(
        workerCount = workerCount,
        backlogCapacity = backlogCapacity,
        threadFactory = ThreadFactory { task ->
            Thread(task, "test-family-marker-${createdThreads.incrementAndGet()}").apply { isDaemon = true }
        },
    )

    private fun family(sequence: Long) = CaptureFamilyKey(
        media = CaptureFamilyMedia.STILL,
        capturedAtEpochMillis = 1_700_040_000_000L + sequence,
        sequence = sequence,
    )
}
