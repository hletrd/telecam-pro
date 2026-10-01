package me.hletrd.telecampro.ui.controls

import me.hletrd.telecampro.R
import me.hletrd.telecampro.camera.ColorTransfer
import me.hletrd.telecampro.camera.tenBitSessionWanted
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AGG2-35: "10-bit video · stills off" names the designed 10-bit trade, so it must follow the
 * request's 10-bit answer, not bare video mode. SDR video on the preview-only rung has no still
 * readers either, and claiming 10-bit there was false.
 */
class NoStillOutputCaptionTest {
    private fun caption(videoMode: Boolean, transfer: ColorTransfer): Int =
        noStillOutputCaption(tenBitSessionWanted(videoMode, transfer))

    @Test
    fun `only a non-SDR video request earns the 10-bit trade caption`() {
        ColorTransfer.entries.filter { it != ColorTransfer.SDR }.forEach { transfer ->
            assertEquals(transfer.name, R.string.output_10_bit_video_stills_off, caption(true, transfer))
        }
    }

    @Test
    fun `SDR video and every photo session fall back to the plain unavailable caption`() {
        assertEquals(R.string.status_still_capture_unavailable, caption(true, ColorTransfer.SDR))
        ColorTransfer.entries.forEach { transfer ->
            assertEquals(transfer.name, R.string.status_still_capture_unavailable, caption(false, transfer))
        }
    }
}
