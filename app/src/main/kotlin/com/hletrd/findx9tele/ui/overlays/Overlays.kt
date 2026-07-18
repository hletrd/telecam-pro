package com.hletrd.findx9tele.ui.overlays

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hletrd.findx9tele.BuildConfig
import com.hletrd.findx9tele.camera.AfIndication
import com.hletrd.findx9tele.camera.AspectRatio
import com.hletrd.findx9tele.camera.CameraUiState
import com.hletrd.findx9tele.camera.CaptureMode
import com.hletrd.findx9tele.camera.DriveMode
import com.hletrd.findx9tele.camera.GridType
import com.hletrd.findx9tele.camera.HistogramData
import com.hletrd.findx9tele.camera.MeteringMode
import com.hletrd.findx9tele.camera.ShutterTimer
import com.hletrd.findx9tele.camera.VideoStabMode
import com.hletrd.findx9tele.camera.WaveformData
import com.hletrd.findx9tele.camera.videoBitRate
import com.hletrd.findx9tele.ui.controls.transferLabelShort
import com.hletrd.findx9tele.ui.controls.videoCodecLabelShort
import com.hletrd.findx9tele.ui.controls.videoResolutionLabel
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Small HUD text must remain readable over a white frame. At 82% black, opaque secondary text
 * (#9E9E9E) retains >5:1 contrast and the blue status accent retains >4.7:1.
 */
internal const val HUD_TEXT_SCRIM_ALPHA = 0.82f

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
 * Composition grid, drawn per [GridType]. Purely decorative; visibility/style is entirely driven
 * by the [type] argument (NONE draws nothing).
 */
/**
 * Sony "Frame Lines": a centered marker box of the delivery aspect (2.39:1 / 1:1 / 9:16), fitted to
 * the viewfinder, for judging a crop that will happen in post.
 */
@Composable
fun FrameLinesOverlay(type: com.hletrd.findx9tele.camera.FrameLineType, modifier: Modifier = Modifier) {
    val ratio = type.ratio ?: return
    Canvas(modifier = modifier) {
        var w = size.width
        var h = w / ratio
        if (h > size.height) {
            h = size.height
            w = h * ratio
        }
        drawRect(
            color = Color.White.copy(alpha = 0.55f),
            topLeft = Offset((size.width - w) / 2f, (size.height - h) / 2f),
            size = androidx.compose.ui.geometry.Size(w, h),
            style = Stroke(width = 1.2.dp.toPx()),
        )
    }
}

@Composable
fun GridOverlay(type: GridType, modifier: Modifier = Modifier) {
    if (type == GridType.NONE) return
    val lineColor = Color.White.copy(alpha = 0.55f)
    Canvas(modifier = modifier.fillMaxSize()) {
        val strokeWidth = 1.dp.toPx()
        when (type) {
            GridType.THIRDS -> drawThirdsGrid(lineColor, strokeWidth)
            GridType.GOLDEN -> drawGoldenGrid(lineColor, strokeWidth)
            GridType.SQUARE -> drawSquareGrid(lineColor, strokeWidth)
            GridType.CENTER -> drawCenterMark(lineColor, strokeWidth)
            GridType.NONE -> Unit
        }
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
        // The mask boxes the crop AS DISPLAYED, and the ~90° sensor orientation swaps W/H on this
        // portrait-locked screen (same rule as the engine's preview-aspect report): a 16:9 crop of
        // the 4:3 sensor keeps the long side — vertical on screen — and narrows the short side, so
        // inside the 3:4 viewfinder the bars land LEFT/RIGHT. The pre-letterbox code used w/h and
        // shaded top/bottom, marking a region that was NOT what the shutter would save.
        val targetAspect = ratio.h.toFloat() / ratio.w.toFloat()
        val viewAspect = size.width / size.height
        if (viewAspect > targetAspect) {
            // View is wider than the target box: bar off the left/right edges.
            val frameWidth = size.height * targetAspect
            val barWidth = (size.width - frameWidth) / 2f
            drawRect(color = barColor, topLeft = Offset.Zero, size = Size(barWidth, size.height))
            drawRect(color = barColor, topLeft = Offset(size.width - barWidth, 0f), size = Size(barWidth, size.height))
        } else {
            // View is taller than the target box: bar off the top/bottom edges.
            val frameHeight = size.width / targetAspect
            val barHeight = (size.height - frameHeight) / 2f
            drawRect(color = barColor, topLeft = Offset.Zero, size = Size(size.width, barHeight))
            drawRect(color = barColor, topLeft = Offset(0f, size.height - barHeight), size = Size(size.width, barHeight))
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
    val indicatorColor = if (isLevel) Color(0xFFFFD60A) else Color.White
    Canvas(modifier = modifier.fillMaxSize()) {
        val cy = size.height / 2f
        val halfSpan = size.width * 0.16f
        drawLine(
            color = Color.White.copy(alpha = 0.4f),
            start = Offset(size.width / 2f - halfSpan, cy),
            end = Offset(size.width / 2f + halfSpan, cy),
            strokeWidth = 2.dp.toPx(),
        )
        rotate(degrees = deviation, pivot = Offset(size.width / 2f, cy)) {
            drawLine(
                color = indicatorColor,
                start = Offset(size.width / 2f - halfSpan, cy),
                end = Offset(size.width / 2f + halfSpan, cy),
                strokeWidth = 4.dp.toPx(),
            )
        }
        drawCircle(color = indicatorColor, radius = 4.dp.toPx(), center = Offset(size.width / 2f, cy))
    }
}

/**
 * Tap-to-focus reticle: a small yellow bracketed square centered at [point] (view-normalized
 * 0..1 coordinates). Draws nothing while [point] is null (e.g. after the auto-hide timeout).
 */
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
        AfIndication.FOCUSED -> Color(0xFF30D158)
        AfIndication.FAILED -> Color(0xFFFF453A)
        AfIndication.SCANNING, AfIndication.IDLE -> Color(0xFFFFD60A)
    }
    val focusDescription = when (indication) {
        AfIndication.FOCUSED -> "Focus locked"
        AfIndication.FAILED -> "Autofocus failed"
        AfIndication.SCANNING -> "Autofocus searching"
        AfIndication.IDLE -> "Focus point"
    }
    Canvas(modifier = modifier.semantics { contentDescription = focusDescription }) {
        val cx = point.first * size.width
        val cy = point.second * size.height
        val half = 32.dp.toPx()
        val corner = 10.dp.toPx()
        val strokeWidth = 2.dp.toPx()
        val left = cx - half
        val right = cx + half
        val top = cy - half
        val bottom = cy + half
        drawLine(color, Offset(left, top), Offset(left + corner, top), strokeWidth)
        drawLine(color, Offset(left, top), Offset(left, top + corner), strokeWidth)
        drawLine(color, Offset(right, top), Offset(right - corner, top), strokeWidth)
        drawLine(color, Offset(right, top), Offset(right, top + corner), strokeWidth)
        drawLine(color, Offset(left, bottom), Offset(left + corner, bottom), strokeWidth)
        drawLine(color, Offset(left, bottom), Offset(left, bottom - corner), strokeWidth)
        drawLine(color, Offset(right, bottom), Offset(right - corner, bottom), strokeWidth)
        drawLine(color, Offset(right, bottom), Offset(right, bottom - corner), strokeWidth)
    }
}

/** Red recording dot + elapsed mm:ss, shown while [com.hletrd.findx9tele.camera.CameraUiState.isRecording] is true. */
@Composable
fun RecordingIndicator(elapsedMs: Long, modifier: Modifier = Modifier) {
    val totalSeconds = elapsedMs / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val timeLabel = "%02d:%02d".format(Locale.US, minutes, seconds)
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = HUD_TEXT_SCRIM_ALPHA), RoundedCornerShape(50))
            // Keep a stable REC description; elapsed telemetry must not be re-announced every second.
            .clearAndSetSemantics { contentDescription = "Recording" },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.width(2.dp))
        Canvas(modifier = Modifier.size(10.dp)) {
            drawCircle(color = Color(0xFFFF3B30))
        }
        Text(
            text = timeLabel,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(end = 10.dp, top = 4.dp, bottom = 4.dp),
        )
    }
}

/**
 * Thin horizontal audio input-level meter (0..1). Fill color shifts green -> yellow -> red as
 * [level] approaches clipping, so it doubles as a basic peak warning while recording.
 */
@Composable
fun AudioMeter(level: Float, modifier: Modifier = Modifier) {
    val fill = level.coerceIn(0f, 1f)
    val fillColor = when {
        fill < 0.6f -> Color(0xFF4CD964)
        fill < 0.85f -> Color(0xFFFFD60A)
        else -> Color(0xFFFF3B30)
    }
    Box(
        modifier = modifier
            .size(width = 120.dp, height = 8.dp)
            // Rides the tested HUD contrast floor (05486cb) like the OSD pills: the meter reads level
            // against bright scenes, so its scrim can't be the near-transparent 0.45 it was.
            .background(Color.Black.copy(alpha = HUD_TEXT_SCRIM_ALPHA), RoundedCornerShape(4.dp)),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (fill > 0f) {
                drawRect(color = fillColor, size = Size(size.width * fill, size.height))
            }
        }
    }
}

/**
 * Top status strip — the Sony-style shooting OSD. Mode-aware so it only shows what affects the
 * NEXT shot in the current mode:
 *  - PHOTO: 35mm-equivalent focal (tagged TELE through the converter), still formats, drive mode
 *    (when not single-shot) and self-timer (when armed).
 *  - VIDEO: focal, the resolved recording spec (resolution · fps · codec · Mbps — what the encoder
 *    will actually write), and the transfer function.
 *  - Both: metering pattern (when not matrix) and an EIS tag. The raw camera id is appended on
 *    DEBUG builds only — it is a lens bring-up aid, noise to a photographer.
 */
@Composable
fun StatusBar(state: CameraUiState, modifier: Modifier = Modifier) {
    val focal = state.caps?.equivalentFocalMm ?: 0f
    // The afocal teleconverter multiplies the ~70 mm periscope → a ~300 mm effective focal.
    // Round to the nearest 10 mm so the readout reads a clean "300mm" rather than 296.
    // TELE effective focal follows the digital zoom on the NOMINAL 300 mm base (constant scale,
    // matching the 13/30/60× pill marks): 300 mm at 13×, 690 at 30×, 1380 at 60×.
    val effFocal = ((300f * state.controls.zoomRatio.coerceAtLeast(1f)) / 10f).roundToInt() * 10
    val focalLabel = when {
        focal <= 0f -> "--"
        state.teleconverterMode -> "${effFocal}mm TELE"
        // Seamless zoom: the logical camera's equiv focal is the MAIN lens's (23 mm) and the unified
        // zoom is main-relative, so the EFFECTIVE focal is their product — 14 mm at 0.6×, 230 mm at
        // 10× — tracking the lens the HAL actually has active, like the TELE readout does.
        else -> "%.0fmm".format(Locale.US, focal * state.controls.zoomRatio.coerceAtLeast(0.01f))
    }
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = HUD_TEXT_SCRIM_ALPHA), RoundedCornerShape(8.dp))
            // Sony bodies paginate their status strip; with many concurrent tags (AEL/AWL/AFL/LOUPE/…)
            // trailing tags would run off-screen, so scroll keeps every lock tag reachable.
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(focalLabel, color = Color.White, style = MaterialTheme.typography.labelMedium)
        state.activeMemorySlot?.let {
            Text(it.label, color = Color(0xFFFFD60A), style = MaterialTheme.typography.labelMedium)
        }
        if (state.mode == CaptureMode.VIDEO) {
            val mbps = videoBitRate(
                state.videoResolution.width, state.videoResolution.height,
                state.videoFrameRate.encoderRate,
                com.hletrd.findx9tele.camera.effectiveBpp(state.bitrateLevel, state.videoCodec), state.videoCodec,
            ) / 1_000_000
            Text(
                "${videoResolutionLabel(state.videoResolution)} ${state.videoFrameRate.label}p " +
                    "${videoCodecLabelShort(state.videoCodec)} ${mbps}Mb",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(transferLabelShort(state.transfer), color = Color(0xFF4C9AFF), style = MaterialTheme.typography.labelMedium)
            if (state.transfer == com.hletrd.findx9tele.camera.ColorTransfer.LOG && state.gammaAssist) {
                // Gamma Display Assist active: the monitor is corrected, the file stays log.
                Text("Assist", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelMedium)
            }
        } else {
            val formatLabel = buildString {
                if (state.photoFormats.heif) append("HEIF")
                if (state.photoFormats.jpeg) {
                    if (isNotEmpty()) append("+")
                    append("JPEG")
                }
                if (state.photoFormats.dngRaw) {
                    if (isNotEmpty()) append("+")
                    append("DNG")
                }
                if (isEmpty()) append("-")
            }
            Text(formatLabel, color = Color.White, style = MaterialTheme.typography.labelMedium)
            if (state.driveMode != DriveMode.SINGLE) {
                val driveLabel = when (state.driveMode) {
                    DriveMode.BURST -> "BURST"
                    DriveMode.AEB -> "AEB±2"
                    DriveMode.TIMELAPSE -> "TL ${state.intervalSec}s"
                    DriveMode.SINGLE -> ""
                }
                Text(driveLabel, color = Color(0xFFFFD60A), style = MaterialTheme.typography.labelMedium)
            }
            if (state.timer != ShutterTimer.OFF) {
                Text("T${state.timer.seconds}s", color = Color(0xFFFFD60A), style = MaterialTheme.typography.labelMedium)
            }
        }
        if (state.controls.meteringMode != MeteringMode.MATRIX) {
            Text(
                if (state.controls.meteringMode == MeteringMode.SPOT) "SPOT" else "CENTER",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        // Lock states are togglable (Fn/hardware key) but had NO on-screen indicator — a locked AE
        // silently "ignoring" the scene reads as a broken camera. Amber tags, Sony-style, in the OSD
        // row per UX policy ("important states belong in the OSD").
        if (state.controls.aeLock) {
            Text("AEL", color = Color(0xFFFFD60A), style = MaterialTheme.typography.labelMedium)
        }
        if (state.controls.awbLock) {
            Text("AWL", color = Color(0xFFFFD60A), style = MaterialTheme.typography.labelMedium)
        }
        if (state.controls.afLock) {
            Text("AFL", color = Color(0xFFFFD60A), style = MaterialTheme.typography.labelMedium)
        }
        if (state.videoStabMode != VideoStabMode.OFF) {
            val stabTag = when (state.videoStabMode) {
                VideoStabMode.STANDARD -> "OIS+"
                VideoStabMode.ENHANCED -> "STEADY"
                VideoStabMode.OFF -> ""
            }
            Text(stabTag, color = Color(0xFF4CD964), style = MaterialTheme.typography.labelMedium)
        }
        if (state.punchIn) {
            Text("LOUPE", color = Color(0xFFFFD60A), style = MaterialTheme.typography.labelMedium)
        }
        // Like LOUPE: shown whenever the toggle is ON, independent of the current gate (TELE,
        // photo, 4:3, zoom floor) — "on but gated off" was otherwise indistinguishable from "off",
        // and the toggle read as broken when flipped in 16:9 or video.
        if (state.teleFinder) {
            Text("PIP", color = Color(0xFFFFD60A), style = MaterialTheme.typography.labelMedium)
        }
        if (BuildConfig.DEBUG) {
            val caps = state.caps
            val cameraLabel = when {
                caps == null -> "-"
                caps.physicalId != null -> "${caps.logicalId}:${caps.physicalId}"
                else -> caps.logicalId
            }
            Text(cameraLabel, color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * Luma + per-channel (R/G/B) 256-bin histogram curves. Draws an empty bordered frame when [data]
 * is null (e.g. before the first frame has been analyzed).
 */
@Composable
fun HistogramOverlay(data: HistogramData?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(width = 150.dp, height = 84.dp)
            // Scrim rides the tested HUD contrast floor (05486cb): the scopes exist to judge exposure
            // against bright/high-key scenes bleeding through the box, so the panel can't sit at the
            // old 0.55 — the darker plate also makes the thin luma/RGB traces read better, not worse.
            .background(Color.Black.copy(alpha = HUD_TEXT_SCRIM_ALPHA), RoundedCornerShape(8.dp))
            .padding(6.dp),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(color = Color.White.copy(alpha = 0.3f), style = Stroke(width = 1.dp.toPx()))
            if (data != null) {
                drawHistogramCurve(data.luma, Color.White.copy(alpha = 0.9f))
                drawHistogramCurve(data.red, Color(0xFFFF5252).copy(alpha = 0.75f))
                drawHistogramCurve(data.green, Color(0xFF4CD964).copy(alpha = 0.75f))
                drawHistogramCurve(data.blue, Color(0xFF4C9AFF).copy(alpha = 0.75f))
            }
        }
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
    Box(
        modifier = modifier
            .width(150.dp)
            .height(84.dp)
            .background(Color.Black.copy(alpha = HUD_TEXT_SCRIM_ALPHA), RoundedCornerShape(8.dp))
            .padding(6.dp),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(color = Color.White.copy(alpha = 0.35f), style = Stroke(width = 1.dp.toPx()))
            if (data != null && data.columns > 0 && data.rows > 0) {
                drawWaveform(data)
            }
        }
    }
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
 * call site from the device orientation); the 0f default keeps existing callers screen-fixed.
 */
@Composable
fun TimerCountdown(seconds: Int, modifier: Modifier = Modifier, rotationDegrees: Float = 0f) {
    if (seconds <= 0) return
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = seconds.toString(),
            color = Color.White,
            style = MaterialTheme.typography.displayLarge.copy(fontSize = 120.sp),
            modifier = Modifier.rotate(rotationDegrees),
        )
    }
}
