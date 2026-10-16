package me.hletrd.telecampro.ui

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

class ViewModelMediaDeleteDispatcherTest {
    @Test
    fun `production facades share the exact process capacity owner`() {
        val first = ProcessViewModelMediaDeleteOwner.capacity(
            VIEW_MODEL_MEDIA_DELETE_WORKER_COUNT,
            VIEW_MODEL_MEDIA_DELETE_BACKLOG_CAPACITY,
        )
        val second = ProcessViewModelMediaDeleteOwner.capacity(
            VIEW_MODEL_MEDIA_DELETE_WORKER_COUNT,
            VIEW_MODEL_MEDIA_DELETE_BACKLOG_CAPACITY,
        )

        assertTrue(first === second)

        val taskFinished = CountDownLatch(1)
        val workerName = java.util.concurrent.atomic.AtomicReference<String>()
        val workerDaemon = AtomicBoolean()
        val facade = ViewModelMediaDeleteDispatcher(
            VIEW_MODEL_MEDIA_DELETE_WORKER_COUNT,
            VIEW_MODEL_MEDIA_DELETE_BACKLOG_CAPACITY,
        )
        try {
            assertEquals(
                ViewModelMediaDeleteDispatch.ACCEPTED,
                facade.dispatch(
                    Runnable {
                        workerName.set(Thread.currentThread().name)
                        workerDaemon.set(Thread.currentThread().isDaemon)
                        taskFinished.countDown()
                    },
                ),
            )
            assertTrue(taskFinished.await(5, TimeUnit.SECONDS))
            assertTrue(workerName.get().startsWith("vm-media-delete-"))
            assertTrue(workerDaemon.get())
        } finally {
            facade.shutdown()
        }
    }

    @Test
    fun `process owner rejects capacity drift across ViewModel generations`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProcessViewModelMediaDeleteOwner.capacity(
                VIEW_MODEL_MEDIA_DELETE_WORKER_COUNT + 1,
                VIEW_MODEL_MEDIA_DELETE_BACKLOG_CAPACITY,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProcessViewModelMediaDeleteOwner.capacity(
                VIEW_MODEL_MEDIA_DELETE_WORKER_COUNT,
                VIEW_MODEL_MEDIA_DELETE_BACKLOG_CAPACITY + 1,
            )
        }
    }

    @Test
    fun `blocked provider stays at exact active plus backlog ceiling across replacement`() {
        val releaseProvider = CountDownLatch(1)
        val providerEntered = CountDownLatch(1)
        val acceptedFinished = CountDownLatch(2)
        val acceptedOrder = CopyOnWriteArrayList<String>()
        val overflowRan = AtomicBoolean()
        val createdThreads = AtomicInteger()
        val capacity = ViewModelMediaDeleteCapacityOwner(
            workerCount = 1,
            backlogCapacity = 1,
            threadFactory = ThreadFactory { task ->
                Thread(task, "test-vm-delete-${createdThreads.incrementAndGet()}").apply {
                    isDaemon = true
                }
            },
        )
        val oldViewModel = ViewModelMediaDeleteDispatcher(capacity)

        try {
            assertEquals(
                ViewModelMediaDeleteDispatch.ACCEPTED,
                oldViewModel.dispatch(
                    Runnable {
                        providerEntered.countDown()
                        releaseProvider.await()
                        acceptedOrder += "old-active"
                        acceptedFinished.countDown()
                    },
                ),
            )
            assertTrue(providerEntered.await(5, TimeUnit.SECONDS))
            assertEquals(
                ViewModelMediaDeleteDispatch.ACCEPTED,
                oldViewModel.dispatch(
                    Runnable {
                        acceptedOrder += "old-backlog"
                        acceptedFinished.countDown()
                    },
                ),
            )
            oldViewModel.shutdown()
            assertEquals(
                ViewModelMediaDeleteDispatch.SHUTDOWN,
                oldViewModel.dispatch(Runnable { overflowRan.set(true) }),
            )

            repeat(32) {
                val replacement = ViewModelMediaDeleteDispatcher(capacity)
                assertEquals(
                    ViewModelMediaDeleteDispatch.OVERFLOW,
                    replacement.dispatch(Runnable { overflowRan.set(true) }),
                )
                replacement.shutdown()
            }

            assertEquals(1, oldViewModel.activeTaskCount())
            assertEquals(1, oldViewModel.queuedTaskCount())
            assertEquals(1, createdThreads.get())
            assertFalse(overflowRan.get())

            releaseProvider.countDown()
            assertTrue(acceptedFinished.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("old-active", "old-backlog"), acceptedOrder.toList())
        } finally {
            releaseProvider.countDown()
            oldViewModel.shutdown()
        }
    }

    @Test
    fun `single worker keeps whole-family and late-sibling effects serial`() {
        val finished = CountDownLatch(3)
        val order = CopyOnWriteArrayList<String>()
        val dispatcher = ViewModelMediaDeleteDispatcher(
            workerCount = 1,
            backlogCapacity = 3,
            threadFactory = ThreadFactory { task ->
                Thread(task, "test-vm-delete-order").apply { isDaemon = true }
            },
        )
        try {
            listOf("family-1", "late-sibling", "family-2").forEach { identity ->
                assertEquals(
                    ViewModelMediaDeleteDispatch.ACCEPTED,
                    dispatcher.dispatch(
                        Runnable {
                            order += identity
                            finished.countDown()
                        },
                    ),
                )
            }
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("family-1", "late-sibling", "family-2"), order.toList())
        } finally {
            dispatcher.shutdown()
        }
    }
}
