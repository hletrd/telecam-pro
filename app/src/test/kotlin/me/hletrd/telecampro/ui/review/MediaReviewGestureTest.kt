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
    fun `pinch transform preserves centered and off-center content points`() {
        val geometry = reviewStillGeometry(1200, 800, 1600, 900)

        val centered = geometry.transformGesture(
            currentScale = 1f,
            currentOffset = Offset.Zero,
            centroid = Offset(600f, 400f),
            pan = Offset.Zero,
            zoomChange = 2f,
        )
        assertEquals(2f, centered.scale, 0.001f)
        assertOffset(0f, 0f, centered.offset)

        val offCenter = geometry.transformGesture(
            currentScale = 1f,
            currentOffset = Offset.Zero,
            centroid = Offset(900f, 200f),
            pan = Offset.Zero,
            zoomChange = 2f,
        )
        assertEquals(2f, offCenter.scale, 0.001f)
        assertOffset(-300f, 200f, offCenter.offset)
    }

    @Test
    fun `pinch transform combines pan and zoom before one boundary clamp`() {
        val geometry = reviewStillGeometry(1200, 800, 1600, 900)

        val combined = geometry.transformGesture(
            currentScale = 2f,
            currentOffset = Offset(100f, -50f),
            centroid = Offset(900f, 600f),
            pan = Offset(40f, -20f),
            zoomChange = 2f,
        )
        assertEquals(4f, combined.scale, 0.001f)
        assertOffset(-60f, -320f, combined.offset)

        val clamped = geometry.transformGesture(
            currentScale = 8f,
            currentOffset = Offset(99_999f, -99_999f),
            centroid = Offset.Zero,
            pan = Offset(99_999f, -99_999f),
            zoomChange = 2f,
        )
        assertEquals(12f, clamped.scale, 0.001f)
        assertOffset(6600f, -3650f, clamped.offset)
    }

    @Test
    fun `non-touch pan is bounded and position reports the viewed image region`() {
        val geometry = reviewStillGeometry(1200, 800, 1600, 900)
        val scale = 4f

        assertEquals(ReviewStillPosition.CENTER, geometry.position(scale, Offset.Zero))
        val left = requireNotNull(geometry.panTarget(Offset.Zero, scale, ReviewPanDirection.LEFT))
        assertOffset(300f, 0f, left)
        assertEquals(ReviewStillPosition.CENTER, geometry.position(scale, left))

        var topLeft = Offset.Zero
        while (true) {
            val next = geometry.panTarget(topLeft, scale, ReviewPanDirection.LEFT) ?: break
            topLeft = next
        }
        while (true) {
            val next = geometry.panTarget(topLeft, scale, ReviewPanDirection.UP) ?: break
            topLeft = next
        }
        assertOffset(1800f, 950f, topLeft)
        assertEquals(ReviewStillPosition.TOP_LEFT, geometry.position(scale, topLeft))
        assertNull(geometry.panTarget(topLeft, scale, ReviewPanDirection.LEFT))
        assertNull(geometry.panTarget(topLeft, scale, ReviewPanDirection.UP))
        assertEquals(
            Offset(1500f, 950f),
            geometry.panTarget(topLeft, scale, ReviewPanDirection.RIGHT),
        )
        assertEquals(
            Offset(1800f, 750f),
            geometry.panTarget(topLeft, scale, ReviewPanDirection.DOWN),
        )
    }

    @Test
    fun `position classifies every image region and its one-third boundaries`() {
        val geometry = reviewStillGeometry(1200, 800, 1600, 900)
        val scale = 4f
        val cases = mapOf(
            Offset.Zero to ReviewStillPosition.CENTER,
            Offset(601f, 0f) to ReviewStillPosition.LEFT,
            Offset(-601f, 0f) to ReviewStillPosition.RIGHT,
            Offset(0f, 317f) to ReviewStillPosition.TOP,
            Offset(0f, -317f) to ReviewStillPosition.BOTTOM,
            Offset(601f, 317f) to ReviewStillPosition.TOP_LEFT,
            Offset(-601f, 317f) to ReviewStillPosition.TOP_RIGHT,
            Offset(601f, -317f) to ReviewStillPosition.BOTTOM_LEFT,
            Offset(-601f, -317f) to ReviewStillPosition.BOTTOM_RIGHT,
        )

        cases.forEach { (offset, expected) ->
            assertEquals("offset=$offset", expected, geometry.position(scale, offset))
        }

        assertEquals(ReviewStillPosition.CENTER, geometry.position(scale, Offset(599f, 316f)))
        assertEquals(ReviewStillPosition.TOP_LEFT, geometry.position(scale, Offset(600f, 317f)))
        assertEquals(ReviewStillPosition.CENTER, geometry.position(1f, Offset(99_999f, 99_999f)))
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
    fun `placement beyond touch slop but inside double tap slop is accepted`() {
        val touchSlop = 18f
        val doubleTapSlop = 100f
        val betweenThresholds = reviewTapSequenceDecision(
            previous = firstTap,
            cleanTap = true,
            downTimeMillis = 180L,
            upTimeMillis = 195L,
            position = Offset(firstTap.position.x + 60f, firstTap.position.y),
            minimumIntervalMillis = 40L,
            maximumIntervalMillis = 300L,
            maximumDistance = doubleTapSlop,
        )

        assertTrue(60f > touchSlop)
        assertTrue(betweenThresholds.isDoubleTap)
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
