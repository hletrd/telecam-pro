package com.hletrd.findx9tele.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the orphan-sweep delete predicate. The production call site wraps the whole query in
 * runCatching, so a placeholder/arg-count mismatch or a broken path anchor would fail as a
 * silently-swallowed SQLiteException — the sweep would no-op forever (storage leak) or, worse, a
 * widened predicate would re-open the pre-fix cross-app deletion shape. These tests are the only
 * loud failure mode that logic has.
 */
class OrphanSweepTest {

    @Test
    fun `placeholder count matches the bound arg count`() {
        val (selection, args) = orphanSweepSelection("X9Tele", "me.hletrd.telecampro", 1_700_000_000L)
        assertEquals(selection.count { it == '?' }, args.size)
    }

    @Test
    fun `selection binds path then age then owner - the arg order`() {
        val (selection, args) = orphanSweepSelection("X9Tele", "me.hletrd.telecampro", 1_700_000_000L)
        // The i-th '?' binds args[i]; pin the column order so a reorder cannot mismatch bindings.
        val columns = Regex("(\\w+) (?:LIKE|<|=) \\?").findAll(selection).map { it.groupValues[1] }.toList()
        assertEquals(listOf("relative_path", "date_added", "owner_package_name"), columns)
        assertEquals("DCIM/X9Tele/%", args[0])
        assertEquals("1700000000", args[1])
        assertEquals("me.hletrd.telecampro", args[2])
    }

    @Test
    fun `path anchor keeps the trailing slash so a sibling directory cannot match`() {
        val (_, args) = orphanSweepSelection("X9Tele", "pkg", 0L)
        val pattern = args[0]
        assertTrue(pattern.endsWith("/%"))
        val prefix = pattern.removeSuffix("%")
        // SQL LIKE 'prefix%' semantics on the normalized values MediaStore stores:
        assertTrue("DCIM/X9Tele/shot.heic".startsWith(prefix))
        assertFalse("DCIM/X9TeleOther/shot.heic".startsWith(prefix))
    }

    @Test
    fun `process start epoch mixes the two clocks and truncates conservatively`() {
        // Booted 100 s ago at epoch 1_000_000 s; process started 40 s after boot.
        val nowMillis = 1_000_100_000L
        val secs = processStartEpochSecs(
            nowMillis = nowMillis,
            elapsedRealtimeMillis = 100_000L,
            processStartElapsedRealtimeMillis = 40_000L,
        )
        assertEquals(1_000_040L, secs)
        // Sub-second parts truncate DOWN (an earlier cutoff deletes fewer rows — conservative).
        val truncated = processStartEpochSecs(
            nowMillis = nowMillis + 900,
            elapsedRealtimeMillis = 100_000L + 500,
            processStartElapsedRealtimeMillis = 40_000L + 999,
        )
        assertTrue(truncated <= 1_000_040L + 1)
    }

    @Test
    fun `completed journal rows are always adopted`() {
        PendingProbe.entries.forEach { probe ->
            assertEquals(
                OrphanDisposition.ADOPT,
                orphanDisposition(PendingJournalState.COMPLETE, probe),
            )
        }
    }

    @Test
    fun `valid legacy or registered rows are adopted`() {
        assertEquals(
            OrphanDisposition.ADOPT,
            orphanDisposition(PendingJournalState.UNKNOWN, PendingProbe.VALID),
        )
        assertEquals(
            OrphanDisposition.ADOPT,
            orphanDisposition(PendingJournalState.REGISTERED, PendingProbe.VALID),
        )
    }

    @Test
    fun `only structurally invalid unfinished rows are deleted`() {
        assertEquals(
            OrphanDisposition.DELETE,
            orphanDisposition(PendingJournalState.REGISTERED, PendingProbe.INVALID),
        )
        assertEquals(
            OrphanDisposition.DELETE,
            orphanDisposition(PendingJournalState.UNKNOWN, PendingProbe.INVALID),
        )
        assertEquals(
            OrphanDisposition.KEEP_PENDING,
            orphanDisposition(PendingJournalState.REGISTERED, PendingProbe.INDETERMINATE),
        )
        assertEquals(
            OrphanDisposition.KEEP_PENDING,
            orphanDisposition(PendingJournalState.UNKNOWN, PendingProbe.INDETERMINATE),
        )
    }

    @Test
    fun `DNG strip values parse conservatively`() {
        assertEquals(listOf(8L, 16L), parseUnsignedExifValues("8, 16"))
        assertEquals(listOf(7L), parseUnsignedExifValues("7/1, -1, bad"))
        assertTrue(parseUnsignedExifValues(null).isEmpty())
    }

    @Test
    fun `unjournaled HEIF requires structural ISO BMFF proof`() {
        assertEquals(
            PendingMediaProbeKind.HEIF,
            pendingMediaProbeKind("image/heif", isVideoCollection = false),
        )
        assertEquals(
            OrphanDisposition.KEEP_PENDING,
            orphanDisposition(PendingJournalState.REGISTERED, PendingProbe.INDETERMINATE),
        )
    }

    @Test
    fun `delete zero is success only when an existence probe proves the row absent`() {
        assertEquals(
            MediaDeleteDisposition.ALREADY_ABSENT,
            mediaDeleteDisposition(deleteCount = 0, rowExistsAfter = false),
        )
        assertEquals(
            MediaDeleteDisposition.FAILED,
            mediaDeleteDisposition(deleteCount = 0, rowExistsAfter = true),
        )
        assertEquals(
            MediaDeleteDisposition.FAILED,
            mediaDeleteDisposition(deleteCount = null, rowExistsAfter = null),
        )
        assertEquals(
            MediaDeleteDisposition.DELETED,
            mediaDeleteDisposition(deleteCount = 1, rowExistsAfter = null),
        )
    }
}
