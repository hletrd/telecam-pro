package me.hletrd.telecampro.ui.controls

import me.hletrd.telecampro.R
import me.hletrd.telecampro.camera.acceptedPhotoSessionOutputs
import me.hletrd.telecampro.camera.sessionAttemptPlan
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "10-bit video · stills off" names the designed 10-bit trade, so it must follow the ACCEPTED
 * session's HLG fact: not bare video mode (AGG2-35 — SDR video on the preview-only rung has no
 * still readers either), and not the 10-bit request (AGG3-17 — a request that fell down the ladder
 * to the 8-bit preview-only rung is neither 10-bit nor a deliberate trade).
 */
class NoStillOutputCaptionTest {
    /** The caption the sheet shows for the session [attempt] of a 10-bit request would accept. */
    private fun tenBitRequestCaptionAt(attempt: Int): Int {
        val plan = sessionAttemptPlan(
            attempt = attempt,
            wantHlg = true,
            supportsRaw = true,
            standalone = true,
            tenBitVideoOnly = true,
        )
        val outputs = acceptedPhotoSessionOutputs(
            processedReaderPresent = plan.useJpeg,
            rawReaderPresent = plan.useRaw,
            hlgSessionAccepted = plan.useHlg,
        )
        return noStillOutputCaption(outputs.hlg)
    }

    @Test
    fun `the accepted 10-bit still-less rung earns the trade caption`() {
        assertEquals(R.string.output_10_bit_video_stills_off, tenBitRequestCaptionAt(0))
    }

    @Test
    fun `a 10-bit request that fell to the 8-bit preview-only rung reads still capture unavailable`() {
        assertEquals(R.string.status_still_capture_unavailable, tenBitRequestCaptionAt(3))
    }

    @Test
    fun `an SDR session without still readers reads still capture unavailable`() {
        val outputs = acceptedPhotoSessionOutputs(processedReaderPresent = false, rawReaderPresent = false)
        assertEquals(R.string.status_still_capture_unavailable, noStillOutputCaption(outputs.hlg))
    }
}
