package com.hletrd.findx9tele.video

import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.hletrd.findx9tele.camera.ColorTransfer
import com.hletrd.findx9tele.camera.VideoCodec

/**
 * Builds the encoder MediaFormats.
 *
 * - HEVC: Main10 (10-bit) tagged Rec.2020, except SDR which is Main (8-bit) BT.709.
 *   - HLG: tagged with the HLG transfer so HDR players render it directly.
 *   - LOG: our GL pipeline bakes a flat log curve; there is no standard "log" transfer id, so the
 *          stream is tagged BT.2020 full-range with transfer left unspecified (grade manually).
 *   - SDR: plain Rec.709/SDR — the GL pipeline applies no curve (camera frames are already SDR),
 *          for footage that plays correctly everywhere with zero grading.
 *
 *   True 10-bit requires the EGL input surface to be 10-bit; if the device falls back to 8-bit the
 *   stream is still Main10 but not genuinely 10-bit. Verify on hardware.
 * - AVC: H.264 High profile, 8-bit SDR (BT.709). No HDR/log support; the engine forces SDR
 *        transfer when AVC is selected.
 */
object ColorProfiles {
    const val MIME_HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC
    const val MIME_AVC = MediaFormat.MIMETYPE_VIDEO_AVC
    const val MIME_AV1 = EncoderCaps.MIME_AV1
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
            when (transfer) {
                ColorTransfer.HLG -> {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                    setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                    setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
                }
                ColorTransfer.LOG -> {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                    setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
                    // No CICP code exists for a log curve, and leaving KEY_COLOR_TRANSFER unset let
                    // this QTI encoder default the VUI to ST2084 (PQ) — players then tone-mapped the
                    // O-Log2 data as HDR and crushed it (found via ffprobe on a device recording).
                    // Tag SDR like other phone log formats: players show the flat log image as-is
                    // and graders assign the O-Log2 IDT/LUT manually.
                    setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                }
                // SDR: 8-bit Main profile, BT.709 limited-range, standard SDR transfer — matches the
                // untouched (no-OETF) frames the GL pipeline delivers for this setting.
                ColorTransfer.SDR -> {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                    setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                    setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                }
            }
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
            VideoCodec.AV1 -> av1Format(width, height, encoderRate, captureRate, bitRate)
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

    /** AV1 8-bit SDR (BT.709). Software encoder on this device — the engine gates it to ≤1080p/≤30fps. */
    private fun av1Format(width: Int, height: Int, encoderRate: Double, captureRate: Double, bitRate: Int): MediaFormat {
        return MediaFormat.createVideoFormat(MIME_AV1, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            applyFrameRate(encoderRate, captureRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
    }

    /** Returns the encoder MIME type for the given codec. */
    fun mimeFor(codec: VideoCodec): String = when (codec) {
        VideoCodec.HEVC -> MIME_HEVC
        VideoCodec.AVC -> MIME_AVC
        VideoCodec.AV1 -> MIME_AV1
    }

    fun aacFormat(): MediaFormat {
        return MediaFormat.createAudioFormat(MIME_AAC, AUDIO_SAMPLE_RATE, AUDIO_CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
    }
}
