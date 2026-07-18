package com.hletrd.findx9tele.capture

import android.annotation.SuppressLint
import android.graphics.Bitmap
import androidx.heifwriter.HeifWriter
import java.io.FileDescriptor

private const val STOP_TIMEOUT_MS = 10_000L

/** Encodes a single bitmap to HEIF via androidx HeifWriter. */
object HeifCapture {

    /**
     * [bitmap] is already oriented by the caller (e.g. rotated 180deg); no rotation is applied here.
     *
     * `@SuppressLint("RestrictedApi")`: `HeifWriter` implements `AutoCloseable`, and `close()` is the
     * intended way to release its encoder/handler thread, but the library annotates the inherited
     * `WriterBase.close()` `@RestrictTo(LIBRARY_GROUP)` — so lint's `RestrictedApi` flags the only
     * supported release path (a library false-positive, present in stable 1.1.0 too).
     */
    @SuppressLint("RestrictedApi")
    fun writeHeif(
        fd: FileDescriptor,
        bitmap: Bitmap,
        quality: Int = 95,
        exifData: ByteArray? = null,
    ) {
        val writer = HeifWriter.Builder(fd, bitmap.width, bitmap.height, HeifWriter.INPUT_MODE_BITMAP)
            .setQuality(quality)
            .build()
        try {
            writer.start()
            writer.addBitmap(bitmap)
            exifData?.takeIf { it.isNotEmpty() }?.let { data ->
                writer.addExifData(0, data, 0, data.size)
            }
            writer.stop(STOP_TIMEOUT_MS)
        } finally {
            writer.close()
        }
    }
}
