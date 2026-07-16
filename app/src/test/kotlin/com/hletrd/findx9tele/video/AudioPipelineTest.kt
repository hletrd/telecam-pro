package com.hletrd.findx9tele.video

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
}
