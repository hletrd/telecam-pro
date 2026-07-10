package com.hletrd.findx9tele.ui.overlays

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [levelDeviationDegrees]: the horizon-gauge deviation from the held quadrant, wrapped to
 * (-180, 180]. Guards the 350°-vs-10° seam case that a naive subtraction gets wrong (+340° instead
 * of -20°).
 */
class LevelDeviationTest {

    @Test
    fun `result always lands in the half-open interval`() {
        var roll = -720f
        while (roll <= 720f) {
            for (dev in intArrayOf(0, 90, 180, 270)) {
                val d = levelDeviationDegrees(roll, dev)
                assertTrue("roll=$roll dev=$dev -> $d", d > -180f && d <= 180f)
            }
            roll += 7.5f
        }
    }

    @Test
    fun `zero when roll equals the held orientation`() {
        assertEquals(0f, levelDeviationDegrees(0f, 0), 1e-4f)
        assertEquals(0f, levelDeviationDegrees(90f, 90), 1e-4f)
        assertEquals(0f, levelDeviationDegrees(270f, 270), 1e-4f)
    }

    @Test
    fun `small offsets keep their sign`() {
        assertEquals(5f, levelDeviationDegrees(5f, 0), 1e-4f) // rolled one way of level
        assertEquals(-5f, levelDeviationDegrees(355f, 0), 1e-4f) // rolled the other way
        assertEquals(3f, levelDeviationDegrees(93f, 90), 1e-4f) // same, in a landscape hold
    }

    @Test
    fun `wraps the short way across the 360 seam`() {
        // 350° roll against a 10° hold is 20° short the OTHER way, not +340°.
        assertEquals(-20f, levelDeviationDegrees(350f, 10), 1e-4f)
        assertEquals(20f, levelDeviationDegrees(10f, 350), 1e-4f)
    }
}
