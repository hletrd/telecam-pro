package com.hletrd.findx9tele.ui.review

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.scale
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hletrd.findx9tele.camera.MediaDeleteScope
import com.hletrd.findx9tele.ui.controls.MinTouchTargetButton
import com.hletrd.findx9tele.ui.overlays.HUD_TEXT_SCRIM_ALPHA
import com.hletrd.findx9tele.ui.theme.CameraColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * In-app review of the last saved photo or video. Stills support high-magnification focus checks;
 * videos use a bounded thumbnail plus an identity-owned TextureView/MediaPlayer playback surface.
 * RAW/DNG captures use a truthful metadata placeholder and deliberately never enter a pixel decoder.
 */

internal enum class ReviewMediaKind {
    STILL,
    VIDEO,
    RAW,
}

private val rawMimeTypes = setOf(
    "image/x-adobe-dng",
    "image/dng",
    "application/x-adobe-dng",
)

/** Pure MIME classifier shared by thumbnail and fullscreen review. */
internal fun reviewMediaKind(mimeType: String?): ReviewMediaKind {
    val normalized = mimeType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(java.util.Locale.ROOT)
    return when {
        normalized in rawMimeTypes -> ReviewMediaKind.RAW
        normalized?.startsWith("video/") == true -> ReviewMediaKind.VIDEO
        else -> ReviewMediaKind.STILL
    }
}

internal fun galleryReviewContentDescription(
    hasMedia: Boolean,
    kind: ReviewMediaKind?,
): String = when {
    !hasMedia -> "No capture to review"
    kind == ReviewMediaKind.RAW -> "Review last RAW capture"
    kind == ReviewMediaKind.VIDEO -> "Review last video"
    kind == ReviewMediaKind.STILL -> "Review last photo"
    else -> "Review last capture"
}

/** Rotation + dimensions of a video, for sizing/orienting the in-review player. */
private data class VideoInfo(val rotationDeg: Int, val width: Int, val height: Int)

/** Positive decoder target whose longest side never exceeds the requested bound. */
internal data class BoundedVideoFrameSize(val width: Int, val height: Int)

internal fun boundedVideoFrameSize(width: Int, height: Int, maxDim: Int): BoundedVideoFrameSize? {
    if (width <= 0 || height <= 0 || maxDim <= 0) return null
    val longest = maxOf(width, height)
    if (longest <= maxDim) return BoundedVideoFrameSize(width, height)
    return if (width >= height) {
        BoundedVideoFrameSize(
            width = maxDim,
            height = (height.toLong() * maxDim / width).toInt().coerceAtLeast(1),
        )
    } else {
        BoundedVideoFrameSize(
            width = (width.toLong() * maxDim / height).toInt().coerceAtLeast(1),
            height = maxDim,
        )
    }
}

/** A MediaPlayer and the caller-owned Surface passed to it; both share one release generation. */
private class VideoPlaybackHandle(
    val player: android.media.MediaPlayer,
    val surface: Surface,
) {
    private var released = false

    fun release() {
        if (released) return
        released = true
        runCatching { player.release() }
        runCatching { surface.release() }
    }
}

private sealed interface ReviewMediaState {
    data object Loading : ReviewMediaState
    sealed interface Ready : ReviewMediaState {
        data class Still(val bitmap: ImageBitmap) : Ready
        data class Video(val info: VideoInfo) : Ready
        data object Raw : Ready
    }
    data class Error(val message: String) : ReviewMediaState
}

private const val REVIEW_PREVIEW_MAX_DIM = 3000

private fun loadVideoInfo(context: Context, uri: Uri): VideoInfo? = runCatching {
    if (context.contentResolver.getType(uri)?.startsWith("video/") != true) return@runCatching null
    val mmr = MediaMetadataRetriever()
    try {
        mmr.setDataSource(context, uri)
        val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        if (w > 0 && h > 0) VideoInfo(rot, w, h) else null
    } finally {
        runCatching { mmr.release() }
    }
}.getOrNull()

/** First frame of a video, for the thumbnail/review of a just-recorded clip (BitmapFactory can't). */
private fun loadVideoFrame(context: Context, uri: Uri, maxDim: Int): ImageBitmap? = runCatching {
    val mmr = MediaMetadataRetriever()
    try {
        mmr.setDataSource(context, uri)
        val metadataTarget = boundedVideoFrameSize(
            width = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
            height = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
            maxDim = maxDim,
        )
        metadataTarget?.let { target ->
            runCatching {
                mmr.getScaledFrameAtTime(
                    0,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    target.width,
                    target.height,
                )
            }.getOrNull()?.let { return@runCatching it.asImageBitmap() }
        }

        // Invalid/missing metadata or decoder-specific scaled-frame failure: decode once, then
        // preserve the same bound and promptly recycle the distinct full-size source bitmap.
        val source = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: return@runCatching null
        val fallbackTarget = boundedVideoFrameSize(source.width, source.height, maxDim)
        if (fallbackTarget == null ||
            (fallbackTarget.width == source.width && fallbackTarget.height == source.height)
        ) {
            source.asImageBitmap()
        } else {
            val scaled = try {
                source.scale(fallbackTarget.width, fallbackTarget.height)
            } catch (error: Throwable) {
                source.recycle()
                throw error
            }
            try {
                scaled.asImageBitmap()
            } finally {
                if (scaled !== source) source.recycle()
            }
        }
    } finally {
        runCatching { mmr.release() }
    }
}.getOrNull()

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

private suspend fun loadReviewMedia(context: Context, uri: Uri): ReviewMediaState {
    val mimeType = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.getType(uri) }.getOrNull()
    }
    return when (reviewMediaKind(mimeType)) {
        ReviewMediaKind.RAW -> ReviewMediaState.Ready.Raw
        ReviewMediaKind.VIDEO -> {
            withContext(Dispatchers.IO) { loadVideoInfo(context, uri) }
                ?.let { ReviewMediaState.Ready.Video(it) }
                ?: ReviewMediaState.Error("Unable to open this video.")
        }
        ReviewMediaKind.STILL -> {
            // A capped first decode avoids a 50 MP ARGB allocation (~200 MB) before Compose/GPU copies.
            // The resulting 3000 px preview still carries ample detail for the existing 4×/8× focus check.
            loadBitmap(context, uri, REVIEW_PREVIEW_MAX_DIM)
                ?.let { ReviewMediaState.Ready.Still(it) }
                ?: ReviewMediaState.Error("Unable to open this image.")
        }
    }
}

private data class ReviewMetadata(
    val name: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    /** "ISO 1250 · 1/300s · 300 mm" when the file carries exposure EXIF (JPEG/DNG); null otherwise. */
    val exifLine: String?,
)

private suspend fun loadMetadata(context: Context, uri: Uri): ReviewMetadata? =
    withContext(Dispatchers.IO) {
        runCatching {
            // Sony playback-style exposure info, when the file carries EXIF (our JPEGs are stamped
            // at save; HEIFs currently are not — the line simply drops out then).
            val exifLine = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val exif = androidx.exifinterface.media.ExifInterface(input)
                    val iso = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                    val expS = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_TIME)?.toDoubleOrNull()
                    val focal35 = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)
                    val parts = buildList {
                        iso?.let { add("ISO $it") }
                        expS?.let { add(if (it >= 1.0) "%.1fs".format(java.util.Locale.US, it) else "1/${(1.0 / it).roundToInt()}s") }
                        focal35?.takeIf { f -> f.toIntOrNull()?.let { it > 0 } == true }?.let { add("$it mm") }
                    }
                    parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
                }
            }.getOrNull()
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
                    exifLine = exifLine,
                )
            }
        }.getOrNull()
    }

private data class GalleryThumbContent(
    val kind: ReviewMediaKind? = null,
    val bitmap: ImageBitmap? = null,
)

/** Tappable thumbnail of the last capture; RAW uses a labeled tile rather than a fake/failed image. */
@Composable
fun GalleryThumb(uri: Uri?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val content by produceState(initialValue = GalleryThumbContent(), uri) {
        // produceState retains its state holder across key changes; clear the outgoing capture before
        // resolving the new MIME so a RAW tile/old bitmap is never briefly labeled as the new owner.
        value = GalleryThumbContent()
        if (uri == null) {
            return@produceState
        }
        val mimeType = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.getType(uri) }.getOrNull()
        }
        val kind = reviewMediaKind(mimeType)
        val bitmap = when (kind) {
            ReviewMediaKind.RAW -> null
            ReviewMediaKind.VIDEO -> withContext(Dispatchers.IO) { loadVideoFrame(context, uri, 240) }
            ReviewMediaKind.STILL -> loadBitmap(context, uri, 240)
        }
        value = GalleryThumbContent(kind, bitmap)
    }
    Box(
        modifier = modifier
            .size(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(CameraColors.Pill)
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(14.dp))
            .semantics {
                contentDescription = galleryReviewContentDescription(uri != null, content.kind)
                role = Role.Button
            }
            .clickable(enabled = uri != null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val t = content.bitmap
        if (t != null) {
            Image(
                bitmap = t,
                // The parent is the one semantic Button; the pixels are decorative and must not
                // produce a duplicate TalkBack announcement.
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (content.kind == ReviewMediaKind.RAW) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "RAW",
                    color = CameraColors.TextPrimary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "DNG",
                    color = CameraColors.TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        } else {
            // "No capture yet" pictograph: frame + sun-dot + mountain path — a complete photo
            // glyph like the app's other multi-primitive hand-drawn icons (the lone off-center
            // dot it used to draw read as a rendering artifact, not an empty state).
            Canvas(Modifier.size(22.dp)) {
                val color = CameraColors.TextSecondary
                val stroke = Stroke(width = size.minDimension * 0.07f)
                drawRoundRect(
                    color = color,
                    cornerRadius = CornerRadius(size.minDimension * 0.12f),
                    style = stroke,
                )
                drawCircle(
                    color,
                    radius = size.minDimension * 0.10f,
                    center = Offset(size.width * 0.32f, size.height * 0.34f),
                )
                val mountains = Path().apply {
                    moveTo(size.width * 0.12f, size.height * 0.78f)
                    lineTo(size.width * 0.40f, size.height * 0.50f)
                    lineTo(size.width * 0.58f, size.height * 0.66f)
                    lineTo(size.width * 0.72f, size.height * 0.54f)
                    lineTo(size.width * 0.88f, size.height * 0.78f)
                }
                drawPath(mountains, color, style = stroke)
            }
        }
    }
}

/**
 * Fullscreen review of [uri]. Still decoding and video thumbnails are bounded so opening a
 * high-resolution capture cannot require an avoidable full-size ARGB allocation.
 */
@Composable
fun MediaReviewOverlay(
    uri: Uri,
    deleteScope: MediaDeleteScope,
    onClose: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    // Shooting-screen glyph rule applied to review: compact/short labels counter-rotate so they
    // read upright in the landscape hold the 300 mm rig encourages; wide boxes (the metadata
    // block) stay screen-fixed — Modifier.rotate is a draw transform, not a re-layout, and a
    // rotated wide box pokes out of its layout slot.
    overlayRotation: Float = 0f,
) {
    val context = LocalContext.current
    var loadAttempt by remember(uri) { mutableIntStateOf(0) }
    var mediaState by remember(uri) { mutableStateOf<ReviewMediaState>(ReviewMediaState.Loading) }
    LaunchedEffect(uri, loadAttempt) {
        mediaState = ReviewMediaState.Loading
        mediaState = loadReviewMedia(context, uri)
    }
    val videoInfo = (mediaState as? ReviewMediaState.Ready.Video)?.info
    val gestureMediaReady =
        mediaState is ReviewMediaState.Ready.Still || mediaState is ReviewMediaState.Ready.Video
    val rawReady = mediaState is ReviewMediaState.Ready.Raw
    val deleteCopy = mediaDeleteConfirmationCopy(deleteScope, rawReady)
    val metadata by produceState<ReviewMetadata?>(initialValue = null, uri) {
        value = null
        value = loadMetadata(context, uri)
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var confirmDelete by remember { mutableStateOf(false) }
    BackHandler(enabled = confirmDelete) { confirmDelete = false }
    BackHandler(enabled = !confirmDelete, onBack = onClose)
    // In-review playback (videos): a TextureView + MediaPlayer — NOT VideoView, whose SurfaceView
    // sits behind the window and is occluded by this overlay's opaque black background (the same
    // trap the camera preview hit). Tap toggles play/pause; the clip loops.
    val playerRef = remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(true) }
    // Stock-gallery-style dismiss: at 1x, a vertical drag slides the image and past a threshold
    // closes the review; below it springs back. Zoomed in, vertical pan just pans.
    var dismissDrag by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(uri, loadAttempt) {
        scale = 1f
        offset = Offset.Zero
        dismissDrag = 0f
        playing = true
    }

    fun toggleVideoPlayback(): Boolean {
        val player = playerRef.value ?: return false
        return runCatching {
            if (player.isPlaying) player.pause() else player.start()
            player.isPlaying
        }.fold(
            onSuccess = { isPlaying ->
                playing = isPlaying
                true
            },
            onFailure = { false },
        )
    }

    fun setReviewScale(target: Float): Boolean {
        scale = target.coerceIn(1f, 12f)
        offset = Offset.Zero
        dismissDrag = 0f
        return true
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .semantics {
                paneTitle = if (rawReady) "RAW capture review" else "Media review"
                isTraversalGroup = true
            },
        contentAlignment = Alignment.Center,
    ) {
        // Keep the raw media gesture surface as a sibling behind the explicit controls. A control
        // tap therefore cannot also bubble through this loop and toggle playback a second time.
        Box(
            modifier = Modifier
                .fillMaxSize()
            .pointerInput(gestureMediaReady, videoInfo != null) {
                if (!gestureMediaReady) return@pointerInput
                // ONE gesture loop owns pinch + pan + swipe-dismiss + tap/double-tap/long-press.
                // Two sibling pointerInput blocks fought here exactly like CameraScreen's tap-vs-
                // pinch conflict: the pinch/pan loop consumed EVERY change, which cancelled the
                // sibling detectTapGestures' waitForUpOrCancellation, so video tap-to-pause almost
                // never fired. Merged, tap-vs-pinch-vs-drag is decided on finger-up (the pattern in
                // CameraScreen.kt's viewfinder loop) and long-press/double-tap are timed inline.
                val isVideo = videoInfo != null
                var lastTapUptime = 0L
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downTime = down.uptimeMillis
                    var zoomed = false
                    var dragged = false
                    var maxPointers = 1
                    var longFired = false
                    var lastEventTime = downTime
                    var upTime = downTime
                    var tapPos = down.position
                    while (true) {
                        // While the finger is a motionless single touch, cap the wait at the
                        // REMAINING long-press window (a held-still finger emits no move events, so
                        // only a timeout can fire the long-press). `remaining` shrinks with each
                        // jitter event's timestamp, so cumulative hold time is honoured — not reset
                        // to the full timeout on every event.
                        val armLongPress = !isVideo && !longFired && !zoomed && !dragged && maxPointers == 1
                        val event = if (armLongPress) {
                            val remaining = viewConfiguration.longPressTimeoutMillis - (lastEventTime - downTime)
                            if (remaining <= 0L) null else withTimeoutOrNull(remaining) { awaitPointerEvent() }
                        } else {
                            awaitPointerEvent()
                        }
                        if (event == null) {
                            // Long-press (stills): a motionless hold zooms 8× to the point; a later
                            // drag then pans (scale > 1) through the same loop.
                            scale = 8f
                            val center = Offset(size.width / 2f, size.height / 2f)
                            offset = (center - down.position) * scale
                            longFired = true
                            continue
                        }
                        lastEventTime = event.changes.maxOfOrNull { it.uptimeMillis } ?: lastEventTime
                        if (event.changes.none { it.pressed }) {
                            val up = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull()
                            if (up != null) {
                                upTime = up.uptimeMillis
                                tapPos = up.position
                            }
                            break
                        }
                        maxPointers = maxOf(maxPointers, event.changes.count { it.pressed })
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        // Pinch-zoom is a stills-only focus check; a video plays at fit size.
                        if (!isVideo && zoom != 1f) {
                            zoomed = true
                            scale = (scale * zoom).coerceIn(1f, 12f)
                        }
                        if (scale > 1f) {
                            offset += pan
                            dismissDrag = 0f
                        } else {
                            offset = Offset.Zero
                            dismissDrag += pan.y
                        }
                        val cur = event.changes.firstOrNull { it.id == down.id }?.position
                        if (cur != null && (cur - down.position).getDistance() > viewConfiguration.touchSlop) dragged = true
                        event.changes.forEach { it.consume() }
                    }
                    // A clean tap = one finger, no pinch, no drag past slop, no long-press already fired.
                    val cleanTap = !longFired && !zoomed && !dragged && maxPointers == 1
                    if (cleanTap) {
                        if (isVideo) {
                            // Single tap toggles play/pause — reliable now the consuming pinch loop
                            // no longer starves it.
                            toggleVideoPlayback()
                        } else if (down.uptimeMillis - lastTapUptime < viewConfiguration.doubleTapTimeoutMillis) {
                            // Double tap (stills): cycle review zoom, centered on the tap. Measured
                            // first-tap-up → second-tap-down, matching detectTapGestures.
                            val next = nextReviewScale(scale)
                            scale = next
                            offset = if (next <= 1f) Offset.Zero
                            else (Offset(size.width / 2f, size.height / 2f) - tapPos) * next
                            lastTapUptime = 0L
                        } else {
                            // First tap of a potential double; a lone tap on a still does nothing.
                            lastTapUptime = upTime
                        }
                        dismissDrag = 0f
                    } else if (scale <= 1f) {
                        if (abs(dismissDrag) > size.height * 0.16f) onClose() else dismissDrag = 0f
                    } else {
                        dismissDrag = 0f
                    }
                }
                },
            contentAlignment = Alignment.Center,
        ) {
        val vi = videoInfo
        if (vi != null) {
            // Fit the ROTATED video within the screen; the rotation hint is applied as a TextureView
            // transform (MediaPlayer ignores it on TextureView output).
            val rotated = vi.rotationDeg % 180 != 0
            val aspect = if (rotated) vi.height.toFloat() / vi.width else vi.width.toFloat() / vi.height
            // key(uri): the factory captures uri/vi once, so a NEW clip landing while review is open
            // (e.g. a hardware-key recording finishing) must recreate the view — otherwise the stale
            // MediaPlayer keeps looping the old file. Recreation routes through
            // onSurfaceTextureDestroyed, which releases the old playback generation.
            androidx.compose.runtime.key(uri) {
                // `playerRef` is shared across key(uri) swaps, while this handle is per clip. A
                // single identity guard therefore owns BOTH the MediaPlayer and caller-created
                // Surface, preventing clip A's late teardown from releasing clip B's generation.
                val heldPlayback = remember { mutableStateOf<VideoPlaybackHandle?>(null) }
                fun releaseIfOwned(handle: VideoPlaybackHandle) {
                    if (heldPlayback.value !== handle) return
                    heldPlayback.value = null
                    if (playerRef.value === handle.player) playerRef.value = null
                    handle.release()
                }
                AndroidView(
                    modifier = Modifier
                        .aspectRatio(aspect.coerceAtLeast(0.01f))
                        .graphicsLayer(
                            translationY = dismissDrag,
                            alpha = (1f - abs(dismissDrag) / 1400f).coerceIn(0.3f, 1f),
                        )
                        .semantics {
                            contentDescription = "Video review"
                            stateDescription = videoPlaybackStateDescription(playing)
                            role = Role.Button
                            onClick(label = videoPlaybackActionLabel(playing)) {
                                toggleVideoPlayback()
                            }
                        },
                    factory = { ctx ->
                        var viewHandle: VideoPlaybackHandle? = null
                        android.view.TextureView(ctx).apply {
                            surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                                    // Undo the default stretch and apply the container rotation so a
                                    // landscape-held clip plays upright and undistorted.
                                    if (vi.rotationDeg != 0) {
                                        val m = android.graphics.Matrix()
                                        val cx = w / 2f
                                        val cy = h / 2f
                                        m.postRotate(vi.rotationDeg.toFloat(), cx, cy)
                                        if (rotated) m.postScale(w.toFloat() / h, h.toFloat() / w, cx, cy)
                                        setTransform(m)
                                    }

                                    viewHandle?.let(::releaseIfOwned)
                                    val mp = android.media.MediaPlayer()
                                    val playbackSurface = try {
                                        Surface(st)
                                    } catch (_: Throwable) {
                                        runCatching { mp.release() }
                                        playing = false
                                        mediaState = ReviewMediaState.Error("Unable to play this video.")
                                        return
                                    }
                                    val handle = VideoPlaybackHandle(mp, playbackSurface)
                                    viewHandle = handle
                                    heldPlayback.value = handle
                                    playerRef.value = mp

                                    fun failPlayback() {
                                        if (heldPlayback.value !== handle) return
                                        if (viewHandle === handle) viewHandle = null
                                        releaseIfOwned(handle)
                                        playing = false
                                        mediaState = ReviewMediaState.Error("Unable to play this video.")
                                    }

                                    runCatching {
                                        mp.setDataSource(ctx, uri)
                                        mp.setSurface(playbackSurface)
                                        mp.isLooping = true
                                        // Async callbacks may arrive after teardown; identity gates
                                        // keep them from starting or releasing a replacement player.
                                        mp.setOnPreparedListener { p ->
                                            if (heldPlayback.value !== handle) return@setOnPreparedListener
                                            runCatching { p.start() }
                                                .onSuccess { playing = true }
                                                .onFailure { failPlayback() }
                                        }
                                        mp.setOnErrorListener { _, _, _ ->
                                            failPlayback()
                                            true
                                        }
                                        mp.prepareAsync()
                                    }.onFailure { failPlayback() }
                                }

                                override fun onSurfaceTextureSizeChanged(
                                    st: android.graphics.SurfaceTexture,
                                    w: Int,
                                    h: Int,
                                ) = Unit

                                override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                                    viewHandle?.let { handle ->
                                        viewHandle = null
                                        releaseIfOwned(handle)
                                    }
                                    return true
                                }

                                override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) = Unit
                            }
                        }
                    },
                )
                DisposableEffect(Unit) {
                    onDispose {
                        heldPlayback.value?.let(::releaseIfOwned)
                    }
                }
            }
            if (!playing) {
                // Paused indicator: a simple ▶ so it's obvious a tap resumes.
                Canvas(Modifier.size(64.dp)) {
                    drawCircle(Color.Black.copy(alpha = 0.45f), radius = size.minDimension / 2f)
                    val tri = androidx.compose.ui.graphics.Path().apply {
                        moveTo(size.width * 0.4f, size.height * 0.3f)
                        lineTo(size.width * 0.4f, size.height * 0.7f)
                        lineTo(size.width * 0.74f, size.height * 0.5f)
                        close()
                    }
                    drawPath(tri, Color.White)
                }
            }
        } else {
        val still = mediaState as? ReviewMediaState.Ready.Still
        if (still != null) {
            Image(
                bitmap = still.bitmap,
                contentDescription = "Photo review",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y + dismissDrag,
                        alpha = (1f - abs(dismissDrag) / 1400f).coerceIn(0.3f, 1f),
                    )
                    .semantics {
                        stateDescription = reviewZoomStateDescription(scale)
                        customActions = listOf(
                            CustomAccessibilityAction("Zoom 4×") { setReviewScale(4f) },
                            CustomAccessibilityAction("Zoom 8×") { setReviewScale(8f) },
                            CustomAccessibilityAction("Reset zoom") { setReviewScale(1f) },
                        )
                    },
            )
        } else if (rawReady) {
            RawReviewPlaceholder(Modifier.fillMaxSize())
        } else when (val current = mediaState) {
            ReviewMediaState.Loading -> Text(
                "Loading review…",
                color = CameraColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            is ReviewMediaState.Error -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .semantics { liveRegion = LiveRegionMode.Assertive },
            ) {
                Text(
                    current.message,
                    color = CameraColors.TextPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                MinTouchTargetButton {
                    TextButton(onClick = { loadAttempt += 1 }) {
                        Text("Retry")
                    }
                }
            }
            is ReviewMediaState.Ready -> Unit
        }
        }
        }

        metadata?.let { meta ->
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(14.dp)
                    .clip(RoundedCornerShape(10.dp))
                    // Sits directly over the reviewed (often bright) photo, so it rides the same tested
                    // contrast floor (05486cb) as the live HUD — at 0.55 the secondary EXIF line was
                    // ~1.78:1, effectively unreadable over any bright region of the frame.
                    .background(Color.Black.copy(alpha = HUD_TEXT_SCRIM_ALPHA))
                    .widthIn(max = 280.dp)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(meta.name, color = CameraColors.TextPrimary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    reviewMetadataLine(rawReady, meta.width, meta.height, meta.sizeBytes),
                    color = CameraColors.TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                )
                meta.exifLine?.let {
                    Text(it, color = CameraColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        if (mediaState is ReviewMediaState.Ready.Still && scale > 1.05f) {
            Text(
                text = reviewScaleLabel(scale),
                color = CameraColors.TextPrimary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 16.dp)
                    // Short label → counter-rotates like the shooting screen's compact glyphs (a
                    // focus check right after a landscape-held 300 mm shot reads "4×" upright).
                    .rotate(overlayRotation)
                    .clip(RoundedCornerShape(50))
                    // Match the live ZoomIndicator's tested contrast floor (05486cb) — its 0.55 sibling
                    // cleared 4.5 only by a hair over a bright review photo.
                    .background(Color.Black.copy(alpha = HUD_TEXT_SCRIM_ALPHA))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }

        when {
            videoInfo != null -> ReviewActionButton(
                actionLabel = videoPlaybackActionLabel(playing),
                stateLabel = videoPlaybackStateDescription(playing),
                onClick = { toggleVideoPlayback() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(14.dp),
            ) {
                PlaybackGlyph(playing = playing)
            }

            mediaState is ReviewMediaState.Ready.Still -> ReviewActionButton(
                actionLabel = reviewZoomActionLabel(scale),
                stateLabel = reviewZoomStateDescription(scale),
                onClick = { setReviewScale(nextReviewScale(scale)) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(14.dp),
            ) {
                Text(
                    text = reviewZoomControlLabel(scale),
                    color = CameraColors.TextPrimary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.rotate(overlayRotation),
                )
            }
        }

        // Close button, top-left. Scrim rides the tested HUD contrast floor (05486cb): the "✕" over a
        // bright/high-key review frame washed out at 0.5 (≈3.98:1, under the 4.5 floor).
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(12.dp)
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = HUD_TEXT_SCRIM_ALPHA))
                .semantics {
                    contentDescription = "Close review"
                    role = Role.Button
                }
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Text("✕", color = CameraColors.TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }

        // Delete button, top-right. Scrim rides the tested HUD contrast floor (05486cb): the red trash
        // glyph over a bright frame was the worst interactive contrast found (≈1.43:1 at 0.5) — and it
        // is a DESTRUCTIVE action, so it must never be ambiguous. At the floor the red clears 4.5.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(12.dp)
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = HUD_TEXT_SCRIM_ALPHA))
                .semantics {
                    contentDescription = deleteCopy.title.removeSuffix("?")
                    role = Role.Button
                }
                .clickable { confirmDelete = true },
            contentAlignment = Alignment.Center,
        ) {
            // Trash-can glyph (icon per feedback, not a "DEL" text chip), tinted delete-red.
            Canvas(Modifier.size(18.dp)) {
                val c = Color(0xFFFF6B6B)
                val sw = 1.6.dp.toPx()
                val w = size.width
                val h = size.height
                // lid + handle
                drawLine(c, Offset(w * 0.08f, h * 0.2f), Offset(w * 0.92f, h * 0.2f), strokeWidth = sw)
                drawLine(c, Offset(w * 0.36f, h * 0.08f), Offset(w * 0.64f, h * 0.08f), strokeWidth = sw)
                // body
                drawLine(c, Offset(w * 0.2f, h * 0.2f), Offset(w * 0.28f, h * 0.94f), strokeWidth = sw)
                drawLine(c, Offset(w * 0.8f, h * 0.2f), Offset(w * 0.72f, h * 0.94f), strokeWidth = sw)
                drawLine(c, Offset(w * 0.28f, h * 0.94f), Offset(w * 0.72f, h * 0.94f), strokeWidth = sw)
                // ribs
                drawLine(c, Offset(w * 0.42f, h * 0.34f), Offset(w * 0.44f, h * 0.8f), strokeWidth = sw * 0.8f)
                drawLine(c, Offset(w * 0.58f, h * 0.34f), Offset(w * 0.56f, h * 0.8f), strokeWidth = sw * 0.8f)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(deleteCopy.title) },
            text = { Text(deleteCopy.body) },
            confirmButton = {
                // 48 dp outer targets (DES4-2): a mis-tap is costliest right here, next to the
                // app's one destructive, irreversible action.
                MinTouchTargetButton {
                    TextButton(onClick = {
                        confirmDelete = false
                        onDelete()
                    }) {
                        // Destructive action reads red (same delete-red as the trash glyph); the rest of
                        // the review chrome stays Sony-style monochrome.
                        Text("Delete", color = Color(0xFFFF6B6B))
                    }
                }
            },
            dismissButton = {
                MinTouchTargetButton {
                    TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
                }
            },
        )
    }
}

internal data class MediaDeleteConfirmationCopy(val title: String, val body: String)

internal fun mediaDeleteConfirmationCopy(
    scope: MediaDeleteScope,
    raw: Boolean,
): MediaDeleteConfirmationCopy = when (scope) {
    MediaDeleteScope.CAPTURE_FAMILY -> MediaDeleteConfirmationCopy(
        title = if (raw) "Delete RAW capture?" else "Delete capture?",
        body = "All saved formats for this capture will be deleted.",
    )
    MediaDeleteScope.FILE_ONLY -> MediaDeleteConfirmationCopy(
        title = if (raw) "Delete RAW file?" else "Delete file?",
        body = "This file will be deleted.",
    )
}

@Composable
private fun RawReviewPlaceholder(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .padding(48.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "RAW DNG capture"
                stateDescription = "Preview unavailable"
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "RAW",
            color = CameraColors.TextPrimary,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "DNG",
            color = CameraColors.TextSecondary,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun ReviewActionButton(
    actionLabel: String,
    stateLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            // Shared scrim constant (DES4-4): the last review-screen surface still on a magic
            // alpha after the fc16e23 sweep — HudContrastTest pins this one with its siblings.
            .background(Color.Black.copy(alpha = HUD_TEXT_SCRIM_ALPHA))
            .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
            .semantics {
                contentDescription = actionLabel
                stateDescription = stateLabel
            }
            .clickable(
                onClickLabel = actionLabel,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun PlaybackGlyph(playing: Boolean) {
    Canvas(Modifier.size(20.dp)) {
        if (playing) {
            val stroke = size.width * 0.16f
            drawLine(
                color = Color.White,
                start = Offset(size.width * 0.34f, size.height * 0.24f),
                end = Offset(size.width * 0.34f, size.height * 0.76f),
                strokeWidth = stroke,
            )
            drawLine(
                color = Color.White,
                start = Offset(size.width * 0.66f, size.height * 0.24f),
                end = Offset(size.width * 0.66f, size.height * 0.76f),
                strokeWidth = stroke,
            )
        } else {
            val triangle = androidx.compose.ui.graphics.Path().apply {
                moveTo(size.width * 0.32f, size.height * 0.22f)
                lineTo(size.width * 0.32f, size.height * 0.78f)
                lineTo(size.width * 0.76f, size.height * 0.5f)
                close()
            }
            drawPath(triangle, Color.White)
        }
    }
}

internal fun nextReviewScale(current: Float): Float = when {
    current < 1.5f -> 4f
    current < 6f -> 8f
    else -> 1f
}

internal fun reviewScaleLabel(scale: Float): String = when {
    abs(scale - 1f) < 0.05f -> "1×"
    abs(scale - 4f) < 0.05f -> "4×"
    abs(scale - 8f) < 0.05f -> "8×"
    else -> "%.1f×".format(java.util.Locale.US, scale)
}

internal fun reviewZoomActionLabel(scale: Float): String = when (nextReviewScale(scale)) {
    4f -> "Zoom 4×"
    8f -> "Zoom 8×"
    else -> "Reset zoom"
}

internal fun reviewZoomControlLabel(scale: Float): String =
    if (nextReviewScale(scale) <= 1f) "1×" else reviewScaleLabel(nextReviewScale(scale))

internal fun reviewZoomStateDescription(scale: Float): String =
    "Magnification ${reviewScaleLabel(scale)}"

internal fun videoPlaybackActionLabel(playing: Boolean): String =
    if (playing) "Pause video" else "Play video"

internal fun videoPlaybackStateDescription(playing: Boolean): String =
    if (playing) "Playing" else "Paused"

internal fun reviewMetadataLine(raw: Boolean, width: Int, height: Int, sizeBytes: Long): String =
    buildList {
        if (raw) add("RAW")
        if (width > 0 && height > 0) add("${width}×${height}")
        add(formatBytes(sizeBytes))
    }.joinToString(" · ")

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "--"
    val mib = bytes / (1024.0 * 1024.0)
    return "%.1f MB".format(java.util.Locale.US, mib)
}
