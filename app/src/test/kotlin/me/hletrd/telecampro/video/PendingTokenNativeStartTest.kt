package me.hletrd.telecampro.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Engine runs recorder setup under a PENDING admission token and publishes it only after the
 * first successful encoder swap. Setup spawns the audio-encode worker, which reaches
 * `AudioRecord.startRecording` before that publication whenever the encoder is slow to swap
 * (TB336ZU, device-measured 2026-09-09). The recorder's own workers are therefore admitted by
 * exact TOKEN, pending or active. The owner-keyed general door stays CLOSED to everyone while any
 * token is pending — including the setup-owning Engine itself, because GL/EGL and Camera2 share that
 * one Engine owner object and must not run mid-setup.
 */
class PendingTokenNativeStartTest {
    @Test
    fun `a pending token's own recorder worker starts native audio before publication`() {
        val gate = RecorderQuarantineAdmissionGate()
        val token = gate.snapshot(Any())
        assertNotNull(token)
        var starts = 0

        val outcome = startNativeOwnerIfSafe(
            runNativeAcquisition = { block -> gate.runRecorderWorkerNative(token!!, block) },
            isTerminal = { false },
            start = { starts++ },
        )

        assertEquals(NativeStartOutcome.STARTED, outcome)
        assertEquals(1, starts)
    }

    @Test
    fun `the setup-owning Engine's own GL or Camera2 acquisition is refused while pending`() {
        val gate = RecorderQuarantineAdmissionGate()
        val engineOwner = Any()
        val token = gate.snapshot(engineOwner)
        assertNotNull(token)
        var entered = false

        // Same owner object as the token: in production this is the Engine's GL/Camera2 caller.
        assertFalse(gate.runNativeIfSafe(engineOwner) { entered = true })
        assertFalse(entered)

        // Publication re-admits that Engine's owner-keyed work.
        assertTrue(gate.publish(token!!) { true })
        assertTrue(gate.runNativeIfSafe(engineOwner) { entered = true })
        assertTrue(entered)
    }

    @Test
    fun `a foreign owner, an anonymous caller and a foreign token wait behind a pending token`() {
        val gate = RecorderQuarantineAdmissionGate()
        val token = gate.snapshot(Any())
        assertNotNull(token)
        val stranger = RecorderQuarantineAdmissionGate().snapshot(Any())
        assertNotNull(stranger)
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
        val foreignToken = startNativeOwnerIfSafe(
            runNativeAcquisition = { block -> gate.runRecorderWorkerNative(stranger!!, block) },
            isTerminal = { false },
            start = { starts++ },
        )

        assertEquals(NativeStartOutcome.REFUSED, foreign)
        assertEquals(NativeStartOutcome.REFUSED, anonymous)
        assertEquals(NativeStartOutcome.REFUSED, foreignToken)
        assertEquals(0, starts)
    }

    @Test
    fun `the token's worker keeps its admission across publication and loses it at finish`() {
        val gate = RecorderQuarantineAdmissionGate()
        val owner = Any()
        val token = gate.snapshot(owner)
        assertNotNull(token)
        assertTrue(gate.publish(token!!) { true })
        var starts = 0

        val outcome = startNativeOwnerIfSafe(
            runNativeAcquisition = { block -> gate.runRecorderWorkerNative(token, block) },
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

        gate.finish(token)
        assertFalse(gate.runRecorderWorkerNative(token) { starts++ })
        assertEquals(1, starts)
    }

    @Test
    fun `quarantine refuses the token's worker`() {
        val gate = RecorderQuarantineAdmissionGate()
        val token = gate.snapshot(Any())
        assertNotNull(token)
        gate.close()
        var starts = 0

        assertFalse(gate.runRecorderWorkerNative(token!!) { starts++ })
        assertEquals(0, starts)
    }
}
