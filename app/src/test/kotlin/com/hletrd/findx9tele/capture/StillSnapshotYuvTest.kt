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
}
