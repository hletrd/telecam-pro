package com.hletrd.findx9tele.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewModeIntentGateTest {
    @Test
    fun supersededToggleCannotApplyAndLatestIntentRetainsOwnership() {
        val photo = PreviewModeIntent(pinAutoFps = false, opticsGeneration = 7)
        val video = PreviewModeIntent(pinAutoFps = true, opticsGeneration = 8)
        val returnedPhoto = PreviewModeIntent(pinAutoFps = false, opticsGeneration = 9)
        val gate = PreviewModeIntentGate(photo)

        assertTrue(gate.request(video))
        assertTrue(gate.request(returnedPhoto))
        assertFalse(gate.isCurrent(video))
        assertTrue(gate.isCurrent(returnedPhoto))

        // An equal duplicate must not replace the identity of the already queued owner.
        assertFalse(gate.request(returnedPhoto.copy()))
        assertTrue(gate.isCurrent(returnedPhoto))
    }

    @Test
    fun resetRearmsTheGateWithAFreshBaselineOwner() {
        val baseline = PreviewModeIntent(pinAutoFps = false, opticsGeneration = 3)
        val video = PreviewModeIntent(pinAutoFps = true, opticsGeneration = 4)
        val gate = PreviewModeIntentGate(PreviewModeIntent(pinAutoFps = false, opticsGeneration = 2))

        assertTrue(gate.request(video))
        gate.reset(baseline)

        // Reset installs the exact baseline instance and revokes the queued intent's ownership,
        // so the same (equal) toggle becomes requestable again afterwards.
        assertTrue(gate.isCurrent(baseline))
        assertFalse(gate.isCurrent(video))
        assertTrue(gate.request(video))
    }
}
