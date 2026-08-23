package me.hletrd.telecampro.video

import android.media.MediaCodecInfo
import android.media.MediaFormat
import me.hletrd.telecampro.camera.VideoCodec
import me.hletrd.telecampro.camera.ColorTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two pure seams of the encoder scan (TEST4-19): the name heuristic that backs a throwing
 * isHardwareAccelerated, and the hardware-first tie-break. EncoderCaps itself needs a live
 * MediaCodecList and stays device-verified.
 */
class EncoderCapsTest {

    private fun component(
        name: String,
        mime: String,
        hardware: Boolean = true,
        profiles: Set<Int>? = emptySet(),
    ) = CodecComponent(
        name = name,
        encoder = true,
        supportedTypes = setOf(mime),
        hardwareAccelerated = hardware,
        hevcProfiles = profiles,
    )

    @Test
    fun `empty and throwing discovery fail closed`() {
        val empty = discoverCodecInventory { emptyList() }
        assertTrue(empty.availableVideoCodecs.isEmpty())
        assertFalse(empty.heifEncodeAvailable)
        assertFalse(empty.tenBitEncodeAvailable)

        val thrown = discoverCodecInventory { throw IllegalStateException("codec service down") }
        assertTrue(thrown.availableVideoCodecs.isEmpty())
        assertNull(thrown.selectionFor(VideoCodec.HEVC))
        assertFalse(thrown.tenBitEncodeAvailable)
    }

    @Test
    fun `capability exception never grants Main10`() {
        val inventory = buildCodecInventory(
            listOf(component("vendor.hevc", MediaFormat.MIMETYPE_VIDEO_HEVC, profiles = null)),
        )
        assertTrue(inventory.heifEncodeAvailable)
        assertFalse(inventory.tenBitEncodeAvailable)
        assertFalse(inventory.selectionFor(VideoCodec.HEVC)!!.main10)
    }

    @Test
    fun `multi-component inventory carries exact admitted hardware identity`() {
        val inventory = buildCodecInventory(
            listOf(
                component(
                    "software.hevc",
                    MediaFormat.MIMETYPE_VIDEO_HEVC,
                    hardware = false,
                    profiles = setOf(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10),
                ),
                component(
                    "vendor.hevc",
                    MediaFormat.MIMETYPE_VIDEO_HEVC,
                    profiles = setOf(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10),
                ),
                component("vendor.avc", MediaFormat.MIMETYPE_VIDEO_AVC),
            ),
        )

        assertEquals(listOf(VideoCodec.HEVC, VideoCodec.AVC), inventory.availableVideoCodecs)
        assertEquals("vendor.hevc", inventory.selectionFor(VideoCodec.HEVC)?.codecName)
        assertEquals("vendor.avc", inventory.selectionFor(VideoCodec.AVC)?.codecName)
        assertTrue(inventory.tenBitEncodeAvailable)
    }

    @Test
    fun `Main10 intent selects the capable component without changing SDR registry preference`() {
        val inventory = buildCodecInventory(
            listOf(
                component(
                    "software.main10",
                    MediaFormat.MIMETYPE_VIDEO_HEVC,
                    hardware = false,
                    profiles = setOf(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10),
                ),
                component(
                    "vendor.main-only",
                    MediaFormat.MIMETYPE_VIDEO_HEVC,
                    profiles = setOf(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain),
                ),
            ),
        )

        assertEquals("vendor.main-only", inventory.selectionFor(VideoCodec.HEVC)?.codecName)
        assertTrue(inventory.tenBitEncodeAvailable)
        assertEquals(
            "software.main10",
            inventory.selectionFor(VideoCodec.HEVC, ColorTransfer.HLG)?.codecName,
        )
        assertEquals(
            listOf("vendor.main-only", "software.main10"),
            inventory.candidatesFor(VideoCodec.HEVC).map { it.codecName },
        )
        assertEquals(
            listOf("software.main10"),
            inventory.candidatesFor(VideoCodec.HEVC, ColorTransfer.SLOG3).map { it.codecName },
        )
    }

    @Test
    fun `hardware candidates remain registry-stable across input permutations`() {
        val first = buildCodecInventory(
            listOf(
                component("sw", MediaFormat.MIMETYPE_VIDEO_HEVC, hardware = false),
                component("hw-a", MediaFormat.MIMETYPE_VIDEO_HEVC),
                component("hw-b", MediaFormat.MIMETYPE_VIDEO_HEVC),
            ),
        )
        val second = buildCodecInventory(
            listOf(
                component("hw-b", MediaFormat.MIMETYPE_VIDEO_HEVC),
                component("sw", MediaFormat.MIMETYPE_VIDEO_HEVC, hardware = false),
                component("hw-a", MediaFormat.MIMETYPE_VIDEO_HEVC),
            ),
        )

        assertEquals(listOf("hw-a", "hw-b", "sw"), first.candidatesFor(VideoCodec.HEVC).map { it.codecName })
        assertEquals(listOf("hw-b", "hw-a", "sw"), second.candidatesFor(VideoCodec.HEVC).map { it.codecName })
    }

    @Test
    fun `configure attempts preserve requested size before spending resolution`() {
        val limited = EncoderSelection(
            VideoCodec.HEVC, "limited", MediaFormat.MIMETYPE_VIDEO_HEVC, true, false,
        )
        val full = limited.copy(codecName = "full")
        val attempts = encoderConfigureAttempts(listOf(limited, full), 1080, 1920)

        assertEquals(
            listOf("limited" to (1080 to 1920), "full" to (1080 to 1920)),
            attempts.take(2).map { it.selection.codecName to (it.width to it.height) },
        )
        assertTrue(attempts[2].width < 1080)
    }

    @Test
    fun `configure runner releases rejected component before accepting the next at requested size`() {
        val limited = EncoderSelection(
            VideoCodec.HEVC, "limited", MediaFormat.MIMETYPE_VIDEO_HEVC, true, false,
        )
        val full = limited.copy(codecName = "full")
        data class FakeOwner(val name: String)
        val visited = mutableListOf<Pair<String, Pair<Int, Int>>>()
        val released = mutableListOf<String>()

        val accepted = firstConfiguredEncoderAttempt(
            attempts = encoderConfigureAttempts(listOf(limited, full), 1080, 1920),
            acquire = { FakeOwner(it.codecName) },
            configure = { owner, attempt ->
                visited += owner.name to (attempt.width to attempt.height)
                if (owner.name == "limited") error("requested portrait size rejected")
            },
            releaseRejected = { released += it.name },
        )

        assertEquals("full", accepted.owner.name)
        assertEquals(1080, accepted.attempt.width)
        assertEquals(1920, accepted.attempt.height)
        assertEquals(listOf("limited"), released)
        assertEquals(
            listOf("limited" to (1080 to 1920), "full" to (1080 to 1920)),
            visited,
        )
    }

    @Test
    fun `smaller fallback attempt resolves its own scaled bitrate`() {
        val selection = EncoderSelection(
            VideoCodec.HEVC, "limited", MediaFormat.MIMETYPE_VIDEO_HEVC, true, false,
        )
        data class FakeOwner(val name: String)
        val attemptedRates = mutableListOf<Triple<Int, Int, Int>>()

        val accepted = firstConfiguredEncoderAttempt(
            attempts = encoderConfigureAttempts(listOf(selection), 1080, 1920),
            acquire = { FakeOwner(it.codecName) },
            configure = { _, attempt ->
                val rate = me.hletrd.telecampro.camera.videoBitRate(
                    attempt.width,
                    attempt.height,
                    30.0,
                    0.4f,
                    VideoCodec.HEVC,
                )
                attemptedRates += Triple(attempt.width, attempt.height, rate)
                if (attempt.width == 1080) error("full raster rejected")
            },
            releaseRejected = {},
        )

        assertTrue(accepted.attempt.width < 1080)
        assertTrue(attemptedRates.last().third < attemptedRates.first().third)
    }

    @Test
    fun `AVC rejects every non-SDR transfer at the final recorder boundary`() {
        val avc = EncoderSelection(
            VideoCodec.AVC, "vendor.avc", MediaFormat.MIMETYPE_VIDEO_AVC, true, false,
        )
        assertTrue(encoderSelectionAdmitsTransfer(avc, ColorTransfer.SDR))
        assertFalse(encoderSelectionAdmitsTransfer(avc, ColorTransfer.HLG))
        assertFalse(encoderSelectionAdmitsTransfer(avc, ColorTransfer.SLOG3))
        val apv = avc.copy(
            codec = VideoCodec.APV,
            codecName = "vendor.apv",
            mime = "video/apv",
        )
        assertFalse(encoderSelectionAdmitsTransfer(apv, ColorTransfer.SDR))
    }

    @Test(expected = IllegalStateException::class)
    fun `empty configure attempt list fails closed`() {
        firstConfiguredEncoderAttempt<Any>(
            attempts = emptyList(),
            acquire = { error("unreachable") },
            configure = { _, _ -> error("unreachable") },
            releaseRejected = {},
        )
    }

    @Test
    fun `known software encoder names classify as software`() {
        assertFalse(looksHardwareAccelerated("c2.android.avc.encoder"))
        assertFalse(looksHardwareAccelerated("c2.android.av1.encoder"))
        assertFalse(looksHardwareAccelerated("OMX.google.h264.encoder"))
    }

    @Test
    fun `vendor encoder names classify as hardware`() {
        assertTrue(looksHardwareAccelerated("c2.qti.hevc.encoder"))
        assertTrue(looksHardwareAccelerated("c2.qti.apv.encoder"))
        assertTrue(looksHardwareAccelerated("OMX.qcom.video.encoder.avc"))
    }

    @Test
    fun `first hardware candidate wins immediately`() {
        assertEquals(
            "hw1",
            pickBestEncoder(listOf("sw1" to false, "hw1" to true, "hw2" to true)),
        )
    }

    @Test
    fun `first software candidate is the remembered fallback`() {
        // A later software match must never displace an earlier one (the reordering bug class
        // the extraction pins).
        assertEquals("sw1", pickBestEncoder(listOf("sw1" to false, "sw2" to false)))
    }

    @Test
    fun `no candidates yields null`() {
        assertNull(pickBestEncoder(emptyList<Pair<String, Boolean>>()))
    }
}
