package com.hletrd.findx9tele.ui.overlays

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Boundary pins for [waveformAlphaBucket] (TEST4-13, now internal): the sqrt-curve bucketing
 * feeds the P6.3-optimized waveform draw and must stay inside [0, buckets-1] for every input.
 */
class WaveformAlphaBucketTest {

    @Test
    fun `zero maps to the first bucket and max to the last`() {
        assertEquals(0, waveformAlphaBucket(0, 100, 8))
        assertEquals(7, waveformAlphaBucket(100, 100, 8))
    }

    @Test
    fun `overflow and negative inputs clamp into range`() {
        assertEquals(7, waveformAlphaBucket(1000, 100, 8))
        assertEquals(0, waveformAlphaBucket(-5, 100, 8))
    }

    @Test
    fun `sqrt curve lifts small counts above linear`() {
        // A quarter of max sits at sqrt(0.25)=0.5 of the bucket range, not 0.25.
        val bucket = waveformAlphaBucket(25, 100, 9)
        assertTrue("expected midrange bucket, was $bucket", bucket == 4)
    }
}
