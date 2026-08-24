package me.hletrd.telecampro.ui.review

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaReviewGestureTest {
    private fun assertOffset(expectedX: Float, expectedY: Float, actual: Offset) {
        assertEquals(expectedX, actual.x, 0.001f)
        assertEquals(expectedY, actual.y, 0.001f)
    }

    @Test
    fun `fit geometry covers portrait landscape and both letterbox axes`() {
        val landscape = reviewStillGeometry(1200, 800, 1600, 900)
        assertEquals(1200f, landscape.fittedWidth, 0.001f)
        assertEquals(675f, landscape.fittedHeight, 0.001f)

        val portraitInLandscape = reviewStillGeometry(1200, 800, 900, 1600)
        assertEquals(450f, portraitInLandscape.fittedWidth, 0.001f)
        assertEquals(800f, portraitInLandscape.fittedHeight, 0.001f)

        val landscapeInPortrait = reviewStillGeometry(800, 1200, 1600, 900)
        assertEquals(800f, landscapeInPortrait.fittedWidth, 0.001f)
        assertEquals(450f, landscapeInPortrait.fittedHeight, 0.001f)
    }

    @Test
    fun `one four and twelve x bounds derive from fitted pixels`() {
        val landscape = reviewStillGeometry(1200, 800, 1600, 900)
        assertOffset(0f, 0f, landscape.panBounds(1f))
        assertOffset(1800f, 950f, landscape.panBounds(4f))
        assertOffset(6600f, 3650f, landscape.panBounds(12f))

        val portrait = reviewStillGeometry(1200, 800, 900, 1600)
        assertOffset(0f, 0f, portrait.panBounds(1f))
        assertOffset(300f, 1200f, portrait.panBounds(4f))
        assertOffset(2100f, 4400f, portrait.panBounds(12f))
    }

    @Test
    fun `corner centering and excessive pan clamp to visible content`() {
        val geometry = reviewStillGeometry(1200, 800, 1600, 900)

        assertOffset(1800f, 950f, geometry.centerOn(Offset.Zero, 4f))
        assertOffset(-1800f, -950f, geometry.centerOn(Offset(1200f, 800f), 4f))
        assertOffset(1800f, -950f, geometry.clampOffset(Offset(99_999f, -99_999f), 4f))
        assertOffset(0f, 0f, geometry.clampOffset(Offset(400f, -300f), 1f))
    }

    @Test
    fun `point centering preserves the tapped content across four to eight x`() {
        val geometry = reviewStillGeometry(1200, 800, 1600, 900)
        val currentOffset = Offset(300f, -200f)

        assertOffset(
            1400f,
            -800f,
            geometry.centerOn(
                point = Offset(200f, 600f),
                targetScale = 8f,
                currentScale = 4f,
                currentOffset = currentOffset,
            ),
        )
    }

    @Test
    fun `size and orientation transition reclamps the prior maximum`() {
        val landscapeViewport = reviewStillGeometry(1200, 800, 1600, 900)
        val portraitViewport = reviewStillGeometry(800, 1200, 1600, 900)
        val prior = landscapeViewport.clampOffset(Offset(99_999f, 99_999f), 12f)

        assertOffset(6600f, 3650f, prior)
        assertOffset(4400f, 2100f, portraitViewport.clampOffset(prior, 12f))
        assertOffset(0f, 0f, portraitViewport.clampOffset(prior, 1f))
    }

    @Test
    fun `invalid geometry and coordinates fail closed`() {
        val invalid = reviewStillGeometry(0, 800, 1600, 900)

        assertOffset(0f, 0f, invalid.panBounds(12f))
        assertOffset(0f, 0f, invalid.clampOffset(Offset(Float.NaN, 2f), 12f))
        assertOffset(0f, 0f, invalid.centerOn(Offset(Float.POSITIVE_INFINITY, 2f), 12f))
    }

    private val firstTap = ReviewTapCandidate(
        upTimeMillis = 100L,
        position = Offset(100f, 100f),
    )

    private fun decide(
        previous: ReviewTapCandidate? = firstTap,
        clean: Boolean = true,
        down: Long = 180L,
        up: Long = 195L,
        position: Offset = Offset(106f, 108f),
    ): ReviewTapSequenceDecision = reviewTapSequenceDecision(
        previous = previous,
        cleanTap = clean,
        downTimeMillis = down,
        upTimeMillis = up,
        position = position,
        minimumIntervalMillis = 40L,
        maximumIntervalMillis = 300L,
        maximumDistance = 18f,
    )

    @Test
    fun `near tap inside supported time window is a double tap`() {
        val decision = decide()

        assertTrue(decision.isDoubleTap)
        assertNull(decision.nextCandidate)
    }

    @Test
    fun `spatially far tap starts a new candidate`() {
        val decision = decide(position = Offset(140f, 100f))

        assertFalse(decision.isDoubleTap)
        assertEquals(Offset(140f, 100f), decision.nextCandidate?.position)
    }

    @Test
    fun `impossibly fast tap starts a new candidate`() {
        val decision = decide(down = 120L)

        assertFalse(decision.isDoubleTap)
        assertEquals(195L, decision.nextCandidate?.upTimeMillis)
    }

    @Test
    fun `timed out tap starts a new candidate`() {
        val decision = decide(down = 401L)

        assertFalse(decision.isDoubleTap)
        assertEquals(Offset(106f, 108f), decision.nextCandidate?.position)
    }

    @Test
    fun `drag cancels the pending tap sequence`() {
        val drag = decide(clean = false)
        val nextTap = decide(previous = drag.nextCandidate)

        assertNull(drag.nextCandidate)
        assertFalse(nextTap.isDoubleTap)
    }

    @Test
    fun `pinch cancels the pending tap sequence`() {
        val pinch = decide(clean = false, position = Offset(104f, 104f))
        val nextTap = decide(previous = pinch.nextCandidate, down = 220L)

        assertNull(pinch.nextCandidate)
        assertFalse(nextTap.isDoubleTap)
    }
}
