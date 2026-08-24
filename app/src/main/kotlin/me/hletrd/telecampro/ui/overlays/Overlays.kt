package me.hletrd.telecampro.ui.overlays

import me.hletrd.telecampro.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.hletrd.telecampro.camera.unifiedZoom
import me.hletrd.telecampro.camera.AfIndication
import me.hletrd.telecampro.camera.AspectRatio
import me.hletrd.telecampro.camera.BitrateLevel
import me.hletrd.telecampro.camera.CameraFacing
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.CaptureMode
import me.hletrd.telecampro.camera.ColorTransfer
import me.hletrd.telecampro.camera.DriveMode
import me.hletrd.telecampro.camera.FocusConfidenceSource
import me.hletrd.telecampro.camera.GridType
import me.hletrd.telecampro.camera.HistogramData
import me.hletrd.telecampro.camera.MeteringMode
import me.hletrd.telecampro.camera.PhotoFormats
import me.hletrd.telecampro.camera.ShutterTimer
import me.hletrd.telecampro.camera.VideoStabMode
import me.hletrd.telecampro.camera.VideoCodec
import me.hletrd.telecampro.camera.VideoFrameRate
import me.hletrd.telecampro.camera.WaveformData
import me.hletrd.telecampro.camera.displayedStillAspect
import me.hletrd.telecampro.camera.largestCenteredRect
import me.hletrd.telecampro.camera.teleFinderVisible
import me.hletrd.telecampro.camera.videoBitRate
import me.hletrd.telecampro.ui.controls.transferLabelShort
import me.hletrd.telecampro.ui.controls.videoCodecLabelShort
import me.hletrd.telecampro.ui.controls.videoResolutionLabel
import me.hletrd.telecampro.ui.controls.videoFrameRateLabel
import me.hletrd.telecampro.ui.controls.lensLabel
import me.hletrd.telecampro.ui.theme.CameraColors
import me.hletrd.telecampro.ui.controls.trailingEdgeFadeScrollHint
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Small HUD text must remain readable over a white frame. At 82% black, opaque secondary text
 * (#9E9E9E) retains >5:1 contrast and the blue status accent retains >4.7:1.
 */
internal const val HUD_TEXT_SCRIM_ALPHA = 0.82f

/**
 * The ONE spelling of the shared HUD plate: the translucent black slab the viewfinder's readouts,
 * pills, scope panels, chrome discs and chips sit on, plus the review screen's own chrome.
 *
 * It is NOT a claim about every black drawn over a frame — that is what the rule at the bottom is
 * for. The known exception is MediaReview's paused-video ▶ disc, a glyph backing at the 3:1 non-text
 * floor rather than a text plate; it says so at its own site.
 *
 * It is a parameterless value, deliberately, and that is the whole point: there is no alpha to pass,
 * so [HUD_TEXT_SCRIM_ALPHA] cannot be bypassed by accident and [CameraColors.ChromeScrim] never needs
 * to be held at a draw site. A `Modifier` extension could not have replaced all 26 sites — the plate
 * is the else-branch of four `if`/`when` expressions (TeleChip, DialChip, FocalRail, the Speed/Angle
 * toggle), which a Modifier cannot express, so a Modifier-only helper would have left the mixed world
 * it was meant to end. Every one of the 26 is a `.background(…)` argument, or a `val`/branch feeding
 * one; none is a draw-call argument.
 *
 * 26 of 27: the three old spellings covered 27 sites (20 `Color.Black.copy(alpha = …)`, 3
 * `ChromeScrim.copy(…)`, 4 `Pill.copy(…)`). The 27th is GearButton's knob halo — a ~2 px disc inside
 * an 18 dp glyph, at its own deliberately lighter alpha, which is not a plate behind anything and
 * carries a comment saying so.
 *
 * A plate that is NOT this — a local halo, a dim, a gradient stop, a glyph backing — must not be
 * spelled as a `.copy` of a scrim token; write it as its own `Color.Black.copy(…)` at the site with a
 * comment saying why, so the search for "who bypasses the floor" keeps returning nothing.
 */
internal val HudPlate: Color = CameraColors.ChromeScrim.copy(alpha = HUD_TEXT_SCRIM_ALPHA)

/** Dark half of every two-tone framing guide; wider than the quiet foreground at each draw site. */
internal val GuideKeylineInk: Color = Color.Black

/** WCAG contrast of [foregroundRgb] against black [scrimAlpha] composited over white. */
internal fun contrastRatioOnWhiteScrim(foregroundRgb: Int, scrimAlpha: Float): Double {
    val foregroundLuminance = relativeLuminance(foregroundRgb)
    val backgroundChannel = (1.0 - scrimAlpha.coerceIn(0f, 1f)).coerceIn(0.0, 1.0)
    val backgroundLuminance = linearSrgb(backgroundChannel)
    val lighter = maxOf(foregroundLuminance, backgroundLuminance)
    val darker = minOf(foregroundLuminance, backgroundLuminance)
    return (lighter + 0.05) / (darker + 0.05)
}

private fun relativeLuminance(rgb: Int): Double {
    val red = linearSrgb(((rgb shr 16) and 0xFF) / 255.0)
    val green = linearSrgb(((rgb shr 8) and 0xFF) / 255.0)
    val blue = linearSrgb((rgb and 0xFF) / 255.0)
    return 0.2126 * red + 0.7152 * green + 0.0722 * blue
}

private fun linearSrgb(channel: Double): Double =
    if (channel <= 0.04045) channel / 12.92 else Math.pow((channel + 0.055) / 1.055, 2.4)

/**
 * Sony "Frame Lines": a centered marker box of the delivery aspect (2.39:1 / 1:1 / 9:16), fitted to
 * the viewfinder, for judging a crop that will happen in post.
 */
@Composable
fun FrameLinesOverlay(type: me.hletrd.telecampro.camera.FrameLineType, modifier: Modifier = Modifier) {
    val ratio = type.ratio ?: return
    Canvas(modifier = modifier) {
        var w = size.width
        var h = w / ratio
        if (h > size.height) {
            h = size.height
            w = h * ratio
        }
        drawRect(
            color = GuideKeylineInk,
            topLeft = Offset((size.width - w) / 2f, (size.height - h) / 2f),
            size = androidx.compose.ui.geometry.Size(w, h),
            style = Stroke(width = 3.2.dp.toPx()),
        )
        drawRect(
            color = CameraColors.GuideLine,
            topLeft = Offset((size.width - w) / 2f, (size.height - h) / 2f),
            size = androidx.compose.ui.geometry.Size(w, h),
            style = Stroke(width = 1.2.dp.toPx()),
        )
    }
}

/**
 * Composition grid, drawn per [GridType]. Purely decorative; visibility/style is entirely driven
 * by the [type] argument (NONE draws nothing).
 */
@Composable
fun GridOverlay(type: GridType, modifier: Modifier = Modifier) {
    if (type == GridType.NONE) return
    val lineColor = CameraColors.GuideLine
    Canvas(modifier = modifier.fillMaxSize()) {
        val strokeWidth = 1.dp.toPx()
        fun draw(color: Color, width: Float) = when (type) {
            GridType.THIRDS -> drawThirdsGrid(color, width)
            GridType.GOLDEN -> drawGoldenGrid(color, width)
            GridType.SQUARE -> drawSquareGrid(color, width)
            GridType.CENTER -> drawCenterMark(color, width)
            GridType.NONE -> Unit
        }
        draw(GuideKeylineInk, 3.dp.toPx())
        draw(lineColor, strokeWidth)
    }
}

private fun DrawScope.drawThirdsGrid(color: Color, strokeWidth: Float) {
    val x1 = size.width / 3f
    val x2 = 2f * size.width / 3f
    val y1 = size.height / 3f
    val y2 = 2f * size.height / 3f
    drawLine(color, Offset(x1, 0f), Offset(x1, size.height), strokeWidth)
    drawLine(color, Offset(x2, 0f), Offset(x2, size.height), strokeWidth)
    drawLine(color, Offset(0f, y1), Offset(size.width, y1), strokeWidth)
    drawLine(color, Offset(0f, y2), Offset(size.width, y2), strokeWidth)
}

private fun DrawScope.drawGoldenGrid(color: Color, strokeWidth: Float) {
    val phiInv = 0.618034f
    val x1 = size.width * (1f - phiInv)
    val x2 = size.width * phiInv
    val y1 = size.height * (1f - phiInv)
    val y2 = size.height * phiInv
    drawLine(color, Offset(x1, 0f), Offset(x1, size.height), strokeWidth)
    drawLine(color, Offset(x2, 0f), Offset(x2, size.height), strokeWidth)
    drawLine(color, Offset(0f, y1), Offset(size.width, y1), strokeWidth)
    drawLine(color, Offset(0f, y2), Offset(size.width, y2), strokeWidth)
}

private fun DrawScope.drawSquareGrid(color: Color, strokeWidth: Float) {
    val cell = size.minDimension / 4f
    if (cell <= 0f) return
    var x = cell
    while (x < size.width) {
        drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth)
        x += cell
    }
    var y = cell
    while (y < size.height) {
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth)
        y += cell
    }
}

private fun DrawScope.drawCenterMark(color: Color, strokeWidth: Float) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val armLength = size.minDimension * 0.05f
    drawLine(color, Offset(cx - armLength, cy), Offset(cx + armLength, cy), strokeWidth)
    drawLine(color, Offset(cx, cy - armLength), Offset(cx, cy + armLength), strokeWidth)
    drawCircle(
        color = color,
        radius = size.minDimension * 0.08f,
        center = Offset(cx, cy),
        style = Stroke(width = strokeWidth),
    )
}

/**
 * Crop mask for non-[AspectRatio.W4_3] capture ratios: dims the sensor area outside
 * [ratio]'s w:h box with semi-opaque black bars (letterboxed top/bottom or pillarboxed
 * left/right, whichever the view's own aspect requires) so the framed area is obvious in the
 * viewfinder. Draws nothing for [AspectRatio.W4_3] (the full-sensor/no-crop default).
 */
@Composable
fun AspectMask(ratio: AspectRatio, modifier: Modifier = Modifier) {
    if (ratio == AspectRatio.W4_3) return // full sensor = no crop mask
    val barColor = Color.Black.copy(alpha = 0.5f)
    Canvas(modifier = modifier.fillMaxSize()) {
        val displayedAspect = displayedStillAspect(ratio)
        val frame = largestCenteredRect(
            containerWidth = size.width,
            containerHeight = size.height,
            aspectWidth = displayedAspect.width,
            aspectHeight = displayedAspect.height,
        )
        if (frame.x > 0f) {
            drawRect(color = barColor, topLeft = Offset.Zero, size = Size(frame.x, size.height))
            drawRect(
                color = barColor,
                topLeft = Offset(frame.x + frame.width, 0f),
                size = Size(frame.x, size.height),
            )
        }
        if (frame.y > 0f) {
            drawRect(color = barColor, topLeft = Offset.Zero, size = Size(size.width, frame.y))
            drawRect(
                color = barColor,
                topLeft = Offset(0f, frame.y + frame.height),
                size = Size(size.width, frame.y),
            )
        }
    }
}

/**
 * Deviation of the horizon gauge from the CURRENT held quadrant, normalized to (-180, 180]. Deviation
 * is measured against the held orientation (not raw roll) because a landscape hold reads ±90° raw and
 * would never show level, but the photographer's question is "am I square to the horizon in THIS
 * hold" — captures auto-rotate per quadrant. Pulled out of [LevelOverlay] as a pure seam so the wrap
 * logic (e.g. 350° roll vs a 10° hold reads as -20°, not +340°) is unit-testable off-device — the
 * sessionAttemptPlan/centerCropBox house pattern. Upside-down (a 180° diff) maps to the inclusive
 * +180 edge, visually identical to -180 on the symmetric gauge line.
 */
internal fun levelDeviationDegrees(rollDegrees: Float, deviceOrientation: Int): Float {
    val m = ((rollDegrees - deviceOrientation) % 360f + 360f) % 360f
    return if (m > 180f) m - 360f else m
}

internal enum class HorizonAccessibilityDirection {
    LEVEL,
    LEFT,
    RIGHT,
}

/**
 * Quiet semantic projection of the continuously moving horizon gauge.
 *
 * The Canvas still follows the sensor exactly. Accessibility rounds a non-level result to
 * five-degree buckets so a focus-inspected state does not churn at sensor rate; it deliberately is
 * not a live region. The visual gauge's existing half-degree level threshold remains the one truth
 * for the LEVEL state.
 */
internal data class HorizonAccessibilityState(
    val direction: HorizonAccessibilityDirection,
    val degrees: Int,
)

internal fun horizonAccessibilityState(deviationDegrees: Float): HorizonAccessibilityState? {
    if (!deviationDegrees.isFinite()) return null
    if (abs(deviationDegrees) < 0.5f) {
        return HorizonAccessibilityState(HorizonAccessibilityDirection.LEVEL, 0)
    }
    val bucket = ((abs(deviationDegrees) / 5f).roundToInt() * 5).coerceIn(5, 180)
    return HorizonAccessibilityState(
        direction = if (deviationDegrees < 0f) {
            HorizonAccessibilityDirection.LEFT
        } else {
            HorizonAccessibilityDirection.RIGHT
        },
        degrees = bucket,
    )
}

/**
 * Horizon/level indicator. A static reference line marks true-horizontal; the [rollDegrees] line
 * rotates with device roll and turns YELLOW (Sony style) once within a small tolerance of level. In
 * a landscape hold the gauge stays horizontal on screen: deviation is measured against the current
 * held quadrant ([deviceOrientation], the task-8 gravity orientation), so "level" means square to
 * the horizon in whatever way the phone is being held.
 */
@Composable
fun LevelOverlay(modifier: Modifier = Modifier, rollDegrees: Float = 0f, deviceOrientation: Int = 0) {
    val deviation = levelDeviationDegrees(rollDegrees, deviceOrientation)
    val isLevel = abs(deviation) < 0.5f
    val indicatorColor = if (isLevel) CameraColors.ManualActive else CameraColors.TextPrimary
    Canvas(modifier = modifier.fillMaxSize()) {
        val cy = size.height / 2f
        val halfSpan = size.width * 0.16f
        drawLine(
            color = GuideKeylineInk,
            start = Offset(size.width / 2f - halfSpan, cy),
            end = Offset(size.width / 2f + halfSpan, cy),
            strokeWidth = 3.5.dp.toPx(),
        )
        drawLine(
            // One-off: the STATIC datum the moving indicator above is read against. It is a part of
            // this gauge and is deliberately quieter than the live line it sits under, so it is not
            // GuideLine (a composition rule the photographer frames to) and not ink.
            // The 2026-07-28 visual pass lowered this from 0.4 to 0.22. 0.36 is the quietest white
            // that still clears 3:1 on black; the keyline supplies the opposite edge on a bright
            // frame. The old 0.22 disappeared on dark content too.
            color = Color.White.copy(alpha = 0.36f),
            start = Offset(size.width / 2f - halfSpan, cy),
            end = Offset(size.width / 2f + halfSpan, cy),
            strokeWidth = 1.5.dp.toPx(),
        )
        rotate(degrees = deviation, pivot = Offset(size.width / 2f, cy)) {
            drawLine(
                color = GuideKeylineInk,
                start = Offset(size.width / 2f - halfSpan, cy),
                end = Offset(size.width / 2f + halfSpan, cy),
                strokeWidth = 4.dp.toPx(),
            )
            drawLine(
                // Slimmed 4 dp → 2 dp and taken off full opacity (user-reported: too bright and
                // heavy). The gauge is read by its ANGLE, not its mass, and the level state already
                // has a second channel — [indicatorColor] turns yellow within 0.5°. Kept a touch
                // stronger than the datum so the live line stays the figure and the datum the ground.
                color = indicatorColor.copy(alpha = 0.72f),
                start = Offset(size.width / 2f - halfSpan, cy),
                end = Offset(size.width / 2f + halfSpan, cy),
                strokeWidth = 2.dp.toPx(),
            )
        }
        drawCircle(
            color = GuideKeylineInk,
            radius = 3.5.dp.toPx(),
            center = Offset(size.width / 2f, cy),
        )
        drawCircle(
            color = indicatorColor.copy(alpha = 0.72f),
            radius = 2.5.dp.toPx(),
            center = Offset(size.width / 2f, cy),
        )
    }
}

/**
 * Tap-to-focus reticle: a small yellow bracketed square centered at [point] (view-normalized
 * 0..1 coordinates). Draws nothing while [point] is null (e.g. after the auto-hide timeout).
 */
internal enum class FocusReticleCue {
    NONE,
    CHECK,
    CROSS,
}

internal data class FocusReticlePoint(val x: Float, val y: Float)

internal data class FocusReticleSegment(
    val start: FocusReticlePoint,
    val end: FocusReticlePoint,
)

/** Exact line geometry and stroke ownership consumed by the terminal-cue Canvas draw. */
internal data class FocusReticleCueGeometry(
    val segments: List<FocusReticleSegment>,
    val inkWidthPx: Float,
    val keylineWidthPx: Float,
)

internal fun focusReticleCueGeometry(
    cue: FocusReticleCue,
    centerX: Float,
    centerY: Float,
    halfSizePx: Float,
    inkWidthPx: Float,
    keylineWidthPx: Float,
): FocusReticleCueGeometry {
    val segments = when (cue) {
        FocusReticleCue.NONE -> emptyList()
        FocusReticleCue.CHECK -> listOf(
            FocusReticleSegment(
                start = FocusReticlePoint(centerX - halfSizePx, centerY),
                end = FocusReticlePoint(centerX - halfSizePx * 0.25f, centerY + halfSizePx * 0.7f),
            ),
            FocusReticleSegment(
                start = FocusReticlePoint(centerX - halfSizePx * 0.25f, centerY + halfSizePx * 0.7f),
                end = FocusReticlePoint(centerX + halfSizePx, centerY - halfSizePx * 0.8f),
            ),
        )
        FocusReticleCue.CROSS -> listOf(
            FocusReticleSegment(
                start = FocusReticlePoint(centerX - halfSizePx, centerY - halfSizePx),
                end = FocusReticlePoint(centerX + halfSizePx, centerY + halfSizePx),
            ),
            FocusReticleSegment(
                start = FocusReticlePoint(centerX + halfSizePx, centerY - halfSizePx),
                end = FocusReticlePoint(centerX - halfSizePx, centerY + halfSizePx),
            ),
        )
    }
    return FocusReticleCueGeometry(segments, inkWidthPx, keylineWidthPx)
}

internal val FocusReticleFocusedInk = Color(0xFF30D158)
internal val FocusReticleFailedInk = Color(0xFFFF453A)
internal val FocusReticleKeylineInk = Color.Black

/** Non-color terminal-state channel kept pure so every AF state has deterministic coverage. */
internal fun focusReticleCue(indication: AfIndication): FocusReticleCue = when (indication) {
    AfIndication.FOCUSED -> FocusReticleCue.CHECK
    AfIndication.FAILED -> FocusReticleCue.CROSS
    AfIndication.SCANNING, AfIndication.IDLE -> FocusReticleCue.NONE
}

@Composable
fun FocusReticle(
    point: Pair<Float, Float>?,
    modifier: Modifier = Modifier,
    indication: AfIndication = AfIndication.IDLE,
) {
    if (point == null) return
    // Sony-style AF confirmation: the bracket turns GREEN on lock and RED on a failed scan — at
    // 300 mm this is the difference between a keeper and a soft frame the user can't judge on the
    // small live view. Yellow = tapped/scanning (the pre-verdict states).
    val color = when (indication) {
        AfIndication.FOCUSED -> FocusReticleFocusedInk
        AfIndication.FAILED -> FocusReticleFailedInk
        AfIndication.SCANNING, AfIndication.IDLE -> CameraColors.ManualActive
    }
    // Accessibility state belongs to the durable viewfinder identity in CameraScreen. This Canvas
    // is visual-only; giving its fill-size caller another semantic identity created a second
    // preview-sized focus stop, while FocusResultLiveRegion already owns terminal announcements.
    Canvas(modifier = modifier) {
        val cx = point.first * size.width
        val cy = point.second * size.height
        val half = 32.dp.toPx()
        val corner = 10.dp.toPx()
        val strokeWidth = 2.dp.toPx()
        val outlineWidth = 4.dp.toPx()
        val left = cx - half
        val right = cx + half
        val top = cy - half
        val bottom = cy + half
        val bracketSegments = listOf(
            Offset(left, top) to Offset(left + corner, top),
            Offset(left, top) to Offset(left, top + corner),
            Offset(right, top) to Offset(right - corner, top),
            Offset(right, top) to Offset(right, top + corner),
            Offset(left, bottom) to Offset(left + corner, bottom),
            Offset(left, bottom) to Offset(left, bottom - corner),
            Offset(right, bottom) to Offset(right - corner, bottom),
            Offset(right, bottom) to Offset(right, bottom - corner),
        )
        fun drawOutlinedLine(
            start: Offset,
            end: Offset,
            rounded: Boolean = false,
            inkWidthPx: Float = strokeWidth,
            keylineWidthPx: Float = outlineWidth,
        ) {
            val cap = if (rounded) StrokeCap.Round else StrokeCap.Butt
            // Two-channel contrast over arbitrary preview pixels: bright state ink survives dark
            // detail, while its opaque black keyline survives bright or same-hue subjects.
            drawLine(FocusReticleKeylineInk, start, end, keylineWidthPx, cap = cap)
            drawLine(color, start, end, inkWidthPx, cap = cap)
        }
        bracketSegments.forEach { (start, end) -> drawOutlinedLine(start, end) }

        // Color remains the Sony-style fast glance channel; terminal geometry makes the same
        // verdict available to color-vision-deficient operators without adding prose to the finder.
        val cueGeometry = focusReticleCueGeometry(
            cue = focusReticleCue(indication),
            centerX = cx,
            centerY = cy,
            halfSizePx = 9.dp.toPx(),
            inkWidthPx = strokeWidth,
            keylineWidthPx = outlineWidth,
        )
        cueGeometry.segments.forEach { segment ->
            drawOutlinedLine(
                start = Offset(segment.start.x, segment.start.y),
                end = Offset(segment.end.x, segment.end.y),
                rounded = true,
                inkWidthPx = cueGeometry.inkWidthPx,
                keylineWidthPx = cueGeometry.keylineWidthPx,
            )
        }
    }
}

/** Stable, off-focus status node that announces only terminal tap-AF outcomes. */
@Composable
fun FocusResultLiveRegion(
    indication: AfIndication,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val terminalDescription = if (!active) null else when (indication) {
        AfIndication.FOCUSED -> stringResource(R.string.a11y_focus_locked)
        AfIndication.FAILED -> stringResource(R.string.a11y_autofocus_failed)
        AfIndication.SCANNING, AfIndication.IDLE -> null
    }
    Box(
        modifier = modifier.clearAndSetSemantics {
            terminalDescription?.let {
                contentDescription = it
                liveRegion = LiveRegionMode.Polite
            }
        },
    )
}

/** Red recording dot + elapsed mm:ss, shown while [me.hletrd.telecampro.camera.CameraUiState.isRecording] is true. */
@Composable
fun RecordingIndicator(elapsedMs: Long, modifier: Modifier = Modifier) {
    val totalSeconds = elapsedMs / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val timeLabel = "%02d:%02d".format(Locale.US, minutes, seconds)
    val recordingDescription = stringResource(R.string.a11y_recording)
    Row(
        modifier = modifier
            .background(HudPlate, RoundedCornerShape(50))
            // Keep a stable REC description; elapsed telemetry must not be re-announced every second.
            .clearAndSetSemantics { contentDescription = recordingDescription }
            // The ONE HUD pill inset, 12/6 (canonical note in CameraScreen's ModeLabel). This
            // pill is why "one inset" needs stating twice: it hand-rolled ~2/10 horizontal and 4
            // vertical out of a leading Spacer plus one-sided Text padding, so the sweep that unified
            // fifteen `padding(…)` calls had nothing here to find. Only the PLATE's inset moves — the
            // dot-to-timecode gap is the Row's own 6 dp arrangement, untouched. It is End-aligned in a
            // column whose other members are 120-150 dp wide, so the ~12 dp it gains contends with
            // nothing, and it is non-interactive, so no touch target is involved.
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(modifier = Modifier.size(10.dp)) {
            drawCircle(color = CameraColors.Record)
        }
        Text(
            text = timeLabel,
            color = CameraColors.TextPrimary,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * Thin horizontal audio input-level meter — ONE BAR PER CHANNEL (each 0..1), stacked.
 *
 * Per-channel rather than one averaged bar because that average is exactly what hides the failure an
 * input meter exists to catch: on a stereo or multi-capsule external mic, one dead channel still
 * leaves the average moving (2026-08-02). Fill colour shifts green -> yellow -> red per channel as
 * it approaches clipping. Bar fill remains the stable RMS signal-strength view; the non-live
 * accessibility state separately consumes held per-channel peaks so “clipping” means a real
 * full-scale sample rather than an almost-square RMS waveform.
 *
 * The overall footprint is held constant: the bar height splits between channels, so a stereo mic
 * does not push the OSD around relative to a mono one.
 */
@Composable
fun AudioMeter(
    levels: List<Float>,
    modifier: Modifier = Modifier,
    overloads: List<me.hletrd.telecampro.video.AudioOverloadState> = emptyList(),
) {
    // An EMPTY list still draws the empty plate. The meter's own visibility is owned by the caller's
    // gate; blanking the plate here would make it flicker away between AudioRecord generations and
    // on every stop, which is a state the operator would read as "the mic died".
    val gap = 2.dp
    val audioMeterLabel = stringResource(R.string.a11y_audio_meter)
    val audioMeterState = if (levels.isEmpty()) {
        stringResource(R.string.a11y_audio_levels_pending)
    } else {
        audioAccessibilityStates(levels, overloads).mapIndexed { index, state ->
            val stateLabel = stringResource(
                when (state) {
                    AudioAccessibilityState.PENDING -> R.string.a11y_audio_level_pending
                    AudioAccessibilityState.SILENT -> R.string.a11y_audio_level_silent
                    AudioAccessibilityState.SIGNAL -> R.string.a11y_audio_level_signal
                    AudioAccessibilityState.HIGH -> R.string.a11y_audio_level_high
                    AudioAccessibilityState.NEAR_CLIPPING -> R.string.a11y_audio_level_near_clipping
                    AudioAccessibilityState.CLIPPING -> R.string.a11y_audio_level_clipping
                },
            )
            stringResource(R.string.a11y_audio_channel_state, index + 1, stateLabel)
        }.joinToString(", ")
    }
    Box(
        modifier = modifier
            .size(width = 120.dp, height = 8.dp)
            // Rides the tested HUD contrast floor (05486cb) like the OSD pills: the meter reads level
            // against bright scenes, so its scrim can't be the near-transparent 0.45 it was.
            .background(HudPlate, RoundedCornerShape(4.dp))
            .semantics {
                contentDescription = audioMeterLabel
                stateDescription = audioMeterState
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val gapPx = gap.toPx()
            val totalGap = gapPx * (levels.size - 1).coerceAtLeast(0)
            val barHeight = ((size.height - totalGap) / levels.size).coerceAtLeast(1f)
            levels.forEachIndexed { index, level ->
                val fill = level.coerceIn(0f, 1f)
                if (fill <= 0f) return@forEachIndexed
                val fillColor = when {
                    fill < 0.6f -> Color(0xFF4CD964)
                    fill < 0.85f -> CameraColors.ManualActive
                    else -> CameraColors.Record
                }
                drawRect(
                    color = fillColor,
                    topLeft = Offset(0f, index * (barHeight + gapPx)),
                    size = Size(size.width * fill, barHeight),
                )
            }
        }
    }
}

internal enum class AudioAccessibilityState { PENDING, SILENT, SIGNAL, HIGH, NEAR_CLIPPING, CLIPPING }

/** Coarse and change-gated by identity: raw 10 Hz meter values do not become sensor-rate speech. */
internal fun audioAccessibilityStates(
    levels: List<Float>,
    overloads: List<me.hletrd.telecampro.video.AudioOverloadState> = emptyList(),
): List<AudioAccessibilityState> =
    levels.mapIndexed { index, raw ->
        if (!raw.isFinite()) return@mapIndexed AudioAccessibilityState.PENDING
        val level = raw.coerceIn(0f, 1f)
        val overload = overloads.getOrNull(index) ?: me.hletrd.telecampro.video.AudioOverloadState.NORMAL
        when {
            overload == me.hletrd.telecampro.video.AudioOverloadState.CLIPPING ->
                AudioAccessibilityState.CLIPPING
            overload == me.hletrd.telecampro.video.AudioOverloadState.NEAR_CLIPPING ->
                AudioAccessibilityState.NEAR_CLIPPING
            level <= 0.02f -> AudioAccessibilityState.SILENT
            level < 0.60f -> AudioAccessibilityState.SIGNAL
            else -> AudioAccessibilityState.HIGH
        }
    }

/**
 * Top status strip — the Sony-style shooting OSD. Mode-aware so it only shows what affects the
 * NEXT shot in the current mode:
 *  - PHOTO: 35mm-equivalent focal (tagged TELE through the converter), still formats, drive mode
 *    (when not single-shot) and self-timer (when armed).
 *  - VIDEO: focal, the resolved recording spec (resolution · fps · codec · Mbps — what the encoder
 *    will actually write), and the transfer function.
 *  - Both: metering pattern (when not matrix), lock state, and — in video — the stabilization tag
 *    (ALWAYS rendered there, not only when non-default: at 300 mm whether stabilization is active
 *    is standing information; UI review #7 aligned this doc with the render).
 */
/**
 * The ONE gate for the OIS OFF tag and its compact-strip visibility clause (review L11 /
 * verification S5). Capability-gated: `oisEnabled` is a persisted preference that normalization
 * deliberately never touches on no-OIS routes, so without the capability axis the tag claimed a
 * control the lens does not have — and the strip clause forced the compact OSD visible for a tag
 * the render side suppresses. A null-caps route (mid-reopen) counts as unavailable.
 */
internal fun oisOffTagVisible(photoMode: Boolean, oisAvailable: Boolean, oisEnabled: Boolean): Boolean =
    photoMode && oisAvailable && !oisEnabled

internal fun compactShootingStatusVisible(state: CameraUiState): Boolean =
    state.activeMemorySlot != null ||
        // FRONT changes what the app IS (the teleconverter is forced off, the tele chip and focal
        // rail disappear). Sighted users read it from the vanished chrome and the mirrored image;
        // without a tag there was no readout of it at all, and no non-visual way to know.
        state.activeCameraRoute != me.hletrd.telecampro.camera.CameraRoute.BACK ||
        state.mode == CaptureMode.VIDEO ||
        (state.mode == CaptureMode.PHOTO && compactPhotoFormatLabel(state) != null) ||
        (state.mode == CaptureMode.PHOTO && state.driveMode != DriveMode.SINGLE) ||
        oisOffTagVisible(
            photoMode = state.mode == CaptureMode.PHOTO,
            oisAvailable = state.caps?.oisAvailable == true,
            oisEnabled = state.controls.oisEnabled,
        ) ||
        // Accepted hi-res is a non-default capture state — the HR tag must stay reachable in the
        // compact strip too, not only while some other tag happens to force it visible.
        state.photoSessionOutputs.hiRes ||
        state.controls.meteringMode != MeteringMode.MATRIX ||
        state.controls.aeLock ||
        state.controls.awbLock ||
        state.controls.afLock ||
        // Focus confidence is the OSD's one statement that the viewfinder is NOT resolving the
        // subject. Compact is the default state (DISP starts off), so leaving it out of this gate
        // made the tag render only when some UNRELATED tag happened to force the strip visible —
        // i.e. never, in the default photo state the detector was built for.
        state.focusConfidence != null ||
        state.punchInActive ||
        teleFinderVisible(
            enabled = state.teleFinder,
            teleconverter = state.teleconverterMode,
            videoMode = state.mode == CaptureMode.VIDEO,
            aspect = state.aspectRatio,
            punchIn = state.punchInActive,
            zoomRatio = state.unifiedZoom,
        )

/** The one HEIF(+JPEG)(+DNG) string. Both StatusBar branches used to build it separately. */
internal fun photoFormatLabel(formats: PhotoFormats): String = buildString {
    if (formats.heif) append("HEIF")
    if (formats.jpeg) {
        if (isNotEmpty()) append("+")
        append("JPEG")
    }
    if (formats.dngRaw) {
        if (isNotEmpty()) append("+")
        append("DNG")
    }
    // "--" is the app's one null token (focalLabel below, the MR slots, the Fn cycles). This slot and
    // that focal readout land in the SAME StatusBar row on a preview-only session, so a lone "-" here
    // would put two spellings of "nothing" side by side.
    if (isEmpty()) append("--")
}

/** Compact strip: the default HEIF-only combination is not an output-changing state, so it is silent. */
internal fun compactPhotoFormatLabel(state: CameraUiState): String? {
    if (state.mode != CaptureMode.PHOTO) return null
    val formats = state.photoFormats
    if (formats.heif && !formats.jpeg && !formats.dngRaw) return null
    return photoFormatLabel(formats)
}

internal data class StatusBarPriorityResetKey(
    val mode: CaptureMode,
    val compact: Boolean,
    val focalLabel: String?,
    val memorySlot: String?,
    val routeTag: me.hletrd.telecampro.camera.CameraRoute?,
    val videoOutput: StatusBarVideoOutputIdentity?,
    val transferTag: ColorTransfer?,
    val gammaAssistTagVisible: Boolean,
    val openGateTagVisible: Boolean,
    val mutedTagVisible: Boolean,
    val stabilizationTag: VideoStabMode?,
    val photoFormatTag: String?,
    val highResolutionTagVisible: Boolean,
    val driveTag: StatusBarDriveIdentity?,
    val oisOffTagVisible: Boolean,
    val timerTag: ShutterTimer?,
)

internal data class StatusBarVideoOutputIdentity(
    val width: Int,
    val height: Int,
    val frameRate: VideoFrameRate,
    val codec: VideoCodec,
    val bitrateLevel: BitrateLevel,
)

internal data class StatusBarDriveIdentity(
    val mode: DriveMode,
    val intervalSec: Int?,
)

/**
 * Stable reset policy for the OSD scroll. Fields follow the leading tag order rendered below and
 * are populated only when that slot is visible in the active mode/compactness. The tail begins at
 * metering: locks, focus analysis, loupe/overview, and all live telemetry deliberately stay out.
 */
internal fun statusBarPriorityResetKey(
    state: CameraUiState,
    focalLabel: String?,
    compact: Boolean,
): StatusBarPriorityResetKey {
    val videoMode = state.mode == CaptureMode.VIDEO
    val photoMode = !videoMode
    val encodedSize = state.encodedVideoResolution
    return StatusBarPriorityResetKey(
        mode = state.mode,
        compact = compact,
        focalLabel = focalLabel,
        memorySlot = state.activeMemorySlot?.name,
        routeTag = state.activeCameraRoute.takeIf { it != me.hletrd.telecampro.camera.CameraRoute.BACK },
        videoOutput = if (videoMode && !compact) {
            StatusBarVideoOutputIdentity(
                width = encodedSize.width,
                height = encodedSize.height,
                frameRate = state.videoFrameRate,
                codec = state.videoCodec,
                bitrateLevel = state.bitrateLevel,
            )
        } else {
            null
        },
        transferTag = state.transfer.takeIf { videoMode && (!compact || it != ColorTransfer.SDR) },
        gammaAssistTagVisible = videoMode && state.transfer.isLog && state.gammaAssist,
        openGateTagVisible = videoMode && state.openGate,
        mutedTagVisible = videoMode && !state.recordAudio,
        stabilizationTag = state.videoStabMode.takeIf { videoMode },
        photoFormatTag = if (photoMode) {
            if (compact) compactPhotoFormatLabel(state) else photoFormatLabel(state.photoFormats)
        } else {
            null
        },
        highResolutionTagVisible = photoMode && state.photoSessionOutputs.hiRes,
        driveTag = state.driveMode.takeIf { photoMode && it != DriveMode.SINGLE }?.let { driveMode ->
            StatusBarDriveIdentity(
                mode = driveMode,
                intervalSec = state.intervalSec.takeIf { driveMode == DriveMode.TIMELAPSE },
            )
        },
        oisOffTagVisible = oisOffTagVisible(
            photoMode = photoMode,
            oisAvailable = state.caps?.oisAvailable == true,
            oisEnabled = state.controls.oisEnabled,
        ),
        timerTag = state.timer.takeIf { photoMode && !compact && it != ShutterTimer.OFF },
    )
}

@Composable
fun StatusBar(state: CameraUiState, modifier: Modifier = Modifier, compact: Boolean = false) {
    if (compact && !compactShootingStatusVisible(state)) return
    // PERF: StatusBar takes the WHOLE CameraUiState, so it recomposes at telemetry rate (audio
    // level, roll, REC timer). Both derivations below are keyed remembers for the same reason its
    // two siblings already are — StatusInfoPill (PERF4-2) and TopBar's availability projection —
    // and the focal label is computed only in the branch that renders it.
    val focalLabel = if (compact) {
        null
    } else {
        val focal = state.caps?.equivalentFocalMm ?: 0f
        val teleFocal = state.teleconverterFocalMm
        remember(focal, state.controls.zoomRatio, state.teleconverterMode, teleFocal) {
            // The afocal teleconverter multiplies the ~70 mm periscope → the SELECTED converter's
            // effective focal (~300 mm on the kit optic). Round to the nearest 10 mm so the readout
            // reads a clean "300 mm" rather than 296 mm. TELE effective focal follows the digital
            // zoom on that NOMINAL base (constant scale, matching the 13/30/60× pill marks): on the
            // kit optic 300 mm at 13×, 690 at 30×, 1380 at 60×.
            val effFocal = ((teleFocal * state.controls.zoomRatio.coerceAtLeast(1f)) / 10f).roundToInt() * 10
            when {
                focal <= 0f -> "--"
                state.teleconverterMode -> "$effFocal mm TELE"
                // Seamless zoom: the logical camera's equiv focal is the MAIN lens's (23 mm) and the
                // unified zoom is main-relative, so the EFFECTIVE focal is their product — 14 mm at
                // 0.6×, 230 mm at 10× — tracking the lens the HAL actually has active, like the TELE
                // readout does. FRONT rides this same seam unchanged: its caps equiv is the front
                // lens's own and its zoom is lens-local, so the product is the honest selfie focal
                // (no TELE multiplier possible — teleconverterMode is forced off on the front route).
                else -> "%.0f mm".format(Locale.US, focal * state.controls.zoomRatio.coerceAtLeast(0.01f))
            }
        }
    }
    val scrollState = rememberScrollState()
    // Reset to logical Start only when the ordered leading identity/output truth changes. Volatile
    // telemetry and tail-only metering/lock/analysis/assist tags may update without yanking a user
    // who is inspecting the tail; any leading tag transition immediately returns priority.
    val priorityResetKey = statusBarPriorityResetKey(state, focalLabel, compact)
    LaunchedEffect(priorityResetKey) { scrollState.scrollTo(0) }
    Row(
        modifier = modifier
            .background(HudPlate, RoundedCornerShape(8.dp))
            // Sony bodies paginate their status strip; with many concurrent tags (AEL/AWL/AFL/LOUPE/…)
            // trailing tags would run off-screen, so scroll keeps every lock tag reachable.
            .trailingEdgeFadeScrollHint(scrollState)
            .horizontalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        focalLabel?.let {
            Text(it, color = CameraColors.TextPrimary, style = MaterialTheme.typography.labelMedium)
        }
        state.activeMemorySlot?.let {
            Text(it.name, color = CameraColors.ManualActive, style = MaterialTheme.typography.labelMedium)
        }
        // Facing: the one state that changes what the app is for. Rear is the norm and stays
        // untagged (UX_POLICY: keep the viewfinder quiet); FRONT is the exception and says so.
        if (state.facing == CameraFacing.FRONT) {
            Text(stringResource(R.string.osd_front), color = CameraColors.ManualActive, style = MaterialTheme.typography.labelMedium)
        } else if (state.activeCameraRoute == me.hletrd.telecampro.camera.CameraRoute.EXTERNAL) {
            Text(stringResource(R.string.osd_external), color = CameraColors.ManualActive, style = MaterialTheme.typography.labelMedium)
        }
        if (state.mode == CaptureMode.VIDEO) {
            if (!compact) {
                val spec = remember(
                    state.encodedVideoResolution, state.videoFrameRate, state.videoCodec, state.bitrateLevel,
                ) {
                    val encodedSize = state.encodedVideoResolution
                    val mbps = videoBitRate(
                        encodedSize.width, encodedSize.height,
                        state.videoFrameRate.encoderRate,
                        me.hletrd.telecampro.camera.effectiveBpp(state.bitrateLevel, state.videoCodec), state.videoCodec,
                    ) / 1_000_000
                    // A bare "M" is the finder convention for a bitrate; "Mb" named megabits, which
                    // is a quantity, not a rate. The menu's Encoder row spells the full "Mbps" —
                    // two registers on purpose, both now correct.
                    "${videoResolutionLabel(encodedSize)} ${videoFrameRateLabel(state.videoFrameRate)}p " +
                        "${videoCodecLabelShort(state.videoCodec)} ${mbps}M"
                }
                Text(spec, color = CameraColors.TextPrimary, style = MaterialTheme.typography.labelMedium)
            }
            if (!compact || state.transfer != ColorTransfer.SDR) {
                // The token, not a second blue: this tag used to be a raw 0xFF4C9AFF while the zoom
                // pill, TAP AF chip and Fn glyph rode CameraColors.Accent (#8AB4F8) — two blues 40
                // units apart for one meaning. Accent is the lighter of the two, so HUD contrast
                // rises. (The histogram's blue CHANNEL curve below stays a literal: it names a
                // colour channel, not a UI accent.)
                Text(transferLabelShort(state.transfer), color = CameraColors.Accent, style = MaterialTheme.typography.labelMedium)
            }
            if (state.transfer.isLog && state.gammaAssist) {
                // Gamma Display Assist active: the monitor is corrected, the file stays log.
                // Caps like every other alphabetic tag in this row (FRONT/MUTE/HR/AEL/LOUPE/SLOG3…);
                // Title Case is the MENU row convention, not the finder's.
                // Full white, like its neutral-white row-mates (the focal readout, the video spec): it
                // was the ONE alpha-modulated tag here, and dimming is this app's vocabulary for
                // UNAVAILABLE everywhere else (0.38 chrome glyphs, DISABLED_ROW_ALPHA, the 0.55 Fn
                // tile). A tag that only exists while the assist is ON must not wear the disabled
                // treatment. (The frame lines and grid are CameraColors.GuideLine at 0.40: graphics
                // over the image, not tags. The old note
                // here said "the level scale", which was never one of them; that gauge is 0.4.)
                Text(stringResource(R.string.osd_assist), color = CameraColors.TextPrimary, style = MaterialTheme.typography.labelMedium)
            }
            if (state.openGate) {
                Text("4:3", color = CameraColors.ManualActive, style = MaterialTheme.typography.labelMedium)
            }
            if (!state.recordAudio) {
                Text(stringResource(R.string.osd_mute), color = CameraColors.ManualActive, style = MaterialTheme.typography.labelMedium)
            }
            val stabTag = when (state.videoStabMode) {
                // One word family for one control (UI review #7): STANDARD used to read "OIS+",
                // borrowing the word of the SEPARATE photo "OIS OFF" tag, so across a mode flip two
                // independent controls read as one. STEADY stays — it is the marketing-free name
                // the ENHANCED value already owns app-wide.
                VideoStabMode.STANDARD -> stringResource(R.string.osd_stabilization_standard)
                VideoStabMode.ENHANCED -> stringResource(R.string.osd_stabilization_steady)
                VideoStabMode.OFF -> stringResource(R.string.osd_stabilization_off)
            }
            Text(
                stabTag,
                color = if (state.videoStabMode == VideoStabMode.OFF) CameraColors.ManualActive else CameraColors.StabActive,
                style = MaterialTheme.typography.labelMedium,
            )
        } else {
            if (!compact) {
                Text(
                    photoFormatLabel(state.photoFormats),
                    color = CameraColors.TextPrimary,
                    style = MaterialTheme.typography.labelMedium,
                )
            } else {
                compactPhotoFormatLabel(state)?.let { formatLabel ->
                    Text(formatLabel, color = CameraColors.ManualActive, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (state.photoSessionOutputs.hiRes) {
                // ACCEPTED-session truth, never the toggle intent (the ladder drops hi-res first) —
                // the same honesty rule as the finder PIP tag.
                Text(stringResource(R.string.osd_high_resolution), color = CameraColors.ManualActive, style = MaterialTheme.typography.labelMedium)
            }
            if (state.driveMode != DriveMode.SINGLE) {
                val driveLabel = when (state.driveMode) {
                    DriveMode.BURST -> stringResource(R.string.osd_drive_burst)
                    DriveMode.AEB -> "AEB±2"
                    DriveMode.TIMELAPSE -> "TL${state.intervalSec}s" // no space: the compound-tag family (T3s, AEB±2) writes tight (UI #30)
                    DriveMode.SINGLE -> ""
                }
                // A LIVE interval run reads in the tally red (review 2026-08-01: running vs armed
                // was previously indistinguishable — the run flag's only consumer was the screen
                // dim, and the shutter's stop role needs a visible state to act on). Same
                // state-belongs-in-the-OSD rule as REC/HR.
                val driveColor = if (state.driveMode == DriveMode.TIMELAPSE && state.timelapseRunning) {
                    CameraColors.Record
                } else {
                    CameraColors.ManualActive
                }
                Text(driveLabel, color = driveColor, style = MaterialTheme.typography.labelMedium)
            }
            // Shared gate with the compact-strip clause (oisOffTagVisible): on a route with no OIS
            // control the toggle's value is inert and announcing "OIS OFF" would claim a control
            // the lens does not have — the request builder applies the key only when
            // caps.oisAvailable (review L11 / verification S5).
            if (oisOffTagVisible(
                    photoMode = true,
                    oisAvailable = state.caps?.oisAvailable == true,
                    oisEnabled = state.controls.oisEnabled,
                )
            ) {
                Text(stringResource(R.string.osd_ois_off), color = CameraColors.ManualActive, style = MaterialTheme.typography.labelMedium)
            }
            if (!compact && state.timer != ShutterTimer.OFF) {
                Text("T${state.timer.seconds}s", color = CameraColors.ManualActive, style = MaterialTheme.typography.labelMedium)
            }
        }
        if (state.controls.meteringMode != MeteringMode.MATRIX) {
            Text(
                stringResource(
                    if (state.controls.meteringMode == MeteringMode.SPOT) R.string.osd_metering_spot
                    else R.string.osd_metering_center,
                ),
                color = CameraColors.TextPrimary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        // Lock states are togglable (Fn/hardware key) but had NO on-screen indicator — a locked AE
        // silently "ignoring" the scene reads as a broken camera. Amber tags, Sony-style, in the OSD
        // row per UX policy ("important states belong in the OSD").
        if (state.controls.aeLock) {
            Text(stringResource(R.string.osd_ael), color = CameraColors.ManualActive, style = MaterialTheme.typography.labelMedium)
        }
        if (state.controls.awbLock) {
            Text(stringResource(R.string.osd_awl), color = CameraColors.ManualActive, style = MaterialTheme.typography.labelMedium)
        }
        if (state.controls.afLock) {
            Text(stringResource(R.string.osd_afl), color = CameraColors.ManualActive, style = MaterialTheme.typography.labelMedium)
        }
        // Focus confidence: ONE compact amber tag whose text follows whichever proof holds —
        // TOO CLOSE (AF admitted defeat with the lens racked at its close limit, optionally
        // suffixed with a genuinely closer-focusing lens) or SOFT (the frame itself resolved no
        // fine detail, which cannot establish distance and so advises nothing). Same Sony-style
        // register as AEL/AFL; the wording rule and the 700 ms hold live in focus/MacroProximity.kt.
        val focusConfidenceTag = when (state.focusConfidence) {
            null -> null
            FocusConfidenceSource.FRAME_DETAIL -> stringResource(R.string.focus_confidence_soft)
            FocusConfidenceSource.AF_LIMIT -> state.macroCloserLens?.let {
                stringResource(R.string.focus_confidence_too_close_lens, lensLabel(it))
            } ?: stringResource(R.string.focus_confidence_too_close)
        }
        focusConfidenceTag?.let { tag ->
            Text(tag, color = CameraColors.ManualActive, style = MaterialTheme.typography.labelMedium)
        }
        if (state.punchInActive) {
            Text(stringResource(R.string.osd_loupe), color = CameraColors.ManualActive, style = MaterialTheme.typography.labelMedium)
        }
        if (teleFinderVisible(
                enabled = state.teleFinder,
                teleconverter = state.teleconverterMode,
                videoMode = state.mode == CaptureMode.VIDEO,
                aspect = state.aspectRatio,
                punchIn = state.punchInActive,
                zoomRatio = state.unifiedZoom,
            )
        ) {
            Text(stringResource(R.string.osd_overview), color = CameraColors.ManualActive, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * Luma + per-channel (R/G/B) 256-bin histogram curves. Draws an empty bordered frame when [data]
 * is null (e.g. before the first frame has been analyzed).
 */
@Composable
fun HistogramOverlay(data: HistogramData?, modifier: Modifier = Modifier) {
    val histogramLabel = stringResource(R.string.label_histogram)
    val histogramState = stringResource(
        when (histogramAccessibilityState(data)) {
            HistogramAccessibilityState.PENDING -> R.string.a11y_histogram_pending
            HistogramAccessibilityState.NO_EDGE_CLIPPING -> R.string.a11y_histogram_no_edge_clipping
            HistogramAccessibilityState.SHADOWS_CLIPPED -> R.string.a11y_histogram_shadows_clipped
            HistogramAccessibilityState.HIGHLIGHTS_CLIPPED -> R.string.a11y_histogram_highlights_clipped
            HistogramAccessibilityState.BOTH_CLIPPED -> R.string.a11y_histogram_both_clipped
        },
    )
    Box(
        modifier = modifier
            .size(width = 150.dp, height = 84.dp)
            // Scrim rides the tested HUD contrast floor (05486cb): the scopes exist to judge exposure
            // against bright/high-key scenes bleeding through the box, so the panel can't sit at the
            // old 0.55 — the darker plate also makes the thin luma/RGB traces read better, not worse.
            .background(HudPlate, RoundedCornerShape(8.dp))
            // Uniform 6 DELIBERATELY, not the 12/6 HUD pill inset — the same instrument-not-text
            // argument the ExposureMeter carries, and for the same measured reason. This box holds NO
            // text: its content is a framed Canvas whose bordering rect is drawn AT the Canvas edge and
            // whose data spans the full x (256 bins) and y (bin height) of that frame. So the padding
            // is not clearance protecting a glyph, it is the gap between the plate edge and the PLOT
            // FRAME; widening it to 12 would cut the 138 dp plot to 126 dp and drop ~9% of the
            // histogram's horizontal axis (the waveform's column axis likewise) to protect nothing.
            // Symmetric because the frame is symmetric: a 12/6 split would visibly seat the drawn
            // border nearer the plate edge top and bottom than left and right.
            .padding(6.dp)
            .semantics {
                contentDescription = histogramLabel
                stateDescription = histogramState
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(color = CameraColors.ScopeFrame, style = Stroke(width = 1.dp.toPx()))
            if (data != null) {
                // The LUMA channel's own colour, sibling to the R/G/B literals below — white because
                // luma is white, not because the HUD's ink is. It stays a literal for the same reason
                // they do (see the OSD transfer-tag note): these name signal channels, not UI roles.
                drawHistogramCurve(data.luma, Color.White.copy(alpha = 0.9f))
                drawHistogramCurve(data.red, Color(0xFFFF5252).copy(alpha = 0.75f))
                drawHistogramCurve(data.green, Color(0xFF4CD964).copy(alpha = 0.75f))
                drawHistogramCurve(data.blue, Color(0xFF4C9AFF).copy(alpha = 0.75f))
            }
        }
    }
}

internal enum class HistogramAccessibilityState {
    PENDING,
    NO_EDGE_CLIPPING,
    SHADOWS_CLIPPED,
    HIGHLIGHTS_CLIPPED,
    BOTH_CLIPPED,
}

internal fun histogramAccessibilityState(data: HistogramData?): HistogramAccessibilityState {
    val bins = data?.luma ?: return HistogramAccessibilityState.PENDING
    val total = bins.sumOf { it.coerceAtLeast(0).toLong() }
    if (bins.size < 256 || total <= 0L) return HistogramAccessibilityState.PENDING
    // computeHistogram publishes display-luma bins 0..255; only the exact endpoints prove clipping.
    val shadowClipped = bins.first() > 0
    val highlightClipped = bins.last() > 0
    return when {
        shadowClipped && highlightClipped -> HistogramAccessibilityState.BOTH_CLIPPED
        shadowClipped -> HistogramAccessibilityState.SHADOWS_CLIPPED
        highlightClipped -> HistogramAccessibilityState.HIGHLIGHTS_CLIPPED
        else -> HistogramAccessibilityState.NO_EDGE_CLIPPING
    }
}

private fun DrawScope.drawHistogramCurve(bins: IntArray, color: Color) {
    if (bins.size < 2) return
    val maxVal = (bins.maxOrNull() ?: 0).coerceAtLeast(1)
    val stepX = size.width / (bins.size - 1)
    val path = Path()
    for (i in bins.indices) {
        val x = i * stepX
        val y = size.height - (bins[i].toFloat() / maxVal) * size.height
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color = color, style = Stroke(width = 1.2.dp.toPx()))
}

/**
 * Luma waveform monitor: for each screen column, plots a vertical spread of points whose alpha is
 * proportional to that bucket's bin intensity (normalized against the frame's brightest bucket).
 * Draws an empty bordered frame when [data] is null (e.g. before the first frame has been analyzed).
 */
@Composable
fun WaveformOverlay(data: WaveformData?, modifier: Modifier = Modifier) {
    // Fixed compact box (matching the histogram's footprint) instead of a fraction of the screen
    // width, so it has a known extent, stacks cleanly under the histogram, and stays clear of the
    // top-bar settings glyph. Scrim rides the tested HUD contrast floor (05486cb) — same reasoning as
    // the histogram — with a brighter trace (below) so it reads at a glance over bright scenes.
    val waveformLabel = stringResource(R.string.label_waveform)
    val waveformRange = waveformAccessibilityRange(data)
    val waveformState = waveformRange?.let { range ->
        stringResource(R.string.a11y_waveform_luma_range, range.minimumPercent, range.maximumPercent)
    } ?: stringResource(R.string.a11y_waveform_pending)
    Box(
        modifier = modifier
            .width(150.dp)
            .height(84.dp)
            .background(HudPlate, RoundedCornerShape(8.dp))
            // Uniform 6, not 12/6, for the reason spelled out on [HistogramOverlay]: a text-free framed
            // instrument whose data reaches its own drawn border, so a pill's horizontal inset would
            // narrow the plot rather than protect a glyph. Kept identical to the histogram's on purpose
            // — they stack directly under one another in the same column.
            .padding(6.dp)
            .semantics {
                contentDescription = waveformLabel
                stateDescription = waveformState
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // [CameraColors.ScopeFrame], shared with the histogram above it. This site used to spell
            // 0.35f against the histogram's 0.3f for the same role; the device check that closed that
            // drift is recorded on the token.
            drawRect(color = CameraColors.ScopeFrame, style = Stroke(width = 1.dp.toPx()))
            if (data != null && data.columns > 0 && data.rows > 0) {
                drawWaveform(data)
            }
        }
    }
}

internal data class WaveformAccessibilityRange(
    val minimumPercent: Int,
    val maximumPercent: Int,
)

internal fun waveformAccessibilityRange(data: WaveformData?): WaveformAccessibilityRange? {
    if (
        data == null || data.columns <= 0 || data.rows <= 1 ||
        data.bins.size.toLong() != data.columns.toLong() * data.rows.toLong()
    ) {
        return null
    }
    var brightestRow = Int.MAX_VALUE
    var darkestRow = Int.MIN_VALUE
    for (column in 0 until data.columns) {
        for (row in 0 until data.rows) {
            if (data.bins[column * data.rows + row] <= 0) continue
            brightestRow = minOf(brightestRow, row)
            darkestRow = maxOf(darkestRow, row)
        }
    }
    if (brightestRow == Int.MAX_VALUE) return null
    fun percent(row: Int): Int =
        (((data.rows - 1 - row) * 100f / (data.rows - 1)) / 5f).roundToInt().times(5).coerceIn(0, 100)
    return WaveformAccessibilityRange(
        minimumPercent = percent(darkestRow),
        maximumPercent = percent(brightestRow),
    )
}

private fun DrawScope.drawWaveform(data: WaveformData) {
    val maxVal = (data.bins.maxOrNull() ?: 0).coerceAtLeast(1)
    val colWidth = size.width / data.columns
    val rowHeight = size.height / data.rows
    // Perf (PERF-5/AGG3-43): the batched-drawPoints version still boxed every populated cell —
    // Offset is a value class, so an `ArrayList<Offset>` per bucket boxed up to columns×rows (~8k)
    // Offsets per redraw at ~6 Hz (~1.5 MB/s garbage on main). Bucket the same √ alpha ramp into
    // primitive FloatArrays of interleaved x,y pairs and hand each straight to the native
    // Canvas.drawPoints(float[]) — zero per-point boxing, identical round-cap dots in ~8 draw ops.
    val alphaBuckets = 8
    val counts = IntArray(alphaBuckets)
    for (col in 0 until data.columns) {
        for (row in 0 until data.rows) {
            val value = data.bins[col * data.rows + row]
            if (value <= 0) continue
            counts[waveformAlphaBucket(value, maxVal, alphaBuckets)]++
        }
    }
    val coords = Array(alphaBuckets) { FloatArray(counts[it] * 2) } // FloatArray = primitive, no boxing
    val next = IntArray(alphaBuckets)
    for (col in 0 until data.columns) {
        val x = col * colWidth + colWidth / 2f
        for (row in 0 until data.rows) {
            val value = data.bins[col * data.rows + row]
            if (value <= 0) continue
            val bucket = waveformAlphaBucket(value, maxVal, alphaBuckets)
            val i = next[bucket]
            coords[bucket][i] = x
            coords[bucket][i + 1] = row * rowHeight + rowHeight / 2f
            next[bucket] = i + 2
        }
    }
    val diameter = 3.2.dp.toPx() // stroke width == the old 1.6 dp-radius circles
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND // round dots, like the old drawCircle points
        strokeWidth = diameter
        color = android.graphics.Color.rgb(0x8B, 0xFF, 0xA8) // brighter green for contrast against the scrim
    }
    val canvas = drawContext.canvas.nativeCanvas
    for (bucket in 0 until alphaBuckets) {
        if (counts[bucket] == 0) continue
        val alpha = (0.4f + 0.6f * (bucket / (alphaBuckets - 1f))).coerceIn(0f, 1f)
        paint.alpha = (alpha * 255f).roundToInt()
        canvas.drawPoints(coords[bucket], paint)
    }
}

/**
 * Maps a bucket cell's [value] to an alpha bucket index. The old linear alpha (value/max) left
 * low-count buckets nearly invisible; a floor + √ curve lifts them so any populated bucket paints
 * clearly (QA: "waveform too faint"). Pulled out so both the counting and the fill pass agree.
 */
internal fun waveformAlphaBucket(value: Int, maxVal: Int, alphaBuckets: Int): Int {
    val norm = (value.toFloat() / maxVal).coerceIn(0f, 1f)
    return (kotlin.math.sqrt(norm) * (alphaBuckets - 1)).toInt().coerceIn(0, alphaBuckets - 1)
}

/**
 * Big centered self-timer countdown number, shown while a shutter delay is counting down.
 * [rotationDegrees] counter-rotates the digit so it stays upright in a landscape hold (wired by the
 * sole call site from the device orientation); the 0f default is the screen-fixed identity. The
 * centered text owns the polite countdown semantics so accessibility bounds follow the visible
 * readout instead of covering the whole touch-to-cancel surface around it.
 */
@Composable
fun TimerCountdown(
    seconds: Int,
    accessibilityLabel: String,
    accessibilityStateDescription: String,
    modifier: Modifier = Modifier,
    rotationDegrees: Float = 0f,
) {
    if (seconds <= 0) return
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = seconds.toString(),
            color = CameraColors.TextPrimary,
            // The line box and tracking must scale WITH the 120 sp override. Copying only fontSize
            // left the 57 sp role's own line box under a 120 sp glyph — roughly HALF the glyph's
            // size, so the digit is not centered in its measured box and Text's default Clip can eat
            // the ascender — plus tracking scaled for 57 sp on a 120 sp numeral. -2.7 sp is Inter's
            // own optical curve at 120 sp; the 120 sp itself is deliberate (see the call site).
            style = MaterialTheme.typography.displayLarge.copy(
                fontSize = 120.sp,
                lineHeight = 126.sp,
                letterSpacing = (-2.7).sp,
            ),
            modifier = Modifier
                .rotate(rotationDegrees)
                .clearAndSetSemantics {
                    contentDescription = accessibilityLabel
                    stateDescription = accessibilityStateDescription
                    liveRegion = LiveRegionMode.Polite
                },
        )
    }
}
