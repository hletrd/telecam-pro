package me.hletrd.telecampro.camera

import android.hardware.camera2.CameraMetadata
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Pins the white-balance mechanism selection and math (WhiteBalance.kt) against the PMA110
 * measurement of 2026-10-27 (.context/wb-fix-2026-10-27/): presets ignored, FAST gains ignored, CCT
 * mode dropping the colour transform, TRANSFORM_MATRIX honoured on every route.
 */
class WhiteBalanceTest {

    // PMA110 camera 2 (the 1× main), dumpsys 2026-10-26: illuminant 1 = D65, 2 = Standard A.
    private val pma110Cam2 = ColorCalibration(
        colorMatrix1 = doubleArrayOf(0.7958, -0.2931, -0.0599, -0.4103, 1.2298, 0.1517, -0.1629, 0.3132, 0.4958),
        colorMatrix2 = doubleArrayOf(1.0717, -0.4897, 0.0361, -0.4242, 1.3889, 0.2471, -0.1003, 0.2620, 0.6874),
        calibration1 = doubleArrayOf(129 / 128.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 127 / 128.0),
        calibration2 = doubleArrayOf(127 / 128.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 125 / 128.0),
        cct1 = 6504.0,
        cct2 = 2856.0,
    )

    private val allAwb = intArrayOf(
        CameraMetadata.CONTROL_AWB_MODE_OFF,
        CameraMetadata.CONTROL_AWB_MODE_AUTO,
        CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT,
        CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT,
        CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
        CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
        CameraMetadata.CONTROL_AWB_MODE_SHADE,
    )

    /** PMA110 as measured: CCT advertised, calibration present, presets ignored. */
    private val pma110 = WbCapability(
        awbModes = allAwb,
        manualPostProcessing = true,
        colorCorrectionModes = intArrayOf(0, 1, 2, COLOR_CORRECTION_MODE_CCT_VALUE),
        cctMin = 1500,
        cctMax = 10000,
        calibrated = true,
        presetsHonoured = false,
    )

    /** Galaxy Z Flip6 as measured: no CCT, calibration present, presets honoured, no SHADE. */
    private val flip6 = WbCapability(
        awbModes = intArrayOf(1, 2, 3, 5, 6, 0),
        manualPostProcessing = true,
        colorCorrectionModes = intArrayOf(0, 1, 2),
        calibrated = true,
        presetsHonoured = true,
    )

    private val autoTransform = doubleArrayOf(1.859, -0.531, -0.320, -0.227, 1.375, -0.141, 0.125, -0.984, 1.859)

    @Test fun `the Planckian locus and tint reproduce the CIE illuminants`() {
        // Standard A sits on the locus at 2856 K: x 0.44757, y 0.40745.
        val a = wbTargetXy(WbTarget(2856, 0))
        assertEquals(0.44757, a[0], 5e-4)
        assertEquals(0.40745, a[1], 5e-4)
        // D65 sits +0.0032 Duv above the locus at 6504 K: tint +16 (= 0.0032 Duv) lands on it.
        val d65 = wbTargetXy(WbTarget(6504, 16))
        assertEquals(0.31271, d65[0], 3e-4)
        assertEquals(0.32902, d65[1], 3e-4)
        // + tint moves the white point toward green (higher y); the camera then removes green.
        assertTrue(wbTargetXy(WbTarget(5600, 40))[1] > wbTargetXy(WbTarget(5600, 0))[1])
        assertTrue(wbTargetXy(WbTarget(5600, -40))[1] < wbTargetXy(WbTarget(5600, 0))[1])
    }

    @Test fun `calibrated gains follow the sensor's ColorMatrix and run the right way`() {
        // Expected values from an independent numpy mirror of the same law; the HAL's own CCT table
        // measured 1.54 / 2.60 at 3200 K and 2.25 / 1.68 at 5600 K on this camera.
        val g3200 = wbTargetGains(pma110Cam2, WbTarget(3200, 0))
        assertEquals(1.5996f, g3200[0], 2e-3f)
        assertEquals(1f, g3200[1], 0f)
        assertEquals(1f, g3200[2], 0f)
        assertEquals(2.3417f, g3200[3], 2e-3f)
        val g5600 = wbTargetGains(pma110Cam2, WbTarget(5600, 0))
        assertEquals(2.2552f, g5600[0], 2e-3f)
        assertEquals(1.5350f, g5600[3], 2e-3f)
        // A warmer assumed light needs more blue and less red gain, monotonically.
        var previous: FloatArray? = null
        for (k in intArrayOf(2000, 2856, 3200, 4230, 5500, 6500, 7500, 10000)) {
            val g = wbTargetGains(pma110Cam2, WbTarget(k, 0))
            assertEquals("smallest gain is 1 at $k K", 1f, g.min(), 1e-6f)
            previous?.let {
                assertTrue("R gain rises with K at $k", g[0] >= it[0])
                assertTrue("B gain falls with K at $k", g[3] <= it[3])
            }
            previous = g
        }
    }

    @Test fun `tint plus is magenta and tint minus is green, like the HAL's CCT tint`() {
        val neutral = wbTargetGains(pma110Cam2, WbTarget(5600, 0))
        val magenta = wbTargetGains(pma110Cam2, WbTarget(5600, 40))
        val green = wbTargetGains(pma110Cam2, WbTarget(5600, -40))
        // Green gain is pinned at 1, so "less green" shows as R and B both rising.
        assertTrue(magenta[0] > neutral[0] && magenta[3] > neutral[3])
        assertTrue(green[0] < neutral[0] && green[3] < neutral[3])
        // Out-of-range tint clamps to ±50 (Camera2's own clamp).
        assertArrayEquals(wbTargetGains(pma110Cam2, WbTarget(5600, 50)), wbTargetGains(pma110Cam2, WbTarget(5600, 90)), 0f)
    }

    @Test fun `uncalibrated gains still run the right way and respect the cap`() {
        val warm = wbTargetGains(null, WbTarget(2856, 0))
        val cool = wbTargetGains(null, WbTarget(10000, 0))
        assertTrue(warm[3] / warm[0] > cool[3] / cool[0])
        for (k in intArrayOf(1000, 2000, 40000)) {
            wbTargetGains(null, WbTarget(k, 0)).forEach { assertTrue(it in 1f..MAX_WB_CHANNEL_GAIN) }
        }
    }

    @Test fun `gain normalization floors at one and caps`() {
        assertArrayEquals(floatArrayOf(1f, 2f, 2f, 3f), normalizedWbGains(0.5, 1.0, 1.5), 1e-6f)
        assertArrayEquals(floatArrayOf(MAX_WB_CHANNEL_GAIN, 1f, 1f, 1f), normalizedWbGains(20.0, 1.0, 1.0), 1e-6f)
    }

    @Test fun `the calibrated fallback transform keeps neutrals neutral`() {
        val gains = wbTargetGains(pma110Cam2, WbTarget(3200, 0))
        val t = calibratedWbTransform(pma110Cam2, WbTarget(3200, 0), gains)
        for (r in 0 until 3) assertEquals(1.0, t[r * 3] + t[r * 3 + 1] + t[r * 3 + 2], 1e-9)
        assertTrue("a real colour correction saturates", t[0] > 1.2 && t[8] > 1.2)
        assertArrayEquals(RawDevelopMath.IDENTITY, calibratedWbTransform(null, WbTarget(3200, 0), gains), 0.0)
    }

    @Test fun `the Kelvin path prefers a calibration, then CCT, then uncalibrated gains`() {
        assertEquals(WbKelvinPath.MATRIX_CALIBRATED, wbKelvinPath(pma110))
        assertEquals(WbKelvinPath.MATRIX_CALIBRATED, wbKelvinPath(flip6))
        assertEquals(WbKelvinPath.CCT, wbKelvinPath(pma110.copy(calibrated = false)))
        assertEquals(WbKelvinPath.MATRIX_APPROXIMATE, wbKelvinPath(flip6.copy(calibrated = false)))
        // Below API 36 the list is unreadable: MANUAL_POST_PROCESSING still guarantees the matrix.
        assertEquals(
            WbKelvinPath.MATRIX_APPROXIMATE,
            wbKelvinPath(WbCapability(awbModes = allAwb, manualPostProcessing = true)),
        )
        // A CCT-only route (no manual post-processing) still has a Kelvin path; nothing else does.
        val cctOnly = pma110.copy(manualPostProcessing = false, calibrated = false)
        assertEquals(WbKelvinPath.CCT, wbKelvinPath(cctOnly))
        assertEquals(WbKelvinPath.NONE, wbKelvinPath(cctOnly.copy(colorCorrectionModes = intArrayOf(0, 1, 2))))
        // An advertised list WITHOUT TRANSFORM_MATRIX withholds the matrix even with the capability.
        assertEquals(
            WbKelvinPath.CCT,
            wbKelvinPath(pma110.copy(colorCorrectionModes = intArrayOf(1, 2, COLOR_CORRECTION_MODE_CCT_VALUE))),
        )
        // AWB OFF is the precondition of every manual path (CCT included, per Camera2).
        assertEquals(WbKelvinPath.NONE, wbKelvinPath(pma110.copy(awbModes = intArrayOf(CameraMetadata.CONTROL_AWB_MODE_AUTO))))
        // CCT needs its range too.
        assertEquals(WbKelvinPath.NONE, wbKelvinPath(cctOnly.copy(cctMin = null)))
    }

    @Test fun `availability is the one answer for presets, manual and custom`() {
        // PMA110: presets ignored by the HAL but carried as Kelvin targets, so all are offered.
        for (mode in WbMode.entries) {
            assertEquals(mode.name, mode != WbMode.CUSTOM, wbModeAvailable(mode, pma110, hasCustomGains = false))
        }
        assertTrue(wbModeAvailable(WbMode.CUSTOM, pma110, hasCustomGains = true))
        // Flip6: honoured presets are offered exactly as advertised (no SHADE there).
        assertTrue(wbModeAvailable(WbMode.DAYLIGHT, flip6, false))
        assertFalse(wbModeAvailable(WbMode.SHADE, flip6, false))
        // Presets ignored AND no Kelvin path: nothing can carry them, so they are not offered.
        val ignoredNoPath = pma110.copy(manualPostProcessing = false, colorCorrectionModes = null)
        assertFalse(wbModeAvailable(WbMode.DAYLIGHT, ignoredNoPath, false))
        assertFalse(wbModeAvailable(WbMode.MANUAL, ignoredNoPath, false))
        assertTrue(wbModeAvailable(WbMode.AUTO, ignoredNoPath, false))
        // Custom gains need app-owned gains: a CCT-only route offers MANUAL but never CUSTOM.
        val cctOnly = pma110.copy(manualPostProcessing = false, calibrated = false)
        assertTrue(wbModeAvailable(WbMode.MANUAL, cctOnly, true))
        assertFalse(wbModeAvailable(WbMode.CUSTOM, cctOnly, true))
    }

    @Test fun `only the CCT path clamps the Kelvin to the advertised range`() {
        val cct = pma110.copy(calibrated = false, cctMin = 2856, cctMax = 6500)
        assertEquals(2856, normalizedWbKelvin(2000, cct))
        assertEquals(6500, normalizedWbKelvin(10000, cct))
        assertEquals(2000, normalizedWbKelvin(2000, pma110))
    }

    @Test fun `ignored presets map to documented Kelvin targets`() {
        assertEquals(WbTarget(2856, 0), wbPresetTarget(WbMode.INCANDESCENT))
        assertEquals(WbTarget(4230, 10), wbPresetTarget(WbMode.FLUORESCENT))
        assertEquals(WbTarget(5500, 0), wbPresetTarget(WbMode.DAYLIGHT))
        assertEquals(WbTarget(6500, 0), wbPresetTarget(WbMode.CLOUDY))
        assertEquals(WbTarget(7500, 0), wbPresetTarget(WbMode.SHADE))
        assertNull(wbPresetTarget(WbMode.AUTO))
        assertNull(wbPresetTarget(WbMode.MANUAL))
        assertNull(wbPresetTarget(WbMode.CUSTOM))
    }

    @Test fun `the wire carries AUTO, honoured presets, and matrix white points`() {
        fun wire(c: ManualControls, cap: WbCapability = pma110, transform: DoubleArray? = autoTransform, required: Boolean = true) =
            resolveWbWire(c, cap, pma110Cam2, transform, required)
        assertEquals(WbWire.Auto(CameraMetadata.CONTROL_AWB_MODE_AUTO, true), wire(ManualControls(awbLock = true)))
        // Flip6 honours presets: the preset key itself.
        assertEquals(
            WbWire.Preset(CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
            wire(ManualControls(wbMode = WbMode.DAYLIGHT), cap = flip6),
        )
        // PMA110 ignores them: DAYLIGHT is 5500 K through the matrix, on the route's own transform.
        assertEquals(
            WbWire.Matrix(wbTargetGains(pma110Cam2, WbTarget(5500, 0)), autoTransform),
            wire(ManualControls(wbMode = WbMode.DAYLIGHT)),
        )
        assertEquals(
            WbWire.Matrix(wbTargetGains(pma110Cam2, WbTarget(3200, 7)), autoTransform),
            wire(ManualControls(wbMode = WbMode.MANUAL, wbKelvin = 3200, wbTint = 7)),
        )
        // Custom gains are replayed verbatim on the route transform.
        assertEquals(
            WbWire.Matrix(floatArrayOf(2f, 1f, 1f, 3f), autoTransform),
            wire(ManualControls(wbMode = WbMode.CUSTOM, customWbGains = WbGains(2f, 1f, 1f, 3f))),
        )
    }

    @Test fun `a route whose transform is unknown primes AUTO, except where nothing depends on it`() {
        val manual = ManualControls(wbMode = WbMode.MANUAL, wbKelvin = 3200)
        assertEquals(
            WbWire.Prime(CameraMetadata.CONTROL_AWB_MODE_AUTO),
            resolveWbWire(manual, pma110, pma110Cam2, routeTransform = null, transformRequired = true),
        )
        // RAW-only session: the developer takes the gains alone, so the calibrated transform rides.
        val gains = wbTargetGains(pma110Cam2, WbTarget(3200, 0))
        assertEquals(
            WbWire.Matrix(gains, calibratedWbTransform(pma110Cam2, WbTarget(3200, 0), gains)),
            resolveWbWire(manual, pma110, pma110Cam2, routeTransform = null, transformRequired = false),
        )
        // No AUTO to learn from at all: the calibrated transform is the last resort.
        val noAuto = pma110.copy(awbModes = intArrayOf(CameraMetadata.CONTROL_AWB_MODE_OFF))
        assertTrue(resolveWbWire(manual, noAuto, pma110Cam2, null, true) is WbWire.Matrix)
    }

    @Test fun `maker Kelvin bypasses matrix priming while AUTO and custom gains keep their semantics`() {
        fun wire(c: ManualControls, transform: DoubleArray? = null, maker: Boolean = true) =
            resolveWbWire(c, pma110, pma110Cam2, transform, true, makerLog = maker)
        val manual = ManualControls(wbMode = WbMode.MANUAL, wbKelvin = 5000, wbTint = 4)
        assertEquals(WbWire.MakerCct(5000, 4), wire(manual))
        assertTrue(wire(manual).awbOff)
        assertEquals(WbWire.MakerCct(5000, -50), wire(manual.copy(wbTint = -80)))
        assertEquals(WbWire.MakerCct(5500, 0), wire(ManualControls(wbMode = WbMode.DAYLIGHT)))
        assertEquals(WbWire.Auto(CameraMetadata.CONTROL_AWB_MODE_AUTO, true), wire(ManualControls(awbLock = true)))
        val custom = ManualControls(wbMode = WbMode.CUSTOM, customWbGains = WbGains(2f, 1f, 1f, 3f))
        assertTrue(wire(custom) is WbWire.Prime)
        assertEquals(WbWire.Matrix(floatArrayOf(2f, 1f, 1f, 3f), autoTransform), wire(custom, autoTransform))
        // A refused maker request uses the ordinary route and must never retain OEM WB keys.
        assertTrue(wire(manual, maker = false) is WbWire.Prime)
        assertTrue(wire(manual, autoTransform, maker = false) is WbWire.Matrix)
    }

    @Test fun `a calibration-less CCT route carries temperature and tint`() {
        val cct = pma110.copy(calibrated = false, cctMin = 2856, cctMax = 6500)
        assertEquals(
            WbWire.Cct(6500, -50),
            resolveWbWire(ManualControls(wbMode = WbMode.MANUAL, wbKelvin = 9000, wbTint = -80), cct, null, null, true),
        )
        assertEquals(
            WbWire.Cct(5500, 0),
            resolveWbWire(ManualControls(wbMode = WbMode.DAYLIGHT), cct, null, null, true),
        )
    }

    @Test fun `normalization keeps only what the route applies`() {
        val caps = CameraControlCapabilities(
            supportsManualPostProcessing = true,
            awbModes = allAwb,
            colorCorrectionModes = intArrayOf(0, 1, 2, COLOR_CORRECTION_MODE_CCT_VALUE),
            colorTemperatureMin = 1500,
            colorTemperatureMax = 10000,
            wbCalibrated = true,
            awbPresetModesHonoured = false,
        )
        assertEquals(WbMode.SHADE, ManualControls(wbMode = WbMode.SHADE).normalizedFor(caps).wbMode)
        assertEquals(50, ManualControls(wbTint = 120).normalizedFor(caps).wbTint)
        // An ignoring profile without any Kelvin path drops the preset to AUTO.
        val noPath = caps.copy(supportsManualPostProcessing = false, colorCorrectionModes = null)
        assertEquals(WbMode.AUTO, ManualControls(wbMode = WbMode.SHADE).normalizedFor(noPath).wbMode)
        // The CCT path clamps the Kelvin the readout shows.
        val cctPath = caps.copy(wbCalibrated = false, colorTemperatureMin = 2856, colorTemperatureMax = 6500)
        assertEquals(2856, ManualControls(wbMode = WbMode.MANUAL, wbKelvin = 2000).normalizedFor(cctPath).wbKelvin)
    }

    @Test fun `the transform is written at 1 in 10000`() {
        val r = wbTransformRationals(doubleArrayOf(1.859, -0.531, -0.32, -0.227, 1.375, -0.141, 0.125, -0.984, 1.859))
        assertEquals(18, r.size)
        assertEquals(18590, r[0]); assertEquals(10000, r[1])
        assertEquals(-5310, r[2]); assertEquals(10000, r[3])
        assertEquals(18590, r[16]); assertEquals(10000, r[17])
    }

    @Test fun `route keys round trip and reject garbage`() {
        val keys = listOf(
            WbRouteKey("0", WbSessionShape.MAKER_LOG, "4"),
            WbRouteKey("4", WbSessionShape.VENDOR_TC, null),
            WbRouteKey("2", WbSessionShape.HLG10, null),
        )
        keys.forEach { assertEquals(it, WbRouteKey.decode(it.encode())) }
        assertNull(WbRouteKey.decode("0|NOPE|-"))
        assertNull(WbRouteKey.decode("|SDR|-"))
        assertNull(WbRouteKey.decode("0|SDR"))
    }

    @Test fun `plausible transforms are finite, bounded and row-normalized`() {
        assertTrue(plausibleWbTransform(autoTransform))
        assertTrue(plausibleWbTransform(RawDevelopMath.IDENTITY))
        assertFalse(plausibleWbTransform(DoubleArray(9)))
        assertFalse(plausibleWbTransform(null))
        assertFalse(plausibleWbTransform(doubleArrayOf(1.0, 0.0, 0.0)))
        assertFalse(plausibleWbTransform(autoTransform.copyOf().also { it[4] = Double.NaN }))
    }

    @Test fun `the reference store learns per sensor, never borrows, and reports material change`() {
        val store = WbReferenceStore()
        var notified = 0
        store.onChanged = { notified++ }
        val main = WbRouteKey("0", WbSessionShape.MAKER_LOG, "2")
        val tele = WbRouteKey("0", WbSessionShape.MAKER_LOG, "4")
        val makerMain = doubleArrayOf(1.125, -0.188, 0.063, -0.164, 1.172, -0.016, -0.023, -0.539, 1.563)
        assertNull(store.lookup(main))
        assertTrue(store.offer(main, makerMain))
        assertEquals(1, notified)
        // Same camera + shape, another sensor never seen: nothing — the route primes instead of
        // borrowing the 1× sensor's transform for the 3× (they differ on PMA110's maker session).
        assertNull(store.lookup(tele))
        assertNull(store.lookup(WbRouteKey("0", WbSessionShape.SDR, "2")))
        // Sub-quantization drift is stored but neither notifies nor reports change.
        assertFalse(store.offer(main, makerMain.copyOf().also { it[0] += 0.005 }))
        assertEquals(1, notified)
        assertTrue(store.offer(main, makerMain.copyOf().also { it[0] += 0.05 }))
        assertEquals(2, notified)
        // Implausible transforms are refused outright.
        assertFalse(store.offer(tele, DoubleArray(9)))
        assertNull(store.lookup(tele))
        assertEquals(main, store.latestKey("0", WbSessionShape.MAKER_LOG))
    }

    @Test fun `seeded transforms never displace learned ones and rank older`() {
        val key = WbRouteKey("2", WbSessionShape.SDR, null)
        val other = WbRouteKey("0", WbSessionShape.SDR, "4")
        val store = WbReferenceStore()
        store.offer(key, autoTransform)
        store.seed(mapOf(key to RawDevelopMath.IDENTITY, other to RawDevelopMath.IDENTITY))
        assertArrayEquals(autoTransform, store.lookup(key), 0.0)
        assertArrayEquals(RawDevelopMath.IDENTITY, store.lookup(other), 0.0)
        store.offer(WbRouteKey("0", WbSessionShape.SDR, "2"), autoTransform)
        assertEquals("2", store.latestKey("0", WbSessionShape.SDR)?.physicalId)
    }

    @Test fun `app-owned white balance is AWB OFF on the wire and in the projection`() {
        assertTrue(WbWire.Matrix(floatArrayOf(1f, 1f, 1f, 2f), autoTransform).awbOff)
        assertTrue(WbWire.Cct(3200, 0).awbOff)
        assertFalse(WbWire.Auto(CameraMetadata.CONTROL_AWB_MODE_AUTO, false).awbOff)
        assertFalse(WbWire.Prime(CameraMetadata.CONTROL_AWB_MODE_AUTO).awbOff)
        assertFalse(WbWire.Preset(CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT).awbOff)
        assertFalse(WbWire.Omit.awbOff)
        // Projection twin: a preset is app-owned only where the HAL ignores presets.
        assertFalse(wbAppOwned(ManualControls(), pma110))
        assertTrue(wbAppOwned(ManualControls(wbMode = WbMode.MANUAL), flip6))
        assertTrue(wbAppOwned(ManualControls(wbMode = WbMode.DAYLIGHT), pma110))
        assertFalse(wbAppOwned(ManualControls(wbMode = WbMode.DAYLIGHT), flip6))
    }

    @Test fun `calibrations and capabilities compare by content`() {
        val copy = ColorCalibration(
            pma110Cam2.colorMatrix1.copyOf(), pma110Cam2.colorMatrix2?.copyOf(),
            pma110Cam2.calibration1?.copyOf(), pma110Cam2.calibration2?.copyOf(), 6504.0, 2856.0,
        )
        assertEquals(pma110Cam2, copy)
        assertEquals(pma110Cam2.hashCode(), copy.hashCode())
        assertFalse(pma110Cam2 == ColorCalibration(pma110Cam2.colorMatrix1, null, null, null, 6504.0, null))
        assertEquals(pma110, pma110.copy(awbModes = allAwb.copyOf()))
        assertEquals(pma110.hashCode(), pma110.copy(awbModes = allAwb.copyOf()).hashCode())
        assertFalse(pma110 == pma110.copy(presetsHonoured = true))
        // A single-illuminant calibration ignores the mired weight.
        val single = ColorCalibration(pma110Cam2.colorMatrix1, null, null, null, 6504.0, null)
        assertArrayEquals(single.xyzToCamera(2856.0), single.xyzToCamera(6504.0), 0.0)
    }

    @Test fun `routes without a usable path fall back to what they can apply`() {
        // No AWB mode advertised at all: no WB key, whatever was asked.
        val none = WbCapability()
        assertEquals(WbWire.Omit, resolveWbWire(ManualControls(wbMode = WbMode.MANUAL), none, null, null, true))
        assertEquals(WbWire.Omit, resolveWbWire(ManualControls(), none, null, null, true))
        // AUTO only: an unreachable MANUAL / CUSTOM resolves to AUTO, never to a dead key.
        val autoOnly = WbCapability(awbModes = intArrayOf(CameraMetadata.CONTROL_AWB_MODE_AUTO))
        val auto = WbWire.Auto(CameraMetadata.CONTROL_AWB_MODE_AUTO, false)
        assertEquals(auto, resolveWbWire(ManualControls(wbMode = WbMode.MANUAL), autoOnly, null, null, true))
        assertEquals(
            auto,
            resolveWbWire(ManualControls(wbMode = WbMode.CUSTOM, customWbGains = WbGains(2f, 1f, 1f, 2f)), autoOnly, null, null, true),
        )
        // Uncalibrated matrix route: the stand-in gains on the route's transform.
        val approx = flip6.copy(calibrated = false)
        assertEquals(
            WbWire.Matrix(wbTargetGains(null, WbTarget(3200, 0)), autoTransform),
            resolveWbWire(ManualControls(wbMode = WbMode.MANUAL, wbKelvin = 3200), approx, null, autoTransform, true),
        )
        assertTrue(WbWire.Matrix(floatArrayOf(1f, 1f, 1f, 2f), autoTransform).toString().startsWith("Matrix(gains="))
    }

    @Test fun `matrix wires compare by content`() {
        assertEquals(WbWire.Matrix(floatArrayOf(1f, 1f, 1f, 2f), autoTransform.copyOf()), WbWire.Matrix(floatArrayOf(1f, 1f, 1f, 2f), autoTransform))
        assertFalse(WbWire.Matrix(floatArrayOf(1f, 1f, 1f, 2f), autoTransform) == WbWire.Matrix(floatArrayOf(1f, 1f, 1f, 3f), autoTransform))
        assertTrue(abs(WbWire.Matrix(floatArrayOf(1f, 1f, 1f, 2f), autoTransform).hashCode()) >= 0)
    }

    // ---- Kelvin-tagged references and the preset sweep (Galaxy Z Flip6, 2026-10-27, A23) ----

    private fun m128(vararg v: Int) = DoubleArray(9) { v[it] / 128.0 }

    // The Flip6's own transforms (result keys, camera 0 / sensor 5) and their white points read
    // through camera 0's calibration (.context/a23-flip6-generic-still-2026-10-27/).
    private val flip6Incandescent = m128(168, -26, -14, -41, 153, 16, -26, -162, 316)
    private val flip6Daylight = m128(186, -61, 2, -27, 167, -12, 5, -96, 219)
    private val flip6Cloudy = m128(193, -67, 2, -23, 160, -9, 3, -83, 208)
    private val flip6Auto = doubleArrayOf(1.33594, -0.25, -0.08594, -0.28125, 1.21875, 0.0625, -0.10156, -1.13281, 2.22656)

    private fun maxDiff(a: DoubleArray, b: DoubleArray) = (0 until 9).maxOf { abs(a[it] - b[it]) }

    @Test fun `mired interpolation of the HAL's own transforms predicts the one between`() {
        val predicted = interpolateWbTransform(listOf(2873.0 to flip6Incandescent, 6313.0 to flip6Cloudy), 5162.0)
        assertTrue(maxDiff(predicted, flip6Daylight) < 0.08)
        // The room's AUTO transform from Incandescent + Daylight, within the same bound.
        val auto = interpolateWbTransform(listOf(2873.0 to flip6Incandescent, 5162.0 to flip6Daylight), 3201.0)
        assertTrue(maxDiff(auto, flip6Auto) < 0.08)
        // Exact at a sample, clamped outside the learned range, rows still summing to 1.
        val samples = listOf(2873.0 to flip6Incandescent, 5162.0 to flip6Daylight, 6313.0 to flip6Cloudy)
        assertArrayEquals(flip6Daylight, interpolateWbTransform(samples, 5162.0), 1e-12)
        assertArrayEquals(flip6Incandescent, interpolateWbTransform(samples, 2000.0), 1e-12)
        assertArrayEquals(flip6Cloudy, interpolateWbTransform(samples, 9000.0), 1e-12)
        val mid = interpolateWbTransform(samples, 4000.0)
        for (r in 0 until 3) assertEquals(1.0, mid[r * 3] + mid[r * 3 + 1] + mid[r * 3 + 2], 0.01)
    }

    @Test fun `a route keeps one transform per white point and blends them for a Kelvin`() {
        val store = WbReferenceStore()
        val key = WbRouteKey("0", WbSessionShape.SDR, "5")
        assertTrue(store.offer(key, flip6Auto, 3201.0))
        // One white point only: every Kelvin gets it, as before the Kelvin tags.
        assertArrayEquals(flip6Auto, store.lookup(key, 5000.0), 0.0)
        assertTrue(store.offer(key, flip6Incandescent, 2873.0))
        assertTrue(store.offer(key, flip6Daylight, 5162.0))
        assertTrue(store.offer(key, flip6Cloudy, 6313.0))
        assertArrayEquals(flip6Daylight, store.lookup(key, 5162.0), 1e-12)
        assertTrue(maxDiff(store.lookup(key, 5000.0)!!, flip6Daylight) < 0.05)
        // No Kelvin asked: the latest, exactly what one-transform routes always replayed.
        assertArrayEquals(flip6Cloudy, store.lookup(key), 0.0)
        assertArrayEquals(flip6Cloudy, store.lookup(key, null), 0.0)
        // AWB settling a few mired off a stored white point replaces it rather than adding one.
        assertFalse(store.offer(key, flip6Daylight, 5200.0))
        assertEquals(4, store.snapshot().samples.getValue(key).size)
    }

    @Test fun `a route keeps at most a dozen white points and always the newest`() {
        val store = WbReferenceStore()
        val key = WbRouteKey("2", WbSessionShape.SDR, null)
        // 20 mired apart, so none merges: 10000 K down to ~2800 K. Which surplus point goes is the
        // closest pair's older one (the range test below pins that); here only the cap.
        val kelvins = (0 until 14).map { 1e6 / (100.0 + it * 20.0) }
        kelvins.forEach { store.offer(key, flip6Daylight, it) }
        val kept = store.snapshot().samples.getValue(key).mapNotNull { it.kelvin }
        assertEquals(WbReferenceStore.WB_REFERENCE_MAX_SAMPLES, kept.size)
        assertEquals(kelvins.last(), kept.first(), 0.0)
    }

    @Test fun `a frame's white point reads back the Kelvin its gains were built for`() {
        for (k in listOf(3000, 5000, 7000)) {
            val g = wbTargetGains(pma110Cam2, WbTarget(k, 0))
            val read = wbFrameKelvin(pma110Cam2, g[0].toDouble(), g[1].toDouble(), g[3].toDouble())!!
            assertEquals(k.toDouble(), read, k * 0.03)
        }
        assertNull(wbFrameKelvin(null, 2.0, 1.0, 1.5))
        assertNull(wbFrameKelvin(pma110Cam2, 0.0, 1.0, 1.5))
    }

    @Test fun `the transform Kelvin follows the packet's own white point`() {
        assertEquals(4300.0, wbTransformKelvin(ManualControls(wbMode = WbMode.MANUAL, wbKelvin = 4300), flip6, pma110Cam2))
        assertNull(wbTransformKelvin(ManualControls(), flip6, pma110Cam2))
        assertNull(wbTransformKelvin(ManualControls(wbMode = WbMode.MANUAL, wbKelvin = 4300), flip6, null))
        // An honoured preset is the HAL's own; an ignored one rides the matrix at its target.
        assertNull(wbTransformKelvin(ManualControls(wbMode = WbMode.DAYLIGHT), flip6, pma110Cam2))
        assertEquals(5500.0, wbTransformKelvin(ManualControls(wbMode = WbMode.DAYLIGHT), pma110, pma110Cam2))
        val custom = ManualControls(wbMode = WbMode.CUSTOM, customWbGains = WbGains(2.13f, 1f, 1f, 1.645f))
        assertEquals(5000.0, wbTransformKelvin(custom, flip6, pma110Cam2)!!, 250.0)
    }

    @Test fun `only a HAL that honours presets is swept, along the locus, once, and never for RAW`() {
        assertEquals(
            listOf(
                CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT,
                CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
                CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
            ),
            wbSweepPresets(flip6),
        )
        assertTrue(wbSweepPresets(pma110).isEmpty())
        val manual = ManualControls(wbMode = WbMode.MANUAL, wbKelvin = 5000)
        assertTrue(wbSweepWanted(manual, flip6, calibrated = true, alreadySwept = false, transformRequired = true))
        assertFalse(wbSweepWanted(manual, flip6, calibrated = true, alreadySwept = true, transformRequired = true))
        assertFalse(wbSweepWanted(manual, flip6, calibrated = false, alreadySwept = false, transformRequired = true))
        assertFalse(wbSweepWanted(manual, flip6, calibrated = true, alreadySwept = false, transformRequired = false))
        assertFalse(wbSweepWanted(ManualControls(), flip6, calibrated = true, alreadySwept = false, transformRequired = true))
        assertFalse(wbSweepWanted(ManualControls(wbMode = WbMode.DAYLIGHT), flip6, true, false, true))
        assertFalse(wbSweepWanted(manual, pma110, calibrated = true, alreadySwept = false, transformRequired = true))
    }

    @Test fun `a pending sweep step takes the repeating wire for an app-owned white point only`() {
        val manual = ManualControls(wbMode = WbMode.MANUAL, wbKelvin = 5000)
        val step = CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
        assertEquals(WbWire.Sweep(step), resolveWbWire(manual, flip6, pma110Cam2, flip6Auto, true, sweepPreset = step))
        assertFalse(WbWire.Sweep(step).awbOff)
        // AUTO, an honoured preset and a RAW-only session ignore a pending step.
        assertTrue(resolveWbWire(ManualControls(), flip6, pma110Cam2, flip6Auto, true, sweepPreset = step) is WbWire.Auto)
        assertTrue(resolveWbWire(ManualControls(wbMode = WbMode.DAYLIGHT), flip6, pma110Cam2, flip6Auto, true, sweepPreset = step) is WbWire.Preset)
        assertTrue(resolveWbWire(manual, flip6, pma110Cam2, flip6Auto, false, sweepPreset = step) is WbWire.Matrix)
        // Without a pending step the matrix carries the route's transform, as always.
        assertTrue(resolveWbWire(manual, flip6, pma110Cam2, flip6Auto, true) is WbWire.Matrix)
    }

    @Test fun `a swept route stays swept through the snapshot and a seed`() {
        val store = WbReferenceStore()
        val key = WbRouteKey("0", WbSessionShape.SDR, "5")
        var notified = 0
        store.onChanged = { notified++ }
        assertFalse(store.isSwept(key))
        store.markSwept(key)
        store.markSwept(key)
        assertEquals(1, notified)
        val restored = WbReferenceStore()
        restored.seed(store.snapshot())
        assertTrue(restored.isSwept(key))
    }

    @Test fun `every wire names the AWB mode its request carries`() {
        val off = CameraMetadata.CONTROL_AWB_MODE_OFF
        assertNull(WbWire.Omit.awbMode())
        assertEquals(CameraMetadata.CONTROL_AWB_MODE_AUTO, WbWire.Auto(CameraMetadata.CONTROL_AWB_MODE_AUTO, true).awbMode())
        assertEquals(CameraMetadata.CONTROL_AWB_MODE_AUTO, WbWire.Prime(CameraMetadata.CONTROL_AWB_MODE_AUTO).awbMode())
        assertEquals(CameraMetadata.CONTROL_AWB_MODE_SHADE, WbWire.Preset(CameraMetadata.CONTROL_AWB_MODE_SHADE).awbMode())
        assertEquals(CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT, WbWire.Sweep(CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT).awbMode())
        assertEquals(off, WbWire.Cct(3200, 0).awbMode())
        assertEquals(off, WbWire.MakerCct(3200, 0).awbMode())
        assertEquals(off, WbWire.Matrix(floatArrayOf(1f, 1f, 1f, 2f), autoTransform).awbMode())
    }

    @Test fun `a reload keeps which white point was learned last`() {
        val store = WbReferenceStore()
        val key = WbRouteKey("0", WbSessionShape.SDR, "5")
        store.offer(key, flip6Cloudy, 6313.0)
        store.offer(key, flip6Incandescent, 2873.0)
        store.offer(key, flip6Daylight, 5162.0)
        val restored = WbReferenceStore()
        restored.seed(store.snapshot())
        // Newest first through the snapshot, so the reload's "latest" is still Daylight.
        assertArrayEquals(flip6Daylight, restored.lookup(key), 0.0)
        assertEquals(listOf(5162.0, 2873.0, 6313.0), restored.snapshot().samples.getValue(key).map { it.kelvin })
    }

    @Test fun `AUTO learning crowding the middle never pushes out the swept ends`() {
        val store = WbReferenceStore()
        val key = WbRouteKey("0", WbSessionShape.SDR, "5")
        store.offer(key, flip6Incandescent, 2850.0)
        store.offer(key, flip6Cloudy, 7500.0)
        // Sixteen AUTO white points 11 mired apart between them, all newer than the two anchors.
        (0 until 16).forEach { i -> store.offer(key, flip6Daylight, 1e6 / (330.0 - i * 11.0)) }
        val kept = store.snapshot().samples.getValue(key).mapNotNull { it.kelvin }
        assertEquals(WbReferenceStore.WB_REFERENCE_MAX_SAMPLES, kept.size)
        assertTrue(2850.0 in kept)
        assertTrue(7500.0 in kept)
    }

    @Test fun `a late seed merges persisted white points into a route that learned first`() {
        val persisted = WbReferenceStore().apply {
            offer(WbRouteKey("0", WbSessionShape.SDR, "5"), flip6Incandescent, 2873.0)
            offer(WbRouteKey("0", WbSessionShape.SDR, "5"), flip6Cloudy, 6313.0)
        }.snapshot()
        val store = WbReferenceStore()
        val key = WbRouteKey("0", WbSessionShape.SDR, "5")
        store.offer(key, flip6Auto, 2900.0)
        store.seed(persisted)
        val kelvins = store.snapshot().samples.getValue(key).map { it.kelvin }
        // The learned 2900 K holds its white point (2873 K is within the merge radius); Cloudy joins.
        assertEquals(listOf(2900.0, 6313.0), kelvins)
        assertArrayEquals(flip6Auto, store.lookup(key, 2900.0), 0.0)
    }
}
