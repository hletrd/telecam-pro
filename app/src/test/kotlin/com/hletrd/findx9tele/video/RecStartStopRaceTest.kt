package com.hletrd.findx9tele.video

import com.hletrd.findx9tele.camera.RecordingAdmissionLatch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The carried P4.7/TEST4-5 regression net: a REC start immediately followed by a stop on the same
 * executor tick must DELETE the half-created recording, never publish it. Drives the real
 * [RecordingAdmissionLatch] ordering plus the pure [shouldPublishRecording] save gate the recorder
 * evaluates when the latched stop lands.
 */
class RecStartStopRaceTest {

    @Test
    fun `stop during admission is latched and runs exactly once after publication`() {
        val latch = RecordingAdmissionLatch()
        assertTrue(latch.tryBeginAdmission())
        // The user's stop lands while admission is still in flight: it must latch, not race.
        assertTrue(latch.requestStop())
        // Admission publishes → the latched stop must run NOW, exactly once.
        assertTrue(latch.completeAdmission(succeeded = true))
        // The latch was consumed: a fresh admission does not inherit a stale stop.
        assertTrue(latch.tryBeginAdmission())
        assertFalse(latch.completeAdmission(succeeded = true))
    }

    @Test
    fun `immediately-stopped recorder deletes, never publishes`() {
        // At the moment the latched stop executes, the recorder has muxed no video sample (the
        // muxer may not even have started). Every such combination must fail the save gate so the
        // pending MediaStore row is deleted instead of a zero-frame clip reaching the gallery.
        assertFalse(
            shouldPublishRecording(muxerStarted = false, wroteVideoSample = false, hasFailure = false, hasUri = true),
        )
        assertFalse(
            shouldPublishRecording(muxerStarted = true, wroteVideoSample = false, hasFailure = false, hasUri = true),
        )
        // Sanity: a genuinely complete recording still publishes.
        assertTrue(
            shouldPublishRecording(muxerStarted = true, wroteVideoSample = true, hasFailure = false, hasUri = true),
        )
        // A video-side failure or a lost uri always deletes.
        assertFalse(
            shouldPublishRecording(muxerStarted = true, wroteVideoSample = true, hasFailure = true, hasUri = true),
        )
        assertFalse(
            shouldPublishRecording(muxerStarted = true, wroteVideoSample = true, hasFailure = false, hasUri = false),
        )
    }

    @Test
    fun `failed admission consumes the latch without running the stop`() {
        val latch = RecordingAdmissionLatch()
        assertTrue(latch.tryBeginAdmission())
        assertTrue(latch.requestStop())
        // A failed admission has nothing to stop; the latch is still consumed exactly-once.
        assertFalse(latch.completeAdmission(succeeded = false))
    }
}
