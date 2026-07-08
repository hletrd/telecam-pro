package com.hletrd.findx9tele.ui.review

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hletrd.findx9tele.ui.theme.CameraColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * In-app review of the last saved still — the piece that makes manual focus at 300 mm usable, since
 * you can pinch to 100 %+ and confirm the shot nailed focus before the subject moves, without leaving
 * for the system gallery. Stills only (HEIF/JPEG); DNG/video aren't decoded for the quick check.
 */

/** Decodes [uri] downsampled so its longest side is ~[maxDim] px (<=0 = full resolution). */
private suspend fun loadBitmap(context: Context, uri: Uri, maxDim: Int): ImageBitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            if (maxDim > 0) {
                val longest = max(bounds.outWidth, bounds.outHeight)
                while (longest / sample > maxDim) sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }?.asImageBitmap()
        }.getOrNull()
    }

private data class ReviewMetadata(
    val name: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
)

private suspend fun loadMetadata(context: Context, uri: Uri): ReviewMetadata? =
    withContext(Dispatchers.IO) {
        runCatching {
            val columns = arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.WIDTH,
                MediaStore.MediaColumns.HEIGHT,
            )
            context.contentResolver.query(uri, columns, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                ReviewMetadata(
                    name = c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)),
                    sizeBytes = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)),
                    width = c.getInt(c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)),
                    height = c.getInt(c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)),
                )
            }
        }.getOrNull()
    }

/** Tappable thumbnail of the last saved still; a placeholder frame until one exists. */
@Composable
fun GalleryThumb(uri: Uri?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val thumb by produceState<ImageBitmap?>(initialValue = null, uri) {
        value = uri?.let { loadBitmap(context, it, 240) }
    }
    Box(
        modifier = modifier
            .size(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(CameraColors.Pill)
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(14.dp))
            .semantics {
                contentDescription = if (uri != null) "Review last shot" else "No shot to review"
                role = Role.Button
            }
            .clickable(enabled = uri != null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val t = thumb
        if (t != null) {
            Image(
                bitmap = t,
                contentDescription = "Review last shot",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Canvas(Modifier.size(22.dp)) {
                val color = CameraColors.TextSecondary
                drawCircle(color, radius = size.minDimension * 0.12f, center = Offset(size.width * 0.32f, size.height * 0.36f))
            }
        }
    }
}

/**
 * Fullscreen pinch-to-zoom review of [uri]. Loads full resolution for pixel-level focus checking
 * (falls back to a large downsample on failure).
 */
@Composable
fun MediaReviewOverlay(uri: Uri, onClose: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, uri) {
        value = loadBitmap(context, uri, 0) ?: loadBitmap(context, uri, 3000)
    }
    val metadata by produceState<ReviewMetadata?>(initialValue = null, uri) {
        value = loadMetadata(context, uri)
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var confirmDelete by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 12f)
                    offset = if (scale > 1f) offset + pan else Offset.Zero
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 4f
                }, onLongPress = { tap ->
                    scale = 8f
                    val center = Offset(size.width / 2f, size.height / 2f)
                    offset = (center - tap) * scale
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = "Review",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    ),
            )
        } else {
            Text("Loading…", color = CameraColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
        }

        // Zoom readout (multiplier vs. fit), top-center.
        Text(
            text = "%.1f×".format(scale),
            color = CameraColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )

        ZoomPresetStrip(
            scale = scale,
            onScale = {
                scale = it
                offset = Offset.Zero
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 54.dp),
        )

        metadata?.let { meta ->
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(14.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .widthIn(max = 280.dp)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(meta.name, color = CameraColors.TextPrimary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${meta.width}×${meta.height} · ${formatBytes(meta.sizeBytes)}",
                    color = CameraColors.TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        // Close button, top-left.
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(12.dp)
                .size(44.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.5f))
                .semantics {
                    contentDescription = "Close review"
                    role = Role.Button
                }
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Text("✕", color = CameraColors.TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(12.dp)
                .size(44.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.5f))
                .semantics {
                    contentDescription = "Delete shot"
                    role = Role.Button
                }
                .clickable { confirmDelete = true },
            contentAlignment = Alignment.Center,
        ) {
            Text("DEL", color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete shot?") },
            text = { Text("Delete from device.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ZoomPresetStrip(
    scale: Float,
    onScale: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.52f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(1f, 4f, 8f, 12f).forEach { preset ->
            val active = abs(scale - preset) < 0.25f
            Text(
                text = "${preset.roundToInt()}×",
                color = if (active) Color.Black else CameraColors.TextPrimary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (active) CameraColors.TextPrimary else Color.Transparent)
                    .semantics {
                        contentDescription = "Review zoom ${preset.roundToInt()} times"
                        role = Role.Button
                    }
                    .clickable { onScale(preset) }
                    .padding(horizontal = 11.dp, vertical = 6.dp),
            )
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "--"
    val mib = bytes / (1024.0 * 1024.0)
    return "%.1f MB".format(mib)
}
