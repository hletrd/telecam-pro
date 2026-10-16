package me.hletrd.telecampro.ui

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerlessMediaDeleteOperationTest {
    @Test
    fun `first terminal wins and drops delivery owner before late provider completion`() {
        val deliveries = mutableListOf<String>()
        val terminal = FirstWinsTerminal<String>(deliveries::add)

        assertTrue(terminal.complete("timeout"))
        assertFalse(terminal.isPending())
        assertFalse(terminal.complete("late provider"))
        assertEquals(listOf("timeout"), deliveries)
    }

    @Test
    fun `scheduler rejection terminalizes immediately`() {
        val delivered = AtomicReference<String>()
        val terminal = FirstWinsTerminal<String>(delivered::set)

        assertFalse(
            armFirstWinsTimeout(
                terminal = terminal,
                timeoutValue = "timeout",
                timeoutMs = 25L,
                postDelayed = { _, _ -> false },
            ),
        )

        assertEquals("timeout", delivered.get())
        assertFalse(terminal.isPending())
    }

    @Test
    fun `blocked provider cannot block caller and timeout makes late result inert`() {
        val providerEntered = CountDownLatch(1)
        val releaseProvider = CountDownLatch(1)
        val scheduled = AtomicReference<Runnable>()
        val deliveries = AtomicInteger()
        val delivered = AtomicReference<String>()
        val terminal = FirstWinsTerminal<String> {
            delivered.set(it)
            deliveries.incrementAndGet()
        }
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "blocked-delete-request-test").apply { isDaemon = true }
        }

        try {
            armFirstWinsTimeout(
                terminal = terminal,
                timeoutValue = "timeout",
                timeoutMs = 25L,
                postDelayed = { task, _ -> scheduled.set(task); true },
            )
            val providerReturned = CountDownLatch(1)
            val providerThread = AtomicReference<Thread>()
            executor.execute {
                providerThread.set(Thread.currentThread())
                providerEntered.countDown()
                // TIMED, so a caller-blocking regression fails below instead of hanging the suite.
                releaseProvider.await(10, TimeUnit.SECONDS)
                terminal.complete("provider")
                providerReturned.countDown()
            }
            // Structural non-blocking proof, no wall-clock budget: dispatch has returned while
            // the provider is still parked, on a different thread, with nothing delivered yet.
            val parkedAtReturn = providerReturned.count == 1L

            assertTrue(providerEntered.await(2, TimeUnit.SECONDS))
            assertTrue("dispatch returned only after the provider finished", parkedAtReturn)
            assertNotSame(Thread.currentThread(), providerThread.get())
            assertEquals(0, deliveries.get())
            scheduled.get().run()
            assertEquals("timeout", delivered.get())

            releaseProvider.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
            assertEquals(1, deliveries.get())
            assertEquals("timeout", delivered.get())
        } finally {
            releaseProvider.countDown()
            executor.shutdown()
        }
    }
}
