package com.hletrd.findx9tele.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Truth table for the same-stream Loupe Overview gate. The engine (`pushTeleFinder` → GL flag)
 * and the Compose border both consume these functions; before the extraction the condition was
 * hand-written in three places and a one-sided edit could silently desync the white border from
 * the GL-drawn overview content. This does not claim a separate 1x camera source.
 */
class TeleFinderVisibilityTest {

    @Test
    fun `resolved only in enabled TELE photo 4-3`() {
        for (enabled in booleanArrayOf(true, false))
            for (tc in booleanArrayOf(true, false))
                for (video in booleanArrayOf(true, false))
                    for (aspect in AspectRatio.entries) {
                        val expected = enabled && tc && !video && aspect == AspectRatio.W4_3
                        assertEquals(
                            "enabled=$enabled tc=$tc video=$video aspect=$aspect",
                            expected,
                            teleFinderResolved(enabled, tc, video, aspect),
                        )
                    }
    }

    @Test
    fun `visibility is the resolved flag AND an active punch-in loupe - the GL decomposition`() {
        // GL gates its resolved flag on its own punch-in state; the Compose border uses
        // teleFinderVisible directly. The two decompositions must be the same function (AGG4-29:
        // the loupe is the one case the same-stream overview is wider than the main view —
        // the old raw zoom floor showed a ~1:1 duplicate corner box at steady state).
        for (enabled in booleanArrayOf(true, false))
            for (tc in booleanArrayOf(true, false))
                for (video in booleanArrayOf(true, false))
                    for (aspect in AspectRatio.entries)
                        for (punchIn in booleanArrayOf(true, false)) {
                            val glStyle = teleFinderResolved(enabled, tc, video, aspect) && punchIn
                            assertEquals(
                                "enabled=$enabled tc=$tc video=$video aspect=$aspect punchIn=$punchIn",
                                glStyle,
                                teleFinderVisible(enabled, tc, video, aspect, punchIn),
                            )
                        }
    }

    @Test
    fun `loupe off hides the finder even fully resolved`() {
        assertTrue(teleFinderVisible(true, true, false, AspectRatio.W4_3, punchIn = true))
        assertFalse(teleFinderVisible(true, true, false, AspectRatio.W4_3, punchIn = false))
    }

    @Test
    fun `front route resolves false through the forced-off converter axis`() {
        // The gate deliberately has NO facing axis: entering FRONT forces teleconverterMode=false
        // in the same optics transaction and the TC toggle refuses while FRONT, so tc=false IS the
        // front truth — even a stale enabled toggle with the loupe up cannot draw the PIP there.
        assertFalse(teleFinderResolved(true, false, false, AspectRatio.W4_3))
        assertFalse(teleFinderVisible(true, false, false, AspectRatio.W4_3, punchIn = true))
    }

    @Test
    fun `video mode never shows the finder regardless of the photo aspect setting`() {
        // The 4:3 gate is the STILL aspect; in video it used to make the overview appear/vanish with an
        // unrelated photo setting. Photo-only closes that semantic surprise.
        for (aspect in AspectRatio.entries) {
            assertFalse(teleFinderVisible(true, true, true, aspect, punchIn = true))
        }
    }
}
