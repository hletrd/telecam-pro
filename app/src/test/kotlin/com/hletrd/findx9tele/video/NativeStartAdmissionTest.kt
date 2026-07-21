package com.hletrd.findx9tele.video

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeStartAdmissionTest {

    @Test
    fun `admitted native start completes before terminal close`() {
        val gate = RecorderQuarantineAdmissionGate()
        val enteredStart = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val closeAttempted = CountDownLatch(1)
        val outcome = AtomicReference<NativeStartOutcome>()
        val closeDone = AtomicBoolean(false)
        val starter = Thread {
            outcome.set(
                startNativeOwnerIfSafe(
                    runNativeAcquisition = gate::runNativeIfSafe,
                    isTerminal = { false },
                    start = {
                        enteredStart.countDown()
                        releaseStart.await()
                    },
                ),
            )
        }
        val closer = Thread {
            closeAttempted.countDown()
            gate.close()
            closeDone.set(true)
        }

        starter.start()
        assertTrue(enteredStart.await(5, TimeUnit.SECONDS))
        closer.start()
        assertTrue(closeAttempted.await(5, TimeUnit.SECONDS))
        assertFalse(closeDone.get())
        releaseStart.countDown()
        starter.join(5_000)
        closer.join(5_000)

        assertEquals(NativeStartOutcome.STARTED, outcome.get())
        assertTrue(closeDone.get())
        assertTrue(gate.isQuarantined())
    }

    @Test
    fun `close before worker admission refuses without touching native owner`() {
        val gate = RecorderQuarantineAdmissionGate()
        var terminalChecks = 0
        var nativeStarts = 0
        assertTrue(gate.close())

        val outcome = startNativeOwnerIfSafe(
            runNativeAcquisition = gate::runNativeIfSafe,
            isTerminal = { terminalChecks++; false },
            start = { nativeStarts++ },
        )

        assertEquals(NativeStartOutcome.REFUSED, outcome)
        assertEquals(0, terminalChecks)
        assertEquals(0, nativeStarts)
    }

    @Test
    fun `local terminal recheck inside admitted block refuses native start`() {
        val gate = RecorderQuarantineAdmissionGate()
        var terminalChecks = 0
        var nativeStarts = 0

        val outcome = startNativeOwnerIfSafe(
            runNativeAcquisition = gate::runNativeIfSafe,
            isTerminal = { terminalChecks++; true },
            start = { nativeStarts++ },
        )

        assertEquals(NativeStartOutcome.REFUSED, outcome)
        assertEquals(1, terminalChecks)
        assertEquals(0, nativeStarts)
        assertFalse(gate.isQuarantined())
    }

    @Test
    fun `native start exception is preserved for its caller`() {
        val gate = RecorderQuarantineAdmissionGate()
        val expected = IllegalStateException("native start failed")

        val thrown = assertThrows(IllegalStateException::class.java) {
            startNativeOwnerIfSafe(
                runNativeAcquisition = gate::runNativeIfSafe,
                isTerminal = { false },
                start = { throw expected },
            )
        }

        assertSame(expected, thrown)
    }

    @Test
    fun `not-ready muxer remains waiting without touching native start`() {
        var starts = 0
        val transition = transitionMuxerStart(
            state = MuxerStartState.WAITING,
            videoTrackReady = false,
            expectedTracks = 1,
            audioTrackReady = false,
        ) {
            starts++
            NativeStartOutcome.STARTED
        }

        assertEquals(MuxerStartState.WAITING, transition.state)
        assertNull(transition.failure)
        assertEquals(0, starts)
    }

    @Test
    fun `successful muxer start publishes once and repeated call is inert`() {
        var starts = 0
        var transition = transitionMuxerStart(
            state = MuxerStartState.WAITING,
            videoTrackReady = true,
            expectedTracks = 1,
            audioTrackReady = false,
        ) {
            starts++
            NativeStartOutcome.STARTED
        }
        assertEquals(MuxerStartState.STARTED, transition.state)
        assertNull(transition.failure)
        assertEquals(1, starts)

        transition = transitionMuxerStart(
            state = transition.state,
            videoTrackReady = true,
            expectedTracks = 1,
            audioTrackReady = false,
        ) {
            starts++
            NativeStartOutcome.STARTED
        }

        assertEquals(MuxerStartState.STARTED, transition.state)
        assertNull(transition.failure)
        assertEquals(1, starts)
    }

    @Test
    fun `refused muxer start is terminal and cannot retry native owner`() {
        var starts = 0
        var transition = transitionMuxerStart(
            state = MuxerStartState.WAITING,
            videoTrackReady = true,
            expectedTracks = 1,
            audioTrackReady = false,
        ) {
            starts++
            NativeStartOutcome.REFUSED
        }
        assertEquals(MuxerStartState.TERMINAL, transition.state)
        assertNull(transition.failure)

        transition = transitionMuxerStart(
            state = transition.state,
            videoTrackReady = true,
            expectedTracks = 1,
            audioTrackReady = false,
        ) {
            starts++
            NativeStartOutcome.STARTED
        }

        assertEquals(MuxerStartState.TERMINAL, transition.state)
        assertNull(transition.failure)
        assertEquals(1, starts)
    }

    @Test
    fun `throwing audio-initiated muxer start notifies once and degrade re-entry cannot retry`() {
        val signal = FirstFailureSignal()
        val expected = IllegalArgumentException("muxer start failed")
        var notifications = 0
        var starts = 0
        var transition = transitionMuxerStart(
            state = MuxerStartState.WAITING,
            videoTrackReady = true,
            expectedTracks = 2,
            audioTrackReady = true,
        ) {
            starts++
            throw expected
        }
        assertEquals(MuxerStartState.TERMINAL, transition.state)
        assertSame(expected, transition.failure)
        transition.failure?.let { signal.record(it) { notifications++ } }

        // This is the former escape path: the audio worker catches its initial start throw,
        // degrades expectedTracks to one, and re-enters maybeStartMuxer. TERMINAL makes that call a
        // no-op, so the same native owner cannot throw again outside the worker's catch boundary.
        transition = transitionMuxerStart(
            state = transition.state,
            videoTrackReady = true,
            expectedTracks = 1,
            audioTrackReady = true,
        ) {
            starts++
            throw AssertionError("native start retried")
        }
        transition.failure?.let { signal.record(it) { notifications++ } }

        assertEquals(MuxerStartState.TERMINAL, transition.state)
        assertNull(transition.failure)
        assertSame(expected, signal.cause)
        assertEquals(1, starts)
        assertEquals(1, notifications)
    }
}
