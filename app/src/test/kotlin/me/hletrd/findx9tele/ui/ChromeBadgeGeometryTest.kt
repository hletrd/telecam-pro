package me.hletrd.findx9tele.ui

import me.hletrd.findx9tele.camera.ShutterTimer
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the chrome plate badge — flash AUTO's "A" and the armed self-timer's seconds — inside the
 * plate's circular clip and clear of the centred glyph.
 *
 * Both badges shipped CLIPPED: at `Alignment.BottomEnd` with 3 dp / 2 dp of corner padding their
 * ink sat outside an 18 dp clip radius, and the arc cut the bottom off every round glyph ("3" read
 * as a "?"). These re-derive the containment from the exact constants the composables draw with, so
 * a nudged inset, a larger badge or a larger centred glyph fails here and not on a device.
 */
class ChromeBadgeGeometryTest {

    /** Every string either badge can show: the armed self-timer values, plus flash AUTO's "A". */
    private val badgeStrings: List<String> =
        ShutterTimer.entries.filter { it != ShutterTimer.OFF }.map { it.seconds.toString() } + "A"

    /**
     * Below this a badge is not clipped but reads as touching the ring or the plate edge: on the
     * target panel (~3.5 px/dp) half a dp is under two pixels of visible air.
     */
    private val minVisibleClearanceDp = 0.5f

    @Test
    fun badgeInkStaysInsideThePlateClip() {
        for (text in badgeStrings) {
            val clearance = chromeBadgePlateClearanceDp(text)
            assertTrue(
                "badge \"$text\" must clear the plate clip, got $clearance dp",
                clearance > minVisibleClearanceDp,
            )
        }
    }

    @Test
    fun badgeInkClearsTheCentredGlyph() {
        val clearance = chromeBadgeGlyphClearanceDp()
        assertTrue(
            "badge must sit below the glyph's lowest ink, got $clearance dp",
            clearance > minVisibleClearanceDp,
        )
    }

    @Test
    fun cornerAnchoredBadgeMeasuresAsClipped() {
        // The exact placement both buttons shipped with: BottomEnd, padding(end = 3.dp, bottom = 2.dp).
        // If this ever measures as fitting, the model has stopped describing the defect it was built
        // from and the clearances above prove nothing.
        for (text in badgeStrings) {
            val clearance = chromeBadgePlateClearanceDp(text, endInsetDp = 3f, bottomInsetDp = 2f)
            assertTrue(
                "the shipped corner placement must measure as clipped, got $clearance dp for \"$text\"",
                clearance < 0f,
            )
        }
    }
}
