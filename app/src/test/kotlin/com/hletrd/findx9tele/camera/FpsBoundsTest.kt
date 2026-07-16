package com.hletrd.findx9tele.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the target-fps range selection ([fixedFpsBounds]/[autoFpsBounds]) — the documented
 * low-light-AE bug zone: photo AUTO must get the LOWEST available floor so AE can extend exposure
 * in the dark, while manual/video paths require an exact fixed range (a fixed [30,30] previously
 * pinned AE at 1/30 s).
 */
class FpsBoundsTest {

    // A realistic advertised set: fixed 24/30/60 plus variable floors.
    private val ranges = listOf(
        15 to 30,
        24 to 24,
        30 to 30,
        24 to 60,
        60 to 60,
        7 to 30,
    )

    @Test
    fun fixedPin_returnsOnlyExactRanges() {
        assertEquals(24 to 24, fixedFpsBounds(ranges, 24))
        assertEquals(30 to 30, fixedFpsBounds(ranges, 30))
        assertEquals(60 to 60, fixedFpsBounds(ranges, 60))
    }

    @Test
    fun variableOnlyRange_isNotAFixedCapabilityOrPin() {
        val variableOnly = listOf(15 to 30)
        assertEquals(emptyList<Int>(), fixedFpsValues(variableOnly))
        assertNull(fixedFpsBounds(variableOnly, 30))
    }

    @Test
    fun mixedRanges_exposeOnlySortedDistinctExactRates() {
        assertEquals(listOf(24, 30, 60), fixedFpsValues(ranges + listOf(30 to 30)))
    }

    @Test
    fun auto_picksLowestFloorForTheCeiling() {
        // Photo AUTO at 30 fps must choose [7,30], not [30,30] — the low-light exposure headroom.
        assertEquals(7 to 30, autoFpsBounds(ranges, 30))
    }

    @Test
    fun auto_fallsBackToCoveringRangeWhenNoMatchingCeiling() {
        assertEquals(15 to 30, autoFpsBounds(listOf(15 to 30), 24))
        assertNull(autoFpsBounds(ranges, 120))
    }
}
