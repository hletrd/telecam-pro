package com.hletrd.findx9tele.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import com.hletrd.findx9tele.camera.AutoExposure
import com.hletrd.findx9tele.camera.CaptureMode
import com.hletrd.findx9tele.camera.ExposureMode
import com.hletrd.findx9tele.camera.FnSlot
import com.hletrd.findx9tele.camera.HardwareKeyAction
import com.hletrd.findx9tele.camera.LensChoice
import com.hletrd.findx9tele.ui.controls.fnSlotLabel
import com.hletrd.findx9tele.ui.overlays.HUD_TEXT_SCRIM_ALPHA
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
 * display duration, rotated-layout bounds math, the Fn overlay's orientation-aware layout/copy
 * policy, exposure-meter visibility/needle math, preview letterbox placement, focal-rail and
 * mode-carousel accessibility state, and the glyph-rotation unwrap. Everything here is plain
 * Kotlin over enums/data (host-testable); the composables that consume these stay in
 * CameraScreen.kt.
 */

internal fun String.isUrgentStatus(): Boolean =
    // "could not": the delete-failure statuses ("Could not delete media") matched no keyword and
    // rendered as polite toasts — found while pinning this classifier (TEST4-14).
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

internal fun showHalfPressLabel(
    active: Boolean,
    action: HardwareKeyAction,
    tapFocusHeld: Boolean,
): Boolean = active && !(action == HardwareKeyAction.AF_ON && tapFocusHeld)

/** Test seam pinning TELE's idle plate to the app-wide, bright-frame contrast floor. */
internal fun teleChipIdleScrimAlpha(): Float = HUD_TEXT_SCRIM_ALPHA

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

internal data class FnOverlayLayoutPolicy(
    val rawColumnCount: Int,
    val anchor: FnOverlayAnchor,
)

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

internal fun fnOverlayLayoutPolicy(deviceOrientation: Int): FnOverlayLayoutPolicy =
    when (((deviceOrientation % 360) + 360) % 360) {
        90 -> FnOverlayLayoutPolicy(FN_OVERLAY_HELD_COLUMN_COUNT, FnOverlayAnchor.CENTER_START)
        270 -> FnOverlayLayoutPolicy(FN_OVERLAY_HELD_COLUMN_COUNT, FnOverlayAnchor.CENTER_END)
        else -> FnOverlayLayoutPolicy(FN_OVERLAY_COLUMN_COUNT, FnOverlayAnchor.BOTTOM_CENTER)
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
    slot == FnSlot.STABILIZATION -> "Steady"
    slot == FnSlot.OPEN_GATE -> "Gate"
    else -> fnSlotLabel(slot)
}

/** Short visual values for held-landscape tiles; accessibility keeps the complete value. */
internal fun fnOverlayVisualValue(slot: FnSlot, value: String, heldLandscape: Boolean): String {
    if (!heldLandscape) return value
    return when (slot) {
        FnSlot.SHUTTER -> value.removePrefix("A ")
        FnSlot.ISO -> value.replaceFirst("A ", "A")
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
