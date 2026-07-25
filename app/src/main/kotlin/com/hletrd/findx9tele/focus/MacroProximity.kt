package com.hletrd.findx9tele.focus

import com.hletrd.findx9tele.camera.AfIndication
import com.hletrd.findx9tele.camera.FocusMode
import com.hletrd.findx9tele.camera.LensChoice
import com.hletrd.findx9tele.camera.LensExifMetadata
import kotlin.math.abs

/**
 * Macro too-close detection (cycle 8). Camera2 exposes no "subject inside minimum focus distance"
 * signal, so this is a heuristic: AF has given a non-FOCUSED verdict (FAILED, or still SCANNING —
 * a genuine too-close subject often keeps CONTINUOUS AF hunting forever instead of failing) while
 * the lens sits racked near its close limit (LENS_FOCUS_DISTANCE ≥ ~85% of
 * LENS_INFO_MINIMUM_FOCUS_DISTANCE). Both are already live in CameraUiState; the hold below turns
 * the flickery instantaneous signal into a stable OSD tag per UX_POLICY (quiet viewfinder — a
 * compact "TOO CLOSE" tag in the OSD row, never a banner/toast).
 *
 * HONESTY (device-observed 2026-07-25): the heuristic is deliberately conservative — it can MISS,
 * it must never false-fire. This HAL can false-lock FOCUSED on a featureless surface at point
 * blank (contrast AF has nothing to judge), and a FOCUSED verdict correctly shows no tag; the tag
 * only appears when AF itself admits it cannot resolve the subject.
 */

/** Fraction of the min-focus diopter limit the lens must exceed to count as "racked near it". */
internal const val MACRO_NEAR_LIMIT_RATIO = 0.85f

/** How long the raw candidate must persist before the tag shows (and AF hunting stops flickering it). */
internal const val MACRO_HOLD_MS = 700L

/** Closer-focus margin: a hinted lens must focus at least this factor nearer (diopters). */
internal const val MACRO_HINT_MIN_ADVANTAGE = 1.2f

/**
 * The instantaneous too-close signal, before the hold. MANUAL focus is excluded — the user owns
 * the distance there and the AF verdict is meaningless; a zero/unknown min-focus (fixed-focus
 * route) can never be "near its limit".
 */
internal fun macroTooCloseCandidate(
    afIndication: AfIndication,
    focusMode: FocusMode,
    liveFocusDiopters: Float?,
    minFocusDiopters: Float,
): Boolean =
    focusMode != FocusMode.MANUAL &&
        minFocusDiopters > 0f &&
        liveFocusDiopters != null &&
        liveFocusDiopters >= minFocusDiopters * MACRO_NEAR_LIMIT_RATIO &&
        (afIndication == AfIndication.FAILED || afIndication == AfIndication.SCANNING)

/**
 * Debounce/hold for the candidate signal: TRUE only after the candidate has persisted [holdMs]
 * (injected clock — host-testable), FALSE the instant it clears. Single-thread confined (main).
 */
internal class MacroProximityHold(private val holdMs: Long = MACRO_HOLD_MS) {
    private var candidateSinceMs: Long? = null

    fun update(candidate: Boolean, nowMs: Long): Boolean {
        if (!candidate) {
            candidateSinceMs = null
            return false
        }
        val since = candidateSinceMs ?: nowMs.also { candidateSinceMs = it }
        return nowMs - since >= holdMs
    }

    /** True while a candidate is pending but not yet shown — the caller schedules a re-check. */
    fun pending(nowMs: Long): Boolean = candidateSinceMs?.let { nowMs - it < holdMs } == true
}

/**
 * The rear lens to hint when the active one cannot focus this close: among [candidates] (the
 * engine's per-lens metadata cache), the WIDER lens (shorter equivalent focal — a longer one would
 * be odd advice for a too-close subject) that focuses at least [MACRO_HINT_MIN_ADVANTAGE]× nearer,
 * preferring the longest focal that qualifies (least framing change). Null when nothing genuinely
 * closer exists — the tag then shows without a hint rather than inventing one.
 */
internal fun closerLensHint(
    activeEquivFocalMm: Float,
    activeMinFocusDiopters: Float,
    candidates: Collection<LensExifMetadata>,
): LensExifMetadata? {
    if (activeEquivFocalMm <= 0f) return null
    return candidates
        .filter {
            it.equivalentFocalMm > 0f && it.equivalentFocalMm < activeEquivFocalMm &&
                it.minFocusDiopters > 0f &&
                it.minFocusDiopters >= activeMinFocusDiopters * MACRO_HINT_MIN_ADVANTAGE
        }
        .maxByOrNull { it.equivalentFocalMm }
}

/** The user-facing lens-preset label nearest an equivalent focal (e.g. 23 mm → "1×"). */
internal fun lensLabelForEquivFocal(equivMm: Float): String? =
    LensChoice.entries.minByOrNull { abs(it.targetEquivMm - equivMm) }?.label
