package com.hletrd.findx9tele.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the HAL-crash-critical session fallback ladder ordering (attempt 0 full → 1 drop RAW → 2
 * also drop HLG → 3 preview-only) and the standalone-only RAW gate — the exact combos CLAUDE.md
 * documents as HAL-crashing when wrong.
 */
class SessionFallbackLadderTest {

    @Test
    fun `attempt 0 is the full session`() {
        val p = sessionAttemptPlan(attempt = 0, wantHlg = true, supportsRaw = true, standalone = true)
        assertEquals(SessionAttemptPlan(useHlg = true, useJpeg = true, useRaw = true), p)
    }

    @Test
    fun `attempt 1 drops RAW first, keeps HLG and JPEG`() {
        val p = sessionAttemptPlan(attempt = 1, wantHlg = true, supportsRaw = true, standalone = true)
        assertEquals(SessionAttemptPlan(useHlg = true, useJpeg = true, useRaw = false), p)
    }

    @Test
    fun `attempt 2 also drops HLG`() {
        val p = sessionAttemptPlan(attempt = 2, wantHlg = true, supportsRaw = true, standalone = true)
        assertEquals(SessionAttemptPlan(useHlg = false, useJpeg = true, useRaw = false), p)
    }

    @Test
    fun `attempt 3 is preview-only`() {
        val p = sessionAttemptPlan(attempt = 3, wantHlg = true, supportsRaw = true, standalone = true)
        assertEquals(SessionAttemptPlan(useHlg = false, useJpeg = false, useRaw = false), p)
    }

    @Test
    fun `RAW is never enabled through physical routing`() {
        // Routed RAW SIGSEGVs the QTI HAL (DataSpace override for format 0x20) — the gate must hold
        // even on attempt 0 with full RAW support.
        val p = sessionAttemptPlan(attempt = 0, wantHlg = true, supportsRaw = true, standalone = false)
        assertFalse(p.useRaw)
        assertTrue(p.useJpeg)
    }

    @Test
    fun `RAW requires the capability`() {
        assertFalse(sessionAttemptPlan(attempt = 0, wantHlg = false, supportsRaw = false, standalone = true).useRaw)
    }

    @Test
    fun `HLG is never enabled when unwanted or unsupported`() {
        assertFalse(sessionAttemptPlan(attempt = 0, wantHlg = false, supportsRaw = true, standalone = true).useHlg)
    }

    @Test
    fun logicalMultiCamera_neverGetsRaw_evenAtAttempt0() {
        // Device-observed 2026-07-14: a still with the RAW target on the plain logical camera
        // errors the whole camera device ~5 s after the shot (CAMERA_ERROR(3)); the image never
        // arrives. RAW is standalone-only in BOTH failure modes.
        val p = sessionAttemptPlan(attempt = 0, wantHlg = false, supportsRaw = true, standalone = true, logicalMultiCamera = true)
        assertFalse(p.useRaw)
        assertTrue(p.useJpeg)
    }

    @Test
    fun `teleconverter tries regular full session before preview-only`() {
        val vendor = sessionAttemptPlan(
            attempt = 0,
            wantHlg = true,
            supportsRaw = true,
            standalone = true,
            teleconverterMode = true,
        )
        val regular = sessionAttemptPlan(
            attempt = 3,
            wantHlg = true,
            supportsRaw = true,
            standalone = true,
            teleconverterMode = true,
        )

        assertTrue(vendor.useVendorOperationMode)
        assertFalse(regular.useVendorOperationMode)
        assertEquals(vendor.copy(useVendorOperationMode = false), regular)
        assertTrue(regular.useJpeg)
        assertTrue(regular.useRaw)
    }

    @Test
    fun `teleconverter reserves both preview-only attempts for last`() {
        val vendor = sessionAttemptPlan(6, true, true, true, teleconverterMode = true)
        val regular = sessionAttemptPlan(7, true, true, true, teleconverterMode = true)

        assertFalse(vendor.useJpeg)
        assertTrue(vendor.useVendorOperationMode)
        assertFalse(regular.useJpeg)
        assertFalse(regular.useVendorOperationMode)
    }

    @Test
    fun `hi-res rides attempt 0 with RAW forced off`() {
        // A 200MP blob + RAW in one session is the over-demanding combo this HAL punishes, so the
        // one hi-res attempt must NEVER carry RAW even with full standalone RAW support.
        val p = sessionAttemptPlan(
            attempt = 0,
            wantHlg = true,
            supportsRaw = true,
            standalone = true,
            wantHiRes = true,
        )
        assertTrue(p.useHiResStill)
        assertFalse(p.useRaw)
        assertTrue(p.useJpeg)
        assertTrue(p.useHlg)
    }

    @Test
    fun `hi-res failure falls back to the FULL session including RAW`() {
        // The hi-res rung is PREPENDED: a rejected hi-res combo must retry the ordinary full plan
        // (RAW alive) before the ladder degrades anything else. The old mapping reused the
        // ordinary indices, whose attempt 1 already had RAW off — a rejected hi-res session
        // silently lost RAW for the whole session (cycle-6 debugger F3).
        assertEquals(
            SessionAttemptPlan(useHlg = true, useJpeg = true, useRaw = true),
            sessionAttemptPlan(1, wantHlg = true, supportsRaw = true, standalone = true, wantHiRes = true),
        )
    }

    @Test
    fun `hi-res shifts the ordinary ladder by exactly one`() {
        // Every post-hi-res attempt is byte-identical to the ordinary ladder one rung earlier —
        // an INDEPENDENT expectation (different inputs on the two sides), not the function
        // compared against itself (cycle-6 test-review F-A1).
        for (attempt in 1..4) {
            assertEquals(
                sessionAttemptPlan(attempt - 1, wantHlg = true, supportsRaw = true, standalone = true),
                sessionAttemptPlan(
                    attempt,
                    wantHlg = true,
                    supportsRaw = true,
                    standalone = true,
                    wantHiRes = true,
                ),
            )
        }
    }

    @Test
    fun `unwanted hi-res changes nothing at attempt 0`() {
        assertEquals(
            sessionAttemptPlan(0, wantHlg = true, supportsRaw = true, standalone = true),
            sessionAttemptPlan(0, wantHlg = true, supportsRaw = true, standalone = true, wantHiRes = false),
        )
    }

    @Test
    fun `tele hi-res rides only the first vendor-mode attempt`() {
        val vendor0 = sessionAttemptPlan(
            attempt = 0,
            wantHlg = true,
            supportsRaw = true,
            standalone = true,
            teleconverterMode = true,
            wantHiRes = true,
        )
        assertTrue(vendor0.useVendorOperationMode)
        assertTrue(vendor0.useHiResStill)
        assertFalse(vendor0.useRaw)
        // Every later TELE rung maps onto the ordinary TELE ladder shifted by one (hi-res never
        // re-enters mid-ladder, and the first fallback is vendor-full WITH RAW).
        assertEquals(
            sessionAttemptPlan(0, true, true, true, teleconverterMode = true),
            sessionAttemptPlan(1, true, true, true, teleconverterMode = true, wantHiRes = true),
        )
        for (attempt in 1..8) {
            assertEquals(
                sessionAttemptPlan(attempt - 1, true, true, true, teleconverterMode = true),
                sessionAttemptPlan(attempt, true, true, true, teleconverterMode = true, wantHiRes = true),
            )
        }
    }

    @Test
    fun `tele degraded rungs pin the documented order`() {
        // Vendor full/degraded, THEN regular full/degraded, preview-only last (CLAUDE.md). The
        // vendor-degraded and regular rungs (1/2/4/5) were previously unpinned — a reorder would
        // have passed the suite (cycle-6 test-review F-A1).
        assertEquals(
            SessionAttemptPlan(useHlg = true, useJpeg = true, useRaw = false, useVendorOperationMode = true),
            sessionAttemptPlan(1, true, true, true, teleconverterMode = true),
        )
        assertEquals(
            SessionAttemptPlan(useHlg = false, useJpeg = true, useRaw = false, useVendorOperationMode = true),
            sessionAttemptPlan(2, true, true, true, teleconverterMode = true),
        )
        assertEquals(
            SessionAttemptPlan(useHlg = true, useJpeg = true, useRaw = false, useVendorOperationMode = false),
            sessionAttemptPlan(4, true, true, true, teleconverterMode = true),
        )
        assertEquals(
            SessionAttemptPlan(useHlg = false, useJpeg = true, useRaw = false, useVendorOperationMode = false),
            sessionAttemptPlan(5, true, true, true, teleconverterMode = true),
        )
    }

    @Test
    fun `exhaustion boundary matches the ladder length`() {
        // The bound must include the preview-only rungs — and stretch by ONE when the hi-res rung
        // is prepended, or the last resort falls off the shifted ladder.
        assertEquals(3, maxSessionAttempt(teleconverterMode = false, wantHiRes = false))
        assertEquals(7, maxSessionAttempt(teleconverterMode = true, wantHiRes = false))
        assertEquals(4, maxSessionAttempt(teleconverterMode = false, wantHiRes = true))
        assertEquals(8, maxSessionAttempt(teleconverterMode = true, wantHiRes = true))
        // The last in-bounds attempt of each ladder is its preview-only last resort.
        val plainLast = sessionAttemptPlan(4, true, true, true, wantHiRes = true)
        assertFalse(plainLast.useJpeg)
        val teleLast = sessionAttemptPlan(8, true, true, true, teleconverterMode = true, wantHiRes = true)
        assertFalse(teleLast.useJpeg)
        assertFalse(teleLast.useVendorOperationMode)
    }

    @Test
    fun `accepted hi-res truth requires the processed reader`() {
        assertTrue(
            acceptedPhotoSessionOutputs(
                processedReaderPresent = true,
                rawReaderPresent = false,
                hiResReaderPresent = true,
            ).hiRes,
        )
        // Defensive: a hi-res flag without a surviving processed reader must not claim hi-res.
        assertFalse(
            acceptedPhotoSessionOutputs(
                processedReaderPresent = false,
                rawReaderPresent = true,
                hiResReaderPresent = true,
            ).hiRes,
        )
        assertFalse(
            acceptedPhotoSessionOutputs(
                processedReaderPresent = true,
                rawReaderPresent = false,
                hiResReaderPresent = false,
            ).hiRes,
        )
    }

    @Test
    fun `accepted reader set reports actual still outputs`() {
        assertEquals(
            PhotoSessionOutputs(processed = true, raw = true),
            acceptedPhotoSessionOutputs(processedReaderPresent = true, rawReaderPresent = true),
        )
        assertEquals(
            PhotoSessionOutputs(processed = true, raw = false),
            acceptedPhotoSessionOutputs(processedReaderPresent = true, rawReaderPresent = false),
        )
        assertEquals(
            PhotoSessionOutputs(processed = false, raw = true),
            acceptedPhotoSessionOutputs(processedReaderPresent = false, rawReaderPresent = true),
        )
        assertFalse(
            acceptedPhotoSessionOutputs(
                processedReaderPresent = false,
                rawReaderPresent = false,
            ).hasStillTarget,
        )
    }
}
