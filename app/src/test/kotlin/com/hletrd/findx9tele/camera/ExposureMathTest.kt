package com.hletrd.findx9tele.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure exposure math that feeds the manual-capture request:
 * [effectiveExposureNs] (cine ANGLE ↔ SPEED derivation) and the frame-duration/exposure
 * relationship that governs whether a shutter slower than 1/fps actually survives to the sensor.
 *
 * These are android-type-free (plain Kotlin on [ManualControls]), so they run on the JVM. They
 * exist to pin down the "exposure longer than the frame interval" edge case for video/long-exposure
 * stills through the 300 mm tele, where sub-1/fps shutters are the norm in low light.
 */
class ExposureMathTest {

    // ---- effectiveExposureNs: SPEED mode ----

    @Test
    fun `speed mode returns the raw exposure time unchanged`() {
        val c = ManualControls(shutterMode = ShutterMode.SPEED, exposureTimeNs = 8_000_000L)
        assertEquals(8_000_000L, c.effectiveExposureNs())
    }

    @Test
    fun `speed mode honors a multi-second long exposure`() {
        // 2 s bulb-style exposure through the tele at night; must pass through untouched.
        val c = ManualControls(shutterMode = ShutterMode.SPEED, exposureTimeNs = 2_000_000_000L)
        assertEquals(2_000_000_000L, c.effectiveExposureNs())
    }

    // ---- captureWatchdogTimeoutMs: request exposure + bounded delivery budget ----

    @Test
    fun `HAL auto and unknown exposure retain the eight second watchdog floor`() {
        assertEquals(8_000L, captureWatchdogTimeoutMs(clampedExposureNs = null))
    }

    @Test
    fun `short manual exposure is rounded up and added to the delivery budget`() {
        assertEquals(8_001L, captureWatchdogTimeoutMs(clampedExposureNs = 1L))
        assertEquals(8_008L, captureWatchdogTimeoutMs(clampedExposureNs = 8_000_000L))
    }

    @Test
    fun `two and eight second manual exposures receive their full exposure time`() {
        assertEquals(10_000L, captureWatchdogTimeoutMs(clampedExposureNs = 2_000_000_000L))
        assertEquals(16_000L, captureWatchdogTimeoutMs(clampedExposureNs = 8_000_000_000L))
    }

    @Test
    fun `long AEB step uses the same sensor clamp as its capture request`() {
        val sensorMaxNs = 30_000_000_000L
        val plusTwoEvNs = manualAebExposuresNs(
            baseNs = 8_000_000_000L,
            minNs = 100_000L,
            maxNs = sensorMaxNs,
        ).last()
        val bracketControls = ManualControls(exposureTimeNs = plusTwoEvNs)
        val appliedNs = bracketControls.clampedEffectiveExposureNs(100_000L, sensorMaxNs)

        assertEquals(sensorMaxNs, appliedNs)
        assertEquals(38_000L, captureWatchdogTimeoutMs(appliedNs))
    }

    @Test
    fun `watchdog arithmetic saturates instead of wrapping`() {
        assertEquals(
            Long.MAX_VALUE,
            captureWatchdogTimeoutMs(
                clampedExposureNs = 1_000_000L,
                deliveryMarginMs = Long.MAX_VALUE,
            ),
        )
    }

    // ---- effectiveExposureNs: ANGLE mode ----

    @Test
    fun `angle mode derives exposure from shutter angle and fps`() {
        // 180° at 24 fps → (0.5)/24 s = 20,833,333 ns.
        val c = ManualControls(shutterMode = ShutterMode.ANGLE, shutterAngle = 180f, fps = 24)
        assertEquals(20_833_333L, c.effectiveExposureNs())
    }

    @Test
    fun `angle mode at 360 degrees equals a full frame interval`() {
        // 360° at 30 fps → 1/30 s = 33,333,333 ns (the maximum cine exposure at that rate).
        val c = ManualControls(shutterMode = ShutterMode.ANGLE, shutterAngle = 360f, fps = 30)
        assertEquals(33_333_333L, c.effectiveExposureNs())
    }

    @Test
    fun `angle mode clamps below 1 degree and above 360 degrees`() {
        val tooSmall = ManualControls(shutterMode = ShutterMode.ANGLE, shutterAngle = 0f, fps = 30)
        val atOne = ManualControls(shutterMode = ShutterMode.ANGLE, shutterAngle = 1f, fps = 30)
        assertEquals("angle < 1° is clamped to 1°", atOne.effectiveExposureNs(), tooSmall.effectiveExposureNs())

        val tooBig = ManualControls(shutterMode = ShutterMode.ANGLE, shutterAngle = 720f, fps = 30)
        val at360 = ManualControls(shutterMode = ShutterMode.ANGLE, shutterAngle = 360f, fps = 30)
        assertEquals("angle > 360° is clamped to 360°", at360.effectiveExposureNs(), tooBig.effectiveExposureNs())
    }

    @Test
    fun `angle mode with fps 0 falls back to the raw speed exposure`() {
        // Guard: fps==0 would divide by zero; the getter must fall back to exposureTimeNs.
        val c = ManualControls(shutterMode = ShutterMode.ANGLE, shutterAngle = 180f, fps = 0, exposureTimeNs = 5_000_000L)
        assertEquals(5_000_000L, c.effectiveExposureNs())
    }

    @Test
    fun `angle mode exposure shortens as fps rises`() {
        val at24 = ManualControls(shutterMode = ShutterMode.ANGLE, shutterAngle = 180f, fps = 24).effectiveExposureNs()
        val at60 = ManualControls(shutterMode = ShutterMode.ANGLE, shutterAngle = 180f, fps = 60).effectiveExposureNs()
        assertTrue("higher fps → shorter cine exposure", at60 < at24)
    }

    // ---- The "exposure longer than the frame interval" invariant ----
    // These document the relationship the capture request must preserve: SENSOR_FRAME_DURATION must
    // be >= SENSOR_EXPOSURE_TIME (Camera2 contract), so a shutter slower than 1/fps requires the
    // frame duration to stretch to the exposure — otherwise the HAL silently caps the exposure.

    @Test
    fun `a sub-1over-fps shutter exceeds the nominal frame interval`() {
        val fps = 30
        val nominalFrameDurationNs = 1_000_000_000L / fps // 33,333,333 ns
        val quarterSecond = 250_000_000L // 1/4 s manual exposure
        assertTrue(
            "1/4 s exposure is longer than the 1/30 s frame interval — the frame duration must stretch to it",
            quarterSecond > nominalFrameDurationNs,
        )
    }

    // ---- sensorFrameDurationNs: the production helper applyExposure now uses ----

    @Test
    fun `frame duration stays at the nominal interval for a short exposure`() {
        // 1/125 s exposure at 30 fps → frame duration remains the 1/30 s interval.
        assertEquals(33_333_333L, sensorFrameDurationNs(fps = 30, exposureNs = 8_000_000L, maxFrameDurationNs = 0L))
    }

    @Test
    fun `frame duration stretches to a long exposure so it is not clamped to 1 over fps`() {
        // 1/4 s and 2 s exposures at 30 fps must carry through as the frame duration.
        assertEquals(250_000_000L, sensorFrameDurationNs(fps = 30, exposureNs = 250_000_000L, maxFrameDurationNs = 0L))
        assertEquals(2_000_000_000L, sensorFrameDurationNs(fps = 30, exposureNs = 2_000_000_000L, maxFrameDurationNs = 0L))
    }

    @Test
    fun `frame duration is capped at the sensor max when reported`() {
        // Sensor max 1 s: a requested 2 s exposure is bounded to the hardware ceiling.
        assertEquals(1_000_000_000L, sensorFrameDurationNs(fps = 30, exposureNs = 2_000_000_000L, maxFrameDurationNs = 1_000_000_000L))
        // A max at or above the need does not shrink it.
        assertEquals(250_000_000L, sensorFrameDurationNs(fps = 30, exposureNs = 250_000_000L, maxFrameDurationNs = 8_000_000_000L))
    }

    @Test
    fun `frame duration falls back to the exposure when fps is non-positive`() {
        // fps <= 0 drops the nominal term; the exposure alone drives the duration.
        assertEquals(500_000_000L, sensorFrameDurationNs(fps = 0, exposureNs = 500_000_000L, maxFrameDurationNs = 0L))
    }

    // ---- manualAebExposuresNs: the manual-exposure AEB shutter bracket ----

    @Test
    fun `manual AEB brackets minus2 0 plus2 EV as quarter and quadruple exposure times`() {
        // 1/125 s base, wide sensor range → exact ×¼ / ×1 / ×4 bracket.
        val steps = manualAebExposuresNs(8_000_000L, 1_000L, 10_000_000_000L)
        assertEquals(listOf(2_000_000L, 8_000_000L, 32_000_000L), steps)
    }

    @Test
    fun `manual AEB clamps to the sensor exposure range`() {
        // Base at the range top: +2 EV clamps back onto the max → deduplicated to 2 shots.
        val steps = manualAebExposuresNs(1_000_000_000L, 1_000L, 1_000_000_000L)
        assertEquals(listOf(250_000_000L, 1_000_000_000L), steps)
    }

    @Test
    fun `manual AEB collapses to a single shot when the range pins everything`() {
        val steps = manualAebExposuresNs(8_000_000L, 8_000_000L, 8_000_000L)
        assertEquals(listOf(8_000_000L), steps)
    }

    @Test
    fun `manual AEB with an inverted range degrades to the base exposure`() {
        assertEquals(listOf(8_000_000L), manualAebExposuresNs(8_000_000L, 10L, 1L))
    }

    @Test
    fun `frame duration never underruns the exposure - the invariant that makes long exposure work`() {
        // Property check across a range: the returned duration is always >= the (bounded) exposure,
        // which is exactly what stops the HAL from truncating a slow shutter.
        val maxDur = 4_000_000_000L
        for (fps in intArrayOf(24, 30, 60, 120)) {
            for (expMs in intArrayOf(1, 8, 33, 100, 250, 1000, 3000)) {
                val exp = expMs * 1_000_000L
                val d = sensorFrameDurationNs(fps, exp, maxDur)
                assertTrue("frameDuration >= min(exposure, maxDur)", d >= minOf(exp, maxDur))
                assertTrue("frameDuration <= maxDur", d <= maxDur)
            }
        }
    }
}
