package me.hletrd.telecampro.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AGG4-65: the status plate arbitrates by rank instead of last-writer-wins. */
class StatusPlateArbitrationTest {
    private val retained = CameraStatusMessage.VIDEO_SAVE_DELAYED.status()
    private val loaded = CameraStatusMessage.MEMORY_SLOT_LOADED.status(CameraStatusArgument.Text("MR1"))
    private val dngFailed = CameraStatusMessage.DNG_SAVE_FAILED.status()
    private val dngKept = CameraStatusMessage.DNG_SAVE_DELAYED.status()
    private val refusal = CameraStatusMessage.STOP_RECORDING_FIRST.status()
    private val finishing = CameraStatusMessage.FINISHING_PREVIOUS_PHOTO.status()
    private val reconfiguring = CameraStatusMessage.CAMERA_RECONFIGURING.status()
    private val saved = CameraStatusMessage.VIDEO_SAVED.status()

    @Test fun `ranks follow PROGRESS SUCCESS INFO WARNING retained ERROR`() {
        assertEquals(
            listOf(
                StatusPlateRank.PROGRESS,
                StatusPlateRank.SUCCESS,
                StatusPlateRank.INFO,
                StatusPlateRank.WARNING,
                StatusPlateRank.RETAINED_TAKE,
                StatusPlateRank.ERROR,
            ),
            listOf(reconfiguring, saved, finishing, refusal, retained, dngFailed).map { it.plateRank },
        )
        // An ERROR-severity condition is still a condition: it yields to events and comes back.
        assertEquals(StatusPlateRank.PROGRESS, CameraStatusMessage.CAMERA_ERROR_RECOVERING.status().plateRank)
    }

    @Test fun `a lower event does not replace an unexpired retained-take instruction`() {
        val result = StatusPlate(retained).publish(loaded)
        assertFalse(result.shownChanged)
        assertEquals(retained, result.plate.shown)
    }

    @Test fun `a later retained DNG does not hide an earlier DNG failure`() {
        val result = StatusPlate(dngFailed).publish(dngKept)
        assertFalse(result.shownChanged)
        assertEquals(dngFailed, result.plate.shown)
    }

    @Test fun `equal or higher rank wins`() {
        assertEquals(dngFailed, StatusPlate(retained).publish(dngFailed).plate.shown)
        val sameRank = CameraStatusMessage.SWITCH_TO_REAR_FIRST.status()
        val result = StatusPlate(refusal).publish(sameRank)
        assertTrue(result.shownChanged)
        assertEquals(sameRank, result.plate.shown)
        // A repeat of the same event re-arms its timer.
        assertTrue(StatusPlate(saved).publish(saved).shownChanged)
    }

    @Test fun `progress waits behind a higher event and returns when it expires`() {
        val behind = StatusPlate(retained).publish(reconfiguring)
        assertFalse(behind.shownChanged)
        assertEquals(retained, behind.plate.shown)
        assertEquals(reconfiguring, behind.plate.deferredProgress)
        assertEquals(StatusPlate(reconfiguring), behind.plate.expire(retained))
        // A stale timer for something no longer shown changes nothing.
        assertEquals(behind.plate, behind.plate.expire(loaded))
        // The condition ending first drops it without touching the event.
        assertEquals(StatusPlate(retained), behind.plate.clearProgress())
    }

    @Test fun `an event replacing a condition defers it, and conditions replace each other`() {
        val event = StatusPlate(reconfiguring).publish(saved)
        assertTrue(event.shownChanged)
        assertEquals(StatusPlate(saved, reconfiguring), event.plate)
        val starting = CameraStatusMessage.STARTING_CAMERA.status()
        assertEquals(StatusPlate(starting), StatusPlate(reconfiguring).publish(starting).plate)
        assertNull(StatusPlate(reconfiguring).clearProgress().shown)
    }

    @Test fun `an empty plate admits anything and null clears everything`() {
        assertEquals(StatusPlate(loaded), StatusPlate(null).publish(loaded).plate)
        val cleared = StatusPlate(retained, reconfiguring).publish(null)
        assertTrue(cleared.shownChanged)
        assertEquals(StatusPlate(null), cleared.plate)
    }
}
