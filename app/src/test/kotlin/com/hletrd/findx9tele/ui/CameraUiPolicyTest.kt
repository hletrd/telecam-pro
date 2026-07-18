package com.hletrd.findx9tele.ui

import com.hletrd.findx9tele.camera.LensChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraUiPolicyTest {
    @Test
    fun `focal rail exposes selection converter reconfiguration and REC truth`() {
        val selected = focalRailState(LensChoice.TELE3X, LensChoice.TELE3X, true, true, false)
        assertTrue(selected.active)
        assertTrue(selected.enabled)
        assertEquals("Selected; teleconverter on", selected.stateDescription)

        val reconfiguring = focalRailState(LensChoice.MAIN, LensChoice.MAIN, false, false, false)
        assertFalse(reconfiguring.enabled)
        assertEquals("Camera reconfiguring", reconfiguring.stateDescription)

        val recording = focalRailState(LensChoice.MAIN, LensChoice.MAIN, false, true, true)
        assertFalse(recording.enabled)
        assertEquals("Unavailable while recording", recording.stateDescription)
    }
}
