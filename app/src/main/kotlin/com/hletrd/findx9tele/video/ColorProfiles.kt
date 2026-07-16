package com.hletrd.findx9tele.video

import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.hletrd.findx9tele.camera.ColorTransfer
import com.hletrd.findx9tele.camera.VideoCodec

/**
 * Builds the encoder MediaFormats.
 *
 * - HEVC: Main10 profile tagged Rec.2020, except SDR which is Main (8-bit) BT.709.
 *   - HLG: tagged with the HLG transfer so HDR players render it directly.
 *   - LOG: our GL pipeline bakes a flat log curve; there is no standard "log" transfer id, so the
 *          stream is tagged BT.2020 full-range with an explicit SDR-class transfer (grade manually).
 *   - SDR: plain Rec.709/SDR — the GL pipeline applies no curve (camera frames are already SDR),
 *          for footage that plays correctly everywhere with zero grading.
 *
 *   The shipping Camera2/EGL path is deliberately SDR/8-bit because HLG10 + full-res JPEG/RAW crashes
 *   this HAL. Main10 here is the encoder/container profile, not an end-to-end 10-bit source claim.
 * - AVC: H.264 High profile, 8-bit SDR (BT.709). No HDR/log support; the engine forces SDR
 *        transfer when AVC is selected.
 */
object ColorProfiles {
    const val MIME_HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC
    const val MIME_AVC = MediaFormat.MIMETYPE_VIDEO_AVC
    const val MIME_APV = EncoderCaps.MIME_APV
    const val MIME_AAC = MediaFormat.MIMETYPE_AUDIO_AAC

    const val AUDIO_SAMPLE_RATE = 48_000
    const val AUDIO_CHANNELS = 2
    const val AUDIO_BIT_RATE = 192_000

    /**
     * Applies the frame-rate keys shared by every video encoder. [encoderRate] is the TRUE rate as
     * a float, so NTSC drop-frame rates (23.976 = 24000/1001, etc.) are tagged exactly rather than
     * rounded. For high-speed capture, [captureRate] (> 0) sets KEY_CAPTURE_RATE + KEY_OPERATING_RATE
     * so the encoder is told it is fed frames faster than real-time.
     */
    private fun MediaFormat.applyFrameRate(encoderRate: Double, captureRate: Double) {
        setFloat(MediaFormat.KEY_FRAME_RATE, encoderRate.toFloat())
        if (captureRate > 0.0) {
            setFloat(MediaFormat.KEY_CAPTURE_RATE, captureRate.toFloat())
            setInteger(MediaFormat.KEY_OPERATING_RATE, captureRate.toInt())
        }
    }

    fun hevcFormat(width: Int, height: Int, encoderRate: Double, captureRate: Double, bitRate: Int, transfer: ColorTransfer): MediaFormat {
        return MediaFormat.createVideoFormat(MIME_HEVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            applyFrameRate(encoderRate, captureRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            val tags = hevcColorTagsFor(transfer)
            tags.profile?.let { setInteger(MediaFormat.KEY_PROFILE, it) }
            setInteger(MediaFormat.KEY_COLOR_STANDARD, tags.standard)
            setInteger(MediaFormat.KEY_COLOR_RANGE, tags.range)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, tags.transfer)
        }
    }

    /** Builds the video MediaFormat for the given encoder and color transfer. */
    fun videoFormat(
        codec: VideoCodec,
        width: Int,
        height: Int,
        encoderRate: Double,
        captureRate: Double,
        bitRate: Int,
        transfer: ColorTransfer,
    ): MediaFormat {
        return when (codec) {
            VideoCodec.HEVC -> hevcFormat(width, height, encoderRate, captureRate, bitRate, transfer)
            VideoCodec.AVC -> avcFormat(width, height, encoderRate, captureRate, bitRate)
            VideoCodec.APV -> apvFormat(width, height, encoderRate, captureRate, bitRate, transfer)
        }
    }

    /**
     * APV (Advanced Professional Video, ISO/IEC 21794) — an ALL-INTRA professional codec, the
     * ProRes/XAVC-I analogue. Every frame is a keyframe (I-frame interval 0), 10-bit 4:2:2 tagged
     * BT.2020 full-range so it grades like the other 10-bit paths. The QTI encoder handles the huge
     * intra bitrate; [bitRate] is already scaled up for intra by [com.hletrd.findx9tele.camera.effectiveBpp].
     */
    private fun apvFormat(width: Int, height: Int, encoderRate: Double, captureRate: Double, bitRate: Int, transfer: ColorTransfer): MediaFormat {
        return MediaFormat.createVideoFormat(MIME_APV, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            applyFrameRate(encoderRate, captureRate)
            // All-intra: a keyframe every frame (interval 0). No inter prediction to grade around.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0)
            val tags = apvColorTagsFor(transfer)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, tags.standard)
            setInteger(MediaFormat.KEY_COLOR_RANGE, tags.range)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, tags.transfer)
        }
    }

    private fun avcFormat(width: Int, height: Int, encoderRate: Double, captureRate: Double, bitRate: Int): MediaFormat {
        return MediaFormat.createVideoFormat(MIME_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            applyFrameRate(encoderRate, captureRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
    }

    /** Returns the encoder MIME type for the given codec. */
    fun mimeFor(codec: VideoCodec): String = when (codec) {
        VideoCodec.HEVC -> MIME_HEVC
        VideoCodec.AVC -> MIME_AVC
        VideoCodec.APV -> MIME_APV
    }

    fun aacFormat(channelCount: Int = AUDIO_CHANNELS): MediaFormat {
        return MediaFormat.createAudioFormat(MIME_AAC, AUDIO_SAMPLE_RATE, channelCount.coerceIn(1, AUDIO_CHANNELS)).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
    }
}

/**
 * Per-transfer color metadata for a video encoder format. [profile] is the encoder profile
 * (null where the codec's profile isn't set this way, e.g. APV). Values are the framework's
 * compile-time int constants, so this stays JVM-unit-testable.
 */
internal data class VideoColorTags(
    val profile: Int?,
    val standard: Int,
    val range: Int,
    val transfer: Int,
)

/**
 * HEVC per-transfer tagging as an EXPRESSION-position `when` with no else: a new [ColorTransfer]
 * member fails the build here instead of silently shipping a branch that forgets
 * KEY_COLOR_TRANSFER — leaving it unset on a BT2020 full-range format makes this QTI encoder
 * default the VUI to ST2084 (PQ), and players then tone-map the footage as HDR and crush it
 * (found via ffprobe on a device recording). Shared shape with [apvColorTagsFor] so the two
 * codecs' tagging can't drift apart unnoticed.
 */
internal fun hevcColorTagsFor(transfer: ColorTransfer): VideoColorTags = when (transfer) {
    ColorTransfer.HLG -> VideoColorTags(
        profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
        standard = MediaFormat.COLOR_STANDARD_BT2020,
        range = MediaFormat.COLOR_RANGE_LIMITED,
        transfer = MediaFormat.COLOR_TRANSFER_HLG,
    )
    // No CICP code exists for a log curve. Tag SDR like other phone log formats: players show the
    // flat log image as-is and graders assign the O-Log2 IDT/LUT manually. (Device ffprobe note:
    // on this QTI encoder "SDR transfer + BT2020 full range" lands in the container as CICP 14 —
    // bt2020-10, functionally identical to BT.709 per H.273 — NOT the literal SDR code point, and
    // crucially not the ST2084/PQ mistag this tag exists to prevent.)
    ColorTransfer.LOG -> VideoColorTags(
        profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
        standard = MediaFormat.COLOR_STANDARD_BT2020,
        range = MediaFormat.COLOR_RANGE_FULL,
        transfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
    )
    // SDR: 8-bit Main profile, BT.709 limited-range, standard SDR transfer — matches the
    // untouched (no-OETF) frames the GL pipeline delivers for this setting.
    ColorTransfer.SDR -> VideoColorTags(
        profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
        standard = MediaFormat.COLOR_STANDARD_BT709,
        range = MediaFormat.COLOR_RANGE_LIMITED,
        transfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
    )
}

/**
 * APV per-transfer tagging (BT.2020 full-range 10-bit 4:2:2 for every transfer; only the transfer
 * id varies). Expression-position with no else for the same forgot-the-transfer protection as
 * [hevcColorTagsFor] — APV previously duplicated that decision by hand.
 */
internal fun apvColorTagsFor(transfer: ColorTransfer): VideoColorTags = when (transfer) {
    ColorTransfer.HLG -> VideoColorTags(
        profile = null,
        standard = MediaFormat.COLOR_STANDARD_BT2020,
        range = MediaFormat.COLOR_RANGE_FULL,
        transfer = MediaFormat.COLOR_TRANSFER_HLG,
    )
    ColorTransfer.LOG, ColorTransfer.SDR -> VideoColorTags(
        profile = null,
        standard = MediaFormat.COLOR_STANDARD_BT2020,
        range = MediaFormat.COLOR_RANGE_FULL,
        transfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
    )
}
