package com.hletrd.findx9tele.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [shouldStartMuxer], the pure MediaMuxer-start gate extracted from
 * VideoRecorder.maybeStartMuxer (AGG3-53/TEST-3 — fa80574 shipped the video-only degrade with zero
 * coverage; `expectedTracks` is mutated at six sites with no regression net anywhere in this file).
 *
 * The gate decides when the one shared MediaMuxer may start: exactly once, only after the video
 * track exists, and — unless the audio expectation was dropped to video-only — only after the audio
 * track exists too. The `expectedTracks == 1` short-circuit is the DATA-LOSS fix behind both the
 * AAC-wedge bail (fa80574) and the mid-REC audio degrade (AGG3-2): a recording whose audio died must
 * start the muxer on the video track alone instead of waiting forever for a dead AAC encoder and
 * discarding a cleanly-muxed video clip.
 */
class VideoRecorderMuxerStartTest {

    @Test
    fun `video track plus single expected track starts regardless of audio (AGG2-6 wedge and mid-REC degrade)`() {
        // The literal AGG2-6 case AND the new AGG3-2 mid-REC audio degrade share this predicate:
        // expectedTracks dropped to 1 (a wedged AAC encoder OR a mid-REC audio fault degrading to
        // video-only). The muxer must start on the video track alone whether or not an audio track
        // was ever emitted, so a good video clip is never discarded over a dead audio encoder.
        assertTrue(
            shouldStartMuxer(
                muxerStarted = false,
                videoTrackReady = true,
                expectedTracks = 1,
                audioTrackReady = false,
            ),
        )
        assertTrue(
            shouldStartMuxer(
                muxerStarted = false,
                videoTrackReady = true,
                expectedTracks = 1,
                audioTrackReady = true,
            ),
        )
    }

    @Test
    fun `normal two-track recording waits for audio then starts`() {
        // Video ready but the audio track not yet emitted: hold the muxer, or the audio samples that
        // arrive before addTrack(audio) would be lost.
        assertFalse(
            shouldStartMuxer(
                muxerStarted = false,
                videoTrackReady = true,
                expectedTracks = 2,
                audioTrackReady = false,
            ),
        )
        // Both tracks added: start.
        assertTrue(
            shouldStartMuxer(
                muxerStarted = false,
                videoTrackReady = true,
                expectedTracks = 2,
                audioTrackReady = true,
            ),
        )
    }

    @Test
    fun `no video track never starts the muxer`() {
        // Video-first invariant: MediaMuxer.start() with no video track produces an unplayable file,
        // and stop()'s save gate additionally requires a muxed video sample.
        assertFalse(
            shouldStartMuxer(
                muxerStarted = false,
                videoTrackReady = false,
                expectedTracks = 1,
                audioTrackReady = false,
            ),
        )
        assertFalse(
            shouldStartMuxer(
                muxerStarted = false,
                videoTrackReady = false,
                expectedTracks = 1,
                audioTrackReady = true,
            ),
        )
        assertFalse(
            shouldStartMuxer(
                muxerStarted = false,
                videoTrackReady = false,
                expectedTracks = 2,
                audioTrackReady = true,
            ),
        )
    }

    @Test
    fun `muxer stop failure over sample-less degraded audio track is tolerated (TR4-2)`() {
        // The one non-terminal combination: video complete, audio degraded mid-REC, and the audio
        // track never muxed a sample — MediaMuxer.stop() throwing over the empty track must not
        // delete the clean video clip.
        assertFalse(
            muxerStopFailureIsTerminal(
                wroteVideoSample = true,
                audioDegradedMidRec = true,
                wroteAudioSample = false,
            ),
        )
    }

    @Test
    fun `every other muxer stop failure stays terminal (TR4-2)`() {
        // No video sample: nothing worth saving regardless of the audio story.
        assertTrue(
            muxerStopFailureIsTerminal(
                wroteVideoSample = false,
                audioDegradedMidRec = true,
                wroteAudioSample = false,
            ),
        )
        // No mid-REC degrade: a stop() throw is a genuine container-finalization failure.
        assertTrue(
            muxerStopFailureIsTerminal(
                wroteVideoSample = true,
                audioDegradedMidRec = false,
                wroteAudioSample = false,
            ),
        )
        // Audio samples were actually muxed: the empty-track excuse does not apply.
        assertTrue(
            muxerStopFailureIsTerminal(
                wroteVideoSample = true,
                audioDegradedMidRec = true,
                wroteAudioSample = true,
            ),
        )
        assertTrue(
            muxerStopFailureIsTerminal(
                wroteVideoSample = false,
                audioDegradedMidRec = false,
                wroteAudioSample = false,
            ),
        )
    }

    @Test
    fun `tolerated muxer stop output publishes only after completed validation`() {
        val base = { validation: FinalizedRecordingValidation ->
            shouldPublishRecording(
                muxerStarted = true,
                wroteVideoSample = true,
                hasFailure = false,
                hasUri = true,
                finalizedValidation = validation,
            )
        }
        assertTrue(base(FinalizedRecordingValidation.NOT_REQUIRED))
        assertTrue(base(FinalizedRecordingValidation.PASSED))
        assertFalse(base(FinalizedRecordingValidation.FAILED))
        assertFalse(base(FinalizedRecordingValidation.SKIPPED))
    }

    @Test
    fun `muxer stop terminal policy covers all eight boolean combinations`() {
        for (video in booleanArrayOf(false, true)) {
            for (degraded in booleanArrayOf(false, true)) {
                for (audio in booleanArrayOf(false, true)) {
                    val expectedTerminal = !(video && degraded && !audio)
                    assertEquals(
                        "video=$video degraded=$degraded audio=$audio",
                        expectedTerminal,
                        muxerStopFailureIsTerminal(video, degraded, audio),
                    )
                }
            }
        }
    }

    @Test
    fun `already-started muxer never restarts (idempotency)`() {
        // maybeStartMuxer is called from every addTrack/degrade site; a second call once the muxer is
        // live must be a no-op across ALL field combinations — MediaMuxer.start() called twice throws
        // IllegalStateException.
        for (video in booleanArrayOf(false, true)) {
            for (audio in booleanArrayOf(false, true)) {
                for (expected in intArrayOf(1, 2)) {
                    assertFalse(
                        shouldStartMuxer(
                            muxerStarted = true,
                            videoTrackReady = video,
                            expectedTracks = expected,
                            audioTrackReady = audio,
                        ),
                    )
                }
            }
        }
    }
}
