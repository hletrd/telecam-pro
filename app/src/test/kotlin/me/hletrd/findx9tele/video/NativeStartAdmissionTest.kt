package me.hletrd.findx9tele.video

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

    /**
     * A close() landing WHILE a native start is in flight must not block on it, and must not
     * corrupt that in-flight call's own outcome — the cycle-6 T6/T7 contract documented on
     * [RecorderQuarantineAdmissionGate.runNativeIfSafe]: the native call deliberately runs OUTSIDE
     * the process-global lock (holding it across seconds of Binder/driver work was the enabling
     * half of the ABBA inversion), an already-running native cannot be un-called under either
     * ordering, and its RESULT is refused downstream by the token checks instead.
     *
     * This test used to assert the OPPOSITE (`assertFalse(closeDone)` the instant the closer thread
     * signalled its intent), i.e. that close() waits for the in-flight native. That contract was
     * removed with the lock; the assertion only ever passed because the closer thread had not been
     * scheduled through close() yet, and it failed intermittently under CPU contention. Pin the
     * real invariants instead: close completes without waiting, the in-flight start still reports
     * STARTED, and ADMISSION (not the native call) is what quarantine refuses afterwards.
     */
    @Test
    fun `terminal close does not wait for an in-flight native start and refuses admission after`() {
        val gate = RecorderQuarantineAdmissionGate()
        val enteredStart = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val closeDone = CountDownLatch(1)
        val outcome = AtomicReference<NativeStartOutcome>()
        val closeAccepted = AtomicBoolean(false)
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
            closeAccepted.set(gate.close())
            closeDone.countDown()
        }

        starter.start()
        assertTrue(enteredStart.await(5, TimeUnit.SECONDS))
        closer.start()
        // The load-bearing assertion: close returns while `start` is still parked inside the native
        // call. A close that waited would time out here instead of racing an unsynchronized flag.
        assertTrue(closeDone.await(5, TimeUnit.SECONDS))
        assertTrue(closeAccepted.get())
        assertTrue(gate.isQuarantined())

        releaseStart.countDown()
        starter.join(5_000)
        closer.join(5_000)

        assertEquals(NativeStartOutcome.STARTED, outcome.get())
        // Quarantine linearizes with ADMISSION, so no new owner can be leased afterwards.
        assertNull(gate.snapshot(owner = Any()))
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
