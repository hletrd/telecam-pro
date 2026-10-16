package me.hletrd.telecampro.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** A complete DNG kept for launch recovery is reported as retained, never as a failed save (AGG-31). */
class RetainedDngStatusTest {
    @Test
    fun `marker failure reports saved pending recovery`() {
        val status = retainedDngStatus(completionMarkerDurable = false)
        assertEquals(CameraStatusMessage.OUTPUT_SAVED_PENDING_RECOVERY, status.message)
        assertEquals(listOf(CameraStatusArgument.Text("DNG")), status.arguments)
        assertNotEquals(CameraStatusMessage.DNG_SAVE_FAILED.status(), status)
    }

    @Test
    fun `durable marker reports only a delayed publication`() {
        assertEquals(CameraStatusMessage.DNG_SAVE_DELAYED.status(), retainedDngStatus(completionMarkerDurable = true))
    }
}
