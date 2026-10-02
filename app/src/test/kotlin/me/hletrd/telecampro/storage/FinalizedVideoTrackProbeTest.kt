package me.hletrd.telecampro.storage

import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live stop tail's finalized-video check used to collapse EVERY exception to "no video track",
 * so a provider that could not be opened for a moment deleted a good take that launch recovery
 * would have kept. Only an extractor that PARSED the container and found no video track is
 * authoritative for deletion; any throw (open or parse) retains the take for launch recovery.
 *
 * AGG4-3: the tolerated `muxer.stop()` throw is NOT evidence of a missing moov (AOSP MPEG4Writer
 * writes the movie header over a sample-less track's ERROR_MALFORMED). After it, a parse throw is
 * re-checked on a FRESH descriptor and is INVALID only when the top-level box walk PROVES no complete
 * moov; every other answer retains.
 */
class FinalizedVideoTrackProbeTest {
    private class Descriptor : AutoCloseable {
        var closed = 0
        override fun close() {
            closed++
        }
    }

    private val unreached: (Descriptor) -> Mp4MoovPresence = { error("walk must not run") }

    @Test
    fun `provider open failure is indeterminate and reports its cause once`() {
        val cause = FileNotFoundException("provider busy")
        val opened = mutableListOf<Throwable>()
        val parsed = mutableListOf<Throwable>()

        val probe = classifyFinalizedVideoTrack<Descriptor>(
            open = { throw cause },
            hasVideoTrack = { error("must not inspect without a descriptor") },
            moovPresence = unreached,
            onOpenFailure = { opened += it },
            onParseFailure = { failure, _ -> parsed += failure },
        )

        assertEquals(PendingProbe.INDETERMINATE, probe)
        assertEquals(1, opened.size)
        assertSame(cause, opened.single())
        assertTrue(parsed.isEmpty())
    }

    @Test
    fun `an extractor-proven missing video track is invalid`() {
        val descriptor = Descriptor()
        val probe = classifyFinalizedVideoTrack(
            open = { descriptor },
            hasVideoTrack = { false },
            moovPresence = unreached,
        )

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
            val parsed = mutableListOf<Pair<Throwable, PendingProbe>>()
            val probe = classifyFinalizedVideoTrack(
                open = { descriptor },
                hasVideoTrack = { throw cause },
                // Even a proven-absent moov does not decide the non-throw path: there is no
                // confirming parse to spend, and recovery judges the retained row structurally.
                moovPresence = { Mp4MoovPresence.ABSENT },
                onParseFailure = { failure, verdict -> parsed += failure to verdict },
            )

            assertEquals(PendingProbe.INDETERMINATE, probe)
            assertSame(cause, parsed.single().first)
            assertEquals(PendingProbe.INDETERMINATE, parsed.single().second)
            assertEquals(1, descriptor.closed)
        }
    }

    @Test
    fun `a stop-throw parse failure over a present or unreadable moov is retained, not deleted`() {
        // AGG4-3 / TR4-3: the tolerated stop throw arrives over a finalized, playable file. Two
        // extractor throws (a busy provider during the post-stop media scan) used to delete it.
        for (presence in listOf(Mp4MoovPresence.PRESENT, Mp4MoovPresence.UNKNOWN)) {
            val parsed = mutableListOf<PendingProbe>()
            assertEquals(
                "$presence",
                PendingProbe.INDETERMINATE,
                classifyFinalizedVideoTrack(
                    open = { Descriptor() },
                    hasVideoTrack = { throw IOException("Failed to instantiate extractor") },
                    moovPresence = { presence },
                    muxerStopThrew = true,
                    onParseFailure = { _, verdict -> parsed += verdict },
                ),
            )
            assertEquals(listOf(PendingProbe.INDETERMINATE), parsed)
        }
        // A walk that itself throws proves nothing either.
        assertEquals(
            PendingProbe.INDETERMINATE,
            classifyFinalizedVideoTrack(
                open = { Descriptor() },
                hasVideoTrack = { throw IOException("extractor") },
                moovPresence = { throw IOException("read failed") },
                muxerStopThrew = true,
            ),
        )
    }

    @Test
    fun `only a proven absent moov after two throws is invalid`() {
        val parsed = mutableListOf<Pair<Throwable, PendingProbe>>()
        val second = IOException("second")
        var attempts = 0
        val probe = classifyFinalizedVideoTrack(
            open = { Descriptor() },
            hasVideoTrack = { if (++attempts == 1) throw IOException("first") else throw second },
            moovPresence = { Mp4MoovPresence.ABSENT },
            muxerStopThrew = true,
            onParseFailure = { failure, verdict -> parsed += failure to verdict },
        )
        assertEquals(PendingProbe.INVALID, probe)
        // Only the final cause is reported, with the verdict it produced.
        assertEquals(listOf(second to PendingProbe.INVALID), parsed)
    }

    @Test
    fun `the confirming parse runs on a fresh descriptor after the pause`() {
        // CR4-7: re-parsing the same descriptor re-read a revoked fd and could never recover.
        val opened = mutableListOf<Descriptor>()
        val events = mutableListOf<String>()
        val probe = classifyFinalizedVideoTrack(
            open = { Descriptor().also { opened += it; events += "open" } },
            hasVideoTrack = { descriptor ->
                events += "parse"
                if (descriptor === opened.first()) throw IOException("revoked fd") else true
            },
            moovPresence = unreached,
            muxerStopThrew = true,
            beforeParseRetry = { events += "pause" },
        )
        assertEquals(PendingProbe.VALID, probe)
        assertEquals(2, opened.size)
        assertNotSame(opened[0], opened[1])
        assertEquals(listOf("open", "parse", "pause", "open", "parse"), events)
        // The first descriptor is closed BEFORE the reopen, and both are closed exactly once.
        assertEquals(listOf(1, 1), opened.map { it.closed })
    }

    @Test
    fun `a failed reopen for the confirming parse is indeterminate`() {
        var opens = 0
        val reopenFailure = FileNotFoundException("provider restarted")
        val openFailures = mutableListOf<Throwable>()
        val first = Descriptor()
        val probe = classifyFinalizedVideoTrack(
            open = { if (++opens == 1) first else throw reopenFailure },
            hasVideoTrack = { throw IOException("first parse") },
            moovPresence = unreached,
            muxerStopThrew = true,
            onOpenFailure = { openFailures += it },
        )
        assertEquals(PendingProbe.INDETERMINATE, probe)
        assertSame(reopenFailure, openFailures.single())
        assertEquals(1, reopenFailure.suppressed.size)
        assertEquals(1, first.closed)
    }

    @Test
    fun `without the stop throw nothing is retried`() {
        var attempts = 0
        var retries = 0
        var opens = 0
        assertEquals(
            PendingProbe.INDETERMINATE,
            classifyFinalizedVideoTrack(
                open = { opens++; Descriptor() },
                hasVideoTrack = { attempts++; throw IOException("transient") },
                moovPresence = unreached,
                beforeParseRetry = { retries++ },
            ),
        )
        assertEquals(1, attempts)
        assertEquals(0, retries)
        assertEquals(1, opens)
    }

    @Test
    fun `a readable video track is valid even when close throws`() {
        val probe = classifyFinalizedVideoTrack(
            open = { AutoCloseable { throw IOException("close failed") } },
            hasVideoTrack = { true },
            moovPresence = { error("walk must not run") },
        )

        assertEquals(PendingProbe.VALID, probe)
    }

    // ---- recovery's structural verdict (AGG4-4) -------------------------------------------------

    @Test
    fun `recovery deletes only a proven moov-less take and otherwise keeps the transient failure`() {
        assertEquals(PendingProbe.VALID, recoveryVideoVerdict({ true }, { error("not walked") }))
        assertEquals(PendingProbe.INVALID, recoveryVideoVerdict({ false }, { error("not walked") }))
        assertEquals(
            PendingProbe.INVALID,
            recoveryVideoVerdict({ throw IOException("no moov") }, { Mp4MoovPresence.ABSENT }),
        )
        val unknownCause = IOException("extractor UNKNOWN")
        val thrown0 = assertThrows(IOException::class.java) {
            recoveryVideoVerdict({ throw unknownCause }, { Mp4MoovPresence.UNKNOWN })
        }
        assertSame(unknownCause, thrown0)
        // MRG4-4: a read walk that found the moov makes the throw a constant of the bytes.
        assertEquals(
            PendingProbe.INDETERMINATE,
            recoveryVideoVerdict({ throw IOException("corrupt stbl") }, { Mp4MoovPresence.PRESENT }),
        )
        val cause = IOException("extractor")
        val walk = IOException("walk")
        val thrown = assertThrows(IOException::class.java) {
            recoveryVideoVerdict({ throw cause }, { throw walk })
        }
        assertSame(cause, thrown)
        assertSame(walk, thrown.suppressed.single())
    }

    // MRG4-4: the fixture end to end through the recovery outcome and the re-arm rule.
    @Test
    fun `a moov-present take the extractor rejects is kept but not re-armed`() {
        val bytes = concat(box("ftyp", 16), box("mdat", 64), box("moov", 40))
        val deterministic = pendingProbeOutcome {
            recoveryVideoVerdict({ throw IOException("unsupported box") }, { walk(bytes) })
        }
        assertEquals(PendingProbeOutcome(PendingProbe.INDETERMINATE, failed = false), deterministic)
        assertEquals(
            OrphanDisposition.KEEP_PENDING,
            orphanDisposition(PendingJournalState.REGISTERED, deterministic.probe),
        )
        assertFalse(keptRowReassertsPending(PendingJournalState.REGISTERED, deterministic))

        // The same bytes behind a failing read prove nothing: transient, kept AND re-armed.
        val unreadable = pendingProbeOutcome {
            recoveryVideoVerdict(
                { throw IOException("fuse busy") },
                { probeMp4MoovPresence(bytes.size.toLong()) { _, _ -> null } },
            )
        }
        assertTrue(unreadable.failed)
        assertTrue(keptRowReassertsPending(PendingJournalState.REGISTERED, unreadable))
    }

    // ---- the top-level ISO-BMFF walk -------------------------------------------------------------

    @Test
    fun `a finalized MP4 with moov after mdat or in the front reservation is present`() {
        assertEquals(Mp4MoovPresence.PRESENT, walk(box("ftyp", 16), box("mdat", 64), box("moov", 40)))
        assertEquals(Mp4MoovPresence.PRESENT, walk(box("ftyp", 16), box("moov", 40), box("free", 8), box("mdat", 64)))
        // Large-size header on the moov itself.
        assertEquals(Mp4MoovPresence.PRESENT, walk(box("ftyp", 16), box("mdat", 32), largeBox("moov", 48)))
        // A size-0 moov is by definition the final box and runs to EOF.
        assertEquals(Mp4MoovPresence.PRESENT, walk(box("ftyp", 16), box("mdat", 32), header(0, "moov") + ByteArray(24)))
    }

    @Test
    fun `crash-truncated takes are proven moov-less`() {
        // mdat placeholder that overruns the bytes actually written.
        assertEquals(Mp4MoovPresence.ABSENT, walk(box("ftyp", 16), box("free", 32), header(4096, "mdat") + ByteArray(100)))
        // 64-bit largesize placeholder still zero.
        assertEquals(Mp4MoovPresence.ABSENT, walk(box("ftyp", 16), header(1, "mdat") + ByteArray(8) + ByteArray(64)))
        // mdat "to EOF": nothing can follow it.
        assertEquals(Mp4MoovPresence.ABSENT, walk(box("ftyp", 16), header(0, "mdat") + ByteArray(64)))
        // Every byte accounted for, no moov, plus a sub-header tail.
        assertEquals(Mp4MoovPresence.ABSENT, walk(box("ftyp", 16), box("mdat", 64), ByteArray(5)))
        // A moov that overruns the file is truncated, not present.
        assertEquals(Mp4MoovPresence.ABSENT, walk(box("ftyp", 16), header(400, "moov") + ByteArray(20)))
        // A header smaller than itself.
        assertEquals(Mp4MoovPresence.ABSENT, walk(box("ftyp", 16), header(4, "mdat") + ByteArray(32)))
        assertEquals(Mp4MoovPresence.ABSENT, walk(ByteArray(0)))
    }

    @Test
    fun `unreadable headers and the box bound prove nothing`() {
        val bytes = concat(box("ftyp", 16), box("mdat", 64), box("moov", 40))
        assertEquals(Mp4MoovPresence.UNKNOWN, probeMp4MoovPresence(bytes.size.toLong()) { _, _ -> null })
        assertEquals(
            Mp4MoovPresence.UNKNOWN,
            probeMp4MoovPresence(bytes.size.toLong()) { offset, count ->
                if (offset == 0L) bytes.copyOfRange(0, count) else ByteArray(3)
            },
        )
        assertEquals(Mp4MoovPresence.UNKNOWN, probeMp4MoovPresence(-1L) { _, _ -> null })
        // A large-size header whose 64-bit size cannot be read.
        val large = largeBox("mdat", 32)
        assertEquals(
            Mp4MoovPresence.UNKNOWN,
            probeMp4MoovPresence(large.size.toLong()) { offset, count ->
                if (offset == 0L) large.copyOfRange(0, count) else null
            },
        )
        val manyFree = ByteArrayOutputStream().apply { repeat(4_097) { write(box("free", 8)) } }.toByteArray()
        assertEquals(Mp4MoovPresence.UNKNOWN, walk(manyFree, box("moov", 16)))
    }

    private fun walk(vararg parts: ByteArray): Mp4MoovPresence {
        val bytes = concat(*parts)
        return probeMp4MoovPresence(bytes.size.toLong()) { offset, count ->
            if (offset < 0 || offset + count > bytes.size) null
            else bytes.copyOfRange(offset.toInt(), offset.toInt() + count)
        }
    }

    private fun header(size: Int, type: String): ByteArray =
        ByteBuffer.allocate(8).putInt(size).put(type.toByteArray(Charsets.US_ASCII)).array()

    private fun box(type: String, size: Int): ByteArray = header(size, type) + ByteArray(size - 8)

    private fun largeBox(type: String, size: Int): ByteArray =
        header(1, type) + ByteBuffer.allocate(8).putLong(size.toLong()).array() + ByteArray(size - 16)

    private fun concat(vararg parts: ByteArray): ByteArray =
        ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
}
