package me.hletrd.telecampro.camera

import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import me.hletrd.telecampro.video.RecorderQuarantineAdmissionGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingPreNativeAllocationTest {
    @Test
    fun `Stop retires a blocked provider without holding replacement Engine admission`() {
        val dispatcher = RecordingPreNativeAllocationDispatcher(workerCount = 1, backlogCapacity = 1)
        val providerEntered = CountDownLatch(1)
        val releaseProvider = CountDownLatch(1)
        val providerReturned = CountDownLatch(1)
        val processGate = RecorderQuarantineAdmissionGate()
        val oldOwner = Any()
        val replacementOwner = Any()
        val oldToken = checkNotNull(processGate.snapshot(oldOwner))
        val latch = RecordingAdmissionLatch()
        assertTrue(latch.tryBeginAdmission())
        val terminalOwnersReleased = AtomicBoolean(false)
        val retiredCount = AtomicInteger()
        val lateRows = CopyOnWriteArrayList<String>()
        val delivery = CopyOnWriteArrayList<RecordingPreNativeDelivery>()
        val attempt = RecordingPreNativeAllocationAttempt(
            onRetired = {
                processGate.abandonPending(oldToken)
                latch.completeAdmission(succeeded = false)
                terminalOwnersReleased.set(true)
                retiredCount.incrementAndGet()
            },
            onLateValue = lateRows::add,
        )

        try {
            val submission = dispatcher.dispatch {
                providerEntered.countDown()
                releaseProvider.await()
                delivery += attempt.deliver(Result.success("content://late-row"))
                providerReturned.countDown()
            }
            assertEquals(RecordingPreNativeDispatch.ACCEPTED, submission.dispatch)
            submission.cancellation?.let(attempt::attachCancellation)
            assertTrue(providerEntered.await(5, TimeUnit.SECONDS))

            // This is the Stop/timeout/release edge: it does not wait for MediaProvider.
            assertTrue(attempt.retire())
            assertTrue(terminalOwnersReleased.get())
            assertEquals(1, retiredCount.get())
            assertTrue(latch.tryBeginAdmission())
            latch.completeAdmission(succeeded = false)
            assertNotNull(processGate.snapshot(replacementOwner))

            releaseProvider.countDown()
            assertTrue(providerReturned.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(RecordingPreNativeDelivery.STALE), delivery.toList())
            assertEquals(listOf("content://late-row"), lateRows.toList())
            assertNull(attempt.claim())
            assertFalse(attempt.retire())
            assertEquals(1, retiredCount.get())
        } finally {
            releaseProvider.countDown()
            dispatcher.shutdown()
        }
    }

    @Test
    fun `timeout and Engine release share one idempotent retirement owner`() {
        val retired = AtomicInteger()
        val late = CopyOnWriteArrayList<String>()
        val canceled = AtomicInteger()
        val attempt = RecordingPreNativeAllocationAttempt(
            onRetired = { retired.incrementAndGet() },
            onLateValue = late::add,
        )
        attempt.attachCancellation(RecordingPreNativeCancellation { canceled.incrementAndGet() })

        assertTrue(attempt.retire()) // timeout wins
        assertFalse(attempt.retire()) // Engine release loses harmlessly
        assertEquals(RecordingPreNativeDelivery.STALE, attempt.deliver(Result.success("late")))
        assertEquals(1, retired.get())
        assertEquals(1, canceled.get())
        assertEquals(listOf("late"), late.toList())
        assertNull(attempt.claim())
        assertTrue(attempt.isRetired())

        val lateCancellation = AtomicInteger()
        attempt.attachCancellation(RecordingPreNativeCancellation { lateCancellation.incrementAndGet() })
        assertEquals(1, lateCancellation.get())
    }

    @Test
    fun `armed allocation deadline makes a later provider row stale`() {
        var timeout: (() -> Unit)? = null
        val late = CopyOnWriteArrayList<String>()
        val retired = AtomicInteger()
        val attempt = RecordingPreNativeAllocationAttempt(
            onRetired = { retired.incrementAndGet() },
            onLateValue = late::add,
        )
        val deadline = RecordingOperationDeadline(
            scheduler = RecordingTeardownScheduler { _, action ->
                timeout = action
                RecordingTeardownCancellation {}
            },
            timeoutMs = 1,
            failure = { java.util.concurrent.TimeoutException("allocation") },
            onTimeout = { attempt.retire() },
        )

        assertTrue(deadline.arm())
        checkNotNull(timeout).invoke()
        assertEquals(RecordingOperationState.TIMED_OUT, deadline.current())
        assertEquals(RecordingPreNativeDelivery.STALE, attempt.deliver(Result.success("late")))
        assertEquals(1, retired.get())
        assertEquals(listOf("late"), late.toList())
        assertNull(attempt.claim())
    }

    @Test
    fun `allocation failure retires admission while allocated retirement owns late cleanup`() {
        val failedRetired = AtomicInteger()
        val failed = RecordingPreNativeAllocationAttempt<String>(
            onRetired = { failedRetired.incrementAndGet() },
            onLateValue = { error("failure must not invent a row") },
        )
        assertEquals(
            RecordingPreNativeDelivery.FAILED,
            failed.deliver(Result.failure(IllegalStateException("provider failed"))),
        )
        assertEquals(1, failedRetired.get())
        assertTrue(failed.isRetired())

        val canceled = AtomicInteger()
        val retired = AtomicInteger()
        val late = CopyOnWriteArrayList<String>()
        val allocated = RecordingPreNativeAllocationAttempt(
            onRetired = { retired.incrementAndGet() },
            onLateValue = late::add,
        )
        assertEquals(RecordingPreNativeDelivery.READY, allocated.deliver(Result.success("row")))
        // A completed allocator has no queued/running Future left to cancel.
        allocated.attachCancellation(RecordingPreNativeCancellation { canceled.incrementAndGet() })
        assertTrue(allocated.retire())
        assertEquals(0, canceled.get())
        assertEquals(1, retired.get())
        assertEquals(listOf("row"), late.toList())
    }

    @Test
    fun `allocator saturation is bounded and canceled backlog frees capacity`() {
        val releaseWorkers = CountDownLatch(1)
        val workersEntered = CountDownLatch(2)
        val queuedRan = AtomicInteger()
        val dispatcher = RecordingPreNativeAllocationDispatcher(workerCount = 2, backlogCapacity = 4)

        try {
            repeat(2) {
                assertEquals(
                    RecordingPreNativeDispatch.ACCEPTED,
                    dispatcher.dispatch {
                        workersEntered.countDown()
                        releaseWorkers.await()
                    }.dispatch,
                )
            }
            assertTrue(workersEntered.await(5, TimeUnit.SECONDS))
            val queued = List(4) {
                dispatcher.dispatch { queuedRan.incrementAndGet() }
            }
            assertTrue(queued.all { it.dispatch == RecordingPreNativeDispatch.ACCEPTED })
            assertEquals(2, dispatcher.activeTaskCount())
            assertEquals(4, dispatcher.queuedTaskCount())
            assertEquals(
                RecordingPreNativeDispatch.OVERFLOW,
                dispatcher.dispatch { queuedRan.incrementAndGet() }.dispatch,
            )

            queued.forEach { it.cancellation?.cancel() }
            assertEquals(0, dispatcher.queuedTaskCount())
            val replacement = dispatcher.dispatch { queuedRan.incrementAndGet() }
            assertEquals(RecordingPreNativeDispatch.ACCEPTED, replacement.dispatch)
            replacement.cancellation?.cancel()
            assertEquals(0, dispatcher.queuedTaskCount())
            assertEquals(0, queuedRan.get())
        } finally {
            releaseWorkers.countDown()
            dispatcher.shutdown()
        }
    }

    @Test
    fun `claimed allocation transfers exactly once and cannot be retired as stale`() {
        val retired = AtomicInteger()
        val late = CopyOnWriteArrayList<String>()
        val attempt = RecordingPreNativeAllocationAttempt(
            onRetired = { retired.incrementAndGet() },
            onLateValue = late::add,
        )

        assertEquals(RecordingPreNativeDelivery.READY, attempt.deliver(Result.success("row")))
        assertEquals("row", attempt.claim())
        attempt.attachCancellation(RecordingPreNativeCancellation { error("claimed work is complete") })
        assertNull(attempt.claim())
        assertFalse(attempt.retire())
        assertEquals(0, retired.get())
        assertTrue(late.isEmpty())
    }

    @Test
    fun `dispatcher shutdown rejects without running or inlining provider work`() {
        val ran = AtomicBoolean(false)
        val dispatcher = RecordingPreNativeAllocationDispatcher(workerCount = 1, backlogCapacity = 1)
        dispatcher.shutdown()

        val submission = dispatcher.dispatch { ran.set(true) }
        assertEquals(RecordingPreNativeDispatch.SHUTDOWN, submission.dispatch)
        assertNull(submission.cancellation)
        assertFalse(ran.get())
    }

    @Test
    fun `process allocator accepts work through the shared finite owner`() {
        val ran = CountDownLatch(1)
        val submission = ProcessRecordingPreNativeAllocator.dispatch { ran.countDown() }

        assertEquals(RecordingPreNativeDispatch.ACCEPTED, submission.dispatch)
        assertTrue(ran.await(5, TimeUnit.SECONDS))
    }
}
