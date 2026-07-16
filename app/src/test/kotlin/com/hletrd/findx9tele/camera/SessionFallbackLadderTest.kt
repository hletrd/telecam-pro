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
