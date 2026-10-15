package me.hletrd.telecampro.camera

import android.hardware.camera2.CameraMetadata
import me.hletrd.telecampro.gl.LogProfiles
import kotlin.math.pow

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
 *  - Highlight latitude, by itself. The curve's input is the ISP's linear signal with 1.0 = clip, so
 *    at the default Log EI offset 0 sensor white lands at S-Log3 code ≈ 0.596 ([LogProfiles.slog3Oetf]
 *    of 1.0) — exactly where Sony's curve puts linear 1.0. Headroom above that exists ONLY when the
 *    operator buys it with the Log EI offset below (underexpose N stops, lift in the curve), which
 *    is exactly what a real S-Log3 camera's base-ISO rating does — and it costs N stops of shadow
 *    noise, it is not free latitude.
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
 * primaries, and the GL chain owns the S-Gamut3/S-Gamut3.Cine/AWG3/BT.2020 matrix exactly as before.
 *
 * Every native-log transfer (S-Log3, S-Log3.Cine, LogC3, O-Log2) programs THIS curve: it is a transport
 * encoding the shader decodes back to linear ([LogProfiles.slog3Inverse]) before applying the
 * selected profile, so switching between them needs no session reopen.
 *
 * [eiFactor] is the ACHIEVED Log EI offset factor k (see below): Pout = slog3(k · Pin), Pin still
 * exactly 0..1, so the decode slog3Inverse(code) returns scene-linear k·x — the inverse needs no k
 * at all, and k only bounds its domain ([0, k], the GL `uSourceLogHeadroom`). k = 1 (the default)
 * returns the historical curve bit for bit.
 */
internal fun nativeLogToneCurve(maxCurvePoints: Int, eiFactor: Double = 1.0): FloatArray {
    val n = NATIVE_LOG_CURVE_POINTS.coerceAtMost(maxCurvePoints).coerceAtLeast(2)
    // Log EI offset (see [nativeLogEiSensorWire]): the wire was underexposed by [eiFactor], so ISP
    // linear x is scene-linear k·x and the curve encodes slog3(k·x). k = 1 is the historical curve
    // BIT FOR BIT: every k-term below is a multiply/divide by exactly 1.0, which IEEE leaves exact.
    val k = eiFactor.coerceIn(1.0, NATIVE_LOG_EI_MAX_FACTOR)
    val y0 = LogProfiles.slog3Oetf(0.0)
    val y1 = LogProfiles.slog3Oetf(k)
    // S-Log3 is continuous but KINKED at its linear/log cut (slope 6.6 below, 5.2 above, in code per
    // linear unit), so an interval straddling it is the curve's worst interpolation error (~1.4e-4,
    // measured by NativeLogCurveTest). The sample nearest the cut is moved ONTO it: the kink becomes
    // a control point, the count is unchanged, and the neighbours stay at least half a step away.
    // Under an EI offset the kink sits at ISP input SLOG3_CUT / k (scene SLOG3_CUT), same rule.
    val step = (y1 - y0) / (n - 1)
    val cutIndex = ((LogProfiles.SLOG3_CUT_CODE / LogProfiles.SLOG3_CODE_SCALE - y0) / step)
        .let { kotlin.math.round(it).toInt() }
        .coerceIn(1, n - 2)
    val out = FloatArray(n * 2)
    for (i in 0 until n) {
        val x = when (i) {
            0 -> 0.0
            n - 1 -> 1.0
            cutIndex -> LogProfiles.SLOG3_CUT / k
            else -> LogProfiles.slog3Inverse(y0 + step * i) / k
        }
        out[2 * i] = x.toFloat()
        out[2 * i + 1] = LogProfiles.slog3Oetf(x * k).toFloat()
    }
    return out
}

/*
 * Log EI offset (owner request 2026-10-15): highlight headroom for NATIVE log video.
 *
 * Offset N stops (k = 2^N) underexposes the REPEATING request by k and lifts it back inside the log
 * curve: the ISP linear signal x (1.0 = sensor clip) is encoded as slog3(k·x), so scene-linear
 * k·x reaches up to k before clipping — highlights that clipped at N = 0 keep up to N more stops.
 * The GL decode (Shaders `nativeSceneLinear`) returns that scene-linear value UNCLAMPED to the log
 * encode branches, while the display/meter rendition stays clamped at scene 1.0, so the preview,
 * zebra, false color and the app-side AE meter keep their normal brightness and the AE loop does not
 * chase the offset.
 *
 * ACHIEVED, not requested: every wire clamp (ISO floor, minimum exposure, the advertised EV-comp
 * range) can deliver less than k, and the curve and the GL headroom both take the factor the wire
 * actually carries — encoding slog3(k·x) over an exposure that was only cut by k/2 would put grey a
 * stop high. Costs, stated plainly: N stops more shadow noise (the sensor sees less light), and the
 * stream is still the 8-bit session with an 8-bit encoder target, so the extra stops share the same
 * code budget. Stills and the in-REC snapshot never take the offset (built with
 * previewExposureCap = false and the stock tone map). UNVERIFIED on device (FIELD_CHECKS A17).
 */

/** The largest offset the setting offers; slog3(2^3 = 8) ≈ 0.83 stays inside S-Log3's code range. */
internal const val LOG_EI_OFFSET_MAX_STOPS = 3

/** Ceiling for any achieved factor fed to the curve/GL: 2^[LOG_EI_OFFSET_MAX_STOPS]. */
internal const val NATIVE_LOG_EI_MAX_FACTOR = 8.0

/** The persisted/offered domain: 0..[LOG_EI_OFFSET_MAX_STOPS] whole stops, 0 = historical behaviour. */
internal fun normalizedLogEiOffsetStops(stops: Int): Int = stops.coerceIn(0, LOG_EI_OFFSET_MAX_STOPS)

/** k = 2^N for a normalized offset. */
internal fun logEiTargetFactor(stops: Int): Double =
    (1 shl normalizedLogEiOffsetStops(stops)).toDouble()

/**
 * What one repeating request actually applied for the EI offset. [isoFactor] and [exposureFactor]
 * are intended ÷ wire (each ≥ 1); [factor] is their product (or the EV-comp offset under HAL AE)
 * and is what the tone curve and the GL headroom must use. [NONE] (all 1.0) on every request that
 * takes no offset — stills, non-native sessions, N = 0.
 */
internal data class NativeLogEiApplied(
    val factor: Double,
    val isoFactor: Double,
    val exposureFactor: Double,
) {
    companion object {
        val NONE = NativeLogEiApplied(1.0, 1.0, 1.0)
    }
}

/** AE-OFF wire values for the offset, from [nativeLogEiSensorWire]. */
internal data class NativeLogEiSensorWire(
    val iso: Int,
    val exposureNs: Long,
    val applied: NativeLogEiApplied,
)

/**
 * AE-OFF (S / ISO / M, app-side or manual): the offset is taken from ISO first — ISO ÷ k, rounded UP
 * so the ISO share never exceeds k — and only the part the ISO floor refused comes out of exposure
 * time, always SHORTER, never longer (a longer frame would break the video cadence and the
 * repeating-request exposure caps). The shutter therefore stays the operator's whenever ISO has room;
 * at base ISO the offset shortens it (less motion blur — stated in the doc, not hidden). Inputs are
 * the FINAL wire values the N = 0 request would carry (after the preview trade, range clamps and
 * frame timing), so the factor is measured against exactly what the offset changes.
 */
internal fun nativeLogEiSensorWire(
    offsetStops: Int,
    iso: Int,
    isoMin: Int,
    exposureNs: Long,
    exposureMinNs: Long,
): NativeLogEiSensorWire {
    val k = logEiTargetFactor(offsetStops)
    if (k <= 1.0 || iso <= 0 || exposureNs <= 0L) {
        return NativeLogEiSensorWire(iso, exposureNs, NativeLogEiApplied.NONE)
    }
    val floor = isoMin.coerceAtLeast(1)
    val idealIso = kotlin.math.ceil(iso / k).toInt()
    val wireIso = maxOf(idealIso, floor).coerceAtMost(iso)
    val isoFactor = iso.toDouble() / wireIso
    var wireExposureNs = exposureNs
    if (idealIso < floor) {
        // Only the share the ISO floor refused moves to the shutter.
        val remainder = k / isoFactor
        if (remainder > 1.0) {
            // Rounded UP like the ISO share, so the product never overshoots k.
            wireExposureNs = kotlin.math.ceil(exposureNs / remainder).toLong()
                .coerceAtLeast(exposureMinNs.coerceAtLeast(1L))
                .coerceAtMost(exposureNs)
        }
    }
    val exposureFactor = exposureNs.toDouble() / wireExposureNs
    return NativeLogEiSensorWire(
        iso = wireIso,
        exposureNs = wireExposureNs,
        applied = NativeLogEiApplied(isoFactor * exposureFactor, isoFactor, exposureFactor),
    )
}

/** HAL-AE wire value for the offset, from [nativeLogEiCompensation]. */
internal data class NativeLogEiCompensation(
    val wireIndex: Int,
    val applied: NativeLogEiApplied,
)

/**
 * HAL AE (video P, AE ON): CONTROL_AE_EXPOSURE_COMPENSATION = the operator's EV index minus N stops
 * in the advertised step, clamped to the advertised range. The achieved offset is the index distance
 * that survived the clamp — with the operator already at −2 EV of a ±2 EV range, an N = 2 request
 * achieves 0 and the curve stays the historical one. The HAL AE then picks HOW (ISO or shutter);
 * the live readout attributes the whole factor to ISO, the EI convention. Whether the HAL reaches
 * the compensated target in every scene (bright-scene minimum exposure) is a field question.
 */
internal fun nativeLogEiCompensation(
    offsetStops: Int,
    userIndex: Int,
    evStepStops: Double,
    evMin: Int,
    evMax: Int,
): NativeLogEiCompensation {
    val user = userIndex.coerceIn(minOf(evMin, evMax), maxOf(evMin, evMax))
    val stops = normalizedLogEiOffsetStops(offsetStops)
    if (stops == 0 || evStepStops <= 0.0 || evMin >= evMax) {
        return NativeLogEiCompensation(user, NativeLogEiApplied.NONE)
    }
    val steps = kotlin.math.round(stops / evStepStops).toInt()
    val wire = (user - steps).coerceIn(evMin, evMax)
    val achievedStops = (user - wire) * evStepStops
    if (achievedStops <= 0.0) return NativeLogEiCompensation(user, NativeLogEiApplied.NONE)
    val factor = 2.0.pow(achievedStops).coerceIn(1.0, NATIVE_LOG_EI_MAX_FACTOR)
    return NativeLogEiCompensation(wire, NativeLogEiApplied(factor, factor, 1.0))
}

/**
 * The EI-rated live readout: a preview RESULT carries the wire ISO/exposure, which under an offset
 * sit [NativeLogEiApplied.isoFactor] / [NativeLogEiApplied.exposureFactor] below what the operator
 * rates the scene at. The UI's auto-mode chips and the P→M handoff seed read this, so they must see
 * the EI values — a raw wire readout would seed M N stops dark and the offset would then apply again.
 */
internal fun eiRatedReadout(wireIso: Int?, wireExposureNs: Long?, applied: NativeLogEiApplied): Pair<Int?, Long?> =
    Pair(
        wireIso?.let { kotlin.math.round(it * applied.isoFactor).toInt() },
        wireExposureNs?.let { kotlin.math.round(it * applied.exposureFactor).toLong() },
    )

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
