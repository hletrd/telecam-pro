package me.hletrd.telecampro.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/** RPL cycle 1 AGG-8/9/10: exposure-mode and shutter-unit handoffs keep the applied exposure. */
class ExposureModeHandoffTest {
    private val liveIso = 6400
    private val liveExposureNs = 1_000_000_000L / 30L

    @Test
    fun `an outgoing HAL program seeds the new mode from the live result`() {
        val halProgram = ManualControls(exposureMode = ExposureMode.PROGRAM, programAppSide = false, iso = 100)
        val manual = halProgram.exposureModeHandoff(ExposureMode.MANUAL, liveIso, liveExposureNs)

        assertEquals(ExposureMode.MANUAL, manual.exposureMode)
        assertEquals(liveIso, manual.iso)
        assertEquals(liveExposureNs, manual.exposureTimeNs)
    }

    @Test
    fun `an app-side program hands over its own still exposure, not the traded wire`() {
        // App-side P settled at 1/10 s ISO 6400 in the dark; the preview wire is the traded 1/30 s.
        val appSide = ManualControls(
            exposureMode = ExposureMode.PROGRAM,
            programAppSide = true,
            iso = 6400,
            exposureTimeNs = 100_000_000L,
        )
        val manual = appSide.exposureModeHandoff(ExposureMode.MANUAL, liveIso, liveExposureNs)

        assertEquals(6400, manual.iso)
        assertEquals(100_000_000L, manual.exposureTimeNs)
    }

    @Test
    fun `entering ISO priority from an angle carries the applied exposure`() {
        // 180° at 30p is 1/60 s; a stale 1/16000 s speed must not seed the loop.
        val angleManual = ManualControls(
            exposureMode = ExposureMode.MANUAL,
            shutterMode = ShutterMode.ANGLE,
            shutterAngle = 180f,
            fps = 30,
            exposureTimeNs = 1_000_000_000L / 16_000L,
        )
        val iso = angleManual.exposureModeHandoff(ExposureMode.ISO, null, null)

        assertEquals(ShutterMode.SPEED, iso.shutterMode)
        assertEquals((1_000_000_000L / 60L).toDouble(), iso.exposureTimeNs.toDouble(), 1.0)
    }

    @Test
    fun `choosing an angle in a loop-owned mode takes the shutter`() {
        val isoPriority = ManualControls(exposureMode = ExposureMode.ISO, exposureTimeNs = 1_000_000_000L / 60L, fps = 30)
        val angle = isoPriority.withShutterModeTakingOwnership(ShutterMode.ANGLE)
        assertEquals(ExposureMode.MANUAL, angle.exposureMode)
        assertEquals(ShutterMode.ANGLE, angle.shutterMode)
        assertEquals(180f, angle.shutterAngle, 1e-3f)

        val appProgram = ManualControls(exposureMode = ExposureMode.PROGRAM, programAppSide = true, fps = 30)
        assertEquals(ExposureMode.MANUAL, appProgram.withShutterModeTakingOwnership(ShutterMode.ANGLE).exposureMode)
    }

    @Test
    fun `the unit toggle in a user-owned shutter mode keeps the mode`() {
        val shutterPriority = ManualControls(exposureMode = ExposureMode.SHUTTER, fps = 30)
        assertEquals(
            ExposureMode.SHUTTER,
            shutterPriority.withShutterModeTakingOwnership(ShutterMode.ANGLE).exposureMode,
        )
        val isoSpeed = ManualControls(exposureMode = ExposureMode.ISO)
        assertEquals(ExposureMode.ISO, isoSpeed.withShutterModeTakingOwnership(ShutterMode.SPEED).exposureMode)
    }
}
