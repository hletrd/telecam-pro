package com.hletrd.findx9tele.storage

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.OutputStream

/**
 * Thin wrapper around MediaStore for saving photos/videos into DCIM/<subDir> using the
 * pending-file convention (IS_PENDING) so files don't appear to other apps until fully written.
 */
object MediaStoreWriter {

    private const val MAX_RESTORE_ROWS_PER_COLLECTION = 64
    private const val MAX_FAMILY_ROWS = 8

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
            onSuccess = { exactRows -> restoreLatestCapture(exactRows) },
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
        return runCatching {
            context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull()
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
        return runCatching {
            context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull()
    }

    fun openParcelFd(context: Context, uri: Uri, mode: String = "rw"): ParcelFileDescriptor? =
        runCatching { context.contentResolver.openFileDescriptor(uri, mode) }.getOrNull()

    fun openOutputStream(context: Context, uri: Uri): OutputStream? =
        runCatching { context.contentResolver.openOutputStream(uri) }.getOrNull()

    /** Clears IS_PENDING so the file becomes visible to other apps (e.g. the gallery). */
    fun publish(context: Context, uri: Uri): Boolean {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        return runCatching { context.contentResolver.update(uri, values, null, null) > 0 }
            .getOrDefault(false)
    }

    /** True only when the resolver confirms at least one row was removed. */
    fun delete(context: Context, uri: Uri): Boolean =
        runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)

    /**
     * Deletes our own leftover pending (IS_PENDING=1) entries under DCIM/[subDir] — orphans from a
     * prior crash / force-kill where [publish]/[delete] never ran (a recording whose MediaMuxer was
     * never stopped, leaving a corrupt, invisible 0-byte-ish file). Safe to run on launch: the normal
     * capture/record paths publish or delete synchronously, so nothing of ours is legitimately pending
     * at startup, and scoped storage only exposes our own entries. Best-effort; never throws.
     */
    fun cleanupOrphanedPending(context: Context, subDir: String = "X9Tele") {
        // Only sweep entries created BEFORE this process: the launch-time sweep runs on the setup
        // executor while an immediate first capture creates its own pending entry on ioExecutor —
        // without the age gate the sweep could delete that in-flight write (two-executor race on
        // shared MediaStore state; an orphan from a prior crash is by definition older than us).
        val processStartSecs =
            (System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()) / 1000 +
                android.os.Process.getStartElapsedRealtime() / 1000
        val selection =
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DATE_ADDED} < ? AND " +
                "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?"
        // RELATIVE_PATH values are normalized with a trailing slash ("DCIM/X9Tele/"), so anchor the
        // pattern on it — "DCIM/X9Tele%" would also sweep a hypothetical "DCIM/X9TeleOther/".
        // OWNER_PACKAGE_NAME makes ownership an EXPLICIT invariant (mirroring queryOwnedPublished):
        // today scoped storage without READ_MEDIA_* already hides other apps' rows, but a future
        // media-read permission would otherwise silently widen this delete sweep to any app's
        // pending items under a same-named DCIM path.
        val args = arrayOf("DCIM/$subDir/%", processStartSecs.toString(), context.packageName)
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
                    base, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_PENDING), queryArgs, null,
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val pendingCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)
                    while (cursor.moveToNext()) {
                        if (cursor.getInt(pendingCol) != 1) continue
                        val uri = ContentUris.withAppendedId(base, cursor.getLong(idCol))
                        runCatching { context.contentResolver.delete(uri, null, null) }
                    }
                }
            }
        }
    }
}
