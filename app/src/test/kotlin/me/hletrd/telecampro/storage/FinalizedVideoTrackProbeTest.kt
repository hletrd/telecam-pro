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
 * would have kept. Only an extractor verdict after a successful open is authoritative.
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
    fun `an unparseable container after a successful open is invalid`() {
        val descriptor = Descriptor()
        val parsed = mutableListOf<Throwable>()
        val probe = classifyFinalizedVideoTrack(
            open = { descriptor },
            hasVideoTrack = { throw IOException("setDataSource failed") },
            onParseFailure = { parsed += it },
        )

        assertEquals(PendingProbe.INVALID, probe)
        assertEquals(1, parsed.size)
        assertEquals(1, descriptor.closed)
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
