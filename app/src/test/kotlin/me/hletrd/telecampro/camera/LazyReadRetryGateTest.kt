package me.hletrd.telecampro.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AGG2-19 / PERF2-5: the lazy `rawChars` re-read runs on the camera handler (metering request build
 * and `tryComplete`). While the characteristics read keeps failing, every shot — each frame of a
 * BURST/AEB chain — used to pay one Binder call there. The gate admits the first retry at once and
 * then at most one per interval.
 */
class LazyReadRetryGateTest {
    @Test
    fun `first retry is admitted immediately, then one per interval`() {
        var now = 5_000L
        val gate = LazyReadRetryGate(minIntervalNs = 1_000L, nowNs = { now })

        assertTrue(gate.tryAcquire())
        assertFalse("same instant refuses", gate.tryAcquire())
        now += 999L
        assertFalse("inside the interval refuses", gate.tryAcquire())
        now += 1L
        assertTrue("the interval boundary admits", gate.tryAcquire())
        assertFalse(gate.tryAcquire())
    }

    @Test
    fun `a negative monotonic origin still admits the first retry`() {
        // System.nanoTime() may be negative; "never attempted" must not be encoded as a time value.
        var now = Long.MIN_VALUE + 10L
        val gate = LazyReadRetryGate(minIntervalNs = 1_000L, nowNs = { now })

        assertTrue(gate.tryAcquire())
        now += 500L
        assertFalse(gate.tryAcquire())
    }
}
