package me.hletrd.telecampro.video

import android.media.MediaCodecInfo
import android.media.MediaFormat
import me.hletrd.telecampro.camera.ColorTransfer
import me.hletrd.telecampro.camera.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [ColorTagsTest] pins the pure tag TABLES; this pins that the encoder MediaFormat builders
 * actually WRITE them. The trap it guards: leaving KEY_COLOR_TRANSFER unset on a BT2020 full-range
 * HEVC format makes the QTI encoder tag the VUI ST2084 (PQ), and players then tone-map the footage
 * as HDR. A refactor that drops a `setInteger` (or stops applying the profile) passes every table
 * test, so the built format itself is asserted for every codec × transfer. MediaFormat is a real
 * class under Robolectric, unlike android.jar's throwing host stubs.
 */
@RunWith(RobolectricTestRunner::class)
class ColorProfilesFormatTest {

    @Test
    fun `every codec and transfer writes explicit color keys from its tag table`() {
        for (codec in VideoCodec.entries) {
            for (transfer in ColorTransfer.entries) {
                val format = build(codec, transfer)
                val label = "$codec/$transfer"
                val expected = expectedTags(codec, transfer)

                assertEquals(label, ColorProfiles.mimeFor(codec), format.getString(MediaFormat.KEY_MIME))
                assertEquals(label, 3840, format.getInteger(MediaFormat.KEY_WIDTH))
                assertEquals(label, 2160, format.getInteger(MediaFormat.KEY_HEIGHT))
                assertEquals(label, BIT_RATE, format.getInteger(MediaFormat.KEY_BIT_RATE))
                assertEquals(
                    label,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                    format.getInteger(MediaFormat.KEY_COLOR_FORMAT),
                )
                for (key in COLOR_KEYS) assertTrue("$label sets $key", format.containsKey(key))
                assertEquals(label, expected.standard, format.getInteger(MediaFormat.KEY_COLOR_STANDARD))
                assertEquals(label, expected.range, format.getInteger(MediaFormat.KEY_COLOR_RANGE))
                assertEquals(label, expected.transfer, format.getInteger(MediaFormat.KEY_COLOR_TRANSFER))
                if (expected.profile == null) {
                    assertFalse("$label leaves the profile to the codec", format.containsKey(MediaFormat.KEY_PROFILE))
                } else {
                    assertEquals(label, expected.profile, format.getInteger(MediaFormat.KEY_PROFILE))
                }
                assertEquals(
                    "$label keyframe cadence",
                    if (codec == VideoCodec.APV) 0 else 1,
                    format.getInteger(MediaFormat.KEY_I_FRAME_INTERVAL),
                )
            }
        }
    }

    @Test
    fun `AVC is always 8-bit High BT709 SDR whatever transfer is requested`() {
        for (transfer in ColorTransfer.entries) {
            val format = build(VideoCodec.AVC, transfer)
            assertEquals(MediaCodecInfo.CodecProfileLevel.AVCProfileHigh, format.getInteger(MediaFormat.KEY_PROFILE))
            assertEquals(MediaFormat.COLOR_STANDARD_BT709, format.getInteger(MediaFormat.KEY_COLOR_STANDARD))
            assertEquals(MediaFormat.COLOR_RANGE_LIMITED, format.getInteger(MediaFormat.KEY_COLOR_RANGE))
            assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, format.getInteger(MediaFormat.KEY_COLOR_TRANSFER))
        }
    }

    @Test
    fun `NTSC drop-frame rate is tagged exactly and real-time capture omits capture keys`() {
        for (codec in VideoCodec.entries) {
            val format = ColorProfiles.videoFormat(
                codec, 3840, 2160, NTSC_2997, captureRate = 0.0, BIT_RATE, ColorTransfer.SDR,
            )
            assertEquals(29.97f, format.getFloat(MediaFormat.KEY_FRAME_RATE), 1e-3f)
            assertEquals(29.97f, format.getFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER), 1e-3f)
            assertEquals(NTSC_2997.toFloat(), format.getFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER))
            assertFalse("$codec", format.containsKey(MediaFormat.KEY_CAPTURE_RATE))
            assertFalse("$codec", format.containsKey(MediaFormat.KEY_OPERATING_RATE))
        }
    }

    @Test
    fun `faster-than-real-time capture sets capture and operating rate`() {
        val format = ColorProfiles.videoFormat(
            VideoCodec.HEVC, 1920, 1080, 30.0, captureRate = 120.0, BIT_RATE, ColorTransfer.SDR,
        )
        assertEquals(30f, format.getFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER))
        assertEquals(120f, format.getFloat(MediaFormat.KEY_CAPTURE_RATE))
        assertEquals(120, format.getInteger(MediaFormat.KEY_OPERATING_RATE))
    }

    @Test
    fun `AAC format is LC stereo 48 kHz and clamps the channel count`() {
        val stereo = ColorProfiles.aacFormat()
        assertEquals(ColorProfiles.MIME_AAC, stereo.getString(MediaFormat.KEY_MIME))
        assertEquals(ColorProfiles.AUDIO_SAMPLE_RATE, stereo.getInteger(MediaFormat.KEY_SAMPLE_RATE))
        assertEquals(ColorProfiles.AUDIO_CHANNELS, stereo.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        assertEquals(
            MediaCodecInfo.CodecProfileLevel.AACObjectLC,
            stereo.getInteger(MediaFormat.KEY_AAC_PROFILE),
        )
        assertEquals(ColorProfiles.AUDIO_BIT_RATE, stereo.getInteger(MediaFormat.KEY_BIT_RATE))
        assertEquals(16_384, stereo.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
        assertEquals(1, ColorProfiles.aacFormat(channelCount = 0).getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        assertEquals(2, ColorProfiles.aacFormat(channelCount = 6).getInteger(MediaFormat.KEY_CHANNEL_COUNT))
    }

    private fun build(codec: VideoCodec, transfer: ColorTransfer): MediaFormat =
        ColorProfiles.videoFormat(codec, 3840, 2160, 30.0, captureRate = 0.0, BIT_RATE, transfer)

    private fun expectedTags(codec: VideoCodec, transfer: ColorTransfer): VideoColorTags = when (codec) {
        VideoCodec.HEVC -> hevcColorTagsFor(transfer)
        VideoCodec.APV -> apvColorTagsFor(transfer)
        VideoCodec.AVC -> VideoColorTags(
            profile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
            standard = MediaFormat.COLOR_STANDARD_BT709,
            range = MediaFormat.COLOR_RANGE_LIMITED,
            transfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
        )
    }

    private companion object {
        const val BIT_RATE = 100_000_000
        const val NTSC_2997 = 30000.0 / 1001.0
        val COLOR_KEYS = listOf(
            MediaFormat.KEY_COLOR_STANDARD,
            MediaFormat.KEY_COLOR_RANGE,
            MediaFormat.KEY_COLOR_TRANSFER,
        )
    }
}
