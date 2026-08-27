package me.hletrd.telecampro.camera

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DngPreCaptureAllocationTest {
    @Test
    fun `still admission requires every process owner`() {
        assertTrue(allStillOutputOwnersAvailable(dng = true, retainedFamily = true, rejectedCleanup = true))
        assertFalse(allStillOutputOwnersAvailable(dng = false, retainedFamily = true, rejectedCleanup = true))
        assertFalse(allStillOutputOwnersAvailable(dng = true, retainedFamily = false, rejectedCleanup = true))
        assertFalse(allStillOutputOwnersAvailable(dng = true, retainedFamily = true, rejectedCleanup = false))
    }

    @Test
    fun `process admission singleton is executable and releases exactly`() {
        val lease = requireNotNull(ProcessDngPreCaptureAdmission.owner.tryAcquire())
        assertFalse(ProcessDngPreCaptureAdmission.owner.canAdmit())
        assertTrue(lease.release())
        assertTrue(ProcessDngPreCaptureAdmission.owner.canAdmit())
    }

    @Test
    fun `blocked identity acquisition is cancelled before Camera2 ownership and late row is cleaned`() {
        val dispatcher = RecordingPreNativeAllocationDispatcher(workerCount = 1, backlogCapacity = 1)
        val identityEntered = CountDownLatch(1)
        val releaseIdentity = CountDownLatch(1)
        val identityReturned = CountDownLatch(1)
        val retired = CountDownLatch(1)
        val cameraDispatches = AtomicInteger()
        val lateRows = CopyOnWriteArrayList<String>()
        val admission = DngPreCaptureAdmission()
        val lease = requireNotNull(admission.tryAcquire())
        val owner = DngPreCaptureAllocation(
            dispatch = dispatcher::dispatch,
            allocate = {
                identityEntered.countDown()
                releaseIdentity.await()
                identityReturned.countDown()
                "content://media/dng/late"
            },
            isCurrent = { true },
            onReady = { cameraDispatches.incrementAndGet() },
            onLateValue = lateRows::add,
            onFailure = {},
            onRetired = {
                lease.release()
                retired.countDown()
            },
        )

        try {
            assertEquals(RecordingPreNativeDispatch.ACCEPTED, owner.start())
            assertTrue(identityEntered.await(5, TimeUnit.SECONDS))
            assertFalse(admission.canAdmit())

            // Lifecycle/optics cancellation is independent of the uncancellable Binder return.
            assertTrue(owner.cancel())
            assertTrue(retired.await(250, TimeUnit.MILLISECONDS))
            assertTrue(admission.canAdmit())
            assertEquals(0, cameraDispatches.get())

            releaseIdentity.countDown()
            assertTrue(identityReturned.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (lateRows.isEmpty() && System.nanoTime() < deadline) Thread.yield()
            assertEquals(listOf("content://media/dng/late"), lateRows.toList())
            assertEquals(0, cameraDispatches.get())
            assertFalse(owner.cancel())
        } finally {
            releaseIdentity.countDown()
            dispatcher.shutdown()
        }
    }

    @Test
    fun `claimed allocation reaches Camera2 exactly once and leaves cancellation ownership`() {
        val dispatcher = RecordingPreNativeAllocationDispatcher(workerCount = 1, backlogCapacity = 1)
        val ready = CountDownLatch(1)
        val claimed = AtomicInteger()
        val retired = AtomicInteger()
        var cameraValue: String? = null
        val owner = DngPreCaptureAllocation(
            dispatch = dispatcher::dispatch,
            allocate = { "content://media/dng/1" },
            isCurrent = { true },
            onReady = {
                cameraValue = it
                ready.countDown()
            },
            onLateValue = { error("claimed allocation must not become late") },
            onFailure = { error("successful allocation must not fail") },
            onRetired = { retired.incrementAndGet() },
            onClaimed = { claimed.incrementAndGet() },
        )

        try {
            assertEquals(RecordingPreNativeDispatch.ACCEPTED, owner.start())
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            assertEquals("content://media/dng/1", cameraValue)
            assertEquals(1, claimed.get())
            assertEquals(0, retired.get())
            assertFalse(owner.cancel())
        } finally {
            dispatcher.shutdown()
        }
    }

    @Test
    fun `superseded allocation never reaches Camera2 and is routed to exact late cleanup`() {
        val dispatcher = RecordingPreNativeAllocationDispatcher(workerCount = 1, backlogCapacity = 1)
        val terminal = CountDownLatch(1)
        val late = CopyOnWriteArrayList<String>()
        val cameraCalled = AtomicBoolean(false)
        val owner = DngPreCaptureAllocation(
            dispatch = dispatcher::dispatch,
            allocate = { "content://media/dng/superseded" },
            isCurrent = { false },
            onReady = { cameraCalled.set(true) },
            onLateValue = late::add,
            onFailure = {},
            onRetired = { terminal.countDown() },
        )

        try {
            assertEquals(RecordingPreNativeDispatch.ACCEPTED, owner.start())
            assertTrue(terminal.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("content://media/dng/superseded"), late.toList())
            assertFalse(cameraCalled.get())
        } finally {
            dispatcher.shutdown()
        }
    }

    @Test
    fun `allocation failure and dispatcher rejection retire without inline provider fallback`() {
        val failed = AtomicInteger()
        val retired = AtomicInteger()
        val allocationRan = AtomicBoolean(false)
        val owner = DngPreCaptureAllocation<String>(
            dispatch = { RecordingPreNativeSubmission(RecordingPreNativeDispatch.OVERFLOW) },
            allocate = {
                allocationRan.set(true)
                "row"
            },
            isCurrent = { true },
            onReady = { error("rejected dispatcher cannot publish") },
            onLateValue = { error("rejected dispatcher cannot invent a row") },
            onFailure = { failure ->
                assertNull(failure)
                failed.incrementAndGet()
            },
            onRetired = { retired.incrementAndGet() },
        )

        assertEquals(RecordingPreNativeDispatch.OVERFLOW, owner.start())
        assertFalse(allocationRan.get())
        assertEquals(1, failed.get())
        assertEquals(1, retired.get())
        assertFalse(owner.cancel())

        val dispatcher = RecordingPreNativeAllocationDispatcher(workerCount = 1, backlogCapacity = 1)
        val failedTerminal = CountDownLatch(1)
        val nullOwner = DngPreCaptureAllocation<String>(
            dispatch = dispatcher::dispatch,
            allocate = { null },
            isCurrent = { true },
            onReady = { error("null allocation cannot publish") },
            onLateValue = { error("null allocation cannot be cleaned") },
            onFailure = { failure -> assertNull(failure) },
            onRetired = { failedTerminal.countDown() },
        )
        try {
            assertEquals(RecordingPreNativeDispatch.ACCEPTED, nullOwner.start())
            assertTrue(failedTerminal.await(5, TimeUnit.SECONDS))
        } finally {
            dispatcher.shutdown()
        }
    }

    @Test
    fun `claimed onReady failure routes exact row to late cleanup and retires ownership`() {
        val dispatcher = RecordingPreNativeAllocationDispatcher(workerCount = 1, backlogCapacity = 1)
        val terminal = CountDownLatch(1)
        val late = CopyOnWriteArrayList<String>()
        val failures = CopyOnWriteArrayList<Throwable?>()
        val owner = DngPreCaptureAllocation(
            dispatch = dispatcher::dispatch,
            allocate = { "content://media/dng/ready-failure" },
            isCurrent = { true },
            onReady = { error("injected Camera2 dispatch failure") },
            onLateValue = late::add,
            onFailure = failures::add,
            onRetired = { terminal.countDown() },
        )

        try {
            assertEquals(RecordingPreNativeDispatch.ACCEPTED, owner.start())
            assertTrue(terminal.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("content://media/dng/ready-failure"), late.toList())
            assertEquals(1, failures.size)
            assertTrue(failures.single() is IllegalStateException)
            assertFalse(owner.cancel())
        } finally {
            dispatcher.shutdown()
        }
    }

    @Test
    fun `allocation deadline retires current request and makes a later row cleanup only`() {
        var timeout: (() -> Unit)? = null
        var providerTask: (() -> Unit)? = null
        val failures = CopyOnWriteArrayList<Throwable?>()
        val late = CopyOnWriteArrayList<String>()
        val retired = AtomicInteger()
        val ready = AtomicInteger()
        val owner = DngPreCaptureAllocation(
            dispatch = { task ->
                providerTask = task
                RecordingPreNativeSubmission(RecordingPreNativeDispatch.ACCEPTED)
            },
            allocate = { "content://media/dng/late-timeout" },
            isCurrent = { true },
            onReady = { ready.incrementAndGet() },
            onLateValue = late::add,
            onFailure = failures::add,
            onRetired = { retired.incrementAndGet() },
            deadlineScheduler = RecordingTeardownScheduler { _, action ->
                timeout = action
                RecordingTeardownCancellation {}
            },
            deadlineMs = 1L,
        )

        assertEquals(RecordingPreNativeDispatch.ACCEPTED, owner.start())
        checkNotNull(timeout).invoke()
        assertTrue(failures.single() is java.util.concurrent.TimeoutException)
        assertEquals(1, retired.get())
        checkNotNull(providerTask).invoke()
        assertEquals(listOf("content://media/dng/late-timeout"), late.toList())
        assertEquals(0, ready.get())
        assertEquals(1, retired.get())
    }

    @Test
    fun `deadline scheduler rejection fails before provider dispatch`() {
        val failures = CopyOnWriteArrayList<Throwable?>()
        val retired = AtomicInteger()
        val providerDispatched = AtomicBoolean(false)
        val owner = DngPreCaptureAllocation<String>(
            dispatch = {
                providerDispatched.set(true)
                RecordingPreNativeSubmission(RecordingPreNativeDispatch.ACCEPTED)
            },
            allocate = { "row" },
            isCurrent = { true },
            onReady = { error("rejected deadline cannot reach Camera2") },
            onLateValue = { error("rejected deadline cannot allocate") },
            onFailure = failures::add,
            onRetired = { retired.incrementAndGet() },
            deadlineScheduler = RecordingTeardownScheduler { _, _ -> null },
            deadlineMs = 1L,
        )

        assertEquals(RecordingPreNativeDispatch.SHUTDOWN, owner.start())
        assertFalse(providerDispatched.get())
        assertTrue(failures.single() is java.util.concurrent.TimeoutException)
        assertEquals(1, retired.get())
    }

    @Test
    fun `DNG process admission is exactly once and reusable after cancellation`() {
        val admission = DngPreCaptureAdmission()
        val first = requireNotNull(admission.tryAcquire())
        assertFalse(admission.canAdmit())
        assertNull(admission.tryAcquire())
        assertTrue(first.release())
        assertFalse(first.release())
        assertTrue(admission.canAdmit())
        assertTrue(requireNotNull(admission.tryAcquire()).release())
    }
}
