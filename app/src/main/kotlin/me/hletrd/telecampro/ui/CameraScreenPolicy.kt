package me.hletrd.telecampro.ui

import androidx.annotation.StringRes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import me.hletrd.telecampro.R
import me.hletrd.telecampro.camera.AspectRatio
import me.hletrd.telecampro.camera.AudioInputPreference
import me.hletrd.telecampro.camera.AudioScene
import me.hletrd.telecampro.camera.AutoExposure
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.CaptureMode
import me.hletrd.telecampro.camera.ColorTransfer
import me.hletrd.telecampro.camera.DriveMode
import me.hletrd.telecampro.camera.ExposureMode
import me.hletrd.telecampro.camera.FlashMode
import me.hletrd.telecampro.camera.FnSlot
import me.hletrd.telecampro.camera.FocusMode
import me.hletrd.telecampro.camera.FrameLineType
import me.hletrd.telecampro.camera.GridType
import me.hletrd.telecampro.camera.HardwareKeyAction
import me.hletrd.telecampro.camera.LensChoice
import me.hletrd.telecampro.camera.MeteringMode
import me.hletrd.telecampro.camera.ShutterMode
import me.hletrd.telecampro.camera.ShutterTimer
import me.hletrd.telecampro.camera.VideoColorMode
import me.hletrd.telecampro.camera.VideoStabMode
import me.hletrd.telecampro.camera.WbMode
import me.hletrd.telecampro.ui.controls.aspectRatioLabel
import me.hletrd.telecampro.ui.controls.evCompStops
import me.hletrd.telecampro.ui.controls.exposureModeLetter
import me.hletrd.telecampro.ui.controls.formatEvComp
import me.hletrd.telecampro.ui.controls.formatShutterSpeed
import me.hletrd.telecampro.ui.controls.frameLineTypeLabel
import me.hletrd.telecampro.ui.controls.transferLabelShort
import me.hletrd.telecampro.ui.controls.videoColorModeChipValue
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * Non-composable policy helpers for [CameraScreen], hoisted (behavior-locked, verbatim) out of
 * CameraScreen.kt so the pure decision logic lives apart from Compose emission: status urgency and
 * display duration, the locale-neutral remaining-capacity parser used by the status pill,
 * rotated-layout bounds math, the Fn overlay's orientation-aware layout/copy
 * policy, exposure-meter visibility/needle math, preview letterbox placement, focal-rail and
 * mode-carousel accessibility state, and the glyph-rotation unwrap. Everything here is plain
 * Kotlin over enums/data (host-testable); the composables that consume these stay in
 * CameraScreen.kt.
 */


/**
 * Whether the WINDOW, not gravity, is the trustworthy statement of which way is up.
 *
 * Handsets remain portrait-locked (`sw < 600`) and use the gravity/window residual elsewhere to keep
 * readable glyphs upright. Large screens are the only windows allowed to follow the device. On those
 * screens the window is the more trustworthy statement of which way is up: a device lying FLAT has
 * no in-plane gravity, so `GyroEis` holds its last confident value while the platform independently
 * holds the window's, and subtracting the two stale numbers would produce a confident-looking lie.
 *
 * A large screen is the form factor that lives flat — on a desk, in a stand, on a keyboard case —
 * and it is the one where that lie was actually seen (TB331FC: window at ROTATION_90, gravity never
 * past its initial 0, every label laid on its side). A handset is essentially always in a hand, so
 * its gravity read is live and the residual stands. The 600dp boundary is the platform's own, and
 * `smallestScreenWidthDp` is the right axis because it measures the SHORTER side and therefore does
 * not change when the device turns.
 *
 * Keyed on the DISPLAY's smallest side, which does not change when the device turns, so a tablet
 * held in portrait is window-authoritative here too.
 */
internal fun windowFollowsDevice(smallestScreenWidthDp: Int): Boolean = smallestScreenWidthDp >= 600

/**
 * Full-screen review must never take the only visible Stop control away from a live video take.
 * Timelapse is deliberately absent from this policy: reviewing a completed interval frame while
 * the unattended run continues is an established workflow and does not own AudioRecord/REC chrome.
 */
internal fun reviewTargetEnabled(
    recordingStarting: Boolean,
    recording: Boolean,
    recordingFinalizing: Boolean = false,
): Boolean = !recordingStarting && !recording && !recordingFinalizing

/** Locale-neutral meaning of the status pill's compact remaining-media token. */
internal sealed interface RemainingCapacity {
    val saturated: Boolean

    data class Shots(
        val count: Int,
        override val saturated: Boolean,
    ) : RemainingCapacity

    data class Duration(
        val hours: Int,
        val minutes: Int,
        override val saturated: Boolean,
    ) : RemainingCapacity
}

/**
 * Parses the exact compact token drawn by the status pill without choosing any sentence order.
 *
 * Photo tokens are a shot count (`1234`, `9999+`). Video tokens are minutes (`45m`), hours with an
 * optional minute remainder (`9h`, `9h30m`), and the same forms with a saturation suffix. Returning
 * null is deliberate: an unknown future token must drop only the media phrase rather than make
 * TalkBack announce raw punctuation. Complete prose belongs to the locale resources consumed by
 * `localizedStatusInfoDescription` in CameraScreen.kt.
 */
internal fun parseRemainingCapacity(token: String?, video: Boolean): RemainingCapacity? {
    if (token.isNullOrEmpty()) return null
    val saturated = token.endsWith('+')
    val body = if (saturated) token.dropLast(1) else token
    if (body.isEmpty() || body.endsWith('+')) return null
    if (!video) {
        val count = body.toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return RemainingCapacity.Shots(count, saturated)
    }

    val hours: Int
    val minutes: Int
    val hourMark = body.indexOf('h')
    if (hourMark >= 0) {
        if (body.indexOf('h', hourMark + 1) >= 0) return null
        hours = body.substring(0, hourMark).toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val minuteText = body.substring(hourMark + 1)
        minutes = when {
            minuteText.isEmpty() -> 0
            !minuteText.endsWith('m') -> return null
            else -> minuteText.dropLast(1).toIntOrNull()?.takeIf { it >= 0 } ?: return null
        }
    } else {
        if (!body.endsWith('m')) return null
        hours = 0
        minutes = body.dropLast(1).toIntOrNull()?.takeIf { it >= 0 } ?: return null
    }
    return RemainingCapacity.Duration(hours, minutes, saturated)
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
    // The self-timer is the ONE toggle that does not follow chromeToggleVisible: it draws only while
    // a timer is actually armed, in full DISP as well as compact. The row cannot hold everyone —
    // eight 48 dp targets need 384 dp and a 411 dp phone leaves 387 dp after padding, so the eighth
    // was clipped to a 12 px sliver and vanished. GRID is what lost that race on device, which is the
    // worst possible loser: the grid lines paint on the live image and this button is the only thing
    // that clears them. Dropping the timer's idle slot leaves seven (384 dp) and everything fits.
    // Chosen by the owner as the control that earns its always-visible slot least; it stays reachable
    // in the Fn menu and the Shoot tab, and an ARMED timer still shows here because a shutter that
    // will not fire immediately must be visible and cancellable.
    timer = photo && timer != ShutterTimer.OFF,
    aspect = photo && chromeToggleVisible(compact, aspect == AspectRatio.W4_3),
    grid = chromeToggleVisible(compact, grid == GridType.NONE),
)

/**
 * `ChromeIconButton`'s visible HUD plate. Its touch target stays a separate 48 dp box; this is the
 * disc that is actually drawn AND, because the plate is a circle, the shape that bounds badge ink.
 */
internal const val CHROME_PLATE_DP = 36f

/** Canvas edge of the centred glyph on the two badged buttons (flash bolt, self-timer clock). */
internal const val CHROME_GLYPH_BOX_DP = 16f

/** The wider stroke of the two badged glyphs — clock 1.3 dp, bolt 1.4 dp. */
private const val CHROME_GLYPH_STROKE_DP = 1.4f

/**
 * How far the centred glyph's ink reaches below the plate centre: half its declared box plus half
 * its stroke. Both badged glyphs touch their box edge, so this holds only while their joins stay
 * bounded — a mitered 31.6-degree spike at the bolt's bottom vertex paints 2.6 dp FURTHER down,
 * past the glyph's own declared size and into the badge, which is why `FlashButton` strokes with a
 * round join.
 */
internal const val CHROME_GLYPH_INK_BELOW_CENTRE_DP =
    CHROME_GLYPH_BOX_DP / 2f + CHROME_GLYPH_STROKE_DP / 2f

/**
 * Badge type size, in DP rather than sp. The plate, the glyph and the clip are all fixed dp, so a
 * badge that grew with the system font scale would walk straight back out through the arc — the
 * defect this geometry exists to prevent. Nothing is lost to a large-font user: neither badge is
 * the only carrier of its state, both buttons speak it through `stateDescription`.
 */
internal const val CHROME_BADGE_TEXT_DP = 8f

/** `hudGlyph`'s leading multiple, which sets the badge's line box height. */
private const val CHROME_BADGE_LEADING = 1.2f

/**
 * Inset of the badge's LINE BOX from the plate's bottom edge. Zero is the derived value, not an
 * oversight: Inter leaves 1.91 dp of descent gap below a digit's baseline at this size and leading,
 * and that gap alone already lands the ink 1.02 dp inside the clip while leaving 1.48 dp of air
 * under the glyph. Splitting the difference exactly would want ~0.25 dp, which is below the panel's
 * pixel pitch. Nudging this is what the two clearance functions guard.
 */
internal const val CHROME_BADGE_BOTTOM_INSET_DP = 0f

// Inter SemiBold, read out of the bundled app/src/main/res/font/inter_semibold.ttf (2048 units/em).
private const val INTER_EM = 2048f
private const val INTER_ASCENT = 1984f
private const val INTER_DESCENT = 494f

/** Tallest ink above the baseline across the badge alphabet (the digit "9"). */
private const val INTER_INK_ABOVE_BASELINE = 1513f

/** Deepest round-glyph overshoot below the baseline across the same set (the digit "9"). */
private const val INTER_INK_BELOW_BASELINE = 21f

/** Tabular ("tnum") advance — identical for every digit, which is what keeps "10" and "3" aligned. */
private const val INTER_DIGIT_ADVANCE = 1325f

/** "A" is the only non-digit either badge draws. */
private const val INTER_A_ADVANCE = 1490f

/**
 * Advance width of a badge string. Deliberately the ADVANCE box and not the ink box: the advance is
 * never narrower than the ink it carries, so every clearance derived from it is conservative.
 */
internal fun chromeBadgeAdvanceDp(text: String): Float {
    var units = 0f
    for (c in text) {
        units += when {
            c.isDigit() -> INTER_DIGIT_ADVANCE
            c == 'A' -> INTER_A_ADVANCE
            // Nothing else is drawn today. Bound an unknown character by the wider of the two rather
            // than under-report the room a future badge would need.
            else -> maxOf(INTER_DIGIT_ADVANCE, INTER_A_ADVANCE)
        }
    }
    return units / INTER_EM * CHROME_BADGE_TEXT_DP
}

/**
 * The badge's baseline in plate coordinates (y down from the plate box's top edge). Compose spreads
 * the difference between the requested leading and the font's own ascent+descent proportionally;
 * the requested 1.2 em is 0.01 em SHORT of Inter's natural 1.21 em line, so that distribution moves
 * the baseline by under 0.02 dp whichever way it resolves and no clearance below turns on it.
 */
private fun chromeBadgeBaselineDp(bottomInsetDp: Float): Float {
    val lineHeight = CHROME_BADGE_TEXT_DP * CHROME_BADGE_LEADING
    return CHROME_PLATE_DP - bottomInsetDp - lineHeight +
        lineHeight * INTER_ASCENT / (INTER_ASCENT + INTER_DESCENT)
}

/**
 * Vertical dp of clear plate between the centred glyph's LOWEST ink and the badge's HIGHEST ink.
 *
 * Vertical, not radial, because the shipped badge sits at `Alignment.BottomCenter`: its ink straddles
 * the plate's vertical centre line, exactly where the glyph reaches lowest, so "wholly below the
 * glyph" is both the right test and a conservative one (further out to either side the glyph's ink
 * stops higher). It is NOT a valid bound for a corner-anchored badge, whose box sits off to one side.
 */
internal fun chromeBadgeGlyphClearanceDp(bottomInsetDp: Float = CHROME_BADGE_BOTTOM_INSET_DP): Float {
    val inkTop = chromeBadgeBaselineDp(bottomInsetDp) -
        INTER_INK_ABOVE_BASELINE / INTER_EM * CHROME_BADGE_TEXT_DP
    return inkTop - (CHROME_PLATE_DP / 2f + CHROME_GLYPH_INK_BELOW_CENTRE_DP)
}

/**
 * Radial dp between the badge's farthest ink corner and the plate's circular CLIP. Negative means
 * the arc cuts the glyph.
 *
 * WHY this exists, and why the badge is bottom-CENTRE: both badges shipped at `Alignment.BottomEnd`
 * with 3 dp / 2 dp of corner padding, which put their ink outside the plate's `clip(CircleShape)`
 * and had the arc shave them diagonally — device-verified, "10" lost the bottom of its "0" and "3"
 * kept only its top curve, reading as a "?". A 36 dp square's corner is 18*sqrt(2) = 25.46 dp from
 * the centre against an 18 dp clip radius, so a corner-anchored badge starts ~7.5 dp outside the
 * circle before any padding is applied. The comment that shipped with it reasoned only about
 * clearing the CLOCK and never about the CLIP, which is exactly how it missed.
 *
 * The usable area is the ANNULUS between the glyph's ink (8.7 dp out) and the clip (18 dp) — only
 * 9.3 dp wide. An axis-aligned W x H box placed at angle t off horizontal consumes
 * `W*|cos t| + H*|sin t|` of it. The badge is wider than it is tall, so the DIAGONAL is the worst
 * direction available: "10" needs (9.17 + 5.99)/sqrt(2) = 10.7 dp there and cannot fit at any
 * padding. Straight DOWN is the best: it needs only the 5.99 dp ink height. Hence BottomCenter,
 * where string width stops driving the fit (it only moves ink sideways, where the disc is widest)
 * and one placement covers "A", "3" and "10" alike.
 *
 * [endInsetDp] defaults to that centred placement; pass the old 3 dp to re-derive the defect.
 */
internal fun chromeBadgePlateClearanceDp(
    text: String,
    endInsetDp: Float = (CHROME_PLATE_DP - chromeBadgeAdvanceDp(text)) / 2f,
    bottomInsetDp: Float = CHROME_BADGE_BOTTOM_INSET_DP,
): Float {
    val radius = CHROME_PLATE_DP / 2f
    val dx = (CHROME_PLATE_DP - endInsetDp) - radius
    val dy = chromeBadgeBaselineDp(bottomInsetDp) +
        INTER_INK_BELOW_BASELINE / INTER_EM * CHROME_BADGE_TEXT_DP - radius
    return radius - hypot(dx, dy)
}

internal const val FN_OVERLAY_COLUMN_COUNT = 4
internal const val FN_OVERLAY_HELD_COLUMN_COUNT = 2
internal const val FN_OVERLAY_MAX_SLOTS = 8

/**
 * The held tray's raw width, i.e. its PHYSICAL depth from the bottom edge of a sideways phone. Kept
 * at 148 dp through the 2026-10-28 strip redesign on purpose: the device harness caps that depth at
 * 40 % of the short edge (`FN_HELD_MAX_DEPTH_FRACTION`), which 148 dp clears on any window at least
 * 370 dp wide. That budget is also why the close target is NOT a third 48 dp physical row in the
 * held tray: info line + two tile rows + a 48 dp close row would need 160 dp.
 */
internal const val FN_OVERLAY_HELD_WIDTH_DP = 148
internal const val FN_OVERLAY_SCRIM_ALPHA = 0.22f

/**
 * Sony-style Fn strip geometry (owner request 2026-10-28: "the Fn toggles are each far too big and
 * don't feel like a camera UI"). The previous tile was a 56 dp-tall card carrying an icon, a grey
 * name and a sentence-case value ("Continuous", "Auto --"); a tile is now one camera abbreviation
 * under its icon, and the full name + value moved to the one info line above the grid.
 *
 * Every number is PHYSICAL (as the photographer sees the strip): a tile is [FN_TILE_LONG_DP] wide and
 * [FN_TILE_SHORT_DP] tall in either hold. The short side is the 48 dp touch floor, never less. In the
 * held tray the raw axes swap, so a tile is FN_TILE_SHORT_DP raw-wide and FN_TILE_LONG_DP raw-tall.
 */
internal const val FN_TILE_SHORT_DP = 48
internal const val FN_TILE_LONG_DP = 64
internal const val FN_TILE_GAP_DP = 4
internal const val FN_PANEL_PADDING_DP = 6

/** The close target: a 48 dp square at the physical end of the tile grid, in every hold. */
internal const val FN_CLOSE_TARGET_DP = 48

/**
 * Upright portrait strip width: four tiles, the close column, and the plate's own padding (332 dp).
 * A narrower window shrinks the tiles instead (they are weighted); a wider one never grows them.
 */
internal const val FN_OVERLAY_MAX_WIDTH_DP =
    2 * FN_PANEL_PADDING_DP + FN_OVERLAY_COLUMN_COUNT * FN_TILE_LONG_DP +
        FN_OVERLAY_COLUMN_COUNT * FN_TILE_GAP_DP + FN_CLOSE_TARGET_DP

/**
 * Held tray: what is left of [FN_OVERLAY_HELD_WIDTH_DP] for the info line once the two raw tile
 * columns and their gaps are paid for (32 dp — a 12 sp line plus its leading even at 2x font scale).
 */
internal const val FN_HELD_INFO_LINE_DP =
    FN_OVERLAY_HELD_WIDTH_DP - 2 * FN_PANEL_PADDING_DP -
        FN_OVERLAY_HELD_COLUMN_COUNT * FN_TILE_SHORT_DP - FN_OVERLAY_HELD_COLUMN_COUNT * FN_TILE_GAP_DP

/** Held tray's raw height: four raw tile rows, then the close row, each separated by one gap. */
internal const val FN_HELD_STRIP_LENGTH_DP =
    FN_OVERLAY_MAX_SLOTS / FN_OVERLAY_HELD_COLUMN_COUNT * (FN_TILE_LONG_DP + FN_TILE_GAP_DP) +
        FN_CLOSE_TARGET_DP

internal enum class FnOverlayAnchor { BOTTOM_CENTER, CENTER_START, CENTER_END }

/**
 * Raw-window edge for the Fn entry affordance. The activity stays portrait-locked, so a clockwise
 * hold (270 degrees) moves the physical bottom edge to raw end; portrait and a counter-clockwise
 * hold keep it at raw start. The entry and its opened tray therefore stay under the same thumb.
 */
internal enum class FnEntryAnchor { START, END }

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

/**
 * What one Fn tile DRAWS: a camera abbreviation or numeral, never a sentence-case word. Pure and
 * locale-free on purpose, the same split the old held-landscape compact values used: matching
 * localized output against English literals made every alias vanish in Korean, so this type carries
 * only semantic identity plus any locale-neutral payload, and Compose resolves the EN/KO spelling.
 */
internal sealed interface FnTileText {
    /**
     * Printed verbatim in every locale: exposure-mode letters, AF-C / AF-S / MF, AWB, Kelvin and ISO
     * numbers, shutter fractions, EV, zoom, aspect ratios, curve names. These are the glyphs a Korean
     * camera body prints in Latin too, the same rule the `translatable="false"` ISO / WB / EV labels
     * follow.
     */
    data class Literal(val text: String) : FnTileText

    /** A short WORD with an EN and a KO spelling ("AUTO" / "자동", "STD" / "표준"). */
    data class Word(@StringRes val res: Int) : FnTileText

    /** The self-timer delay, "3s" / "3초". */
    data class Seconds(val seconds: Int) : FnTileText

    /** The converter's effective focal, "300mm" (whole millimetres, like every focal readout). */
    data class FocalMm(val millimeters: Int) : FnTileText
}

/**
 * One tile's visual readout. [manual] marks an operator-fixed exposure/focus/WB value (a manual ISO,
 * shutter, MF, any non-auto white balance): the tile draws it in `CameraColors.ManualActive`, the
 * amber the dial rulers and the manual chips already use for "the camera is not choosing this".
 */
internal data class FnTileReadout(val text: FnTileText, val manual: Boolean = false)

private fun fnTileOnOff(on: Boolean): FnTileText =
    FnTileText.Word(if (on) R.string.fn_tile_on else R.string.fn_tile_off)

private val FN_TILE_AUTO = FnTileText.Word(R.string.fn_tile_auto)

/**
 * A shutter fraction drops its unit ("1/250", as camera bodies print it); a whole or decimal second
 * keeps it ("0.8s", "4.0s"), because "4.0" alone could be any number.
 */
internal fun fnTileShutter(ns: Long): String {
    val speed = formatShutterSpeed(ns)
    return if (speed.startsWith("1/")) speed.removeSuffix("s") else speed
}

/**
 * The Fn strip's camera-abbreviation table, one entry per slot and value. The complete localized
 * value stays where it was — [me.hletrd.telecampro.ui.controls.fnSlotValue] feeds every tile's
 * accessibility state and the strip's info line — so nothing here has to be self-explanatory: a
 * tile is read beside its icon, and the line above the grid spells the selected tile out.
 */
internal fun fnTileReadout(slot: FnSlot, state: CameraUiState): FnTileReadout {
    val c = state.controls
    return when (slot) {
        FnSlot.EXPOSURE_MODE -> FnTileReadout(FnTileText.Literal(exposureModeLetter(c.exposureMode)))
        FnSlot.FOCUS -> when (c.focusMode) {
            FocusMode.MANUAL -> FnTileReadout(FnTileText.Literal("MF"), manual = true)
            // AUTO is the one-shot scan (CONTROL_AF_MODE_AUTO): what every body prints as AF-S.
            FocusMode.AUTO -> FnTileReadout(FnTileText.Literal("AF-S"))
            FocusMode.CONTINUOUS -> FnTileReadout(FnTileText.Literal("AF-C"))
            FocusMode.MACRO -> FnTileReadout(FnTileText.Word(R.string.fn_tile_macro))
        }
        // Same branch order as fnSlotValue, so the tile and its spoken state can never disagree on
        // whether the value is the camera's or the operator's.
        FnSlot.SHUTTER -> when {
            c.exposureMode == ExposureMode.PROGRAM || c.autoShutterDriven -> FnTileReadout(FN_TILE_AUTO)
            c.shutterMode == ShutterMode.ANGLE ->
                FnTileReadout(FnTileText.Literal("%.0f°".format(Locale.US, c.shutterAngle)), manual = true)
            else -> FnTileReadout(FnTileText.Literal(fnTileShutter(c.exposureTimeNs)), manual = true)
        }
        FnSlot.ISO -> when {
            c.exposureMode == ExposureMode.PROGRAM || c.autoIsoDriven -> FnTileReadout(FN_TILE_AUTO)
            else -> FnTileReadout(FnTileText.Literal(c.iso.toString()), manual = true)
        }
        FnSlot.WB -> when (c.wbMode) {
            WbMode.AUTO -> FnTileReadout(FnTileText.Literal("AWB"))
            WbMode.MANUAL -> FnTileReadout(FnTileText.Literal("${c.wbKelvin}K"), manual = true)
            WbMode.INCANDESCENT -> FnTileReadout(FnTileText.Word(R.string.fn_tile_wb_tungsten), manual = true)
            WbMode.FLUORESCENT -> FnTileReadout(FnTileText.Word(R.string.fn_tile_wb_fluorescent), manual = true)
            WbMode.DAYLIGHT -> FnTileReadout(FnTileText.Word(R.string.fn_tile_wb_daylight), manual = true)
            WbMode.CLOUDY -> FnTileReadout(FnTileText.Word(R.string.fn_tile_wb_cloudy), manual = true)
            WbMode.SHADE -> FnTileReadout(FnTileText.Word(R.string.fn_tile_wb_shade), manual = true)
            WbMode.CUSTOM -> FnTileReadout(FnTileText.Word(R.string.fn_tile_wb_custom), manual = true)
        }
        FnSlot.EV -> FnTileReadout(FnTileText.Literal(formatEvComp(evCompStops(state))))
        FnSlot.ZOOM -> FnTileReadout(
            FnTileText.Literal(formatDisplayZoom(c.zoomRatio, state.mainRelativeZoomMultiplier)),
        )
        FnSlot.STABILIZATION -> FnTileReadout(
            when (state.videoStabMode) {
                VideoStabMode.OFF -> fnTileOnOff(false)
                VideoStabMode.STANDARD -> FnTileText.Word(R.string.fn_tile_standard)
                VideoStabMode.ENHANCED -> FnTileText.Word(R.string.fn_tile_active)
            },
        )
        FnSlot.DRIVE -> FnTileReadout(
            FnTileText.Word(
                when (state.driveMode) {
                    DriveMode.SINGLE -> R.string.fn_tile_drive_single
                    DriveMode.BURST -> R.string.fn_tile_drive_burst
                    DriveMode.AEB -> R.string.fn_tile_drive_aeb
                    DriveMode.TIMELAPSE -> R.string.fn_tile_drive_timelapse
                },
            ),
        )
        FnSlot.METERING -> FnTileReadout(
            FnTileText.Word(
                when (c.meteringMode) {
                    MeteringMode.MATRIX -> R.string.fn_tile_meter_matrix
                    MeteringMode.CENTER -> R.string.fn_tile_center
                    MeteringMode.SPOT -> R.string.fn_tile_meter_spot
                },
            ),
        )
        FnSlot.PEAKING -> FnTileReadout(fnTileOnOff(state.focusPeaking))
        FnSlot.ZEBRA -> FnTileReadout(fnTileOnOff(state.zebra))
        // "709" rather than the chip's "Rec. 709": the tile carries the curve's short name, the same
        // vocabulary as the OSD transfer tag (transferLabelShort), with RAW Log's " RAW" suffix.
        FnSlot.TRANSFER -> FnTileReadout(
            FnTileText.Literal(
                if (state.videoColorMode == VideoColorMode.SDR) {
                    transferLabelShort(ColorTransfer.SDR)
                } else {
                    videoColorModeChipValue(state.videoColorMode, state.transfer)
                },
            ),
        )
        FnSlot.AUDIO_SCENE -> FnTileReadout(
            FnTileText.Word(
                when (state.audioScene) {
                    AudioScene.STANDARD -> R.string.fn_tile_standard
                    AudioScene.SOUND_FOCUS -> R.string.fn_tile_sound_focus
                    AudioScene.SOUND_STAGE -> R.string.fn_tile_sound_stage
                },
            ),
        )
        FnSlot.GRID -> FnTileReadout(
            when (state.grid) {
                GridType.NONE -> fnTileOnOff(false)
                GridType.THIRDS -> FnTileText.Literal("3×3")
                GridType.GOLDEN -> FnTileText.Word(R.string.fn_tile_grid_golden)
                GridType.SQUARE -> FnTileText.Word(R.string.fn_tile_grid_square)
                GridType.CENTER -> FnTileText.Word(R.string.fn_tile_center)
            },
        )
        FnSlot.LEVEL -> FnTileReadout(fnTileOnOff(state.level))
        FnSlot.PUNCH_IN -> FnTileReadout(fnTileOnOff(state.punchIn))
        FnSlot.TELECONVERTER -> FnTileReadout(
            if (state.teleconverterMode) {
                FnTileText.FocalMm(kotlin.math.round(state.teleconverterFocalMm).toInt())
            } else {
                fnTileOnOff(false)
            },
        )
        // fnSlotValue's own pair: an engaged Open Gate is named by the 4:3 frame it records.
        FnSlot.OPEN_GATE -> FnTileReadout(
            if (state.openGate) FnTileText.Literal("4:3") else fnTileOnOff(false),
        )
        FnSlot.FRAME_LINES -> FnTileReadout(
            if (state.frameLines == FrameLineType.OFF) {
                fnTileOnOff(false)
            } else {
                FnTileText.Literal(frameLineTypeLabel(state.frameLines))
            },
        )
        FnSlot.FLASH -> FnTileReadout(
            when (c.flash) {
                FlashMode.OFF -> fnTileOnOff(false)
                FlashMode.AUTO -> FN_TILE_AUTO
                FlashMode.ON -> fnTileOnOff(true)
                FlashMode.TORCH -> FnTileText.Word(R.string.fn_tile_torch)
            },
        )
        FnSlot.TIMER -> FnTileReadout(
            if (state.timer == ShutterTimer.OFF) fnTileOnOff(false) else FnTileText.Seconds(state.timer.seconds),
        )
        FnSlot.ASPECT -> FnTileReadout(FnTileText.Literal(aspectRatioLabel(state.aspectRatio)))
        FnSlot.AUDIO_INPUT -> FnTileReadout(
            when (state.audioInputPreference) {
                AudioInputPreference.AUTO -> FN_TILE_AUTO
                AudioInputPreference.BUILT_IN -> FnTileText.Word(R.string.fn_tile_mic_phone)
                AudioInputPreference.WIRED -> FnTileText.Word(R.string.fn_tile_mic_wired)
                AudioInputPreference.USB -> FnTileText.Literal("USB")
                AudioInputPreference.BLUETOOTH -> FnTileText.Literal("BT")
            },
        )
    }
}

/**
 * The tile the info line names (Sony's title line): the last one the photographer touched or moved
 * focus to, while it is still in the strip; otherwise the first ENABLED tile, so a strip whose first
 * slot is dimmed does not open by describing a control that cannot be used; otherwise the first.
 */
internal fun fnOverlayInfoSlot(
    slots: List<FnSlot>,
    lastTouched: FnSlot?,
    enabled: (FnSlot) -> Boolean,
): FnSlot? = lastTouched?.takeIf { it in slots } ?: slots.firstOrNull(enabled) ?: slots.firstOrNull()

/**
 * Rotation of the info line, which is the one WIDE element in the strip. Tiles counter-rotate with
 * the animated glyph angle like every compact glyph, but a wide line cannot: rotating it inside a
 * portrait slot mid-tween balloons its reserved box (rotateLayout reserves the rotated AABB). So it
 * snaps — 0 or 180 while the strip is docked upright, and exactly the held quarter turn once the
 * held tray (where the line runs along the raw Y axis) is the layout.
 */
internal fun fnOverlayInfoRotation(deviceOrientation: Int): Float =
    when (((deviceOrientation % 360) + 360) % 360) {
        90 -> 90f
        270 -> 270f
        in 91..269 -> 180f
        else -> 0f
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
 * Width of the letterboxed preview box: the largest box of [aspect] (width/height) that FITS INSIDE
 * the window on BOTH axes.
 *
 * The old math was width-bound unconditionally (`height = width / aspect`), which is only correct
 * while the window is taller than the content — true for a portrait phone and, historically, the
 * only shape this portrait-locked app could be in. It is NOT true for a landscape window, and
 * Android 16 hands one to every sw600dp device by ignoring the orientation lock (device-reproduced
 * on a Lenovo TB336ZU / Android 16 / 1600x2560, 2026-08-02: in the 2560x1600 landscape window the
 * 3:4 preview asked for 3413 px of height inside 1600 px, so the viewfinder was clipped at the top
 * and most of the window was dead black). Split-screen and freeform reach the same shape on phones.
 *
 * Portrait windows are unchanged by construction: there the height-bound candidate is the larger
 * one, so the width still binds and the result is exactly the previous value.
 */
internal fun previewBoxWidthPx(
    availableWidthPx: Int,
    availableHeightPx: Int,
    aspect: Float,
): Int {
    if (aspect <= 0f || availableWidthPx <= 0 || availableHeightPx <= 0) return availableWidthPx
    val heightBoundWidth = (availableHeightPx * aspect).toInt()
    return min(availableWidthPx, heightBoundWidth)
}

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

/**
 * Vertical offset (px, never positive) for the bottom cluster's RAIL SECTION — the M exposure
 * readout, the focal rail and the compact Fn button. The bottom twin of the TopBar seam rule: chrome
 * sits wholly on the image or wholly on the band, never across the edge.
 *
 * [previewTopPx] lifts a 4:3 preview to clear the cluster but may not climb into the top chrome, so
 * where status bar + top row + the 3:4 image + the cluster outgrow the window, the image's bottom
 * edge lands INSIDE the rail row. Device-seen on PMA110 at the owner's display size (411 x 905 dp,
 * 2026-10-25): the edge ran along the top of the zoom chips and through the Fn circle, reported as a
 * broken PHOTO layout. The band below is what is too short, so the section moves UP onto the image,
 * its bottom [gapPx] above the edge, exactly when its rest span — widened by [gapPx] so a bare touch
 * counts too — contains the edge. 16:9 (the whole cluster already on the image) and any preview that
 * clears the cluster answer 0. [railTopPx]/[railBottomPx] are the section's REST span (measured
 * before the offset is applied), in the same coordinates as [previewBottomPx].
 */
internal fun bottomRailSeamOffsetPx(
    previewBottomPx: Int,
    railTopPx: Int,
    railBottomPx: Int,
    gapPx: Int,
): Int {
    if (railBottomPx <= railTopPx) return 0
    val straddles = previewBottomPx > railTopPx - gapPx && previewBottomPx < railBottomPx + gapPx
    return if (straddles) previewBottomPx - gapPx - railBottomPx else 0
}

internal data class FocalRailState(
    val selected: Boolean,
    val enabled: Boolean,
    val state: CameraControlSelectionState,
    val accessibilityRole: Role,
)

internal enum class CameraControlSelectionState {
    UNAVAILABLE_WHILE_RECORDING,
    CAMERA_RECONFIGURING,
    // Not Ready and NOT reconfiguring (cold start, the terminal "reopen the app") — AGG6-9.
    UNAVAILABLE,
    SELECTED,
    SELECTED_TELECONVERTER_ON,
    NOT_SELECTED,
}

internal fun focalRailState(
    choice: LensChoice,
    selectedLens: LensChoice,
    teleconverter: Boolean,
    cameraReady: Boolean,
    reopenInProgress: Boolean,
    recording: Boolean,
): FocalRailState {
    val selected = choice == selectedLens
    // These presets are one mutually exclusive value, not pages of content. RadioButton lets
    // TalkBack announce that relationship truthfully; Android exports the active preset through
    // AccessibilityNodeInfo.isChecked rather than mislabelling each focal length as a tab.
    return FocalRailState(
        selected = selected,
        enabled = cameraReady && !recording,
        state = railChipState(
            selected = selected,
            cameraReady = cameraReady,
            reopenInProgress = reopenInProgress,
            recording = recording,
            teleconverterSelected = teleconverter && choice == LensChoice.TELE3X,
        ),
        accessibilityRole = Role.RadioButton,
    )
}

/**
 * One chip of the rail's TELE face, where the marks are total-magnification zoom picks rather than
 * lenses. Same availability rules and same wording as [focalRailState] (both read through
 * [railChipState], so the two faces of one rail cannot drift apart) and the same
 * RadioButton relationship — a free pinch can leave every mark unselected, and a radio group with no
 * selection announces exactly that truthfully.
 */
internal fun teleZoomMarkState(
    selected: Boolean,
    cameraReady: Boolean,
    reopenInProgress: Boolean,
    recording: Boolean,
): FocalRailState = FocalRailState(
    selected = selected,
    enabled = cameraReady && !recording,
    state = railChipState(selected, cameraReady, reopenInProgress, recording),
    accessibilityRole = Role.RadioButton,
)

private fun railChipState(
    selected: Boolean,
    cameraReady: Boolean,
    reopenInProgress: Boolean,
    recording: Boolean,
    teleconverterSelected: Boolean = false,
): CameraControlSelectionState = when {
    recording -> CameraControlSelectionState.UNAVAILABLE_WHILE_RECORDING
    // The Output row's predicate (CameraUiState.reopenInProgress, AGG6-9): TalkBack used to read
    // "Camera reconfiguring…" on every chip after the terminal "reopen the app".
    !cameraReady && reopenInProgress -> CameraControlSelectionState.CAMERA_RECONFIGURING
    !cameraReady -> CameraControlSelectionState.UNAVAILABLE
    teleconverterSelected -> CameraControlSelectionState.SELECTED_TELECONVERTER_ON
    selected -> CameraControlSelectionState.SELECTED
    else -> CameraControlSelectionState.NOT_SELECTED
}

internal data class ModeCarouselState(
    val selected: Boolean,
    val enabled: Boolean,
    val state: CameraControlSelectionState,
    val accessibilityRole: Role,
)

internal fun modeCarouselState(active: Boolean, enabled: Boolean): ModeCarouselState =
    ModeCarouselState(
        selected = active,
        enabled = enabled,
        state = if (active) CameraControlSelectionState.SELECTED else CameraControlSelectionState.NOT_SELECTED,
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
