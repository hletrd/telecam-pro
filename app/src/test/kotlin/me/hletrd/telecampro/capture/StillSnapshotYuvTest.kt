package me.hletrd.telecampro.capture

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StillSnapshotYuvTest {
    private val y = YuvPlaneData(ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)), rowStride = 4, pixelStride = 1)
    private val expected = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 30, 10, 40, 20)

    // AGG3-62 / FD3-3: the ~19 MB NV21 copy is dropped on a FAILED encode too, not only on success.
    @Test
    fun nv21Snapshot_dropsPixelsOnFailureAndSuccess() {
        var encodes = 0
        val failing = StillSnapshot.Nv21(ByteArray(12), 4, 2, compress = { _, _, _, _ -> encodes++; false })
        assertThrows(IllegalStateException::class.java) { failing.jpeg() }
        val second = assertThrows(IllegalStateException::class.java) { failing.jpeg() }
        assertEquals("StillSnapshot.jpeg is single-use", second.message)
        assertEquals(1, encodes)

        val throwing = StillSnapshot.Nv21(ByteArray(12), 4, 2, compress = { _, _, _, _ -> error("encoder died") })
        assertThrows(IllegalStateException::class.java) { throwing.jpeg() }
        assertEquals(
            "StillSnapshot.jpeg is single-use",
            assertThrows(IllegalStateException::class.java) { throwing.jpeg() }.message,
        )

        val ok = StillSnapshot.Nv21(ByteArray(12), 4, 2, compress = { _, _, _, out -> out.write(byteArrayOf(9)); true })
        // AGG6-35: the encode is handed over IN PLACE — the pre-sized (width*height) backing array
        // and its count, byte-identical to the old toByteArray() copy but without making it.
        val encoded = ok.jpeg()
        assertEquals(1, encoded.length)
        assertEquals(4 * 2, encoded.bytes.size)
        assertArrayEquals(byteArrayOf(9), encoded.bytes.copyOf(encoded.length))
        assertThrows(IllegalStateException::class.java) { ok.jpeg() }
    }

    @Test
    fun planarPlanes_packAsNv21() {
        val u = YuvPlaneData(ByteBuffer.wrap(byteArrayOf(10, 20)), rowStride = 2, pixelStride = 1)
        val v = YuvPlaneData(ByteBuffer.wrap(byteArrayOf(30, 40)), rowStride = 2, pixelStride = 1)

        assertArrayEquals(expected, packYuv420ToNv21(4, 2, y, u, v))
    }

    @Test
    fun nv21ShapedViews_stillUseSemanticPlaneIdentity() {
        val u = YuvPlaneData(ByteBuffer.wrap(byteArrayOf(10, 40, 20)), rowStride = 4, pixelStride = 2)
        val v = YuvPlaneData(ByteBuffer.wrap(byteArrayOf(30, 10, 40)), rowStride = 4, pixelStride = 2)

        assertArrayEquals(expected, packYuv420ToNv21(4, 2, y, u, v))
    }

    @Test
    fun nv12ShapedViews_areReorderedToNv21() {
        val u = YuvPlaneData(ByteBuffer.wrap(byteArrayOf(10, 30, 20)), rowStride = 4, pixelStride = 2)
        val v = YuvPlaneData(ByteBuffer.wrap(byteArrayOf(30, 20, 40)), rowStride = 4, pixelStride = 2)

        assertArrayEquals(expected, packYuv420ToNv21(4, 2, y, u, v))
    }

    // The row-copy fast paths (Y pixelStride 1; chroma V pixelStride 2) must stay exact for padded
    // rowStrides across multiple rows — the shapes real gralloc buffers use.
    @Test
    fun paddedRowStrides_multiRow_packExactly() {
        val yPadded = YuvPlaneData(
            ByteBuffer.wrap(
                byteArrayOf(
                    1, 2, 3, 4, 99, 99,
                    5, 6, 7, 8, 99, 99,
                    9, 10, 11, 12, 99, 99,
                    13, 14, 15, 16,
                ),
            ),
            rowStride = 6,
            pixelStride = 1,
        )
        val v = YuvPlaneData(ByteBuffer.wrap(byteArrayOf(30, 10, 40, 99, 99, 99, 50, 60, 70)), rowStride = 6, pixelStride = 2)
        val u = YuvPlaneData(ByteBuffer.wrap(byteArrayOf(10, 40, 20, 99, 99, 99, 60, 70, 80)), rowStride = 6, pixelStride = 2)

        assertArrayEquals(
            byteArrayOf(
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
                30, 10, 40, 20,
                50, 60, 70, 80,
            ),
            packYuv420ToNv21(4, 4, yPadded, u, v),
        )
    }

    // A pixelStride-2 Y view (no real source produces one, but the contract allows it) must take
    // the generic elementwise fallback and still pack exactly.
    @Test
    fun pixelStride2Y_fallsBackElementwise() {
        val y2 = YuvPlaneData(
            ByteBuffer.wrap(byteArrayOf(1, 0, 2, 0, 3, 0, 4, 0, 5, 0, 6, 0, 7, 0, 8)),
            rowStride = 8,
            pixelStride = 2,
        )
        val u = YuvPlaneData(ByteBuffer.wrap(byteArrayOf(10, 20)), rowStride = 2, pixelStride = 1)
        val v = YuvPlaneData(ByteBuffer.wrap(byteArrayOf(30, 40)), rowStride = 2, pixelStride = 1)

        assertArrayEquals(expected, packYuv420ToNv21(4, 2, y2, u, v))
    }
}
