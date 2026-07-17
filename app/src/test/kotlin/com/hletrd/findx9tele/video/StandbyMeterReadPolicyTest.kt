package com.hletrd.findx9tele.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the standby audio meter's read handling to the shared [classifyAudioRead] policy plus its
 * bounded recreation rule. Regression target: the meter's old `n <= 0 -> continue` treated
 * TERMINAL framework errors (which return synchronously) as transient empty reads, so a dead
 * route hot-spun the StandbyAudioMeter thread forever without releasing the AudioRecord.
 */
class StandbyMeterReadPolicyTest {

    @Test
    fun `positive reads are pcm`() {
        assertEquals(AudioReadOutcome.Pcm(2048), classifyAudioRead(2048, running = true))
        assertEquals(AudioReadOutcome.Pcm(1), classifyAudioRead(1, running = true))
    }

    @Test
    fun `zero is a transient retry`() {
        assertEquals(AudioReadOutcome.Retry, classifyAudioRead(0, running = true))
    }

    @Test
    fun `every framework negative code is terminal while running`() {
        // ERROR, ERROR_BAD_VALUE, ERROR_INVALID_OPERATION, ERROR_DEAD_OBJECT + unknown negatives.
        for (code in intArrayOf(-1, -2, -3, -6, -7, Int.MIN_VALUE)) {
            assertEquals(AudioReadOutcome.Failure(code), classifyAudioRead(code, running = true))
        }
    }

    @Test
    fun `a disable race owns the negative wakeup`() {
        // Ownership dropped (disable/REC claim) before the read returned: the negative code is a
        // normal stop wakeup, not a terminal error.
        assertEquals(AudioReadOutcome.Stopped, classifyAudioRead(-6, running = false))
        assertEquals(AudioReadOutcome.Stopped, classifyAudioRead(0, running = false))
        assertEquals(AudioReadOutcome.Stopped, classifyAudioRead(512, running = false))
    }

    @Test
    fun `recreation is bounded and starts at the first failed generation`() {
        val max = 3
        assertTrue(standbyMeterShouldRecreate(failedGenerations = 1, maxRecreates = max))
        assertTrue(standbyMeterShouldRecreate(failedGenerations = 2, maxRecreates = max))
        assertTrue(standbyMeterShouldRecreate(failedGenerations = 3, maxRecreates = max))
        // Budget exhausted: a persistently dead mic must not recreate-spin.
        assertFalse(standbyMeterShouldRecreate(failedGenerations = 4, maxRecreates = max))
        assertFalse(standbyMeterShouldRecreate(failedGenerations = 100, maxRecreates = max))
        // Defensive: a zero/negative counter never recreates (reset-under-race safe).
        assertFalse(standbyMeterShouldRecreate(failedGenerations = 0, maxRecreates = max))
        assertFalse(standbyMeterShouldRecreate(failedGenerations = -1, maxRecreates = max))
    }
}
