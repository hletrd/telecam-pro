package me.hletrd.telecampro.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Switching the shutter UNIT must not move the shutter. The toggle used to copy only the mode
 * flag, so entering ANGLE revived a stale angle and silently applied its exposure
 * (device-found on TB331FC 2026-09-09: 1/16000 s became 209° = 1/50 s).
 */
class ShutterModeConversionTest {
    private val base = ManualControls(
        shutterMode = ShutterMode.SPEED,
        exposureTimeNs = 1_000_000_000L / 500L,
        shutterAngle = 209f,
        fps = 30,
    )

    @Test
    fun `entering angle carries the applied speed`() {
        val angle = base.withShutterMode(ShutterMode.ANGLE)

        assertEquals(ShutterMode.ANGLE, angle.shutterMode)
        assertEquals(360f * 30f / 500f, angle.shutterAngle, 1e-3f)
        assertEquals(base.exposureTimeNs.toDouble(), angle.effectiveExposureNs().toDouble(), 1.0)
    }

    @Test
    fun `a speed the cine range cannot express lands on its nearest angle, not a stale one`() {
        // 1/16000 s at 30 fps is 0.675°, under the 1° floor: the toggle reports 1° = 1/10800 s,
        // the closest exposure the convention has — never the 209° that was set minutes earlier.
        val angle = base.copy(exposureTimeNs = 1_000_000_000L / 16_000L).withShutterMode(ShutterMode.ANGLE)

        assertEquals(1f, angle.shutterAngle, 0f)
        assertEquals(1_000_000_000L / 10_800L, angle.effectiveExposureNs())
    }

    @Test
    fun `entering speed carries the applied angle`() {
        val angle = base.copy(shutterMode = ShutterMode.ANGLE, shutterAngle = 180f)

        val speed = angle.withShutterMode(ShutterMode.SPEED)

        assertEquals(ShutterMode.SPEED, speed.shutterMode)
        assertEquals(1_000_000_000L / 60L, speed.exposureTimeNs)
        assertEquals(angle.effectiveExposureNs(), speed.effectiveExposureNs())
    }

    @Test
    fun `angle clamps to the cine range at both ends`() {
        val tooFast = base.copy(exposureTimeNs = 1_000L).withShutterMode(ShutterMode.ANGLE)
        val tooSlow = base.copy(exposureTimeNs = 2_000_000_000L).withShutterMode(ShutterMode.ANGLE)

        assertEquals(1f, tooFast.shutterAngle, 0f)
        assertEquals(360f, tooSlow.shutterAngle, 0f)
    }

    @Test
    fun `a round trip returns the same exposure`() {
        val back = base.withShutterMode(ShutterMode.ANGLE).withShutterMode(ShutterMode.SPEED)

        assertEquals(base.exposureTimeNs.toDouble(), back.exposureTimeNs.toDouble(), 1.0)
    }

    @Test
    fun `the same unit is a no-op and an unknown frame rate keeps the stored angle`() {
        assertSame(base, base.withShutterMode(ShutterMode.SPEED))
        val noFps = base.copy(fps = 0).withShutterMode(ShutterMode.ANGLE)
        assertEquals(209f, noFps.shutterAngle, 0f)
    }
}
