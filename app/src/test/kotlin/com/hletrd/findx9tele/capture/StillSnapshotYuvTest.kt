package com.hletrd.findx9tele.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class StillSnapshotYuvTest {
    private val y = YuvPlaneData(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), rowStride = 4, pixelStride = 1)
    private val expected = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 30, 10, 40, 20)

    @Test
    fun planarPlanes_packAsNv21() {
        val u = YuvPlaneData(byteArrayOf(10, 20), rowStride = 2, pixelStride = 1)
        val v = YuvPlaneData(byteArrayOf(30, 40), rowStride = 2, pixelStride = 1)

        assertArrayEquals(expected, packYuv420ToNv21(4, 2, y, u, v))
    }

    @Test
    fun nv21ShapedViews_stillUseSemanticPlaneIdentity() {
        val u = YuvPlaneData(byteArrayOf(10, 40, 20), rowStride = 4, pixelStride = 2)
        val v = YuvPlaneData(byteArrayOf(30, 10, 40), rowStride = 4, pixelStride = 2)

        assertArrayEquals(expected, packYuv420ToNv21(4, 2, y, u, v))
    }

    @Test
    fun nv12ShapedViews_areReorderedToNv21() {
        val u = YuvPlaneData(byteArrayOf(10, 30, 20), rowStride = 4, pixelStride = 2)
        val v = YuvPlaneData(byteArrayOf(30, 20, 40), rowStride = 4, pixelStride = 2)

        assertArrayEquals(expected, packYuv420ToNv21(4, 2, y, u, v))
    }

    // The row-copy fast paths (Y pixelStride 1; chroma V pixelStride 2) must stay exact for padded
    // rowStrides across multiple rows — the shapes real gralloc buffers use.
    @Test
    fun paddedRowStrides_multiRow_packExactly() {
        val yPadded = YuvPlaneData(
            byteArrayOf(
                1, 2, 3, 4, 99, 99,
                5, 6, 7, 8, 99, 99,
                9, 10, 11, 12, 99, 99,
                13, 14, 15, 16,
            ),
            rowStride = 6,
            pixelStride = 1,
        )
        val v = YuvPlaneData(byteArrayOf(30, 10, 40, 99, 99, 99, 50, 60, 70), rowStride = 6, pixelStride = 2)
        val u = YuvPlaneData(byteArrayOf(10, 40, 20, 99, 99, 99, 60, 70, 80), rowStride = 6, pixelStride = 2)

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
            byteArrayOf(1, 0, 2, 0, 3, 0, 4, 0, 5, 0, 6, 0, 7, 0, 8),
            rowStride = 8,
            pixelStride = 2,
        )
        val u = YuvPlaneData(byteArrayOf(10, 20), rowStride = 2, pixelStride = 1)
        val v = YuvPlaneData(byteArrayOf(30, 40), rowStride = 2, pixelStride = 1)

        assertArrayEquals(expected, packYuv420ToNv21(4, 2, y2, u, v))
    }
}
