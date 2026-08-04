package me.hletrd.telecampro.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the single geometry rule the TELE finder PIP shares between the GL scissor/viewport
 * (pixels) and the Compose border overlay (dp): fraction of the FULL box, with independent side and
 * bottom clearances measured against the short edge. The regression this protects: the original
 * Compose chain applied `padding` BEFORE `fillMaxWidth`, sizing the border from padding-reduced
 * constraints — ~6% smaller than the GL content box.
 */
class FinderGeometryTest {

    @Test
    fun `box is the fraction of the full box with independent short-edge insets`() {
        val r = finderRect(
            boxWidth = 1000f,
            boxHeight = 750f,
            fraction = 0.30f,
            sideMargin = 0.03f,
            bottomMargin = 0.10f,
        )
        assertEquals(300f, r.width, 1e-4f)
        assertEquals(225f, r.height, 1e-4f)
        // Short edge is 750 → 22.5 side inset. The box is anchored to the RIGHT edge (2026-07-29):
        // the left column carries the vertical exposure/zoom ruler, which the overview used to sit
        // under. Vertically it hangs from the box TOP (2026-08-04), so y is whatever is left below.
        assertEquals(1000f - 300f - 22.5f, r.x, 1e-4f)
        // A LANDSCAPE box is shorter than the top anchor reaches, so the anchor alone would put the
        // box below the frame (-315 here). The clearance floor is what stops that.
        assertEquals(750f * FINDER_MIN_BOTTOM_CLEARANCE, r.y, 1e-4f)
        assertTrue("the overview must never hang below the frame", r.y >= 0f)
    }

    @Test
    fun `portrait boxes inset by the width`() {
        val r = finderRect(boxWidth = 1080f, boxHeight = 1440f)
        assertEquals(1080f * FINDER_FRACTION, r.width, 1e-3f)
        assertEquals(1440f * FINDER_FRACTION, r.height, 1e-3f)
        assertEquals(1080f - 1080f * FINDER_FRACTION - 1080f * FINDER_SIDE_MARGIN, r.x, 1e-3f)
        // 1080x1440 is a 4:3 box whose anchor lands BELOW the clearance floor, so the floor wins —
        // which is the whole point of it on wide screens.
        assertEquals(
            maxOf(
                1440f - 1080f * FINDER_TOP_ANCHOR - 1440f * FINDER_FRACTION,
                1440f * FINDER_MIN_BOTTOM_CLEARANCE,
            ),
            r.y,
            1e-3f,
        )
    }

    @Test
    fun `gl pixel box and compose dp box are the same physical rect`() {
        // The same physical preview box expressed in px (GL surface) and in dp (Compose
        // constraints) must produce density-scaled copies of one rect — the property that keeps
        // the white border exactly on the GL content box.
        val density = 2.625f
        val px = finderRect(boxWidth = 1080f, boxHeight = 1440f)
        val dp = finderRect(boxWidth = 1080f / density, boxHeight = 1440f / density)
        assertEquals(px.x / density, dp.x, 1e-3f)
        assertEquals(px.y / density, dp.y, 1e-3f)
        assertEquals(px.width / density, dp.width, 1e-3f)
        assertEquals(px.height / density, dp.height, 1e-3f)
    }

    @Test
    fun `size does not depend on either margin`() {
        // The regression case: the border must be fraction-of-FULL-box, not
        // fraction-of-(box minus 2 margins).
        val noMargin = finderRect(
            boxWidth = 1000f,
            boxHeight = 1500f,
            sideMargin = 0f,
            bottomMargin = 0f,
        )
        val withMargin = finderRect(
            boxWidth = 1000f,
            boxHeight = 1500f,
            sideMargin = 0.03f,
            bottomMargin = 0.14f,
        )
        assertEquals(noMargin.width, withMargin.width, 1e-4f)
        assertEquals(noMargin.height, withMargin.height, 1e-4f)
    }

    @Test
    fun `side and top-anchor margins move only their own axes`() {
        val baseline = finderRect(boxWidth = 1000f, boxHeight = 1500f, sideMargin = 0.03f, topAnchor = 0.60f)
        val raised = finderRect(boxWidth = 1000f, boxHeight = 1500f, sideMargin = 0.03f, topAnchor = 0.49f)
        assertEquals(baseline.x, raised.x, 1e-4f)
        // A SMALLER top anchor hangs the box higher, so its distance from the bottom grows.
        assertEquals(110f, raised.y - baseline.y, 1e-4f)
    }

    @Test
    fun `box aspect matches the preview box aspect`() {
        val r = finderRect(boxWidth = 1080f, boxHeight = 1440f)
        assertEquals(1080f / 1440f, r.width / r.height, 1e-4f)
    }

    @Test
    fun `top-left UI hit geometry consumes only the visible finder rect`() {
        val r = finderRect(boxWidth = 1080f, boxHeight = 1440f)
        val top = 1440f - r.y - r.height

        assertTrue(finderContainsTopLeftPoint(r.x + 1f, top + 1f, 1080f, 1440f))
        assertTrue(finderContainsTopLeftPoint(r.x + r.width, top + r.height, 1080f, 1440f))
        assertFalse(finderContainsTopLeftPoint(r.x - 1f, top + 1f, 1080f, 1440f))
        assertFalse(finderContainsTopLeftPoint(r.x + 1f, top - 1f, 1080f, 1440f))
        assertFalse(finderContainsTopLeftPoint(0f, 0f, 0f, 1440f))
    }

    @Test
    fun `the overview clears the bottom chrome in 4-3 and 16-9 alike`() {
        // Device-measured on a 1440-wide phone: the 4:3 preview occupies screen y 510-2430 and the
        // 16:9 one 304-2864 — the taller aspect grows in BOTH directions, so its bottom edge is only
        // ~434 px lower, not the 640 px the aspect difference alone suggests. A bottom-relative
        // inset therefore cannot place the box against screen-fixed chrome: tuning it for 4:3 drops
        // 16:9 onto the focal rail, and subtracting the full aspect difference lifts 16:9 ~300 px
        // clear of it. Both were reported. Measured from the box TOP the two agree.
        val w = 1440f
        val photo = finderRect(w, w * 4f / 3f)
        val video = finderRect(w, w * 16f / 9f)
        assertEquals(photo.width, video.width, 0.01f)
        assertEquals(photo.x, video.x, 0.01f)
        // Screen position of each box's LOWER edge, using the measured preview bottoms.
        val photoBottom = 2430f - photo.y
        val videoBottom = 2864f - video.y
        // Close, not identical: the clearance floor lifts whichever aspect reaches it (4:3 here) a
        // little above the anchor line, which is the floor doing its job rather than a drift.
        assertEquals("both aspects must land the box at about the same height", photoBottom, videoBottom, 40f)
        // And both must stay clear of the focal rail, whose top edge is at 2384 on this device.
        assertTrue("photo overview overlaps the rail", photoBottom < 2384f)
        assertTrue("video overview overlaps the rail", videoBottom < 2384f)
    }
}
