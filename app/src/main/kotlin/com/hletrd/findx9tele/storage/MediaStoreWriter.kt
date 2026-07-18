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
import java.io.OutputStream
import java.io.FileInputStream

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
    fun markWriteComplete(context: Context, uri: Uri): Boolean =
        context.getSharedPreferences(PENDING_JOURNAL, Context.MODE_PRIVATE)
            .edit()
            .putString(uri.toString(), PENDING_COMPLETE)
            .commit()

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
     * persistent failure still returns false; there is NO republish path afterwards — the pending
     * file is invisible until the sweep deletes it.
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
    fun cleanupOrphanedPending(context: Context, subDir: String = "X9Tele") {
        // Only sweep entries created BEFORE this process: the launch-time sweep runs on the setup
        // executor while an immediate first capture creates its own pending entry on ioExecutor —
        // without the age gate the sweep could delete that in-flight write (two-executor race on
        // shared MediaStore state; an orphan from a prior crash is by definition older than us).
        val processStartSecs = processStartEpochSecs(
            nowMillis = System.currentTimeMillis(),
            elapsedRealtimeMillis = android.os.SystemClock.elapsedRealtime(),
            processStartElapsedRealtimeMillis = android.os.Process.getStartElapsedRealtime(),
        )
        // Selection/args construction is pure and PINNED BY TEST (OrphanSweepTest): the whole call
        // below is runCatching-wrapped, so a placeholder/arg-count mismatch or a broken path anchor
        // would otherwise fail as a silently-swallowed SQLiteException — the sweep no-ops forever
        // with no crash, no log, and no test failure.
        val (selection, args) = orphanSweepSelection(subDir, context.packageName, processStartSecs)
        for (base in listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        )) {
            runCatching {
                val queryArgs = Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                    // Pending items are hidden from ordinary queries even for the owner; opt in.
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                }
                context.contentResolver.query(
                    base,
                    arrayOf(
                        MediaStore.MediaColumns._ID,
                        MediaStore.MediaColumns.IS_PENDING,
                        MediaStore.MediaColumns.MIME_TYPE,
                        MediaStore.MediaColumns.SIZE,
                    ),
                    queryArgs,
                    null,
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val pendingCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    while (cursor.moveToNext()) {
                        if (cursor.getInt(pendingCol) != 1) continue
                        val uri = ContentUris.withAppendedId(base, cursor.getLong(idCol))
                        val journalState = pendingJournalState(context, uri)
                        val sizeBytes = if (cursor.isNull(sizeCol)) 0L else cursor.getLong(sizeCol)
                        val probe = when {
                            sizeBytes <= 0L -> PendingProbe.INVALID
                            journalState == PendingJournalState.COMPLETE -> PendingProbe.VALID
                            else -> probePendingMedia(
                                context = context,
                                uri = uri,
                                mimeType = cursor.getString(mimeCol).orEmpty(),
                                isVideoCollection = base == MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                                sizeBytes = sizeBytes,
                            )
                        }
                        when (orphanDisposition(journalState, probe)) {
                            OrphanDisposition.ADOPT -> publish(context, uri)
                            OrphanDisposition.DELETE -> delete(context, uri)
                            OrphanDisposition.KEEP_PENDING -> Unit
                        }
                    }
                }
            }
        }
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
    ): PendingProbe = runCatching {
        when (pendingMediaProbeKind(mimeType, isVideoCollection)) {
            PendingMediaProbeKind.VIDEO -> probeFinalizedVideo(context, uri)
            PendingMediaProbeKind.JPEG -> probeCompleteJpeg(context, uri, sizeBytes)
            PendingMediaProbeKind.DNG -> probeCompleteDng(context, uri, sizeBytes)
            // HEIF/unknown image containers can expose dimensions before their payload is fully
            // closed. A bounds-only decode therefore cannot prove completion. Without a durable
            // COMPLETE marker, retain the private pending row instead of publishing corrupt media.
            PendingMediaProbeKind.KEEP_PENDING -> PendingProbe.INDETERMINATE
        }
    }.getOrDefault(PendingProbe.INDETERMINATE)

    private fun probeFinalizedVideo(context: Context, uri: Uri): PendingProbe {
        val extractor = MediaExtractor()
        return try {
            val pfd = openParcelFd(context, uri, "r") ?: return PendingProbe.INDETERMINATE
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

    private fun probeCompleteJpeg(context: Context, uri: Uri, sizeBytes: Long): PendingProbe {
        if (sizeBytes < 4L) return PendingProbe.INVALID
        val pfd = openParcelFd(context, uri, "r") ?: return PendingProbe.INDETERMINATE
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
        val pfd = openParcelFd(context, uri, "r") ?: return PendingProbe.INDETERMINATE
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

internal enum class PendingMediaProbeKind { VIDEO, JPEG, DNG, KEEP_PENDING }

/**
 * Only containers with a conservative terminal-structure probe may bridge a missing COMPLETE
 * marker. In particular, HEIF remains pending: header dimensions do not prove its payload closed.
 */
internal fun pendingMediaProbeKind(
    mimeType: String,
    isVideoCollection: Boolean,
): PendingMediaProbeKind = when {
    isVideoCollection -> PendingMediaProbeKind.VIDEO
    mimeType.equals("image/jpeg", ignoreCase = true) -> PendingMediaProbeKind.JPEG
    mimeType.contains("dng", ignoreCase = true) -> PendingMediaProbeKind.DNG
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
