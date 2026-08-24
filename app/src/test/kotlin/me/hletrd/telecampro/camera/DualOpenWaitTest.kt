package me.hletrd.telecampro.camera

import org.junit.Assert.assertEquals
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
}
