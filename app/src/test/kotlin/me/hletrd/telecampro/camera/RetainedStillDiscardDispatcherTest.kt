package me.hletrd.telecampro.camera

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RetainedStillDiscardDispatcherTest {
    @Test
    fun `production facades share the exact process owner`() {
        val firstOwner = ProcessRetainedStillDiscardOwner.capacity(
            RETAINED_STILL_DISCARD_WORKER_COUNT,
            RETAINED_STILL_DISCARD_BACKLOG_CAPACITY,
        )
        val secondOwner = ProcessRetainedStillDiscardOwner.capacity(
            RETAINED_STILL_DISCARD_WORKER_COUNT,
            RETAINED_STILL_DISCARD_BACKLOG_CAPACITY,
        )
        assertTrue(firstOwner === secondOwner)

        val finished = CountDownLatch(1)
        val facade = RetainedStillDiscardDispatcher(
            RETAINED_STILL_DISCARD_WORKER_COUNT,
            RETAINED_STILL_DISCARD_BACKLOG_CAPACITY,
        )
        assertEquals(
            RetainedStillDiscardDispatch.ACCEPTED,
            facade.dispatch(Runnable { finished.countDown() }),
        )
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        facade.shutdown()
    }

    @Test
    fun `registered producer terminal survives old Engine facade shutdown`() {
        val staleEngine = RetainedStillDiscardDispatcher(
            RETAINED_STILL_DISCARD_WORKER_COUNT,
            RETAINED_STILL_DISCARD_BACKLOG_CAPACITY,
        )
        staleEngine.shutdown()
        assertEquals(
            RetainedStillDiscardDispatch.SHUTDOWN,
            staleEngine.dispatch(Runnable { error("closed Engine facade admitted new work") }),
        )

        val terminalFinished = CountDownLatch(1)
        assertEquals(
            RetainedStillDiscardDispatch.ACCEPTED,
            ProcessRetainedStillDiscardOwner.dispatchRegisteredProducerTerminal(
                Runnable { terminalFinished.countDown() },
            ),
        )
        assertTrue(terminalFinished.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `process owner rejects capacity drift across Engine generations`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProcessRetainedStillDiscardOwner.capacity(
                RETAINED_STILL_DISCARD_WORKER_COUNT + 1,
                RETAINED_STILL_DISCARD_BACKLOG_CAPACITY,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RetainedStillDiscardCapacityOwner(workerCount = 0, backlogCapacity = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RetainedStillDiscardCapacityOwner(workerCount = 1, backlogCapacity = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProcessRetainedStillDiscardOwner.capacity(
                RETAINED_STILL_DISCARD_WORKER_COUNT,
                RETAINED_STILL_DISCARD_BACKLOG_CAPACITY + 1,
            )
        }
    }

    @Test
    fun `injected facade exposes its exact finite active and queued capacity`() {
        val releaseWorker = CountDownLatch(1)
        val workerEntered = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val facade = RetainedStillDiscardDispatcher(
            workerCount = 1,
            backlogCapacity = 1,
            threadFactory = ThreadFactory { task ->
                Thread(task, "test-retained-facade").apply { isDaemon = true }
            },
        )
        try {
            assertEquals(
                RetainedStillDiscardDispatch.ACCEPTED,
                facade.dispatch(
                    Runnable {
                        workerEntered.countDown()
                        releaseWorker.await()
                        finished.countDown()
                    },
                ),
            )
            assertTrue(workerEntered.await(5, TimeUnit.SECONDS))
            assertEquals(
                RetainedStillDiscardDispatch.ACCEPTED,
                facade.dispatch(Runnable { finished.countDown() }),
            )
            assertEquals(1, facade.activeTaskCount())
            assertEquals(1, facade.queuedTaskCount())
            releaseWorker.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
        } finally {
            releaseWorker.countDown()
            facade.shutdown()
        }
    }

    @Test
    fun `live family retirement submissions stay inside finite process capacity`() {
        val releaseWorker = CountDownLatch(1)
        val workerEntered = CountDownLatch(1)
        val overflowRan = AtomicBoolean()
        val capacity = RetainedStillDiscardCapacityOwner(
            workerCount = 1,
            backlogCapacity = 1,
            threadFactory = ThreadFactory { task ->
                Thread(task, "test-family-retirement").apply { isDaemon = true }
            },
        )
        val facade = RetainedStillDiscardDispatcher(capacity)
        try {
            assertEquals(
                RetainedStillDiscardDispatch.ACCEPTED,
                dispatchDeletedFamilyRetirement(
                    facade,
                    Runnable {
                        workerEntered.countDown()
                        releaseWorker.await()
                    },
                ),
            )
            assertTrue(workerEntered.await(5, TimeUnit.SECONDS))
            assertEquals(
                RetainedStillDiscardDispatch.ACCEPTED,
                dispatchDeletedFamilyRetirement(facade, Runnable {}),
            )

            repeat(32) {
                assertEquals(
                    RetainedStillDiscardDispatch.OVERFLOW,
                    dispatchDeletedFamilyRetirement(
                        facade,
                        Runnable { overflowRan.set(true) },
                    ),
                )
            }

            assertEquals(1, capacity.activeTaskCount())
            assertEquals(1, capacity.queuedTaskCount())
            assertFalse(overflowRan.get())
        } finally {
            releaseWorker.countDown()
            facade.shutdown()
        }
    }

    @Test
    fun `blocked provider stays finite across repeated Engine replacement`() {
        val releaseProvider = CountDownLatch(1)
        val providerEntered = CountDownLatch(1)
        val oldFinished = CountDownLatch(2)
        val callbacks = CopyOnWriteArrayList<String>()
        val overflowRan = AtomicBoolean()
        val createdThreads = AtomicInteger()
        val capacity = RetainedStillDiscardCapacityOwner(
            workerCount = 1,
            backlogCapacity = 1,
            threadFactory = ThreadFactory { task ->
                Thread(task, "test-retained-discard-${createdThreads.incrementAndGet()}").apply {
                    isDaemon = true
                }
            },
        )
        val oldEngine = RetainedStillDiscardDispatcher(capacity)

        try {
            assertEquals(
                RetainedStillDiscardDispatch.ACCEPTED,
                oldEngine.dispatch(
                    Runnable {
                        providerEntered.countDown()
                        releaseProvider.await()
                        callbacks += "old-blocked"
                        oldFinished.countDown()
                    },
                ),
            )
            assertTrue(providerEntered.await(5, TimeUnit.SECONDS))
            assertEquals(
                RetainedStillDiscardDispatch.ACCEPTED,
                oldEngine.dispatch(Runnable { callbacks += "old-queued"; oldFinished.countDown() }),
            )
            oldEngine.shutdown()
            assertEquals(
                RetainedStillDiscardDispatch.SHUTDOWN,
                oldEngine.dispatch(Runnable { callbacks += "stale-after-shutdown" }),
            )

            repeat(32) {
                val replacement = RetainedStillDiscardDispatcher(capacity)
                assertEquals(
                    RetainedStillDiscardDispatch.OVERFLOW,
                    replacement.dispatch(Runnable { overflowRan.set(true) }),
                )
                replacement.shutdown()
            }

            assertEquals(1, capacity.activeTaskCount())
            assertEquals(1, capacity.queuedTaskCount())
            assertEquals(1, createdThreads.get())
            assertFalse(overflowRan.get())

            releaseProvider.countDown()
            assertTrue(oldFinished.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("old-blocked", "old-queued"), callbacks.toList())

            val currentFinished = CountDownLatch(1)
            val currentEngine = RetainedStillDiscardDispatcher(capacity)
            assertEquals(
                RetainedStillDiscardDispatch.ACCEPTED,
                currentEngine.dispatch(
                    Runnable {
                        callbacks += "current"
                        currentFinished.countDown()
                    },
                ),
            )
            assertTrue(currentFinished.await(5, TimeUnit.SECONDS))
            assertEquals("current", callbacks.last())
            currentEngine.shutdown()
        } finally {
            releaseProvider.countDown()
            oldEngine.shutdown()
        }
    }
}
