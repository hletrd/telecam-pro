package com.hletrd.findx9tele.video

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `muxer state publishes only after real successful native start`() {
        var starts = 0
        val refused = muxerStartedAfterNativeStart(
            muxerStarted = false,
            videoTrackReady = true,
            expectedTracks = 1,
            audioTrackReady = false,
        ) {
            NativeStartOutcome.REFUSED
        }
        assertFalse(refused)
        assertEquals(0, starts)

        val started = muxerStartedAfterNativeStart(
            muxerStarted = refused,
            videoTrackReady = true,
            expectedTracks = 1,
            audioTrackReady = false,
        ) {
            starts++
            NativeStartOutcome.STARTED
        }
        assertTrue(started)
        assertEquals(1, starts)

        val expected = IllegalArgumentException("muxer start failed")
        var publishedAfterThrow = false
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            publishedAfterThrow = muxerStartedAfterNativeStart(
                muxerStarted = false,
                videoTrackReady = true,
                expectedTracks = 1,
                audioTrackReady = false,
            ) {
                throw expected
            }
        }
        assertSame(expected, thrown)
        assertFalse(publishedAfterThrow)
    }
}
