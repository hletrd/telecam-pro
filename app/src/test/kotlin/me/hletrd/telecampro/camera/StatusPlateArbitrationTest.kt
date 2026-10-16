package me.hletrd.telecampro.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AGG4-65 / AGG5-11: the status plate arbitrates by rank and by who asked, not last-writer-wins. */
class StatusPlateArbitrationTest {
    private val retained = CameraStatusMessage.VIDEO_SAVE_DELAYED.status()
    private val loaded = CameraStatusMessage.MEMORY_SLOT_LOADED.status(CameraStatusArgument.Text("MR1"))
    private val dngFailed = CameraStatusMessage.DNG_SAVE_FAILED.status()
    private val dngKept = CameraStatusMessage.DNG_SAVE_DELAYED.status()
    private val refusal = CameraStatusMessage.STOP_RECORDING_FIRST.status()
    private val finishing = CameraStatusMessage.FINISHING_PREVIOUS_PHOTO.status()
    // The ordinary optics condition (CAMERA_RECONFIGURING is a timed refusal since AGG6-10).
    private val starting = CameraStatusMessage.STARTING_CAMERA.status()
    private val saved = CameraStatusMessage.VIDEO_SAVED.status()
    private val withoutAudio = CameraStatusMessage.RECORDING_WITHOUT_AUDIO.status()
    private val dngOnly = CameraStatusMessage.PROCESSED_STILL_UNAVAILABLE_DNG_ONLY.status()
    private val previewRetrying = CameraStatusMessage.PREVIEW_UNAVAILABLE_RETRYING.status()
    private val errorRecovering = CameraStatusMessage.CAMERA_ERROR_RECOVERING.status()

    /** A plate whose [shown] was published at [atMs] (so it expires at atMs + its duration). */
    private fun shownAt(shown: CameraStatus, atMs: Long = 0L, deferredProgress: CameraStatus? = null) =
        StatusPlate(null).publish(shown, atMs).plate.copy(deferredProgress = deferredProgress)

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
            listOf(starting, saved, finishing, refusal, retained, dngFailed).map { it.plateRank },
        )
    }

    // AGG5-11 / CT5-7: an ASSERTIVE camera-error condition is not held back behind chatter.
    @Test fun `an ERROR-severity condition ranks at ERROR and takes the plate over a lower event`() {
        assertEquals(StatusPlateRank.ERROR, errorRecovering.plateRank)
        assertEquals(StatusPlateRank.ERROR, CameraStatusMessage.CAMERA_UNAVAILABLE_RETRYING.status().plateRank)
        val over = shownAt(loaded).publish(errorRecovering, 100L)
        assertTrue(over.shownChanged)
        assertEquals(errorRecovering, over.plate.shown)
        assertNull("still no timer", over.plate.shownExpiresAtMs)
        // An ambient lower event cannot displace it, and Ready still clears it.
        val held = over.plate.publish(CameraStatusMessage.FILE_ALREADY_REMOVED.status().copy(), 200L)
        assertTrue("FILE_ALREADY_REMOVED is a response", held.shownChanged)
        val ambient = over.plate.publish(dngOnly, 200L)
        assertFalse(ambient.shownChanged)
        assertEquals(errorRecovering, ambient.plate.shown)
        assertNull(over.plate.clearProgress().shown)
        // A non-error condition still yields to every event.
        assertEquals(StatusPlateRank.PROGRESS, starting.plateRank)
    }

    @Test fun `a lower ambient event does not replace an unexpired retained-take instruction`() {
        val result = shownAt(retained).publish(dngOnly, 10L)
        assertFalse(result.shownChanged)
        assertEquals(retained, result.plate.shown)
    }

    @Test fun `a later retained DNG does not hide an earlier DNG failure`() {
        val result = shownAt(dngFailed).publish(dngKept, 10L)
        assertFalse(result.shownChanged)
        assertEquals(dngFailed, result.plate.shown)
    }

    @Test fun `equal or higher rank wins`() {
        assertEquals(dngFailed, shownAt(retained).publish(dngFailed, 10L).plate.shown)
        val sameRank = CameraStatusMessage.RAW_UNAVAILABLE.status()
        val result = shownAt(dngOnly).publish(sameRank, 10L)
        assertTrue(result.shownChanged)
        assertEquals(sameRank, result.plate.shown)
        // A repeat of the same event re-arms its timer.
        val repeat = shownAt(saved).publish(saved, 500L)
        assertTrue(repeat.shownChanged)
        assertEquals(500L + saved.durationMs!!, repeat.plate.shownExpiresAtMs)
    }

    // AGG5-11 / UX5-2: a response to the operator's own input always takes the plate; the higher
    // event it covers returns for the time it had left.
    @Test fun `a response covers a retained-take line which returns for its remaining time`() {
        val retainedAt0 = shownAt(retained, atMs = 0L)
        val empty = CameraStatusMessage.MEMORY_SLOT_EMPTY.status(CameraStatusArgument.Text("MR2"))
        val covered = retainedAt0.publish(empty, 2_000L)
        assertTrue(covered.shownChanged)
        assertEquals(empty, covered.plate.shown)
        assertEquals(DeferredStatusEvent(retained, retained.durationMs!! - 2_000L), covered.plate.deferredEvent)
        assertEquals(2_000L + empty.durationMs!!, covered.plate.shownExpiresAtMs)
        // The response expires: the instruction returns for exactly the 4 s it had left.
        val back = covered.plate.expire(empty, 4_500L)
        assertEquals(retained, back.shown)
        assertNull(back.deferredEvent)
        assertEquals(4_500L + 4_000L, back.shownExpiresAtMs)
        // A second response replaces the first and keeps the SAME covered event.
        val again = covered.plate.publish(refusal, 2_100L)
        assertEquals(refusal, again.plate.shown)
        assertEquals(covered.plate.deferredEvent, again.plate.deferredEvent)
        // An ambient event below the covered one cannot sneak in under the response.
        assertFalse(covered.plate.publish(dngOnly, 2_100L).shownChanged)
        // An ambient event at or above it replaces both.
        val error = covered.plate.publish(dngFailed, 2_100L)
        assertEquals(dngFailed, error.plate.shown)
        assertNull(error.plate.deferredEvent)
    }

    @Test fun `a response over an error defers the error, and an expired error is not resurrected`() {
        val deleted = CameraStatusMessage.DELETED.status()
        val cancel = CameraStatusMessage.DELETE_CANCELED.status()
        val covered = shownAt(dngFailed, atMs = 0L).publish(cancel, 1_000L)
        assertEquals(cancel, covered.plate.shown)
        assertEquals(dngFailed, covered.plate.deferredEvent?.status)
        // Nothing left on the error's clock: the response does not keep it.
        assertNull(shownAt(dngFailed, atMs = 0L).publish(cancel, 6_000L).plate.deferredEvent)
        // A response at or above the shown event simply replaces it.
        assertNull(shownAt(loaded).publish(deleted, 10L).plate.deferredEvent)
    }

    // AGG5-11 / RG5-6: a status that RESOLVES the shown or covered event wipes it instead.
    @Test fun `a resolution wipes the stale event it settles`() {
        val couldNot = CameraStatusMessage.COULD_NOT_DELETE_FILE.status()
        val deleted = CameraStatusMessage.DELETED.status()
        val retried = shownAt(couldNot).publish(deleted, 500L)
        assertEquals(deleted, retried.plate.shown)
        assertNull("the failure must not return after the retry succeeded", retried.plate.deferredEvent)
        assertNull(retried.plate.expire(deleted, 2_000L).shown)
        // "Recording without audio" still up when a short take stops: "Video saved" is shown.
        val shortTake = shownAt(withoutAudio).publish(saved, 1_000L)
        assertEquals(saved, shortTake.plate.shown)
        assertNull(shortTake.plate.deferredEvent)
        val finishingClip = shownAt(CameraStatusMessage.FINISHING_PREVIOUS_CLIP.status()).publish(saved, 100L)
        assertEquals(saved, finishingClip.plate.shown)
        assertNull(finishingClip.plate.deferredEvent)
    }

    @Test fun `progress waits behind a higher non-terminal event and returns when it expires`() {
        val behind = shownAt(retained).publish(starting, 10L)
        assertFalse(behind.shownChanged)
        assertEquals(retained, behind.plate.shown)
        assertEquals(starting, behind.plate.deferredProgress)
        assertEquals(starting, behind.plate.condition)
        assertEquals(StatusPlate(starting), behind.plate.expire(retained, 6_000L))
        // A stale timer for something no longer shown changes nothing.
        assertEquals(behind.plate, behind.plate.expire(loaded, 6_000L))
        // The condition ending first drops it without touching the event.
        assertEquals(retained, behind.plate.clearProgress().shown)
        assertNull(behind.plate.clearProgress().deferredProgress)
    }

    @Test fun `an event replacing a condition defers it, and conditions replace each other`() {
        val event = StatusPlate(starting).publish(saved, 0L)
        assertTrue(event.shownChanged)
        assertEquals(saved, event.plate.shown)
        assertEquals(starting, event.plate.deferredProgress)
        val retrying = CameraStatusMessage.CAMERA_UNAVAILABLE_RETRYING.status()
        assertEquals(StatusPlate(retrying), StatusPlate(starting).publish(retrying, 0L).plate)
        assertNull(StatusPlate(starting).clearProgress().shown)
    }

    // MRG4-1: an event that ENDS the condition must not resurrect it on expiry.
    @Test fun `an exhausted retry ends the retrying condition instead of deferring it`() {
        val retrying = CameraStatusMessage.CAMERA_UNAVAILABLE_RETRYING.status()
        val reopen = CameraStatusMessage.CAMERA_UNAVAILABLE_REOPEN.status()
        val shown = StatusPlate(retrying).publish(reopen, 0L)
        assertTrue(shown.shownChanged)
        assertEquals(reopen, shown.plate.shown)
        assertNull(shown.plate.deferredProgress)
        assertNull(shown.plate.expire(reopen, 6_000L).shown)
        val preview = StatusPlate(previewRetrying)
            .publish(CameraStatusMessage.PREVIEW_UNAVAILABLE_REOPEN.status(), 0L)
        assertNull(preview.plate.deferredProgress)
    }

    @Test fun `a not-ready rollback ends the optics condition instead of deferring it`() {
        val unchanged = CameraStatusMessage.CAMERA_UNAVAILABLE_MODE_UNCHANGED.status()
        val plate = StatusPlate(starting).publish(unchanged, 0L).plate
        assertEquals(unchanged, plate.shown)
        assertNull(plate.deferredProgress)
        assertNull(plate.expire(unchanged, 6_000L).shown)
        // A WARNING-severity rollback ends it too, even while it is deferred behind a higher event.
        val stopFirst = CameraStatusMessage.STOP_RECORDING_LENS_UNCHANGED.status()
        val behindError = shownAt(dngFailed, deferredProgress = starting).publish(stopFirst, 10L)
        assertEquals("a refusal is a response: it takes the plate", stopFirst, behindError.plate.shown)
        assertNull(behindError.plate.deferredProgress)
        assertEquals(dngFailed, behindError.plate.deferredEvent?.status)
        assertNull(behindError.plate.expire(stopFirst, 2_510L).deferredProgress)
    }

    // AGG5-11 / RG5-7: a rollback ends the OPTICS condition it belongs to, not a health recovery.
    @Test fun `a rollback does not end a preview or camera-health recovery running beside it`() {
        val lens = CameraStatusMessage.LENS_UNAVAILABLE_UNCHANGED.status()
        val preview = StatusPlate(previewRetrying).publish(lens, 0L).plate
        assertEquals(lens, preview.shown)
        assertEquals(previewRetrying, preview.deferredProgress)
        assertEquals(previewRetrying, preview.expire(lens, 6_000L).shown)
        val health = shownAt(saved, deferredProgress = errorRecovering)
            .publish(CameraStatusMessage.STOP_RECORDING_MODE_UNCHANGED.status(), 10L)
        assertEquals(errorRecovering, health.plate.deferredProgress)
        // The camera-wide terminal still ends every condition.
        val terminal = StatusPlate(previewRetrying).publish(CameraStatusMessage.CAMERA_UNAVAILABLE_REOPEN.status(), 0L)
        assertNull(terminal.plate.deferredProgress)
        // The optics family the rollback DOES end.
        OPTICS_CONDITION_MESSAGES.forEach { condition ->
            assertTrue(condition.name, lens.endsCondition(condition.status()))
        }
        assertFalse(lens.endsCondition(previewRetrying))
        assertFalse(lens.endsCondition(errorRecovering))
        assertFalse(lens.endsCondition(CameraStatusMessage.PREVIEW_INTERRUPTED_RECOVERING.status()))
    }

    @Test fun `an ordinary success over the optics condition still lets it return`() {
        val plate = StatusPlate(starting).publish(loaded, 0L).plate
        assertEquals(loaded, plate.shown)
        assertEquals(starting, plate.deferredProgress)
        assertEquals(StatusPlate(starting), plate.expire(loaded, 1_500L))
    }

    @Test fun `every condition-ending and response message is an event`() {
        (CAMERA_CONDITION_ENDING_MESSAGES + RESPONSE_MESSAGES).forEach {
            assertEquals(it.name, CameraStatusLifecycle.EVENT, it.status().lifecycle)
        }
        // Responses never claim the retained-take or error ranks they are allowed to cover.
        RESPONSE_MESSAGES.forEach {
            assertTrue(it.name, it.status().plateRank < StatusPlateRank.RETAINED_TAKE)
        }
    }

    // AGG6-10: the not-Ready refusal is a timed response. As a timer-less condition, a press after
    // the terminal "reopen the app" left "Camera reconfiguring…" on the plate (and in the Output
    // row's condition) for good once the terminal expired.
    @Test fun `the not-ready refusal is a timed response that never outlives the terminal`() {
        val refusal = CameraStatusMessage.CAMERA_RECONFIGURING.status()
        assertEquals(CameraStatusLifecycle.EVENT, refusal.lifecycle)
        assertEquals(2_500L, refusal.durationMs)
        val terminal = CameraStatusMessage.CAMERA_UNAVAILABLE_REOPEN.status()
        val pressed = shownAt(terminal, atMs = 0L).publish(refusal, 1_000L)
        assertTrue("the press is answered", pressed.shownChanged)
        assertEquals(refusal, pressed.plate.shown)
        assertNull(pressed.plate.condition)
        val back = pressed.plate.expire(refusal, 3_500L)
        assertEquals("the terminal returns for its remaining time", terminal, back.shown)
        val after = back.expire(terminal, 8_500L)
        assertNull(after.shown)
        assertNull(after.condition)
    }

    // AGG6-7 / UX6-1: the microphone answers to a REC press are responses; a REC refused with
    // "Microphone busy" right after a take that did not finish cleanly used to be dropped under that
    // take's retained-take line, so the press looked inert.
    @Test fun `microphone answers take the plate over a retained-take line which returns`() {
        listOf(
            CameraStatusMessage.MICROPHONE_BUSY,
            CameraStatusMessage.RECORDING_WITHOUT_AUDIO,
            CameraStatusMessage.MICROPHONE_DENIED_RECORDING_WITHOUT_AUDIO,
            CameraStatusMessage.MICROPHONE_DENIED_AUDIO_OFF,
            CameraStatusMessage.MICROPHONE_ALLOWED_AUDIO_ON,
            CameraStatusMessage.AUDIO_INPUT_USING_DEFAULT,
        ).forEach { message ->
            val answer = message.status()
            val covered = shownAt(retained, atMs = 0L).publish(answer, 1_000L)
            assertTrue(message.name, covered.shownChanged)
            assertEquals(message.name, answer, covered.plate.shown)
            assertEquals(message.name, retained, covered.plate.deferredEvent?.status)
            assertEquals(message.name, retained, covered.plate.expire(answer, 3_500L).shown)
        }
    }

    @Test fun `an empty plate admits anything and null clears everything`() {
        assertEquals(loaded, StatusPlate(null).publish(loaded, 0L).plate.shown)
        val cleared = shownAt(retained, deferredProgress = starting).publish(null, 10L)
        assertTrue(cleared.shownChanged)
        assertEquals(StatusPlate(null), cleared.plate)
    }
}
