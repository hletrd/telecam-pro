package com.hletrd.findx9tele.ui.overlays

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hletrd.findx9tele.BuildConfig
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
import kotlin.math.abs

/**
 * Composition grid, drawn per [GridType]. Purely decorative; visibility/style is entirely driven
 * by the [type] argument (NONE draws nothing).
 */
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
 * Crop mask for non-[AspectRatio.FULL] capture ratios: dims the sensor area outside
 * [ratio]'s w:h box with semi-opaque black bars (letterboxed top/bottom or pillarboxed
 * left/right, whichever the view's own aspect requires) so the framed area is obvious in the
 * viewfinder. Draws nothing for [AspectRatio.FULL].
 */
@Composable
fun AspectMask(ratio: AspectRatio, modifier: Modifier = Modifier) {
    if (ratio == AspectRatio.W4_3) return // full sensor = no crop mask
    val barColor = Color.Black.copy(alpha = 0.5f)
    Canvas(modifier = modifier.fillMaxSize()) {
        val targetAspect = ratio.w.toFloat() / ratio.h.toFloat()
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
 * Horizon/level indicator. A static reference line marks true-horizontal; the [rollDegrees] line
 * rotates with device roll and turns YELLOW (Sony style) once within a small tolerance of level. In
 * a landscape hold the gauge stays horizontal on screen: deviation is measured against the current
 * held quadrant ([deviceOrientation], the task-8 gravity orientation), so "level" means square to
 * the horizon in whatever way the phone is being held.
 */
@Composable
fun LevelOverlay(modifier: Modifier = Modifier, rollDegrees: Float = 0f, deviceOrientation: Int = 0) {
    // Deviation from the CURRENT held orientation (the gravity quadrant) rather than raw roll: a
    // landscape hold reads ±90° raw and would never show level, but the photographer's question is
    // "am I square to the horizon in THIS hold" — captures auto-rotate per quadrant. Normalized to
    // (-180, 180].
    var deviation = rollDegrees - deviceOrientation
    deviation = ((deviation + 180f) % 360f + 360f) % 360f - 180f
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
fun FocusReticle(point: Pair<Float, Float>?, modifier: Modifier = Modifier) {
    if (point == null) return
    val color = Color(0xFFFFD60A)
    Canvas(modifier = modifier) {
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
    val timeLabel = "%02d:%02d".format(minutes, seconds)
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50)),
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
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(4.dp)),
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
    val focalLabel = when {
        focal <= 0f -> "--"
        state.teleconverterMode -> "%.0fmm TELE".format(focal)
        else -> "%.0fmm".format(focal)
    }
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(focalLabel, color = Color.White, style = MaterialTheme.typography.labelMedium)
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
        if (state.videoStabMode != VideoStabMode.OFF) {
            val stabTag = when (state.videoStabMode) {
                VideoStabMode.STANDARD -> "OIS+"
                VideoStabMode.ENHANCED -> "STEADY"
                VideoStabMode.OFF -> ""
            }
            Text(stabTag, color = Color(0xFF4CD964), style = MaterialTheme.typography.labelMedium)
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
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
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
    // top-bar settings glyph. Darker scrim + brighter trace (below) so it reads at a glance.
    Box(
        modifier = modifier
            .width(150.dp)
            .height(84.dp)
            .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(8.dp))
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
    val color = Color(0xFF8BFFA8) // brighter green than before for contrast against the scrim
    for (col in 0 until data.columns) {
        val x = col * colWidth + colWidth / 2f
        for (row in 0 until data.rows) {
            val value = data.bins[col * data.rows + row]
            if (value <= 0) continue
            // The old linear alpha (value/max) left low-count buckets nearly invisible. Lift them with
            // a floor + √ curve so any populated bucket paints clearly (QA: "waveform too faint").
            val norm = (value.toFloat() / maxVal).coerceIn(0f, 1f)
            val alpha = (0.4f + 0.6f * kotlin.math.sqrt(norm)).coerceIn(0f, 1f)
            val y = row * rowHeight + rowHeight / 2f
            drawCircle(color = color.copy(alpha = alpha), radius = 1.6.dp.toPx(), center = Offset(x, y))
        }
    }
}

/** Big centered self-timer countdown number, shown while a shutter delay is counting down. */
@Composable
fun TimerCountdown(seconds: Int, modifier: Modifier = Modifier) {
    if (seconds <= 0) return
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = seconds.toString(),
            color = Color.White,
            style = MaterialTheme.typography.displayLarge.copy(fontSize = 120.sp),
        )
    }
}
