package com.hletrd.findx9tele.ui

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
        assertTrue("Capture failed: timeout".isUrgentStatus())
        assertTrue("Failed to save DNG: no RAW".isUrgentStatus())
        assertTrue("Still capture unavailable in current session".isUrgentStatus())
        // Found while writing this pin: delete failures matched no keyword and rendered polite —
        // "could not" joined the classifier with this test.
        assertTrue("Could not delete media".isUrgentStatus())
        assertTrue("Camera permission denied".isUrgentStatus())
        assertTrue("Insufficient storage".isUrgentStatus())
    }

    @Test
    fun `ordinary statuses stay quiet`() {
        assertFalse("Saved".isUrgentStatus())
        assertFalse("DNG saved".isUrgentStatus())
        assertFalse("MR1 loaded".isUrgentStatus())
        assertFalse("Stop REC first".isUrgentStatus())
    }
}
