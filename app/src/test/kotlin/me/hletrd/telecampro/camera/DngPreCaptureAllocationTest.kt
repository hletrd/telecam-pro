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
    fun `combined publication serializes an older callback before newer truth`() {
        val current = AtomicBoolean(false)
        val events = CopyOnWriteArrayList<Boolean>()
        val olderEntered = CountDownLatch(1)
        val releaseOlder = CountDownLatch(1)
        val publication = StillAdmissionPublication(
            snapshot = current::get,
            deliver = { available ->
                if (!available) {
                    olderEntered.countDown()
                    releaseOlder.await()
                }
                events += available
            },
        )
        val older = Thread(publication::publish).apply { start() }
        assertTrue(olderEntered.await(2, TimeUnit.SECONDS))
        current.set(true)
        val newer = Thread(publication::publish).apply { start() }

        newer.join(50L)
        assertTrue("newer delivery must wait behind the admitted older publication", newer.isAlive)
        releaseOlder.countDown()
        older.join(2_000L)
        newer.join(2_000L)

        assertEquals(listOf(false, true), events.toList())
    }

    @Test
    fun `local and process edges share one cache and reset replays current truth`() {
        val current = AtomicBoolean(true)
        val events = mutableListOf<Boolean>()
        val publication = StillAdmissionPublication(current::get, events::add)

        publication.publish()
        publication.publish()
        current.set(false)
        publication.publish()
        current.set(true)
        publication.publish()
        publication.reset()
        publication.publish()

        assertEquals(listOf(true, false, true, true), events)
    }

    @Test
    fun `process admission singleton is executable and releases exactly`() {
        val lease = requireNotNull(ProcessDngPreCaptureAdmission.owner.tryAcquire())
        assertFalse(ProcessDngPreCaptureAdmission.owner.canAdmit())
        assertTrue(lease.release())
        assertTrue(ProcessDngPreCaptureAdmission.owner.canAdmit())
    }

    @Test
    fun `replacement subscriber observes every old terminal release and detached listener is inert`() {
        listOf("success", "error", "timeout", "cancel").forEach { terminal ->
            val admission = DngPreCaptureAdmission()
            val oldEvents = CopyOnWriteArrayList<Boolean>()
            val replacementEvents = CopyOnWriteArrayList<Boolean>()
            val oldSubscription = admission.subscribe(oldEvents::add)
            val lease = requireNotNull(admission.tryAcquire())
            val replacementSubscription = admission.subscribe(replacementEvents::add)

            assertEquals("$terminal replacement initial truth", listOf(false), replacementEvents)
            oldSubscription.close()
            val oldAfterDetach = oldEvents.toList()
            assertTrue("$terminal must release exactly", lease.release())

            assertEquals("$terminal replacement terminal truth", listOf(false, true), replacementEvents)
            assertEquals("$terminal detached owner must remain inert", oldAfterDetach, oldEvents.toList())
            replacementSubscription.close()
        }
    }

    @Test
    fun `success error timeout and cancel allocation terminals reopen replacement subscription`() {
        listOf("success", "error", "timeout", "cancel").forEach { terminal ->
            val admission = DngPreCaptureAdmission()
            val lease = requireNotNull(admission.tryAcquire())
            val replacementEvents = CopyOnWriteArrayList<Boolean>()
            val replacement = admission.subscribe(replacementEvents::add)
            var timeout: (() -> Unit)? = null
            var queued: (() -> Unit)? = null
            val owner = DngPreCaptureAllocation<String>(
                dispatch = { task ->
                    if (terminal == "success" || terminal == "error") task() else queued = task
                    RecordingPreNativeSubmission(RecordingPreNativeDispatch.ACCEPTED)
                },
                allocate = { if (terminal == "error") null else "row" },
                isCurrent = { true },
                onReady = { lease.release() },
                onLateValue = {},
                onFailure = {},
                onRetired = { lease.release() },
                deadlineScheduler = if (terminal == "timeout") {
                    RecordingTeardownScheduler { _, action ->
                        timeout = action
                        RecordingTeardownCancellation {}
                    }
                } else {
                    null
                },
            )

            assertEquals(RecordingPreNativeDispatch.ACCEPTED, owner.start())
            when (terminal) {
                "timeout" -> checkNotNull(timeout).invoke()
                "cancel" -> assertTrue(owner.cancel())
            }

            assertEquals("$terminal terminal", listOf(false, true), replacementEvents.toList())
            replacement.close()
            // Queued provider work is deliberately not run: timeout/cancel caller ownership has
            // already retired, and the existing late-value tests cover its eventual cleanup path.
            assertTrue(terminal !in setOf("timeout", "cancel") || queued != null)
        }
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
    fun `deadline winning after allocation delivery prevents Camera2 claim`() {
        var timeout: (() -> Unit)? = null
        val failures = CopyOnWriteArrayList<Throwable?>()
        val late = CopyOnWriteArrayList<String>()
        val retired = AtomicInteger()
        val ready = AtomicInteger()
        val owner = DngPreCaptureAllocation(
            dispatch = { task ->
                task()
                RecordingPreNativeSubmission(RecordingPreNativeDispatch.ACCEPTED)
            },
            allocate = { "content://media/dng/deadline-race" },
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
            beforeDeadlineCompletion = { checkNotNull(timeout).invoke() },
        )

        assertEquals(RecordingPreNativeDispatch.ACCEPTED, owner.start())
        assertTrue(failures.single() is java.util.concurrent.TimeoutException)
        assertEquals(listOf("content://media/dng/deadline-race"), late.toList())
        assertEquals(0, ready.get())
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
