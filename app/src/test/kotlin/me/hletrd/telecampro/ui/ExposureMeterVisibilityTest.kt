package me.hletrd.telecampro.ui

import me.hletrd.telecampro.camera.ExposureMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposureMeterVisibilityTest {
    @Test
    fun `manual meter remains visible in the compact shooting view`() {
        assertTrue(shouldShowExposureMeter(ExposureMode.MANUAL, transient = false))
    }

    @Test
    fun `automatic modes show the meter only for transient feedback`() {
        for (mode in listOf(ExposureMode.PROGRAM, ExposureMode.SHUTTER, ExposureMode.ISO)) {
            assertFalse("$mode at rest", shouldShowExposureMeter(mode, transient = false))
            assertTrue("$mode after EV change", shouldShowExposureMeter(mode, transient = true))
        }
    }

    @Test
    fun `the meter keeps its centred home unless an open dial would cover its scale`() {
        // PMA110 release QA 2026-11-02: 3168 px window, a 591 px detailed meter centred at 1288-1879,
        // and the shutter ruler's cluster reaching up to 1635 px (Speed / Angle row over the scale).
        val lift = exposureMeterLiftPx(
            rootHeightPx = 3168, meterHeightPx = 591, clusterHeightPx = 3168 - 1635, gapPx = 28, minTopPx = 600,
        )
        assertEquals(1288 + 591 + 28 - 1635, lift)
        // A closed dial (cluster top below the meter plus the gap) leaves the home alone.
        assertEquals(0, exposureMeterLiftPx(3168, 591, 3168 - 1910, 28, 600))
        // Never above the top bar's edge: the lift stops at the room there is.
        assertEquals(1288 - 1200, exposureMeterLiftPx(3168, 591, 3168 - 1000, 28, 1200))
        // Unmeasured inputs (first frame) never move it.
        assertEquals(0, exposureMeterLiftPx(0, 591, 1500, 28, 600))
        assertEquals(0, exposureMeterLiftPx(3168, 0, 1500, 28, 600))
        assertEquals(0, exposureMeterLiftPx(3168, 591, 0, 28, 600))
    }
}
