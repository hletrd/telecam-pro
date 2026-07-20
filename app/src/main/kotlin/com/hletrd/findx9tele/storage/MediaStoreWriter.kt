package com.hletrd.findx9tele.storage

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Thin wrapper around MediaStore for saving photos/videos into DCIM/<subDir> using the
 * pending-file convention (IS_PENDING) so files don't appear to other apps until fully written.
 */
object MediaStoreWriter {

    private const val MAX_RESTORE_ROWS_PER_COLLECTION = 64
    private const val MAX_FAMILY_ROWS = 8
    private const val PENDING_JOURNAL = "pending_media_journal"
    private const val PENDING_REGISTERED = "registered"
    private const val PENDING_COMPLETE = "complete"
    private const val COMPLETION_MARK_ATTEMPTS = 3
    private const val COMPLETION_MARK_BACKOFF_MS = 25L

    /**
     * Reconstructs the newest published capture THIS APP saved under its own folder.
     *
     * Images and Video are separate MediaStore collections, so each query is bounded and the pure
     * reducer compares their results. Versioned filenames prove sibling identity; legacy files stay
     * one-file delete scopes instead of being grouped by timestamp proximity.
     */
    internal fun latestOwnCapture(
        context: Context,
        subDir: String = "X9Tele",
    ): RestoredCapture<Uri>? {
        val imageRows = queryOwnedPublished(
            context = context,
            base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            collection = StoredMediaCollection.IMAGE,
            subDir = subDir,
        )
        val videoRows = queryOwnedPublished(
            context = context,
            base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            collection = StoredMediaCollection.VIDEO,
            subDir = subDir,
        )
        // Media providers can fault one collection independently. Preserve every successful result
        // so a transient Images failure cannot hide the latest video (or vice versa).
        val initial = restoreLatestCaptureFromQueryResults(imageRows, videoRows) ?: return null
        val familyKey = initial.familyKey ?: return initial

        // The broad queries find the winner. One exact, bounded follow-up prevents their row limits
        // from ever omitting an older sibling of that winning family from whole-capture deletion.
        val familyCollection = when (familyKey.media) {
            CaptureFamilyMedia.STILL -> StoredMediaCollection.IMAGE
            CaptureFamilyMedia.VIDEO -> StoredMediaCollection.VIDEO
        }
        val familyBase = when (familyCollection) {
            StoredMediaCollection.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            StoredMediaCollection.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        return queryOwnedPublished(
            context = context,
            base = familyBase,
            collection = familyCollection,
            subDir = subDir,
            displayNames = familyKey.knownOutputDisplayNames(),
            limit = MAX_FAMILY_ROWS,
        ).fold(
            // An EMPTY exact result gets the same FILE_ONLY fallback as a failed query (CR4-11):
            // between the broad query that found the winner and this bounded follow-up, another
            // app can delete/re-pend those rows — dropping the whole restore over that TOCTOU
            // discarded a review file that still exists on disk. The two branches must agree.
            onSuccess = { exactRows ->
                restoreLatestCapture(exactRows) ?: RestoredCapture(
                    preferred = initial.preferred,
                    outputs = listOf(initial.preferred),
                    familyKey = null,
                    deleteScope = RestoredDeleteScope.FILE_ONLY,
                )
            },
            // A failed family expansion cannot safely promise capture-level deletion. Retain only
            // the already-resolved review file and make the fallback contract explicit.
            onFailure = {
                RestoredCapture(
                    preferred = initial.preferred,
                    outputs = listOf(initial.preferred),
                    familyKey = null,
                    deleteScope = RestoredDeleteScope.FILE_ONLY,
                )
            },
        )
    }

    private fun queryOwnedPublished(
        context: Context,
        base: Uri,
        collection: StoredMediaCollection,
        subDir: String,
        displayNames: List<String>? = null,
        limit: Int = MAX_RESTORE_ROWS_PER_COLLECTION,
    ): Result<List<StoredMediaRow<Uri>>> = runCatching {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.IS_PENDING,
        )
        val queryArgs = Bundle().apply {
            val nameSelection = displayNames
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(prefix = " AND ${MediaStore.MediaColumns.DISPLAY_NAME} IN (", postfix = ")") { "?" }
                .orEmpty()
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND " +
                    "${MediaStore.MediaColumns.IS_PENDING} = ? AND " +
                    "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?" + nameSelection,
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                buildList {
                    add("DCIM/$subDir/")
                    add("0")
                    add(context.packageName)
                    displayNames?.let(::addAll)
                }.toTypedArray(),
            )
            putString(
                ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                "${MediaStore.MediaColumns.DATE_TAKEN} DESC, " +
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC, " +
                    "${MediaStore.MediaColumns._ID} DESC",
            )
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        context.contentResolver.query(base, projection, queryArgs, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val takenColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val addedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val pendingColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    add(
                        StoredMediaRow(
                            output = ContentUris.withAppendedId(base, id),
                            collection = collection,
                            rowId = id,
                            displayName = cursor.getString(nameColumn),
                            mimeType = cursor.getString(mimeColumn),
                            dateTakenEpochMillis = if (cursor.isNull(takenColumn)) null else cursor.getLong(takenColumn),
                            dateAddedEpochSeconds = cursor.getLong(addedColumn),
                            dateModifiedEpochSeconds = cursor.getLong(modifiedColumn),
                            isPending = cursor.getInt(pendingColumn) != 0,
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    fun createPendingImage(
        context: Context,
        displayName: String,
        mimeType: String,
        subDir: String = "X9Tele",
    ): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/$subDir")
            put(MediaStore.Images.Media.IS_PENDING, 1)
            CaptureFamilyKey.parse(displayName)?.familyKey?.capturedAtEpochMillis?.let {
                put(MediaStore.Images.Media.DATE_TAKEN, it)
            }
        }
        val uri = runCatching {
            context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return null
        return registerPending(context, uri)
    }

    fun createPendingVideo(
        context: Context,
        displayName: String,
        mimeType: String,
        subDir: String = "X9Tele",
    ): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, mimeType)
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/$subDir")
            put(MediaStore.Video.Media.IS_PENDING, 1)
            CaptureFamilyKey.parse(displayName)?.familyKey?.capturedAtEpochMillis?.let {
                put(MediaStore.Video.Media.DATE_TAKEN, it)
            }
        }
        val uri = runCatching {
            context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return null
        return registerPending(context, uri)
    }

    /**
     * Durably records that all bytes/container metadata for [uri] were finalized. Call this after
     * the encoder/muxer has stopped and the output has been closed, but before [publish]. A launch
     * recovery can then distinguish a valuable complete take from an interrupted write without
     * guessing from file size alone.
     */
    internal fun markWriteComplete(context: Context, uri: Uri): CompletionMarkResult =
        markCompletionWithRetry(
            maxAttempts = COMPLETION_MARK_ATTEMPTS,
            commit = {
                context.getSharedPreferences(PENDING_JOURNAL, Context.MODE_PRIVATE)
                    .edit()
                    .putString(uri.toString(), PENDING_COMPLETE)
                    .commit()
            },
            backoff = { attempt ->
                runCatching { Thread.sleep(COMPLETION_MARK_BACKOFF_MS * attempt) }
            },
        )

    fun openParcelFd(context: Context, uri: Uri, mode: String = "rw"): ParcelFileDescriptor? =
        runCatching { context.contentResolver.openFileDescriptor(uri, mode) }.getOrNull()

    fun openOutputStream(context: Context, uri: Uri): OutputStream? =
        runCatching { context.contentResolver.openOutputStream(uri) }.getOrNull()

    /**
     * Clears IS_PENDING so the file becomes visible to other apps (e.g. the gallery).
     *
     * Retries a transient resolver failure a few times with a short backoff (CRIT4-5): a complete
     * artifact must not be stranded pending — and later deleted by the next launch's
     * [cleanupOrphanedPending] sweep — over a one-off provider hiccup. Callers run on background
     * executors (ioExecutor / recorderExecutor), so the bounded sleep never blocks the UI. A
     * persistent failure still returns false; launch recovery returns an observable report and
     * retries complete or structurally proven rows instead of silently deleting them.
     */
    fun publish(context: Context, uri: Uri): Boolean {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        repeat(PUBLISH_ATTEMPTS) { attempt ->
            val published = runCatching { context.contentResolver.update(uri, values, null, null) > 0 }
                .getOrDefault(false)
            if (published) {
                clearPending(context, uri)
                return true
            }
            if (attempt < PUBLISH_ATTEMPTS - 1) {
                runCatching { Thread.sleep(PUBLISH_RETRY_BACKOFF_MS * (attempt + 1)) }
            }
        }
        return false
    }

    private const val PUBLISH_ATTEMPTS = 3
    private const val PUBLISH_RETRY_BACKOFF_MS = 50L

    /**
     * True when the requested media is gone after the operation. A resolver delete count of zero
     * is ambiguous (already absent vs. provider failure), so probe the exact URI before reporting a
     * failure. This keeps asynchronous family-delete reconciliation from restoring a stale URI as a
     * broken review thumbnail merely because another app removed it first.
     */
    fun delete(context: Context, uri: Uri): Boolean {
        val deleteCount = runCatching { context.contentResolver.delete(uri, null, null) }.getOrNull()
        val rowExistsAfter = if ((deleteCount ?: 0) > 0) {
            null
        } else {
            mediaRowExists(context, uri)
        }
        val disposition = mediaDeleteDisposition(deleteCount, rowExistsAfter)
        if (disposition != MediaDeleteDisposition.FAILED) clearPending(context, uri)
        return disposition != MediaDeleteDisposition.FAILED
    }

    /** Null means the provider could not answer; false is an authoritative already-absent row. */
    private fun mediaRowExists(context: Context, uri: Uri): Boolean? = runCatching {
        val queryArgs = Bundle().apply {
            // delete() is also used for not-yet-published cleanup; include an owned pending row so
            // an empty default query cannot be mistaken for authoritative absence.
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns._ID),
            queryArgs,
            null,
        )?.use { cursor -> cursor.moveToFirst() }
    }.getOrNull()

    /**
     * Recovers our own prior-process pending entries under DCIM/[subDir]. A complete take is adopted
     * by publishing it; only a proven incomplete artifact is deleted. Indeterminate rows remain
     * pending for a later launch rather than risking silent data loss. Best-effort; never throws.
     */
    internal fun cleanupOrphanedPending(context: Context, subDir: String = "X9Tele"): RecoveryReport {
        // Only sweep entries created BEFORE this process: the launch-time sweep runs on the setup
        // executor while an immediate first capture creates its own pending entry on ioExecutor —
        // without the age gate the sweep could delete that in-flight write (two-executor race on
        // shared MediaStore state; an orphan from a prior crash is by definition older than us).
        val processStartSecs = processStartEpochSecs(
            nowMillis = System.currentTimeMillis(),
            elapsedRealtimeMillis = android.os.SystemClock.elapsedRealtime(),
            processStartElapsedRealtimeMillis = android.os.Process.getStartElapsedRealtime(),
        )
        // Selection/args construction is pure and PINNED BY TEST (OrphanSweepTest). Each collection
        // is independently caught and reported, so a broken Images query cannot suppress Video.
        val (selection, args) = orphanSweepSelection(subDir, context.packageName, processStartSecs)
        var report = RecoveryReport()
        for (base in listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        )) {
            val collectionResult = runCatching {
                val queryArgs = Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                    // Pending items are hidden from ordinary queries even for the owner; opt in.
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                }
                val cursor = context.contentResolver.query(
                    base,
                    arrayOf(
                        MediaStore.MediaColumns._ID,
                        MediaStore.MediaColumns.IS_PENDING,
                        MediaStore.MediaColumns.MIME_TYPE,
                        MediaStore.MediaColumns.SIZE,
                    ),
                    queryArgs,
                    null,
                ) ?: error("MediaProvider returned no cursor")
                cursor.use {
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val pendingCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    while (cursor.moveToNext()) {
                        if (cursor.getInt(pendingCol) != 1) continue
                        report = report.record(RecoveryEvent.SCANNED)
                        val uri = ContentUris.withAppendedId(base, cursor.getLong(idCol))
                        val journalState = pendingJournalState(context, uri)
                        val sizeBytes = if (cursor.isNull(sizeCol)) 0L else cursor.getLong(sizeCol)
                        val probeOutcome = when {
                            sizeBytes <= 0L -> PendingProbeOutcome(PendingProbe.INVALID)
                            journalState == PendingJournalState.COMPLETE -> PendingProbeOutcome(PendingProbe.VALID)
                            else -> probePendingMedia(
                                context = context,
                                uri = uri,
                                mimeType = cursor.getString(mimeCol).orEmpty(),
                                isVideoCollection = base == MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                                sizeBytes = sizeBytes,
                            )
                        }
                        if (probeOutcome.failed) {
                            report = report.record(RecoveryEvent.PROBE_FAILED)
                        }
                        when (orphanDisposition(journalState, probeOutcome.probe)) {
                            OrphanDisposition.ADOPT -> {
                                report = report.record(
                                    if (publish(context, uri)) RecoveryEvent.ADOPTED
                                    else RecoveryEvent.PUBLISH_FAILED,
                                )
                            }
                            OrphanDisposition.DELETE -> {
                                report = report.record(
                                    if (delete(context, uri)) RecoveryEvent.DELETED
                                    else RecoveryEvent.DELETE_FAILED,
                                )
                            }
                            OrphanDisposition.KEEP_PENDING -> report = report.record(RecoveryEvent.RETAINED)
                        }
                    }
                }
            }
            if (collectionResult.isFailure) report = report.record(RecoveryEvent.QUERY_FAILED)
        }
        return report
    }

    private fun registerPending(context: Context, uri: Uri): Uri? {
        val registered = context.getSharedPreferences(PENDING_JOURNAL, Context.MODE_PRIVATE)
            .edit()
            .putString(uri.toString(), PENDING_REGISTERED)
            .commit()
        if (registered) return uri
        runCatching { context.contentResolver.delete(uri, null, null) }
        return null
    }

    private fun pendingJournalState(context: Context, uri: Uri): PendingJournalState =
        when (context.getSharedPreferences(PENDING_JOURNAL, Context.MODE_PRIVATE).getString(uri.toString(), null)) {
            PENDING_COMPLETE -> PendingJournalState.COMPLETE
            PENDING_REGISTERED -> PendingJournalState.REGISTERED
            else -> PendingJournalState.UNKNOWN
        }

    private fun clearPending(context: Context, uri: Uri) {
        context.getSharedPreferences(PENDING_JOURNAL, Context.MODE_PRIVATE)
            .edit()
            .remove(uri.toString())
            .commit()
    }

    private fun probePendingMedia(
        context: Context,
        uri: Uri,
        mimeType: String,
        isVideoCollection: Boolean,
        sizeBytes: Long,
    ): PendingProbeOutcome = pendingProbeOutcome {
        when (pendingMediaProbeKind(mimeType, isVideoCollection)) {
            PendingMediaProbeKind.VIDEO -> probeFinalizedVideo(context, uri)
            PendingMediaProbeKind.JPEG -> probeCompleteJpeg(context, uri, sizeBytes)
            PendingMediaProbeKind.DNG -> probeCompleteDng(context, uri, sizeBytes)
            PendingMediaProbeKind.HEIF -> probeCompleteHeif(context, uri)
            PendingMediaProbeKind.KEEP_PENDING -> PendingProbe.INDETERMINATE
        }
    }

    private fun probeFinalizedVideo(context: Context, uri: Uri): PendingProbe {
        val extractor = MediaExtractor()
        return try {
            val pfd = openReadableParcelFd(context, uri)
            pfd.use { descriptor ->
                extractor.setDataSource(descriptor.fileDescriptor)
                if ((0 until extractor.trackCount).any { index ->
                        extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                    }
                ) {
                    PendingProbe.VALID
                } else {
                    PendingProbe.INVALID
                }
            }
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** Reopens a closed recording and requires an extractor-readable video track. */
    internal fun hasReadableVideoTrack(context: Context, uri: Uri): Boolean =
        runCatching { probeFinalizedVideo(context, uri) == PendingProbe.VALID }.getOrDefault(false)

    private fun probeCompleteHeif(context: Context, uri: Uri): PendingProbe {
        val pfd = openReadableParcelFd(context, uri)
        return pfd.use {
            FileInputStream(it.fileDescriptor).use { input ->
                val channel = input.channel
                probeHeifIsoBmff(channel.size()) { offset, byteCount ->
                    val buffer = ByteBuffer.allocate(byteCount)
                    channel.position(offset)
                    while (buffer.hasRemaining()) {
                        if (channel.read(buffer) <= 0) return@probeHeifIsoBmff null
                    }
                    buffer.array()
                }
            }
        }
    }

    private fun probeCompleteJpeg(context: Context, uri: Uri, sizeBytes: Long): PendingProbe {
        if (sizeBytes < 4L) return PendingProbe.INVALID
        val pfd = openReadableParcelFd(context, uri)
        return pfd.use {
            FileInputStream(it.fileDescriptor).use { input ->
                val channel = input.channel
                if (channel.size() < 4L) return@use PendingProbe.INVALID
                channel.position(channel.size() - 2L)
                val tail = ByteArray(2)
                if (input.read(tail) == 2 && tail[0] == 0xff.toByte() && tail[1] == 0xd9.toByte()) {
                    PendingProbe.VALID
                } else {
                    PendingProbe.INVALID
                }
            }
        }
    }

    private fun probeCompleteDng(context: Context, uri: Uri, sizeBytes: Long): PendingProbe {
        val pfd = openReadableParcelFd(context, uri)
        return pfd.use {
            val exif = androidx.exifinterface.media.ExifInterface(it.fileDescriptor)
            val width = exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_IMAGE_WIDTH, 0)
            val height = exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_IMAGE_LENGTH, 0)
            val version = exif.getAttributeBytes(androidx.exifinterface.media.ExifInterface.TAG_DNG_VERSION)
            val offsets = parseUnsignedExifValues(
                exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_STRIP_OFFSETS),
            )
            val counts = parseUnsignedExifValues(
                exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_STRIP_BYTE_COUNTS),
            )
            val stripsFit = offsets.isNotEmpty() && offsets.size == counts.size &&
                offsets.zip(counts).all { (offset, count) -> count > 0L && offset <= sizeBytes - count }
            if (width > 0 && height > 0 && version != null && version.isNotEmpty() && stripsFit) {
                PendingProbe.VALID
            } else {
                PendingProbe.INVALID
            }
        }
    }

    /** A queried row that cannot be reopened is a provider/probe error, not quiet indeterminacy. */
    private fun openReadableParcelFd(context: Context, uri: Uri): ParcelFileDescriptor =
        context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("MediaProvider returned no file descriptor")

}

internal data class PendingProbeOutcome(
    val probe: PendingProbe,
    val failed: Boolean = false,
)

/** Converts access/parser exceptions into an explicit retained probe failure for launch recovery. */
internal fun pendingProbeOutcome(probe: () -> PendingProbe): PendingProbeOutcome {
    val result = runCatching(probe)
    return PendingProbeOutcome(
        probe = result.getOrDefault(PendingProbe.INDETERMINATE),
        failed = result.isFailure,
    )
}

internal data class CompletionMarkResult(
    val durable: Boolean,
    val attempts: Int,
)

/** Bounded durable-marker policy with injected seams for commit-failure tests. */
internal fun markCompletionWithRetry(
    maxAttempts: Int,
    commit: () -> Boolean,
    backoff: (attempt: Int) -> Unit = {},
): CompletionMarkResult {
    require(maxAttempts > 0)
    repeat(maxAttempts) { zeroBasedAttempt ->
        val attempt = zeroBasedAttempt + 1
        if (runCatching(commit).getOrDefault(false)) {
            return CompletionMarkResult(durable = true, attempts = attempt)
        }
        if (attempt < maxAttempts) backoff(attempt)
    }
    return CompletionMarkResult(durable = false, attempts = maxAttempts)
}

internal enum class RecoveryFailureClass { QUERY, PROBE, PUBLISH, DELETE }

internal data class RecoveryReport(
    val scanned: Int = 0,
    val adopted: Int = 0,
    val deleted: Int = 0,
    val retained: Int = 0,
    val errors: Int = 0,
    val failureClasses: Set<RecoveryFailureClass> = emptySet(),
) {
    val retryRequired: Boolean
        get() = failureClasses.isNotEmpty()

    internal fun record(event: RecoveryEvent): RecoveryReport = when (event) {
        RecoveryEvent.SCANNED -> copy(scanned = scanned + 1)
        RecoveryEvent.ADOPTED -> copy(adopted = adopted + 1)
        RecoveryEvent.DELETED -> copy(deleted = deleted + 1)
        RecoveryEvent.RETAINED -> copy(retained = retained + 1)
        RecoveryEvent.QUERY_FAILED -> failed(RecoveryFailureClass.QUERY, retain = false)
        RecoveryEvent.PROBE_FAILED -> failed(RecoveryFailureClass.PROBE, retain = false)
        RecoveryEvent.PUBLISH_FAILED -> failed(RecoveryFailureClass.PUBLISH, retain = true)
        RecoveryEvent.DELETE_FAILED -> failed(RecoveryFailureClass.DELETE, retain = true)
    }

    private fun failed(failure: RecoveryFailureClass, retain: Boolean): RecoveryReport = copy(
        retained = retained + if (retain) 1 else 0,
        errors = errors + 1,
        failureClasses = failureClasses + failure,
    )

    /**
     * Adds durable transition counts while making the newest attempt the sole owner of unresolved
     * failures. A clean retry therefore completes even though the terminal summary retains the
     * earlier attempt's error count for truthful diagnostics.
     */
    internal fun foldRecoveryAttempt(attempt: RecoveryReport): RecoveryReport = RecoveryReport(
        scanned = scanned + attempt.scanned,
        adopted = adopted + attempt.adopted,
        deleted = deleted + attempt.deleted,
        retained = retained + attempt.retained,
        errors = errors + attempt.errors,
        failureClasses = attempt.failureClasses,
    )
}

internal enum class RecoveryEvent {
    SCANNED,
    ADOPTED,
    DELETED,
    RETAINED,
    QUERY_FAILED,
    PROBE_FAILED,
    PUBLISH_FAILED,
    DELETE_FAILED,
}

internal enum class RecoveryRetryDecision { COMPLETE, RETRY, EXHAUSTED }

/** One-based bounded launch-recovery retry decision, kept pure for provider-failure matrices. */
internal fun recoveryRetryDecision(
    report: RecoveryReport,
    completedAttempts: Int,
    maxAttempts: Int,
): RecoveryRetryDecision = when {
    !report.retryRequired -> RecoveryRetryDecision.COMPLETE
    completedAttempts < maxAttempts.coerceAtLeast(1) -> RecoveryRetryDecision.RETRY
    else -> RecoveryRetryDecision.EXHAUSTED
}

internal enum class MediaDeleteDisposition { DELETED, ALREADY_ABSENT, FAILED }

/** Pure resolver-result reduction used by [MediaStoreWriter.delete]. */
internal fun mediaDeleteDisposition(
    deleteCount: Int?,
    rowExistsAfter: Boolean?,
): MediaDeleteDisposition = when {
    deleteCount != null && deleteCount > 0 -> MediaDeleteDisposition.DELETED
    rowExistsAfter == false -> MediaDeleteDisposition.ALREADY_ABSENT
    else -> MediaDeleteDisposition.FAILED
}

internal enum class PendingMediaProbeKind { VIDEO, JPEG, DNG, HEIF, KEEP_PENDING }

/**
 * Only containers with a conservative terminal-structure probe may bridge a missing COMPLETE
 * marker. HEIF requires a supported primary-item location whose explicit extents are wholly inside
 * bounded mdat payloads; header image dimensions or top-level box presence are never accepted.
 */
internal fun pendingMediaProbeKind(
    mimeType: String,
    isVideoCollection: Boolean,
): PendingMediaProbeKind = when {
    isVideoCollection -> PendingMediaProbeKind.VIDEO
    mimeType.equals("image/jpeg", ignoreCase = true) -> PendingMediaProbeKind.JPEG
    mimeType.contains("dng", ignoreCase = true) -> PendingMediaProbeKind.DNG
    mimeType.equals("image/heif", ignoreCase = true) ||
        mimeType.equals("image/heic", ignoreCase = true) -> PendingMediaProbeKind.HEIF
    else -> PendingMediaProbeKind.KEEP_PENDING
}

internal enum class PendingJournalState { UNKNOWN, REGISTERED, COMPLETE }

internal enum class PendingProbe { VALID, INVALID, INDETERMINATE }

internal enum class OrphanDisposition { ADOPT, DELETE, KEEP_PENDING }

/** Pure conservative launch-recovery decision; an unknown answer never destroys user media. */
internal fun orphanDisposition(
    journalState: PendingJournalState,
    probe: PendingProbe,
): OrphanDisposition = when {
    journalState == PendingJournalState.COMPLETE -> OrphanDisposition.ADOPT
    probe == PendingProbe.VALID -> OrphanDisposition.ADOPT
    probe == PendingProbe.INVALID -> OrphanDisposition.DELETE
    else -> OrphanDisposition.KEEP_PENDING
}

/**
 * Structural HEIF completion probe. Reads bounded ISO-BMFF metadata only, never pixel data. A valid
 * result requires a HEIF brand, one supported primary item, and every explicit primary-item extent
 * to resolve wholly inside an mdat payload. Malformed or out-of-range structures are INVALID;
 * unreadable bytes, unbounded boxes, and unsupported meta/pitm/iloc variants are INDETERMINATE so
 * recovery retains the private row instead of risking deletion.
 */
internal fun probeHeifIsoBmff(
    fileSize: Long,
    readAt: (offset: Long, byteCount: Int) -> ByteArray?,
): PendingProbe {
    if (fileSize < 24L) return PendingProbe.INVALID
    var offset = 0L
    var boxCount = 0
    var foundFtyp = false
    var metaBox: HeifIsoBox? = null
    val mdatPayloads = mutableListOf<HeifByteRange>()

    while (offset < fileSize) {
        if (++boxCount > MAX_HEIF_BOXES) return PendingProbe.INDETERMINATE
        val box = when (val parsed = readHeifIsoBox(offset, fileSize, readAt)) {
            is HeifParse.Success -> parsed.value
            is HeifParse.Failure -> return parsed.probe
        }
        when (box.type) {
            "ftyp" -> {
                when (val brands = hasHeifBrand(box, readAt)) {
                    is HeifParse.Success -> foundFtyp = foundFtyp || brands.value
                    is HeifParse.Failure -> return brands.probe
                }
            }
            "meta" -> {
                if (metaBox != null) return PendingProbe.INVALID
                metaBox = box
            }
            "mdat" -> {
                if (box.payloadSize <= 0L) return PendingProbe.INVALID
                mdatPayloads += HeifByteRange(box.payloadOffset, box.payloadSize)
            }
        }
        offset += box.size
    }

    if (offset != fileSize || !foundFtyp || metaBox == null || mdatPayloads.isEmpty()) {
        return PendingProbe.INVALID
    }
    val primaryExtents = when (val parsed = parseHeifMeta(metaBox, readAt)) {
        is HeifParse.Success -> parsed.value
        is HeifParse.Failure -> return parsed.probe
    }
    return if (primaryExtents.all { extent ->
            extent.length > 0L &&
                extent.offset <= fileSize - extent.length &&
                mdatPayloads.any { payload -> extent.isWhollyInside(payload) }
        }
    ) PendingProbe.VALID else PendingProbe.INVALID
}

private const val MAX_HEIF_BOXES = 4_096
private const val MAX_HEIF_ITEMS = 4_096
private const val MAX_HEIF_EXTENTS = 4_096
private const val MAX_HEIF_FTYP_BYTES = 4_096

private val HEIF_BRANDS = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1")

private sealed interface HeifParse<out T> {
    data class Success<T>(val value: T) : HeifParse<T>
    data class Failure(val probe: PendingProbe) : HeifParse<Nothing>
}

private data class HeifIsoBox(
    val type: String,
    val offset: Long,
    val size: Long,
    val headerSize: Long,
) {
    val payloadOffset: Long get() = offset + headerSize
    val payloadSize: Long get() = size - headerSize
    val endOffset: Long get() = offset + size
}

private data class HeifByteRange(val offset: Long, val length: Long) {
    fun isWhollyInside(container: HeifByteRange): Boolean {
        if (offset < container.offset) return false
        val relativeOffset = offset - container.offset
        return relativeOffset <= container.length && length <= container.length - relativeOffset
    }
}

private fun readHeifIsoBox(
    offset: Long,
    parentEnd: Long,
    readAt: (offset: Long, byteCount: Int) -> ByteArray?,
): HeifParse<HeifIsoBox> {
    if (offset < 0L || parentEnd < offset || parentEnd - offset < 8L) {
        return HeifParse.Failure(PendingProbe.INVALID)
    }
    val header = readAt(offset, 8)
        ?: return HeifParse.Failure(PendingProbe.INDETERMINATE)
    if (header.size != 8) return HeifParse.Failure(PendingProbe.INDETERMINATE)
    val size32 = ByteBuffer.wrap(header, 0, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffff_ffffL
    val type = String(header, 4, 4, Charsets.US_ASCII)
    val headerSize: Long
    val boxSize: Long
    when (size32) {
        // Although ISO-BMFF defines zero as "through parent/EOF", a crash-truncated final box has
        // the same shape. The recovery probe deliberately declines to decide.
        0L -> return HeifParse.Failure(PendingProbe.INDETERMINATE)
        1L -> {
            if (parentEnd - offset < 16L) return HeifParse.Failure(PendingProbe.INVALID)
            val extended = readAt(offset + 8L, 8)
                ?: return HeifParse.Failure(PendingProbe.INDETERMINATE)
            if (extended.size != 8) return HeifParse.Failure(PendingProbe.INDETERMINATE)
            boxSize = ByteBuffer.wrap(extended).order(ByteOrder.BIG_ENDIAN).long
            if (boxSize < 0L) return HeifParse.Failure(PendingProbe.INVALID)
            headerSize = 16L
        }
        else -> {
            boxSize = size32
            headerSize = 8L
        }
    }
    if (boxSize < headerSize || boxSize > parentEnd - offset) {
        return HeifParse.Failure(PendingProbe.INVALID)
    }
    return HeifParse.Success(HeifIsoBox(type, offset, boxSize, headerSize))
}

private fun hasHeifBrand(
    box: HeifIsoBox,
    readAt: (offset: Long, byteCount: Int) -> ByteArray?,
): HeifParse<Boolean> {
    if (box.payloadSize < 8L || (box.payloadSize - 8L) % 4L != 0L) {
        return HeifParse.Failure(PendingProbe.INVALID)
    }
    if (box.payloadSize > MAX_HEIF_FTYP_BYTES) {
        return HeifParse.Failure(PendingProbe.INDETERMINATE)
    }
    val bytes = readAt(box.payloadOffset, box.payloadSize.toInt())
        ?: return HeifParse.Failure(PendingProbe.INDETERMINATE)
    if (bytes.size != box.payloadSize.toInt()) {
        return HeifParse.Failure(PendingProbe.INDETERMINATE)
    }
    val brands = buildList {
        add(String(bytes, 0, 4, Charsets.US_ASCII))
        var offset = 8
        while (offset + 4 <= bytes.size) {
            add(String(bytes, offset, 4, Charsets.US_ASCII))
            offset += 4
        }
    }
    return HeifParse.Success(brands.any(HEIF_BRANDS::contains))
}

private fun parseHeifMeta(
    meta: HeifIsoBox,
    readAt: (offset: Long, byteCount: Int) -> ByteArray?,
): HeifParse<List<HeifByteRange>> {
    if (meta.payloadSize < 4L) return HeifParse.Failure(PendingProbe.INVALID)
    val fullBox = when (val parsed = readHeifUnsigned(meta.payloadOffset, 4, meta.endOffset, readAt)) {
        is HeifParse.Success -> parsed.value
        is HeifParse.Failure -> return parsed
    }
    val version = (fullBox ushr 24).toInt()
    val flags = fullBox and 0x00ff_ffffL
    if (version != 0) return HeifParse.Failure(PendingProbe.INDETERMINATE)
    if (flags != 0L) return HeifParse.Failure(PendingProbe.INVALID)

    var childOffset = meta.payloadOffset + 4L
    var childCount = 0
    var pitm: HeifIsoBox? = null
    var iloc: HeifIsoBox? = null
    while (childOffset < meta.endOffset) {
        if (++childCount > MAX_HEIF_BOXES) return HeifParse.Failure(PendingProbe.INDETERMINATE)
        val child = when (val parsed = readHeifIsoBox(childOffset, meta.endOffset, readAt)) {
            is HeifParse.Success -> parsed.value
            is HeifParse.Failure -> return parsed
        }
        when (child.type) {
            "pitm" -> {
                if (pitm != null) return HeifParse.Failure(PendingProbe.INVALID)
                pitm = child
            }
            "iloc" -> {
                if (iloc != null) return HeifParse.Failure(PendingProbe.INVALID)
                iloc = child
            }
        }
        childOffset += child.size
    }
    if (childOffset != meta.endOffset || pitm == null || iloc == null) {
        return HeifParse.Failure(PendingProbe.INVALID)
    }
    val primaryItemId = when (val parsed = parsePrimaryItemId(pitm, readAt)) {
        is HeifParse.Success -> parsed.value
        is HeifParse.Failure -> return parsed
    }
    return parsePrimaryItemExtents(iloc, primaryItemId, readAt)
}

private fun parsePrimaryItemId(
    pitm: HeifIsoBox,
    readAt: (offset: Long, byteCount: Int) -> ByteArray?,
): HeifParse<Long> {
    if (pitm.payloadSize < 4L) return HeifParse.Failure(PendingProbe.INVALID)
    val fullBox = when (val parsed = readHeifUnsigned(pitm.payloadOffset, 4, pitm.endOffset, readAt)) {
        is HeifParse.Success -> parsed.value
        is HeifParse.Failure -> return parsed
    }
    val version = (fullBox ushr 24).toInt()
    val flags = fullBox and 0x00ff_ffffL
    if (version !in 0..1) return HeifParse.Failure(PendingProbe.INDETERMINATE)
    if (flags != 0L) return HeifParse.Failure(PendingProbe.INVALID)
    val itemIdSize = if (version == 0) 2 else 4
    if (pitm.payloadSize != 4L + itemIdSize) return HeifParse.Failure(PendingProbe.INVALID)
    return when (val parsed = readHeifUnsigned(pitm.payloadOffset + 4L, itemIdSize, pitm.endOffset, readAt)) {
        is HeifParse.Success -> if (parsed.value != 0L) parsed else HeifParse.Failure(PendingProbe.INVALID)
        is HeifParse.Failure -> parsed
    }
}

private fun parsePrimaryItemExtents(
    iloc: HeifIsoBox,
    primaryItemId: Long,
    readAt: (offset: Long, byteCount: Int) -> ByteArray?,
): HeifParse<List<HeifByteRange>> {
    val reader = HeifBoundedReader(iloc.payloadOffset, iloc.endOffset, readAt)
    val fullBox = reader.readUnsigned(4) ?: return HeifParse.Failure(reader.failure!!)
    val version = (fullBox ushr 24).toInt()
    val flags = fullBox and 0x00ff_ffffL
    if (version !in 0..2) return HeifParse.Failure(PendingProbe.INDETERMINATE)
    if (flags != 0L) return HeifParse.Failure(PendingProbe.INVALID)

    val sizePair = reader.readUnsigned(1)?.toInt()
        ?: return HeifParse.Failure(reader.failure!!)
    val baseAndIndexPair = reader.readUnsigned(1)?.toInt()
        ?: return HeifParse.Failure(reader.failure!!)
    val offsetSize = sizePair ushr 4
    val lengthSize = sizePair and 0x0f
    val baseOffsetSize = baseAndIndexPair ushr 4
    val indexSize = if (version == 0) 0 else baseAndIndexPair and 0x0f
    if (version == 0 && baseAndIndexPair and 0x0f != 0) {
        return HeifParse.Failure(PendingProbe.INVALID)
    }
    val legalFieldSizes = setOf(0, 4, 8)
    if (offsetSize !in legalFieldSizes || lengthSize !in legalFieldSizes ||
        baseOffsetSize !in legalFieldSizes || indexSize !in legalFieldSizes
    ) {
        return HeifParse.Failure(PendingProbe.INVALID)
    }

    val itemCount = reader.readUnsigned(if (version < 2) 2 else 4)
        ?: return HeifParse.Failure(reader.failure!!)
    if (itemCount > MAX_HEIF_ITEMS) return HeifParse.Failure(PendingProbe.INDETERMINATE)

    var primaryFound = false
    var totalExtents = 0L
    val primaryExtents = mutableListOf<HeifByteRange>()
    repeat(itemCount.toInt()) {
        val itemId = reader.readUnsigned(if (version < 2) 2 else 4)
            ?: return HeifParse.Failure(reader.failure!!)
        val constructionMethod = if (version == 0) {
            0
        } else {
            val construction = reader.readUnsigned(2)?.toInt()
                ?: return HeifParse.Failure(reader.failure!!)
            if (construction and 0xfff0 != 0) return HeifParse.Failure(PendingProbe.INVALID)
            construction and 0x0f
        }
        val dataReferenceIndex = reader.readUnsigned(2)
            ?: return HeifParse.Failure(reader.failure!!)
        val isPrimary = itemId == primaryItemId
        if (isPrimary && primaryFound) return HeifParse.Failure(PendingProbe.INVALID)
        if (isPrimary && constructionMethod != 0) {
            return HeifParse.Failure(PendingProbe.INDETERMINATE)
        }
        if (isPrimary && dataReferenceIndex != 0L) {
            return HeifParse.Failure(PendingProbe.INDETERMINATE)
        }
        val baseOffset = if (isPrimary) {
            reader.readUnsigned(baseOffsetSize) ?: return HeifParse.Failure(reader.failure!!)
        } else {
            if (!reader.skip(baseOffsetSize)) return HeifParse.Failure(reader.failure!!)
            0L
        }
        val extentCount = reader.readUnsigned(2)
            ?: return HeifParse.Failure(reader.failure!!)
        totalExtents += extentCount
        if (totalExtents > MAX_HEIF_EXTENTS) return HeifParse.Failure(PendingProbe.INDETERMINATE)
        if (extentCount == 0L) return HeifParse.Failure(PendingProbe.INVALID)
        if (isPrimary && lengthSize == 0) return HeifParse.Failure(PendingProbe.INDETERMINATE)

        repeat(extentCount.toInt()) {
            if (!reader.skip(indexSize)) return HeifParse.Failure(reader.failure!!)
            if (isPrimary) {
                val extentOffset = reader.readUnsigned(offsetSize)
                    ?: return HeifParse.Failure(reader.failure!!)
                val extentLength = reader.readUnsigned(lengthSize)
                    ?: return HeifParse.Failure(reader.failure!!)
                // A zero length means "the whole source" in ISO-BMFF. It is valid syntax, but not
                // an explicit crash-safe bound, so keep the row pending rather than adopting it.
                if (extentLength == 0L) return HeifParse.Failure(PendingProbe.INDETERMINATE)
                if (baseOffset > Long.MAX_VALUE - extentOffset) {
                    return HeifParse.Failure(PendingProbe.INVALID)
                }
                primaryExtents += HeifByteRange(baseOffset + extentOffset, extentLength)
            } else {
                if (!reader.skip(offsetSize + lengthSize)) return HeifParse.Failure(reader.failure!!)
            }
        }
        if (isPrimary) primaryFound = true
    }
    if (reader.cursor != iloc.endOffset) return HeifParse.Failure(PendingProbe.INVALID)
    return if (primaryFound && primaryExtents.isNotEmpty()) {
        HeifParse.Success(primaryExtents)
    } else {
        HeifParse.Failure(PendingProbe.INVALID)
    }
}

private class HeifBoundedReader(
    start: Long,
    private val end: Long,
    private val readAt: (offset: Long, byteCount: Int) -> ByteArray?,
) {
    var cursor: Long = start
        private set
    var failure: PendingProbe? = null
        private set

    fun readUnsigned(byteCount: Int): Long? {
        if (failure != null) return null
        if (byteCount !in 0..8) {
            failure = PendingProbe.INDETERMINATE
            return null
        }
        if (byteCount == 0) return 0L
        if (cursor < 0L || end < cursor || byteCount.toLong() > end - cursor) {
            failure = PendingProbe.INVALID
            return null
        }
        val bytes = readAt(cursor, byteCount)
        if (bytes == null || bytes.size != byteCount) {
            failure = PendingProbe.INDETERMINATE
            return null
        }
        var value = 0L
        for (byte in bytes) {
            val unsigned = byte.toInt() and 0xff
            if (value > (Long.MAX_VALUE - unsigned) / 256L) {
                failure = PendingProbe.INVALID
                return null
            }
            value = value * 256L + unsigned
        }
        cursor += byteCount
        return value
    }

    fun skip(byteCount: Int): Boolean {
        if (failure != null) return false
        if (byteCount < 0 || cursor < 0L || end < cursor || byteCount.toLong() > end - cursor) {
            failure = PendingProbe.INVALID
            return false
        }
        cursor += byteCount
        return true
    }
}

private fun readHeifUnsigned(
    offset: Long,
    byteCount: Int,
    end: Long,
    readAt: (offset: Long, byteCount: Int) -> ByteArray?,
): HeifParse<Long> {
    val reader = HeifBoundedReader(offset, end, readAt)
    val value = reader.readUnsigned(byteCount)
    return if (value != null) HeifParse.Success(value) else HeifParse.Failure(reader.failure!!)
}

internal fun parseUnsignedExifValues(raw: String?): List<Long> = raw
    ?.split(',')
    ?.mapNotNull { token -> token.trim().substringBefore('/').toLongOrNull()?.takeIf { it >= 0L } }
    .orEmpty()

/**
 * Epoch-seconds moment this process started: wall-clock "now" rolled back by the time elapsed
 * since boot, plus the process-start elapsed-realtime stamp. Mixes the two clocks deliberately —
 * DATE_ADDED is epoch seconds, but "before this process" is an elapsed-realtime fact. Integer
 * division truncates up to ~1 s conservative (fewer deletions), which is the safe direction for
 * a delete sweep.
 */
internal fun processStartEpochSecs(
    nowMillis: Long,
    elapsedRealtimeMillis: Long,
    processStartElapsedRealtimeMillis: Long,
): Long = (nowMillis - elapsedRealtimeMillis) / 1000 + processStartElapsedRealtimeMillis / 1000

/**
 * The orphan-sweep delete predicate, as a pure pair so OrphanSweepTest can pin it:
 * - RELATIVE_PATH values are normalized with a trailing slash ("DCIM/X9Tele/"), so the pattern
 *   anchors on it — "DCIM/X9Tele%" would also sweep a hypothetical "DCIM/X9TeleOther/".
 * - OWNER_PACKAGE_NAME makes ownership an EXPLICIT invariant (mirroring queryOwnedPublished):
 *   scoped storage without READ_MEDIA_* already hides other apps' rows today, but a future
 *   media-read permission would otherwise silently widen this delete sweep to any app's pending
 *   items under a same-named DCIM path.
 * - Placeholder count must equal the arg count; a drifted edit fails the test, not silently
 *   inside the sweep's runCatching.
 */
internal fun orphanSweepSelection(
    subDir: String,
    packageName: String,
    cutoffEpochSecs: Long,
): Pair<String, Array<String>> {
    val selection =
        "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DATE_ADDED} < ? AND " +
            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?"
    return selection to arrayOf("DCIM/$subDir/%", cutoffEpochSecs.toString(), packageName)
}
