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

    /**
     * Newest still THIS APP saved (scoped storage shows an app its own contributions without any
     * read permission). Seeds the review thumbnail on a fresh launch, so "last shot" works before
     * the first capture of the session. DNGs are skipped — BitmapFactory can't decode them, and the
     * HEIF/JPEG sibling of the same capture is always newer or equal.
     */
    fun latestOwnImage(context: Context): Uri? = runCatching {
        val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val queryArgs = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.MIME_TYPE} != ?",
            )
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("image/x-adobe-dng"))
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_ADDED} DESC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
        }
        context.contentResolver.query(base, arrayOf(MediaStore.MediaColumns._ID), queryArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) ContentUris.withAppendedId(base, cursor.getLong(0)) else null
        }
    }.getOrNull()

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

    fun delete(context: Context, uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

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
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DATE_ADDED} < ?"
        // RELATIVE_PATH values are normalized with a trailing slash ("DCIM/X9Tele/"), so anchor the
        // pattern on it — "DCIM/X9Tele%" would also sweep a hypothetical "DCIM/X9TeleOther/".
        val args = arrayOf("DCIM/$subDir/%", processStartSecs.toString())
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
