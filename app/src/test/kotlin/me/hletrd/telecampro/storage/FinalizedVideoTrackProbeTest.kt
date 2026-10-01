package me.hletrd.telecampro.storage

import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live stop tail's finalized-video check used to collapse EVERY exception to "no video track",
 * so a provider that could not be opened for a moment deleted a good take that launch recovery
 * would have kept. Only an extractor that PARSED the container and found no video track is
 * authoritative for deletion; any throw (open or parse) retains the take for launch recovery.
 */
class FinalizedVideoTrackProbeTest {
    private class Descriptor : AutoCloseable {
        var closed = 0
        override fun close() {
            closed++
        }
    }

    @Test
    fun `provider open failure is indeterminate and reports its cause once`() {
        val cause = FileNotFoundException("provider busy")
        val opened = mutableListOf<Throwable>()
        val parsed = mutableListOf<Throwable>()

        val probe = classifyFinalizedVideoTrack<Descriptor>(
            open = { throw cause },
            hasVideoTrack = { error("must not inspect without a descriptor") },
            onOpenFailure = { opened += it },
            onParseFailure = { parsed += it },
        )

        assertEquals(PendingProbe.INDETERMINATE, probe)
        assertEquals(1, opened.size)
        assertSame(cause, opened.single())
        assertTrue(parsed.isEmpty())
    }

    @Test
    fun `an extractor-proven missing video track is invalid`() {
        val descriptor = Descriptor()
        val probe = classifyFinalizedVideoTrack(open = { descriptor }, hasVideoTrack = { false })

        assertEquals(PendingProbe.INVALID, probe)
        assertEquals(1, descriptor.closed)
    }

    @Test
    fun `an extractor throw after a successful open is indeterminate, never destructive`() {
        // AGG2-18: MediaExtractor.setDataSource raises the same IOException for a transient FUSE /
        // provider read error as for a corrupt container, and launch recovery maps any probe throw
        // to INDETERMINATE. The live tail must not be the stricter path in the destructive direction.
        for (cause in listOf(
            IOException("setDataSource failed"),
            SecurityException("fd revoked"),
            IllegalStateException("extractor"),
        )) {
            val descriptor = Descriptor()
            val parsed = mutableListOf<Throwable>()
            val probe = classifyFinalizedVideoTrack(
                open = { descriptor },
                hasVideoTrack = { throw cause },
                onParseFailure = { parsed += it },
            )

            assertEquals(PendingProbe.INDETERMINATE, probe)
            assertSame(cause, parsed.single())
            assertEquals(1, descriptor.closed)
        }
    }

    @Test
    fun `a readable video track is valid even when close throws`() {
        val probe = classifyFinalizedVideoTrack(
            open = { AutoCloseable { throw IOException("close failed") } },
            hasVideoTrack = { true },
        )

        assertEquals(PendingProbe.VALID, probe)
    }
}
