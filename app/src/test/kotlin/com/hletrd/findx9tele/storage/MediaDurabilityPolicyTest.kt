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
    fun `complete HEIF box extents and required boxes are structurally valid`() {
        val bytes = heifBytes()
        assertEquals(PendingProbe.VALID, probe(bytes))
    }

    @Test
    fun `truncated missing and unreadable HEIF structures never adopt`() {
        val complete = heifBytes()
        assertEquals(PendingProbe.INVALID, probe(complete.copyOf(complete.size - 1)))
        assertEquals(
            PendingProbe.INVALID,
            probe(box("ftyp", "heic\u0000\u0000\u0000\u0000mif1".toByteArray()) + box("meta", ByteArray(4))),
        )
        assertEquals(
            PendingProbe.INDETERMINATE,
            probeHeifIsoBmff(complete.size.toLong()) { _, _ -> null },
        )
        val unboundedMdat =
            box("ftyp", "heic\u0000\u0000\u0000\u0000mif1".toByteArray()) +
                box("meta", ByteArray(4)) +
                sizeZeroBox("mdat", byteArrayOf(1))
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
    fun `failed marker plus failed publish is explicit and a later restart can adopt`() {
        val marker = markCompletionWithRetry(maxAttempts = 3, commit = { false })
        assertFalse(marker.durable)
        val structuralProbe = probe(heifBytes())
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

    private fun probe(bytes: ByteArray): PendingProbe = probeHeifIsoBmff(bytes.size.toLong()) { offset, count ->
        val start = offset.toInt()
        if (start < 0 || start + count > bytes.size) null else bytes.copyOfRange(start, start + count)
    }

    private fun heifBytes(): ByteArray =
        box("ftyp", "heic\u0000\u0000\u0000\u0000mif1".toByteArray()) +
            box("meta", ByteArray(4)) +
            box("mdat", byteArrayOf(1, 2, 3, 4))

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

    private fun sizeZeroBox(type: String, payload: ByteArray): ByteArray {
        require(type.length == 4)
        return byteArrayOf(0, 0, 0, 0) + type.toByteArray(Charsets.US_ASCII) + payload
    }
}
