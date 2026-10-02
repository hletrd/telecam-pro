package me.hletrd.telecampro.video

import android.media.MediaCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * AGG6-6: drives [RecorderCodecTeardown], the owner VideoRecorder's setup-failure, finalizer and
 * drain-worker paths actually call, instead of re-implementing their wiring (TE6-1). Robolectric
 * supplies a real `MediaCodec.CodecException` class for the throw-site latch.
 */
@RunWith(RobolectricTestRunner::class)
class RecorderCodecTeardownTest {

    private fun codecException(): MediaCodec.CodecException {
        val ctor = MediaCodec.CodecException::class.java.declaredConstructors.first { ctor ->
            ctor.parameterTypes.contentEquals(
                arrayOf(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java),
            )
        }
        ctor.isAccessible = true
        return ctor.newInstance(-1, 0, "codec reported an internal error") as MediaCodec.CodecException
    }

    @Test
    fun `a failed AAC start makes the audio setup cleanup skip its stop throw and release everything`() {
        val teardown = RecorderCodecTeardown(RecorderNativeOperationGate())
        val startFailure = IllegalStateException("AAC start() refused")
        val thrown = runCatching { teardown.audioCodecSetupCall<Unit> { throw startFailure } }
        assertSame(startFailure, thrown.exceptionOrNull())
        assertTrue(teardown.audioFault.isLatched)
        assertFalse(teardown.videoFault.isLatched)

        val released = mutableListOf<String>()
        assertTrue(
            teardown.releaseFailedAudioSetup(
                releaseRecord = { released += "record" },
                stopCodec = { throw IllegalStateException("stop() in the Error state") },
                releaseCodec = { released += "codec" },
            ),
        )
        assertEquals(listOf("record", "codec"), released)
        // No unproved release: the engine must NOT enter process-wide recorder quarantine.
        assertNull(teardown.failure)
    }

    @Test
    fun `a successful AAC setup leaves a later real stop failure quarantined`() {
        val teardown = RecorderCodecTeardown(RecorderNativeOperationGate())
        assertEquals(7, teardown.audioCodecSetupCall { 7 })
        assertFalse(teardown.audioFault.isLatched)
        val stopFailure = IllegalStateException("native stop failed")
        var codecReleased = false
        assertFalse(
            teardown.releaseFailedAudioSetup(
                releaseRecord = {},
                stopCodec = { throw stopFailure },
                releaseCodec = { codecReleased = true },
            ),
        )
        assertFalse(codecReleased)
        assertSame(stopFailure, teardown.failure)
        // The first unproved owner freezes every later cleanup phase.
        var entered = false
        assertFalse(teardown.native { entered = true })
        assertFalse(teardown.codec(CodecCleanupCall.STOP, teardown.videoFault) { entered = true })
        assertFalse(entered)
    }

    @Test
    fun `a CodecException from EOS latches at its throw site and skips to release`() {
        val skipped = mutableListOf<CodecCleanupCall>()
        val teardown = RecorderCodecTeardown(RecorderNativeOperationGate()) { call, _ -> skipped += call }
        val error = codecException()
        var waitedForDrain = false
        assertTrue(
            teardown.signalVideoEndOfInput(
                signal = { throw error },
                awaitVideoDrainExit = { waitedForDrain = true },
            ),
        )
        assertTrue(teardown.videoFault.isLatched)
        assertFalse(waitedForDrain)
        assertEquals(listOf(CodecCleanupCall.SIGNAL_END_OF_INPUT), skipped)
        assertNull(teardown.failure)
    }

    @Test
    fun `a plain EOS state throw racing an async codec error waits for the drain thread to latch`() {
        val teardown = RecorderCodecTeardown(RecorderNativeOperationGate())
        // The drain thread's own dequeue then fails on the errored codec and latches (onCodec).
        assertTrue(
            teardown.signalVideoEndOfInput(
                signal = { throw IllegalStateException("signalEndOfInputStream in the Error state") },
                awaitVideoDrainExit = {
                    teardown.videoFault.observeCodecThrow(IllegalStateException("dequeue cancelled"))
                },
            ),
        )
        assertNull(teardown.failure)
    }

    @Test
    fun `a plain EOS state throw whose drain never reports an error stays unproven`() {
        val teardown = RecorderCodecTeardown(RecorderNativeOperationGate())
        val eos = IllegalStateException("signalEndOfInputStream refused")
        var waits = 0
        assertFalse(teardown.signalVideoEndOfInput(signal = { throw eos }, awaitVideoDrainExit = { waits++ }))
        assertEquals(1, waits)
        assertSame(eos, teardown.failure)

        // A non-state throwable never waits and is never skipped.
        val other = RecorderCodecTeardown(RecorderNativeOperationGate())
        val runtime = RuntimeException("native fault")
        assertFalse(other.signalVideoEndOfInput(signal = { throw runtime }, awaitVideoDrainExit = { waits++ }))
        assertEquals(1, waits)
        assertSame(runtime, other.failure)
    }

    @Test
    fun `release never consults or creates codec error evidence`() {
        val teardown = RecorderCodecTeardown(RecorderNativeOperationGate())
        val error = codecException()
        assertFalse(teardown.codec(CodecCleanupCall.RELEASE, teardown.audioFault) { throw error })
        assertFalse(teardown.audioFault.isLatched)
        assertSame(error, teardown.failure)
    }

    @Test
    fun `only a throw from a call on the codec is error evidence`() {
        val latch = CodecFaultLatch()
        // Admission revocation says nothing about the codec.
        latch.observeCodecThrow(RecorderNativeOperationRevokedException())
        assertFalse(latch.isLatched)
        assertFalse(codecReportedError(IllegalStateException("mic read failed")))
        assertTrue(codecReportedError(codecException()))
        latch.observeCodecThrow(IllegalStateException("dequeue on an errored codec"))
        assertTrue(latch.isLatched)
    }

    @Test
    fun `a revoked cleanup return is neither skipped nor recorded as a failure`() {
        val gate = RecorderNativeOperationGate()
        val teardown = RecorderCodecTeardown(gate)
        teardown.audioFault.observeCodecThrow(IllegalStateException("errored"))
        assertTrue(gate.close())
        assertFalse(teardown.codec(CodecCleanupCall.STOP, teardown.audioFault) { })
        assertFalse(teardown.native { })
        assertNull(teardown.failure)
    }

    @Test
    fun `a clean codec cleanup completes`() {
        val teardown = RecorderCodecTeardown(RecorderNativeOperationGate())
        assertTrue(teardown.codec(CodecCleanupCall.STOP, teardown.videoFault) { })
        assertTrue(teardown.native { })
        assertNull(teardown.failure)
    }
}
