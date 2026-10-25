package me.hletrd.telecampro.camera

import android.hardware.camera2.CameraMetadata
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure colour and geometry math behind the EXPERIMENTAL RAW log developer ([RawLogBridge]): which
 * Bayer site is red, how the white balance picks between the sensor's two calibration matrices, the
 * ONE camera-RGB → S-Gamut3.Cine matrix the shader applies, and the raw crop that stands in for the
 * ISP's aspect/zoom crop. Host-tested; nothing here touches Android framework objects beyond the
 * metadata constants.
 *
 * Why one composed matrix (owner direction 2026-10-25: "log must not go through 709"): a RAW-derived
 * log has no reason to visit a display gamut. Camera RGB → XYZ(D50) → Bradford D50→D65 →
 * S-Gamut3.Cine (the exact inverse of Sony's published S-Gamut3.Cine→XYZ) is composed on the CPU
 * into one 3×3, so nothing is clipped to a narrower intermediate.
 *
 * Camera RGB → XYZ follows the DNG specification's two paths. With an informative ForwardMatrix:
 * `FM(cct) × D × inverse(CC)`. Without one: `CA × inverse(CC × CM(cct))`, CA the Bradford adaptation
 * from the as-shot white to D50. PMA110 forces the second (dumpsys 2026-10-25, every rear camera):
 * ForwardMatrix1 and ForwardMatrix2 are IDENTICAL and equal the standard sRGB→XYZ(D50) matrix to
 * the 1/128 quantization — a placeholder, which would treat native camera RGB as sRGB and skip colour
 * correction entirely (camera→709 through it is the identity to ±0.01) — while the ColorMatrix pair
 * differs between D65 and Standard A as real calibration does. [forwardMatrixInformative] makes that
 * call from the data, never from a model string.
 */
internal object RawDevelopMath {

    /** Row-major 3×3 identity. */
    val IDENTITY: DoubleArray = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    /**
     * CIE XYZ (D50, the DNG profile connection space) → linear S-Gamut3.Cine (D65): Sony's published
     * S-Gamut3.Cine→XYZ(D65) inverted, after the Bradford D50→D65 adaptation. Derived, not typed: see
     * [SG3C_TO_XYZ_D65] and [BRADFORD_D50_TO_D65]; the host test pins D50 white → (1, 1, 1) and the
     * composition against [me.hletrd.telecampro.gl.LogProfiles]' BT.709→S-Gamut3.Cine constants.
     */
    val XYZ_D50_TO_SG3C: DoubleArray by lazy { multiply(invert(SG3C_TO_XYZ_D65), BRADFORD_D50_TO_D65) }

    /** Sony S-Gamut3.Cine → CIE XYZ (D65), from Sony's S-Gamut3.Cine/S-Log3 technical summary. */
    val SG3C_TO_XYZ_D65: DoubleArray = doubleArrayOf(
        0.599083920, 0.248925516, 0.102446490,
        0.215075820, 0.885068502, -0.100144322,
        -0.032065850, -0.027658391, 1.148781596,
    )

    /** The Bradford cone-response matrix (Lam 1985), for [bradford] adaptations between whites. */
    val BRADFORD_CONE: DoubleArray = doubleArrayOf(
        0.8951, 0.2664, -0.1614,
        -0.7502, 1.7135, 0.0367,
        0.0389, -0.0685, 1.0296,
    )

    /** CIE XYZ of D50 (the DNG profile connection space white), Y = 1. */
    val D50_WHITE: DoubleArray = doubleArrayOf(0.96422, 1.0, 0.82521)

    /** Bradford chromatic adaptation from white [source] to white [target] (both XYZ). */
    fun bradford(source: DoubleArray, target: DoubleArray): DoubleArray {
        val s = mulVec(BRADFORD_CONE, source)
        val t = mulVec(BRADFORD_CONE, target)
        return multiply(multiply(invert(BRADFORD_CONE), diag(DoubleArray(3) { t[it] / s[it] })), BRADFORD_CONE)
    }

    /** Bradford chromatic adaptation D50 → D65 (Lindbloom's tabulation). */
    val BRADFORD_D50_TO_D65: DoubleArray = doubleArrayOf(
        0.9555766, -0.0230393, 0.0631636,
        -0.0282895, 1.0099416, 0.0210077,
        0.0122982, -0.0204830, 1.3299098,
    )

    /** S-Gamut3.Cine → S-Gamut3 (both D65), for the S-Log3/S-Gamut3 transfer on a RAW source. */
    val SG3C_TO_SG3: DoubleArray by lazy { multiply(invert(SG3_TO_XYZ_D65), SG3C_TO_XYZ_D65) }

    /** Sony S-Gamut3 → CIE XYZ (D65), same technical summary. */
    val SG3_TO_XYZ_D65: DoubleArray = doubleArrayOf(
        0.706482713, 0.128801049, 0.115172164,
        0.270979671, 0.786606411, -0.057586082,
        -0.009677845, 0.004600038, 1.094135558,
    )

    /** The red site of a 2×2 Bayer tile as (x & 1, y & 1); blue is the diagonal opposite. */
    data class BayerPhase(val redX: Int, val redY: Int)

    /** SENSOR_INFO_COLOR_FILTER_ARRANGEMENT → red site; null for RGB/mono/NIR (not developed). */
    fun bayerPhase(cfa: Int?): BayerPhase? = when (cfa) {
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> BayerPhase(0, 0)
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> BayerPhase(1, 0)
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> BayerPhase(0, 1)
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> BayerPhase(1, 1)
        else -> null
    }

    /**
     * Correlated colour temperature of a SENSOR_REFERENCE_ILLUMINANT (EXIF LightSource) code, the
     * table the Adobe DNG SDK uses; null for codes with no defined temperature (the caller then
     * takes matrix 1 alone).
     */
    fun illuminantCct(code: Int?): Double? = when (code) {
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_A -> 2856.0
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_TUNGSTEN -> 2850.0
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_ISO_STUDIO_TUNGSTEN -> 3200.0
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_WHITE_FLUORESCENT -> 3450.0
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_COOL_WHITE_FLUORESCENT -> 4150.0
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_B -> 4874.0
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_DAY_WHITE_FLUORESCENT -> 5000.0
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D50 -> 5003.0
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_DAYLIGHT,
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_FLASH,
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_FINE_WEATHER,
        -> 5500.0
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D55 -> 5503.0
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_DAYLIGHT_FLUORESCENT -> 6430.0
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_CLOUDY_WEATHER -> 6500.0
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D65 -> 6504.0
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_C -> 6774.0
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_SHADE -> 7500.0
        CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D75 -> 7504.0
        else -> null
    }

    /**
     * DNG interpolation weight of calibration 1 at [cct]: linear in inverse temperature (mired)
     * between the two calibration illuminants, clamped to [0, 1] outside them. Order-independent.
     */
    fun matrixWeight(cct: Double, cct1: Double, cct2: Double): Double {
        if (abs(1.0 / cct1 - 1.0 / cct2) < 1e-12) return 1.0
        return ((1.0 / cct - 1.0 / cct2) / (1.0 / cct1 - 1.0 / cct2)).coerceIn(0.0, 1.0)
    }

    /** McCamy's CCT approximation from CIE 1931 xy (adequate across 2000–12500 K for a weight). */
    fun cctFromXy(x: Double, y: Double): Double {
        val n = (x - 0.3320) / (0.1858 - y)
        return 449.0 * n * n * n + 3525.0 * n * n + 6823.3 * n + 5520.33
    }

    /**
     * The white balance's temperature, found the DNG way: the camera-space neutral the gains imply
     * is mapped back to XYZ through the interpolated ColorMatrix (XYZ → camera, times the per-unit
     * CameraCalibration), the resulting chromaticity gives a temperature, and the two steps repeat
     * until the weight settles. [gains] are COLOR_CORRECTION_GAINS as (R, G, B) — the HAL's own AWB
     * (or the operator's preset), so the developed white follows the same decision the ISP made.
     */
    fun neutralCct(
        gains: DoubleArray,
        colorMatrix1: DoubleArray,
        colorMatrix2: DoubleArray?,
        calibration1: DoubleArray?,
        calibration2: DoubleArray?,
        cct1: Double,
        cct2: Double?,
    ): Double {
        val neutral = doubleArrayOf(gains[1] / gains[0], 1.0, gains[1] / gains[2])
        if (colorMatrix2 == null || cct2 == null) return cct1
        var cct = 5000.0
        repeat(NEUTRAL_CCT_ITERATIONS) {
            val w = matrixWeight(cct, cct1, cct2)
            val cm = lerp(
                multiply(calibration1 ?: IDENTITY, colorMatrix1),
                multiply(calibration2 ?: IDENTITY, colorMatrix2),
                w,
            )
            val xyz = mulVec(invert(cm), neutral)
            val sum = xyz[0] + xyz[1] + xyz[2]
            if (sum <= 0.0) return cct
            cct = cctFromXy(xyz[0] / sum, xyz[1] / sum).coerceIn(MIN_CCT, MAX_CCT)
        }
        return cct
    }

    /**
     * White-balanced camera RGB → linear S-Gamut3.Cine: [XYZ_D50_TO_SG3C] × ForwardMatrix(cct) ×
     * diag(1 / referenceNeutral) × inverse(CameraCalibration) × diag(1 / gains), the DNG
     * "CameraToXYZ_D50 = FM × D × inverse(AB × CC)" with AB = I, re-expressed on the GAINED signal the
     * shader holds (it multiplies by [gains] first, for its highlight clamp). With a calibration of
     * identity this reduces to XYZ_D50_TO_SG3C × FM. Rows sum to ~1 because a ForwardMatrix maps the
     * reference neutral to D50 white, so a neutral grey keeps its level: sensor clip stays 1.0.
     */
    fun cameraToSg3c(
        gains: DoubleArray,
        forwardMatrix1: DoubleArray,
        forwardMatrix2: DoubleArray?,
        calibration1: DoubleArray?,
        calibration2: DoubleArray?,
        weight1: Double,
    ): DoubleArray {
        val fm = if (forwardMatrix2 == null) forwardMatrix1 else lerp(forwardMatrix1, forwardMatrix2, weight1)
        val cc = when {
            calibration1 == null -> IDENTITY
            calibration2 == null -> calibration1
            else -> lerp(calibration1, calibration2, weight1)
        }
        val ccInv = invert(cc)
        val cameraNeutral = doubleArrayOf(1.0 / gains[0], 1.0 / gains[1], 1.0 / gains[2])
        val referenceNeutral = mulVec(ccInv, cameraNeutral)
        val d = diag(DoubleArray(3) { 1.0 / referenceNeutral[it] })
        val ungain = diag(DoubleArray(3) { 1.0 / gains[it] })
        // Normalize so the gained neutral (1, 1, 1) lands on XYZ(D50) with Y = 1 exactly.
        val raw = multiply(multiply(multiply(fm, d), ccInv), ungain)
        val y = raw[3] + raw[4] + raw[5]
        val scaled = DoubleArray(9) { raw[it] / y }
        return multiply(XYZ_D50_TO_SG3C, scaled)
    }

    /**
     * The DNG "no ForwardMatrix" path on the GAINED signal: [XYZ_D50_TO_SG3C] × CA × inverse(CC × CM)
     * × diag(1 / gains), CA the Bradford adaptation from the as-shot white (the camera neutral the
     * [gains] imply, mapped to XYZ) to D50. Normalized so the gained neutral (1, 1, 1) lands on
     * S-Gamut3.Cine (1, 1, 1): sensor clip stays 1.0, as on the ForwardMatrix path.
     */
    fun cameraToSg3cFromColorMatrix(
        gains: DoubleArray,
        colorMatrix1: DoubleArray,
        colorMatrix2: DoubleArray?,
        calibration1: DoubleArray?,
        calibration2: DoubleArray?,
        weight1: Double,
    ): DoubleArray {
        val xyzToCamera1 = multiply(calibration1 ?: IDENTITY, colorMatrix1)
        val xyzToCamera = if (colorMatrix2 == null) {
            xyzToCamera1
        } else {
            lerp(xyzToCamera1, multiply(calibration2 ?: IDENTITY, colorMatrix2), weight1)
        }
        val cameraToXyz = invert(xyzToCamera)
        val white = mulVec(cameraToXyz, doubleArrayOf(1.0 / gains[0], 1.0 / gains[1], 1.0 / gains[2]))
        val whiteNormalized = DoubleArray(3) { white[it] / white[1] }
        val adapted = multiply(bradford(whiteNormalized, D50_WHITE), cameraToXyz)
        val raw = multiply(adapted, diag(DoubleArray(3) { 1.0 / gains[it] }))
        val y = raw[3] + raw[4] + raw[5]
        return multiply(XYZ_D50_TO_SG3C, DoubleArray(9) { raw[it] / y })
    }

    /**
     * Whether a ForwardMatrix pair carries calibration: false when the two are identical while the
     * ColorMatrix pair differs (a real two-illuminant calibration never yields one ForwardMatrix for
     * both), and false when there is none. A single-illuminant profile (no second matrices) trusts it.
     */
    fun forwardMatrixInformative(
        forward1: DoubleArray?,
        forward2: DoubleArray?,
        color1: DoubleArray?,
        color2: DoubleArray?,
    ): Boolean {
        if (forward1 == null) return false
        if (forward2 == null || color1 == null || color2 == null) return true
        val forwardSame = (0 until 9).all { abs(forward1[it] - forward2[it]) < 1e-6 }
        val colorSame = (0 until 9).all { abs(color1[it] - color2[it]) < 1e-4 }
        return !(forwardSame && !colorSame)
    }

    /** A raw-array rectangle in sensor pixels. */
    data class CropRect(val x: Int, val y: Int, val width: Int, val height: Int)

    /**
     * The raw region that stands in for the ISP's aspect + zoom crop: the largest centred rectangle
     * of the OUTPUT aspect inside the array, divided by [zoomRatio] (lens-local, >= 1 on a
     * standalone camera), with even origin and size so the region starts on a whole Bayer tile.
     */
    fun cropRect(arrayWidth: Int, arrayHeight: Int, outWidth: Int, outHeight: Int, zoomRatio: Float): CropRect {
        val outAspect = outWidth.toDouble() / outHeight
        val baseW: Double
        val baseH: Double
        if (arrayWidth.toDouble() / arrayHeight > outAspect) {
            baseH = arrayHeight.toDouble()
            baseW = baseH * outAspect
        } else {
            baseW = arrayWidth.toDouble()
            baseH = baseW / outAspect
        }
        val z = max(1.0, zoomRatio.toDouble())
        val w = evenDown(min(arrayWidth.toDouble(), baseW / z))
        val h = evenDown(min(arrayHeight.toDouble(), baseH / z))
        val x = evenDown((arrayWidth - w) / 2.0)
        val y = evenDown((arrayHeight - h) / 2.0)
        return CropRect(x, y, w, h)
    }

    private fun evenDown(v: Double): Int = (v.roundToInt() / 2) * 2

    // ---- 3×3 row-major helpers ----

    fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(9) { i ->
        val r = i / 3
        val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    fun mulVec(m: DoubleArray, v: DoubleArray): DoubleArray = DoubleArray(3) { r ->
        m[r * 3] * v[0] + m[r * 3 + 1] * v[1] + m[r * 3 + 2] * v[2]
    }

    fun invert(m: DoubleArray): DoubleArray {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val k = m[8]
        val det = a * (e * k - f * h) - b * (d * k - f * g) + c * (d * h - e * g)
        require(abs(det) > 1e-12) { "singular 3x3" }
        return doubleArrayOf(
            (e * k - f * h) / det, (c * h - b * k) / det, (b * f - c * e) / det,
            (f * g - d * k) / det, (a * k - c * g) / det, (c * d - a * f) / det,
            (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det,
        )
    }

    fun lerp(m1: DoubleArray, m2: DoubleArray, weight1: Double): DoubleArray =
        DoubleArray(9) { m1[it] * weight1 + m2[it] * (1.0 - weight1) }

    private fun diag(v: DoubleArray): DoubleArray = doubleArrayOf(v[0], 0.0, 0.0, 0.0, v[1], 0.0, 0.0, 0.0, v[2])

    private const val NEUTRAL_CCT_ITERATIONS = 4
    private const val MIN_CCT = 2000.0
    private const val MAX_CCT = 12000.0
}
