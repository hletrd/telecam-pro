package com.hletrd.findx9tele.ui.review

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
 * (falls back to a large downsample on failure). Pinch to zoom (1×–12×), drag to pan, double-tap to
 * toggle 1×/4×, tap the ✕ to close.
 */
@Composable
fun MediaReviewOverlay(uri: Uri, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, uri) {
        value = loadBitmap(context, uri, 0) ?: loadBitmap(context, uri, 3000)
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

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
    }
}
