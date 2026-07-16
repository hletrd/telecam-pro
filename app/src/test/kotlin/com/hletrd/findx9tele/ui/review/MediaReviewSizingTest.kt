package com.hletrd.findx9tele.ui.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaReviewSizingTest {

    @Test
    fun `still zoom cycle exposes the next visible action`() {
        assertEquals(4f, nextReviewScale(1f))
        assertEquals("Zoom 4×", reviewZoomActionLabel(1f))
        assertEquals("4×", reviewZoomControlLabel(1f))

        assertEquals(8f, nextReviewScale(4f))
        assertEquals("Zoom 8×", reviewZoomActionLabel(4f))
        assertEquals("8×", reviewZoomControlLabel(4f))

        assertEquals(1f, nextReviewScale(8f))
        assertEquals("Reset zoom", reviewZoomActionLabel(8f))
        assertEquals("1×", reviewZoomControlLabel(8f))
    }

    @Test
    fun `still zoom state describes current magnification`() {
        assertEquals("Magnification 1×", reviewZoomStateDescription(1f))
        assertEquals("Magnification 4×", reviewZoomStateDescription(4f))
        assertEquals("Magnification 8×", reviewZoomStateDescription(8f))
        assertEquals("Magnification 2.5×", reviewZoomStateDescription(2.5f))
    }

    @Test
    fun `video playback labels expose action and current state`() {
        assertEquals("Pause video", videoPlaybackActionLabel(playing = true))
        assertEquals("Playing", videoPlaybackStateDescription(playing = true))
        assertEquals("Play video", videoPlaybackActionLabel(playing = false))
        assertEquals("Paused", videoPlaybackStateDescription(playing = false))
    }

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
