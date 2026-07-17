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

    // ---- smoothedRoll: wrap-aware low-pass across the atan2 ±180° seam (AGG3-22 / D-17) ----
    // The naive lerp `current + α*(sample - current)` at the seam (+179 sample -179) stepped
    // α*(-358) ≈ -72° through the WRONG quadrants in one sample; snapToQuadrant then handed a
    // capture a 90°-off orientation. The wrap-aware step must take the 2° shortest path instead.

    @Test
    fun `smoothedRoll crosses the seam by the shortest path`() {
        // +179° current, -179° sample: shortest move is +2° (through 180), not -358°.
        val out = smoothedRoll(current = 179f, sample = -179f, alpha = 0.75f)
        // 179 + 0.75*2 = 180.5 → wraps to -179.5. The key property: it stays in the ±180 band
        // AND snaps to the SAME 180° quadrant as both endpoints — never a side quadrant.
        assertEquals(180, snapToQuadrant(out))
        assertTrue("stays normalized", out > -180.0001f && out <= 180.0001f)
    }

    @Test
    fun `smoothedRoll seam crossing in the other direction is symmetric`() {
        val out = smoothedRoll(current = -179f, sample = 179f, alpha = 0.75f)
        assertEquals(180, snapToQuadrant(out))
        assertTrue(out > -180.0001f && out <= 180.0001f)
    }

    @Test
    fun `smoothedRoll away from the seam matches the plain lerp`() {
        // 40° → 50° at α=0.75: plain lerp lands 47.5 — wrap handling must not distort it.
        assertEquals(47.5f, smoothedRoll(40f, 50f, 0.75f), 1e-4f)
    }

    @Test
    fun `smoothedRoll never swings through a wrong quadrant at the seam`() {
        // Iterate a noisy hover around the seam: the smoothed value must always snap to 180,
        // never to 90 or 270 (the wrong-quadrant transient the naive lerp produced).
        var roll = 178f
        val samples = floatArrayOf(-179.5f, 179.8f, -178.9f, 179.2f, -179.9f, 178.8f)
        for (s in samples) {
            roll = smoothedRoll(roll, s, 0.75f)
            assertEquals("sample $s produced roll $roll", 180, snapToQuadrant(roll))
        }
    }

    @Test
    fun `wrapDegrees normalizes into the atan2 band`() {
        assertEquals(0f, wrapDegrees(360f), 0f)
        assertEquals(180f, wrapDegrees(180f), 0f)
        assertEquals(180f, wrapDegrees(-180f), 0f) // -180 folds to +180 (single seam value)
        assertEquals(-170f, wrapDegrees(190f), 1e-4f)
        assertEquals(170f, wrapDegrees(-190f), 1e-4f)
    }
}
