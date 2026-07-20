package com.hletrd.findx9tele.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDurabilityPolicyTest {

    @Test
    fun `completion marker retries boundedly and exposes exhaustion`() {
        var calls = 0
        val backoffs = mutableListOf<Int>()
        val recovered = markCompletionWithRetry(
            maxAttempts = 3,
            commit = { ++calls == 3 },
            backoff = backoffs::add,
        )
        assertTrue(recovered.durable)
        assertEquals(3, recovered.attempts)
        assertEquals(listOf(1, 2), backoffs)

        val exhausted = markCompletionWithRetry(maxAttempts = 3, commit = { false })
        assertFalse(exhausted.durable)
        assertEquals(3, exhausted.attempts)
    }

    @Test
    fun `HEIF primary item with a supported in-mdat extent is structurally valid`() {
        assertEquals(PendingProbe.VALID, probe(locatedHeif()))
        assertEquals(
            PendingProbe.VALID,
            probe(
                locatedHeif(
                    primaryItemId = 70_000,
                    locationItemId = 70_000,
                    pitmVersion = 1,
                    ilocVersion = 2,
                ),
            ),
        )
    }

    @Test
    fun `missing or mismatched HEIF primary-location metadata is invalid`() {
        val boxedGarbage =
            box("ftyp", "heic\u0000\u0000\u0000\u0000mif1".toByteArray()) +
                box("meta", ByteArray(4)) +
                box("mdat", byteArrayOf(1, 2, 3, 4))
        val cases = mapOf(
            "boxed garbage" to boxedGarbage,
            "missing pitm" to locatedHeif(includePitm = false),
            "missing iloc" to locatedHeif(includeIloc = false),
            "primary absent from iloc" to locatedHeif(primaryItemId = 2),
            "zero primary id" to locatedHeif(primaryItemId = 0, locationItemId = 0),
        )

        cases.forEach { (name, bytes) ->
            assertEquals(name, PendingProbe.INVALID, probe(bytes))
        }
    }

    @Test
    fun `malformed and out-of-range HEIF primary extents are invalid`() {
        val cases = mapOf(
            "extent points into ftyp" to locatedHeif(extentOffset = 0),
            "extent runs past file" to locatedHeif(extentOffset = 0xffff_fff0L, extentLength = 32),
            "missing mdat" to locatedHeif(includeMdat = false),
            "nonzero reserved construction bits" to locatedHeif(ilocVersion = 1, constructionMethod = 0x10),
        )

        cases.forEach { (name, bytes) ->
            assertEquals(name, PendingProbe.INVALID, probe(bytes))
        }
        val complete = locatedHeif()
        assertEquals(PendingProbe.INVALID, probe(complete.copyOf(complete.size - 1)))
    }

    @Test
    fun `unsupported HEIF versions and construction methods remain pending`() {
        val cases = mapOf(
            "meta version" to locatedHeif(metaVersion = 1),
            "pitm version" to locatedHeif(pitmVersion = 2),
            "iloc version" to locatedHeif(ilocVersion = 3),
            "idat construction" to locatedHeif(ilocVersion = 1, constructionMethod = 1),
            "external data reference" to locatedHeif(dataReferenceIndex = 1),
            "whole-source zero length" to locatedHeif(extentLength = 0),
        )

        cases.forEach { (name, bytes) ->
            assertEquals(name, PendingProbe.INDETERMINATE, probe(bytes))
            assertEquals(
                name,
                OrphanDisposition.KEEP_PENDING,
                orphanDisposition(PendingJournalState.REGISTERED, probe(bytes)),
            )
        }
    }

    @Test
    fun `unreadable and unbounded HEIF structures remain pending`() {
        val complete = locatedHeif()
        assertEquals(
            PendingProbe.INDETERMINATE,
            probeHeifIsoBmff(complete.size.toLong()) { _, _ -> null },
        )
        val unboundedMdat = complete.copyOf().apply {
            val mdatOffset = size - 12
            fill(0, mdatOffset, mdatOffset + 4)
        }
        assertEquals(PendingProbe.INDETERMINATE, probe(unboundedMdat))
    }

    @Test
    fun `probe access failure is retained as an explicit retryable error`() {
        val outcome = pendingProbeOutcome { throw java.io.IOException("provider unavailable") }

        assertEquals(PendingProbe.INDETERMINATE, outcome.probe)
        assertTrue(outcome.failed)
        val report = RecoveryReport()
            .record(RecoveryEvent.SCANNED)
            .record(RecoveryEvent.PROBE_FAILED)
            .record(RecoveryEvent.RETAINED)
        assertEquals(1, report.retained)
        assertEquals(1, report.errors)
        assertEquals(setOf(RecoveryFailureClass.PROBE), report.failureClasses)
        assertTrue(report.retryRequired)
    }

    @Test
    fun `semantic HEIF indeterminacy is retained without becoming a retryable probe error`() {
        val unsupported = locatedHeif(ilocVersion = 1, constructionMethod = 1)
        val outcome = pendingProbeOutcome { probe(unsupported) }

        assertEquals(PendingProbe.INDETERMINATE, outcome.probe)
        assertFalse(outcome.failed)
        assertEquals(
            OrphanDisposition.KEEP_PENDING,
            orphanDisposition(PendingJournalState.REGISTERED, outcome.probe),
        )
    }

    @Test
    fun `failed marker plus failed publish is explicit and a later restart can adopt`() {
        val marker = markCompletionWithRetry(maxAttempts = 3, commit = { false })
        assertFalse(marker.durable)
        val structuralProbe = probe(locatedHeif())
        assertEquals(
            OrphanDisposition.ADOPT,
            orphanDisposition(PendingJournalState.REGISTERED, structuralProbe),
        )

        val failedPublish = RecoveryReport()
            .record(RecoveryEvent.SCANNED)
            .record(RecoveryEvent.PUBLISH_FAILED)
        assertEquals(1, failedPublish.scanned)
        assertEquals(1, failedPublish.retained)
        assertEquals(1, failedPublish.errors)
        assertEquals(setOf(RecoveryFailureClass.PUBLISH), failedPublish.failureClasses)
        assertTrue(failedPublish.retryRequired)

        val nextRestart = RecoveryReport()
            .record(RecoveryEvent.SCANNED)
            .record(RecoveryEvent.ADOPTED)
        assertEquals(1, nextRestart.adopted)
        assertFalse(nextRestart.retryRequired)
    }

    @Test
    fun `mixed collection recovery preserves successes and sanitizes failures`() {
        val report = RecoveryReport()
            .record(RecoveryEvent.QUERY_FAILED)
            .record(RecoveryEvent.SCANNED)
            .record(RecoveryEvent.ADOPTED)
            .record(RecoveryEvent.SCANNED)
            .record(RecoveryEvent.DELETE_FAILED)
            .record(RecoveryEvent.SCANNED)
            .record(RecoveryEvent.RETAINED)

        assertEquals(3, report.scanned)
        assertEquals(1, report.adopted)
        assertEquals(0, report.deleted)
        assertEquals(2, report.retained)
        assertEquals(2, report.errors)
        assertEquals(
            setOf(RecoveryFailureClass.QUERY, RecoveryFailureClass.DELETE),
            report.failureClasses,
        )
    }

    @Test
    fun `provider recovery retry is bounded and exhaustion remains observable`() {
        val failed = RecoveryReport().record(RecoveryEvent.QUERY_FAILED)
        assertEquals(RecoveryRetryDecision.RETRY, recoveryRetryDecision(failed, 1, 3))
        assertEquals(RecoveryRetryDecision.RETRY, recoveryRetryDecision(failed, 2, 3))
        assertEquals(RecoveryRetryDecision.EXHAUSTED, recoveryRetryDecision(failed, 3, 3))
        assertEquals(
            RecoveryRetryDecision.COMPLETE,
            recoveryRetryDecision(RecoveryReport(), 1, 3),
        )
    }

    @Test
    fun `recovery fold preserves transition counts but a clean retry resolves failures`() {
        val failedAttempt = RecoveryReport()
            .record(RecoveryEvent.SCANNED)
            .record(RecoveryEvent.ADOPTED)
            .record(RecoveryEvent.QUERY_FAILED)
        val cleanAttempt = RecoveryReport()
            .record(RecoveryEvent.SCANNED)
            .record(RecoveryEvent.DELETED)

        val cumulative = RecoveryReport()
            .foldRecoveryAttempt(failedAttempt)
            .foldRecoveryAttempt(cleanAttempt)

        assertEquals(2, cumulative.scanned)
        assertEquals(1, cumulative.adopted)
        assertEquals(1, cumulative.deleted)
        assertEquals(1, cumulative.errors)
        assertTrue(cumulative.failureClasses.isEmpty())
        assertFalse(cumulative.retryRequired)
        assertEquals(
            RecoveryRetryDecision.COMPLETE,
            recoveryRetryDecision(cleanAttempt, completedAttempts = 2, maxAttempts = 3),
        )
    }

    private fun probe(bytes: ByteArray): PendingProbe = probeHeifIsoBmff(bytes.size.toLong()) { offset, count ->
        val start = offset.toInt()
        if (start < 0 || start + count > bytes.size) null else bytes.copyOfRange(start, start + count)
    }

    /** Minimal supported HEIF structure: ftyp + meta(pitm, iloc) + referenced mdat bytes. */
    private fun locatedHeif(
        primaryItemId: Long = 1,
        locationItemId: Long = 1,
        extentOffset: Long? = null,
        extentLength: Long = 4,
        metaVersion: Int = 0,
        pitmVersion: Int = 0,
        ilocVersion: Int = 0,
        constructionMethod: Int = 0,
        dataReferenceIndex: Int = 0,
        includePitm: Boolean = true,
        includeIloc: Boolean = true,
        includeMdat: Boolean = true,
    ): ByteArray {
        val ftyp = box("ftyp", "heic\u0000\u0000\u0000\u0000mif1".toByteArray())
        fun meta(primaryExtentOffset: Long): ByteArray {
            val children = buildList {
                if (includePitm) {
                    val itemId = if (pitmVersion == 0) u16(primaryItemId) else u32(primaryItemId)
                    add(box("pitm", fullBox(pitmVersion, itemId)))
                }
                if (includeIloc) {
                    val itemCount = if (ilocVersion < 2) u16(1) else u32(1)
                    val itemId = if (ilocVersion < 2) u16(locationItemId) else u32(locationItemId)
                    val construction = if (ilocVersion == 0) byteArrayOf() else u16(constructionMethod.toLong())
                    val ilocPayload = fullBox(
                        ilocVersion,
                        byteArrayOf(0x44, 0x00) +
                            itemCount +
                            itemId +
                            construction +
                            u16(dataReferenceIndex.toLong()) +
                            u16(1) +
                            u32(primaryExtentOffset) +
                            u32(extentLength),
                    )
                    add(box("iloc", ilocPayload))
                }
            }.fold(byteArrayOf()) { accumulated, child -> accumulated + child }
            return box("meta", fullBox(metaVersion, children))
        }

        val provisionalMeta = meta(0)
        val mdatPayloadOffset = ftyp.size.toLong() + provisionalMeta.size + 8L
        val finalMeta = meta(extentOffset ?: mdatPayloadOffset)
        val mdat = if (includeMdat) box("mdat", byteArrayOf(1, 2, 3, 4)) else byteArrayOf()
        return ftyp + finalMeta + mdat
    }

    private fun fullBox(version: Int, payload: ByteArray): ByteArray =
        byteArrayOf(version.toByte(), 0, 0, 0) + payload

    private fun u16(value: Long): ByteArray = byteArrayOf(
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun u32(value: Long): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun box(type: String, payload: ByteArray): ByteArray {
        require(type.length == 4)
        val size = payload.size + 8
        return byteArrayOf(
            (size ushr 24).toByte(),
            (size ushr 16).toByte(),
            (size ushr 8).toByte(),
            size.toByte(),
        ) + type.toByteArray(Charsets.US_ASCII) + payload
    }
}
