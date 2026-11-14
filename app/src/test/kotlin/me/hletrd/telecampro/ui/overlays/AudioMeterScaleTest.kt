package me.hletrd.telecampro.ui.overlays

import me.hletrd.telecampro.video.AudioOverloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** UI review 2026-11-14: the meter was linear RMS with no peak or clip mark of its own. */
class AudioMeterScaleTest {
    @Test fun `the bar is a dB scale from minus 48 dBFS`() {
        assertEquals(0f, audioMeterFraction(0f), 0f)
        assertEquals(0f, audioMeterFraction(Float.NaN), 0f)
        assertEquals(1f, audioMeterFraction(1f), 1e-6f)
        // Dialogue at -20 dBFS sits past the middle instead of a tenth of the way along.
        assertEquals(28f / 48f, audioMeterFraction(0.1f), 1e-4f)
        assertEquals(36f / 48f, audioMeterFraction(0.2512f), 1e-3f)
        assertEquals(0f, audioMeterFraction(1f / 512f), 0f)
        assertEquals(audioMeterDbFraction(-12f), audioMeterFraction(0.2512f), 1e-3f)
    }

    @Test fun `a peak mark holds, then falls back to the live level`() {
        var hold = nextAudioMeterHold(AudioMeterHold(), listOf(0.8f), emptyList(), nowMs = 0L)
        hold = nextAudioMeterHold(hold, listOf(0.3f), emptyList(), nowMs = 1_000L)
        assertEquals(0.8f, hold.peaks.single(), 0f)
        hold = nextAudioMeterHold(hold, listOf(0.3f), emptyList(), nowMs = AUDIO_METER_PEAK_HOLD_MS)
        assertEquals(0.3f, hold.peaks.single(), 0f)
        hold = nextAudioMeterHold(hold, listOf(0.9f), emptyList(), nowMs = AUDIO_METER_PEAK_HOLD_MS + 10L)
        assertEquals(0.9f, hold.peaks.single(), 0f)
    }

    @Test fun `a clip lights its own channel's lamp for the latch time`() {
        val clipped = nextAudioMeterHold(
            AudioMeterHold(),
            listOf(0.5f, 0.5f),
            listOf(AudioOverloadState.NORMAL, AudioOverloadState.CLIPPING),
            nowMs = 100L,
        )
        assertFalse(clipped.clipLit(0, 100L))
        assertTrue(clipped.clipLit(1, 100L + AUDIO_METER_CLIP_LATCH_MS - 1L))
        assertFalse(clipped.clipLit(1, 100L + AUDIO_METER_CLIP_LATCH_MS))
        // Near-clipping alone is not a clip.
        val near = nextAudioMeterHold(AudioMeterHold(), listOf(0.9f), listOf(AudioOverloadState.NEAR_CLIPPING), 0L)
        assertFalse(near.clipLit(0, 0L))
    }

    @Test fun `a channel-count change starts the marks over`() {
        val mono = nextAudioMeterHold(AudioMeterHold(), listOf(0.7f), listOf(AudioOverloadState.CLIPPING), 0L)
        val stereo = nextAudioMeterHold(mono, listOf(0.2f, 0.4f), emptyList(), 10L)
        assertEquals(listOf(0.2f, 0.4f), stereo.peaks)
        assertFalse(stereo.clipLit(0, 10L))
    }
}
