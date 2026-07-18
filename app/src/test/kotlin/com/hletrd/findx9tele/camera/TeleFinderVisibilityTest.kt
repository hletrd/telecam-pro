package com.hletrd.findx9tele.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Truth table for the shared TELE-finder gate. The engine (`pushTeleFinder` → GL resolved flag)
 * and the Compose border both consume these functions; before the extraction the condition was
 * hand-written in three places and a one-sided edit could silently desync the white border from
 * the GL-drawn PIP content.
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
        // the loupe is the one case the single-stream PIP is genuinely wider than the main view —
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
    fun `video mode never shows the finder regardless of the photo aspect setting`() {
        // The 4:3 gate is the STILL aspect; in video it used to make the PIP appear/vanish with an
        // unrelated photo setting. Photo-only closes that semantic surprise.
        for (aspect in AspectRatio.entries) {
            assertFalse(teleFinderVisible(true, true, true, aspect, punchIn = true))
        }
    }
}
