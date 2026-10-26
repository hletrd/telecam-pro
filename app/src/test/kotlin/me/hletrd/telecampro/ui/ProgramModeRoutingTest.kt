package me.hletrd.telecampro.ui

import me.hletrd.telecampro.camera.CaptureMode
import me.hletrd.telecampro.camera.ExposureMode
import me.hletrd.telecampro.camera.FlashMode
import me.hletrd.telecampro.camera.ManualControls
import me.hletrd.telecampro.camera.TELECONVERTER_MAGNIFICATION
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
        val tele = preferredProgramShutterNs(70f, teleconverterMode = true, TELECONVERTER_MAGNIFICATION)
        assertEquals((1_000_000_000f / (70f * TELECONVERTER_MAGNIFICATION)).toLong(), tele)
        // Main 23 mm without the converter → 1/23 s.
        assertEquals((1_000_000_000f / 23f).toLong(), preferredProgramShutterNs(23f, teleconverterMode = false, TELECONVERTER_MAGNIFICATION))
    }

    @Test
    fun `degenerate focal cannot divide by zero`() {
        assertEquals(1_000_000_000L, preferredProgramShutterNs(0f, teleconverterMode = false, TELECONVERTER_MAGNIFICATION))
    }

    @Test
    fun `the app-side loop holds only while a video take is starting or rolling`() {
        // Each loop step is a repeating-request swap: 7 frames lost per step mid-REC on PMA110.
        assertTrue(appSideExposureHeldForRecording(CaptureMode.VIDEO, recording = true, recordingStarting = false))
        assertTrue(appSideExposureHeldForRecording(CaptureMode.VIDEO, recording = false, recordingStarting = true))
        assertFalse(appSideExposureHeldForRecording(CaptureMode.VIDEO, recording = false, recordingStarting = false))
        // Stills never hold: there is no take to protect.
        assertFalse(appSideExposureHeldForRecording(CaptureMode.PHOTO, recording = true, recordingStarting = true))
    }

    @Test
    fun `only the field the loop owns in this mode makes a packet a loop step`() {
        val s = ManualControls(exposureMode = ExposureMode.SHUTTER, iso = 800, exposureTimeNs = 16_666_666L)
        // S: the loop drives ISO; the operator owns the shutter.
        assertTrue(controlsDeltaIsAppSideAeOnly(s, s.copy(iso = 1_000)))
        assertFalse(controlsDeltaIsAppSideAeOnly(s, s.copy(exposureTimeNs = 8_333_333L)))
        assertFalse(controlsDeltaIsAppSideAeOnly(s, s.copy(iso = 1_000, zoomRatio = 2f)))
        // ISO: the loop drives the shutter; the operator owns ISO.
        val iso = s.copy(exposureMode = ExposureMode.ISO)
        assertTrue(controlsDeltaIsAppSideAeOnly(iso, iso.copy(exposureTimeNs = 8_333_333L)))
        assertFalse(controlsDeltaIsAppSideAeOnly(iso, iso.copy(iso = 1_000)))
        // App-side P owns both; HAL-AE P and M own neither.
        val appP = s.copy(exposureMode = ExposureMode.PROGRAM, programAppSide = true)
        assertTrue(controlsDeltaIsAppSideAeOnly(appP, appP.copy(iso = 1_000, exposureTimeNs = 8_333_333L)))
        val halP = appP.copy(programAppSide = false)
        assertFalse(controlsDeltaIsAppSideAeOnly(halP, halP.copy(iso = 1_000)))
        val m = s.copy(exposureMode = ExposureMode.MANUAL)
        assertFalse(controlsDeltaIsAppSideAeOnly(m, m.copy(iso = 1_000)))
        // Nothing sent yet, or nothing changed: not a loop step to drop.
        assertFalse(controlsDeltaIsAppSideAeOnly(null, s))
        assertFalse(controlsDeltaIsAppSideAeOnly(s, s))
    }
}
