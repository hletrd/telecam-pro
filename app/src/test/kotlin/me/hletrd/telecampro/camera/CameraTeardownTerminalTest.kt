package me.hletrd.telecampro.camera

import java.util.Collections
import java.util.IdentityHashMap
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
    fun `exact onClosed authorizes replacement without quarantine`() {
        val quarantineCalls = AtomicInteger(0)
        val terminal = CameraTeardownTerminal { quarantineCalls.incrementAndGet() }
        val device = FakeDevice("expected")
        val owner = ExactCameraDeviceCloseOwner<FakeDevice>(terminal) { it.closeCalls.incrementAndGet() }

        owner.claim(device)
        owner.armClose(device)
        owner.retire(device)
        assertEquals(CameraControllerCloseResult.PENDING, terminal.currentOrPending())
        assertFalse(cameraReplacementMayAcquire(terminal.currentOrPending()))
        owner.onClosed(device)

        val result = terminal.await(0, TimeUnit.MILLISECONDS)
        assertEquals(CameraControllerCloseResult.STRICTLY_RELEASED, result)
        assertTrue(cameraReplacementMayAcquire(result))
        assertEquals(0, quarantineCalls.get())
    }

    @Test
    fun `close return and wrong onClosed cannot authorize replacement before exact callback`() {
        val terminal = CameraTeardownTerminal { error("unexpected quarantine") }
        val expected = FakeDevice("expected")
        val wrong = FakeDevice("wrong")
        val owner = ExactCameraDeviceCloseOwner<FakeDevice>(terminal) { it.closeCalls.incrementAndGet() }

        assertTrue(owner.claim(expected))
        owner.armClose(expected)
        owner.retire(expected) // Fake close returns immediately.
        assertFalse(owner.claim(wrong, retire = true))

        assertEquals(1, expected.closeCalls.get())
        assertEquals(1, wrong.closeCalls.get())
        assertEquals(null, owner.onClosed(wrong))
        assertEquals(CameraControllerCloseResult.PENDING, terminal.currentOrPending())
        assertFalse(cameraReplacementMayAcquire(terminal.currentOrPending()))

        assertEquals(CameraControllerCloseResult.STRICTLY_RELEASED, owner.onClosed(expected))
        assertEquals(CameraControllerCloseResult.STRICTLY_RELEASED, owner.onClosed(expected))
        assertEquals(1, expected.closeCalls.get())
        assertTrue(cameraReplacementMayAcquire(terminal.currentOrPending()))
    }

    @Test
    fun `onOpened racing close is retired even before controller field installation`() {
        val terminal = CameraTeardownTerminal { error("unexpected quarantine") }
        val device = FakeDevice("raced-onOpened")
        val owner = ExactCameraDeviceCloseOwner<FakeDevice>(terminal) { it.closeCalls.incrementAndGet() }

        owner.claim(device) // callback claim wins; controller field has not been assigned yet
        owner.armClose(installedDevice = null)

        assertEquals(1, device.closeCalls.get())
        assertEquals(CameraControllerCloseResult.PENDING, terminal.currentOrPending())
        owner.onClosed(device)
        assertEquals(CameraControllerCloseResult.STRICTLY_RELEASED, terminal.currentOrPending())
    }

    @Test
    fun `missing exact onClosed times out to one-way quarantine and late callback is inert`() {
        val quarantineCalls = AtomicInteger(0)
        val terminal = CameraTeardownTerminal { quarantineCalls.incrementAndGet() }
        val device = FakeDevice("missing-callback")
        val owner = ExactCameraDeviceCloseOwner<FakeDevice>(terminal) { it.closeCalls.incrementAndGet() }
        owner.claim(device)
        owner.armClose(device)
        owner.retire(device)

        assertEquals(
            CameraControllerCloseResult.QUARANTINED,
            terminal.await(0, TimeUnit.MILLISECONDS),
        )
        assertEquals(CameraControllerCloseResult.QUARANTINED, owner.onClosed(device))
        assertEquals(1, quarantineCalls.get())
        assertFalse(cameraReplacementMayAcquire(terminal.currentOrPending()))
    }

    @Test
    fun `error before onOpened claims and closes callback handle exactly once`() {
        failedOpenCallbackClosesExactlyOnce("error-before-open")
    }

    @Test
    fun `disconnect before onOpened claims and closes callback handle exactly once`() {
        failedOpenCallbackClosesExactlyOnce("disconnect-before-open")
    }

    @Test
    fun `camera callback losing close race observes pending without waiting or quarantining`() {
        val quarantineCalls = AtomicInteger(0)
        val terminal = CameraTeardownTerminal { quarantineCalls.incrementAndGet() }
        val device = FakeDevice("handler-owned")
        val owner = ExactCameraDeviceCloseOwner<FakeDevice>(terminal) { it.closeCalls.incrementAndGet() }
        owner.claim(device)

        // Models an off-handler winner that arms close and queues teardown behind this admitted
        // camera callback. The callback loser reads PENDING and returns; it never awaits its queue.
        owner.armClose(device)
        assertEquals(CameraControllerCloseResult.PENDING, terminal.currentOrPending())
        assertEquals(0, quarantineCalls.get())
        assertFalse(cameraReplacementMayAcquire(terminal.currentOrPending()))

        owner.retire(device) // queued teardown runs after the callback returns
        owner.onClosed(device)
        assertEquals(CameraControllerCloseResult.STRICTLY_RELEASED, terminal.currentOrPending())
        assertEquals(0, quarantineCalls.get())
    }

    @Test
    fun `posted teardown waits behind admitted callback without blocking that callback`() {
        val terminal = CameraTeardownTerminal { error("unexpected quarantine") }
        val device = FakeDevice("queued")
        val owner = ExactCameraDeviceCloseOwner<FakeDevice>(terminal) { it.closeCalls.incrementAndGet() }
        val queue = mutableListOf<Runnable>()
        var queueClosed = false
        owner.claim(device)
        owner.armClose(device)

        dispatchCameraTeardown(
            onCameraThread = false,
            beginQueueClose = { it() },
            postTeardown = { queue += it; true },
            closeQueue = { queueClosed = true },
            teardown = Runnable { owner.retire(device) },
            onQueueFailure = { throw AssertionError("unexpected queue failure", it) },
        )

        assertTrue(queueClosed)
        assertEquals(1, queue.size)
        assertEquals(0, device.closeCalls.get())
        assertEquals(CameraControllerCloseResult.PENDING, terminal.currentOrPending())
        queue.single().run()
        assertEquals(1, device.closeCalls.get())
        owner.onClosed(device)
        assertTrue(cameraReplacementMayAcquire(terminal.currentOrPending()))
    }

    @Test
    fun `post failure executes teardown inline and still requires exact onClosed`() {
        val terminal = CameraTeardownTerminal { error("unexpected quarantine") }
        val device = FakeDevice("post-failure")
        val owner = ExactCameraDeviceCloseOwner<FakeDevice>(terminal) { it.closeCalls.incrementAndGet() }
        owner.claim(device)
        owner.armClose(device)

        dispatchCameraTeardown(
            onCameraThread = false,
            beginQueueClose = { it() },
            postTeardown = { false },
            closeQueue = {},
            teardown = Runnable { owner.retire(device) },
            onQueueFailure = { throw AssertionError("unexpected queue failure", it) },
        )

        assertEquals(1, device.closeCalls.get())
        assertFalse(cameraReplacementMayAcquire(terminal.currentOrPending()))
        owner.onClosed(device)
        assertTrue(cameraReplacementMayAcquire(terminal.currentOrPending()))
    }

    @Test
    fun `camera-thread teardown executes inline without posting or waiting`() {
        val terminal = CameraTeardownTerminal { error("unexpected quarantine") }
        val device = FakeDevice("camera-thread")
        val owner = ExactCameraDeviceCloseOwner<FakeDevice>(terminal) { it.closeCalls.incrementAndGet() }
        var postCalls = 0
        owner.claim(device)
        owner.armClose(device)

        dispatchCameraTeardown(
            onCameraThread = true,
            beginQueueClose = { it() },
            postTeardown = { postCalls++; true },
            closeQueue = {},
            teardown = Runnable { owner.retire(device) },
            onQueueFailure = { throw AssertionError("unexpected queue failure", it) },
        )

        assertEquals(0, postCalls)
        assertEquals(1, device.closeCalls.get())
        assertEquals(CameraControllerCloseResult.PENDING, terminal.currentOrPending())
        owner.onClosed(device)
        assertEquals(CameraControllerCloseResult.STRICTLY_RELEASED, terminal.currentOrPending())
    }

    @Test
    fun `cleanup exception cannot replace exact onClosed proof with method-return proof`() {
        val failures = mutableListOf<Throwable>()
        val terminal = CameraTeardownTerminal { error("unexpected quarantine") }
        val device = FakeDevice("cleanup-failure")
        val owner = ExactCameraDeviceCloseOwner<FakeDevice>(terminal) { it.closeCalls.incrementAndGet() }
        owner.claim(device)
        owner.armClose(device)

        runCameraTeardownCleanup(
            cleanup = {
                owner.retire(device)
                error("reader cleanup failed")
            },
            onFailure = failures::add,
        )

        assertEquals(listOf("reader cleanup failed"), failures.map(Throwable::message))
        assertEquals(CameraControllerCloseResult.PENDING, terminal.currentOrPending())
        owner.onClosed(device)
        assertEquals(CameraControllerCloseResult.STRICTLY_RELEASED, terminal.currentOrPending())
    }

    @Test
    fun `racing close callers issue one device close and share exact callback terminal`() {
        val terminal = CameraTeardownTerminal { error("unexpected quarantine") }
        val device = FakeDevice("racing")
        val owner = ExactCameraDeviceCloseOwner<FakeDevice>(terminal) { it.closeCalls.incrementAndGet() }
        owner.claim(device)
        owner.armClose(device)
        val start = CountDownLatch(1)
        val callers = List(16) { index ->
            thread(name = "device-retire-$index") {
                start.await(1, TimeUnit.SECONDS)
                owner.retire(device)
            }
        }
        start.countDown()
        callers.forEach { it.join(1_000) }

        assertTrue(callers.none(Thread::isAlive))
        assertEquals(1, device.closeCalls.get())
        owner.onClosed(device)
        assertEquals(CameraControllerCloseResult.STRICTLY_RELEASED, terminal.currentOrPending())
    }

    @Test
    fun `synchronous open refusal proves no device and authorizes only after close starts`() {
        val terminal = CameraTeardownTerminal { error("unexpected quarantine") }
        val owner = ExactCameraDeviceCloseOwner<FakeDevice>(terminal) {
            error("no CameraDevice may be closed")
        }

        owner.proveNoDeviceWillArrive()
        assertEquals(CameraControllerCloseResult.PENDING, terminal.currentOrPending())
        owner.armClose(null)

        assertEquals(CameraControllerCloseResult.STRICTLY_RELEASED, terminal.currentOrPending())
        assertTrue(cameraReplacementMayAcquire(terminal.currentOrPending()))
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

    private fun failedOpenCallbackClosesExactlyOnce(name: String) {
        val terminal = CameraTeardownTerminal { error("unexpected quarantine") }
        val device = FakeDevice(name)
        val closedIdentities = Collections.synchronizedSet(
            Collections.newSetFromMap(IdentityHashMap<FakeDevice, Boolean>()),
        )
        val owner = ExactCameraDeviceCloseOwner<FakeDevice>(terminal) {
            it.closeCalls.incrementAndGet()
            closedIdentities += it
        }

        // Production onError/onDisconnected ordering: claim+retire before observer/recovery, then
        // idempotent controller close. A late intentional callback cannot close the handle twice.
        assertTrue(owner.claim(device, retire = true))
        owner.armClose(null)
        owner.retire(device)
        owner.claim(device, retire = true)

        assertEquals(1, device.closeCalls.get())
        assertEquals(setOf(device), closedIdentities)
        assertEquals(CameraControllerCloseResult.PENDING, terminal.currentOrPending())
        owner.onClosed(device)
        assertEquals(CameraControllerCloseResult.STRICTLY_RELEASED, terminal.currentOrPending())
    }

    private class FakeDevice(val name: String) {
        val closeCalls = AtomicInteger(0)

        override fun toString(): String = name
    }
}
