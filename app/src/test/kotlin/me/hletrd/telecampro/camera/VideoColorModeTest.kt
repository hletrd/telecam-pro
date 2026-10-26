package me.hletrd.telecampro.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/** Gamma = Off / HLG / Log / RAW Log (owner decision 2026-10-26; CameraState.kt VideoColorMode). */
class VideoColorModeTest {

    @Test
    fun `the modes come in the owner's order, with RAW Log only where the route offers it`() {
        assertEquals(
            listOf(VideoColorMode.OFF, VideoColorMode.HLG, VideoColorMode.LOG, VideoColorMode.RAW_LOG),
            availableVideoColorModes(VideoCodec.HEVC, tenBitEncodeAvailable = true, rawLogOffered = true),
        )
        assertEquals(
            listOf(VideoColorMode.OFF, VideoColorMode.HLG, VideoColorMode.LOG),
            availableVideoColorModes(VideoCodec.HEVC, tenBitEncodeAvailable = true, rawLogOffered = false),
        )
    }

    @Test
    fun `an encoder that cannot carry ten bits offers Off alone, RAW route or not`() {
        for (raw in listOf(true, false)) {
            assertEquals(listOf(VideoColorMode.OFF), availableVideoColorModes(VideoCodec.HEVC, false, raw))
            assertEquals(listOf(VideoColorMode.OFF), availableVideoColorModes(VideoCodec.AVC, true, raw))
        }
    }

    @Test
    fun `the mode reads off the transfer, and RAW Log needs intent and route together`() {
        for (raw in listOf(true, false)) {
            for (offered in listOf(true, false)) {
                assertEquals(VideoColorMode.OFF, videoColorModeOf(ColorTransfer.SDR, raw, offered))
                assertEquals(VideoColorMode.HLG, videoColorModeOf(ColorTransfer.HLG, raw, offered))
            }
        }
        for (log in VIDEO_LOG_PROFILES) {
            assertEquals(VideoColorMode.RAW_LOG, videoColorModeOf(log, rawLogRequested = true, rawLogOffered = true))
            // A kept intent on a route that cannot develop RAW records native log, and says so.
            assertEquals(VideoColorMode.LOG, videoColorModeOf(log, rawLogRequested = true, rawLogOffered = false))
            assertEquals(VideoColorMode.LOG, videoColorModeOf(log, rawLogRequested = false, rawLogOffered = true))
        }
    }

    @Test
    fun `both log modes offer the same industry-standard profiles`() {
        assertEquals(listOf(ColorTransfer.SLOG3, ColorTransfer.SLOG3_CINE, ColorTransfer.LOGC3), VIDEO_LOG_PROFILES)
        VIDEO_LOG_PROFILES.forEach { assertEquals("$it", true, rawLogTransfer(it)) }
    }
}
