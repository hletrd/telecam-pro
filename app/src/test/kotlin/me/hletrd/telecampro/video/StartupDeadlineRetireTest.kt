package me.hletrd.telecampro.video

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AGG2-24: the startup deadline's expiry runs on the deadline executor and retires that executor
 * from inside itself (recordFailure → cancel). Retirement must not interrupt the running expiry,
 * which goes on to invoke the owner's failure callback.
 */
class StartupDeadlineRetireTest {
    @Test(timeout = 10_000)
    fun `the expiry retiring its own executor keeps its interrupt flag clear`() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val futureRef = AtomicReference<ScheduledFuture<*>>()
        val interruptedAfterRetire = AtomicReference<Boolean>()
        val armed = CountDownLatch(1)
        val done = CountDownLatch(1)
        futureRef.set(
            executor.schedule(
                {
                    armed.await()
                    retireStartupDeadline(futureRef.get(), executor)
                    interruptedAfterRetire.set(Thread.currentThread().isInterrupted)
                    done.countDown()
                },
                1,
                TimeUnit.MILLISECONDS,
            ),
        )
        armed.countDown()

        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(false, interruptedAfterRetire.get())
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
    }

    @Test(timeout = 10_000)
    fun `retiring before expiry cancels the deadline and lets the executor terminate`() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val future = executor.schedule({ error("expired") }, 1, TimeUnit.HOURS)

        retireStartupDeadline(future, executor)

        assertTrue(future.isCancelled)
        assertTrue("cancelled delayed task is purged on shutdown", executor.awaitTermination(5, TimeUnit.SECONDS))
    }
}
