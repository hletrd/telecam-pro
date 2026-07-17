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
 *    device): the planes are repacked to NV21 on the camera thread (row-wise arraycopy fast paths
 *    in [packYuv420ToNv21] — the earlier fully elementwise pack was ~19M bounds-checked ops per
 *    still, NOT a cheap memcpy, and it stalled 3A/zoom during bursts), and the JPEG encode happens
 *    lazily in [jpegBytes] on the caller's io thread — never on the camera thread, where ~200 ms
 *    of encode would stall preview and 3A.
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

        /** Repacks YUV_420_888 by each plane's semantic U/V identity, never by stride heuristics. */
        private fun yuvToNv21(image: Image): ByteArray {
            fun snapshot(plane: Image.Plane): YuvPlaneData {
                val buffer = plane.buffer.duplicate()
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                return YuvPlaneData(bytes, plane.rowStride, plane.pixelStride)
            }
            return packYuv420ToNv21(
                width = image.width,
                height = image.height,
                y = snapshot(image.planes[0]),
                u = snapshot(image.planes[1]),
                v = snapshot(image.planes[2]),
            )
        }
    }
}

/** Plain plane snapshot used by the JVM-testable YUV stride conversion core. */
internal data class YuvPlaneData(val bytes: ByteArray, val rowStride: Int, val pixelStride: Int)

/**
 * Packs planar, NV12-shaped, or NV21-shaped YUV_420_888 views into canonical NV21 (Y + VU).
 *
 * This runs on the CAMERA thread (the Image is short-lived), and a fully elementwise pack of a
 * 4080×3064 still is ~19M bounds-checked array ops — tens of milliseconds that queue behind 3A
 * callbacks and the zoom fast path (visible hitch when shooting while zooming, worst in BURST).
 * So the bulk copies are row-wise [System.arraycopy] where that is EXACT without layout
 * assumptions (each plane's semantic identity still comes from its own view, never from stride
 * heuristics — see the past-bug note on [StillSnapshot.from]):
 *  - Y with pixelStride 1 (every real YUV_420_888 source): one arraycopy per row.
 *  - Chroma with V pixelStride 2 (semi-planar sources): arraycopy the V view's row — V samples
 *    land on the even (NV21 V) positions by the view's own stride contract, whatever the gap
 *    bytes contain — then overwrite every odd position from the U view. Exact for NV21-shaped,
 *    NV12-shaped, or any other pixelStride-2 layout, at half the elementwise work.
 *  - Anything else falls back to the fully general elementwise pack.
 */
internal fun packYuv420ToNv21(
    width: Int,
    height: Int,
    y: YuvPlaneData,
    u: YuvPlaneData,
    v: YuvPlaneData,
): ByteArray {
    require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
    val out = ByteArray(width * height * 3 / 2)
    var pos = 0
    if (y.pixelStride == 1) {
        for (row in 0 until height) {
            System.arraycopy(y.bytes, row * y.rowStride, out, pos, width)
            pos += width
        }
    } else {
        for (row in 0 until height) {
            val rowStart = row * y.rowStride
            for (col in 0 until width) out[pos++] = y.bytes[rowStart + col * y.pixelStride]
        }
    }
    for (row in 0 until height / 2) {
        val vRow = row * v.rowStride
        val uRow = row * u.rowStride
        if (v.pixelStride == 2) {
            // V view stride contract puts V(k) at vRow + 2k → the row copy fills every even output
            // position correctly (odd positions get whatever the view's gap bytes were and are
            // fully overwritten from the U view below). The copy is width-1 bytes because the V
            // row's last valid byte is V(width/2-1) at offset width-2; output's final odd slot is
            // written by the U loop.
            System.arraycopy(v.bytes, vRow, out, pos, width - 1)
            for (col in 0 until width / 2) {
                out[pos + 2 * col + 1] = u.bytes[uRow + col * u.pixelStride]
            }
            pos += width
        } else {
            for (col in 0 until width / 2) {
                out[pos++] = v.bytes[vRow + col * v.pixelStride]
                out[pos++] = u.bytes[uRow + col * u.pixelStride]
            }
        }
    }
    return out
}
