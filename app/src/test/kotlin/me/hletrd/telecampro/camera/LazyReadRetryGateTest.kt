package me.hletrd.telecampro.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // AGG3-9: a metering build that consumed the gate must not cost the next shot its re-read.
    @Test
    fun `a shot re-reads even after metering consumed the gate at the same instant`() {
        val now = 0L
        val gate = LazyReadRetryGate(minIntervalNs = 1_000L, nowNs = { now })
        var calls = 0
        val failingThenSucceeding: () -> String? = { if (calls++ == 0) null else "chars" }

        assertNull("metering read fails and consumes the gate", lazyCharacteristicsRead(null, false, gate, failingThenSucceeding))
        assertNull("metering stays rate-limited", lazyCharacteristicsRead(null, false, gate, failingThenSucceeding))
        assertEquals(1, calls)
        assertEquals("chars", lazyCharacteristicsRead(null, true, gate, failingThenSucceeding))
        assertEquals(2, calls)
    }

    @Test
    fun `a cached value never reads`() {
        val gate = LazyReadRetryGate(minIntervalNs = 1_000L, nowNs = { 0L })
        assertEquals("cached", lazyCharacteristicsRead("cached", true, gate) { error("must not read") })
        assertTrue("the gate stays unconsumed", gate.tryAcquire())
    }

    // AGG4-37: one gate-bypassing re-read per capture CHAIN, not per completion.
    @Test
    fun `a failing read costs one bypass per chain, then the shared gate`() {
        val now = 0L
        val gate = LazyReadRetryGate(minIntervalNs = 1_000L, nowNs = { now })
        val chain = ChainCharacteristicsReread()
        var calls = 0
        val failing: () -> String? = { calls++; null }

        // Metering consumed the gate a moment before the shutter (the AGG3-9 case).
        assertNull(lazyCharacteristicsRead(null, false, gate, failing))
        assertEquals(1, calls)
        chain.arm()
        repeat(20) { assertNull(lazyCharacteristicsRead(null, chain.consume(), gate, failing)) }
        assertEquals("the chain head re-read once; its 19 continuations hit the spent gate", 2, calls)

        // The next chain head gets its own bypass again.
        chain.arm()
        assertNull(lazyCharacteristicsRead(null, chain.consume(), gate, failing))
        assertEquals(3, calls)
    }

    @Test
    fun `an unarmed completion never bypasses the gate`() {
        val chain = ChainCharacteristicsReread()
        assertFalse(chain.consume())
        chain.arm()
        assertTrue(chain.consume())
        assertFalse(chain.consume())
    }
}
