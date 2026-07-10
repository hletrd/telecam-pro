package com.hletrd.findx9tele.camera

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
    // Tuned for VISUALLY SMOOTH transitions (user: 1-stop ticks read as visible steps): smaller
    // per-tick cap with slightly higher gain ≈ the same convergence time in many small slides
    // (~1.8 stops/s at the 6 Hz meter) instead of a few big jumps.
    private const val GAIN = 0.6f
    private const val MAX_STEP_STOPS = 0.30f
    private const val DEADBAND_STOPS = 0.05f

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
        if (mean <= 0f) return MAX_STEP_STOPS // pitch black meter → open up (per-tick cap)
        val target = (TARGET_LUMA * pow2(evCompStops).toFloat()).coerceIn(0.02f, 0.95f)
        val errorStops = log2(target / mean)
        if (kotlin.math.abs(errorStops) < DEADBAND_STOPS) return null
        return (errorStops * GAIN).coerceIn(-MAX_STEP_STOPS, MAX_STEP_STOPS)
    }

    // Bounds are plain Int/Long (not android.util.Range): Range's getters throw "not mocked" on the
    // JVM, which made these drive functions untestable — the same discipline as sensorFrameDurationNs.

    /** SHUTTER priority: next ISO to hit the target at the fixed shutter, or null if converged. */
    fun driveIso(luma: IntArray, currentIso: Int, isoMin: Int, isoMax: Int, evCompStops: Float): Int? {
        val stops = correctionStops(meanLuma(luma), evCompStops) ?: return null
        val next = (currentIso * pow2(stops)).roundToInt().coerceIn(isoMin, isoMax)
        return if (next == currentIso) null else next
    }

    /** ISO priority: next exposure time (ns) to hit the target at the fixed ISO, or null if converged. */
    fun driveShutterNs(luma: IntArray, currentNs: Long, expMinNs: Long, expMaxNs: Long, evCompStops: Float): Long? {
        val stops = correctionStops(meanLuma(luma), evCompStops) ?: return null
        val next = (currentNs * pow2(stops)).roundToLong().coerceIn(expMinNs, expMaxNs)
        return if (next == currentNs) null else next
    }

    /**
     * PROGRAM (photo): next (ISO, exposure ns) on a classic Auto-ISO program line, or null if settled.
     *
     * The line: hold the shutter at [preferredNs] — the handheld-safe 1/(effective focal) rule, the
     * "auto min shutter" every real camera applies in P — and let ISO carry the exposure. Only when
     * ISO clamps does the shutter leave the preferred point: at max ISO in the dark it lengthens (down
     * to a 1/10 s handheld ceiling), at min ISO in the bright it shortens. Per tick the shutter moves
     * at most one stop (no visible exposure snaps), and ISO counter-moves so a shutter re-centering
     * never changes overall brightness.
     */
    fun driveProgram(
        luma: IntArray,
        currentIso: Int,
        currentNs: Long,
        preferredNs: Long,
        isoMin: Int,
        isoMax: Int,
        expMinNs: Long,
        expMaxNs: Long,
        evCompStops: Float,
    ): Pair<Int, Long>? {
        // Handheld ceiling: past ~1/10 s no amount of "P mode" saves the shot; stop trading there.
        val slowCapNs = minOf(expMaxNs, 100_000_000L)
        val pref = preferredNs.coerceIn(expMinNs, slowCapNs)

        val corr = correctionStops(meanLuma(luma), evCompStops) ?: 0f
        // Re-center the shutter toward the preferred point by at most 1 stop this tick…
        val shutterStops = log2(pref.toFloat() / currentNs.toFloat()).coerceIn(-0.35f, 0.35f)
        // …and give ISO the exposure correction minus what the shutter move already contributes
        // (longer shutter = brighter), so re-centering is brightness-neutral.
        val isoStops = corr - shutterStops
        var newNs = (currentNs * pow2(shutterStops)).roundToLong()
        val wantIso = currentIso * pow2(isoStops)
        var newIso = wantIso.roundToInt()
        if (wantIso > isoMax) {
            // Dark scene, ISO exhausted → push the remainder into a slower shutter (≤ handheld cap).
            val overflowStops = log2((wantIso / isoMax).toFloat())
            newIso = isoMax
            newNs = (newNs * pow2(overflowStops)).roundToLong()
        } else if (wantIso < isoMin) {
            // Bright scene at base ISO → shorten the shutter below the preferred point.
            val overflowStops = log2((wantIso / isoMin).toFloat())
            newIso = isoMin
            newNs = (newNs * pow2(overflowStops)).roundToLong()
        }
        newNs = newNs.coerceIn(expMinNs, slowCapNs)
        newIso = newIso.coerceIn(isoMin, isoMax)
        if (newIso == currentIso && newNs == currentNs) return null
        return newIso to newNs
    }

    private fun pow2(x: Float): Double = Math.pow(2.0, x.toDouble())
    private fun log2(x: Float): Float = (ln(x.toDouble()) / ln(2.0)).toFloat()
}
