package me.hletrd.telecampro.camera

import android.hardware.camera2.CameraMetadata
import kotlin.math.abs
import kotlin.math.hypot

/*
 * White balance: which Camera2 mechanism carries the operator's choice on a route, and the pure math
 * behind it. Device-measured on PMA110 (ColorOS 17, 2026-10-27, isolated Camera2 probe + chart; raw
 * evidence .context/wb-fix-2026-10-27/), per route:
 *
 *   route                     AUTO  presets  CCT mode            FAST+gains   TRANSFORM_MATRIX
 *   photo, logical 0          yes   ignored  K+tint, CCM = I     fixed blue   honoured exactly
 *   video SDR, standalone 2   yes   ignored  K+tint, CCM = I     fixed blue   honoured exactly
 *   native S-Log3, cam 2      yes   ignored  K+tint, CCM = I     fixed blue   honoured exactly
 *   TELE, cam 4 + 0x80b4      yes   ignored  K+tint, CCM = I     fixed blue   honoured exactly
 *   maker Log 0x830b (0, TC)  yes   ignored  tint only, K ignored  ignored    honoured exactly
 *
 * "Ignored" presets record the AWB-AUTO gains. FAST + COLOR_CORRECTION_GAINS — the app's manual path
 * until 2026-10-27 — is spec-wrong: Camera2 documents the gains as camera-owned unless the mode is
 * TRANSFORM_MATRIX, and both HALs measured (PMA110 and the Galaxy Z Flip6, SM-F741N) disregard them
 * (PMA110 then applies a fixed R 0.851 / B 5.263 blue cast). CCT mode applies the HAL's own
 * Kelvin→gains table but an IDENTITY colour transform (result and pixels agree: identical to
 * TRANSFORM_MATRIX with the same gains and I), i.e. camera-native colour, visibly desaturated beside
 * AUTO; on the maker session it ignores the temperature outright. TRANSFORM_MATRIX with gains AND a
 * transform is honoured byte-exactly on every route, the maker one included — so that is the path,
 * with the route's own colour transform (see [WbReferenceStore]): each route renders its own
 * gamut (the maker route's is a wide log gamut, the TC route reports I with colour handled downstream),
 * so no single calibrated matrix is right everywhere. Follow-up on the maker route found the
 * stock AWBCCT vendor key: AWB OFF + FAST + AWBCCT + colour tone uses the OEM Kelvin table and
 * retains processing that TRANSFORM_MATRIX bypasses even with identical gains and CCM. Maker
 * Kelvin/presets therefore take that path; custom gains and every other route keep the matrix.
 */

/** Kelvin + tint controls. Calibrated/CCT paths interpret tint as Duv; maker Log uses native Tone. */
data class WbTarget(val kelvin: Int, val tint: Int)

/** Tint range. Standard/calibrated paths use ±0.01 Duv; maker Log uses the OEM's native Tone units. */
const val WB_TINT_LIMIT = 50

/** Duv per tint unit: Camera2 maps ±50 onto ±0.01 Duv (CaptureRequest.COLOR_CORRECTION_COLOR_TINT). */
private const val DUV_PER_TINT_UNIT = 0.01 / WB_TINT_LIMIT

/**
 * Value of `CameraMetadata.COLOR_CORRECTION_MODE_CCT` (API 36). Spelled out because pure capability
 * code must compile and run on the JVM and on API 33–35, where the constant would be an inlined
 * API-36 field; the request writer still reads the real key only behind an SDK check.
 */
internal const val COLOR_CORRECTION_MODE_CCT_VALUE = 3

/** Ceiling for one WB channel gain; a bounded request beats an out-of-range one at the extremes. */
const val MAX_WB_CHANNEL_GAIN = 8f

/**
 * The white point each preset stands for where a HAL does NOT honour CONTROL_AWB_MODE presets
 * ([DeviceProfile.awbPresetModesIgnored]): the preset is then carried as a Kelvin target through the
 * same mechanism as manual WB. INCANDESCENT and FLUORESCENT take the CIE illuminants Camera2 names
 * for them (A: 2856 K on the locus; F2: 4230 K, +0.0020 Duv → tint +10). DAYLIGHT, CLOUDY and SHADE
 * take the photographic 5500 / 6500 / 7500 K: Camera2 names D65 for DAYLIGHT only "approximately",
 * and a spec HAL that honours presets (Galaxy Z Flip6, 2026-10-27) renders DAYLIGHT ≈ 5160 K and
 * CLOUDY ≈ 6310 K through its own calibration, nowhere near a 6504 K daylight that would leave no
 * room above it for cloudy and shade. Where presets ARE honoured these values are never used.
 * Maker Log sends these control values to the OEM's Kelvin/Tone table; that path does not claim
 * the calibrated path's absolute Duv value for the preset tint.
 */
internal fun wbPresetTarget(mode: WbMode): WbTarget? = when (mode) {
    WbMode.INCANDESCENT -> WbTarget(2856, 0)
    WbMode.FLUORESCENT -> WbTarget(4230, 10)
    WbMode.DAYLIGHT -> WbTarget(5500, 0)
    WbMode.CLOUDY -> WbTarget(6500, 0)
    WbMode.SHADE -> WbTarget(7500, 0)
    WbMode.AUTO, WbMode.MANUAL, WbMode.CUSTOM -> null
}

/**
 * DNG-style colour calibration of ONE sensor (SENSOR_COLOR_TRANSFORM1/2 = XYZ → reference camera,
 * SENSOR_CALIBRATION_TRANSFORM1/2 = reference → this unit, SENSOR_REFERENCE_ILLUMINANT1/2 as CCTs).
 * Only the ColorMatrix pair is used: it is what defines a neutral for an illuminant, and it is the
 * pair PMA110 actually calibrates (its ForwardMatrix pair is an sRGB placeholder, RawDevelopMath).
 */
class ColorCalibration(
    val colorMatrix1: DoubleArray,
    val colorMatrix2: DoubleArray?,
    val calibration1: DoubleArray?,
    val calibration2: DoubleArray?,
    val cct1: Double,
    val cct2: Double?,
) {
    /** XYZ → this unit's camera RGB at [kelvin]: CC × CM, interpolated in mired like the DNG SDK. */
    fun xyzToCamera(kelvin: Double): DoubleArray {
        val m1 = RawDevelopMath.multiply(calibration1 ?: RawDevelopMath.IDENTITY, colorMatrix1)
        val cm2 = colorMatrix2 ?: return m1
        val c2 = cct2 ?: return m1
        val m2 = RawDevelopMath.multiply(calibration2 ?: RawDevelopMath.IDENTITY, cm2)
        return RawDevelopMath.lerp(m1, m2, RawDevelopMath.matrixWeight(kelvin, cct1, c2))
    }

    override fun equals(other: Any?): Boolean = other is ColorCalibration &&
        colorMatrix1.contentEquals(other.colorMatrix1) && colorMatrix2.contentEquals(other.colorMatrix2) &&
        calibration1.contentEquals(other.calibration1) && calibration2.contentEquals(other.calibration2) &&
        cct1 == other.cct1 && cct2 == other.cct2

    override fun hashCode(): Int = colorMatrix1.contentHashCode() * 31 + cct1.hashCode()
}

/** Linear sRGB (D65) from CIE XYZ — IEC 61966-2-1's matrix. */
private val XYZ_TO_LINEAR_SRGB = doubleArrayOf(
    3.2404542, -1.5371385, -0.4985314,
    -0.9692660, 1.8760108, 0.0415560,
    0.0556434, -0.2040259, 1.0572252,
)

/** CIE XYZ of D65, Y = 1. */
private val D65_WHITE = doubleArrayOf(0.95047, 1.0, 1.08883)

/**
 * The Planckian locus in CIE 1960 (u, v) — Krystek's (1985) rational approximation, quoted as accurate
 * to ~1e-5 in uv across 1000–15000 K; [kelvin] is clamped into that span. Checked against the CIE
 * illuminants it must reproduce: Standard A projects onto 2855 K at Duv ≈ 0, D65 onto ≈6505 K at
 * +0.0033 Duv (the published offset of daylight above the locus).
 */
internal fun planckianUv(kelvin: Double): DoubleArray {
    val t = kelvin.coerceIn(1000.0, 15000.0)
    val u = (0.860117757 + 1.54118254e-4 * t + 1.28641212e-7 * t * t) /
        (1.0 + 8.42420235e-4 * t + 7.08145163e-7 * t * t)
    val v = (0.317398726 + 4.22806245e-5 * t + 4.20481691e-8 * t * t) /
        (1.0 - 2.89741816e-5 * t + 1.61456053e-7 * t * t)
    return doubleArrayOf(u, v)
}

/**
 * Chromaticity (x, y) of the white point [target]: the Planckian point at its Kelvin, moved
 * perpendicular to the locus by tint × 0.0002 Duv. Positive Duv lies ABOVE the locus (greener
 * light), which the camera neutralizes by lowering green — so + tint renders magenta, matching both
 * the app's −50 green … +50 magenta slider and the PMA110 CCT measurement (c5600t40 lifted R and B
 * against G; t−40 lowered them).
 */
internal fun wbTargetXy(target: WbTarget): DoubleArray {
    val k = target.kelvin.toDouble().coerceIn(1000.0, 15000.0)
    val p = planckianUv(k)
    // Locus tangent by central difference; the normal is the tangent turned toward +v.
    val a = planckianUv(k - 1.0)
    val b = planckianUv(k + 1.0)
    var nu = -(b[1] - a[1])
    var nv = b[0] - a[0]
    if (nv < 0) { nu = -nu; nv = -nv }
    val len = hypot(nu, nv)
    val duv = target.tint.coerceIn(-WB_TINT_LIMIT, WB_TINT_LIMIT) * DUV_PER_TINT_UNIT
    val u = p[0] + duv * nu / len
    val v = p[1] + duv * nv / len
    val d = 2.0 * u - 8.0 * v + 4.0
    return doubleArrayOf(3.0 * u / d, 2.0 * v / d)
}

private fun xyToXyz(xy: DoubleArray): DoubleArray =
    doubleArrayOf(xy[0] / xy[1], 1.0, (1.0 - xy[0] - xy[1]) / xy[1])

/**
 * COLOR_CORRECTION_GAINS `[R, G_even, G_odd, B]` that render [target] neutral on the sensor
 * [calibration] describes: the camera-space neutral of the target's XYZ through the interpolated
 * ColorMatrix, inverted. Without a calibration, linear sRGB stands in for camera RGB — directionally
 * right, nothing more; such a route takes the HAL's own CCT mode instead when it advertises one
 * ([wbKelvinPath]). Normalized so the smallest gain is 1.0 (Camera2 advises against gains below 1,
 * "color artifacts") and each is capped at [MAX_WB_CHANNEL_GAIN].
 *
 * PMA110 cross-check (camera 2): 3200 K → 1.60 / 2.34 and 5600 K → 2.26 / 1.54 against the HAL's own
 * CCT table's 1.54 / 2.60 and 2.25 / 1.68 — the same law, the HAL ~10 % bluer at both ends.
 */
internal fun wbTargetGains(calibration: ColorCalibration?, target: WbTarget): FloatArray {
    val xyz = xyToXyz(wbTargetXy(target))
    val toCamera = calibration?.xyzToCamera(target.kelvin.toDouble()) ?: XYZ_TO_LINEAR_SRGB
    val neutral = RawDevelopMath.mulVec(toCamera, xyz)
    if (neutral.any { !it.isFinite() || it <= 1e-6 }) return floatArrayOf(1f, 1f, 1f, 1f)
    return normalizedWbGains(neutral[1] / neutral[0], 1.0, neutral[1] / neutral[2])
}

/** `[R, G, G, B]` with the smallest channel at 1.0 and none above [MAX_WB_CHANNEL_GAIN]. */
internal fun normalizedWbGains(r: Double, g: Double, b: Double): FloatArray {
    val floor = minOf(r, g, b).coerceAtLeast(1e-6)
    val cap = MAX_WB_CHANNEL_GAIN.toDouble()
    fun n(v: Double) = (v / floor).coerceIn(1.0, cap).toFloat()
    return floatArrayOf(n(r), n(g), n(g), n(b))
}

/**
 * Camera RGB (after [gains]) → linear sRGB for [target] from the calibration alone: the DNG
 * "no ForwardMatrix" path, CA × inverse(CC × CM) with CA the Bradford adaptation from the target
 * white to D65, rows normalized so the gained neutral stays (1, 1, 1). Only a FALLBACK for
 * [WbWire.Matrix]: the route's own transform from its AUTO frames is what the request carries
 * whenever one is known, because routes render different gamuts (see the file header). Identity
 * without a calibration.
 */
internal fun calibratedWbTransform(calibration: ColorCalibration?, target: WbTarget, gains: FloatArray): DoubleArray {
    if (calibration == null) return RawDevelopMath.IDENTITY.copyOf()
    return runCatching {
        val white = xyToXyz(wbTargetXy(target))
        val cameraToXyz = RawDevelopMath.invert(calibration.xyzToCamera(target.kelvin.toDouble()))
        val adapted = RawDevelopMath.multiply(RawDevelopMath.bradford(white, D65_WHITE), cameraToXyz)
        val ungain = doubleArrayOf(
            1.0 / gains[0], 0.0, 0.0,
            0.0, 1.0 / gains[1], 0.0,
            0.0, 0.0, 1.0 / gains[3],
        )
        val m = RawDevelopMath.multiply(RawDevelopMath.multiply(XYZ_TO_LINEAR_SRGB, adapted), ungain)
        DoubleArray(9) { i ->
            val r = i / 3
            m[i] / (m[r * 3] + m[r * 3 + 1] + m[r * 3 + 2])
        }.takeIf { t -> t.all { it.isFinite() } } ?: RawDevelopMath.IDENTITY.copyOf()
    }.getOrDefault(RawDevelopMath.IDENTITY.copyOf())
}

/**
 * The WB facts of one route, Android-type-free so the selection is host-testable. Defaults describe a
 * device that predates the fields: no CCT, no calibration, presets honoured.
 */
internal data class WbCapability(
    val awbModes: IntArray = IntArray(0),
    val manualPostProcessing: Boolean = false,
    /** COLOR_CORRECTION_AVAILABLE_MODES (API 36+); null when unreadable (below 36, or absent). */
    val colorCorrectionModes: IntArray? = null,
    /** COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE (API 36+); both null when absent. */
    val cctMin: Int? = null,
    val cctMax: Int? = null,
    /** The route's sensor has a usable ColorMatrix calibration ([ColorCalibration]). */
    val calibrated: Boolean = false,
    /** False where a measured HAL disregards CONTROL_AWB_MODE presets ([DeviceProfile.awbPresetModesIgnored]). */
    val presetsHonoured: Boolean = true,
) {
    private fun has(mode: Int) = exactAdvertisedMode(mode, awbModes) != null

    val awbOffAvailable: Boolean get() = has(CameraMetadata.CONTROL_AWB_MODE_OFF)

    /**
     * App-owned gains + transform. Below API 36 Camera2 guarantees TRANSFORM_MATRIX wherever the
     * colorCorrection keys exist (MANUAL_POST_PROCESSING); from 36 the advertised list decides.
     */
    val transformMatrixAvailable: Boolean
        get() = manualPostProcessing && awbOffAvailable &&
            (colorCorrectionModes?.contains(CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX) ?: true)

    val cctAvailable: Boolean
        get() = awbOffAvailable && colorCorrectionModes?.contains(COLOR_CORRECTION_MODE_CCT_VALUE) == true &&
            cctMin != null && cctMax != null && cctMin <= cctMax

    override fun equals(other: Any?): Boolean = other is WbCapability &&
        awbModes.contentEquals(other.awbModes) && manualPostProcessing == other.manualPostProcessing &&
        colorCorrectionModes.contentEquals(other.colorCorrectionModes) && cctMin == other.cctMin &&
        cctMax == other.cctMax && calibrated == other.calibrated && presetsHonoured == other.presetsHonoured

    override fun hashCode(): Int = awbModes.contentHashCode() * 31 + calibrated.hashCode()
}

/** How a Kelvin + tint target reaches the request on a route. */
internal enum class WbKelvinPath {
    /** TRANSFORM_MATRIX, gains from the sensor's ColorMatrix calibration. */
    MATRIX_CALIBRATED,
    /** COLOR_CORRECTION_MODE_CCT: the HAL's own Kelvin table (no calibration to do better with). */
    CCT,
    /** TRANSFORM_MATRIX with uncalibrated (linear-sRGB stand-in) gains: neither of the above. */
    MATRIX_APPROXIMATE,
    NONE,
}

/**
 * The Kelvin path, by capability alone. A calibration beats CCT mode because CCT mode was measured to
 * drop the colour transform on PMA110 (header table) and Camera2 does not promise one; CCT beats
 * uncalibrated gains because the HAL's table knows its own sensor. No model string decides this.
 */
internal fun wbKelvinPath(cap: WbCapability): WbKelvinPath = when {
    cap.transformMatrixAvailable && cap.calibrated -> WbKelvinPath.MATRIX_CALIBRATED
    cap.cctAvailable -> WbKelvinPath.CCT
    cap.transformMatrixAvailable -> WbKelvinPath.MATRIX_APPROXIMATE
    else -> WbKelvinPath.NONE
}

/** Whether [mode] can actually be applied on the route; the ONE answer for normalization and UI. */
internal fun wbModeAvailable(mode: WbMode, cap: WbCapability, hasCustomGains: Boolean): Boolean = when (mode) {
    WbMode.AUTO -> exactAdvertisedMode(CameraMetadata.CONTROL_AWB_MODE_AUTO, cap.awbModes) != null
    WbMode.MANUAL -> wbKelvinPath(cap) != WbKelvinPath.NONE
    // Measured gains need app-owned gains: CCT mode has no gains key.
    WbMode.CUSTOM -> hasCustomGains && cap.transformMatrixAvailable
    WbMode.INCANDESCENT, WbMode.FLUORESCENT, WbMode.DAYLIGHT, WbMode.CLOUDY, WbMode.SHADE ->
        if (cap.presetsHonoured) {
            exactAdvertisedMode(mode.awbMetadata, cap.awbModes) != null
        } else {
            wbKelvinPath(cap) != WbKelvinPath.NONE
        }
}

/**
 * The Kelvin a route can actually take for manual WB: the CCT path clamps to the advertised
 * COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE (Camera2 would clamp anyway — the UI must show the value
 * that lands); every other path keeps the requested value.
 */
internal fun normalizedWbKelvin(kelvin: Int, cap: WbCapability): Int =
    if (wbKelvinPath(cap) == WbKelvinPath.CCT && cap.cctMin != null && cap.cctMax != null) {
        kelvin.coerceIn(cap.cctMin, cap.cctMax)
    } else {
        kelvin
    }

/** Exactly the WB keys one request carries. */
internal sealed interface WbWire {
    /** True when the request turns the HAL's AWB OFF (the app owns the white point). */
    val awbOff: Boolean get() = this is Matrix || this is Cct || this is MakerCct

    /** The route advertises no AWB mode at all: write no WB key. */
    data object Omit : WbWire
    data class Auto(val awbMode: Int, val lock: Boolean) : WbWire
    data class Preset(val awbMode: Int) : WbWire
    data class Cct(val kelvin: Int, val tint: Int) : WbWire
    /** Confirmed maker session only: FAST colour processing, OEM Kelvin table and native Tone. */
    data class MakerCct(val kelvin: Int, val tone: Int) : WbWire
    /** AWB OFF + TRANSFORM_MATRIX + [gains] `[R, G_even, G_odd, B]` + row-major [transform]. */
    class Matrix(val gains: FloatArray, val transform: DoubleArray) : WbWire {
        override fun equals(other: Any?): Boolean =
            other is Matrix && gains.contentEquals(other.gains) && transform.contentEquals(other.transform)
        override fun hashCode(): Int = gains.contentHashCode() * 31 + transform.contentHashCode()
        override fun toString(): String = "Matrix(gains=${gains.toList()}, transform=${transform.toList()})"
    }
    /**
     * A matrix WB is wanted but the route's own colour transform has never been seen: run AWB AUTO
     * until its first converged frame supplies one ([WbReferenceStore]), then rebuild. Writes AUTO.
     */
    data class Prime(val awbMode: Int) : WbWire
    /**
     * A matrix WB is wanted on a route that honours presets and was never swept: run the preset
     * [awbMode] until its frames report the HAL's own transform at that white point, then the next
     * ([wbSweepPresets]); the matrix follows once the last one is learned. Writes the preset.
     */
    data class Sweep(val awbMode: Int) : WbWire
}

/** The CONTROL_AWB_MODE a request carrying [this] writes (OFF for an app-owned white point); null for none. */
internal fun WbWire.awbMode(): Int? = when (this) {
    WbWire.Omit -> null
    is WbWire.Auto -> awbMode
    is WbWire.Preset -> awbMode
    is WbWire.Prime -> awbMode
    is WbWire.Sweep -> awbMode
    is WbWire.Cct, is WbWire.MakerCct, is WbWire.Matrix -> CameraMetadata.CONTROL_AWB_MODE_OFF
}

/**
 * Resolves the WB keys for an already-NORMALIZED packet ([ManualControls.normalizedFor]) on a route.
 * [routeTransform] is the route's own colour transform at the packet's white point
 * ([WbReferenceStore.lookup], [wbTransformKelvin]; null = never seen); [transformRequired] is false
 * only where no processed pixel depends on it (a RAW-only session, whose developer takes just the
 * gains), so priming or sweeping would buy nothing there. [sweepPreset] is the preset a pending
 * sweep runs next ([wbSweepWanted]); null on every still request, which never sweeps.
 */
internal fun resolveWbWire(
    c: ManualControls,
    cap: WbCapability,
    calibration: ColorCalibration?,
    routeTransform: DoubleArray?,
    transformRequired: Boolean,
    makerLog: Boolean = false,
    sweepPreset: Int? = null,
): WbWire {
    val auto = exactAdvertisedMode(CameraMetadata.CONTROL_AWB_MODE_AUTO, cap.awbModes)
    fun autoWire(): WbWire = auto?.let { WbWire.Auto(it, false) } ?: WbWire.Omit
    fun matrix(gains: FloatArray, target: WbTarget?): WbWire = when {
        sweepPreset != null && transformRequired -> WbWire.Sweep(sweepPreset)
        routeTransform != null -> WbWire.Matrix(gains, routeTransform.copyOf())
        !transformRequired -> WbWire.Matrix(
            gains,
            target?.let { calibratedWbTransform(calibration, it, gains) } ?: RawDevelopMath.IDENTITY.copyOf(),
        )
        auto != null -> WbWire.Prime(auto)
        // No AUTO to learn from: the calibrated transform is the best remaining answer.
        else -> WbWire.Matrix(
            gains,
            target?.let { calibratedWbTransform(calibration, it, gains) } ?: RawDevelopMath.IDENTITY.copyOf(),
        )
    }
    fun kelvin(target: WbTarget): WbWire = if (makerLog) {
        WbWire.MakerCct(target.kelvin, target.tint.coerceIn(-WB_TINT_LIMIT, WB_TINT_LIMIT))
    } else when (wbKelvinPath(cap)) {
        WbKelvinPath.MATRIX_CALIBRATED -> matrix(wbTargetGains(calibration, target), target)
        WbKelvinPath.MATRIX_APPROXIMATE -> matrix(wbTargetGains(null, target), target)
        WbKelvinPath.CCT -> WbWire.Cct(
            normalizedWbKelvin(target.kelvin, cap),
            target.tint.coerceIn(-WB_TINT_LIMIT, WB_TINT_LIMIT),
        )
        WbKelvinPath.NONE -> autoWire()
    }
    return when (val mode = c.wbMode) {
        WbMode.AUTO -> auto?.let { WbWire.Auto(it, c.awbLock) } ?: WbWire.Omit
        WbMode.MANUAL -> kelvin(WbTarget(c.wbKelvin, c.wbTint))
        WbMode.CUSTOM -> {
            val g = c.customWbGains
            if (g != null && cap.transformMatrixAvailable) {
                matrix(floatArrayOf(g.r, g.gEven, g.gOdd, g.b), null)
            } else {
                autoWire()
            }
        }
        WbMode.INCANDESCENT, WbMode.FLUORESCENT, WbMode.DAYLIGHT, WbMode.CLOUDY, WbMode.SHADE -> {
            val preset = exactAdvertisedMode(mode.awbMetadata, cap.awbModes)
            if (cap.presetsHonoured && preset != null) {
                WbWire.Preset(preset)
            } else {
                wbPresetTarget(mode)?.let(::kelvin) ?: autoWire()
            }
        }
    }
}

/**
 * Whether the normalized packet [c] puts an app-owned white balance (AWB OFF) on the route — the
 * projection-side twin of [WbWire.awbOff], before any priming detour: MANUAL and CUSTOM always do,
 * a preset only where the HAL ignores presets and the Kelvin path carries it.
 */
internal fun wbAppOwned(c: ManualControls, cap: WbCapability): Boolean = when (c.wbMode) {
    WbMode.AUTO -> false
    WbMode.MANUAL, WbMode.CUSTOM -> true
    WbMode.INCANDESCENT, WbMode.FLUORESCENT, WbMode.DAYLIGHT, WbMode.CLOUDY, WbMode.SHADE ->
        !(cap.presetsHonoured && exactAdvertisedMode(c.wbMode.awbMetadata, cap.awbModes) != null)
}

/**
 * The session shape a route's colour transform belongs to: each renders its own gamut. MAKER_LOG is
 * OPPO's measured maker session; VIVO_MAKER_LOG the experimental, unmeasured vivo one — its own key,
 * so a transform learned under vivo's (unknown) Log gamut never replays on another shape, or back.
 */
// OEM_8K: the 0x80A9 8K graph (OemEightKVideo.kt) runs the full OEM pipeline, whose transform is
// not assumed equal to the ordinary SDR session's.
internal enum class WbSessionShape { SDR, HLG10, VENDOR_TC, MAKER_LOG, RAW_LOG, VIVO_MAKER_LOG, OEM_8K }

/**
 * Identity of one route's colour transform: the camera opened, the session shape, and — on a logical
 * multi-camera — the physical sensor that produced the frame (PMA110's maker session reports a
 * different AUTO transform on the 1× sensor and on the 3× it zooms onto, 2026-10-27).
 */
internal data class WbRouteKey(val cameraId: String, val shape: WbSessionShape, val physicalId: String?) {
    fun encode(): String = "$cameraId|${shape.name}|${physicalId ?: "-"}"

    companion object {
        fun decode(raw: String): WbRouteKey? {
            val p = raw.split('|')
            if (p.size != 3 || p[0].isEmpty()) return null
            val shape = WbSessionShape.entries.firstOrNull { it.name == p[1] } ?: return null
            return WbRouteKey(p[0], shape, p[2].takeIf { it != "-" && it.isNotEmpty() })
        }
    }
}

/** A plausible COLOR_CORRECTION_TRANSFORM: nine finite values, rows summing near 1, not all zero. */
internal fun plausibleWbTransform(t: DoubleArray?): Boolean {
    if (t == null || t.size != 9 || t.any { !it.isFinite() || abs(it) > 8.0 }) return false
    return (0 until 3).all { r -> abs(t[r * 3] + t[r * 3 + 1] + t[r * 3 + 2] - 1.0) < 0.25 }
}

/**
 * One learned colour transform of a route and the white point its frame was balanced for, as a
 * correlated colour temperature read off the frame's own gains through the sensor calibration
 * ([wbFrameKelvin]); null where the route has no calibration to read it with.
 */
internal class WbReferenceSample(val kelvin: Double?, val transform: DoubleArray)

/**
 * Every route's own colour transforms, learned from its AWB-AUTO frames (and, where the HAL honours
 * them, its preset frames) and replayed under manual WB (TRANSFORM_MATRIX needs one; Camera2 itself
 * documents that replaying a FAST/HIGH_QUALITY frame's gains and transform reproduces its white
 * point). Process-wide, shared by successive controllers, seeded from and mirrored to storage by the
 * engine so a relaunch in manual WB needs no priming.
 *
 * A route keeps SEVERAL transforms, one per white point it was seen at, because a HAL's colour
 * transform moves with the illuminant: the Galaxy Z Flip6 reports a blue row of −0.20 / −1.27 /
 * +2.47 for Incandescent, −0.10 / −1.13 / +2.23 for AUTO in a 3200 K room and +0.04 / −0.75 /
 * +1.71 for Daylight (2026-10-27, FIELD_CHECKS A23). Replaying the room's transform under 5000 K
 * gains drove blue to the floor (grey patches 127 / 81 / 7 against the Daylight preset's
 * 125 / 89 / 23 at nearly the same gains). [lookup] with a Kelvin therefore blends the two learned
 * transforms around it, linearly in mired like the DNG ColorMatrix pair: on the Flip6 that predicts
 * Daylight from Incandescent + Cloudy and AUTO from Incandescent + Daylight within 0.07 per element.
 * Outside the learned range the nearest transform stands; with none carrying a Kelvin, the latest.
 *
 * Lookup is EXACT per route: another sensor's transform is not "close" — PMA110's maker session
 * reports [1.125 −0.188 0.063 …] on the 1× sensor and [0.984 0.094 −0.078 …] on the 3× it zooms
 * onto — so a key never seen primes once (per route, per sensor, per install) instead of borrowing
 * a neighbour's. [latestKey] only GUESSES which sensor a logical camera will start on, so a reopen
 * on the sensor the operator left it on needs no rebuild. [isSwept] records that a route's honoured
 * presets were collected once ([wbSweepPresets]), so it never sweeps again.
 */
internal class WbReferenceStore {
    private class Entry(val kelvin: Double?, val transform: DoubleArray, val order: Long)

    private val entries = HashMap<WbRouteKey, List<Entry>>()
    private val swept = HashSet<WbRouteKey>()
    private val sequence = java.util.concurrent.atomic.AtomicLong()

    /** Called (from the offering thread) after a NEW or materially changed transform is stored. */
    @Volatile var onChanged: ((WbReferenceSnapshot) -> Unit)? = null

    /** The most recently learned transform of [key], whatever its white point. */
    @Synchronized
    fun lookup(key: WbRouteKey): DoubleArray? = entries[key]?.maxByOrNull { it.order }?.transform?.copyOf()

    /**
     * The transform for a white point of [kelvin] on [key]: blended between the learned transforms
     * around it (mired), the nearest outside their range, or [lookup]'s latest when no learned
     * transform carries a Kelvin or [kelvin] is null.
     */
    @Synchronized
    fun lookup(key: WbRouteKey, kelvin: Double?): DoubleArray? {
        val list = entries[key] ?: return null
        val known = list.filter { it.kelvin != null }
        if (kelvin == null || !kelvin.isFinite() || kelvin <= 0.0 || known.isEmpty()) {
            return list.maxByOrNull { it.order }?.transform?.copyOf()
        }
        return interpolateWbTransform(known.map { it.kelvin!! to it.transform }, kelvin)
    }

    /** The most recently learned key of [cameraId] in [shape] — on a logical camera, its last sensor. */
    @Synchronized
    fun latestKey(cameraId: String, shape: WbSessionShape): WbRouteKey? = entries.entries
        .filter { it.key.cameraId == cameraId && it.key.shape == shape }
        .maxByOrNull { e -> e.value.maxOf { it.order } }
        ?.key

    /**
     * Stores [transform] for [key] at the white point [kelvin]. A sample within
     * [WB_REFERENCE_MERGE_MIRED] of a stored one replaces it (AWB settles a few mired either side of
     * one light); a Kelvin-less sample replaces the Kelvin-less one. At most
     * [WB_REFERENCE_MAX_SAMPLES] Kelvin samples per route ([capped]). Returns true when the
     * white point is new or its transform moved by more than [WB_REFERENCE_EPSILON] in any element —
     * the only case that persists or should rebuild a request.
     */
    fun offer(key: WbRouteKey, transform: DoubleArray, kelvin: Double? = null): Boolean {
        if (!plausibleWbTransform(transform)) return false
        val k = kelvin?.takeIf { it.isFinite() && it > 0.0 }
        val changed = synchronized(this) {
            val list = entries[key].orEmpty()
            val same = list.firstOrNull { e -> sameWhitePoint(e.kelvin, k) }
            val moved = same == null ||
                (0 until 9).any { abs(same.transform[it] - transform[it]) > WB_REFERENCE_EPSILON }
            entries[key] = capped(list.filter { it !== same } + Entry(k, transform.copyOf(), sequence.incrementAndGet()))
            moved
        }
        if (changed) onChanged?.invoke(snapshot())
        return changed
    }

    /**
     * [list] with at most [WB_REFERENCE_MAX_SAMPLES] Kelvin samples: each surplus one is the OLDER of
     * the two white points closest in mired, so the range survives — a sweep's Incandescent and
     * Cloudy anchors are never pushed out by AUTO learning filling the middle (review 2026-10-27).
     */
    private fun capped(list: List<Entry>): List<Entry> {
        var next = list
        while (next.count { it.kelvin != null } > WB_REFERENCE_MAX_SAMPLES) {
            val byMired = next.filter { it.kelvin != null }.sortedBy { 1e6 / it.kelvin!! }
            val (a, b) = byMired.zipWithNext().minBy { (a, b) -> 1e6 / b.kelvin!! - 1e6 / a.kelvin!! }
            val victim = if (a.order < b.order) a else b
            next = next.filter { it !== victim }
        }
        return next
    }

    /** Whether [key]'s honoured presets were already collected ([markSwept]). */
    @Synchronized
    fun isSwept(key: WbRouteKey): Boolean = key in swept

    /** Records that [key]'s preset sweep finished; persisted with the transforms. */
    fun markSwept(key: WbRouteKey) {
        val added = synchronized(this) { swept.add(key) }
        if (added) onChanged?.invoke(snapshot())
    }

    /** Every route's samples NEWEST FIRST (what [seed] expects back), and the swept routes. */
    @Synchronized
    fun snapshot(): WbReferenceSnapshot = WbReferenceSnapshot(
        samples = entries.mapValues { (_, list) ->
            list.sortedByDescending { it.order }.map { WbReferenceSample(it.kelvin, it.transform.copyOf()) }
        },
        swept = swept.toSet(),
    )

    /**
     * Adds persisted transforms without notifying and without displacing anything learned in this
     * process (storage loads off the main thread and may land after the first session learned): a
     * persisted white point joins a route only where no learned sample already holds it. Seeded
     * entries rank older than every learned one and keep their persisted order (newest first).
     */
    @Synchronized
    fun seed(persisted: WbReferenceSnapshot) {
        persisted.samples.forEach { (k, samples) ->
            val have = entries[k].orEmpty()
            val added = samples.filter { s ->
                plausibleWbTransform(s.transform) && have.none { e -> sameWhitePoint(e.kelvin, s.kelvin) }
            }.map { Entry(it.kelvin, it.transform.copyOf(), -sequence.incrementAndGet()) }
            if (added.isNotEmpty()) entries[k] = capped(have + added)
        }
        swept.addAll(persisted.swept)
    }

    private fun sameWhitePoint(a: Double?, b: Double?): Boolean =
        if (a == null || b == null) a == b else abs(1e6 / a - 1e6 / b) < WB_REFERENCE_MERGE_MIRED

    /** [seed] for Kelvin-less transforms (the pre-2026-10-27 store, and host tests). */
    fun seed(persisted: Map<WbRouteKey, DoubleArray>) =
        seed(WbReferenceSnapshot(persisted.mapValues { listOf(WbReferenceSample(null, it.value)) }, emptySet()))

    companion object {
        /** Below the HAL's own 1/128 transform quantization: smaller moves are AWB noise. */
        const val WB_REFERENCE_EPSILON = 0.01

        /**
         * One white point per this many mired: ~±90 K at 3000 K, ~±250 K at 5000 K — wider than an
         * AUTO frame's settling jitter, narrower than the Flip6's preset spacing (Incandescent
         * 2873 K / Daylight 5162 K / Cloudy 6313 K / Shade ≈ 7500 K are ≥ 25 mired apart).
         */
        const val WB_REFERENCE_MERGE_MIRED = 10.0

        /** Kelvin samples kept per route; the oldest goes first. */
        const val WB_REFERENCE_MAX_SAMPLES = 12
    }
}

/** What [WbReferenceStore] persists: every route's samples and the routes already swept. */
internal data class WbReferenceSnapshot(
    val samples: Map<WbRouteKey, List<WbReferenceSample>>,
    val swept: Set<WbRouteKey>,
)

/**
 * The transform at [kelvin] from Kelvin-tagged [samples]: linear in mired between the two around it
 * (rows that each sum to 1 still sum to 1), the nearest one's outside their range.
 */
internal fun interpolateWbTransform(samples: List<Pair<Double, DoubleArray>>, kelvin: Double): DoubleArray {
    val byMired = samples.sortedBy { 1e6 / it.first }
    val mired = 1e6 / kelvin
    val lo = byMired.lastOrNull { 1e6 / it.first <= mired }
    val hi = byMired.firstOrNull { 1e6 / it.first >= mired }
    if (lo == null) return byMired.first().second.copyOf()
    if (hi == null || hi === lo) return lo.second.copyOf()
    val mLo = 1e6 / lo.first
    val mHi = 1e6 / hi.first
    val w = if (mHi - mLo < 1e-9) 0.0 else (mired - mLo) / (mHi - mLo)
    return DoubleArray(9) { i -> lo.second[i] * (1.0 - w) + hi.second[i] * w }
}

/**
 * The white point a frame's COLOR_CORRECTION_GAINS ([r], [g], [b]) balance for, found the DNG way
 * through [calibration] ([RawDevelopMath.neutralCct]); null without a calibration, or when the
 * gains cannot be read as a white point.
 */
internal fun wbFrameKelvin(calibration: ColorCalibration?, r: Double, g: Double, b: Double): Double? {
    if (calibration == null || !(r > 0.0 && g > 0.0 && b > 0.0)) return null
    return runCatching {
        RawDevelopMath.neutralCct(
            doubleArrayOf(r, g, b),
            calibration.colorMatrix1,
            calibration.colorMatrix2,
            calibration.calibration1,
            calibration.calibration2,
            calibration.cct1,
            calibration.cct2,
        )
    }.getOrNull()?.takeIf { it.isFinite() && it > 0.0 }
}

/**
 * The white point whose transform a matrix wire for [c] should replay: the manual Kelvin, an
 * ignored preset's target, or the custom gains' own white point. Null when the route has no
 * calibration (its learned transforms carry no Kelvin either) or the packet owns no white point.
 */
internal fun wbTransformKelvin(c: ManualControls, cap: WbCapability, calibration: ColorCalibration?): Double? {
    if (calibration == null) return null
    return when (c.wbMode) {
        WbMode.MANUAL -> normalizedWbKelvin(c.wbKelvin, cap).toDouble()
        WbMode.CUSTOM -> c.customWbGains?.let { g ->
            wbFrameKelvin(calibration, g.r.toDouble(), (g.gEven + g.gOdd) / 2.0, g.b.toDouble())
        }
        WbMode.INCANDESCENT, WbMode.FLUORESCENT, WbMode.DAYLIGHT, WbMode.CLOUDY, WbMode.SHADE ->
            if (wbAppOwned(c, cap)) wbPresetTarget(c.wbMode)?.kelvin?.toDouble() else null
        WbMode.AUTO -> null
    }
}

/**
 * The presets a route sweeps once to learn the HAL's own transform at each white point: the
 * along-the-locus ones (fluorescent presets carry a tint the Kelvin read ignores), in the order a
 * HAL that honours them is advertised to apply. Empty where presets are ignored — PMA110 renders
 * every preset with the AUTO gains, so there is nothing to learn.
 */
internal fun wbSweepPresets(cap: WbCapability): List<Int> =
    if (!cap.presetsHonoured) {
        emptyList()
    } else {
        listOf(
            CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT,
            CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
            CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
            CameraMetadata.CONTROL_AWB_MODE_SHADE,
        ).filter { exactAdvertisedMode(it, cap.awbModes) != null }
    }

/**
 * Whether the repeating request for [c] should sweep the route's presets first: the packet wants an
 * app-owned white point (manual Kelvin or custom gains), the HAL honours at least two locus presets,
 * a calibration can read their white points, processed pixels depend on the transform, and the
 * route has not been swept before.
 */
internal fun wbSweepWanted(
    c: ManualControls,
    cap: WbCapability,
    calibrated: Boolean,
    alreadySwept: Boolean,
    transformRequired: Boolean,
): Boolean = !alreadySwept && calibrated && transformRequired &&
    (c.wbMode == WbMode.MANUAL || c.wbMode == WbMode.CUSTOM) && wbAppOwned(c, cap) &&
    wbSweepPresets(cap).size >= 2
