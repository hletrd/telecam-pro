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

    @Test
    fun `native log reads 10-bit only on an accepted P010 bridge session`() {
        ColorTransfer.entries.filter { it.isLog }.forEach {
            assertEquals(
                "$it",
                R.string.video_native_log_source_10bit,
                transferSourceCaption(it, nativeLogCurveSupported = true, tenBitNativeLogSession = true),
            )
            assertEquals(
                "$it, bridge refused or not accepted",
                R.string.video_native_log_source,
                transferSourceCaption(it, nativeLogCurveSupported = true, tenBitNativeLogSession = false),
            )
            // The GL-log fallback is never ten-bit native log, whatever a stale flag says.
            assertEquals(
                R.string.video_tone_mapped_source,
                transferSourceCaption(it, nativeLogCurveSupported = false, tenBitNativeLogSession = true),
            )
        }
        assertNull(transferSourceCaption(ColorTransfer.SDR, nativeLogCurveSupported = true, tenBitNativeLogSession = true))
        assertEquals(
            R.string.video_tone_mapped_source,
            transferSourceCaption(ColorTransfer.HLG, nativeLogCurveSupported = true, tenBitNativeLogSession = true),
        )
    }

    @Test
    fun `accepted outputs carry the bridge truth`() {
        val plan = sessionAttemptPlan(
            attempt = 0,
            wantHlg = false,
            supportsRaw = true,
            standalone = true,
            logicalMultiCamera = false,
            teleconverterMode = false,
            wantHiRes = false,
            tenBitVideoOnly = false,
            frontRoute = false,
            yuvStillRequired = false,
            rawStandaloneOnly = true,
        )
        assertTrue(acceptedPhotoSessionOutputs(plan, true, true, false, p010BridgePresent = true).tenBitNativeLog)
        assertFalse(acceptedPhotoSessionOutputs(plan, true, true, false, p010BridgePresent = false).tenBitNativeLog)
        assertFalse(acceptedPhotoSessionOutputs(plan, true, true, false).tenBitNativeLog)
    }

    // ---- Log EI offset row ----

    @Test
    fun `the Log EI offset row appears only for a log transfer on a native-curve camera`() {
        ColorTransfer.entries.filter { it.isLog }.forEach {
            assertTrue("$it native", logEiOffsetSettingVisible(it, nativeLogCurveSupported = true))
            assertFalse("$it GL fallback", logEiOffsetSettingVisible(it, nativeLogCurveSupported = false))
        }
        listOf(ColorTransfer.SDR, ColorTransfer.HLG).forEach {
            assertFalse("$it", logEiOffsetSettingVisible(it, nativeLogCurveSupported = true))
            assertFalse("$it", logEiOffsetSettingVisible(it, nativeLogCurveSupported = false))
        }
    }

    @Test
    fun `the row offers 0 to 3 with EV-labelled lifts`() {
        assertEquals(listOf(0, 1, 2, 3), LOG_EI_OFFSET_OPTIONS)
        assertEquals(listOf("0", "+1 EV", "+2 EV", "+3 EV"), LOG_EI_OFFSET_OPTIONS.map(::logEiOffsetLabel))
    }

    @Test
    fun `the OSD log tag gains a compact suffix only when the offset can act`() {
        assertEquals("SLOG3 +2", transferLabelShort(ColorTransfer.SLOG3) + logEiOsdSuffix(ColorTransfer.SLOG3, true, 2))
        assertEquals("", logEiOsdSuffix(ColorTransfer.SLOG3, true, 0))
        assertEquals("", logEiOsdSuffix(ColorTransfer.SLOG3, false, 2))
        assertEquals("", logEiOsdSuffix(ColorTransfer.HLG, true, 2))
        assertEquals("", logEiOsdSuffix(ColorTransfer.SDR, true, 3))
    }

    @Test
    fun `only the phone-maker logs carry the maker caption, above the same source caveat`() {
        for (t in ColorTransfer.entries) {
            if (t.isNativeLog) {
                assertEquals("$t", R.string.video_maker_log_caption, transferMakerCaption(t))
                // The maker caption ADDS to the honesty caveat; it never replaces it.
                assertEquals("$t", R.string.video_native_log_source, transferSourceCaption(t, nativeLogCurveSupported = true))
                assertEquals("$t", R.string.video_tone_mapped_source, transferSourceCaption(t, nativeLogCurveSupported = false))
            } else {
                assertNull("$t", transferMakerCaption(t))
            }
        }
    }
}
