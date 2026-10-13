package me.hletrd.telecampro.ui.controls

import me.hletrd.telecampro.R
import me.hletrd.telecampro.camera.ColorTransfer
import me.hletrd.telecampro.camera.acceptedPhotoSessionOutputs
import me.hletrd.telecampro.camera.sessionAttemptPlan
import me.hletrd.telecampro.camera.tenBitSessionWanted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Native ISP log (2026-10-14) is the ordinary 8-bit session: it must keep its still readers (so
 * the "10-bit video · stills off" caption cannot appear for it) and must not carry the
 * "already tone-mapped stream" caveat that is false there.
 */
class NativeLogCaptionTest {

    @Test
    fun `a native log session keeps stills and RAW on its full rung`() {
        ColorTransfer.entries.filter { it.isLog }.forEach { transfer ->
            val tenBit = tenBitSessionWanted(videoMode = true, transfer = transfer, nativeLogCurveSupported = true)
            assertFalse(tenBit)
            val plan = sessionAttemptPlan(
                attempt = 0,
                wantHlg = tenBit,
                supportsRaw = true,
                standalone = true,
                logicalMultiCamera = false,
                teleconverterMode = false,
                wantHiRes = false,
                tenBitVideoOnly = tenBit,
                frontRoute = false,
                yuvStillRequired = false,
                rawStandaloneOnly = true,
            )
            assertTrue("$transfer must keep the processed still reader", plan.useJpeg)
            assertTrue("$transfer must keep RAW on the standalone full rung", plan.useRaw)
            assertFalse("$transfer must not ask for HLG10", plan.useHlg)
            val outputs = acceptedPhotoSessionOutputs(plan, plan.useJpeg, plan.useRaw, hiResReaderPresent = false)
            assertTrue(outputs.hasStillTarget)
            assertFalse(outputs.hlg)
        }
    }

    @Test
    fun `the GL fallback still takes the designed 10-bit trade`() {
        val tenBit = tenBitSessionWanted(videoMode = true, transfer = ColorTransfer.SLOG3, nativeLogCurveSupported = false)
        val plan = sessionAttemptPlan(
            attempt = 0,
            wantHlg = tenBit,
            supportsRaw = true,
            standalone = true,
            logicalMultiCamera = false,
            teleconverterMode = false,
            wantHiRes = false,
            tenBitVideoOnly = tenBit,
            frontRoute = false,
            yuvStillRequired = false,
            rawStandaloneOnly = true,
        )
        val outputs = acceptedPhotoSessionOutputs(plan, plan.useJpeg, plan.useRaw, hiResReaderPresent = false)
        assertFalse(outputs.hasStillTarget)
        assertEquals(R.string.output_10_bit_video_stills_off, noStillOutputCaption(outputs.hlg))
    }

    @Test
    fun `transfer caption follows the native curve capability`() {
        assertNull(transferSourceCaption(ColorTransfer.SDR, nativeLogCurveSupported = true))
        assertNull(transferSourceCaption(ColorTransfer.SDR, nativeLogCurveSupported = false))
        ColorTransfer.entries.filter { it.isLog }.forEach {
            assertEquals(R.string.video_native_log_source, transferSourceCaption(it, nativeLogCurveSupported = true))
            assertEquals(R.string.video_tone_mapped_source, transferSourceCaption(it, nativeLogCurveSupported = false))
        }
        // HLG is never ISP-encoded: its source is the tone-mapped HLG10 stream either way.
        assertEquals(R.string.video_tone_mapped_source, transferSourceCaption(ColorTransfer.HLG, nativeLogCurveSupported = true))
    }
}
