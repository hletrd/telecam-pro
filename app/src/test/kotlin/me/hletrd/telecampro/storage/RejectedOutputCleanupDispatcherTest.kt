package me.hletrd.telecampro.storage

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RejectedOutputCleanupDispatcherTest {
    @Test
    fun `reserved cleanup returns while provider identity work is blocked`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val owner = owner<String> {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            PendingOutputDiscardResult.RECOVERY_MARKED
        }
        val reservation = requireNotNull(owner.reserve())
        val callbackThread = Executors.newSingleThreadExecutor()
        try {
            val returned = callbackThread.submit<Boolean> {
                reservation.submit("failed.dng") { completed.countDown() }
            }
            assertTrue(returned.get(250, TimeUnit.MILLISECONDS))
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertEquals(1, owner.admittedCount())
            assertFalse(completed.await(100, TimeUnit.MILLISECONDS))

            release.countDown()
            assertTrue(completed.await(1, TimeUnit.SECONDS))
            assertEquals(0, owner.unresolvedCount())
        } finally {
            release.countDown()
            callbackThread.shutdownNow()
            owner.shutdownNowForTest()
        }
    }

    @Test
    fun `reservation closes before a RAW output can exceed process capacity`() {
        val owner = owner<String> { PendingOutputDiscardResult.DELETED }
        val first = owner.reserve()
        val second = owner.reserve()

        assertNotNull(first)
        assertNotNull(second)
        assertNull(owner.reserve())
        assertEquals(2, owner.admittedCount())
        assertTrue(first!!.cancel())
        assertTrue(second!!.cancel())
        owner.shutdownNowForTest()
    }

    @Test
    fun `shutdown retains exact work and never invokes provider inline`() {
        var effects = 0
        val completed = CountDownLatch(1)
        val owner = owner<String> {
            effects += 1
            PendingOutputDiscardResult.DELETED
        }
        val reservation = requireNotNull(owner.reserve())
        owner.shutdownNowForTest()

        assertFalse(reservation.submit("old-engine.dng") { completed.countDown() })
        assertEquals(0, effects)
        assertTrue(completed.await(100, TimeUnit.MILLISECONDS))
        assertEquals(1, owner.unresolvedCount())
        assertFalse(owner.canAdmit())
    }

    @Test
    fun `old owner completion retains exact output and later retry retires it`() {
        val durable = mutableSetOf<String>()
        val firstCompleted = CountDownLatch(1)
        val owner = owner<String> { output ->
            if (output in durable) PendingOutputDiscardResult.RECOVERY_MARKED
            else PendingOutputDiscardResult.UNRESOLVED
        }

        assertEquals(
            RejectedOutputCleanupDispatch.ACCEPTED,
            owner.dispatch("old-engine.dng") { firstCompleted.countDown() },
        )
        assertTrue(firstCompleted.await(1, TimeUnit.SECONDS))
        assertEquals(1, owner.unresolvedCount())
        assertFalse(owner.canAdmit())

        durable += "old-engine.dng"
        owner.retryUnresolved()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (owner.unresolvedCount() != 0 && System.nanoTime() < deadline) Thread.yield()
        assertEquals(0, owner.unresolvedCount())
        assertTrue(owner.canAdmit())
        owner.shutdownNowForTest()
    }

    @Test
    fun `direct dispatch reports shutdown without inline cleanup`() {
        var effects = 0
        val owner = owner<String> {
            effects++
            PendingOutputDiscardResult.DELETED
        }
        owner.shutdownNowForTest()

        assertEquals(RejectedOutputCleanupDispatch.SHUTDOWN, owner.dispatch("shutdown.dng"))
        assertEquals(0, effects)
        assertEquals(1, owner.unresolvedCount())
    }

    @Test
    fun `a cleanup reserved before the worker dequeues the backlog is never rejected`() {
        // AGG5-27 (the AGG4-26 sibling): runAttempt returns its permit in a finally while the worker
        // is still busy and the backlog task is still queued. A reservation in that window used to be
        // admitted by the semaphore and then rejected by a backlog-sized queue as UNRESOLVED.
        val releaseFirst = CountDownLatch(1)
        val firstEntered = CountDownLatch(1)
        val allFinished = CountDownLatch(3)
        val windowResults = java.util.concurrent.CopyOnWriteArrayList<Any>()
        lateinit var owner: RejectedOutputCleanupCapacityOwner<String>
        owner = RejectedOutputCleanupCapacityOwner(
            workerCount = 1,
            backlogCapacity = 1,
            admissionLimit = 4,
            discardEffect = { output ->
                if (output == "first") {
                    firstEntered.countDown()
                    releaseFirst.await(5, TimeUnit.SECONDS)
                }
                PendingOutputDiscardResult.DELETED
            },
            afterTaskReleased = {
                if (windowResults.isEmpty()) {
                    // On the worker, after "first" returned its permit and before the worker can
                    // dequeue "second": the queue is still occupied.
                    windowResults += owner.queuedTaskCount()
                    val reserved = owner.reserve()
                    windowResults += reserved?.submit("window") { result ->
                        windowResults += result
                        allFinished.countDown()
                    } ?: "no permit"
                }
            },
        )
        try {
            assertTrue(requireNotNull(owner.reserve()).submit("first") { allFinished.countDown() })
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            assertTrue(requireNotNull(owner.reserve()).submit("second") { allFinished.countDown() })
            assertNull("both permits are taken", owner.reserve())

            releaseFirst.countDown()
            assertTrue(allFinished.await(5, TimeUnit.SECONDS))
            assertEquals(listOf<Any>(1, true, PendingOutputDiscardResult.DELETED), windowResults.toList())
            assertEquals(0, owner.unresolvedCount())
        } finally {
            releaseFirst.countDown()
            owner.shutdownNowForTest()
        }
    }

    private fun <T> owner(
        effect: (T) -> PendingOutputDiscardResult,
    ) = RejectedOutputCleanupCapacityOwner(
        workerCount = 1,
        backlogCapacity = 1,
        admissionLimit = 1,
        ownershipLimit = 4,
        discardEffect = effect,
    )
}
