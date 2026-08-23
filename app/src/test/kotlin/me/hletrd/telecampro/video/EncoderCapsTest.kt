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
    fun `Main10 fact belongs to selected component rather than a different candidate`() {
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
        assertFalse(inventory.tenBitEncodeAvailable)
        val selection = inventory.selectionFor(VideoCodec.HEVC)!!
        assertTrue(encoderSelectionAdmitsTransfer(selection, ColorTransfer.SDR))
        assertFalse(encoderSelectionAdmitsTransfer(selection, ColorTransfer.HLG))
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
