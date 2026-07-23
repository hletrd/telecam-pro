package com.hletrd.findx9tele.storage

import com.hletrd.findx9tele.camera.MediaRecoveryCompletion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
    fun `only known container mimes may bridge a missing COMPLETE marker`() {
        assertEquals(PendingMediaProbeKind.VIDEO, pendingMediaProbeKind("video/mp4", isVideoCollection = true))
        assertEquals(PendingMediaProbeKind.JPEG, pendingMediaProbeKind("image/JPEG", isVideoCollection = false))
        assertEquals(PendingMediaProbeKind.DNG, pendingMediaProbeKind("image/x-adobe-dng", isVideoCollection = false))
        assertEquals(PendingMediaProbeKind.HEIF, pendingMediaProbeKind("image/HEIC", isVideoCollection = false))
        // An unrecognized mime has no conservative structural probe — the row must stay pending
        // rather than risk adopting (or deleting) something this app cannot validate.
        assertEquals(PendingMediaProbeKind.KEEP_PENDING, pendingMediaProbeKind("image/webp", isVideoCollection = false))
        assertEquals(PendingMediaProbeKind.KEEP_PENDING, pendingMediaProbeKind("", isVideoCollection = false))
    }

    @Test
    fun `probe outcome defaults to a non-failed probe`() {
        assertFalse(PendingProbeOutcome(PendingProbe.VALID).failed)
    }

    @Test
    fun `a 64-bit largesize mdat box is parsed and still probes valid`() {
        assertEquals(PendingProbe.VALID, probe(locatedHeif(mdatLargesize = true)))
    }

    @Test
    fun `malformed 64-bit largesize boxes are invalid`() {
        // Fewer than 16 bytes remain for the extended header.
        val truncatedLargesize = box("ftyp", "heic0000mif1".toByteArray()) +
            u32(1) + "mdat".toByteArray(Charsets.US_ASCII) + byteArrayOf(1, 2, 3, 4)
        assertEquals(PendingProbe.INVALID, probe(truncatedLargesize))

        // A negative 64-bit size can never bound a real payload.
        val negativeLargesize = box("ftyp", "heic0000mif1".toByteArray()) +
            u32(1) + "mdat".toByteArray(Charsets.US_ASCII) + u64(-1L) + byteArrayOf(1, 2, 3, 4)
        assertEquals(PendingProbe.INVALID, probe(negativeLargesize))
    }

    @Test
    fun `unreadable 64-bit largesize extension remains pending`() {
        val bytes = locatedHeif(mdatLargesize = true)
        val extendedSizeOffset = boxOffset(bytes, "mdat") + 8L
        assertEquals(PendingProbe.INDETERMINATE, probe(bytes, nullAt = extendedSizeOffset))
        assertEquals(PendingProbe.INDETERMINATE, probe(bytes, shortAt = extendedSizeOffset))
    }

    @Test
    fun `trailing bytes too small for any box header are invalid`() {
        val bytes = box("ftyp", "heic0000mif1".toByteArray()) + byteArrayOf(0, 0, 0, 0)
        assertEquals(PendingProbe.INVALID, probe(bytes))
    }

    @Test
    fun `an unreadable byte at every parse stage keeps the row pending, never invalid`() {
        // Each stage's read failure must stay INDETERMINATE: the bytes may be fine and only the
        // provider read failed, so recovery retains the row instead of destroying user media.
        val bytes = locatedHeif()
        val stages = mapOf(
            "second box header" to boxOffset(bytes, "meta"),
            "ftyp payload" to 8L,
            "meta fullbox" to boxOffset(bytes, "meta") + 8L,
            "pitm fullbox" to boxOffset(bytes, "pitm") + 8L,
            "iloc fields" to boxOffset(bytes, "iloc") + 8L,
        )

        stages.forEach { (name, offset) ->
            assertEquals(name, PendingProbe.INDETERMINATE, probe(bytes, nullAt = offset))
            assertEquals("$name (short read)", PendingProbe.INDETERMINATE, probe(bytes, shortAt = offset))
        }
    }

    @Test
    fun `malformed and oversized ftyp brand boxes resolve conservatively`() {
        // Structurally impossible brand payloads are INVALID...
        assertEquals(PendingProbe.INVALID, probe(box("ftyp", ByteArray(6)) + box("mdat", byteArrayOf(1))))
        assertEquals(PendingProbe.INVALID, probe(box("ftyp", ByteArray(10)) + box("mdat", byteArrayOf(1))))
        // ...an absurdly large but well-formed one only refuses to decide...
        assertEquals(PendingProbe.INDETERMINATE, probe(box("ftyp", ByteArray(4100))))
        // ...and well-formed non-HEIF brands leave the whole file invalid (no HEIF brand found).
        assertEquals(
            PendingProbe.INVALID,
            probe(
                box("ftyp", "abcd0000efgh".toByteArray()) +
                    box("meta", ByteArray(4)) + box("mdat", byteArrayOf(1, 2, 3, 4)),
            ),
        )
    }

    @Test
    fun `iloc field-size abuse is invalid`() {
        // Version 0 reserves the index-size nibble: nonzero bits mean a writer this probe does
        // not understand well enough to trust its offsets.
        assertEquals(
            PendingProbe.INVALID,
            probe(rawIlocHeif(fullBox(0, byteArrayOf(0x44, 0x04) + u16(1) + u16(1) + u16(0) + u16(1) + u32(0) + u32(4)))),
        )
        // Field sizes outside {0,4,8} bytes are illegal ISO-BMFF.
        assertEquals(
            PendingProbe.INVALID,
            probe(rawIlocHeif(fullBox(0, byteArrayOf(0x24, 0x00) + u16(1) + u16(1) + u16(0) + u16(1) + u32(0) + u32(4)))),
        )
    }

    @Test
    fun `iloc extent arithmetic overflow is invalid`() {
        // base_offset_size = 8 (0x80): base + extent offset overflowing Long can never be a real
        // in-file location.
        val overflowingBase = fullBox(
            0,
            byteArrayOf(0x44, 0x80.toByte()) + u16(1) + u16(1) + u16(0) +
                u64(Long.MAX_VALUE) + u16(1) + u32(1) + u32(4),
        )
        assertEquals(PendingProbe.INVALID, probe(rawIlocHeif(overflowingBase)))

        // An unsigned 64-bit base that does not even fit a Long trips the bounded reader itself.
        val unrepresentableBase = fullBox(
            0,
            byteArrayOf(0x44, 0x80.toByte()) + u16(1) + u16(1) + u16(0) +
                ByteArray(8) { 0xff.toByte() } + u16(1) + u32(1) + u32(4),
        )
        assertEquals(PendingProbe.INVALID, probe(rawIlocHeif(unrepresentableBase)))
    }

    @Test
    fun `an iloc payload that ends mid-parse is invalid`() {
        // Declares two items but carries bytes for one: the bounded reader runs off the box end.
        val truncatedItems = fullBox(
            0,
            byteArrayOf(0x44, 0x00) + u16(2) + u16(1) + u16(0) + u16(1) + u32(0) + u32(4),
        )
        assertEquals(PendingProbe.INVALID, probe(rawIlocHeif(truncatedItems)))

        // The second (non-primary) item is cut off right where its base offset should be skipped.
        val truncatedSkip = fullBox(
            0,
            byteArrayOf(0x44, 0x80.toByte()) + u16(2) +
                u16(1) + u16(0) + u64(0) + u16(1) + u32(0) + u32(4) +
                u16(2) + u16(0),
        )
        assertEquals(PendingProbe.INVALID, probe(rawIlocHeif(truncatedSkip)))
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
    fun `engine recovery completion carries report attempt and decision as one packet`() {
        // The engine publishes one immutable completion snapshot per cleanupOrphans pass; the
        // decision must ride WITH the exact report/attempt pair that produced it.
        val report = RecoveryReport()
            .record(RecoveryEvent.SCANNED)
            .record(RecoveryEvent.PUBLISH_FAILED)
        val completion = MediaRecoveryCompletion(
            report = report,
            attempts = 2,
            decision = recoveryRetryDecision(report, 2, 3),
        )

        assertSame(report, completion.report)
        assertEquals(2, completion.attempts)
        assertEquals(RecoveryRetryDecision.RETRY, completion.decision)
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

    private fun probe(
        bytes: ByteArray,
        // Fault injection: readAt returns null (unreadable) or one byte short (torn read) when a
        // read STARTS at exactly this offset — pinpointing one parse stage per test case.
        nullAt: Long? = null,
        shortAt: Long? = null,
    ): PendingProbe = probeHeifIsoBmff(bytes.size.toLong()) { offset, count ->
        val start = offset.toInt()
        when {
            offset == nullAt -> null
            start < 0 || start + count > bytes.size -> null
            offset == shortAt -> bytes.copyOfRange(start, start + count - 1)
            else -> bytes.copyOfRange(start, start + count)
        }
    }

    /** Offset of the box whose 4-byte type string is [type] (the size field, not the type). */
    private fun boxOffset(bytes: ByteArray, type: String): Long {
        val needle = type.toByteArray(Charsets.US_ASCII)
        for (index in 4..bytes.size - needle.size) {
            if (needle.indices.all { bytes[index + it] == needle[it] }) return index - 4L
        }
        error("box $type not found")
    }

    /** ftyp + meta(pitm id=1, iloc with a caller-crafted payload) + mdat, for iloc edge cases. */
    private fun rawIlocHeif(ilocPayload: ByteArray): ByteArray =
        box("ftyp", "heic0000mif1".toByteArray()) +
            box("meta", fullBox(0, box("pitm", fullBox(0, u16(1))) + box("iloc", ilocPayload))) +
            box("mdat", byteArrayOf(1, 2, 3, 4))

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
        mdatLargesize: Boolean = false,
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
        val mdatHeaderSize = if (mdatLargesize) 16L else 8L
        val mdatPayloadOffset = ftyp.size.toLong() + provisionalMeta.size + mdatHeaderSize
        val finalMeta = meta(extentOffset ?: mdatPayloadOffset)
        val mdat = when {
            !includeMdat -> byteArrayOf()
            mdatLargesize -> box64("mdat", byteArrayOf(1, 2, 3, 4))
            else -> box("mdat", byteArrayOf(1, 2, 3, 4))
        }
        return ftyp + finalMeta + mdat
    }

    private fun fullBox(version: Int, payload: ByteArray): ByteArray =
        byteArrayOf(version.toByte(), 0, 0, 0) + payload

    private fun u16(value: Long): ByteArray = byteArrayOf(
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun u64(value: Long): ByteArray = ByteArray(8) { index ->
        (value ushr ((7 - index) * 8)).toByte()
    }

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

    /** ISO-BMFF 64-bit box: size32 == 1 sentinel + the real size in a trailing largesize field. */
    private fun box64(type: String, payload: ByteArray): ByteArray {
        require(type.length == 4)
        return u32(1) + type.toByteArray(Charsets.US_ASCII) + u64(payload.size + 16L) + payload
    }
}
