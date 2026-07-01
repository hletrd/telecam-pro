package com.hletrd.findx9tele.capture

import android.graphics.Bitmap
import androidx.heifwriter.HeifWriter
import java.io.FileDescriptor

private const val STOP_TIMEOUT_MS = 10_000L

/** Encodes a single bitmap to HEIF via androidx HeifWriter. */
object HeifCapture {

    /** [bitmap] is already oriented by the caller (e.g. rotated 180deg); no rotation is applied here. */
    fun writeHeif(fd: FileDescriptor, bitmap: Bitmap, quality: Int = 95) {
        val writer = HeifWriter.Builder(fd, bitmap.width, bitmap.height, HeifWriter.INPUT_MODE_BITMAP)
            .setQuality(quality)
            .build()
        try {
            writer.start()
            writer.addBitmap(bitmap)
            writer.stop(STOP_TIMEOUT_MS)
        } finally {
            writer.close()
        }
    }
}
