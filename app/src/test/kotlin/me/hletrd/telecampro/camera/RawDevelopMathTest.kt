package me.hletrd.telecampro.camera

import android.hardware.camera2.CameraMetadata
import me.hletrd.telecampro.gl.LogProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The EXPERIMENTAL RAW log developer's colour and geometry math (RawDevelopMath.kt): one composed
 * camera → S-Gamut3.Cine matrix that never visits BT.709, the DNG calibration interpolation, and the
 * raw crop that stands in for the ISP's aspect/zoom crop.
 */
class RawDevelopMathTest {

    private val d50 = doubleArrayOf(0.96422, 1.0, 0.82521)

    // PMA110 camera 2 / 4 ForwardMatrix1 (the D65 calibration), dumpsys 2026-10-25, row-major.
    private val pma110Fm1 = doubleArrayOf(
        56 / 128.0, 49 / 128.0, 18 / 128.0,
        28 / 128.0, 92 / 128.0, 8 / 128.0,
        2 / 128.0, 12 / 128.0, 91 / 128.0,
    )

    private val rec709ToXyz = doubleArrayOf(
        0.4123908, 0.3575843, 0.1804808,
        0.2126390, 0.7151687, 0.0721923,
        0.0193308, 0.1191948, 0.9505322,
    )

    private fun assertMatrix(expected: DoubleArray, actual: DoubleArray, delta: Double, message: String) {
        for (i in 0 until 9) assertEquals("$message [$i]", expected[i], actual[i], delta)
    }

    @Test
    fun `D50 white lands on S-Gamut3_Cine white`() {
        val white = RawDevelopMath.mulVec(RawDevelopMath.XYZ_D50_TO_SG3C, d50)
        white.forEach { assertEquals(1.0, it, 2e-4) }
    }

    @Test
    fun `the Sony matrices are the same gamut the shader's 709 constants describe`() {
        // inv(SG3C->XYZ) x (709->XYZ) must reproduce LogProfiles' typed 709->S-Gamut3.Cine constants.
        val derived = RawDevelopMath.multiply(RawDevelopMath.invert(RawDevelopMath.SG3C_TO_XYZ_D65), rec709ToXyz)
        val typed = doubleArrayOf(
            LogProfiles.SG3C_R_FROM_R, LogProfiles.SG3C_R_FROM_G, LogProfiles.SG3C_R_FROM_B,
            LogProfiles.SG3C_G_FROM_R, LogProfiles.SG3C_G_FROM_G, LogProfiles.SG3C_G_FROM_B,
            LogProfiles.SG3C_B_FROM_R, LogProfiles.SG3C_B_FROM_G, LogProfiles.SG3C_B_FROM_B,
        )
        assertMatrix(typed, derived, 1e-6, "709->SG3C")
        // And S-Gamut3.Cine -> S-Gamut3 composes with it into the typed 709->S-Gamut3 constants.
        val toSg3 = RawDevelopMath.multiply(RawDevelopMath.SG3C_TO_SG3, typed)
        val typedSg3 = doubleArrayOf(
            LogProfiles.SG3_R_FROM_R, LogProfiles.SG3_R_FROM_G, LogProfiles.SG3_R_FROM_B,
            LogProfiles.SG3_G_FROM_R, LogProfiles.SG3_G_FROM_G, LogProfiles.SG3_G_FROM_B,
            LogProfiles.SG3_B_FROM_R, LogProfiles.SG3_B_FROM_G, LogProfiles.SG3_B_FROM_B,
        )
        assertMatrix(typedSg3, toSg3, 1e-6, "709->SG3")
    }

    // PMA110 camera 2 ColorMatrix1 (D65) / ColorMatrix2 (Standard A) and CameraCalibration1, dumpsys
    // 2026-10-25, row-major. Its ForwardMatrix2 is identical to ForwardMatrix1 (the placeholder).
    private val pma110Cm1 = doubleArrayOf(0.7387, -0.2388, -0.0681, -0.4394, 1.2479, 0.1503, -0.1678, 0.3424, 0.4286)
    private val pma110Cm2 = doubleArrayOf(0.9905, -0.3888, 0.0076, -0.4535, 1.4172, 0.2610, -0.0864, 0.2818, 0.6377)
    private val pma110Cc1 = doubleArrayOf(1.0078, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.9922)

    @Test
    fun `the PMA110 ForwardMatrix pair is a placeholder and the ColorMatrix pair is not`() {
        assertFalse(RawDevelopMath.forwardMatrixInformative(pma110Fm1, pma110Fm1.copyOf(), pma110Cm1, pma110Cm2))
        // Through the placeholder, camera -> linear 709 is the identity to ±0.01: no colour correction.
        val viaPlaceholder = RawDevelopMath.multiply(
            RawDevelopMath.invert(rec709ToXyz),
            RawDevelopMath.multiply(RawDevelopMath.BRADFORD_D50_TO_D65, pma110Fm1),
        )
        assertMatrix(RawDevelopMath.IDENTITY, viaPlaceholder, 0.011, "placeholder FM ~ sRGB")
        // A real pair, a single-illuminant profile, and an absent FM each decide as the DNG spec reads.
        assertTrue(RawDevelopMath.forwardMatrixInformative(pma110Fm1, RawDevelopMath.IDENTITY, pma110Cm1, pma110Cm2))
        assertTrue(RawDevelopMath.forwardMatrixInformative(pma110Fm1, null, pma110Cm1, null))
        assertFalse(RawDevelopMath.forwardMatrixInformative(null, null, pma110Cm1, pma110Cm2))
    }

    @Test
    fun `the ColorMatrix path keeps neutral level and actually corrects colour`() {
        val gains = doubleArrayOf(1.36, 1.0, 2.49)
        val cct = RawDevelopMath.neutralCct(gains, pma110Cm1, pma110Cm2, pma110Cc1, null, 6504.0, 2856.0)
        val w = RawDevelopMath.matrixWeight(cct, 6504.0, 2856.0)
        val m = RawDevelopMath.cameraToSg3cFromColorMatrix(gains, pma110Cm1, pma110Cm2, pma110Cc1, null, w)
        // The gained neutral keeps its level: sensor clip stays 1.0 in S-Gamut3.Cine (to the same
        // 2e-4 the tabulated Bradford matrix leaves on D50 white, see the first test).
        RawDevelopMath.mulVec(m, doubleArrayOf(0.5, 0.5, 0.5)).forEach { assertEquals(0.5, it, 2e-4) }
        // Real correction: strong off-diagonal mixing, unlike the placeholder's ~identity.
        val to709 = RawDevelopMath.multiply(
            RawDevelopMath.invert(rec709ToXyz),
            RawDevelopMath.multiply(RawDevelopMath.invert(RawDevelopMath.XYZ_D50_TO_SG3C).let {
                RawDevelopMath.multiply(RawDevelopMath.BRADFORD_D50_TO_D65, it)
            }, m),
        )
        assertTrue("blue row mixes green", to709[7] < -0.5)
        // This warm interior's gains resolve near tungsten, inside the calibration span.
        assertTrue("cct $cct", cct in 2000.0..3500.0)
    }

    @Test
    fun `Bradford reproduces the tabulated D50 to D65 matrix`() {
        val d65 = doubleArrayOf(0.95047, 1.0, 1.08883)
        assertMatrix(RawDevelopMath.BRADFORD_D50_TO_D65, RawDevelopMath.bradford(d50, d65), 2e-4, "Bradford")
        assertMatrix(RawDevelopMath.IDENTITY, RawDevelopMath.bradford(d65, d65), 1e-12, "same white")
    }

    @Test
    fun `the ForwardMatrix path keeps neutral level`() {
        val m = RawDevelopMath.cameraToSg3c(doubleArrayOf(1.36, 1.0, 2.49), pma110Fm1, null, null, null, 1.0)
        RawDevelopMath.mulVec(m, doubleArrayOf(0.5, 0.5, 0.5)).forEach { assertEquals(0.5, it, 4e-3) }
    }

    @Test
    fun `an identity calibration reduces to XYZ_D50_TO_SG3C x ForwardMatrix`() {
        val withCal = RawDevelopMath.cameraToSg3c(
            doubleArrayOf(2.0, 1.0, 1.6), pma110Fm1, null, RawDevelopMath.IDENTITY, null, 1.0,
        )
        val plain = RawDevelopMath.multiply(RawDevelopMath.XYZ_D50_TO_SG3C, pma110Fm1)
        assertMatrix(plain, withCal, 1e-9, "identity CC")
    }

    @Test
    fun `the DNG weight is linear in mired and clamps outside the calibrations`() {
        assertEquals(1.0, RawDevelopMath.matrixWeight(6504.0, 6504.0, 2856.0), 1e-12)
        assertEquals(0.0, RawDevelopMath.matrixWeight(2856.0, 6504.0, 2856.0), 1e-12)
        assertEquals(1.0, RawDevelopMath.matrixWeight(9000.0, 6504.0, 2856.0), 1e-12)
        assertEquals(0.0, RawDevelopMath.matrixWeight(2000.0, 6504.0, 2856.0), 1e-12)
        val mid = 1.0 / ((1.0 / 6504.0 + 1.0 / 2856.0) / 2.0)
        assertEquals(0.5, RawDevelopMath.matrixWeight(mid, 6504.0, 2856.0), 1e-9)
        // Order-independent.
        assertEquals(0.5, RawDevelopMath.matrixWeight(mid, 2856.0, 6504.0), 1e-9)
    }

    @Test
    fun `McCamy places D65 and illuminant A where they belong`() {
        assertEquals(6504.0, RawDevelopMath.cctFromXy(0.31271, 0.32902), 25.0)
        assertEquals(2856.0, RawDevelopMath.cctFromXy(0.44757, 0.40745), 25.0)
    }

    @Test
    fun `the white balance's temperature follows the neutral the gains imply`() {
        // A synthetic camera whose ColorMatrix is the XYZ->linear-709 matrix at both calibrations:
        // gains that neutralise D65 must come back near 6500 K.
        val xyzTo709 = RawDevelopMath.invert(rec709ToXyz)
        val d65Neutral709 = RawDevelopMath.mulVec(xyzTo709, doubleArrayOf(0.95047, 1.0, 1.08883))
        val gains = doubleArrayOf(1.0 / d65Neutral709[0], 1.0 / d65Neutral709[1], 1.0 / d65Neutral709[2])
            .let { g -> DoubleArray(3) { g[it] / g[1] } }
        val cct = RawDevelopMath.neutralCct(gains, xyzTo709, xyzTo709, null, null, 6504.0, 2856.0)
        assertEquals(6504.0, cct, 60.0)
    }

    @Test
    fun `Bayer phases name the red site`() {
        assertEquals(RawDevelopMath.BayerPhase(0, 0), RawDevelopMath.bayerPhase(CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB))
        assertEquals(RawDevelopMath.BayerPhase(1, 0), RawDevelopMath.bayerPhase(CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG))
        assertEquals(RawDevelopMath.BayerPhase(0, 1), RawDevelopMath.bayerPhase(CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG))
        // PMA110's rear cameras: red at (odd, odd).
        assertEquals(RawDevelopMath.BayerPhase(1, 1), RawDevelopMath.bayerPhase(CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR))
        assertNull(RawDevelopMath.bayerPhase(CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGB))
        assertNull(RawDevelopMath.bayerPhase(null))
    }

    @Test
    fun `the PMA110 calibration illuminants have their temperatures`() {
        assertEquals(6504.0, RawDevelopMath.illuminantCct(CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D65)!!, 0.0)
        assertEquals(2856.0, RawDevelopMath.illuminantCct(CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_A)!!, 0.0)
        assertNull(RawDevelopMath.illuminantCct(null))
        assertNull(RawDevelopMath.illuminantCct(0))
    }

    @Test
    fun `the crop stands in for the ISP's aspect and zoom crop on whole Bayer tiles`() {
        // 16:9 from camera 2's 4080x3064 array: full width, centred band.
        val wide = RawDevelopMath.cropRect(4080, 3064, 3840, 2160, 1f)
        assertEquals(RawDevelopMath.CropRect(0, 384, 4080, 2294), wide)
        // 2x zoom halves both sides, still centred and even.
        val zoomed = RawDevelopMath.cropRect(4080, 3064, 3840, 2160, 2f)
        assertEquals(2040, zoomed.width)
        assertEquals(1148, zoomed.height)
        assertEquals(1020, zoomed.x)
        assertEquals(958, zoomed.y)
        listOf(wide, zoomed).forEach { r ->
            assertTrue(r.x % 2 == 0 && r.y % 2 == 0 && r.width % 2 == 0 && r.height % 2 == 0)
        }
        // 4:3 (Open Gate) takes the whole array; a zoom below 1 never widens past it.
        assertEquals(RawDevelopMath.CropRect(0, 0, 4096, 3072), RawDevelopMath.cropRect(4096, 3072, 2560, 1920, 1f))
        assertEquals(RawDevelopMath.CropRect(0, 0, 4096, 3072), RawDevelopMath.cropRect(4096, 3072, 2560, 1920, 0.5f))
    }

    @Test
    fun `every calibrated illuminant code has the DNG SDK temperature`() {
        val table = mapOf(
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_TUNGSTEN to 2850.0,
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_ISO_STUDIO_TUNGSTEN to 3200.0,
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_WHITE_FLUORESCENT to 3450.0,
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_COOL_WHITE_FLUORESCENT to 4150.0,
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_B to 4874.0,
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_DAY_WHITE_FLUORESCENT to 5000.0,
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D50 to 5003.0,
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_DAYLIGHT to 5500.0,
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_FLASH to 5500.0,
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_FINE_WEATHER to 5500.0,
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D55 to 5503.0,
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_DAYLIGHT_FLUORESCENT to 6430.0,
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_CLOUDY_WEATHER to 6500.0,
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_C to 6774.0,
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_SHADE to 7500.0,
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D75 to 7504.0,
        )
        table.forEach { (code, cct) -> assertEquals("code $code", cct, RawDevelopMath.illuminantCct(code)!!, 0.0) }
        // FLUORESCENT has no single temperature: no calibration weight can be derived from it.
        assertNull(RawDevelopMath.illuminantCct(CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_FLUORESCENT))
    }

    @Test
    fun `a two-calibration profile interpolates CameraCalibration with the matrices`() {
        val cal2 = doubleArrayOf(0.98, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.03)
        val gains = doubleArrayOf(1.8, 1.0, 1.7)
        // Weight 1 must equal a calibration-1-only profile; weight 0 the calibration-2 one.
        val both1 = RawDevelopMath.cameraToSg3c(gains, pma110Fm1, RawDevelopMath.IDENTITY, pma110Cc1, cal2, 1.0)
        val only1 = RawDevelopMath.cameraToSg3c(gains, pma110Fm1, null, pma110Cc1, null, 1.0)
        assertMatrix(only1, both1, 1e-12, "weight 1 = calibration 1")
        val both0 = RawDevelopMath.cameraToSg3c(gains, pma110Fm1, pma110Fm1, pma110Cc1, cal2, 0.0)
        val only2 = RawDevelopMath.cameraToSg3c(gains, pma110Fm1, null, cal2, null, 1.0)
        assertMatrix(only2, both0, 1e-12, "weight 0 = calibration 2")
        RawDevelopMath.mulVec(both0, doubleArrayOf(0.3, 0.3, 0.3)).forEach { assertEquals(0.3, it, 3e-3) }
    }

    @Test
    fun `a single-illuminant ColorMatrix profile ignores the weight`() {
        val gains = doubleArrayOf(1.36, 1.0, 2.49)
        val a = RawDevelopMath.cameraToSg3cFromColorMatrix(gains, pma110Cm1, null, pma110Cc1, null, 1.0)
        val b = RawDevelopMath.cameraToSg3cFromColorMatrix(gains, pma110Cm1, null, pma110Cc1, null, 0.0)
        assertMatrix(a, b, 0.0, "no second matrix to blend")
        RawDevelopMath.mulVec(a, doubleArrayOf(0.5, 0.5, 0.5)).forEach { assertEquals(0.5, it, 2e-4) }
        // And neutralCct, with no second calibration, answers the one it has.
        assertEquals(6504.0, RawDevelopMath.neutralCct(gains, pma110Cm1, null, null, null, 6504.0, null), 0.0)
    }

    @Test
    fun `an array wider than the output keeps full height and centres the width`() {
        // A 16:9 sensor feeding 4:3 output (the opposite of PMA110's 4:3 sensor into 16:9 video).
        val r = RawDevelopMath.cropRect(4000, 2250, 1440, 1080, 1f)
        assertEquals(2250 / 2 * 2, r.height)
        assertEquals(3000, r.width)
        assertEquals(500, r.x)
        assertEquals(0, r.y)
    }

    @Test
    fun `matrix helpers invert and compose`() {
        val m = RawDevelopMath.XYZ_D50_TO_SG3C
        assertMatrix(RawDevelopMath.IDENTITY, RawDevelopMath.multiply(m, RawDevelopMath.invert(m)), 1e-12, "m x inv(m)")
        assertMatrix(m, RawDevelopMath.lerp(m, RawDevelopMath.IDENTITY, 1.0), 0.0, "lerp weight 1")
    }
}
