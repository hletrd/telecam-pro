package me.hletrd.telecampro.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Engine runs recorder setup under a PENDING admission token and publishes it only after the
 * first successful encoder swap. Setup spawns the audio-encode worker, which reaches
 * `AudioRecord.startRecording` through the general native gate before that publication whenever
 * the encoder is slow to swap (TB336ZU, device-measured 2026-09-09). The token's own owner must
 * therefore be admitted while pending, exactly as it is once active; everyone else still waits.
 */
class PendingTokenNativeStartTest {
    @Test
    fun `a pending token's own owner starts native audio before publication`() {
        val gate = RecorderQuarantineAdmissionGate()
        val owner = Any()
        val token = gate.snapshot(owner)
        assertNotNull(token)
        var starts = 0

        val outcome = startNativeOwnerIfSafe(
            runNativeAcquisition = { block -> gate.runNativeIfSafe(owner, block) },
            isTerminal = { false },
            start = { starts++ },
        )

        assertEquals(NativeStartOutcome.STARTED, outcome)
        assertEquals(1, starts)
    }

    @Test
    fun `a foreign owner and an anonymous caller still wait behind a pending token`() {
        val gate = RecorderQuarantineAdmissionGate()
        val token = gate.snapshot(Any())
        assertNotNull(token)
        var starts = 0

        val foreign = startNativeOwnerIfSafe(
            runNativeAcquisition = { block -> gate.runNativeIfSafe(Any(), block) },
            isTerminal = { false },
            start = { starts++ },
        )
        val anonymous = startNativeOwnerIfSafe(
            runNativeAcquisition = { block -> gate.runNativeIfSafe(null, block) },
            isTerminal = { false },
            start = { starts++ },
        )

        assertEquals(NativeStartOutcome.REFUSED, foreign)
        assertEquals(NativeStartOutcome.REFUSED, anonymous)
        assertEquals(0, starts)
    }

    @Test
    fun `the same owner keeps its admission across publication`() {
        val gate = RecorderQuarantineAdmissionGate()
        val owner = Any()
        val token = gate.snapshot(owner)
        assertNotNull(token)
        assertTrue(gate.publish(token!!) { true })
        var starts = 0

        val outcome = startNativeOwnerIfSafe(
            runNativeAcquisition = { block -> gate.runNativeIfSafe(owner, block) },
            isTerminal = { false },
            start = { starts++ },
        )

        assertEquals(NativeStartOutcome.STARTED, outcome)
        assertEquals(1, starts)
        assertEquals(
            NativeStartOutcome.REFUSED,
            startNativeOwnerIfSafe(
                runNativeAcquisition = { block -> gate.runNativeIfSafe(Any(), block) },
                isTerminal = { false },
                start = { starts++ },
            ),
        )
        assertEquals(1, starts)
    }
}
