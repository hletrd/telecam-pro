package com.hletrd.findx9tele.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.OutputStream

/**
 * Thin wrapper around MediaStore for saving photos/videos into DCIM/<subDir> using the
 * pending-file convention (IS_PENDING) so files don't appear to other apps until fully written.
 */
object MediaStoreWriter {

    fun savePhotoBytes(
        context: Context,
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
        subDir: String = "X9Tele",
    ): Uri? {
        val uri = createPendingImage(context, displayName, mimeType, subDir) ?: return null
        val ok = runCatching {
            openOutputStream(context, uri)?.use { it.write(bytes) } ?: return null
        }.isSuccess
        if (!ok) {
            delete(context, uri)
            return null
        }
        publish(context, uri)
        return uri
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
    fun publish(context: Context, uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        runCatching { context.contentResolver.update(uri, values, null, null) }
    }

    fun delete(context: Context, uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
    }
}
