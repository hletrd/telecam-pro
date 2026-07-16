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
}
