package me.hletrd.findx9tele.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import me.hletrd.findx9tele.camera.AspectRatio
import me.hletrd.findx9tele.camera.AutoExposure
import me.hletrd.findx9tele.camera.CaptureMode
import me.hletrd.findx9tele.camera.ExposureMode
import me.hletrd.findx9tele.camera.FlashMode
import me.hletrd.findx9tele.camera.FnSlot
import me.hletrd.findx9tele.camera.GridType
import me.hletrd.findx9tele.camera.HardwareKeyAction
import me.hletrd.findx9tele.camera.LensChoice
import me.hletrd.findx9tele.camera.ShutterTimer
import me.hletrd.findx9tele.ui.controls.fnSlotLabel
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Non-composable policy helpers for [CameraScreen], hoisted (behavior-locked, verbatim) out of
 * CameraScreen.kt so the pure decision logic lives apart from Compose emission: status urgency and
 * display duration, the spoken forms of the self-timer countdown and the status pill,
 * rotated-layout bounds math, the Fn overlay's orientation-aware layout/copy
 * policy, exposure-meter visibility/needle math, preview letterbox placement, focal-rail and
 * mode-carousel accessibility state, and the glyph-rotation unwrap. Everything here is plain
 * Kotlin over enums/data (host-testable); the composables that consume these stay in
 * CameraScreen.kt.
 */

internal fun String.isUrgentStatus(): Boolean =
    // "could not": the delete-failure statuses ("Could not delete file", "Some files could not be
    // deleted. …") matched no keyword and rendered as polite toasts — found while pinning this
    // classifier (TEST4-14). It is the only keyword those three strings contain, so StatusUrgencyTest
    // pins all of them by their exact shipped wording.
    listOf("error", "fail", "unable", "unavailable", "denied", "insufficient", "could not")
        .any { contains(it, ignoreCase = true) }

/** Keeps successful acknowledgements quiet while leaving actionable failures readable. */
internal fun statusDisplayDurationMs(message: String?): Long? = when {
    message == null -> null
    message.isUrgentStatus() -> 6_000L
    listOf("saved", "deleted", "loaded").any { token -> message.contains(token, ignoreCase = true) } ->
        1_500L
    else -> 2_500L
}

/**
 * The self-timer countdown, spoken. Its node is a 1 Hz `LiveRegionMode.Polite` region, so TalkBack
 * reads this unprompted on EVERY tick — including the last one, where the interpolated plural said
 * "1 seconds remaining". Hoisted because a string built inline inside a semantics block is reachable
 * from no host test at all; both the countdown overlay and the shutter button read it from here so
 * the two can never disagree about the same second.
 */
internal fun timerCountdownDescription(sec: Int): String =
    if (sec == 1) "1 second remaining" else "$sec seconds remaining"

/**
 * The battery + remaining-media pill, spoken as ONE readout. The drawn copy is deliberately
 * telegraphic for a finder — "72%", then "45m" / "9h30m" / a bare shot count — but read aloud "45m"
 * is a distance and "1234" names nothing at all. This spells the same two facts out in words while
 * the pill keeps its short glyphs; [remaining] is the exact token the pill draws, so there is one
 * source of truth for the number and only its wording differs.
 */
internal fun statusInfoDescription(batteryPct: Int, remaining: String?, video: Boolean): String {
    val parts = mutableListOf<String>()
    if (batteryPct >= 0) parts += "Battery $batteryPct percent"
    remaining?.let { token -> remainingMediaDescription(token, video)?.let { parts += it } }
    return parts.joinToString(", ")
}

/** "1 shot" / "2 shots" — a spoken plural must follow the count, unlike the drawn digits. */
private fun countedUnit(value: Long, singular: String): String =
    if (value == 1L) "$value $singular" else "$value ${singular}s"

/**
 * Words for the pill's remaining-media token. Both branches saturate with a trailing "+" ("9h+",
 * "9999+"), which reads as "Over ..." rather than a plus sign. An unparsable token yields null so a
 * future format change degrades to the battery fact alone instead of speaking punctuation.
 */
private fun remainingMediaDescription(token: String, video: Boolean): String? {
    val over = token.endsWith("+")
    val body = token.removeSuffix("+")
    val spoken = if (video) {
        val hourMark = body.indexOf('h')
        val hours = if (hourMark < 0) 0L else body.substring(0, hourMark).toLongOrNull() ?: return null
        val minuteText = (if (hourMark < 0) body else body.substring(hourMark + 1)).removeSuffix("m")
        val minutes = if (minuteText.isEmpty()) 0L else minuteText.toLongOrNull() ?: return null
        when {
            hours > 0 && minutes > 0 -> "${countedUnit(hours, "hour")} ${countedUnit(minutes, "minute")}"
            hours > 0 -> countedUnit(hours, "hour")
            else -> countedUnit(minutes, "minute")
        }
    } else {
        countedUnit(body.toLongOrNull() ?: return null, "shot")
    }
    return if (over) "Over $spoken remaining" else "$spoken remaining"
}

internal data class RotatedLayoutBounds(val widthPx: Int, val heightPx: Int)

/** Exact axis-aligned bounds for a [widthPx] by [heightPx] rectangle rotated around its centre. */
internal fun rotatedLayoutBounds(widthPx: Int, heightPx: Int, degrees: Float): RotatedLayoutBounds {
    require(widthPx >= 0 && heightPx >= 0)
    if (!degrees.isFinite()) return RotatedLayoutBounds(widthPx, heightPx)

    val normalized = ((degrees.toDouble() % 360.0) + 360.0) % 360.0
    val radians = Math.toRadians(normalized)
    fun snapCardinal(value: Double): Double = when {
        value < 1e-7 -> 0.0
        1.0 - value < 1e-7 -> 1.0
        else -> value
    }
    val cosine = snapCardinal(abs(cos(radians)))
    val sine = snapCardinal(abs(sin(radians)))

    fun layoutCeil(value: Double): Int = when {
        value <= 0.0 -> 0
        value >= Int.MAX_VALUE.toDouble() -> Int.MAX_VALUE
        else -> ceil(value).toInt()
    }

    return RotatedLayoutBounds(
        widthPx = layoutCeil(widthPx * cosine + heightPx * sine),
        heightPx = layoutCeil(widthPx * sine + heightPx * cosine),
    )
}

internal fun constrainedRotatedLayoutBounds(
    widthPx: Int,
    heightPx: Int,
    degrees: Float,
    constraints: Constraints,
): RotatedLayoutBounds {
    val bounds = rotatedLayoutBounds(widthPx, heightPx, degrees)
    return RotatedLayoutBounds(
        widthPx = constraints.constrainWidth(bounds.widthPx),
        heightPx = constraints.constrainHeight(bounds.heightPx),
    )
}

/**
 * True once [degrees] is past the 45° crossover, i.e. the rotated child's own width axis now runs
 * mostly along the PARENT'S height. Same |sin| > |cos| crossover [rotatedLayoutBounds] already
 * turns on, so the measure axis flips exactly where the reserved AABB does.
 */
internal fun rotatedMeasureAxisSwapped(degrees: Float): Boolean {
    if (!degrees.isFinite()) return false
    val radians = Math.toRadians(((degrees.toDouble() % 360.0) + 360.0) % 360.0)
    return abs(sin(radians)) > abs(cos(radians))
}

/**
 * The child's own measurement space when its draw is quarter-turned: a rotated `Text`'s glyph run
 * extends along the parent's HEIGHT, but Compose still ellipsizes it against whatever maxWidth it
 * was measured with. Held-landscape Fn tiles are ~42 dp wide and ~58 dp tall, so measuring the
 * rotated value against the tile's WIDTH truncated exactly the readouts (shutter, ISO) the tray
 * exists to show. Swapping is min/max-safe: min<=max holds per axis, so the swapped pair is valid.
 */
internal fun swappedMeasureConstraints(constraints: Constraints): Constraints = Constraints(
    minWidth = constraints.minHeight,
    maxWidth = constraints.maxHeight,
    minHeight = constraints.minWidth,
    maxHeight = constraints.maxWidth,
)

internal fun showHalfPressLabel(
    active: Boolean,
    action: HardwareKeyAction,
    tapFocusHeld: Boolean,
): Boolean = active && !(action == HardwareKeyAction.AF_ON && tapFocusHeld)

/**
 * Whether a top-bar chrome toggle (flash / self-timer / aspect / grid) draws in the current DISP
 * state. Full DISP shows all of them; compact keeps only the ones whose value is NOT the default —
 * UX_POLICY: "compact mode keeps only active or output-changing state plus the Fn entry point."
 *
 * Hoisted because the rule was spelled four times inline and GRID shipped with the second clause
 * MISSING (a bare `!compact`). Grid is the one of the four that paints on the live image, so the
 * preview-first state could carry four 55%-white lines across the frame with no visible control to
 * clear them — and GRID is in neither `compactShootingStatusVisible` nor `FnSlot.PHOTO_DEFAULT` /
 * `MY_MENU_DEFAULT`, so nothing else exposed it either. Four copies of one rule is how a clause goes
 * missing invisibly; one predicate is how it stays pinned.
 */
internal fun chromeToggleVisible(compact: Boolean, isDefault: Boolean): Boolean = !compact || !isDefault

/** Which of the four top-bar chrome toggles draw, from live state. */
internal data class ChromeToggles(
    val flash: Boolean,
    val timer: Boolean,
    val aspect: Boolean,
    val grid: Boolean,
)

/**
 * [chromeToggleVisible] resolved against the live values, so the OTHER half of each rule — which
 * value counts as "default" for that toggle — is pinned here too instead of being retyped at four
 * Compose call sites. One predicate stopped the `!compact` clause going missing; this stops the
 * comparison beside it going wrong, which is the same failure one argument to the left.
 *
 * "Default" means the QUIET value, the one whose control changes no output and paints nothing — not
 * the value the app launches with. GRID is where the two come apart: `CameraUiState.grid` starts at
 * THIRDS, so the grid toggle is deliberately visible in compact at first launch, because the lines
 * are on the live image and this button is the only thing that clears them.
 *
 * [photo] gates the two PHOTO-only toggles: the self-timer and the aspect ratio have no meaning in
 * video, where the frame size is the encoder's.
 */
internal fun chromeToggles(
    compact: Boolean,
    photo: Boolean,
    flash: FlashMode,
    timer: ShutterTimer,
    aspect: AspectRatio,
    grid: GridType,
): ChromeToggles = ChromeToggles(
    flash = chromeToggleVisible(compact, flash == FlashMode.OFF),
    timer = photo && chromeToggleVisible(compact, timer == ShutterTimer.OFF),
    aspect = photo && chromeToggleVisible(compact, aspect == AspectRatio.W4_3),
    grid = chromeToggleVisible(compact, grid == GridType.NONE),
)

internal const val FN_OVERLAY_COLUMN_COUNT = 4
internal const val FN_OVERLAY_HELD_COLUMN_COUNT = 2
internal const val FN_OVERLAY_MAX_SLOTS = 8
internal const val FN_OVERLAY_HELD_WIDTH_DP = 148
internal const val FN_OVERLAY_SCRIM_ALPHA = 0.22f

internal enum class FnOverlayAnchor { BOTTOM_CENTER, CENTER_START, CENTER_END }

/**
 * Raw-window edge for the Fn entry affordance. The activity stays portrait-locked, so a clockwise
 * hold (270 degrees) moves the physical bottom edge to raw end; portrait and a counter-clockwise
 * hold keep it at raw start. The entry and its opened tray therefore stay under the same thumb.
 */
internal enum class FnEntryAnchor { START, END }

internal enum class FnTileContentAxis {
    PORTRAIT,
    HELD_LANDSCAPE_LABEL_FIRST_RAW,
    HELD_LANDSCAPE_VALUE_FIRST_RAW,
}

/** Keep the shooting Fn menu mode-specific; My Menu and Recent remain in the settings sheet. */
internal fun fnOverlaySlots(mode: CaptureMode, activeSlots: List<FnSlot>): List<FnSlot> =
    activeSlots
        .distinct()
        .take(FN_OVERLAY_MAX_SLOTS)
        .ifEmpty { if (mode == CaptureMode.VIDEO) FnSlot.VIDEO_DEFAULT else FnSlot.PHOTO_DEFAULT }

/**
 * Where the opened Fn tray docks for a held/upright device. Anchor ONLY: the tray's raw column
 * count is derived once, by [fnOverlayGridRows], from FN_OVERLAY_COLUMN_COUNT /
 * FN_OVERLAY_HELD_COLUMN_COUNT. This used to also return a `rawColumnCount` nothing ever read — a
 * second derivation of the same number, free to drift from the one that actually draws.
 */
internal fun fnOverlayAnchor(deviceOrientation: Int): FnOverlayAnchor =
    when (((deviceOrientation % 360) + 360) % 360) {
        90 -> FnOverlayAnchor.CENTER_START
        270 -> FnOverlayAnchor.CENTER_END
        else -> FnOverlayAnchor.BOTTOM_CENTER
    }

internal fun fnEntryAnchor(deviceOrientation: Int): FnEntryAnchor =
    if (((deviceOrientation % 360) + 360) % 360 == 270) {
        FnEntryAnchor.END
    } else {
        FnEntryAnchor.START
    }

/**
 * Raw portrait-locked cells that become a physical 4x2 tray when the handset is held sideways.
 * Null cells preserve the intended physical row for mode-specific lists shorter than eight slots.
 */
internal fun fnOverlayGridRows(slots: List<FnSlot>, deviceOrientation: Int): List<List<FnSlot?>> {
    val visible = slots.take(FN_OVERLAY_MAX_SLOTS)
    return when (((deviceOrientation % 360) + 360) % 360) {
        90 -> MutableList<FnSlot?>(FN_OVERLAY_MAX_SLOTS) { null }.also { raw ->
            visible.forEachIndexed { index, slot ->
                val physicalRow = index / FN_OVERLAY_COLUMN_COUNT
                val physicalColumn = index % FN_OVERLAY_COLUMN_COUNT
                raw[physicalColumn * FN_OVERLAY_HELD_COLUMN_COUNT + (1 - physicalRow)] = slot
            }
        }.chunked(FN_OVERLAY_HELD_COLUMN_COUNT)
        270 -> MutableList<FnSlot?>(FN_OVERLAY_MAX_SLOTS) { null }.also { raw ->
            visible.forEachIndexed { index, slot ->
                val physicalRow = index / FN_OVERLAY_COLUMN_COUNT
                val physicalColumn = index % FN_OVERLAY_COLUMN_COUNT
                raw[(FN_OVERLAY_COLUMN_COUNT - 1 - physicalColumn) * FN_OVERLAY_HELD_COLUMN_COUNT + physicalRow] = slot
            }
        }.chunked(FN_OVERLAY_HELD_COLUMN_COUNT)
        else -> visible.chunked(FN_OVERLAY_COLUMN_COUNT).map { row ->
            row.map<FnSlot, FnSlot?> { it } + List(FN_OVERLAY_COLUMN_COUNT - row.size) { null }
        }
    }
}

internal fun fnTileContentAxis(deviceOrientation: Int): FnTileContentAxis =
    when (((deviceOrientation % 360) + 360) % 360) {
        // Raw X becomes perceived Y in the portrait-locked landscape hold. The ordering reverses
        // between quarter turns, so swap the raw children at 90° to keep label-above-value upright.
        90 -> FnTileContentAxis.HELD_LANDSCAPE_VALUE_FIRST_RAW
        270 -> FnTileContentAxis.HELD_LANDSCAPE_LABEL_FIRST_RAW
        else -> FnTileContentAxis.PORTRAIT
    }

/** Short visual copy for the narrow physical strip; accessibility keeps the complete slot label. */
internal fun fnOverlayVisualLabel(slot: FnSlot, heldLandscape: Boolean): String = when {
    !heldLandscape -> fnSlotLabel(slot)
    // "Stab", not "Steady": the OSD already owns STEADY for ONE value (ENHANCED), so the held tray
    // read "Steady / Std" while the OSD said OIS+ — a tile labelled with the name of a state it is
    // not in. "Stab" matches the OSD's own STAB OFF tag and still fits the 148 dp tile.
    slot == FnSlot.STABILIZATION -> "Stab"
    slot == FnSlot.OPEN_GATE -> "Gate"
    else -> fnSlotLabel(slot)
}

/** Short visual values for held-landscape tiles; accessibility keeps the complete value. */
internal fun fnOverlayVisualValue(slot: FnSlot, value: String, heldLandscape: Boolean): String {
    if (!heldLandscape) return value
    return when (slot) {
        // fnSlotValue emits "Auto 1/60s" / "Auto 12750" — never "A 1/60". The old "A " prefixes
        // matched nothing, so BOTH branches were no-ops in production and the two most-consulted
        // readouts ellipsized in the 148 dp held tray. Keep the auto MARKER (whether the shutter is
        // auto-driven is exactly what a photographer reads here) but spend one character on it.
        FnSlot.SHUTTER, FnSlot.ISO -> value.replaceFirst("Auto ", "A")
        FnSlot.WB -> when (value) {
            "Daylight" -> "Day"
            "Tungsten" -> "Tung."
            else -> value
        }
        FnSlot.STABILIZATION -> if (value == "Standard") "Std" else value
        FnSlot.DRIVE -> if (value == "Timelapse") "TL" else value
        FnSlot.AUDIO_SCENE -> when (value) {
            "Standard" -> "Std"
            "Sound Focus" -> "Focus"
            "Sound Stage" -> "Stage"
            else -> value
        }
        FnSlot.TELECONVERTER -> value.replace(" mm", "mm")
        else -> value
    }
}

internal fun shouldShowExposureMeter(
    mode: ExposureMode,
    transient: Boolean,
): Boolean = mode == ExposureMode.MANUAL || transient

// Pure (plain enum + IntArray) and internal so the MANUAL-mode spot meter's three guard branches
// and clamp are unit-testable — a wrong needle here misleads every manual exposure decision.
internal fun manualMeterEv(mode: ExposureMode, luma: IntArray?): Float? {
    if (mode != ExposureMode.MANUAL) return null
    if (luma == null) return null
    var total = 0L
    luma.forEach { total += it }
    if (total == 0L) return null
    val mean = AutoExposure.meanLuma(luma).coerceAtLeast(0.001f)
    return log2(mean / AutoExposure.TARGET_LUMA).coerceIn(-3f, 3f)
}

private fun log2(value: Float): Float = (ln(value.toDouble()) / ln(2.0)).toFloat()

/**
 * Top y of the letterboxed preview box. Unconditional vertical CENTERING left the 4:3 preview's
 * bottom edge cutting through the focal rail / Fn row — the bottom cluster is bottom-anchored, so
 * chrome straddled the image boundary and read as clipped. Instead, bias the preview UP just far
 * enough that the rest-state bottom cluster starts at (or below) the preview's bottom edge:
 *  - never above [topChromeMinPx] (the status bar + top icon row + OSD strip must stay clear),
 *  - never below the centered position (the preview may only move UP from center, so 16:9 — which
 *    can never clear the cluster — keeps its centered placement and the cluster overlays it fully
 *    INSIDE the image, same as before),
 *  - degenerate (preview taller than the space) falls back to the centered position.
 */
internal fun previewTopPx(
    availableHeightPx: Int,
    previewHeightPx: Int,
    topChromeMinPx: Int,
    bottomReservePx: Int,
): Int {
    val centerTop = (availableHeightPx - previewHeightPx) / 2
    val clearingTop = availableHeightPx - bottomReservePx - previewHeightPx
    if (centerTop <= topChromeMinPx) return centerTop
    return min(centerTop, max(topChromeMinPx, clearingTop))
}

internal data class FocalRailState(
    val selected: Boolean,
    val enabled: Boolean,
    val stateDescription: String,
    val accessibilityRole: Role,
)

internal fun focalRailState(
    choice: LensChoice,
    selectedLens: LensChoice,
    teleconverter: Boolean,
    cameraReady: Boolean,
    recording: Boolean,
): FocalRailState {
    val selected = choice == selectedLens
    val enabled = cameraReady && !recording
    val description = when {
        recording -> "Unavailable while recording"
        !cameraReady -> "Camera reconfiguring"
        selected && teleconverter && choice == LensChoice.TELE3X -> "Selected; teleconverter on"
        selected -> "Selected"
        else -> "Not selected"
    }
    // These presets are one mutually exclusive value, not pages of content. RadioButton lets
    // TalkBack announce that relationship truthfully; Android exports the active preset through
    // AccessibilityNodeInfo.isChecked rather than mislabelling each focal length as a tab.
    return FocalRailState(selected, enabled, description, Role.RadioButton)
}

internal data class ModeCarouselState(
    val selected: Boolean,
    val enabled: Boolean,
    val stateDescription: String,
    val accessibilityRole: Role,
)

internal fun modeCarouselState(active: Boolean, enabled: Boolean): ModeCarouselState =
    ModeCarouselState(
        selected = active,
        enabled = enabled,
        stateDescription = if (active) "Selected" else "Not selected",
        accessibilityRole = Role.RadioButton,
    )

/**
 * Shortest-path angle unwrap for the glyph counter-rotation animation: accumulates an UNWRAPPED
 * target so the spring always takes the <=180-degree way around (a 350->10 transition moves +20,
 * not -340). Pure and internal because the rotation sign and wrap cases have regressed before.
 */
internal fun shortestRotationTarget(current: Float, desiredDegrees: Float): Float {
    var delta = (desiredDegrees - current) % 360f
    if (delta > 180f) delta -= 360f
    if (delta < -180f) delta += 360f
    return current + delta
}
