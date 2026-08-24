package me.hletrd.telecampro.ui

import me.hletrd.telecampro.camera.CameraStatus
import me.hletrd.telecampro.camera.CameraStatusLifecycle
import me.hletrd.telecampro.camera.CameraStatusLivePriority
import me.hletrd.telecampro.camera.CameraStatusMessage
import me.hletrd.telecampro.camera.CameraStatusSeverity
import me.hletrd.telecampro.camera.status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins typed status metadata. Translated copy is deliberately absent from these decisions.
 */
class StatusUrgencyTest {

    @Test
    fun `status constructor defaults to no presentation arguments`() {
        val status = CameraStatus(
            message = CameraStatusMessage.STARTING_CAMERA,
            severity = CameraStatusSeverity.INFO,
            livePriority = CameraStatusLivePriority.POLITE,
            lifecycle = CameraStatusLifecycle.PROGRESS,
            durationMs = null,
        )

        assertTrue(status.arguments.isEmpty())
    }

    @Test
    fun `real failure identities stay assertive and long lived`() {
        listOf(
            CameraStatusMessage.PHOTO_CAPTURE_FAILED,
            CameraStatusMessage.DNG_SAVE_FAILED,
            CameraStatusMessage.STILL_CAPTURE_UNAVAILABLE,
            CameraStatusMessage.COULD_NOT_DELETE_FILE,
            CameraStatusMessage.SOME_FILES_NOT_DELETED_RETRY_GALLERY,
        ).forEach {
            val status = it.status()
            assertEquals(CameraStatusSeverity.ERROR, status.severity)
            assertEquals(CameraStatusLivePriority.ASSERTIVE, status.livePriority)
            assertEquals(6_000L, status.durationMs)
        }
    }

    @Test
    fun `success and guidance identities preserve their durations`() {
        assertEquals(1_500L, CameraStatusMessage.VIDEO_SAVED.status().durationMs)
        assertEquals(1_500L, CameraStatusMessage.CUSTOM_WB_SET.status().durationMs)
        assertEquals(1_500L, CameraStatusMessage.MEMORY_SLOT_LOADED.status().durationMs)
        assertEquals(2_500L, CameraStatusMessage.STOP_RECORDING_FIRST.status().durationMs)
    }

    @Test
    fun `complete outputs awaiting publication are polite recoverable warnings`() {
        listOf(
            CameraStatusMessage.DNG_SAVE_DELAYED,
            CameraStatusMessage.OUTPUT_SAVED_PENDING,
            CameraStatusMessage.VIDEO_SAVE_DELAYED,
        ).forEach {
            val status = it.status()
            assertEquals(CameraStatusSeverity.WARNING, status.severity)
            assertEquals(CameraStatusLivePriority.POLITE, status.livePriority)
            assertEquals(CameraStatusLifecycle.EVENT, status.lifecycle)
            assertEquals(2_500L, status.durationMs)
        }
        assertEquals(
            CameraStatusSeverity.ERROR,
            CameraStatusMessage.OUTPUT_SAVED_PENDING_RECOVERY.status().severity,
        )
    }

    @Test
    fun `the cold-start progress status is cleared by an event, never by a timer`() {
        val starting = CameraStatusMessage.STARTING_CAMERA.status()
        assertEquals(null, starting.durationMs)
        assertEquals(CameraStatusLifecycle.PROGRESS, starting.lifecycle)
        assertEquals(CameraStatusLivePriority.POLITE, starting.livePriority)
        assertEquals(CameraStatusLifecycle.EVENT, CameraStatusMessage.CAMERA_RECONFIGURING.status().lifecycle)
        assertEquals(2_500L, CameraStatusMessage.CAMERA_RECONFIGURING.status().durationMs)
        assertTrue(CameraStatusMessage.entries
            .filterNot { it == CameraStatusMessage.STARTING_CAMERA }
            .all { it.status().lifecycle == CameraStatusLifecycle.EVENT })
    }
}
