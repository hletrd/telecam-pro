package me.hletrd.telecampro.stab

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The reported device orientation must take a DECISIVE turn to change, like Android's own
 * OrientationEventListener. A bare 45° snap re-reports on the slightest movement near a diagonal,
 * and every rotating glyph twitches with it.
 */
class OrientationHysteresisTest {

    @Test
    fun stayingInsideTheCurrentQuadrantChangesNothing() {
        for (roll in -44..44) {
            assertEquals(0, snapToQuadrantHysteretic(roll.toFloat(), current = 0))
        }
    }

    /** The old behaviour: anything past 45° flipped. That is the sensitivity being fixed. */
    @Test
    fun justPastTheMidpointNoLongerFlips() {
        assertEquals(0, snapToQuadrantHysteretic(46f, current = 0))
        assertEquals(0, snapToQuadrantHysteretic(55f, current = 0))
        assertEquals(0, snapToQuadrantHysteretic(-55f, current = 0))
    }

    @Test
    fun aDecisiveTurnIsAdopted() {
        assertEquals(90, snapToQuadrantHysteretic(61f, current = 0))
        assertEquals(90, snapToQuadrantHysteretic(90f, current = 0))
        assertEquals(270, snapToQuadrantHysteretic(-90f, current = 0))
        assertEquals(180, snapToQuadrantHysteretic(180f, current = 0))
    }

    /** Asymmetry is the point: easy to hold, deliberate to leave — from every quadrant. */
    @Test
    fun theBandIsAsymmetricFromEveryQuadrant() {
        for (current in intArrayOf(0, 90, 180, 270)) {
            assertEquals(current, snapToQuadrantHysteretic(current + 55f, current = current))
            assertEquals(current, snapToQuadrantHysteretic(current - 55f, current = current))
            val next = (current + 90) % 360
            assertEquals(next, snapToQuadrantHysteretic(current + 61f, current = current))
        }
    }

    /** Wrap must not create a dead zone at the 180 seam. */
    @Test
    fun theWrapSeamBehavesLikeAnyOtherBoundary() {
        assertEquals(180, snapToQuadrantHysteretic(179f, current = 90))
        assertEquals(180, snapToQuadrantHysteretic(-179f, current = 270))
    }
}
