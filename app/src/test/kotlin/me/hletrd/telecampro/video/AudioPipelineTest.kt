package me.hletrd.telecampro.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the audio pipeline's pure seams: PTS derivation (the entire A/V-sync basis of every clip)
 * and the capture-device channel-count heuristic.
 */
class AudioPipelineTest {

    @Test
    fun audioPtsUs_exactValuesAt48k() {
        assertEquals(0L, audioPtsUs(0, 48_000))
        assertEquals(1_000_000L, audioPtsUs(48_000, 48_000))
        assertEquals(500_000L, audioPtsUs(24_000, 48_000))
        // One AAC frame (1024 samples) at 48 kHz = 21333 µs (truncating integer division).
        assertEquals(21_333L, audioPtsUs(1024, 48_000))
    }

    @Test
    fun audioPtsUs_monotonicAndOverflowSafeForMultiHourTakes() {
        // 4 hours of 48 kHz stereo samples — far beyond any real clip.
        val fourHours = 48_000L * 3600 * 4
        var prev = -1L
        for (samples in listOf(0L, 48_000L, fourHours / 2, fourHours)) {
            val pts = audioPtsUs(samples, 48_000)
            assertTrue(pts > prev)
            prev = pts
        }
        assertEquals(4L * 3600 * 1_000_000, audioPtsUs(fourHours, 48_000))
    }

    @Test
    fun channelCount_noDevice_defaultsStereo() {
        assertEquals(2, resolveAudioChannelCount(null, isBluetooth = false))
    }

    @Test
    fun channelCount_stereoCapableDevice_isStereo() {
        assertEquals(2, resolveAudioChannelCount(intArrayOf(1, 2), isBluetooth = false))
        assertEquals(2, resolveAudioChannelCount(intArrayOf(2), isBluetooth = true))
    }

    @Test
    fun channelCount_emptyCaps_builtInAssumesStereo_btAssumesMono() {
        // Built-in mics report empty caps but record stereo; a BT headset mic with empty caps is
        // assumed mono — asking it for stereo mismatches the AAC MediaFormat's channel count.
        assertEquals(2, resolveAudioChannelCount(intArrayOf(), isBluetooth = false))
        assertEquals(1, resolveAudioChannelCount(intArrayOf(), isBluetooth = true))
    }

    @Test
    fun channelCount_monoOnlyDevice_isMono() {
        assertEquals(1, resolveAudioChannelCount(intArrayOf(1), isBluetooth = false))
        assertEquals(1, resolveAudioChannelCount(intArrayOf(1), isBluetooth = true))
    }

    @Test
    fun audioSetupFailure_reportsUnavailableRoute() {
        assertEquals("USB unavailable", audioUnavailableLabel("USB"))
    }

    @Test
    fun audioRead_positiveBytes_arePcm() {
        assertEquals(AudioReadOutcome.Pcm(4096), classifyAudioRead(4096, running = true))
    }

    @Test
    fun audioRead_zeroWhileRunning_isTransient() {
        assertSame(AudioReadOutcome.Retry, classifyAudioRead(0, running = true))
    }

    @Test
    fun audioRead_everyFrameworkErrorWhileRunning_isTerminalAndDescriptive() {
        val errors = listOf(
            -1 to "ERROR",
            -2 to "ERROR_BAD_VALUE",
            -3 to "ERROR_INVALID_OPERATION",
            -6 to "ERROR_DEAD_OBJECT",
        )

        errors.forEach { (code, label) ->
            assertEquals(AudioReadOutcome.Failure(code), classifyAudioRead(code, running = true))
            val failure = audioReadFailure(code)
            assertTrue(failure.message.orEmpty().contains(label))
            assertTrue(failure.message.orEmpty().contains(code.toString()))
        }
    }

    @Test
    fun audioRead_unknownNegativeWhileRunning_isAlsoTerminal() {
        assertEquals(AudioReadOutcome.Failure(-99), classifyAudioRead(-99, running = true))
        assertTrue(audioReadFailure(-99).message.orEmpty().contains("UNKNOWN"))
    }

    @Test
    fun audioRead_negativeAfterStopRequested_isNormalEos() {
        assertSame(AudioReadOutcome.Stopped, classifyAudioRead(-6, running = false))
    }

    @Test
    fun audioSampleCapture_walksFromTheTimestampAnchor() {
        // Frame 4 800 was captured at t = 10 s: frame 0 lies 100 ms earlier at 48 kHz.
        assertEquals(9_900_000_000L, audioSampleCaptureNs(4_800, 10_000_000_000L, 0, 48_000))
        assertEquals(10_000_000_000L, audioSampleCaptureNs(4_800, 10_000_000_000L, 4_800, 48_000))
        assertEquals(10_001_000_000L, audioSampleCaptureNs(4_800, 10_000_000_000L, 4_848, 48_000))
    }

    @Test
    fun audioCaptureOffset_movesAudioOntoTheVideoTimeline() {
        // Sample 0 captured 56 ms after the first frame's exposure began: audio starts at +56 ms.
        assertEquals(56_000L, audioCaptureOffsetUs(1_000_056_000_000L, 1_000_000_000_000L, true))
        // Captured before it: frames earlier than the first video frame fall below zero.
        assertEquals(-30_000L, audioCaptureOffsetUs(1_000_000_000_000L, 1_000_030_000_000L, true))
    }

    @Test
    fun audioCaptureOffset_staysUnanchoredWithoutAComparableClock() {
        assertEquals(0L, audioCaptureOffsetUs(1_000_056_000_000L, 1_000_000_000_000L, false))
        assertEquals(0L, audioCaptureOffsetUs(0L, 1_000_000_000_000L, true))
        assertEquals(0L, audioCaptureOffsetUs(1_000_056_000_000L, 0L, true))
        // A gap past the plausibility bound is a clock mismatch, never a latency.
        assertEquals(0L, audioCaptureOffsetUs(1_001_000_000_001L, 1_000_000_000_000L, true))
        assertEquals(1_000_000L, audioCaptureOffsetUs(1_001_000_000_000L, 1_000_000_000_000L, true))
    }

    @Test
    fun alignedAudioPts_dropsOnlyFramesBeforeTheFirstVideoFrame() {
        assertEquals(56_000L, alignedAudioPtsUs(0L, 56_000L))
        assertEquals(0L, alignedAudioPtsUs(30_000L, -30_000L))
        assertEquals(null, alignedAudioPtsUs(21_333L, -30_000L))
    }
}
