package com.hletrd.findx9tele.ui

import com.hletrd.findx9tele.camera.CaptureMode
import com.hletrd.findx9tele.camera.ExposureMode
import com.hletrd.findx9tele.camera.FlashMode
import com.hletrd.findx9tele.camera.ManualControls
import com.hletrd.findx9tele.camera.TELECONVERTER_MAGNIFICATION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the P-mode routing matrix ([programShouldRunAppSide]) — which PROGRAM runs the app-side
 * min-shutter loop vs the HAL AE — and the handheld shutter rule ([preferredProgramShutterNs]).
 */
class ProgramModeRoutingTest {

    @Test
    fun `photo PROGRAM without flash metering runs app-side`() {
        assertTrue(programShouldRunAppSide(CaptureMode.PHOTO, ExposureMode.PROGRAM, FlashMode.OFF))
        assertTrue(programShouldRunAppSide(CaptureMode.PHOTO, ExposureMode.PROGRAM, FlashMode.TORCH))
    }

    @Test
    fun `flash-metered photo PROGRAM stays on the HAL AE`() {
        // AUTO/ON flash metering only exists with the HAL AE ON.
        assertFalse(programShouldRunAppSide(CaptureMode.PHOTO, ExposureMode.PROGRAM, FlashMode.AUTO))
        assertFalse(programShouldRunAppSide(CaptureMode.PHOTO, ExposureMode.PROGRAM, FlashMode.ON))
    }

    @Test
    fun `video PROGRAM stays on the HAL AE`() {
        assertFalse(programShouldRunAppSide(CaptureMode.VIDEO, ExposureMode.PROGRAM, FlashMode.OFF))
        assertFalse(exposureAnalysisRequired(ManualControls(exposureMode = ExposureMode.PROGRAM)))
    }

    @Test
    fun `app-owned and priority exposure keep analysis enabled`() {
        assertTrue(
            exposureAnalysisRequired(
                ManualControls(exposureMode = ExposureMode.PROGRAM, programAppSide = true),
            ),
        )
        assertTrue(exposureAnalysisRequired(ManualControls(exposureMode = ExposureMode.SHUTTER)))
        assertTrue(exposureAnalysisRequired(ManualControls(exposureMode = ExposureMode.ISO)))
        assertTrue(exposureAnalysisRequired(ManualControls(exposureMode = ExposureMode.MANUAL)))
    }

    @Test
    fun `non-PROGRAM modes never claim the app-side flag`() {
        for (mode in listOf(ExposureMode.SHUTTER, ExposureMode.ISO, ExposureMode.MANUAL)) {
            assertFalse("$mode must not set programAppSide", programShouldRunAppSide(CaptureMode.PHOTO, mode, FlashMode.OFF))
        }
    }

    @Test
    fun `handheld shutter rule is one over the effective focal`() {
        // 70 mm lens with the converter → ~300 mm effective → ~1/300 s.
        val tele = preferredProgramShutterNs(70f, teleconverterMode = true)
        assertEquals((1_000_000_000f / (70f * TELECONVERTER_MAGNIFICATION)).toLong(), tele)
        // Main 23 mm without the converter → 1/23 s.
        assertEquals((1_000_000_000f / 23f).toLong(), preferredProgramShutterNs(23f, teleconverterMode = false))
    }

    @Test
    fun `degenerate focal cannot divide by zero`() {
        assertEquals(1_000_000_000L, preferredProgramShutterNs(0f, teleconverterMode = false))
    }
}
