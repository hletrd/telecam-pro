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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hletrd.findx9tele.camera.ColorTransfer
import com.hletrd.findx9tele.camera.GridType
import com.hletrd.findx9tele.camera.HistogramData
import com.hletrd.findx9tele.camera.PhotoFormats
import com.hletrd.findx9tele.camera.WaveformData
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
 * Horizon/level indicator. A static reference line marks true-horizontal; the [rollDegrees] line
 * rotates with device roll and turns green once within a small tolerance of level.
 */
@Composable
fun LevelOverlay(rollDegrees: Float = 0f, modifier: Modifier = Modifier) {
    val isLevel = abs(rollDegrees) < 0.5f
    val indicatorColor = if (isLevel) Color(0xFF4CD964) else Color.White
    Canvas(modifier = modifier.fillMaxSize()) {
        val cy = size.height / 2f
        val halfSpan = size.width * 0.16f
        drawLine(
            color = Color.White.copy(alpha = 0.4f),
            start = Offset(size.width / 2f - halfSpan, cy),
            end = Offset(size.width / 2f + halfSpan, cy),
            strokeWidth = 2.dp.toPx(),
        )
        rotate(degrees = rollDegrees, pivot = Offset(size.width / 2f, cy)) {
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
 * Top status strip: active camera id, 35mm-equivalent focal length (annotated when the afocal
 * teleconverter is engaged), enabled photo formats, transfer function, and an EIS tag.
 */
@Composable
fun StatusBar(
    cameraLabel: String,
    equivFocalMm: Float,
    teleconverter: Boolean,
    transfer: ColorTransfer,
    photoFormats: PhotoFormats,
    eis: Boolean,
    modifier: Modifier = Modifier,
) {
    val formatLabel = buildString {
        if (photoFormats.heif) append("HEIF")
        if (photoFormats.dngRaw) {
            if (isNotEmpty()) append("+")
            append("DNG")
        }
        if (isEmpty()) append("-")
    }
    val focalLabel = when {
        equivFocalMm <= 0f -> "--"
        teleconverter -> "%.0fmm 텔레".format(equivFocalMm)
        else -> "%.0fmm".format(equivFocalMm)
    }
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(cameraLabel, color = Color.White, style = MaterialTheme.typography.labelMedium)
        Text(focalLabel, color = Color.White, style = MaterialTheme.typography.labelMedium)
        Text(formatLabel, color = Color.White, style = MaterialTheme.typography.labelMedium)
        Text(transfer.name, color = Color(0xFF4C9AFF), style = MaterialTheme.typography.labelMedium)
        if (eis) {
            Text("EIS", color = Color(0xFF4CD964), style = MaterialTheme.typography.labelMedium)
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
    Box(
        modifier = modifier
            .fillMaxWidth(0.3f)
            .height(90.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .padding(6.dp),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(color = Color.White.copy(alpha = 0.3f), style = Stroke(width = 1.dp.toPx()))
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
    val color = Color(0xFF4CD964)
    for (col in 0 until data.columns) {
        val x = col * colWidth + colWidth / 2f
        for (row in 0 until data.rows) {
            val value = data.bins[col * data.rows + row]
            if (value <= 0) continue
            val alpha = (value.toFloat() / maxVal).coerceIn(0f, 1f)
            val y = row * rowHeight + rowHeight / 2f
            drawCircle(color = color.copy(alpha = alpha), radius = 1.2.dp.toPx(), center = Offset(x, y))
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
