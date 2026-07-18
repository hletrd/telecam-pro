package com.hletrd.findx9tele.video

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import com.hletrd.findx9tele.camera.VideoCodec

/**
 * Runtime video-encoder inventory, queried once from [MediaCodecList] (REGULAR_CODECS).
 *
 * On the Find X9 Ultra (SM8850 / Snapdragon 8 Elite Gen 5) this resolves to:
 *  - HEVC → `c2.qti.hevc.encoder` (HW, 8K30 / 4K120, ≤180 Mbps)
 *  - AVC  → `c2.qti.avc.encoder`  (HW, 8K30 / 4K120, ≤220 Mbps)
 *  - Dolby Vision `c2.qti.dv.encoder` exists in HW and is detected via [hasDolbyVision], but is not
 *    offered as a recording codec (clean MP4 muxing of a DV elementary stream is not straightforward).
 *
 * (AV1 was removed as a recording codec: the only AV1 encoder on this SoC is software
 * `c2.android.av1.encoder` — too slow/low-res to ship.)
 *
 * All lookups are defensive so a missing/renamed codec degrades to "unavailable" rather than throwing.
 */
object EncoderCaps {

    private data class Info(val name: String, val hardware: Boolean)

    // codec → best encoder (prefer a hardware one) or null if the device has no encoder for it.
    private val byCodec: Map<VideoCodec, Info> by lazy { buildMap { scan(this) } }

    /** True if the device advertises a Dolby Vision (video/dolby-vision) encoder — HW on this SoC. */
    val hasDolbyVision: Boolean by lazy { encoderFor(MIME_DOLBY_VISION) != null }

    private fun scan(out: MutableMap<VideoCodec, Info>) {
        for ((codec, mime) in listOf(
            VideoCodec.HEVC to MediaFormat.MIMETYPE_VIDEO_HEVC,
            VideoCodec.AVC to MediaFormat.MIMETYPE_VIDEO_AVC,
            VideoCodec.APV to MIME_APV,
        )) {
            encoderFor(mime)?.let { out[codec] = it }
        }
    }

    /** Best encoder for [mime]: prefers a hardware-accelerated one, falling back to any software one. */
    private fun encoderFor(mime: String): Info? {
        val infos = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        }.getOrNull() ?: return null
        val candidates = infos.mapNotNull { ci ->
            if (!ci.isEncoder) return@mapNotNull null
            if (ci.supportedTypes.none { it.equals(mime, ignoreCase = true) }) return@mapNotNull null
            val hw = runCatching { ci.isHardwareAccelerated }
                .getOrDefault(looksHardwareAccelerated(ci.name))
            Info(ci.name, hw) to hw
        }
        return pickBestEncoder(candidates)
    }

    /**
     * Codecs the device can actually encode AND that we can mux into MP4, in UI order (HEVC, AVC).
     * APV is intentionally EXCLUDED: the HW `c2.qti.apv.encoder` exists, but Android's MediaMuxer
     * (API 36) rejects APV in an MP4 container — device-verified it errors the encoder mid-drain —
     * so there is no working recording path for it via MediaCodec+MediaMuxer. [isSupported]/
     * [encoderName] still report it for diagnostics.
     */
    fun availableCodecs(): List<VideoCodec> =
        listOf(VideoCodec.HEVC, VideoCodec.AVC).filter { byCodec.containsKey(it) }

    fun isSupported(codec: VideoCodec): Boolean = byCodec.containsKey(codec)

    /** True if [codec] has a hardware encoder. */
    fun isHardware(codec: VideoCodec): Boolean = byCodec[codec]?.hardware == true

    /** The concrete encoder component name chosen for [codec] (for diagnostics), or null. */
    fun encoderName(codec: VideoCodec): String? = byCodec[codec]?.name

    const val MIME_DOLBY_VISION = "video/dolby-vision"
    // APV (Advanced Professional Video, ISO/IEC 21794) — HW `c2.qti.apv.encoder` on this SoC.
    const val MIME_APV = "video/apv"
}

/**
 * Name-based hardware heuristic used only when [android.media.MediaCodecInfo.isHardwareAccelerated]
 * itself throws (TEST4-19): the two Android software-encoder prefixes are the reliable negatives.
 * Extracted pure so misclassifying a new vendor name fails a host test, not a device recording.
 */
internal fun looksHardwareAccelerated(codecName: String): Boolean =
    !codecName.startsWith("c2.android") && !codecName.startsWith("OMX.google")

/**
 * The encoder tie-break, extracted pure (TEST4-19): the FIRST hardware candidate wins immediately;
 * otherwise the FIRST software candidate is the remembered fallback (a later software match must
 * never displace an earlier one); null on no candidates.
 */
internal fun <T> pickBestEncoder(candidates: List<Pair<T, Boolean>>): T? {
    var fallback: T? = null
    for ((value, hw) in candidates) {
        if (hw) return value
        if (fallback == null) fallback = value
    }
    return fallback
}
