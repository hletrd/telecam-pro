package com.hletrd.findx9tele.ui.overlays

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.hletrd.findx9tele.camera.ColorTransfer
import com.hletrd.findx9tele.camera.PhotoFormats
import kotlin.math.abs

/**
 * Rule-of-thirds composition grid. Purely decorative; visibility is gated by [CameraUiState.grid]
 * in the caller.
 */
@Composable
fun GridOverlay(modifier: Modifier = Modifier) {
    val lineColor = Color.White.copy(alpha = 0.55f)
    Canvas(modifier = modifier.fillMaxSize()) {
        val strokeWidth = 1.dp.toPx()
        val x1 = size.width / 3f
        val x2 = 2f * size.width / 3f
        val y1 = size.height / 3f
        val y2 = 2f * size.height / 3f
        drawLine(lineColor, Offset(x1, 0f), Offset(x1, size.height), strokeWidth)
        drawLine(lineColor, Offset(x2, 0f), Offset(x2, size.height), strokeWidth)
        drawLine(lineColor, Offset(0f, y1), Offset(size.width, y1), strokeWidth)
        drawLine(lineColor, Offset(0f, y2), Offset(size.width, y2), strokeWidth)
    }
}

/**
 * Horizon/level indicator. A static reference line marks true-horizontal; the [rollDegrees] line
 * rotates with device roll and turns green once within a small tolerance of level. Defaults to a
 * centered, always-level draw since no sensor is wired up yet.
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

/** Red recording dot + elapsed mm:ss, shown while [CameraUiState.isRecording] is true. */
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

/** Top status strip: active camera id, focal length, enabled photo formats, and transfer function. */
@Composable
fun StatusBar(
    cameraLabel: String,
    focalMm: Float,
    transfer: ColorTransfer,
    photoFormats: PhotoFormats,
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
    val focalLabel = if (focalMm > 0f) "%.0fmm".format(focalMm) else "--"
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
    }
}
