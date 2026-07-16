package com.hletrd.findx9tele.ui.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaReviewSizingTest {

    @Test
    fun `4K landscape thumbnail is bounded without changing aspect`() {
        assertEquals(
            BoundedVideoFrameSize(240, 135),
            boundedVideoFrameSize(width = 3840, height = 2160, maxDim = 240),
        )
    }

    @Test
    fun `4K portrait thumbnail is bounded without changing aspect`() {
        assertEquals(
            BoundedVideoFrameSize(135, 240),
            boundedVideoFrameSize(width = 2160, height = 3840, maxDim = 240),
        )
    }

    @Test
    fun `source below limit retains its dimensions`() {
        assertEquals(
            BoundedVideoFrameSize(640, 360),
            boundedVideoFrameSize(width = 640, height = 360, maxDim = 1000),
        )
    }

    @Test
    fun `invalid source dimensions have no decoder target`() {
        assertNull(boundedVideoFrameSize(width = 0, height = 1080, maxDim = 240))
        assertNull(boundedVideoFrameSize(width = 1920, height = 0, maxDim = 240))
        assertNull(boundedVideoFrameSize(width = -1, height = 1080, maxDim = 240))
    }

    @Test
    fun `nonpositive max dimension requests fallback decoding`() {
        assertNull(boundedVideoFrameSize(width = 1920, height = 1080, maxDim = 0))
        assertNull(boundedVideoFrameSize(width = 1920, height = 1080, maxDim = -1))
    }

    @Test
    fun `one pixel bound still returns positive dimensions`() {
        assertEquals(
            BoundedVideoFrameSize(1, 1),
            boundedVideoFrameSize(width = 3840, height = 2160, maxDim = 1),
        )
    }
}
