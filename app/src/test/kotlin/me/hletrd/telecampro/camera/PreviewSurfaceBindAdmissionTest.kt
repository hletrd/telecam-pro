package me.hletrd.telecampro.camera

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewSurfaceBindAdmissionTest {
    @Test
    fun `main caller returns while terminal gate is held and stale queued bind is discarded`() {
        val gate = TerminalAcquisitionGate()
        val gateEntered = CountDownLatch(1)
        val releaseGate = CountDownLatch(1)
        val gateHolder = Thread {
            gate.runIfOpen {
                gateEntered.countDown()
                releaseGate.await()
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        val firstOwnershipCheck = CountDownLatch(1)
        val current = AtomicBoolean(true)
        val binds = AtomicInteger()
        try {
            gateHolder.start()
            assertTrue(gateEntered.await(5, TimeUnit.SECONDS))

            val callbackReturned = CountDownLatch(1)
            Thread {
                assertTrue(
                    dispatchGenerationOwnedPreviewBind(
                        submit = { task -> executor.execute(task); true },
                        terminalGate = gate,
                        isCurrent = {
                            firstOwnershipCheck.countDown()
                            current.get()
                        },
                        bind = { binds.incrementAndGet() },
                    ),
                )
                callbackReturned.countDown()
            }.start()

            // This is the simulated TextureView/main-thread contract: dispatch returns even though
            // its serialized worker has reached the load-bearing, still-held acquisition gate.
            assertTrue(callbackReturned.await(1, TimeUnit.SECONDS))
            assertTrue(firstOwnershipCheck.await(5, TimeUnit.SECONDS))
            assertEquals(1L, releaseGate.count)

            // Surface generation/GL ownership changes while the worker is waiting. The second
            // ownership check after gate admission must refuse the obsolete native-window bind.
            current.set(false)
            releaseGate.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            gateHolder.join(5_000)
            assertFalse(gateHolder.isAlive)
            assertEquals(0, binds.get())
        } finally {
            releaseGate.countDown()
            executor.shutdownNow()
            gateHolder.join(5_000)
        }
    }

    @Test
    fun `current queued bind runs once after serialized terminal admission`() {
        val executor = Executors.newSingleThreadExecutor()
        val bound = CountDownLatch(1)
        try {
            assertTrue(
                dispatchGenerationOwnedPreviewBind(
                    submit = { task -> executor.execute(task); true },
                    terminalGate = TerminalAcquisitionGate(),
                    isCurrent = { true },
                    bind = bound::countDown,
                ),
            )
            assertTrue(bound.await(5, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
    }
}
