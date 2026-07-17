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
    fun `visibility is the resolved flag AND the zoom floor - the GL decomposition`() {
        // GL gates its resolved flag on the live zoom target; the Compose border uses
        // teleFinderVisible directly. The two decompositions must be the same function.
        for (enabled in booleanArrayOf(true, false))
            for (tc in booleanArrayOf(true, false))
                for (video in booleanArrayOf(true, false))
                    for (aspect in AspectRatio.entries)
                        for (zoom in floatArrayOf(1f, FINDER_MIN_ZOOM - 0.01f, FINDER_MIN_ZOOM, 3f)) {
                            val glStyle = teleFinderResolved(enabled, tc, video, aspect) &&
                                zoom >= FINDER_MIN_ZOOM
                            assertEquals(
                                "enabled=$enabled tc=$tc video=$video aspect=$aspect zoom=$zoom",
                                glStyle,
                                teleFinderVisible(enabled, tc, video, aspect, zoom),
                            )
                        }
    }

    @Test
    fun `zoom floor boundary is inclusive`() {
        assertTrue(teleFinderVisible(true, true, false, AspectRatio.W4_3, FINDER_MIN_ZOOM))
        assertFalse(
            teleFinderVisible(true, true, false, AspectRatio.W4_3, FINDER_MIN_ZOOM - 1e-4f),
        )
    }

    @Test
    fun `video mode never shows the finder regardless of the photo aspect setting`() {
        // The 4:3 gate is the STILL aspect; in video it used to make the PIP appear/vanish with an
        // unrelated photo setting. Photo-only closes that semantic surprise.
        for (aspect in AspectRatio.entries) {
            assertFalse(teleFinderVisible(true, true, true, aspect, 5f))
        }
    }
}
