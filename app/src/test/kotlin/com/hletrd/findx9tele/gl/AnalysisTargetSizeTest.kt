package com.hletrd.findx9tele.gl

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalysisTargetSizeTest {
    @Test
    fun `landscape target preserves aspect with bounded long edge`() {
        assertEquals(AnalysisTargetSize(256, 144), analysisTargetSize(1920, 1080))
    }

    @Test
    fun `portrait target preserves orientation and aspect`() {
        assertEquals(AnalysisTargetSize(144, 256), analysisTargetSize(1080, 1920))
    }

    @Test
    fun `square target uses square buffer`() {
        assertEquals(AnalysisTargetSize(256, 256), analysisTargetSize(1000, 1000))
    }

    @Test
    fun `invalid dimensions degrade to one pixel`() {
        assertEquals(AnalysisTargetSize(1, 1), analysisTargetSize(0, 1920))
    }

    @Test
    fun `analysis framing stays on capture crop and frame center`() {
        assertEquals(AnalysisFrame(crop = 0.12f, centerX = 0.5f, centerY = 0.5f), analysisFrame(0.12f))
    }

    @Test
    fun `analysis readback always meters display-referred, log previews included`() {
        // AGG4-9/P3.4: the scopes/AE meter must not move when the user toggles a log profile —
        // metering the flat preview curve put 18% grey at the curve's grey anchor instead of 0.18
        // and settled the app-side AE ~1.5 stops off (found on the since-removed O-Log2 option).
        for (transfer in com.hletrd.findx9tele.camera.ColorTransfer.entries) {
            assertEquals(null, analysisReadbackTransfer(transfer))
        }
        assertEquals(null, analysisReadbackTransfer(null))
    }
}
