package me.hletrd.findx9tele.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the iPhone-style framing hint drawn inside the Loupe Overview: the sub-rect marking where the
 * magnified main view is actually looking within the full delivered frame.
 *
 * The hint's whole value is that it agrees with the main draw, so these fix the two things that
 * could silently disagree — the SIZE (which must be the same `(1 - crop) / zoomComp` the main draw
 * got) and the PLACE (which must follow the loupe point through the renderer's texcoord rotation).
 */
class LoupeHintRectTest {

    private val finder = FinderRect(x = 40f, y = 60f, width = 300f, height = 400f)

    @Test
    fun `a centred loupe puts the hint dead centre at the main view's own fraction`() {
        // PUNCH_IN_CROP 0.6 at rest (zoomComp 1) shows 40% of the frame, so the hint is 40% of the
        // finder — the number a user can check by eye against how much the loupe magnifies.
        val hint = loupeHintRect(finder, visibleFraction = 0.4f, 0.5f, 0.5f, rotationDegrees = 180)

        assertEquals(120f, hint.width, 1e-4f)
        assertEquals(160f, hint.height, 1e-4f)
        assertEquals(40f + 150f - 60f, hint.x, 1e-4f)
        assertEquals(60f + 200f - 80f, hint.y, 1e-4f)
    }

    @Test
    fun `180 degrees is what the finder actually runs at, and it inverts an off-centre loupe`() {
        // The finder is reachable only in tele, where the afocal correction is 180°. A loupe tapped
        // toward one texcoord corner therefore shows in the OPPOSITE corner of the drawn image —
        // this is the sign the device check exists to confirm, and the one a naive
        // "just place it at (x, y)" implementation gets backwards.
        val rotated = loupeHintRect(finder, 0.4f, centerTexX = 0.25f, centerTexY = 0.25f, rotationDegrees = 180)
        val unrotated = loupeHintRect(finder, 0.4f, centerTexX = 0.25f, centerTexY = 0.25f, rotationDegrees = 0)

        // Mirrored through the finder centre on BOTH axes.
        val centreX = finder.x + finder.width / 2f - hintHalf(hint = 0.4f, span = finder.width)
        val centreY = finder.y + finder.height / 2f - hintHalf(hint = 0.4f, span = finder.height)
        assertEquals(centreX - (unrotated.x - centreX), rotated.x, 1e-4f)
        assertEquals(centreY - (unrotated.y - centreY), rotated.y, 1e-4f)
    }

    @Test
    fun `the hint never escapes the finder box`() {
        // Two ways out: a loupe pushed to the very edge, and zoomComp < 1 mid-gesture, which makes
        // the main view genuinely wider than the delivered frame. A hint drawn outside its own
        // border reads as a rendering bug rather than as "you are at the edge".
        val corner = loupeHintRect(finder, 0.4f, centerTexX = 0f, centerTexY = 1f, rotationDegrees = 180)
        assertTrue(corner.x >= finder.x - 1e-4f)
        assertTrue(corner.y >= finder.y - 1e-4f)
        assertTrue(corner.x + corner.width <= finder.x + finder.width + 1e-4f)
        assertTrue(corner.y + corner.height <= finder.y + finder.height + 1e-4f)

        val wide = loupeHintRect(finder, visibleFraction = 4f, 0.5f, 0.5f, rotationDegrees = 180)
        assertEquals(finder.width, wide.width, 1e-4f)
        assertEquals(finder.height, wide.height, 1e-4f)
        assertEquals(finder.x, wide.x, 1e-4f)
        assertEquals(finder.y, wide.y, 1e-4f)
    }

    @Test
    fun `a stronger loupe draws a smaller hint`() {
        // Monotonicity is the property a user reads directly: magnify more, the marked box shrinks.
        val mild = loupeHintRect(finder, 0.6f, 0.5f, 0.5f, 180)
        val strong = loupeHintRect(finder, 0.2f, 0.5f, 0.5f, 180)
        assertTrue(strong.width < mild.width)
        assertTrue(strong.height < mild.height)
    }

    private fun hintHalf(hint: Float, span: Float) = span * hint / 2f
}
