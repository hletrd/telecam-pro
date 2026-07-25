package me.hletrd.findx9tele.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure exposure math that feeds the manual-capture request:
 * [effectiveExposureNs] (cine ANGLE ↔ SPEED derivation) and the frame-duration/exposure
 * relationship that governs whether a shutter slower than 1/fps actually survives to the sensor.
 *
 * These are android-type-free (plain Kotlin on [ManualControls]), so they run on the JVM. They
 * exist to pin down the "exposure longer than the frame interval" edge case for long-exposure
 * stills through the 300 mm tele. Video has a separate fixed-rate policy and cannot stretch frames.
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
    // These document the long-exposure STILL relationship: SENSOR_FRAME_DURATION must be >=
    // SENSOR_EXPOSURE_TIME, so a shutter slower than 1/fps requires the frame duration to stretch.

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

    // ---- previewExposureTrade: the REPEATING-request exposure policy ----
    // Pins the QA-1 fix AND the cycle-8 fluidity policy: a multi-second SENSOR_EXPOSURE_TIME on
    // the repeating request wedges this HAL's still handoff (CAMERA_ERROR(3) after ~one exposure,
    // shot lost — device-reproduced at 6.3 s), and any preview exposure past 1/15 s is a
    // slideshow with pipeline-deep shutter lag. The ladder under test: exposure to the cap → ISO
    // while headroom lasts (brightness-neutral, gain 1.0) → GL digital gain ≤ ×16 → honest dark.

    @Test
    fun `user modes below the fluidity ceiling stay WYSIWYG with unity gain`() {
        // S mode at 1/30 s: under the 1/15 s fluidity ceiling — untouched, nothing simulated.
        val t = previewExposureTrade(
            wantExposureNs = 33_333_333L, iso = 400, isoUpper = 12_800, neutralCapNs = null,
        )
        assertEquals(33_333_333L, t.exposureNs)
        assertEquals(400, t.iso)
        assertEquals(1f, t.digitalGain, 0f)
    }

    @Test
    fun `user mode long exposure trades brightness-neutrally into ISO headroom`() {
        // S 1/4 s at ISO 400 (upper 12800): headroom covers the full 3.75× shortfall → the wire
        // lands at the fluidity ceiling with the SAME EV (exposure×ISO preserved) and NO gain.
        val want = 250_000_000L
        val t = previewExposureTrade(
            wantExposureNs = want, iso = 400, isoUpper = 12_800, neutralCapNs = null,
        )
        assertTrue("exposure rides the fluidity ceiling", t.exposureNs <= PREVIEW_FLUIDITY_MAX_EXPOSURE_NS)
        val wantEv = want.toDouble() * 400
        val gotEv = t.exposureNs.toDouble() * t.iso
        assertTrue("EV preserved within 10%", gotEv > wantEv * 0.9 && gotEv < wantEv * 1.1)
        assertEquals("fully covered by ISO — nothing left to simulate", 1f, t.digitalGain, 0.06f)
    }

    @Test
    fun `residual shortfall past ISO headroom becomes bounded digital gain`() {
        // S 2 s at ISO 3200 (upper 12800): ISO covers 4× of the 30× shortfall; the remaining 7.5×
        // is returned for the GL preview to simulate — within the ×16 bound, so no honest-dark tail.
        val t = previewExposureTrade(
            wantExposureNs = 2_000_000_000L, iso = 3_200, isoUpper = 12_800, neutralCapNs = null,
        )
        assertTrue(t.exposureNs <= PREVIEW_FLUIDITY_MAX_EXPOSURE_NS)
        assertEquals(12_800, t.iso)
        assertEquals(7.5f, t.digitalGain, 0.1f)
    }

    @Test
    fun `exhausted ISO headroom still clamps the repeating exposure and caps the gain`() {
        // The exact shipped failure: P/S at 6.3 s with ISO already at the ceiling → the old code
        // skipped the trade entirely and put 6.3 s on the wire. The clamp must be unconditional;
        // the 94.5× shortfall saturates at the ×16 gain bound and the preview honestly darkens.
        val t = previewExposureTrade(
            wantExposureNs = 6_300_000_000L, iso = 12_750, isoUpper = 12_800, neutralCapNs = null,
        )
        assertTrue("no long frame ever reaches the repeating request", t.exposureNs <= PREVIEW_FLUIDITY_MAX_EXPOSURE_NS)
        assertTrue(t.iso <= 12_800)
        assertEquals(PREVIEW_MAX_DIGITAL_GAIN, t.digitalGain, 0f)
    }

    @Test
    fun `safety ceiling stays authoritative over a loosened fluidity constant`() {
        // If a future tuning pass raises the fluidity cap past the HAL-safety bound, safety must
        // win — the 6.3 s wedge can never come back through a constant change.
        val t = previewExposureTrade(
            wantExposureNs = 6_300_000_000L, iso = 12_800, isoUpper = 12_800, neutralCapNs = null,
            fluidityCapNs = 2_000_000_000L,
        )
        assertTrue(t.exposureNs <= PREVIEW_SAFE_MAX_EXPOSURE_NS)
    }

    @Test
    fun `program mode keeps its 1 over 30s neutral target when headroom allows`() {
        // P at 1/10 s, ISO 800 (upper 12800): scale = min(3.0, 16) = 3.0 → ~1/30 s at ISO 2400,
        // nothing left to simulate.
        val t = previewExposureTrade(
            wantExposureNs = 100_000_000L, iso = 800, isoUpper = 12_800, neutralCapNs = 33_333_333L,
        )
        assertTrue("preview restored to ~1/30 s", t.exposureNs in 29_000_000L..34_000_000L)
        assertTrue("ISO carries the traded stops", t.iso in 2_300..2_500)
        assertEquals(1f, t.digitalGain, 0.01f)
    }

    @Test
    fun `program mode with no headroom rides the fluidity ceiling with gain`() {
        // P at 1/10 s with the ISO ceiling exhausted: no neutral trade possible; the wire drops to
        // the 1/15 s fluidity ceiling and the 1.5× shortfall is simulated in GL (the pre-cycle-8
        // behavior kept the honest 10 fps slow preview — now it is fluid AND equally bright).
        val t = previewExposureTrade(
            wantExposureNs = 100_000_000L, iso = 12_800, isoUpper = 12_800, neutralCapNs = 33_333_333L,
        )
        assertEquals(PREVIEW_FLUIDITY_MAX_EXPOSURE_NS, t.exposureNs)
        assertEquals(12_800, t.iso)
        assertEquals(1.5f, t.digitalGain, 0.01f)
    }

    @Test
    fun `program want between neutral and fluidity with no headroom stays WYSIWYG`() {
        // P at 1/20 s at the ISO ceiling: above the 1/30 s neutral aim but under the fluidity
        // ceiling — no trade possible, no cap bites, no simulation needed.
        val t = previewExposureTrade(
            wantExposureNs = 50_000_000L, iso = 12_800, isoUpper = 12_800, neutralCapNs = 33_333_333L,
        )
        assertEquals(50_000_000L, t.exposureNs)
        assertEquals(1f, t.digitalGain, 0.01f)
    }

    @Test
    fun `non positive ISO cannot trade but is still clamped safe`() {
        // Degenerate zero ISO: no trade possible, but the fluidity clamp still holds and the gain
        // (exposure-ratio only) saturates at the bound — the finder shows SOMETHING rather than a
        // wedge-risk long frame.
        val t = previewExposureTrade(
            wantExposureNs = 6_300_000_000L, iso = 0, isoUpper = 12_800, neutralCapNs = null,
        )
        assertTrue(t.exposureNs <= PREVIEW_FLUIDITY_MAX_EXPOSURE_NS)
        assertEquals(0, t.iso)
        assertEquals(PREVIEW_MAX_DIGITAL_GAIN, t.digitalGain, 0f)
    }

    // ---- previewDigitalGain: the GL-side twin of the wire trade ----

    private fun manualSensorControl(isoMax: Int = 12_800) = CameraControlCapabilities(
        supportsManualSensor = true,
        hasIsoRange = true,
        isoMin = 100,
        isoMax = isoMax,
        hasExposureTimeRange = true,
        exposureTimeMinNs = 100_000L,
        exposureTimeMaxNs = 4_000_000_000L,
        aeModes = intArrayOf(
            android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF,
            android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_ON,
        ),
    )

    @Test
    fun `gain follows the wire trade for an AE-OFF long exposure`() {
        // M 2 s at ISO 3200: identical inputs to the wire-side test above → identical 7.5× residual.
        val c = ManualControls(
            exposureMode = ExposureMode.MANUAL,
            shutterMode = ShutterMode.SPEED,
            exposureTimeNs = 2_000_000_000L,
            iso = 3_200,
        )
        assertEquals(7.5f, previewDigitalGain(c, manualSensorControl()), 0.1f)
    }

    @Test
    fun `saturation is reported only past the clamp so the AE loop can freeze upward motion`() {
        // Below the bound the residual is representable — the loop must stay free to drive.
        val representable = ManualControls(
            exposureMode = ExposureMode.ISO,
            shutterMode = ShutterMode.SPEED,
            exposureTimeNs = 2_000_000_000L,
            iso = 3_200,
        )
        assertFalse(previewBrightnessSimulationSaturated(representable, manualSensorControl()))

        // ISO priority pinned at the ceiling in a scene needing far more than 16x the fluidity cap:
        // the preview is permanently darker than the intent, so the metered error can never shrink
        // and an unfrozen loop would walk the exposure to the 4 s HAL-safe still ceiling.
        val saturated = representable.copy(exposureTimeNs = 6_300_000_000L, iso = 12_750)
        assertTrue(previewBrightnessSimulationSaturated(saturated, manualSensorControl()))
        assertEquals(PREVIEW_MAX_DIGITAL_GAIN, previewDigitalGain(saturated, manualSensorControl()), 0f)

        // A route the trade never applies to cannot be "saturated" — HAL AE owns brightness there.
        assertFalse(
            previewBrightnessSimulationSaturated(
                saturated.copy(exposureMode = ExposureMode.PROGRAM, programAppSide = false),
                manualSensorControl(),
            ),
        )
        assertFalse(
            previewBrightnessSimulationSaturated(
                saturated,
                manualSensorControl().copy(supportsManualSensor = false),
            ),
        )
    }

    @Test
    fun `gain is unity for HAL-AE program`() {
        // Video-P / flash-metered P run the HAL AE (autoExposure == true): the boost must never
        // engage — the HAL owns preview brightness there.
        val c = ManualControls(
            exposureMode = ExposureMode.PROGRAM,
            programAppSide = false,
            exposureTimeNs = 2_000_000_000L,
            iso = 3_200,
        )
        assertEquals(1f, previewDigitalGain(c, manualSensorControl()), 0f)
    }

    @Test
    fun `gain is unity without manual-sensor support or AE_OFF advertisement`() {
        val c = ManualControls(
            exposureMode = ExposureMode.MANUAL,
            shutterMode = ShutterMode.SPEED,
            exposureTimeNs = 2_000_000_000L,
            iso = 3_200,
        )
        assertEquals(1f, previewDigitalGain(c, manualSensorControl().copy(supportsManualSensor = false)), 0f)
        assertEquals(
            1f,
            previewDigitalGain(
                c,
                manualSensorControl().copy(
                    aeModes = intArrayOf(android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_ON),
                ),
            ),
            0f,
        )
    }

    @Test
    fun `video frame intervals always sit under the fluidity ceiling with unity gain`() {
        // The mid-REC invariant (team-lead-requested pin): video exposure is clamped to one frame
        // (normalizedForCaptureMode), and every offered recording rate's interval fits under the
        // 1/15 s fluidity ceiling — so the brightness-simulation gain is structurally 1.0 while
        // recording, and the encoder's gain-free draw can never diverge from the display. A future
        // sub-15-fps recording rate must fail HERE before it silently breaks that.
        for (fps in intArrayOf(24, 25, 30, 60)) {
            val interval = checkNotNull(frameIntervalNs(fps))
            assertTrue("1/$fps s must fit the fluidity ceiling", interval <= PREVIEW_FLUIDITY_MAX_EXPOSURE_NS)
            val t = previewExposureTrade(
                wantExposureNs = interval, iso = 6_400, isoUpper = 12_800, neutralCapNs = null,
            )
            assertEquals(interval, t.exposureNs)
            assertEquals("no simulation at any video cadence", 1f, t.digitalGain, 0f)
        }
    }

    @Test
    fun `gain is unity when the exposure fits under the fluidity ceiling`() {
        val c = ManualControls(
            exposureMode = ExposureMode.SHUTTER,
            shutterMode = ShutterMode.SPEED,
            exposureTimeNs = 8_000_000L,
            iso = 3_200,
        )
        assertEquals(1f, previewDigitalGain(c, manualSensorControl()), 0f)
    }

    @Test
    fun `malformed sensor range leaves the requested exposure untouched`() {
        // A min above max is an invalid advertisement; clamping into it would fabricate a value
        // the sensor never offered, so the request keeps the app-owned exposure verbatim.
        val c = ManualControls(shutterMode = ShutterMode.SPEED, exposureTimeNs = 8_000_000L)
        assertEquals(8_000_000L, c.clampedEffectiveExposureNs(minNs = 10L, maxNs = 5L))
        assertEquals(8_000_000L, c.clampedEffectiveExposureNs(minNs = null, maxNs = 5L))
        assertEquals(8_000_000L, c.clampedEffectiveExposureNs(minNs = 10L, maxNs = null))
    }

    @Test
    fun `priority modes report exactly which side the app-side loop drives`() {
        // SHUTTER: user owns the shutter, the loop drives ISO; ISO mode is the mirror image.
        assertTrue(ManualControls(exposureMode = ExposureMode.SHUTTER).autoIsoDriven)
        assertFalse(ManualControls(exposureMode = ExposureMode.SHUTTER).autoShutterDriven)
        assertTrue(ManualControls(exposureMode = ExposureMode.ISO).autoShutterDriven)
        assertFalse(ManualControls(exposureMode = ExposureMode.ISO).autoIsoDriven)
        // PROGRAM and MANUAL drive neither: HAL AE or the user owns both values.
        assertFalse(ManualControls(exposureMode = ExposureMode.PROGRAM).autoIsoDriven)
        assertFalse(ManualControls(exposureMode = ExposureMode.PROGRAM).autoShutterDriven)
        assertFalse(ManualControls(exposureMode = ExposureMode.MANUAL).autoIsoDriven)
        assertFalse(ManualControls(exposureMode = ExposureMode.MANUAL).autoShutterDriven)
    }
}
