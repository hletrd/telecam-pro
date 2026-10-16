package me.hletrd.telecampro.stab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The timestamped rotation window that replaced `drainRotation` (device-diagnosed 2026-08-11).
 *
 * The defect it exists to fix: the gyro was integrated over the interval between DRAIN CALLS, while
 * the frames being judged had left the sensor earlier. A slow one-direction pan hid it (rotation
 * keeps one sign, so a shifted window still points the same way); a reversing pan inverted the
 * verdict. So the tests that matter here are the ones about REVERSAL and about REFUSAL — a window
 * that cannot be answered must return null, never a fabricated zero.
 */
class RotationWindowTest {

    /** Constant-rate rotation: cumulative angle is t * rate, sampled every 20 ms. */
    private fun ramp(rate: Float, n: Int = 16, stepNs: Long = 20_000_000L): Triple<LongArray, FloatArray, FloatArray> {
        val t = LongArray(n) { 1_000_000_000L + it * stepNs }
        val y = FloatArray(n) { (it * stepNs) * 1e-9f * rate }
        val p = FloatArray(n) { 0f }
        return Triple(t, y, p)
    }

    @Test
    fun `integrates a constant rate over an exact sample window`() {
        val (t, y, p) = ramp(rate = 1.0f) // 1 rad/s
        // 100 ms window -> 0.1 rad
        val r = rotationBetweenSamples(t, y, p, count = t.size, head = 0, fromNs = t[0], toNs = t[5])
        assertNotNull(r)
        assertEquals(0.1f, r!![0], 1e-4f)
    }

    /** The window rarely lands on samples; the answer must interpolate, not snap to a sample. */
    @Test
    fun `interpolates between samples`() {
        val (t, y, p) = ramp(rate = 1.0f)
        val mid0 = t[1] + 10_000_000L // half-way into a 20 ms step
        val mid1 = t[4] + 10_000_000L
        val r = rotationBetweenSamples(t, y, p, count = t.size, head = 0, fromNs = mid0, toNs = mid1)
        assertNotNull(r)
        assertEquals(0.06f, r!![0], 1e-4f) // exactly 60 ms
    }

    /**
     * THE REGRESSION FENCE. Rotate one way then back; a window over the RETURN leg must report the
     * opposite sign. The old drain could not do this — it only knew "since you last asked", so a
     * window that closed before the asking was unrepresentable and the caller silently got the
     * wrong leg.
     */
    @Test
    fun `a reversing sweep reports each leg with its own sign`() {
        val n = 21
        val step = 20_000_000L
        val t = LongArray(n) { 1_000_000_000L + it * step }
        // +1 rad/s for 10 samples, then -1 rad/s back down.
        val y = FloatArray(n) { i -> if (i <= 10) i * 0.02f else (0.2f - (i - 10) * 0.02f) }
        val p = FloatArray(n) { 0f }

        val out = rotationBetweenSamples(t, y, p, n, 0, t[0], t[10])!!
        val back = rotationBetweenSamples(t, y, p, n, 0, t[10], t[20])!!

        assertEquals(0.2f, out[0], 1e-4f)
        assertEquals(-0.2f, back[0], 1e-4f)
        assertEquals("the two legs must cancel", 0f, out[0] + back[0], 1e-4f)
    }

    /** Yaw and pitch are independent channels and must not bleed into each other. */
    @Test
    fun `yaw and pitch are independent`() {
        val n = 8
        val t = LongArray(n) { 1_000_000_000L + it * 20_000_000L }
        val y = FloatArray(n) { it * 0.01f }
        val p = FloatArray(n) { -it * 0.03f }
        val r = rotationBetweenSamples(t, y, p, n, 0, t[0], t[4])!!
        assertEquals(0.04f, r[0], 1e-5f)
        assertEquals(-0.12f, r[1], 1e-5f)
    }

    // --- refusals: null, never a fabricated zero ------------------------------------------------

    @Test
    fun `refuses a window older than the retained history`() {
        val (t, y, p) = ramp(rate = 1.0f)
        assertNull(rotationBetweenSamples(t, y, p, t.size, 0, t[0] - 1, t[3]))
    }

    @Test
    fun `refuses a window newer than the retained history`() {
        val (t, y, p) = ramp(rate = 1.0f)
        assertNull(rotationBetweenSamples(t, y, p, t.size, 0, t[3], t[t.size - 1] + 1))
    }

    @Test
    fun `refuses fewer than two samples`() {
        val (t, y, p) = ramp(rate = 1.0f)
        assertNull(rotationBetweenSamples(t, y, p, 0, 0, t[0], t[1]))
        assertNull(rotationBetweenSamples(t, y, p, 1, 0, t[0], t[1]))
    }

    @Test
    fun `refuses an inverted or empty window`() {
        val (t, y, p) = ramp(rate = 1.0f)
        assertNull(rotationBetweenSamples(t, y, p, t.size, 0, t[5], t[2]))
        assertNull(rotationBetweenSamples(t, y, p, t.size, 0, t[5], t[5]))
    }

    /**
     * Once the ring wraps, sample 0 is at [head], not at index 0. A window read with the wrong
     * origin would silently return a value from an unrelated stretch of time — plausible, wrong,
     * and undetectable downstream.
     */
    @Test
    fun `reads correctly after the ring has wrapped`() {
        val cap = 8
        val step = 20_000_000L
        val t = LongArray(cap)
        val y = FloatArray(cap)
        val p = FloatArray(cap)
        // 12 samples into an 8-slot ring: head lands at 4, oldest retained is sample 4.
        for (i in 0 until 12) {
            val slot = i % cap
            t[slot] = 1_000_000_000L + i * step
            y[slot] = i * 0.01f
        }
        val head = 12 % cap
        val oldest = 1_000_000_000L + 4 * step
        val newest = 1_000_000_000L + 11 * step

        // Anything before the oldest retained sample is gone, and must be refused.
        assertNull(rotationBetweenSamples(t, y, p, cap, head, oldest - 1, newest))

        val r = rotationBetweenSamples(t, y, p, cap, head, oldest, newest)
        assertNotNull(r)
        assertEquals((11 - 4) * 0.01f, r!![0], 1e-5f)
    }
}
