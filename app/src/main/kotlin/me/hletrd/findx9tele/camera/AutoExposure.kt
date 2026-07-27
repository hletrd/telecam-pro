package me.hletrd.findx9tele.camera

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

    // Log-domain proportional gain: each tick moves GAIN of the measured error, so it converges in
    // a few ticks (~0.5 s at 6 Hz) without overshoot. The deadband holds the value once we're
    // within ~1/12 stop so a noisy meter doesn't jitter ISO/shutter forever.
    //
    // The per-tick clamp is ERROR-SCHEDULED (cycle 8): near the target it keeps the tuned
    // 0.30-stop ceiling (user: 1-stop ticks read as visible steps — steady-state smoothness is
    // sacred), but for a big scene change (lens cap, window pan) it opens up to 0.5×|error| capped
    // at 1.2 stops, so a 5-stop error converges in ~9 ticks instead of ~17. Behavior is IDENTICAL
    // to the old fixed clamp for errors ≤ 0.6 stop (0.5×0.6 = the same 0.30 ceiling), and steps
    // stay strictly below the remaining error, so the approach still cannot overshoot. This
    // matters most in the dark, where the cycle-8 fluidity cap raised the meter cadence from
    // ~0.4 Hz (500 ms frames) to ~3 Hz — fast ticks × scheduled steps is what makes low-light AE
    // feel immediate.
    private const val GAIN = 0.6f
    private const val MAX_STEP_STOPS = 0.30f
    private const val MAX_FAR_STEP_STOPS = 1.20f
    private const val FAR_STEP_SLOPE = 0.5f
    private const val DEADBAND_STOPS = 0.05f

    /** The per-tick step ceiling for a given error magnitude (stops) — the schedule above. */
    internal fun maxStepStops(errorMagnitudeStops: Float): Float =
        (FAR_STEP_SLOPE * errorMagnitudeStops).coerceIn(MAX_STEP_STOPS, MAX_FAR_STEP_STOPS)

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
     * EV-shifted target, scaled by [GAIN] and clamped to the error-scheduled ±[maxStepStops].
     * Positive = brighten (raise ISO / lengthen shutter). Returns null inside the deadband
     * (converged → no update).
     */
    internal fun correctionStops(mean: Float, evCompStops: Float): Float? {
        // Pitch-black meter: the error is unbounded (log of ~0), so open up at the far cap — the
        // schedule's own answer for an arbitrarily large error.
        if (mean <= 0f) return MAX_FAR_STEP_STOPS
        val target = (TARGET_LUMA * pow2(evCompStops).toFloat()).coerceIn(0.02f, 0.95f)
        val errorStops = log2(target / mean)
        if (kotlin.math.abs(errorStops) < DEADBAND_STOPS) return null
        val cap = maxStepStops(kotlin.math.abs(errorStops))
        return (errorStops * GAIN).coerceIn(-cap, cap)
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
        // coerceAtLeast guards the degenerate case of an exposure FLOOR above the ceiling (or an
        // inverted range) — coerceIn(min, max) throws when min > max, and both coerceIn calls below
        // use (expMinNs, slowCapNs). Mirrors the guard in manualAebExposuresNs.
        val slowCapNs = minOf(expMaxNs, 100_000_000L).coerceAtLeast(expMinNs)
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

    /**
     * The exposure factor that preserves the exposure value across an aperture change, i.e. the
     * number the incoming route's exposure (or ISO) must be multiplied by so the same scene lands
     * at the same brightness: `(N_in / N_out)²`.
     *
     * Scene luminance is the invariant across a lens switch — the light in the room does not change
     * when the HAL routes to different glass — so holding EV is the physically right transfer:
     * `N_out² / (t_out · S_out) == N_in² / (t_in · S_in)`.
     *
     * Null when either f-number is missing, non-positive, or non-finite: a route that does not
     * report `LENS_INFO_AVAILABLE_APERTURES` gives no basis for a transfer, and inventing one
     * would seed a wrong exposure where today's cold start at least starts from a known value.
     */
    internal fun apertureTransferFactor(outgoingApertureF: Float, incomingApertureF: Float): Double? {
        if (!outgoingApertureF.isFinite() || outgoingApertureF <= 0f) return null
        if (!incomingApertureF.isFinite() || incomingApertureF <= 0f) return null
        val ratio = incomingApertureF.toDouble() / outgoingApertureF.toDouble()
        return ratio * ratio
    }

    /**
     * Which sensor axis an aperture transfer may move on [exposureMode] — always the one the
     * app-side loop already owns, never one the photographer set. A lens switch that silently
     * rewrote a dialled-in shutter or ISO would be a worse bug than the swing it fixes.
     *
     * [angleDerivedShutter] is [ShutterMode.ANGLE], where [ManualControls.effectiveExposureNs]
     * computes the exposure from the cine angle and fps and IGNORES `exposureTimeNs`. Writing that
     * field there changes nothing on the wire, so time is not a usable carrier: PROGRAM (which owns
     * both axes) falls back to ISO, and ISO-priority (where ISO is the user's) has no carrier left.
     */
    internal fun seedCarrier(
        exposureMode: ExposureMode,
        angleDerivedShutter: Boolean,
    ): ExposureSeedCarrier? = when (exposureMode) {
        // MANUAL is user-owned on BOTH axes — the same contract as swapping glass on a real body.
        ExposureMode.MANUAL -> null
        // SHUTTER priority: the user fixed the time, the loop drives ISO.
        ExposureMode.SHUTTER -> ExposureSeedCarrier.ISO
        // ISO priority: the user fixed ISO, the loop drives the time.
        ExposureMode.ISO -> if (angleDerivedShutter) null else ExposureSeedCarrier.EXPOSURE_TIME
        // PROGRAM: the loop owns both. Prefer TIME and hold ISO — the simplest, most predictable
        // rule, and the one the request framed. It is also the one with headroom on this device:
        // the ISO ceiling is a couple of stops away in the dark, while the exposure range runs to
        // the 4 s HAL-safe ceiling, so a +2.3-stop transfer (f/1.58 main → f/3.5 10×) fits in time
        // and would clamp in ISO. [driveProgram] then re-centres the shutter toward the handheld
        // rule over the next ticks, counter-moving ISO brightness-neutrally, so the redistribution
        // is invisible — only the total brightness had to be right at the switch, and it is.
        ExposureMode.PROGRAM ->
            if (angleDerivedShutter) ExposureSeedCarrier.ISO else ExposureSeedCarrier.EXPOSURE_TIME
    }

    /**
     * The exposure to START the incoming route's app-side loop from, carried across a lens change
     * at constant exposure value.
     *
     * This is a PRIOR, not an answer. Different lenses see different fields, so the metered scene
     * genuinely differs — a 0.6× ultrawide and a 10× tele can want different exposures for the same
     * room, and the loop still has to converge. What the transfer removes is the part of the error
     * that is pure optics: on this device the rear f-numbers span f/1.58 to f/3.50, so an
     * unseeded switch starts up to ~2.3 stops off and visibly swings bright or dark before
     * settling. Seeded, it starts within the framing difference.
     *
     * Returns null when no transfer is possible — an unusable f-number on either side, or an
     * exposure mode with no loop-owned axis to move (see [seedCarrier]) — so the caller leaves the
     * exposure exactly as it is today rather than seeding a wrong value. Degenerate current values
     * (non-positive ISO or exposure) are refused for the same reason.
     *
     * The result is clamped into the INCOMING route's advertised ranges; a transfer that would run
     * past them lands on the bound and the loop finishes the remainder. It deliberately does NOT
     * spill the clamped remainder onto the other axis: in the priority modes that axis is the
     * user's, and in PROGRAM the loop redistributes anyway. An inverted/degenerate advertised range
     * clamps nothing (mirrors [clampExposureNs]) rather than throwing out of `coerceIn`.
     */
    internal fun seedForApertureChange(
        exposureMode: ExposureMode,
        angleDerivedShutter: Boolean,
        iso: Int,
        exposureTimeNs: Long,
        outgoingApertureF: Float,
        incomingApertureF: Float,
        isoMin: Int,
        isoMax: Int,
        expMinNs: Long,
        expMaxNs: Long,
    ): SeededExposure? {
        val carrier = seedCarrier(exposureMode, angleDerivedShutter) ?: return null
        val transfer = apertureTransferFactor(outgoingApertureF, incomingApertureF) ?: return null
        if (iso <= 0 || exposureTimeNs <= 0L) return null
        return when (carrier) {
            // roundToLong/roundToInt saturate at the type bound for an out-of-range Double, so a
            // pathological f-number pair overflows into the clamp instead of wrapping negative.
            ExposureSeedCarrier.EXPOSURE_TIME -> SeededExposure(
                carrier = carrier,
                iso = iso,
                exposureTimeNs = clampInRange((exposureTimeNs * transfer).roundToLong(), expMinNs, expMaxNs),
            )
            ExposureSeedCarrier.ISO -> SeededExposure(
                carrier = carrier,
                iso = clampInRange((iso * transfer).roundToInt(), isoMin, isoMax),
                exposureTimeNs = exposureTimeNs,
            )
        }
    }

    private fun clampInRange(value: Long, min: Long, max: Long): Long =
        if (min <= max) value.coerceIn(min, max) else value

    private fun clampInRange(value: Int, min: Int, max: Int): Int =
        if (min <= max) value.coerceIn(min, max) else value

    private fun pow2(x: Float): Double = Math.pow(2.0, x.toDouble())
    private fun log2(x: Float): Float = (ln(x.toDouble()) / ln(2.0)).toFloat()
}

/** Which sensor axis an exposure transfer may move — the one the app-side AE loop owns. */
internal enum class ExposureSeedCarrier { EXPOSURE_TIME, ISO }

/**
 * The sensor pair to start the incoming route from, plus which axis the transfer actually moved.
 * The carrier is part of the result so callers write back only the moved field: the untouched one
 * is echoed for convenience and must not overwrite a user-owned value (in ANGLE shutter mode the
 * echoed `exposureTimeNs` is the angle-derived exposure, not the stored field).
 */
internal data class SeededExposure(
    val carrier: ExposureSeedCarrier,
    val iso: Int,
    val exposureTimeNs: Long,
)
