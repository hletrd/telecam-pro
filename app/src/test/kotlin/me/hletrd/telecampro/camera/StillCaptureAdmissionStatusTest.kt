package me.hletrd.telecampro.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StillCaptureAdmissionStatusTest {
    @Test
    fun `available output capacity has no failure status`() {
        assertNull(stillCaptureAdmissionFailureStatus(admissionAvailable = true))
    }

    @Test
    fun `capacity refusal reports unavailable capture rather than deletion failure`() {
        assertEquals(
            CameraStatusMessage.STILL_CAPTURE_UNAVAILABLE,
            stillCaptureAdmissionFailureStatus(admissionAvailable = false),
        )
    }
}
