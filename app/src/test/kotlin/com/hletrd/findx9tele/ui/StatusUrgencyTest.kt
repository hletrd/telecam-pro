package com.hletrd.findx9tele.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [isUrgentStatus] (TEST4-14, now internal): the keyword classifier decides whether a status
 * toast renders urgent/red AND whether accessibility announces it assertively — a wording change
 * in the emitting sites must fail here, not silently downgrade an error to polite styling.
 */
class StatusUrgencyTest {

    @Test
    fun `real failure statuses classify urgent`() {
        // Exact strings CameraViewModel/engine emit today.
        assertTrue("Photo capture failed".isUrgentStatus())
        assertTrue("DNG save failed".isUrgentStatus())
        assertTrue("Still capture unavailable".isUrgentStatus())
        // Found while writing this pin: delete failures matched no keyword and rendered polite —
        // "could not" joined the classifier with this test.
        assertTrue("Could not delete media".isUrgentStatus())
        assertTrue("Camera permission denied".isUrgentStatus())
        assertTrue("Insufficient storage".isUrgentStatus())
    }

    @Test
    fun `ordinary statuses stay quiet`() {
        assertFalse("Video saved".isUrgentStatus())
        assertFalse("Custom WB set".isUrgentStatus())
        assertFalse("MR1 loaded".isUrgentStatus())
        assertFalse("Stop REC first".isUrgentStatus())
    }

    @Test
    fun `status lifetime is long for failures short for success and neutral for guidance`() {
        assertEquals(6_000L, statusDisplayDurationMs("HEIF save failed"))
        assertEquals(1_500L, statusDisplayDurationMs("Video saved"))
        assertEquals(2_500L, statusDisplayDurationMs("Stop REC first"))
        assertEquals(null, statusDisplayDurationMs(null))
    }
}
