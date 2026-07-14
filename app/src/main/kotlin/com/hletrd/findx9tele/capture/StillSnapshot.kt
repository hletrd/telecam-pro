package com.hletrd.findx9tele.capture

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import java.io.ByteArrayOutputStream

/**
 * A still frame snapshotted OFF its (short-lived) [Image] so the save pipeline can run later on an
 * io thread. Two sources:
 *  - HAL JPEG (standalone cameras): the compressed bytes are copied out as-is.
 *  - YUV_420_888 (the logical multicamera, whose JPEG blob allocation fails in gralloc on this
 *    device): the planes are repacked to NV21 on the camera thread (cheap memcpy), and the JPEG
 *    encode happens lazily in [jpegBytes] on the caller's io thread — never on the camera thread,
 *    where ~200 ms of encode would stall preview and 3A.
 *
 * The downstream HEIF/JPEG savers already decode → crop → rotate → re-encode every shot, so an
 * intermediate JPEG (at [INTERMEDIATE_QUALITY]) changes nothing structurally about the pipeline.
 */
sealed class StillSnapshot {
    /** Compressed JPEG bytes of the shot; potentially expensive — call on an io thread. */
    abstract fun jpegBytes(): ByteArray

    private class Jpeg(private val bytes: ByteArray) : StillSnapshot() {
        override fun jpegBytes(): ByteArray = bytes
    }

    private class Nv21(private val nv21: ByteArray, private val width: Int, private val height: Int) : StillSnapshot() {
        override fun jpegBytes(): ByteArray {
            val out = ByteArrayOutputStream(width * height / 2)
            val ok = YuvImage(nv21, ImageFormat.NV21, width, height, null)
                .compressToJpeg(Rect(0, 0, width, height), INTERMEDIATE_QUALITY, out)
            check(ok) { "YUV→JPEG compress failed" }
            return out.toByteArray()
        }
    }

    companion object {
        // Intermediate encode quality for the YUV path. The final container is re-encoded at the
        // user's JPEG-quality/HEIF setting anyway; 97 keeps generational loss invisible.
        private const val INTERMEDIATE_QUALITY = 97

        /** Copies the frame out of [image] (must be alive). Camera-thread cheap for both formats. */
        fun from(image: Image): StillSnapshot = when (image.format) {
            ImageFormat.JPEG -> {
                val buf = image.planes[0].buffer
                Jpeg(ByteArray(buf.remaining()).also { buf.get(it) })
            }
            ImageFormat.YUV_420_888 -> Nv21(yuvToNv21(image), image.width, image.height)
            else -> throw IllegalArgumentException("Unsupported still format ${image.format}")
        }

        /**
         * Repacks YUV_420_888 planes into NV21 (Y then interleaved V/U), honoring row/pixel strides.
         * Fast path: the common semi-planar layout (chroma pixelStride == 2, rowStride == width)
         * where plane[2]'s buffer is already the V/U interleave — one bulk get. Generic per-sample
         * fallback otherwise.
         */
        private fun yuvToNv21(image: Image): ByteArray {
            val w = image.width
            val h = image.height
            val out = ByteArray(w * h * 3 / 2)
            val y = image.planes[0]
            val u = image.planes[1]
            val v = image.planes[2]

            // ---- Y plane ----
            val yBuf = y.buffer
            if (y.pixelStride == 1 && y.rowStride == w) {
                yBuf.get(out, 0, w * h)
            } else {
                var pos = 0
                for (row in 0 until h) {
                    yBuf.position(row * y.rowStride)
                    yBuf.get(out, pos, w)
                    pos += w
                }
            }

            // ---- chroma ----
            val uvBytes = w * h / 2
            val vBuf = v.buffer
            val uBuf = u.buffer
            if (v.pixelStride == 2 && v.rowStride == w && u.pixelStride == 2 && u.rowStride == w) {
                // Semi-planar: V buffer reads V0 U0 V1 U1 … = exactly NV21's chroma sequence. Its
                // last U byte lives only in the U plane (the V view ends one byte short).
                val n = minOf(vBuf.remaining(), uvBytes)
                vBuf.get(out, w * h, n)
                if (n < uvBytes) {
                    uBuf.position(uBuf.remaining() - 1)
                    out[w * h + uvBytes - 1] = uBuf.get()
                }
            } else {
                var pos = w * h
                for (row in 0 until h / 2) {
                    val vRow = row * v.rowStride
                    val uRow = row * u.rowStride
                    for (col in 0 until w / 2) {
                        out[pos++] = vBuf.get(vRow + col * v.pixelStride)
                        out[pos++] = uBuf.get(uRow + col * u.pixelStride)
                    }
                }
            }
            return out
        }
    }
}
