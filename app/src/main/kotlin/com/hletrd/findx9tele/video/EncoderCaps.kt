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
 *  - AV1  → `c2.android.av1.encoder` ONLY (software — no `c2.qti.av1.*`), so it is slow and capped
 *           ≤1080p by the encoder itself. Reported here with [isHardware] = false so the UI can warn.
 *  - Dolby Vision `c2.qti.dv.encoder` exists in HW and is detected via [hasDolbyVision], but is not
 *    offered as a recording codec (clean MP4 muxing of a DV elementary stream is not straightforward).
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
            VideoCodec.AV1 to MIME_AV1,
            VideoCodec.APV to MIME_APV,
        )) {
            encoderFor(mime)?.let { out[codec] = it }
        }
    }

    /** Best encoder for [mime]: prefers a hardware-accelerated one, falling back to any (e.g. SW AV1). */
    private fun encoderFor(mime: String): Info? {
        val infos = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        }.getOrNull() ?: return null
        var fallback: Info? = null
        for (ci in infos) {
            if (!ci.isEncoder) continue
            if (ci.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
            val hw = runCatching { ci.isHardwareAccelerated }.getOrDefault(!ci.name.startsWith("c2.android") && !ci.name.startsWith("OMX.google"))
            val info = Info(ci.name, hw)
            if (hw) return info
            if (fallback == null) fallback = info
        }
        return fallback
    }

    /**
     * Codecs the device can actually encode AND that we can mux into MP4, in UI order (HEVC, AVC,
     * then AV1 if present). APV is intentionally EXCLUDED: the HW `c2.qti.apv.encoder` exists, but
     * Android's MediaMuxer (API 36) rejects APV in an MP4 container — device-verified it errors the
     * encoder mid-drain — so there is no working recording path for it via MediaCodec+MediaMuxer.
     * [isSupported]/[encoderName] still report it for diagnostics.
     */
    fun availableCodecs(): List<VideoCodec> =
        listOf(VideoCodec.HEVC, VideoCodec.AVC, VideoCodec.AV1).filter { byCodec.containsKey(it) }

    fun isSupported(codec: VideoCodec): Boolean = byCodec.containsKey(codec)

    /** True if [codec] has a hardware encoder (false for the software-only AV1 on this device). */
    fun isHardware(codec: VideoCodec): Boolean = byCodec[codec]?.hardware == true

    /** The concrete encoder component name chosen for [codec] (for diagnostics), or null. */
    fun encoderName(codec: VideoCodec): String? = byCodec[codec]?.name

    const val MIME_AV1 = "video/av01"
    const val MIME_DOLBY_VISION = "video/dolby-vision"
    // APV (Advanced Professional Video, ISO/IEC 21794) — HW `c2.qti.apv.encoder` on this SoC.
    const val MIME_APV = "video/apv"
}
