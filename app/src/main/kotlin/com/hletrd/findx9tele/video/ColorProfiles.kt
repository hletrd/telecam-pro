package com.hletrd.findx9tele.video

import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.hletrd.findx9tele.camera.ColorTransfer

/**
 * Builds the encoder MediaFormats. Video is HEVC Main10 (10-bit) tagged Rec.2020.
 *
 * - HLG: tagged with the HLG transfer so HDR players render it directly.
 * - LOG: our GL pipeline bakes a flat log curve; there is no standard "log" transfer id, so the
 *        stream is tagged BT.2020 full-range with transfer left unspecified (grade manually).
 *
 * True 10-bit requires the EGL input surface to be 10-bit; if the device falls back to 8-bit the
 * stream is still Main10 but not genuinely 10-bit. Verify on hardware.
 */
object ColorProfiles {
    const val MIME_HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC
    const val MIME_AAC = MediaFormat.MIMETYPE_AUDIO_AAC

    const val AUDIO_SAMPLE_RATE = 48_000
    const val AUDIO_CHANNELS = 2
    const val AUDIO_BIT_RATE = 192_000

    fun hevcFormat(width: Int, height: Int, fps: Int, bitRate: Int, transfer: ColorTransfer): MediaFormat {
        return MediaFormat.createVideoFormat(MIME_HEVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            when (transfer) {
                ColorTransfer.HLG -> {
                    setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                    setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
                }
                ColorTransfer.LOG -> {
                    setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
                }
            }
        }
    }

    fun aacFormat(): MediaFormat {
        return MediaFormat.createAudioFormat(MIME_AAC, AUDIO_SAMPLE_RATE, AUDIO_CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
    }
}
