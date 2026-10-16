package me.hletrd.telecampro.ui

import me.hletrd.telecampro.camera.ExposureMode
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
}
