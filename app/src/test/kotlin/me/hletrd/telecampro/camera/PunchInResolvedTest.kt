package me.hletrd.telecampro.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The loupe magnifies to check critical focus at long focal lengths. On a ~21 mm selfie lens that
 * crop buys nothing and makes the finder disagree with the saved file — preview narrow, picture
 * full-frame — which is precisely how it was reported, twice.
 */
class PunchInResolvedTest {

    @Test
    fun theRearRouteAppliesTheOperatorsToggle() {
        assertTrue(punchInResolved(enabled = true, frontFacing = false))
        assertFalse(punchInResolved(enabled = false, frontFacing = false))
    }

    @Test
    fun theFrontRouteNeverApplensTheLoupe() {
        assertFalse(punchInResolved(enabled = true, frontFacing = true))
        assertFalse(punchInResolved(enabled = false, frontFacing = true))
    }

    /**
     * Suppressed, not cleared: the front route must not consume the toggle, or returning to the
     * rear would silently drop an aid the operator had switched on.
     */
    @Test
    fun theToggleSurvivesAFrontTrip() {
        val intent = true
        assertFalse("suppressed while front", punchInResolved(intent, frontFacing = true))
        assertTrue("restored on return", punchInResolved(intent, frontFacing = false))
    }
}
