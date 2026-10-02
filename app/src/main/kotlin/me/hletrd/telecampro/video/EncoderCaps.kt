package me.hletrd.telecampro.video

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import me.hletrd.telecampro.camera.ColorTransfer
import me.hletrd.telecampro.camera.VideoCodec

/** The exact encoder component admitted by capability discovery and used for recording. */
data class EncoderSelection(
    val codec: VideoCodec,
    val codecName: String,
    val mime: String,
    val hardwareAccelerated: Boolean,
    val main10: Boolean,
)

/**
 * One immutable result of the process-wide platform codec walk.
 *
 * Every UI and recorder decision is derived from this same snapshot. In particular, [selectionFor]
 * is the token handed to VideoRecorder, so a capability admitted on one component can never be
 * instantiated later through MIME-based factory selection as a different component.
 */
class CodecInventory internal constructor(
    private val candidates: Map<VideoCodec, List<EncoderSelection>>,
) {
    val availableVideoCodecs: List<VideoCodec> =
        listOf(VideoCodec.HEVC, VideoCodec.AVC).filter { candidates[it].orEmpty().isNotEmpty() }
    val heifEncodeAvailable: Boolean = candidates[VideoCodec.HEVC].orEmpty().isNotEmpty()
    val tenBitEncodeAvailable: Boolean = candidates[VideoCodec.HEVC].orEmpty().any { it.main10 }

    /** Exact components that can honestly encode [transfer], hardware-first and registry-stable. */
    fun candidatesFor(codec: VideoCodec, transfer: ColorTransfer = ColorTransfer.SDR): List<EncoderSelection> =
        candidates[codec].orEmpty().filter { encoderSelectionAdmitsTransfer(it, transfer) }

    fun selectionFor(codec: VideoCodec, transfer: ColorTransfer = ColorTransfer.SDR): EncoderSelection? =
        candidatesFor(codec, transfer).firstOrNull()

    companion object {
        val EMPTY = CodecInventory(emptyMap())
    }
}

/** Android-free input to [buildCodecInventory], retained so failure cases are host-testable. */
internal data class CodecComponent(
    val name: String,
    val encoder: Boolean,
    val supportedTypes: Set<String>,
    val hardwareAccelerated: Boolean,
    /** null means the capability query failed; Main10 must then fail closed. */
    val hevcProfiles: Set<Int>?,
)

/**
 * Builds one immutable codec inventory. A failed outer scan is represented by an empty list; a
 * failed HEVC capability query is represented by null profiles and never grants Main10.
 */
internal fun buildCodecInventory(components: List<CodecComponent>): CodecInventory {
    val candidatesByCodec = buildMap {
        for ((codec, mime) in listOf(
            VideoCodec.HEVC to MediaFormat.MIMETYPE_VIDEO_HEVC,
            VideoCodec.AVC to MediaFormat.MIMETYPE_VIDEO_AVC,
            VideoCodec.APV to EncoderCaps.MIME_APV,
        )) {
            val candidates = components.asSequence()
                .filter { component ->
                    component.encoder && component.supportedTypes.any { it.equals(mime, ignoreCase = true) }
                }
                .map { component ->
                    EncoderSelection(
                        codec = codec,
                        codecName = component.name,
                        mime = mime,
                        hardwareAccelerated = component.hardwareAccelerated,
                        main10 = codec == VideoCodec.HEVC &&
                            component.hevcProfiles?.contains(
                                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                            ) == true,
                    )
                }
                .toList()
                .withIndex()
                .sortedWith(
                    compareByDescending<IndexedValue<EncoderSelection>> { it.value.hardwareAccelerated }
                        .thenBy { it.index },
                )
                .map { it.value }
            if (candidates.isNotEmpty()) put(codec, candidates)
        }
    }
    return CodecInventory(candidatesByCodec.toMap())
}

/** Fail-closed boundary for platform-list and component-metadata failures. */
internal fun discoverCodecInventory(load: () -> List<CodecComponent>): CodecInventory =
    runCatching { buildCodecInventory(load()) }.getOrDefault(CodecInventory.EMPTY)

/**
 * The process latch over the platform codec walk (AGG5-30). Only a SUCCESSFUL walk latches — an
 * empty list included, because that is the device's real answer. A walk that THROWS (mediaserver
 * restarting during the cold-start scan, a Binder hiccup) used to latch [CodecInventory.EMPTY] for
 * the whole process: no video codecs and no HEIF until the process died, with no log line. Now one
 * [load] makes at most [maxAttempts] walks, [pause] between them, and a load that exhausts them
 * returns EMPTY WITHOUT latching, so the next load (the next ViewModel) walks again. [onScanFailure]
 * reports the first exhausted load once per process — a bounded, quota-safe single row.
 */
internal class CodecInventoryLoader(
    private val scan: () -> List<CodecComponent>,
    private val maxAttempts: Int = CODEC_SCAN_MAX_ATTEMPTS,
    private val pause: (attempt: Int) -> Unit = {},
    private val onScanFailure: (Throwable) -> Unit = {},
) {
    private val loadLock = Any()
    @Volatile private var loaded = false
    @Volatile private var inventory = CodecInventory.EMPTY
    private var failureReported = false

    init {
        require(maxAttempts > 0)
    }

    fun load(): CodecInventory {
        if (loaded) return inventory
        return synchronized(loadLock) {
            if (loaded) return@synchronized inventory
            var lastFailure: Throwable? = null
            for (attempt in 1..maxAttempts) {
                if (attempt > 1) pause(attempt)
                val walked = runCatching { buildCodecInventory(scan()) }
                if (walked.isSuccess) {
                    inventory = walked.getOrThrow()
                    loaded = true
                    return@synchronized inventory
                }
                lastFailure = walked.exceptionOrNull()
            }
            if (!failureReported) {
                failureReported = true
                lastFailure?.let(onScanFailure)
            }
            CodecInventory.EMPTY
        }
    }

    fun currentInventory(): CodecInventory = inventory

    fun isLoaded(): Boolean = loaded
}

internal const val CODEC_SCAN_MAX_ATTEMPTS = 3
private const val CODEC_SCAN_RETRY_PAUSE_MS = 250L

/**
 * Runtime encoder inventory. [load] latches the first successful platform list walk process-wide
 * ([CodecInventoryLoader]) and must be called off main — a failed walk pauses and retries there; all
 * other methods are non-blocking reads of the resulting immutable snapshot.
 */
object EncoderCaps {
    private val loader = CodecInventoryLoader(
        scan = ::scanPlatformComponents,
        pause = { attempt ->
            // Off main by contract (the ViewModel loads on ioExecutor); an interrupt skips the pause.
            me.hletrd.telecampro.storage.sleepPreservingInterrupt(CODEC_SCAN_RETRY_PAUSE_MS * (attempt - 1))
        },
        onScanFailure = { failure ->
            me.hletrd.telecampro.camera.DiagnosticLog.w(
                "EncoderCaps",
                "codec inventory walk failed $CODEC_SCAN_MAX_ATTEMPTS times; no video/HEIF encoders until a later load",
                failure,
            )
        },
    )

    fun load(): CodecInventory = loader.load()

    private val inventory: CodecInventory get() = loader.currentInventory()

    fun currentInventory(): CodecInventory = inventory

    fun isLoaded(): Boolean = loader.isLoaded()

    fun availableCodecs(): List<VideoCodec> = inventory.availableVideoCodecs

    fun isSupported(codec: VideoCodec): Boolean = inventory.selectionFor(codec) != null

    fun heifEncodeAvailable(): Boolean = inventory.heifEncodeAvailable

    fun tenBitEncodeAvailable(): Boolean = inventory.tenBitEncodeAvailable

    fun selectionFor(codec: VideoCodec): EncoderSelection? = inventory.selectionFor(codec)

    fun candidatesFor(
        codec: VideoCodec,
        transfer: ColorTransfer = ColorTransfer.SDR,
    ): List<EncoderSelection> = inventory.candidatesFor(codec, transfer)

    fun encoderName(codec: VideoCodec): String? = selectionFor(codec)?.codecName

    private fun scanPlatformComponents(): List<CodecComponent> {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList()
        return infos.map { info ->
            val supportedTypes = runCatching { info.supportedTypes.toSet() }.getOrDefault(emptySet())
            val advertisesHevc = supportedTypes.any {
                it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
            }
            CodecComponent(
                name = info.name,
                encoder = runCatching { info.isEncoder }.getOrDefault(false),
                supportedTypes = supportedTypes,
                hardwareAccelerated = runCatching { info.isHardwareAccelerated }
                    .getOrDefault(looksHardwareAccelerated(info.name)),
                hevcProfiles = if (advertisesHevc) {
                    runCatching {
                        info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                            .profileLevels
                            .mapTo(mutableSetOf()) { it.profile }
                            .toSet()
                    }.getOrNull()
                } else {
                    emptySet()
                },
            )
        }
    }

    // APV (Advanced Professional Video, ISO/IEC 21794) — HW `c2.qti.apv.encoder` on this SoC.
    const val MIME_APV = "video/apv"
}

/** Fallback heuristic when MediaCodecInfo.isHardwareAccelerated itself throws. */
internal fun looksHardwareAccelerated(codecName: String): Boolean =
    !codecName.startsWith("c2.android") && !codecName.startsWith("OMX.google")

/** First hardware candidate, otherwise the first software candidate. */
internal fun <T> pickBestEncoder(candidates: List<Pair<T, Boolean>>): T? {
    var fallback: T? = null
    for ((value, hardware) in candidates) {
        if (hardware) return value
        if (fallback == null) fallback = value
    }
    return fallback
}
