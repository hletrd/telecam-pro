package me.hletrd.findx9tele.focus

import me.hletrd.findx9tele.camera.AfIndication
import me.hletrd.findx9tele.camera.FocusConfidenceSource
import me.hletrd.findx9tele.camera.FocusMode
import me.hletrd.findx9tele.camera.FrameDetail
import me.hletrd.findx9tele.camera.LensChoice
import me.hletrd.findx9tele.camera.LensExifMetadata
import kotlin.math.abs

/**
 * Focus-confidence detection: the OSD's one honest statement about whether the viewfinder is
 * resolving the subject. TWO independent proofs feed one tag, one hold, one OSD slot.
 *
 * 1. [macroTooCloseCandidate] (cycle 8, AF_LIMIT) — AF gave a non-FOCUSED verdict while the lens
 *    sat racked near its close limit (LENS_FOCUS_DISTANCE ≥ ~85% of
 *    LENS_INFO_MINIMUM_FOCUS_DISTANCE). That really does prove the subject is inside the minimum
 *    focus distance, so it may say TOO CLOSE and may advise a closer-focusing lens.
 * 2. [frameDefocusCandidate] (cycle 9, FRAME_DETAIL) — the app's own pixels resolved no fine
 *    detail (gl/FocusDetail.kt). Proves only "unresolved", so it says SOFT and advises nothing.
 *
 * WHY BOTH (device-measured 2026-07-25, PMA110): with a subject ~9 cm away on the TELE route
 * (advertised minimum focus 120 cm) the preview is completely defocused, yet the HAL reports
 * `afState = FOCUSED_LOCKED` with `LENS_FOCUS_DISTANCE = 0.0068` diopters — racked to INFINITY.
 * It neither admits failure nor racks near, so proof 1 is structurally unreachable on this device.
 * Proof 1 is nonetheless KEPT: it is a strictly stronger claim and remains live on routes whose AF
 * is honest (this device's other rear lenses advertise 6.67 dpt / 15 cm, one ultrawide 25 dpt /
 * 4 cm), and it would be a bad trade to delete a strong proof because one lens lies.
 *
 * HONESTY: both are deliberately conservative — they MAY MISS, they must NEVER false-fire. A
 * FOCUSED verdict on a featureless surface correctly shows no tag; so does a frame the detail
 * metric cannot judge. Per UX_POLICY this is one compact amber tag in the OSD row, in the same
 * register as AEL/AFL/LOUPE — never a banner, chip, or coach mark.
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

/** Newest frame-detail statistics older than this cannot speak for the live preview. */
internal const val FOCUS_DETAIL_MAX_AGE_MS = 1_000L

/**
 * Multiple of the handheld-rule shutter above which an analysed frame's own motion blur becomes
 * indistinguishable from defocus, so the detail proof is refused.
 *
 * Conservative on purpose. The handheld rule targets roughly one circle of confusion (~3 px at 4K)
 * while the detail metric's floor is ~200 sensor px, so physics only makes the two ambiguous around
 * ~50x — refusing at 16x refuses about 3x earlier than required. It lands where it matters:
 * PREVIEW_FLUIDITY_MAX_EXPOSURE_NS caps the finder at 1/15 s, and 16x the 3.33 ms rule at 300 mm is
 * ~53 ms, so the detector is refused across exactly the dark-preview regime where a hand-held
 * 300 mm frame is smeared anyway (and where the metric's own noise suppression has already given
 * up).
 */
internal const val FOCUS_DETAIL_MAX_SHUTTER_FACTOR = 16L

/**
 * The instantaneous FRAME_DETAIL signal, before the hold. [FrameDetail.SOFT] is necessary and
 * nowhere near sufficient; every gate below removes a state in which unresolved detail has a
 * legitimate cause other than "the viewfinder is not resolving the subject".
 *
 * @param detail newest frame verdict, null before the first analysis frame.
 * @param detailAgeMs age of that verdict. Covers Not-Ready, a paused/dead GL generation, and a
 *   preview that simply stopped delivering real frames — all of which freeze [detail] at its last
 *   value with nothing else to notice.
 * @param exposureNs the exposure the ANALYSED FRAME actually rode, i.e. result metadata
 *   (`CameraUiState.liveExposureNs`), NOT `controls.effectiveExposureNs()`. previewExposureTrade
 *   caps the preview at 1/15 s while the intended STILL exposure can be seconds; gating on intent
 *   would wrongly refuse the detector whenever a long still was dialled in.
 */
internal fun frameDefocusCandidate(
    detail: FrameDetail?,
    detailAgeMs: Long,
    focusMode: FocusMode,
    afIndication: AfIndication,
    recording: Boolean,
    recordingStarting: Boolean,
    zoomInteracting: Boolean,
    exposureNs: Long?,
    handheldShutterNs: Long,
): Boolean {
    // Only SOFT may arm. UNJUDGEABLE is NOT weak evidence — it is the metric declining to speak.
    if (detail != FrameDetail.SOFT) return false
    if (detailAgeMs < 0L || detailAgeMs > FOCUS_DETAIL_MAX_AGE_MS) return false
    // MANUAL focus: the user owns the distance, and deliberate defocus is a creative state.
    // AUTO/CONTINUOUS/MACRO all admit — a MACRO mode that still cannot resolve is worth saying.
    if (focusMode == FocusMode.MANUAL) return false
    // A lens mid-sweep is defocused BY DESIGN. Note this INVERTS the AF_LIMIT predicate, which
    // treats persistent scanning as evidence. Because a refusal also resets the hold, the 700 ms
    // window only begins after the scan ends — which doubles as the mechanical/ISP settle wait,
    // so there is no second timer to get wrong and the tag cannot blink through every AF hunt.
    if (afIndication == AfIndication.SCANNING) return false
    // The OSD must not grow an element mid-take, and panning during REC is exactly when the
    // motion-blur ambiguity is worst. ARMED video (neither flag) admits — that is where it helps.
    if (recording || recordingStarting) return false
    // Each throttled HAL zoom submit gaps this stream ~180 ms and the HAL re-converges afterwards,
    // producing genuinely soft REAL frames that say nothing about the subject distance.
    if (zoomInteracting) return false
    val exposure = exposureNs ?: return false
    if (exposure <= 0L) return false
    return exposure <= handheldShutterNs.coerceAtLeast(1L) * FOCUS_DETAIL_MAX_SHUTTER_FACTOR
}

/**
 * The two proofs, OR-ed, with AF_LIMIT winning: it establishes strictly more (an actual distance
 * relation), so when both hold the stronger wording is the truthful one.
 */
internal fun focusConfidenceCandidate(
    afLimit: Boolean,
    frameDetail: Boolean,
): FocusConfidenceSource? = when {
    afLimit -> FocusConfidenceSource.AF_LIMIT
    frameDetail -> FocusConfidenceSource.FRAME_DETAIL
    else -> null
}

/**
 * Debounce/hold for the candidate signal: the source is published only after it has persisted
 * [holdMs] (injected clock — host-testable), and cleared the instant it goes away. Single-thread
 * confined (main).
 *
 * Latches a VALUE, not a boolean: a change of source re-arms from zero, so an interval that only
 * ever proved AF_LIMIT cannot donate its elapsed time to a later FRAME_DETAIL interval (they are
 * different claims and would print different text).
 */
internal class FocusConfidenceHold(private val holdMs: Long = MACRO_HOLD_MS) {
    private var latched: FocusConfidenceSource? = null
    private var sinceMs: Long = 0L

    fun update(candidate: FocusConfidenceSource?, nowMs: Long): FocusConfidenceSource? {
        if (candidate == null) {
            latched = null
            return null
        }
        if (latched != candidate) {
            latched = candidate
            sinceMs = nowMs
            return null
        }
        return if (nowMs - sinceMs >= holdMs) candidate else null
    }

    /** True while a candidate is pending but not yet shown — the caller schedules a re-check. */
    fun pending(nowMs: Long): Boolean = latched != null && nowMs - sinceMs < holdMs

    /** Drops the latch outright: used at optics-generation doors, where the evidence is stale. */
    fun reset() {
        latched = null
    }
}

/**
 * The OSD text for a held source. Each proof gets exactly the wording it can defend:
 *
 * - AF_LIMIT proved a distance relation, so `TOO CLOSE`, and it may carry the `→ <lens>` suffix
 *   naming a genuinely closer-focusing lens.
 * - FRAME_DETAIL proved only that the frame resolves nothing fine. `SOFT` is true in EVERY state
 *   that path can reach — gross defocus (too close, or focus racked wrong), a soft subject, haze,
 *   a fogged converter, isotropic shake that survived the exposure gate — and it is Sony-native
 *   vocabulary. It deliberately takes NO lens suffix: `→ 1×` is a distance remedy, and recommending
 *   it would smuggle back the causal claim the metric cannot make. `DEFOCUS`/`NO FOCUS` are
 *   rejected for the same reason; `TOO CLOSE` here would simply be false in the haze/shake cases.
 *
 * The separator is U+2192 RIGHTWARDS ARROW, not the U+25B8 small triangle this shipped with: the
 * app bundles three Inter faces (res/font) and NONE of them carries U+25B8, so that glyph fell back
 * to a system typeface mid-string — a different face, weight, and metrics inside one OSD tag. The
 * arrow is in all three faces and already carries exactly this meaning elsewhere in the app's own
 * copy (MediaReview's "→<scale>" zoom button = "go to this"), so `TOO CLOSE → 1×` reads as
 * "too close; switch to 1×", which is the remedy the suffix exists to name.
 */
internal fun focusConfidenceLabel(
    source: FocusConfidenceSource?,
    closerLensLabel: String?,
): String? = when (source) {
    null -> null
    FocusConfidenceSource.FRAME_DETAIL -> "SOFT"
    FocusConfidenceSource.AF_LIMIT -> closerLensLabel?.let { "TOO CLOSE → $it" } ?: "TOO CLOSE"
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
