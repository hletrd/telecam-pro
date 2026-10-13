package me.hletrd.telecampro.camera

import android.hardware.camera2.CameraMetadata
import me.hletrd.telecampro.gl.LogProfiles

/*
 * Native ISP log: the camera's own tone-map stage encodes S-Log3 through a caller-programmed
 * `TONEMAP_MODE_CONTRAST_CURVE`, instead of the GL chain baking a log curve onto the ISP's stock,
 * already tone-mapped (S-curved) stream.
 *
 * Device facts this rests on (PMA110, measured 2026-10-14, one grey wall, video P, standalone
 * camera 4, preview mean luma): stock 64.2 → linear curve 18.1 → S-Log3 curve 79.5 → gamma-2.4
 * curve 83.6 → stock again 64.4, and on the HLG10 session stock 79.7 vs linear 32.4. So the HAL
 * HONOURS a programmed curve on the repeating request in both the 8-bit and the 10-bit session.
 * Every camera there advertises MANUAL_POST_PROCESSING, [CONTRAST_CURVE, FAST, HIGH_QUALITY] and
 * 512 curve points.
 *
 * What this does NOT buy, and must never be described as buying:
 *  - Highlight latitude. The curve's input is the ISP's linear signal with 1.0 = clip, so sensor
 *    white lands at S-Log3 code ≈ 0.596 ([LogProfiles.slog3Oetf] of 1.0) — exactly where Sony's
 *    curve puts linear 1.0. There is no exposure offset (a real S-Log3 camera rates its base ISO so
 *    that clip sits far above 1.0); that is unimplemented.
 *  - Removal of LOCAL tone mapping. The QTI ADRC/LTM stages are not in availableRequestKeys, so no
 *    public key can switch them off; whether they still run under a programmed curve is UNMEASURED.
 *  - Precision. The stream is the 8-bit SDR session's, and S-Log3 spends only ~130 of its 256 codes;
 *    the GL encoder target is 8-bit too. Banding in a hard grade is the expected failure mode.
 *
 * Capability-gated, never model-gated (CLAUDE.md: only `detectPhone` and `DeviceProfile.resolve`
 * may read a model string). A device without the capability keeps the GL-log fallback: HLG10
 * source, GL log curve, stills off.
 */

/** Fewer points than this cannot follow a log curve's shoulder well enough to call it S-Log3. */
internal const val NATIVE_LOG_MIN_CURVE_POINTS = 32

/**
 * Points actually programmed (clamped to the advertised maximum). Spaced uniformly in OUTPUT code,
 * which makes them densest near black in linear input where the log curve bends hardest; at 128
 * (with one point snapped onto the linear/log cut) the piecewise-linear interpolation error is
 * ~2.5e-5 of full scale, about 0.03 of a 10-bit code.
 */
internal const val NATIVE_LOG_CURVE_POINTS = 128

/**
 * Android-free core: whether this camera can carry the native S-Log3 curve. MANUAL_POST_PROCESSING
 * is the capability that makes TONEMAP_CURVE a guaranteed request key; CONTRAST_CURVE must be an
 * advertised mode; and the point budget must be large enough to draw the curve.
 */
internal fun nativeLogCurveSupported(
    supportsManualPostProcessing: Boolean,
    toneMapModes: IntArray,
    maxCurvePoints: Int,
): Boolean = supportsManualPostProcessing &&
    toneMapModes.contains(CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE) &&
    maxCurvePoints >= NATIVE_LOG_MIN_CURVE_POINTS

/** [nativeLogCurveSupported] for a selected route; unknown caps are unsupported (fail to the fallback). */
internal fun nativeLogCurveSupported(caps: CameraCaps?): Boolean = caps != null &&
    nativeLogCurveSupported(caps.supportsManualPostProcessing, caps.toneMapModes, caps.maxCurvePoints)

/**
 * The S-Log3 OETF as TONEMAP_CURVE control points: interleaved (Pin, Pout) pairs, Pin strictly
 * increasing from exactly 0 to exactly 1, Pout = [LogProfiles.slog3Oetf] (Pin). The same array is
 * used for R, G and B — the curve is a per-channel transfer; the gamut stays the ISP's 709
 * primaries, and the GL chain owns the S-Gamut3/S-Gamut3.Cine/AWG3 matrix exactly as before.
 *
 * Every native-log transfer (S-Log3, S-Log3.Cine, LogC3) programs THIS curve: it is a transport
 * encoding the shader decodes back to linear ([LogProfiles.slog3Inverse]) before applying the
 * selected profile, so switching between them needs no session reopen.
 */
internal fun nativeLogToneCurve(maxCurvePoints: Int): FloatArray {
    val n = NATIVE_LOG_CURVE_POINTS.coerceAtMost(maxCurvePoints).coerceAtLeast(2)
    val y0 = LogProfiles.slog3Oetf(0.0)
    val y1 = LogProfiles.slog3Oetf(1.0)
    // S-Log3 is continuous but KINKED at its linear/log cut (slope 6.6 below, 5.2 above, in code per
    // linear unit), so an interval straddling it is the curve's worst interpolation error (~1.4e-4,
    // measured by NativeLogCurveTest). The sample nearest the cut is moved ONTO it: the kink becomes
    // a control point, the count is unchanged, and the neighbours stay at least half a step away.
    val step = (y1 - y0) / (n - 1)
    val cutIndex = ((LogProfiles.SLOG3_CUT_CODE / LogProfiles.SLOG3_CODE_SCALE - y0) / step)
        .let { kotlin.math.round(it).toInt() }
        .coerceIn(1, n - 2)
    val out = FloatArray(n * 2)
    for (i in 0 until n) {
        val x = when (i) {
            0 -> 0.0
            n - 1 -> 1.0
            cutIndex -> LogProfiles.SLOG3_CUT
            else -> LogProfiles.slog3Inverse(y0 + step * i)
        }
        out[2 * i] = x.toFloat()
        out[2 * i + 1] = LogProfiles.slog3Oetf(x).toFloat()
    }
    return out
}

/**
 * The tone-map mode a STILL request must carry. A still never inherits the log curve — it is
 * built from a fresh TEMPLATE_STILL_CAPTURE, never from the repeating builder — but the HAL's
 * template default is not a contract, and a still taken inside a native-log session (the in-REC
 * snapshot) must not come out log-encoded. So in that session it names the stock curve explicitly:
 * HIGH_QUALITY when advertised, else FAST. Outside it (every PHOTO session) null: the request stays
 * byte-identical to the measured PMA110 still path.
 */
internal fun stillToneMapMode(nativeLogSession: Boolean, toneMapModes: IntArray): Int? = when {
    !nativeLogSession -> null
    toneMapModes.contains(CameraMetadata.TONEMAP_MODE_HIGH_QUALITY) -> CameraMetadata.TONEMAP_MODE_HIGH_QUALITY
    toneMapModes.contains(CameraMetadata.TONEMAP_MODE_FAST) -> CameraMetadata.TONEMAP_MODE_FAST
    else -> null
}
