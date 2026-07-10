package com.hletrd.findx9tele.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the target-fps range selection ([clampFpsBounds]/[autoFpsBounds]) — the documented
 * low-light-AE bug zone: photo AUTO must get the LOWEST available floor so AE can extend exposure
 * in the dark, while manual/video paths prefer an exact fixed range (a fixed [30,30] previously
 * pinned AE at 1/30 s).
 */
class FpsBoundsTest {

    // A realistic advertised set: fixed 30/60 plus variable floors.
    private val ranges = listOf(
        15 to 30,
        30 to 30,
        24 to 60,
        60 to 60,
        7 to 30,
    )

    @Test
    fun clamp_prefersExactFixedRange() {
        assertEquals(30 to 30, clampFpsBounds(ranges, 30))
        assertEquals(60 to 60, clampFpsBounds(ranges, 60))
    }

    @Test
    fun clamp_fallsBackToCoveringRange() {
        // No fixed [24,24]; the first range covering 24 wins (declaration order).
        assertEquals(15 to 30, clampFpsBounds(ranges, 24))
    }

    @Test
    fun clamp_unmatchable_returnsNull() {
        assertNull(clampFpsBounds(ranges, 120))
    }

    @Test
    fun auto_picksLowestFloorForTheCeiling() {
        // Photo AUTO at 30 fps must choose [7,30], not [30,30] — the low-light exposure headroom.
        assertEquals(7 to 30, autoFpsBounds(ranges, 30))
    }

    @Test
    fun auto_fallsBackToClampWhenNoMatchingCeiling() {
        assertEquals(15 to 30, autoFpsBounds(ranges, 24))
        assertNull(autoFpsBounds(ranges, 120))
    }
}
