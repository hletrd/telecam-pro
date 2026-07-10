package com.hletrd.findx9tele.stab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure gravity-decision seams extracted from [GyroEis.onSensorChanged]:
 * [shouldUpdateOrientation]/[shouldUpdateRoll] (in-plane-gravity thresholds) and [snapToQuadrant]
 * (0/90/180/270 snap). No SensorManager, no device.
 */
class GyroEisMathTest {

    @Test
    fun `orientation gate flips exactly at the FLAT gravity threshold`() {
        val t = GyroEis.FLAT_GRAVITY_THRESHOLD // 4.9 (~half g)
        assertTrue(shouldUpdateOrientation(t + 0.1f, 0f))
        assertFalse(shouldUpdateOrientation(t - 0.1f, 0f))
        // Strictly greater-than: exactly at the threshold does NOT update.
        assertFalse(shouldUpdateOrientation(t, 0f))
    }

    @Test
    fun `roll gate flips exactly at the LEVEL gravity threshold`() {
        val t = GyroEis.LEVEL_GRAVITY_THRESHOLD // 2.5
        assertTrue(shouldUpdateRoll(t + 0.1f, 0f))
        assertFalse(shouldUpdateRoll(t - 0.1f, 0f))
        assertFalse(shouldUpdateRoll(t, 0f))
    }

    @Test
    fun `gates use the hypot magnitude, not either axis alone`() {
        // Each axis alone is below the LEVEL threshold; the combined magnitude clears it.
        assertTrue(shouldUpdateRoll(2.0f, 2.0f)) // hypot ~2.83 > 2.5
        // Each axis below the FLAT threshold; combined ~4.24 still short, then ~4.95 clears it.
        assertFalse(shouldUpdateOrientation(3.0f, 3.0f)) // hypot ~4.24
        assertTrue(shouldUpdateOrientation(3.5f, 3.5f)) // hypot ~4.95 > 4.9
    }

    @Test
    fun `snapToQuadrant rounds to the nearest 90 with 45-degree midpoints`() {
        assertEquals(0, snapToQuadrant(0f))
        assertEquals(0, snapToQuadrant(44.9f))
        assertEquals(90, snapToQuadrant(45.1f))
        assertEquals(90, snapToQuadrant(90f))
        assertEquals(90, snapToQuadrant(134.9f))
        assertEquals(180, snapToQuadrant(135.1f))
        assertEquals(180, snapToQuadrant(180f))
        assertEquals(270, snapToQuadrant(225.1f))
        assertEquals(270, snapToQuadrant(270f))
        assertEquals(270, snapToQuadrant(314.9f))
    }

    @Test
    fun `snapToQuadrant normalizes the 360 wrap and negative angles into 0-359`() {
        // 315+ rounds up to 360, which normalizes back to 0 (a full turn = upright portrait).
        assertEquals(0, snapToQuadrant(315.1f))
        assertEquals(0, snapToQuadrant(360f))
        // Negative rolls (roll can swing below 0) fold into the same quadrants.
        assertEquals(0, snapToQuadrant(-44.9f))
        assertEquals(270, snapToQuadrant(-45.1f))
        assertEquals(270, snapToQuadrant(-90f))
    }
}
