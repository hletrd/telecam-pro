package com.hletrd.findx9tele.video

import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.hletrd.findx9tele.camera.ColorTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the per-transfer encoder color tagging ([hevcColorTagsFor]/[apvColorTagsFor]). The bug this
 * closes by construction: leaving KEY_COLOR_TRANSFER unset on a BT2020 full-range format makes the
 * QTI encoder default the VUI to ST2084 (PQ) — players then tone-map log footage as HDR. Every
 * ColorTransfer entry is asserted, so a new member without a deliberate tag fails HERE as well as
 * at the expression-position `when`. (Framework values are compile-time int constants — JVM-safe.)
 */
class ColorTagsTest {

    @Test
    fun hevc_everyTransfer_setsAnExplicitNonPqTransfer() {
        for (t in ColorTransfer.entries) {
            val tags = hevcColorTagsFor(t)
            assertNotEquals(
                "transfer $t must never fall through to the PQ default",
                MediaFormat.COLOR_TRANSFER_ST2084,
                tags.transfer,
            )
        }
    }

    @Test
    fun hevc_hlg_isMain10Bt2020LimitedHlg() {
        val tags = hevcColorTagsFor(ColorTransfer.HLG)
        assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, tags.profile)
        assertEquals(MediaFormat.COLOR_STANDARD_BT2020, tags.standard)
        assertEquals(MediaFormat.COLOR_RANGE_LIMITED, tags.range)
        assertEquals(MediaFormat.COLOR_TRANSFER_HLG, tags.transfer)
    }

    @Test
    fun hevc_log_isMain10Bt2020FullTaggedSdr() {
        val tags = hevcColorTagsFor(ColorTransfer.LOG)
        assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, tags.profile)
        assertEquals(MediaFormat.COLOR_STANDARD_BT2020, tags.standard)
        assertEquals(MediaFormat.COLOR_RANGE_FULL, tags.range)
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, tags.transfer)
    }

    @Test
    fun hevc_sdr_isMainBt709Limited() {
        val tags = hevcColorTagsFor(ColorTransfer.SDR)
        assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain, tags.profile)
        assertEquals(MediaFormat.COLOR_STANDARD_BT709, tags.standard)
        assertEquals(MediaFormat.COLOR_RANGE_LIMITED, tags.range)
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, tags.transfer)
    }

    @Test
    fun apv_everyTransfer_isBt2020FullWithExplicitTransfer() {
        for (t in ColorTransfer.entries) {
            val tags = apvColorTagsFor(t)
            assertNull(tags.profile)
            assertEquals(MediaFormat.COLOR_STANDARD_BT2020, tags.standard)
            assertEquals(MediaFormat.COLOR_RANGE_FULL, tags.range)
            assertNotEquals(MediaFormat.COLOR_TRANSFER_ST2084, tags.transfer)
        }
        assertEquals(MediaFormat.COLOR_TRANSFER_HLG, apvColorTagsFor(ColorTransfer.HLG).transfer)
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, apvColorTagsFor(ColorTransfer.LOG).transfer)
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, apvColorTagsFor(ColorTransfer.SDR).transfer)
    }

    @Test
    fun hevcAndApv_agreeOnTheTransferForEveryEntry() {
        // The two codecs previously duplicated this decision by hand; pin that they stay in sync.
        for (t in ColorTransfer.entries) {
            assertEquals(
                "HEVC and APV must tag the same transfer for $t",
                hevcColorTagsFor(t).transfer,
                apvColorTagsFor(t).transfer,
            )
        }
    }
}
