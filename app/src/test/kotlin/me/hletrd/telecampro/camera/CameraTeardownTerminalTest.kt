package me.hletrd.telecampro.camera

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import me.hletrd.telecampro.video.RecorderQuarantineAdmissionGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraTeardownTerminalTest {

    @Test
    fun `strict handler completion authorizes replacement without quarantine`() {
        val quarantineCalls = AtomicInteger(0)
        val terminal = CameraTeardownTerminal { quarantineCalls.incrementAndGet() }

        terminal.strictlyReleased()

        val result = terminal.await(0, TimeUnit.MILLISECONDS)
        assertEquals(CameraControllerCloseResult.STRICTLY_RELEASED, result)
        assertTrue(cameraReplacementMayAcquire(result))
        assertEquals(0, quarantineCalls.get())
    }

    @Test
    fun `blocked handler timeout quarantines and refuses native replacement`() {
        val processGate = RecorderQuarantineAdmissionGate()
        val terminal = CameraTeardownTerminal { processGate.close() }
        val handlerEntered = CountDownLatch(1)
        val releaseHandler = CountDownLatch(1)
        val handler = thread(name = "blocked-camera-handler") {
            handlerEntered.countDown()
            releaseHandler.await(1, TimeUnit.SECONDS)
            terminal.strictlyReleased()
        }
        assertTrue(handlerEntered.await(1, TimeUnit.SECONDS))

        val result = terminal.await(0, TimeUnit.MILLISECONDS)

        assertEquals(CameraControllerCloseResult.QUARANTINED, result)
        assertFalse(cameraReplacementMayAcquire(result))
        var acquired = false
        assertFalse(processGate.runNativeIfSafe { acquired = true })
        assertFalse(acquired)

        releaseHandler.countDown()
        handler.join(1_000)
        assertFalse(handler.isAlive)
    }

    @Test
    fun `concurrent close callers share one strict terminal identity`() {
        val quarantineCalls = AtomicInteger(0)
        val terminal = CameraTeardownTerminal { quarantineCalls.incrementAndGet() }
        val start = CountDownLatch(1)
        val results = Array<CameraControllerCloseResult?>(8) { null }
        val callers = results.indices.map { index ->
            thread(name = "camera-close-$index") {
                start.await(1, TimeUnit.SECONDS)
                results[index] = terminal.await(1, TimeUnit.SECONDS)
            }
        }

        start.countDown()
        terminal.strictlyReleased()
        callers.forEach { it.join(1_000) }

        assertTrue(callers.none(Thread::isAlive))
        assertTrue(results.all { it == CameraControllerCloseResult.STRICTLY_RELEASED })
        assertEquals(0, quarantineCalls.get())
    }

    @Test
    fun `late teardown completion is inert after timeout terminal`() {
        val quarantineCalls = AtomicInteger(0)
        val terminal = CameraTeardownTerminal { quarantineCalls.incrementAndGet() }

        val timeout = terminal.await(0, TimeUnit.MILLISECONDS)
        val lateCompletion = terminal.strictlyReleased()
        val repeatedTimeout = terminal.quarantine()

        assertEquals(CameraControllerCloseResult.QUARANTINED, timeout)
        assertEquals(timeout, lateCompletion)
        assertEquals(timeout, repeatedTimeout)
        assertEquals(1, quarantineCalls.get())
        assertFalse(cameraReplacementMayAcquire(lateCompletion))
        assertTrue(cameraReplacementMayAcquire(null))
    }

    @Test
    fun `interrupted teardown wait preserves interruption and quarantines`() {
        val quarantineCalls = AtomicInteger(0)
        val terminal = CameraTeardownTerminal { quarantineCalls.incrementAndGet() }

        Thread.currentThread().interrupt()
        try {
            assertEquals(
                CameraControllerCloseResult.QUARANTINED,
                terminal.await(1, TimeUnit.SECONDS),
            )
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(1, quarantineCalls.get())
            assertFalse(cameraReplacementMayAcquire(terminal.strictlyReleased()))
        } finally {
            Thread.interrupted()
        }
    }
}
