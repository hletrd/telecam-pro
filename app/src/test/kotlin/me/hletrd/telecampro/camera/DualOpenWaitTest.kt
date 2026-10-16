package me.hletrd.telecampro.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DualOpenWaitTest {
    @Test
    fun `device signal ends the wait immediately`() {
        var waits = 0

        val result = waitForDualOpenBoundary(
            awaitSlice = { waits += 1; true },
            shouldContinue = { true },
            nowNanos = { 0L },
        )

        assertEquals(DualOpenWaitResult.SIGNALED, result)
        assertEquals(1, waits)
    }

    @Test
    fun `production clock default is exercised by an immediate signal`() {
        assertEquals(
            DualOpenWaitResult.SIGNALED,
            waitForDualOpenBoundary(
                awaitSlice = { true },
                shouldContinue = { true },
            ),
        )
    }

    @Test
    fun `newer generation ends a silent open after one ownership slice`() {
        var now = 0L
        var owns = true
        var waits = 0

        val result = waitForDualOpenBoundary(
            awaitSlice = { slice ->
                waits += 1
                now += slice
                owns = false
                false
            },
            shouldContinue = { owns },
            nowNanos = { now },
        )

        assertEquals(DualOpenWaitResult.SUPERSEDED, result)
        assertEquals(1, waits)
        assertEquals(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(20), now)
    }

    @Test
    fun `lifecycle retirement before the wait performs no blocking call`() {
        val result = waitForDualOpenBoundary(
            awaitSlice = { error("retired ownership must not wait") },
            shouldContinue = { false },
            nowNanos = { 0L },
        )

        assertEquals(DualOpenWaitResult.SUPERSEDED, result)
    }

    @Test
    fun `silent current generation retains the absolute timeout`() {
        var now = 0L
        var waited = 0L
        val timeout = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(55)
        val poll = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(20)

        val result = waitForDualOpenBoundary(
            awaitSlice = { slice -> now += slice; waited += slice; false },
            shouldContinue = { true },
            timeoutNanos = timeout,
            ownershipPollNanos = poll,
            nowNanos = { now },
        )

        assertEquals(DualOpenWaitResult.TIMED_OUT, result)
        assertEquals(timeout, waited)
    }

    @Test
    fun `interruption retires the wait and preserves the thread signal`() {
        try {
            val result = waitForDualOpenBoundary(
                awaitSlice = { throw InterruptedException("test") },
                shouldContinue = { true },
                nowNanos = { 0L },
            )

            assertEquals(DualOpenWaitResult.SUPERSEDED, result)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `supersession restores live outgoing when candidate owns or already cleared the slot`() {
        val outgoing = Any()
        val candidate = Any()
        assertEquals(
            DualOpenSupersessionCleanup.RESTORE_OUTGOING,
            dualOpenSupersessionCleanup(candidate, candidate, outgoing, outgoingRestorable = true),
        )
        assertEquals(
            DualOpenSupersessionCleanup.RESTORE_OUTGOING,
            dualOpenSupersessionCleanup(null, candidate, outgoing, outgoingRestorable = true),
        )
    }

    @Test
    fun `supersession keeps an already restored outgoing owner`() {
        val outgoing = Any()
        assertEquals(
            DualOpenSupersessionCleanup.KEEP_OUTGOING,
            dualOpenSupersessionCleanup(outgoing, Any(), outgoing, outgoingRestorable = true),
        )
    }

    @Test
    fun `supersession releases outgoing rather than overwriting a newer controller`() {
        assertEquals(
            DualOpenSupersessionCleanup.RELEASE_OUTGOING,
            dualOpenSupersessionCleanup(Any(), Any(), Any(), outgoingRestorable = true),
        )
    }

    @Test
    fun `supersession totalizes the absent outgoing and vacant slot`() {
        assertEquals(
            DualOpenSupersessionCleanup.RESTORE_OUTGOING,
            dualOpenSupersessionCleanup(null, Any(), null, outgoingRestorable = true),
        )
    }

    @Test
    fun `supersession never restores a terminal outgoing owner`() {
        val outgoing = Any()
        val candidate = Any()
        assertEquals(
            DualOpenSupersessionCleanup.RESTORE_VACANT,
            dualOpenSupersessionCleanup(candidate, candidate, outgoing, outgoingRestorable = false),
        )
        assertEquals(
            DualOpenSupersessionCleanup.RESTORE_VACANT,
            dualOpenSupersessionCleanup(null, candidate, outgoing, outgoingRestorable = false),
        )
        assertEquals(
            DualOpenSupersessionCleanup.RESTORE_VACANT,
            dualOpenSupersessionCleanup(outgoing, candidate, outgoing, outgoingRestorable = false),
        )
    }
}
