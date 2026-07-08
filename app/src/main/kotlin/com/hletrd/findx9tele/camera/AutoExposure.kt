package com.hletrd.findx9tele.camera

import android.util.Range
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * App-side auto-exposure for the SHUTTER- and ISO-priority modes.
 *
 * Camera2 has no native shutter- or ISO-priority: `CONTROL_AE_MODE_ON` owns BOTH ISO and shutter,
 * and `CONTROL_AE_MODE_OFF` owns neither. So for a priority mode we run AE OFF (the user fixes one
 * variable) and close the loop ourselves — metering off the preview luma the GL pipeline already
 * computes for the scopes (a 256-bin luma histogram, ~6×/s), and nudging the FREE variable toward a
 * mid-grey target each tick.
 *
 * Pure and stateless: [driveIso]/[driveShutterNs] take the current value and return the next one (or
 * null when the change is within the deadband — no update, so the repeating request isn't rebuilt for
 * nothing). A log-domain proportional step with a per-tick clamp gives a fast but non-hunting
 * approach; the deadband stops micro-oscillation once converged.
 */
object AutoExposure {

    /**
     * Target mean luma on the display-referred preview (0..1). ~0.45 lands a mid-tone scene around the
     * classic middle-grey without clipping — the same "expose the average to the middle" a matrix meter
     * aims for. EV compensation shifts this target up/down in stops.
     */
    const val TARGET_LUMA = 0.45f

    // Log-domain proportional gain: each tick moves GAIN of the measured error, so it converges in a
    // few ticks (~0.5 s at 6 Hz) without overshoot. The per-tick clamp bounds a huge scene change to
    // one stop per tick so a lens cap on/off ramps smoothly instead of snapping. The deadband holds
    // the value once we're within ~1/12 stop so a noisy meter doesn't jitter ISO/shutter forever.
    private const val GAIN = 0.5f
    private const val MAX_STEP_STOPS = 1.0f
    private const val DEADBAND_STOPS = 0.08f

    /** Mean luma (0..1) of a 256-bin luma histogram. Returns 0 for an empty/degenerate histogram. */
    fun meanLuma(luma: IntArray): Float {
        var weighted = 0.0
        var total = 0L
        for (i in luma.indices) {
            weighted += i.toDouble() * luma[i]
            total += luma[i]
        }
        if (total == 0L) return 0f
        return (weighted / total / 255.0).toFloat()
    }

    /**
     * The correction in stops to apply this tick: how far (log2) the measured [mean] is from the
     * EV-shifted target, scaled by [GAIN] and clamped to ±[MAX_STEP_STOPS]. Positive = brighten (raise
     * ISO / lengthen shutter). Returns null inside the deadband (converged → no update).
     */
    internal fun correctionStops(mean: Float, evCompStops: Float): Float? {
        if (mean <= 0f) return MAX_STEP_STOPS // pitch black meter → open up hard
        val target = (TARGET_LUMA * pow2(evCompStops).toFloat()).coerceIn(0.02f, 0.95f)
        val errorStops = log2(target / mean)
        if (kotlin.math.abs(errorStops) < DEADBAND_STOPS) return null
        return (errorStops * GAIN).coerceIn(-MAX_STEP_STOPS, MAX_STEP_STOPS)
    }

    /** SHUTTER priority: next ISO to hit the target at the fixed shutter, or null if converged. */
    fun driveIso(luma: IntArray, currentIso: Int, isoRange: Range<Int>?, evCompStops: Float): Int? {
        val range = isoRange ?: return null
        val stops = correctionStops(meanLuma(luma), evCompStops) ?: return null
        val next = (currentIso * pow2(stops)).roundToInt().coerceIn(range.lower, range.upper)
        return if (next == currentIso) null else next
    }

    /** ISO priority: next exposure time (ns) to hit the target at the fixed ISO, or null if converged. */
    fun driveShutterNs(luma: IntArray, currentNs: Long, expRange: Range<Long>?, evCompStops: Float): Long? {
        val range = expRange ?: return null
        val stops = correctionStops(meanLuma(luma), evCompStops) ?: return null
        val next = (currentNs * pow2(stops)).roundToLong().coerceIn(range.lower, range.upper)
        return if (next == currentNs) null else next
    }

    private fun pow2(x: Float): Double = Math.pow(2.0, x.toDouble())
    private fun log2(x: Float): Float = (ln(x.toDouble()) / ln(2.0)).toFloat()
}
