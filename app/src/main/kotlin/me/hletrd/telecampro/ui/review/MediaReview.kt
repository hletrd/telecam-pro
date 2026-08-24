package me.hletrd.telecampro.ui.review

import androidx.annotation.StringRes

import me.hletrd.telecampro.R

import android.content.Context
import android.graphics.Bitmap
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
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.focusable
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.core.graphics.createBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
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
import me.hletrd.telecampro.camera.MediaDeleteScope
import me.hletrd.telecampro.storage.MediaProvenance
import me.hletrd.telecampro.ui.controls.MinTouchTarget48
import me.hletrd.telecampro.ui.controls.formatShutterSpeed
import me.hletrd.telecampro.ui.modalFocusBoundary
import me.hletrd.telecampro.ui.rotateLayout
import me.hletrd.telecampro.ui.overlays.HudPlate
import me.hletrd.telecampro.ui.theme.CameraColors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max

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

/**
 * Returns the STRING RESOURCE for the gallery button's spoken name, not the string itself. The
 * choice is a pure function of media state and belongs in a plain testable function, but the words
 * are localized — returning text would have hard-coded English into a decision that has nothing to
 * do with language. Tests assert the resource identity, which is what the decision actually is.
 */
@StringRes
internal fun galleryReviewContentDescription(
    hasMedia: Boolean,
    kind: ReviewMediaKind?,
    provenance: MediaProvenance = MediaProvenance.APP_OWNED,
): Int = when {
    !hasMedia -> R.string.a11y_no_capture_to_review
    provenance == MediaProvenance.LEGACY_FORMAT_UNVERIFIED ->
        R.string.a11y_review_legacy_unverified_media
    kind == ReviewMediaKind.RAW -> R.string.a11y_review_last_raw
    kind == ReviewMediaKind.VIDEO -> R.string.a11y_review_last_video
    kind == ReviewMediaKind.STILL -> R.string.a11y_review_last_photo
    else -> R.string.a11y_review_last_capture
}

/** Quiet review-plate disclosure; verified package-owned media needs no extra provenance copy. */
@StringRes
internal fun reviewProvenanceLabel(provenance: MediaProvenance): Int? = when (provenance) {
    MediaProvenance.APP_OWNED -> null
    MediaProvenance.LEGACY_FORMAT_UNVERIFIED -> R.string.review_legacy_unverified_provenance
}

/** Rotation + dimensions of a video, for sizing/orienting the in-review player. */
private data class VideoInfo(val rotationDeg: Int, val width: Int, val height: Int)

/** Exact provider thumbnail request; it never depends on untrusted video metadata. */
internal data class ProviderThumbnailRequest(val width: Int, val height: Int)

internal fun providerThumbnailRequest(maxDim: Int): ProviderThumbnailRequest? =
    maxDim.takeIf { it > 0 }?.let { ProviderThumbnailRequest(it, it) }

/** Rejects a broken provider response instead of allocating another bitmap to repair it. */
internal fun providerThumbnailFitsRequest(
    width: Int,
    height: Int,
    request: ProviderThumbnailRequest,
): Boolean = width in 1..request.width && height in 1..request.height

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

/**
 * Latest-wins setup boundary for provider/native work that cannot observe coroutine cancellation.
 *
 * A caller can invalidate its owner synchronously from Back/disposal while [work] is blocked. The
 * worker may return later, but [ProgressiveLatestWorkLane] then releases its unpublished result instead of
 * letting a retired TextureView generation reach Compose. A replacement owner's request remains
 * untouched by invalidating the old owner.
 */
internal class LatestReviewSetupLane<I, R : Any>(
    dispatcher: CoroutineDispatcher? = null,
    workerCount: Int = REVIEW_LANE_WORKER_COUNT,
    terminalTimeoutMs: Long = REVIEW_WORK_TERMINAL_TIMEOUT_MS,
    work: (I) -> R?,
    release: (R) -> Unit,
) {
    internal enum class Outcome { PUBLISHED, RETIRED, TIMED_OUT, CAPACITY_EXHAUSTED }

    private val lane = if (dispatcher == null) {
        ProgressiveLatestWorkLane(
            workerCount = workerCount,
            terminalTimeoutMs = terminalTimeoutMs,
            work = work,
            dispose = release,
        )
    } else {
        ProgressiveLatestWorkLane(dispatcher, workerCount, terminalTimeoutMs, work, release)
    }

    suspend fun run(owner: Any, input: I, publish: (R) -> Unit): Outcome =
        when (val submission = lane.submit(owner, input)) {
            is ProgressiveLatestWorkLane.Submission.Completed -> {
                if (lane.claim(submission.completion, publish)) Outcome.PUBLISHED else Outcome.RETIRED
            }
            ProgressiveLatestWorkLane.Submission.Retired -> Outcome.RETIRED
            ProgressiveLatestWorkLane.Submission.TimedOut -> Outcome.TIMED_OUT
            ProgressiveLatestWorkLane.Submission.CapacityExhausted -> Outcome.CAPACITY_EXHAUSTED
        }

    fun invalidate(owner: Any) = lane.invalidate(owner)
}

/** Process-owned requests must never retain an Activity/window graph on a blocked worker. */
internal fun processReviewContext(context: Context): Context = context.applicationContext

private class VideoPlaybackSetupRequest(
    context: Context,
    val uri: Uri,
    surface: Surface,
) {
    val context: Context = processReviewContext(context)

    private val surfaceOwner = AtomicReference(surface)

    /** Transfers the exact Surface to a successfully configured playback handle. */
    fun claimSurface(): Surface? = surfaceOwner.getAndSet(null)

    /** Releases a request canceled before its worker transferred Surface ownership. */
    fun releaseSurface() {
        surfaceOwner.getAndSet(null)?.let { surface -> runCatching { surface.release() } }
    }
}

private sealed interface VideoPlaybackSetupResult {
    data class Ready(val handle: VideoPlaybackHandle) : VideoPlaybackSetupResult
    data object Failed : VideoPlaybackSetupResult
}

/** Operator-visible transport truth; Preparing deliberately has no transport action. */
internal enum class VideoPlaybackUiState {
    PREPARING,
    PLAYING,
    PAUSED,
}

/** Opens the MediaProvider descriptor and initializes MediaPlayer entirely off the UI thread. */
private fun createVideoPlayback(request: VideoPlaybackSetupRequest): VideoPlaybackSetupResult {
    val player = android.media.MediaPlayer()
    var playbackSurface: Surface? = null
    return try {
        // Context+Uri synchronously opens an AssetFileDescriptor before prepareAsync can begin.
        // This function is owned by [videoPlaybackSetupLane]'s finite process worker pool.
        player.setDataSource(request.context.applicationContext, request.uri)
        val claimedSurface = checkNotNull(request.claimSurface()) { "Playback setup was retired" }
        playbackSurface = claimedSurface
        player.setSurface(claimedSurface)
        player.isLooping = true
        VideoPlaybackSetupResult.Ready(VideoPlaybackHandle(player, claimedSurface))
    } catch (_: Throwable) {
        runCatching { player.release() }
        playbackSurface?.let { surface -> runCatching { surface.release() } }
            ?: request.releaseSurface()
        VideoPlaybackSetupResult.Failed
    }
}

/** Two progressive workers; one blocked retired open cannot starve the newest video review. */
private val videoPlaybackSetupLane =
    LatestReviewSetupLane<VideoPlaybackSetupRequest, VideoPlaybackSetupResult>(
        work = ::createVideoPlayback,
        release = { result ->
            if (result is VideoPlaybackSetupResult.Ready) result.handle.release()
        },
    )

private sealed interface ReviewMediaState {
    data object Loading : ReviewMediaState
    data object RestartRequired : ReviewMediaState
    sealed interface Ready : ReviewMediaState {
        data class Still(val bitmap: ReviewBitmap) : Ready
        data class Video(val info: VideoInfo) : Ready
        data object Raw : Ready
    }
    data class Error(@StringRes val messageRes: Int) : ReviewMediaState
}

/**
 * Compose image plus exact ownership of its Android bitmap.
 *
 * Never-published decoder results are recycled promptly. Once transferred to Compose, however, the
 * bitmap becomes GC-owned: assigning replacement state merely schedules recomposition, so recycling
 * the previous bitmap at that instant can race the still-live draw node. There is no public Compose
 * callback that proves every renderer reference has retired.
 */
internal class ReviewBitmap(private val source: Bitmap) {
    val image: ImageBitmap = source.asImageBitmap()
    @Volatile private var compositionOwned = false

    fun transferToComposition(): ReviewBitmap {
        compositionOwned = true
        return this
    }

    fun dispose() {
        if (!compositionOwned && !source.isRecycled) source.recycle()
    }
}

private class ReviewBitmapRequest(
    context: Context,
    val uri: Uri,
    val maxDim: Int,
) {
    val context: Context = processReviewContext(context)
}

internal sealed interface ReviewBitmapLoad {
    data class Ready(val bitmap: ReviewBitmap) : ReviewBitmapLoad
    data object Failed : ReviewBitmapLoad
}

internal fun reviewBitmapLoad(decoded: Bitmap?): ReviewBitmapLoad =
    decoded?.let { ReviewBitmapLoad.Ready(ReviewBitmap(it)) } ?: ReviewBitmapLoad.Failed

/** Two finite workers bound unpublished 3000px ARGB results while one poisoned decoder is retired. */
private val reviewBitmapDecodeLane = LatestReviewSetupLane<ReviewBitmapRequest, ReviewBitmapLoad>(
    work = { request ->
        reviewBitmapLoad(decodeReviewBitmap(request.context, request.uri, request.maxDim))
    },
    release = { result -> if (result is ReviewBitmapLoad.Ready) result.bitmap.dispose() },
)

private fun ReviewMediaState.dispose() {
    (this as? ReviewMediaState.Ready.Still)?.bitmap?.dispose()
}

private fun ReviewMediaState.transferToComposition(): ReviewMediaState = also {
    (it as? ReviewMediaState.Ready.Still)?.bitmap?.transferToComposition()
}

internal sealed interface ReviewCriticalUiState {
    data object Loading : ReviewCriticalUiState
    data object RestartRequired : ReviewCriticalUiState
    data object Raw : ReviewCriticalUiState
    data class Error(@StringRes val messageRes: Int) : ReviewCriticalUiState
}

private const val REVIEW_PREVIEW_MAX_DIM = 3000

private const val REVIEW_BOTTOM_EDGE_DP = 14f
private const val REVIEW_BOTTOM_ACTION_DP = 48f
private const val REVIEW_BOTTOM_GAP_DP = 12f
private const val REVIEW_METADATA_MAX_WIDTH_DP = 280f

/**
 * Maximum width of the bottom-start metadata plate after reserving the bottom-end review action.
 *
 * The returned width is in logical Start/End geometry: Compose's existing BottomStart/BottomEnd
 * anchors mirror it in RTL without a second coordinate policy. With no action, the established
 * 280 dp wide-window cap is unchanged. Invalid or impossibly narrow inputs fail closed at zero.
 */
internal fun reviewBottomMetadataMaxWidthDp(
    windowWidthDp: Float,
    actionVisible: Boolean,
): Float {
    if (!windowWidthDp.isFinite() || windowWidthDp <= 0f) return 0f
    val actionReserve = if (actionVisible) REVIEW_BOTTOM_ACTION_DP + REVIEW_BOTTOM_GAP_DP else 0f
    return (windowWidthDp - 2f * REVIEW_BOTTOM_EDGE_DP - actionReserve)
        .coerceIn(0f, REVIEW_METADATA_MAX_WIDTH_DP)
}

private fun loadVideoInfo(context: Context, uri: Uri): VideoInfo? = runCatching {
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

/**
 * Bounded provider thumbnail for the gallery button.
 *
 * This path intentionally has no MediaMetadataRetriever frame fallback. Missing/invalid metadata,
 * provider failure, and a provider that violates the requested bound all produce the existing
 * placeholder; none can trigger a full native video-frame allocation.
 */
private fun loadVideoThumbnail(context: Context, uri: Uri, maxDim: Int): Bitmap? {
    val request = providerThumbnailRequest(maxDim) ?: return null
    val bitmap = runCatching {
        context.contentResolver.loadThumbnail(
            uri,
            android.util.Size(request.width, request.height),
            null,
        )
    }.getOrNull() ?: return null
    if (!providerThumbnailFitsRequest(bitmap.width, bitmap.height, request)) {
        bitmap.recycle()
        return null
    }
    return bitmap
}

internal fun reviewDecodeSampleSize(width: Int, height: Int, maxDim: Int): Int? {
    if (width <= 0 || height <= 0 || maxDim <= 0) return null
    val longest = max(width, height).toLong()
    val target = maxDim.toLong()
    var sample = 1L
    while ((longest + sample - 1L) / sample > target) {
        if (sample > Int.MAX_VALUE.toLong() / 2L) return null
        sample *= 2
    }
    return sample.toInt()
}

internal fun reviewDecodedFitsBound(width: Int, height: Int, maxDim: Int): Boolean =
    width > 0 && height > 0 && maxDim > 0 && width <= maxDim && height <= maxDim

private fun decodeReviewBitmap(context: Context, uri: Uri, maxDim: Int): Bitmap? = runCatching {
    val source = context.contentResolver.openInputStream(uri)?.use { stream ->
        spoolReviewSource(context.cacheDir, stream)
    }
        ?: return@runCatching null
    source.use sourceUse@ { spool ->
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        spool.openInputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
        val sample = reviewDecodeSampleSize(bounds.outWidth, bounds.outHeight, maxDim)
            ?: return@sourceUse null
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = spool.openInputStream().use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return@sourceUse null
        if (!reviewDecodedFitsBound(decoded.width, decoded.height, maxDim)) {
            decoded.recycle()
            return@sourceUse null
        }
        applyExifOrientation(spool, decoded)
    }
}.getOrNull()

/**
 * Honors the file's EXIF orientation on the decoded pixels. The ordinary processed lanes
 * pre-rotate pixels and stamp ORIENTATION_NORMAL (no-op here), but the hi-res passthrough lane
 * deliberately writes the HAL JPEG unrotated with the correction ONLY in EXIF (a ~200MP
 * decode-rotate would OOM the save path) — a plain decode showed those stills rotated in review
 * and its thumbnail while external viewers were correct (cycle-6 feature-dev review). The decode
 * above is already inSampleSize-capped, so the rotate runs on the bounded preview bitmap.
 */
private fun applyExifOrientation(source: ReviewSourceSpool, decoded: Bitmap): Bitmap {
    val orientation = runCatching {
        source.openInputStream().use { stream ->
            androidx.exifinterface.media.ExifInterface(stream)
                .getAttributeInt(
                    androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL,
                )
        }
    }.getOrNull() ?: androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
    val transform = reviewExifTransform(orientation) ?: return decoded
    val transformed = runCatching {
        applyReviewExifTransform(decoded, transform)
    }.getOrNull() ?: return decoded
    if (transformed !== decoded) decoded.recycle()
    return transformed
}

internal data class ReviewExifTransform(val rotationDegrees: Float, val flipHorizontal: Boolean)

/** Complete EXIF-orientation mapping for current app output and admitted ownerless legacy media. */
internal fun reviewExifTransform(orientation: Int): ReviewExifTransform? = when (orientation) {
    androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL ->
        ReviewExifTransform(0f, flipHorizontal = true)
    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 ->
        ReviewExifTransform(180f, flipHorizontal = false)
    androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_VERTICAL ->
        ReviewExifTransform(180f, flipHorizontal = true)
    androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSPOSE ->
        ReviewExifTransform(90f, flipHorizontal = true)
    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 ->
        ReviewExifTransform(90f, flipHorizontal = false)
    androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSVERSE ->
        ReviewExifTransform(270f, flipHorizontal = true)
    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 ->
        ReviewExifTransform(270f, flipHorizontal = false)
    else -> null
}

internal fun applyReviewExifTransform(decoded: Bitmap, transform: ReviewExifTransform): Bitmap {
    val swapsDimensions = transform.rotationDegrees == 90f || transform.rotationDegrees == 270f
    val outputWidth = if (swapsDimensions) decoded.height else decoded.width
    val outputHeight = if (swapsDimensions) decoded.width else decoded.height
    val matrix = android.graphics.Matrix().apply {
        setTranslate(-decoded.width / 2f, -decoded.height / 2f)
        postRotate(transform.rotationDegrees)
        if (transform.flipHorizontal) postScale(-1f, 1f)
        postTranslate(outputWidth / 2f, outputHeight / 2f)
    }
    val output = createBitmap(
        outputWidth,
        outputHeight,
        decoded.config ?: Bitmap.Config.ARGB_8888,
    )
    android.graphics.Canvas(output).drawBitmap(decoded, matrix, null)
    return output
}

private data class ReviewMetadata(
    val name: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    /** "ISO 1250 · 1/300s · 300 mm" when the file carries exposure EXIF (JPEG/DNG); null otherwise. */
    val exifLine: String?,
)

private class ReviewDescriptorRequest(context: Context, val uri: Uri) {
    val context: Context = processReviewContext(context)
}

private data class ReviewDescriptor(
    val kind: ReviewMediaKind,
    val videoInfo: VideoInfo?,
    val metadata: ReviewMetadata?,
)

private fun loadMetadata(context: Context, uri: Uri, kind: ReviewMediaKind): ReviewMetadata? =
    runCatching {
        // Video dimensions/rotation already come from MediaMetadataRetriever above. Do not open the
        // MP4 through ExifInterface a second time for still-only exposure fields.
        val exifLine = if (kind == ReviewMediaKind.VIDEO) null else runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val exif = androidx.exifinterface.media.ExifInterface(input)
                val iso = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                val expS = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_TIME)?.toDoubleOrNull()
                val focal35 = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)
                val parts = buildList {
                    iso?.let { add("ISO $it") }
                    // Through the canonical formatter, not a local 1/x snap: that snap emitted
                    // the nonsensical "1/1s" in [0.667 s, 1 s) — reachable, stills clamp at 4 s
                    // — and skipped NICE_SHUTTER_DENOM, so a shot the OSD showed as 1/125s read
                    // 1/128s here.
                    expS?.let { add(formatShutterSpeed((it * 1_000_000_000.0).toLong())) }
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

/** One finite URI acquisition owns MIME, video geometry, and the metadata plate together. */
private fun loadReviewDescriptor(request: ReviewDescriptorRequest): ReviewDescriptor {
    val kind = reviewMediaKind(
        runCatching { request.context.contentResolver.getType(request.uri) }.getOrNull(),
    )
    return ReviewDescriptor(
        kind = kind,
        videoInfo = if (kind == ReviewMediaKind.VIDEO) {
            loadVideoInfo(request.context, request.uri)
        } else {
            null
        },
        metadata = loadMetadata(request.context, request.uri, kind),
    )
}

private val reviewDescriptorLane = LatestReviewSetupLane<ReviewDescriptorRequest, ReviewDescriptor>(
    work = ::loadReviewDescriptor,
    release = {},
)

private data class LoadedReview(val state: ReviewMediaState, val metadata: ReviewMetadata?)

private suspend fun loadReviewMedia(
    context: Context,
    uri: Uri,
    descriptorOwner: Any,
    decodeOwner: Any,
): LoadedReview? {
    var descriptor: ReviewDescriptor? = null
    val descriptorOutcome = reviewDescriptorLane.run(
        descriptorOwner,
        ReviewDescriptorRequest(context, uri),
    ) { descriptor = it }
    when (descriptorOutcome) {
        LatestReviewSetupLane.Outcome.RETIRED -> return null
        LatestReviewSetupLane.Outcome.TIMED_OUT -> {
            return LoadedReview(ReviewMediaState.Error(R.string.review_error_timed_out), null)
        }
        LatestReviewSetupLane.Outcome.CAPACITY_EXHAUSTED -> {
            return LoadedReview(ReviewMediaState.RestartRequired, null)
        }
        LatestReviewSetupLane.Outcome.PUBLISHED -> Unit
    }
    val loaded = checkNotNull(descriptor)
    val state = when (loaded.kind) {
        ReviewMediaKind.RAW -> ReviewMediaState.Ready.Raw
        ReviewMediaKind.VIDEO -> loaded.videoInfo
            ?.let { ReviewMediaState.Ready.Video(it) }
            ?: ReviewMediaState.Error(R.string.review_error_open_video)
        ReviewMediaKind.STILL -> {
            // A capped first decode avoids a 50 MP ARGB allocation (~200 MB) before Compose/GPU copies.
            var decoded: ReviewMediaState? = null
            val bitmapOutcome = reviewBitmapDecodeLane.run(
                decodeOwner,
                ReviewBitmapRequest(context, uri, REVIEW_PREVIEW_MAX_DIM),
            ) { result ->
                decoded = when (result) {
                    is ReviewBitmapLoad.Ready -> ReviewMediaState.Ready.Still(result.bitmap)
                    ReviewBitmapLoad.Failed -> ReviewMediaState.Error(R.string.review_error_open_image)
                }
            }
            when (bitmapOutcome) {
                LatestReviewSetupLane.Outcome.RETIRED -> return null
                LatestReviewSetupLane.Outcome.TIMED_OUT -> {
                    ReviewMediaState.Error(R.string.review_error_timed_out)
                }
                LatestReviewSetupLane.Outcome.CAPACITY_EXHAUSTED -> {
                    ReviewMediaState.RestartRequired
                }
                LatestReviewSetupLane.Outcome.PUBLISHED -> decoded ?: return null
            }
        }
    }
    return LoadedReview(state, loaded.metadata)
}

/**
 * Exact visual truth for the last-capture tile.
 *
 * A non-null URI is never represented by [Empty]: while MIME and pixels are acquired it is
 * [Loading], and a missing/invalid thumbnail becomes [Failed] without pretending the capture
 * itself disappeared. [kind] is nullable only before the bounded MIME lookup completes.
 */
internal sealed interface GalleryThumbState {
    val kind: ReviewMediaKind?

    data object Empty : GalleryThumbState {
        override val kind: ReviewMediaKind? = null
    }

    data class Loading(override val kind: ReviewMediaKind? = null) : GalleryThumbState

    data class Ready(
        override val kind: ReviewMediaKind,
        val bitmap: ReviewBitmap? = null,
    ) : GalleryThumbState {
        init {
            require((kind == ReviewMediaKind.RAW) == (bitmap == null)) {
                "Only RAW gallery content may be ready without pixels"
            }
        }
    }

    data class Failed(override val kind: ReviewMediaKind?) : GalleryThumbState
}

private fun GalleryThumbState.transferToComposition(): GalleryThumbState = also {
    (it as? GalleryThumbState.Ready)?.bitmap?.transferToComposition()
}

private fun GalleryThumbState.dispose() {
    (this as? GalleryThumbState.Ready)?.bitmap?.dispose()
}

private const val GALLERY_THUMB_MAX_DIM = 240

private fun loadGalleryThumbKind(request: ReviewDescriptorRequest): ReviewMediaKind =
    reviewMediaKind(
        runCatching { request.context.contentResolver.getType(request.uri) }.getOrNull(),
    )

private class GalleryThumbRequest(
    context: Context,
    val uri: Uri,
    val kind: ReviewMediaKind,
) {
    val context: Context = processReviewContext(context)
}

private fun loadGalleryThumb(request: GalleryThumbRequest): GalleryThumbState {
    val bitmap = when (request.kind) {
        ReviewMediaKind.RAW -> return GalleryThumbState.Ready(ReviewMediaKind.RAW)
        ReviewMediaKind.VIDEO -> loadVideoThumbnail(request.context, request.uri, GALLERY_THUMB_MAX_DIM)
        ReviewMediaKind.STILL -> decodeReviewBitmap(request.context, request.uri, GALLERY_THUMB_MAX_DIM)
    }
    return bitmap
        ?.let { GalleryThumbState.Ready(request.kind, ReviewBitmap(it)) }
        ?: GalleryThumbState.Failed(request.kind)
}

private val galleryThumbKindLane = LatestReviewSetupLane<ReviewDescriptorRequest, ReviewMediaKind>(
    work = ::loadGalleryThumbKind,
    release = {},
)

private val galleryThumbLane = LatestReviewSetupLane<GalleryThumbRequest, GalleryThumbState>(
    work = ::loadGalleryThumb,
    release = GalleryThumbState::dispose,
)

/** Tappable thumbnail of the last review item; RAW uses a label rather than a fake/failed image. */
@Composable
fun GalleryThumb(
    uri: Uri?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    provenance: MediaProvenance = MediaProvenance.APP_OWNED,
    enabled: Boolean = true,
) {
    val context = LocalContext.current
    val thumbOwner = remember(uri) { Any() }
    val stateHolder = remember(uri) {
        mutableStateOf<GalleryThumbState>(
            if (uri == null) GalleryThumbState.Empty else GalleryThumbState.Loading(),
        )
    }
    val state by stateHolder
    fun replaceState(next: GalleryThumbState) {
        val previous = stateHolder.value
        stateHolder.value = next.transferToComposition()
        if (previous !== next) previous.dispose()
    }
    LaunchedEffect(uri) {
        if (uri != null) {
            var kind: ReviewMediaKind? = null
            when (galleryThumbKindLane.run(
                thumbOwner,
                ReviewDescriptorRequest(context, uri),
            ) { kind = it }) {
                LatestReviewSetupLane.Outcome.RETIRED -> return@LaunchedEffect
                LatestReviewSetupLane.Outcome.TIMED_OUT,
                LatestReviewSetupLane.Outcome.CAPACITY_EXHAUSTED,
                -> replaceState(GalleryThumbState.Failed(null))
                LatestReviewSetupLane.Outcome.PUBLISHED -> {
                    val resolvedKind = checkNotNull(kind)
                    replaceState(GalleryThumbState.Loading(resolvedKind))
                    galleryThumbLane.run(
                        thumbOwner,
                        GalleryThumbRequest(context, uri, resolvedKind),
                        ::replaceState,
                    ).let { outcome ->
                        if (
                            outcome == LatestReviewSetupLane.Outcome.TIMED_OUT ||
                            outcome == LatestReviewSetupLane.Outcome.CAPACITY_EXHAUSTED
                        ) {
                            replaceState(GalleryThumbState.Failed(resolvedKind))
                        }
                    }
                }
            }
        }
    }
    DisposableEffect(thumbOwner) {
        onDispose {
            galleryThumbKindLane.invalidate(thumbOwner)
            galleryThumbLane.invalidate(thumbOwner)
            stateHolder.value.dispose()
        }
    }
    GalleryThumbSurface(
        state = state,
        onClick = onClick,
        modifier = modifier,
        provenance = provenance,
        enabled = enabled,
    )
}

/** Production gallery-tile composition, split out so every real visual branch is render-tested. */
@Composable
internal fun GalleryThumbSurface(
    state: GalleryThumbState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    provenance: MediaProvenance = MediaProvenance.APP_OWNED,
    enabled: Boolean = true,
) {
    val galleryDesc = stringResource(
        galleryReviewContentDescription(state != GalleryThumbState.Empty, state.kind, provenance),
    )
    val galleryState = stringResource(
        when (state) {
            GalleryThumbState.Empty -> R.string.a11y_gallery_state_empty
            is GalleryThumbState.Loading -> R.string.a11y_gallery_state_loading
            is GalleryThumbState.Ready -> R.string.a11y_gallery_state_ready
            is GalleryThumbState.Failed -> R.string.a11y_gallery_state_failed
        },
    )
    Box(
        modifier = modifier
            .size(52.dp)
            // Keep the last-capture identity visible while REC owns the camera, but do not present
            // a live affordance that opens an opaque review over the only Stop control. The whole
            // target dims together; the stored thumbnail remains recognizable as state, not action.
            .alpha(if (enabled) 1f else 0.35f)
            .clip(RoundedCornerShape(14.dp))
            .background(CameraColors.Pill)
            .border(1.dp, CameraColors.Hairline, RoundedCornerShape(14.dp))
            .semantics {
                contentDescription = galleryDesc
                stateDescription = galleryState
                role = Role.Button
                if (!enabled) disabled()
            }
            // Null is an actionable restore state: the caller requests contextual visual-media
            // access and re-runs the previous-install capture query.
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            GalleryThumbState.Empty -> EmptyGalleryPictogram()
            is GalleryThumbState.Loading -> GalleryMediaPlaceholder(state.kind, loading = true)
            is GalleryThumbState.Failed -> GalleryMediaPlaceholder(state.kind, loading = false)
            is GalleryThumbState.Ready -> if (state.kind == ReviewMediaKind.RAW) {
                RawGalleryPlaceholder(loading = false, failed = false)
            } else {
                Image(
                    bitmap = checkNotNull(state.bitmap).image,
                    // The parent is the one semantic Button; the pixels are decorative and must not
                    // produce a duplicate TalkBack announcement.
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun EmptyGalleryPictogram() {
    // Reserved for "no capture yet": a complete photo glyph, matching the app's other
    // multi-primitive hand-drawn icons. Real captures never take this branch.
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

@Composable
private fun GalleryMediaPlaceholder(kind: ReviewMediaKind?, loading: Boolean) {
    if (kind == ReviewMediaKind.RAW) {
        RawGalleryPlaceholder(loading = loading, failed = !loading)
        return
    }
    Canvas(Modifier.size(26.dp)) {
        val color = CameraColors.TextSecondary
        val stroke = Stroke(width = size.minDimension * 0.07f)
        when (kind) {
            ReviewMediaKind.STILL -> {
                drawRoundRect(
                    color = color,
                    cornerRadius = CornerRadius(size.minDimension * 0.10f),
                    style = stroke,
                )
                drawCircle(
                    color,
                    radius = size.minDimension * 0.08f,
                    center = Offset(size.width * 0.30f, size.height * 0.30f),
                )
            }
            ReviewMediaKind.VIDEO -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(0f, size.height * 0.10f),
                    size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.70f),
                    cornerRadius = CornerRadius(size.minDimension * 0.10f),
                    style = stroke,
                )
                val play = Path().apply {
                    moveTo(size.width * 0.40f, size.height * 0.28f)
                    lineTo(size.width * 0.68f, size.height * 0.45f)
                    lineTo(size.width * 0.40f, size.height * 0.62f)
                    close()
                }
                drawPath(play, color)
            }
            null -> drawCircle(
                color = color,
                radius = size.minDimension * 0.28f,
                center = Offset(size.width * 0.50f, size.height * 0.40f),
                style = stroke,
            )
            ReviewMediaKind.RAW -> Unit
        }
        if (loading) {
            repeat(3) { index ->
                drawCircle(
                    color = color,
                    radius = size.minDimension * 0.045f,
                    center = Offset(size.width * (0.38f + index * 0.12f), size.height * 0.92f),
                )
            }
        } else {
            drawLine(
                color,
                Offset(size.width * 0.34f, size.height * 0.78f),
                Offset(size.width * 0.66f, size.height),
                strokeWidth = stroke.width,
            )
            drawLine(
                color,
                Offset(size.width * 0.66f, size.height * 0.78f),
                Offset(size.width * 0.34f, size.height),
                strokeWidth = stroke.width,
            )
        }
    }
}

@Composable
private fun RawGalleryPlaceholder(loading: Boolean, failed: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.format_raw),
            color = CameraColors.TextPrimary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = when {
                loading -> "…"
                failed -> "×"
                else -> stringResource(R.string.format_dng)
            },
            color = CameraColors.TextSecondary,
            style = MaterialTheme.typography.labelSmall,
        )
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
    provenance: MediaProvenance = MediaProvenance.APP_OWNED,
    // Shooting-screen glyph rule applied to review: compact/short labels counter-rotate so they
    // read upright in the landscape hold the 300 mm rig encourages; wide boxes (the metadata
    // block) stay screen-fixed — Modifier.rotate is a draw transform, not a re-layout, and a
    // rotated wide box pokes out of its layout slot.
    overlayRotation: Float = 0f,
) {
    val a11yVideoReview = stringResource(R.string.a11y_video_review)
    val a11yCloseReview = stringResource(R.string.a11y_close_review)
    val context = LocalContext.current
    var loadAttempt by remember(uri) { mutableIntStateOf(0) }
    val descriptorOwner = remember(uri) { Any() }
    val decodeOwner = remember(uri) { Any() }
    val mediaStateHolder = remember(uri) {
        mutableStateOf<ReviewMediaState>(ReviewMediaState.Loading)
    }
    var mediaState by mediaStateHolder
    var metadata by remember(uri) { mutableStateOf<ReviewMetadata?>(null) }
    fun replaceMediaState(next: ReviewMediaState) {
        val previous = mediaStateHolder.value
        mediaStateHolder.value = next.transferToComposition()
        if (previous !== next) previous.dispose()
    }
    LaunchedEffect(uri, loadAttempt) {
        replaceMediaState(ReviewMediaState.Loading)
        metadata = null
        var loaded: LoadedReview? = null
        try {
            loaded = loadReviewMedia(context, uri, descriptorOwner, decodeOwner)
            loaded?.let { result ->
                metadata = result.metadata
                replaceMediaState(result.state)
            }
            loaded = null
        } finally {
            // Cancellation can win after the lane returned but before state publication. That
            // bitmap never reached Compose and still has an exact eager recycle owner.
            loaded?.state?.dispose()
        }
    }
    DisposableEffect(descriptorOwner, decodeOwner) {
        onDispose {
            reviewDescriptorLane.invalidate(descriptorOwner)
            reviewBitmapDecodeLane.invalidate(decodeOwner)
            mediaStateHolder.value.dispose()
        }
    }
    val videoInfo = (mediaState as? ReviewMediaState.Ready.Video)?.info
    val gestureMediaReady =
        mediaState is ReviewMediaState.Ready.Still || mediaState is ReviewMediaState.Ready.Video
    val rawReady = mediaState is ReviewMediaState.Ready.Raw
    val deleteCopy = mediaDeleteConfirmationCopy(deleteScope, rawReady)
    val deleteTitle = stringResource(deleteCopy.title)
    val provenanceLabel = reviewProvenanceLabel(provenance)?.let { stringResource(it) }
    val zoom4Action = stringResource(R.string.a11y_zoom_4x)
    val zoom8Action = stringResource(R.string.a11y_zoom_8x)
    val resetZoomAction = stringResource(R.string.a11y_reset_zoom)
    val panLeftAction = stringResource(R.string.a11y_pan_left)
    val panRightAction = stringResource(R.string.a11y_pan_right)
    val panUpAction = stringResource(R.string.a11y_pan_up)
    val panDownAction = stringResource(R.string.a11y_pan_down)
    val doubleTapSlopPx = reviewDoubleTapSlop(context)
    var scale by remember { mutableFloatStateOf(1f) }
    val reviewZoomDescription = stringResource(R.string.a11y_review_zoom, reviewScaleLabel(scale))
    var offset by remember { mutableStateOf(Offset.Zero) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleteFocusReturn by remember { mutableIntStateOf(0) }
    val deleteFocusRequester = remember { FocusRequester() }
    val dismissDelete = {
        confirmDelete = false
        deleteFocusReturn++
        Unit
    }
    val playbackRetirement = remember(uri) { AtomicReference<(() -> Unit)?>(null) }
    BackHandler(enabled = confirmDelete, onBack = dismissDelete)
    BackHandler(enabled = !confirmDelete) {
        playbackRetirement.getAndSet(null)?.invoke()
        onClose()
    }
    val closeFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { closeFocusRequester.requestFocus() }
    LaunchedEffect(deleteFocusReturn) {
        if (deleteFocusReturn > 0) deleteFocusRequester.requestFocus()
    }
    // In-review playback (videos): a TextureView + MediaPlayer — NOT VideoView, whose SurfaceView
    // sits behind the window and is occluded by this overlay's opaque black background (the same
    // trap the camera preview hit). Tap toggles play/pause; the clip loops.
    val playerRef = remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    val playbackSetupScope = rememberCoroutineScope()
    var playbackUiState by remember(uri) { mutableStateOf(VideoPlaybackUiState.PREPARING) }
    val reviewZoomAction = stringResource(reviewZoomActionResource(scale))
    val playbackActionRes = videoPlaybackActionResource(playbackUiState)
    val playbackAction = playbackActionRes?.let { stringResource(it) }
    val playbackState = stringResource(videoPlaybackStateResource(playbackUiState))
    val reviewPaneTitle = stringResource(
        if (rawReady) R.string.a11y_raw_capture_review else R.string.a11y_media_review,
    )
    // Stock-gallery-style dismiss: at 1x, a vertical drag slides the image and past a threshold
    // closes the review; below it springs back. Zoomed in, vertical pan just pans.
    var dismissDrag by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(uri, loadAttempt) {
        scale = 1f
        offset = Offset.Zero
        dismissDrag = 0f
        playbackUiState = VideoPlaybackUiState.PREPARING
    }

    fun toggleVideoPlayback(): Boolean {
        val player = playerRef.value ?: return false
        val target = videoPlaybackToggleTarget(playbackUiState) ?: return false
        return runCatching {
            when (target) {
                VideoPlaybackUiState.PLAYING -> player.start()
                VideoPlaybackUiState.PAUSED -> player.pause()
                VideoPlaybackUiState.PREPARING -> error("Preparing has no transport action")
            }
        }.onSuccess {
            playbackUiState = target
        }.onFailure {
            playbackUiState = VideoPlaybackUiState.PAUSED
            replaceMediaState(ReviewMediaState.Error(R.string.review_error_play_video))
        }.isSuccess
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .modalFocusBoundary()
            .background(CameraColors.Background)
            .semantics {
                paneTitle = reviewPaneTitle
                isTraversalGroup = true
            },
        contentAlignment = Alignment.Center,
    ) {
        val stillImage = (mediaState as? ReviewMediaState.Ready.Still)?.bitmap?.image
        val stillGeometry = remember(
            constraints.maxWidth,
            constraints.maxHeight,
            stillImage?.width,
            stillImage?.height,
        ) {
            reviewStillGeometry(
                viewportWidth = constraints.maxWidth,
                viewportHeight = constraints.maxHeight,
                bitmapWidth = stillImage?.width ?: 0,
                bitmapHeight = stillImage?.height ?: 0,
            )
        }
        val clampedReviewOffset = stillGeometry.clampOffset(offset, scale)
        // Size/orientation and async decode transitions can change fitted travel without a pointer
        // event. Draw with the derived clamp immediately, then publish it back into gesture state.
        LaunchedEffect(stillGeometry, scale) {
            if (offset != clampedReviewOffset) offset = clampedReviewOffset
        }
        fun setReviewScale(target: Float): Boolean {
            val next = target.takeIf { it.isFinite() }?.coerceIn(1f, 12f) ?: 1f
            scale = next
            offset = stillGeometry.clampOffset(Offset.Zero, next)
            dismissDrag = 0f
            return true
        }
        val bottomActionVisible =
            (videoInfo != null && playbackAction != null) || mediaState is ReviewMediaState.Ready.Still
        val metadataMaxWidth = reviewBottomMetadataMaxWidthDp(
            windowWidthDp = maxWidth.value,
            actionVisible = bottomActionVisible,
        ).dp
        // Keep the raw media gesture surface as a sibling behind the explicit controls. A control
        // tap therefore cannot also bubble through this loop and toggle playback a second time.
        Box(
            modifier = Modifier
                .fillMaxSize()
            .pointerInput(gestureMediaReady, videoInfo != null, playbackUiState, stillGeometry) {
                if (!gestureMediaReady) return@pointerInput
                // ONE gesture loop owns pinch + pan + swipe-dismiss + tap/double-tap/long-press.
                // Two sibling pointerInput blocks fought here exactly like CameraScreen's tap-vs-
                // pinch conflict: the pinch/pan loop consumed EVERY change, which cancelled the
                // sibling detectTapGestures' waitForUpOrCancellation, so video tap-to-pause almost
                // never fired. Merged, tap-vs-pinch-vs-drag is decided on finger-up (the pattern in
                // CameraScreen.kt's viewfinder loop) and long-press/double-tap are timed inline.
                val isVideo = videoInfo != null
                var firstTapCandidate: ReviewTapCandidate? = null
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
                            val previousScale = scale
                            val previousOffset = offset
                            val next = 8f
                            scale = next
                            offset = stillGeometry.centerOn(
                                point = down.position,
                                targetScale = next,
                                currentScale = previousScale,
                                currentOffset = previousOffset,
                            )
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
                            val transform = stillGeometry.transformGesture(
                                currentScale = scale,
                                currentOffset = offset,
                                centroid = event.calculateCentroid(useCurrent = false),
                                pan = pan,
                                zoomChange = zoom,
                            )
                            scale = transform.scale
                            offset = transform.offset
                        } else if (scale > 1f) {
                            offset = stillGeometry.clampOffset(offset + pan, scale)
                        }
                        if (scale > 1f) {
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
                            if (playbackUiState != VideoPlaybackUiState.PREPARING) {
                                toggleVideoPlayback()
                            }
                        } else {
                            val tapDecision = reviewTapSequenceDecision(
                                previous = firstTapCandidate,
                                cleanTap = true,
                                downTimeMillis = downTime,
                                upTimeMillis = upTime,
                                position = tapPos,
                                minimumIntervalMillis = viewConfiguration.doubleTapMinTimeMillis,
                                maximumIntervalMillis = viewConfiguration.doubleTapTimeoutMillis,
                                // Inter-contact placement has its own platform threshold. Touch
                                // slop above remains the one-contact drag discriminator.
                                maximumDistance = doubleTapSlopPx,
                            )
                            firstTapCandidate = tapDecision.nextCandidate
                            if (tapDecision.isDoubleTap) {
                                val previousScale = scale
                                val previousOffset = offset
                                val next = nextReviewScale(scale)
                                scale = next
                                offset = if (next <= 1f) Offset.Zero
                                else stillGeometry.centerOn(
                                    point = tapPos,
                                    targetScale = next,
                                    currentScale = previousScale,
                                    currentOffset = previousOffset,
                                )
                            }
                        }
                        dismissDrag = 0f
                    } else {
                        // A drag, pinch, or long press owns this gesture and breaks any pending tap
                        // pair; the next clean tap starts a fresh candidate.
                        if (!isVideo) firstTapCandidate = null
                        if (scale <= 1f) {
                            if (abs(dismissDrag) > size.height * 0.16f) onClose()
                            else dismissDrag = 0f
                        } else {
                            dismissDrag = 0f
                        }
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
                val setupOwner = remember { Any() }
                val setupJob = remember { mutableStateOf<Job?>(null) }
                val setupRequest = remember { AtomicReference<VideoPlaybackSetupRequest?>(null) }
                val playbackOwner = remember {
                    ExactHandlePrepareOwner<VideoPlaybackHandle>(
                        timeoutMs = REVIEW_WORK_TERMINAL_TIMEOUT_MS,
                        schedule = { timeoutMs, onTimeout ->
                            val job = playbackSetupScope.launch {
                                delay(timeoutMs)
                                onTimeout()
                            }
                            ReviewDeadlineRegistration { job.cancel() }
                        },
                        dispose = { handle ->
                            if (playerRef.value === handle.player) playerRef.value = null
                            handle.release()
                        },
                    )
                }
                fun retireSetup() {
                    setupJob.value?.cancel()
                    setupJob.value = null
                    videoPlaybackSetupLane.invalidate(setupOwner)
                    setupRequest.getAndSet(null)?.releaseSurface()
                }
                AndroidView(
                    modifier = Modifier
                        .aspectRatio(aspect.coerceAtLeast(0.01f))
                        .graphicsLayer(
                            translationY = dismissDrag,
                            alpha = (1f - abs(dismissDrag) / 1400f).coerceIn(0.3f, 1f),
                        )
                        .semantics {
                            contentDescription = a11yVideoReview
                            stateDescription = playbackState
                            if (playbackUiState == VideoPlaybackUiState.PREPARING) {
                                liveRegion = LiveRegionMode.Polite
                            }
                            if (playbackAction != null) {
                                role = Role.Button
                                onClick(label = playbackAction) {
                                    toggleVideoPlayback()
                                }
                            }
                        },
                    factory = { ctx ->
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

                                    playbackOwner.releaseCurrent()
                                    retireSetup()
                                    playbackUiState = VideoPlaybackUiState.PREPARING
                                    val playbackSurface = try {
                                        Surface(st)
                                    } catch (_: Throwable) {
                                        playbackUiState = VideoPlaybackUiState.PAUSED
                                        replaceMediaState(ReviewMediaState.Error(R.string.review_error_play_video))
                                        return
                                    }
                                    val request = VideoPlaybackSetupRequest(ctx, uri, playbackSurface)
                                    setupRequest.set(request)

                                    // Context+Uri synchronously opens MediaProvider before
                                    // prepareAsync. The latest-wins lane keeps that Binder call off
                                    // main; Back/disposal invalidates immediately and a late result
                                    // releases its player + Surface without publishing.
                                    setupJob.value = playbackSetupScope.launch {
                                        val outcome = videoPlaybackSetupLane.run(
                                            owner = setupOwner,
                                            input = request,
                                        ) { result ->
                                            setupJob.value = null
                                            setupRequest.compareAndSet(request, null)
                                            when (result) {
                                                VideoPlaybackSetupResult.Failed -> {
                                                    playbackUiState = VideoPlaybackUiState.PAUSED
                                                    replaceMediaState(
                                                        ReviewMediaState.Error(R.string.review_error_play_video),
                                                    )
                                                }
                                                is VideoPlaybackSetupResult.Ready -> {
                                                    val handle = result.handle
                                                    val mp = handle.player
                                                    playbackOwner.replace(handle)
                                                    playerRef.value = mp

                                                    fun failPlayback(messageRes: Int) {
                                                        if (!playbackOwner.release(handle)) return
                                                        playbackUiState = VideoPlaybackUiState.PAUSED
                                                        replaceMediaState(
                                                            ReviewMediaState.Error(messageRes),
                                                        )
                                                    }

                                                    runCatching {
                                                        // Do not depend on which Looper MediaPlayer
                                                        // selected when constructed on its worker.
                                                        // Every event crosses explicitly to the
                                                        // composition-owned main scope, then rechecks
                                                        // this TextureView generation.
                                                        mp.setOnPreparedListener { p ->
                                                            playbackSetupScope.launch prepared@{
                                                                if (!playbackOwner.prepared(handle)) {
                                                                    return@prepared
                                                                }
                                                                runCatching { p.start() }
                                                                    .onSuccess {
                                                                        playbackUiState = VideoPlaybackUiState.PLAYING
                                                                    }
                                                                    .onFailure {
                                                                        failPlayback(R.string.review_error_play_video)
                                                                    }
                                                            }
                                                        }
                                                        mp.setOnErrorListener { _, _, _ ->
                                                            playbackSetupScope.launch {
                                                                failPlayback(R.string.review_error_play_video)
                                                            }
                                                            true
                                                        }
                                                        playbackOwner.arm(handle) {
                                                            playbackUiState = VideoPlaybackUiState.PAUSED
                                                            replaceMediaState(
                                                                ReviewMediaState.Error(
                                                                    R.string.review_error_timed_out,
                                                                ),
                                                            )
                                                        }
                                                        mp.prepareAsync()
                                                    }.onFailure {
                                                        failPlayback(R.string.review_error_play_video)
                                                    }
                                                }
                                            }
                                        }
                                        if (
                                            outcome == LatestReviewSetupLane.Outcome.TIMED_OUT ||
                                            outcome == LatestReviewSetupLane.Outcome.CAPACITY_EXHAUSTED
                                        ) {
                                            setupJob.value = null
                                            setupRequest.compareAndSet(request, null)
                                            request.releaseSurface()
                                            playbackUiState = VideoPlaybackUiState.PAUSED
                                            replaceMediaState(
                                                if (outcome == LatestReviewSetupLane.Outcome.TIMED_OUT) {
                                                    ReviewMediaState.Error(R.string.review_error_timed_out)
                                                } else {
                                                    ReviewMediaState.RestartRequired
                                                },
                                            )
                                        }
                                    }
                                }

                                override fun onSurfaceTextureSizeChanged(
                                    st: android.graphics.SurfaceTexture,
                                    w: Int,
                                    h: Int,
                                ) = Unit

                                override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                                    playbackUiState = VideoPlaybackUiState.PREPARING
                                    retireSetup()
                                    playbackOwner.releaseCurrent()
                                    return true
                                }

                                override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) = Unit
                            }
                        }
                    },
                )
                val retirePlayback: () -> Unit = {
                    retireSetup()
                    playbackOwner.releaseCurrent()
                }
                DisposableEffect(playbackOwner) {
                    playbackRetirement.set(retirePlayback)
                    onDispose {
                        playbackRetirement.compareAndSet(retirePlayback, null)
                        retirePlayback()
                    }
                }
            }
            if (playbackUiState == VideoPlaybackUiState.PAUSED) {
                // Paused indicator: a simple ▶ so it's obvious a tap resumes.
                Canvas(Modifier.size(64.dp)) {
                    // Deliberately its own black, NOT the shared HudPlate. What sits on this disc is a
                    // 64 dp white GLYPH, not text, so its floor is WCAG's 3:1 non-text minimum rather
                    // than the plate's 4.5:1 — and 0.45 black over a white frame measures 3.36:1 for
                    // white by the app's own formula (HudContrastTest pins that same 0.45/white pair
                    // as a text FAILURE, which it is). Written out at the site, with the alpha here
                    // rather than as a `.copy` of a scrim token, per the rule in HudPlate's KDoc.
                    // OPEN: whether it should fold into HudPlate anyway needs a device look at a
                    // paused bright video frame — folding it is a visual call, not a contrast fix.
                    drawCircle(Color.Black.copy(alpha = 0.45f), radius = size.minDimension / 2f)
                    val tri = androidx.compose.ui.graphics.Path().apply {
                        moveTo(size.width * 0.4f, size.height * 0.3f)
                        lineTo(size.width * 0.4f, size.height * 0.7f)
                        lineTo(size.width * 0.74f, size.height * 0.5f)
                        close()
                    }
                    drawPath(tri, CameraColors.TextPrimary)
                }
            }
        } else {
        val still = mediaState as? ReviewMediaState.Ready.Still
        if (still != null) {
            val position = stillGeometry.position(scale, offset)
            val positionDescription = stringResource(position.stringRes)
            val fullReviewState = if (scale > 1.05f) {
                stringResource(R.string.a11y_review_zoom_position, reviewZoomDescription, positionDescription)
            } else {
                reviewZoomDescription
            }
            fun pan(direction: ReviewPanDirection): Boolean {
                val target = stillGeometry.panTarget(offset, scale, direction) ?: return false
                offset = target
                dismissDrag = 0f
                return true
            }
            val panActions = buildList {
                if (stillGeometry.panTarget(offset, scale, ReviewPanDirection.LEFT) != null) {
                    add(ReviewPanAccessibilityAction(ReviewPanDirection.LEFT, panLeftAction))
                }
                if (stillGeometry.panTarget(offset, scale, ReviewPanDirection.RIGHT) != null) {
                    add(ReviewPanAccessibilityAction(ReviewPanDirection.RIGHT, panRightAction))
                }
                if (stillGeometry.panTarget(offset, scale, ReviewPanDirection.UP) != null) {
                    add(ReviewPanAccessibilityAction(ReviewPanDirection.UP, panUpAction))
                }
                if (stillGeometry.panTarget(offset, scale, ReviewPanDirection.DOWN) != null) {
                    add(ReviewPanAccessibilityAction(ReviewPanDirection.DOWN, panDownAction))
                }
            }
            ReviewStillImage(
                bitmap = still.bitmap.image,
                contentDescription = stringResource(R.string.a11y_photo_review),
                scale = scale,
                offset = offset,
                dismissDrag = dismissDrag,
                geometry = stillGeometry,
                modifier = Modifier.reviewStillNonTouchControls(
                    state = fullReviewState,
                    panActions = panActions,
                    onPan = ::pan,
                    otherActions = listOf(
                        CustomAccessibilityAction(zoom4Action) { setReviewScale(4f) },
                        CustomAccessibilityAction(zoom8Action) { setReviewScale(8f) },
                        CustomAccessibilityAction(resetZoomAction) { setReviewScale(1f) },
                    ),
                ),
            )
        } else {
            val criticalState = when (val current = mediaState) {
                ReviewMediaState.Loading -> ReviewCriticalUiState.Loading
                ReviewMediaState.RestartRequired -> ReviewCriticalUiState.RestartRequired
                is ReviewMediaState.Error -> ReviewCriticalUiState.Error(current.messageRes)
                ReviewMediaState.Ready.Raw -> ReviewCriticalUiState.Raw
                is ReviewMediaState.Ready -> null
            }
            criticalState?.let {
                ReviewCriticalStatus(
                    state = it,
                    overlayRotation = overlayRotation,
                    onRetry = { loadAttempt += 1 },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        }
        }

        if (metadata != null || provenanceLabel != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    // Same nav-bar inset its sibling on this edge already takes (the bottom-end
                    // action button): without it the two bottom-anchored elements sat at different
                    // heights and the filename line landed inside the gesture-nav swipe zone.
                    .navigationBarsPadding()
                    .padding(14.dp)
                    .clip(RoundedCornerShape(8.dp))
                    // Sits directly over the reviewed (often bright) photo, so it rides the same tested
                    // contrast floor (05486cb) as the live HUD — at 0.55 the secondary EXIF line was
                    // ~1.78:1, effectively unreadable over any bright region of the frame.
                    .background(HudPlate)
                    // Reserve the existing 48 dp bottom-end action plus a 12 dp gap only while that
                    // action is visible. BottomStart/BottomEnd mirror together in RTL, so this one
                    // logical width policy keeps both directions disjoint without moving either
                    // anchor or changing the established 280 dp wide-window cap.
                    .widthIn(max = metadataMaxWidth)
                    // The ONE HUD pill inset, 12/6 — this panel and the zoom-scale label below were
                    // the two review-screen plates the inset sweep left behind, on 9 dp and 5 dp of
                    // vertical padding respectively.
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                metadata?.let { meta ->
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
                provenanceLabel?.let { label ->
                    Text(label, color = CameraColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        if (mediaState is ReviewMediaState.Ready.Still && scale > 1.05f) {
            Text(
                text = reviewScaleLabel(scale),
                color = CameraColors.TextPrimary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 16.dp)
                    // Short label → counter-rotates like the shooting screen's compact glyphs (a
                    // focus check right after a landscape-held 300 mm shot reads "4×" upright).
                    .rotate(overlayRotation)
                    .clip(RoundedCornerShape(50))
                    // Match the live ZoomIndicator completely, not just in colour: the shared plate
                    // (its tested contrast floor, 05486cb — the 0.55 sibling cleared 4.5 only by a
                    // hair over a bright review photo) AND the shared 12/6 pill inset. This one kept
                    // a 5 dp vertical while claiming that kinship.
                    .background(HudPlate)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        when {
            videoInfo != null && playbackAction != null -> ReviewActionButton(
                actionLabel = playbackAction,
                stateLabel = playbackState,
                onClick = { toggleVideoPlayback() },
                // Bottom-END, not center: the centered slot overlapped the bottom-left metadata
                // panel's filename line (user-reported); the right corner is the one free anchor
                // (close top-left, delete top-right, metadata bottom-left).
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(14.dp),
            ) {
                PlaybackGlyph(playing = playbackUiState == VideoPlaybackUiState.PLAYING)
            }

            mediaState is ReviewMediaState.Ready.Still -> ReviewActionButton(
                actionLabel = reviewZoomAction,
                stateLabel = reviewZoomDescription,
                onClick = { setReviewScale(nextReviewScale(scale)) },
                // Same bottom-end anchor as the playback control (metadata owns bottom-left).
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(14.dp),
            ) {
                Text(
                    text = reviewZoomControlLabel(scale),
                    color = CameraColors.TextPrimary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.rotate(overlayRotation),
                )
            }
        }

        // Close button, top-left. Scrim rides the tested HUD contrast floor (05486cb): the close glyph
        // over a bright/high-key review frame washed out at 0.5 (≈3.98:1, under the 4.5 floor).
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .focusRequester(closeFocusRequester)
                .statusBarsPadding()
                .padding(12.dp)
                .size(48.dp)
                .clip(CircleShape)
                .background(HudPlate)
                .semantics {
                    contentDescription = a11yCloseReview
                    role = Role.Button
                }
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            // U+00D7, not U+2715: the old ✕ (MULTIPLICATION X) is absent from ALL THREE bundled Inter
            // faces (verified over the cmaps of inter_regular/medium/semibold), so the app's most
            // prominent close control — sitting next to the destructive delete — was the one glyph
            // rendered in whatever face ColorOS substitutes, at that face's weight and metrics. That is
            // exactly what Theme.kt bundled Inter to end. U+00D7 IS in all three, and ManualDials'
            // dial-close pill already draws it.
            //
            // titleLarge (22 sp), not titleMedium (16 sp), because the substitution was also hiding a
            // SIZE change: Inter's `multiply` ink is 1016/2048 = 0.496 em tall at SemiBold, where a
            // dingbat U+2715 is ~0.67-0.70 em (Arial Unicode 0.668, Zapf Dingbats 0.696). At 16 sp the
            // outgoing glyph drew ~10.7-11.1 sp of ink; 22 sp x 0.496 = 10.9 sp reproduces it, and the
            // 1.375x scale carries the stroke weight up with it. `fontWeight = SemiBold` is NOT an
            // alternative here — titleMedium already carries SemiBold(600).
            Text("×", color = CameraColors.TextPrimary, style = MaterialTheme.typography.titleLarge)
        }

        // Delete button, top-right. Scrim rides the tested HUD contrast floor (05486cb): the red trash
        // glyph over a bright frame was the worst interactive contrast found (≈1.43:1 at 0.5) — and it
        // is a DESTRUCTIVE action, so it must never be ambiguous. At the floor the red clears 4.5.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .focusRequester(deleteFocusRequester)
                .statusBarsPadding()
                .padding(12.dp)
                .size(48.dp)
                .clip(CircleShape)
                .background(HudPlate)
                .semantics {
                    contentDescription = deleteTitle.removeSuffix("?")
                    role = Role.Button
                }
                .clickable { confirmDelete = true },
            contentAlignment = Alignment.Center,
        ) {
            // Trash-can glyph (icon per feedback, not a "DEL" text chip), tinted delete-red.
            Canvas(Modifier.size(18.dp)) {
                val c = CameraColors.Alert
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
        ReviewDeleteConfirmationDialog(
            scope = deleteScope,
            raw = rawReady,
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = dismissDelete,
        )
    }
}

internal data class MediaDeleteConfirmationCopy(@StringRes val title: Int, @StringRes val body: Int)

internal fun mediaDeleteConfirmationCopy(
    scope: MediaDeleteScope,
    raw: Boolean,
): MediaDeleteConfirmationCopy = when (scope) {
    MediaDeleteScope.CAPTURE_FAMILY -> MediaDeleteConfirmationCopy(
        title = if (raw) R.string.review_delete_raw_capture_title else R.string.review_delete_capture_title,
        body = R.string.review_delete_family_body,
    )
    MediaDeleteScope.FILE_ONLY -> MediaDeleteConfirmationCopy(
        title = if (raw) R.string.review_delete_raw_file_title else R.string.review_delete_file_title,
        // FILE_ONLY is exactly the degraded path where siblings SURVIVE — that difference from
        // CAPTURE_FAMILY is the whole reason the two dialogs exist, so the body states it instead
        // of restating the title in the passive voice.
        body = R.string.review_delete_file_body,
    )
}

/** Production destructive confirmation, shared with the deterministic debug snapshot host. */
@Composable
internal fun ReviewDeleteConfirmationDialog(
    scope: MediaDeleteScope,
    raw: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val copy = mediaDeleteConfirmationCopy(scope, raw)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CameraColors.Pill,
        title = { Text(stringResource(copy.title)) },
        text = { Text(stringResource(copy.body)) },
        confirmButton = {
            MinTouchTarget48 {
                TextButton(onClick = onConfirm) {
                    Text(stringResource(R.string.action_delete), color = CameraColors.Alert)
                }
            }
        },
        dismissButton = {
            MinTouchTarget48 {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

@Composable
internal fun RawReviewPlaceholder(
    modifier: Modifier = Modifier,
    overlayRotation: Float = 0f,
) {
    val a11yRawDngCapture = stringResource(R.string.a11y_raw_dng_capture)
    val reviewPreviewUnavailable = stringResource(R.string.review_preview_unavailable)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .padding(48.dp)
                .rotateLayout(overlayRotation)
                .semantics(mergeDescendants = true) {
                    contentDescription = a11yRawDngCapture
                    stateDescription = reviewPreviewUnavailable
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.format_raw),
                color = CameraColors.TextPrimary,
                style = MaterialTheme.typography.displaySmall,
            )
            Text(
                text = stringResource(R.string.format_dng),
                color = CameraColors.TextSecondary,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/** Production review critical-state surface, shared with the deterministic debug host. */
@Composable
internal fun ReviewCriticalStatus(
    state: ReviewCriticalUiState,
    overlayRotation: Float,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when (state) {
            ReviewCriticalUiState.Raw -> RawReviewPlaceholder(Modifier.fillMaxSize(), overlayRotation)
            ReviewCriticalUiState.Loading -> Text(
                stringResource(R.string.review_loading),
                color = CameraColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .rotateLayout(overlayRotation)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            ReviewCriticalUiState.RestartRequired -> Text(
                stringResource(R.string.review_error_restart_required),
                color = CameraColors.TextPrimary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .rotateLayout(overlayRotation)
                    .semantics { liveRegion = LiveRegionMode.Assertive },
            )
            is ReviewCriticalUiState.Error -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .rotateLayout(overlayRotation)
                    .semantics { liveRegion = LiveRegionMode.Assertive },
            ) {
                Text(
                    stringResource(state.messageRes),
                    color = CameraColors.TextPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                MinTouchTarget48 {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                }
            }
        }
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
            .background(HudPlate)
            .border(1.dp, CameraColors.AffordanceEdge, CircleShape)
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
                color = CameraColors.TextPrimary,
                start = Offset(size.width * 0.34f, size.height * 0.24f),
                end = Offset(size.width * 0.34f, size.height * 0.76f),
                strokeWidth = stroke,
            )
            drawLine(
                color = CameraColors.TextPrimary,
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
            drawPath(triangle, CameraColors.TextPrimary)
        }
    }
}

/** Composed still transform; raw state is clamped again at the final draw boundary. */
@Composable
internal fun ReviewStillImage(
    bitmap: ImageBitmap,
    contentDescription: String,
    scale: Float,
    offset: Offset,
    dismissDrag: Float,
    geometry: ReviewStillGeometry,
    modifier: Modifier = Modifier,
    onTransformApplied: ((ReviewStillTransform) -> Unit)? = null,
) {
    val transform = reviewStillTransform(scale, offset, dismissDrag, geometry)
    SideEffect { onTransformApplied?.invoke(transform) }
    Image(
        bitmap = bitmap,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(
                scaleX = transform.scale,
                scaleY = transform.scale,
                translationX = transform.translation.x,
                translationY = transform.translation.y,
                alpha = transform.alpha,
            )
            .then(modifier),
    )
}

internal data class ReviewStillTransform(
    val scale: Float,
    val translation: Offset,
    val alpha: Float,
)

internal data class ReviewStillGestureTransform(
    val scale: Float,
    val offset: Offset,
)

internal enum class ReviewPanDirection { LEFT, RIGHT, UP, DOWN }

internal enum class ReviewStillPosition(@StringRes val stringRes: Int) {
    CENTER(R.string.a11y_review_position_center),
    LEFT(R.string.a11y_review_position_left),
    RIGHT(R.string.a11y_review_position_right),
    TOP(R.string.a11y_review_position_top),
    BOTTOM(R.string.a11y_review_position_bottom),
    TOP_LEFT(R.string.a11y_review_position_top_left),
    TOP_RIGHT(R.string.a11y_review_position_top_right),
    BOTTOM_LEFT(R.string.a11y_review_position_bottom_left),
    BOTTOM_RIGHT(R.string.a11y_review_position_bottom_right),
}

internal data class ReviewPanAccessibilityAction(
    val direction: ReviewPanDirection,
    val label: String,
)

internal fun reviewPanDirectionForKey(key: Key): ReviewPanDirection? = when (key) {
    Key.DirectionLeft -> ReviewPanDirection.LEFT
    Key.DirectionRight -> ReviewPanDirection.RIGHT
    Key.DirectionUp -> ReviewPanDirection.UP
    Key.DirectionDown -> ReviewPanDirection.DOWN
    else -> null
}

/** One non-touch owner for bounded image navigation. Image geometry is physical and never RTL-flipped. */
internal fun Modifier.reviewStillNonTouchControls(
    state: String,
    panActions: List<ReviewPanAccessibilityAction>,
    onPan: (ReviewPanDirection) -> Boolean,
    otherActions: List<CustomAccessibilityAction>,
): Modifier = this
    .onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        reviewPanDirectionForKey(event.key)?.let(onPan) ?: false
    }
    .focusable()
    .semantics {
        stateDescription = state
        customActions = otherActions + panActions.map { action ->
            CustomAccessibilityAction(action.label) { onPan(action.direction) }
        }
    }

internal fun reviewStillTransform(
    scale: Float,
    offset: Offset,
    dismissDrag: Float,
    geometry: ReviewStillGeometry,
): ReviewStillTransform {
    val renderedScale = scale.takeIf { it.isFinite() }?.coerceIn(1f, 12f) ?: 1f
    val translated = geometry.clampOffset(offset, renderedScale)
    val safeDismissDrag = dismissDrag.takeIf { it.isFinite() } ?: 0f
    return ReviewStillTransform(
        scale = renderedScale,
        translation = Offset(translated.x, translated.y + safeDismissDrag),
        alpha = (1f - abs(safeDismissDrag) / 1400f).coerceIn(0.3f, 1f),
    )
}

internal fun nextReviewScale(current: Float): Float = when {
    current < 1.5f -> 4f
    current < 6f -> 8f
    else -> 1f
}

/** Fitted still bounds in viewport pixels, shared by drawing and every gesture mutation. */
internal data class ReviewStillGeometry(
    val viewportWidth: Float,
    val viewportHeight: Float,
    val fittedWidth: Float,
    val fittedHeight: Float,
) {
    fun panBounds(scale: Float): Offset {
        val safeScale = scale.takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 1f
        return Offset(
            x = ((fittedWidth * safeScale - viewportWidth) / 2f).coerceAtLeast(0f),
            y = ((fittedHeight * safeScale - viewportHeight) / 2f).coerceAtLeast(0f),
        )
    }

    fun clampOffset(candidate: Offset, scale: Float): Offset {
        if (!candidate.x.isFinite() || !candidate.y.isFinite()) return Offset.Zero
        val bounds = panBounds(scale)
        return Offset(
            x = candidate.x.coerceIn(-bounds.x, bounds.x),
            y = candidate.y.coerceIn(-bounds.y, bounds.y),
        )
    }

    /** Scale about the gesture centroid, translate with it, then clamp exactly once. */
    fun transformGesture(
        currentScale: Float,
        currentOffset: Offset,
        centroid: Offset,
        pan: Offset,
        zoomChange: Float,
    ): ReviewStillGestureTransform {
        val oldScale = currentScale.takeIf { it.isFinite() }?.coerceIn(1f, 12f) ?: 1f
        val oldOffset = clampOffset(currentOffset, oldScale)
        val nextScale = (oldScale * zoomChange).takeIf { it.isFinite() }
            ?.coerceIn(1f, 12f)
            ?: oldScale
        if (!centroid.x.isFinite() || !centroid.y.isFinite() || !pan.x.isFinite() || !pan.y.isFinite()) {
            return ReviewStillGestureTransform(oldScale, oldOffset)
        }
        val ratio = nextScale / oldScale
        val center = Offset(viewportWidth / 2f, viewportHeight / 2f)
        val nextOffset = oldOffset * ratio + (centroid - center) * (1f - ratio) + pan
        return ReviewStillGestureTransform(nextScale, clampOffset(nextOffset, nextScale))
    }

    /** One quarter-viewport navigation step; null means that direction is already at its bound. */
    fun panTarget(
        currentOffset: Offset,
        scale: Float,
        direction: ReviewPanDirection,
    ): Offset? {
        val owned = clampOffset(currentOffset, scale)
        val delta = when (direction) {
            ReviewPanDirection.LEFT -> Offset(viewportWidth * 0.25f, 0f)
            ReviewPanDirection.RIGHT -> Offset(-viewportWidth * 0.25f, 0f)
            ReviewPanDirection.UP -> Offset(0f, viewportHeight * 0.25f)
            ReviewPanDirection.DOWN -> Offset(0f, -viewportHeight * 0.25f)
        }
        val target = clampOffset(owned + delta, scale)
        return target.takeIf { it != owned }
    }

    fun position(scale: Float, currentOffset: Offset): ReviewStillPosition {
        val bounds = panBounds(scale)
        val owned = clampOffset(currentOffset, scale)
        val horizontal = when {
            bounds.x <= 0f || abs(owned.x) < bounds.x / 3f -> 0
            owned.x > 0f -> -1 // image right => viewport is inspecting its left side
            else -> 1
        }
        val vertical = when {
            bounds.y <= 0f || abs(owned.y) < bounds.y / 3f -> 0
            owned.y > 0f -> -1 // image down => viewport is inspecting its top
            else -> 1
        }
        return when (horizontal to vertical) {
            0 to 0 -> ReviewStillPosition.CENTER
            -1 to 0 -> ReviewStillPosition.LEFT
            1 to 0 -> ReviewStillPosition.RIGHT
            0 to -1 -> ReviewStillPosition.TOP
            0 to 1 -> ReviewStillPosition.BOTTOM
            -1 to -1 -> ReviewStillPosition.TOP_LEFT
            1 to -1 -> ReviewStillPosition.TOP_RIGHT
            -1 to 1 -> ReviewStillPosition.BOTTOM_LEFT
            else -> ReviewStillPosition.BOTTOM_RIGHT
        }
    }

    /** Centers the content under a viewport-space point across an arbitrary zoom transition. */
    fun centerOn(
        point: Offset,
        targetScale: Float,
        currentScale: Float = 1f,
        currentOffset: Offset = Offset.Zero,
    ): Offset {
        if (!point.x.isFinite() || !point.y.isFinite()) return Offset.Zero
        val safeTargetScale = targetScale.takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 1f
        val safeCurrentScale = currentScale.takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 1f
        val ownedOffset = clampOffset(currentOffset, safeCurrentScale)
        val scaleChange = safeTargetScale / safeCurrentScale
        val centered = Offset(
            x = (viewportWidth / 2f - point.x + ownedOffset.x) * scaleChange,
            y = (viewportHeight / 2f - point.y + ownedOffset.y) * scaleChange,
        )
        return clampOffset(centered, safeTargetScale)
    }
}

/** Mirrors [ContentScale.Fit] for the bitmap drawn by the review Image. Invalid input fails closed. */
internal fun reviewStillGeometry(
    viewportWidth: Int,
    viewportHeight: Int,
    bitmapWidth: Int,
    bitmapHeight: Int,
): ReviewStillGeometry {
    if (viewportWidth <= 0 || viewportHeight <= 0 || bitmapWidth <= 0 || bitmapHeight <= 0) {
        return ReviewStillGeometry(0f, 0f, 0f, 0f)
    }
    val viewportW = viewportWidth.toFloat()
    val viewportH = viewportHeight.toFloat()
    val fitScale = minOf(viewportW / bitmapWidth, viewportH / bitmapHeight)
    return ReviewStillGeometry(
        viewportWidth = viewportW,
        viewportHeight = viewportH,
        fittedWidth = bitmapWidth * fitScale,
        fittedHeight = bitmapHeight * fitScale,
    )
}

internal data class ReviewTapCandidate(
    val upTimeMillis: Long,
    val position: Offset,
)

internal data class ReviewTapSequenceDecision(
    val isDoubleTap: Boolean,
    val nextCandidate: ReviewTapCandidate?,
)

internal fun reviewDoubleTapSlop(context: Context): Float =
    android.view.ViewConfiguration.get(context).scaledDoubleTapSlop.toFloat()

/**
 * One still-gesture owner's double-tap reducer. Non-tap gestures cancel the sequence; an invalid
 * clean tap becomes the next first tap so ordinary tap chaining matches platform gesture behavior.
 */
internal fun reviewTapSequenceDecision(
    previous: ReviewTapCandidate?,
    cleanTap: Boolean,
    downTimeMillis: Long,
    upTimeMillis: Long,
    position: Offset,
    minimumIntervalMillis: Long,
    maximumIntervalMillis: Long,
    maximumDistance: Float,
): ReviewTapSequenceDecision {
    if (!cleanTap || !position.x.isFinite() || !position.y.isFinite()) {
        return ReviewTapSequenceDecision(isDoubleTap = false, nextCandidate = null)
    }
    val interval = previous?.let { downTimeMillis - it.upTimeMillis }
    val distance = previous?.let { (position - it.position).getDistance() }
    val accepted = minimumIntervalMillis >= 0L &&
        maximumIntervalMillis >= minimumIntervalMillis &&
        previous != null && interval != null && distance != null &&
        interval in minimumIntervalMillis..maximumIntervalMillis &&
        maximumDistance.isFinite() && maximumDistance >= 0f && distance <= maximumDistance
    return if (accepted) {
        ReviewTapSequenceDecision(isDoubleTap = true, nextCandidate = null)
    } else {
        ReviewTapSequenceDecision(
            isDoubleTap = false,
            nextCandidate = ReviewTapCandidate(upTimeMillis, position),
        )
    }
}

internal fun reviewScaleLabel(scale: Float): String = when {
    abs(scale - 1f) < 0.05f -> "1×"
    abs(scale - 4f) < 0.05f -> "4×"
    abs(scale - 8f) < 0.05f -> "8×"
    else -> "%.1f×".format(java.util.Locale.US, scale)
}

internal fun reviewZoomActionResource(scale: Float): Int = when (nextReviewScale(scale)) {
    4f -> R.string.a11y_zoom_4x
    8f -> R.string.a11y_zoom_8x
    else -> R.string.a11y_reset_zoom
}

/** Visual label on the corner zoom button: the magnification the NEXT press applies, arrow-prefixed
 *  so it cannot be misread as the CURRENT scale the top pill shows — a bare "N×" on a button reads
 *  as current state, and the two readouts contradicted on screen (cycle-6 D-12). The accessibility
 *  action/state strings above stay the honest current/next pair. */
internal fun reviewZoomControlLabel(scale: Float): String =
    "→" + reviewScaleLabel(nextReviewScale(scale))

internal fun videoPlaybackActionResource(state: VideoPlaybackUiState): Int? = when (state) {
    VideoPlaybackUiState.PREPARING -> null
    VideoPlaybackUiState.PLAYING -> R.string.a11y_pause_video
    VideoPlaybackUiState.PAUSED -> R.string.a11y_play_video
}

internal fun videoPlaybackToggleTarget(state: VideoPlaybackUiState): VideoPlaybackUiState? =
    when (state) {
        VideoPlaybackUiState.PREPARING -> null
        VideoPlaybackUiState.PLAYING -> VideoPlaybackUiState.PAUSED
        VideoPlaybackUiState.PAUSED -> VideoPlaybackUiState.PLAYING
    }

internal fun videoPlaybackStateResource(state: VideoPlaybackUiState): Int = when (state) {
    VideoPlaybackUiState.PREPARING -> R.string.a11y_preparing_video
    VideoPlaybackUiState.PLAYING -> R.string.a11y_playing
    VideoPlaybackUiState.PAUSED -> R.string.a11y_paused
}

internal fun reviewMetadataLine(raw: Boolean, width: Int, height: Int, sizeBytes: Long): String =
    buildList {
        if (raw) add("RAW")
        if (width > 0 && height > 0) add("${width}×${height}")
        add(formatBytes(sizeBytes))
    }.joinToString(" · ")

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "--"
    // DECIMAL megabytes, matching the label and the rest of the app: StatusInfoPill's
    // remaining-shots budget is decimal (8/6/26 MB per file), so the binary divisor made a 26 MB DNG
    // report as "24.8 MB" in review — two byte bases for the same file, one of them mislabelled.
    val mb = bytes / 1_000_000.0
    return "%.1f MB".format(java.util.Locale.US, mb)
}
