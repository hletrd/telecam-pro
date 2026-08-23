package me.hletrd.telecampro.camera

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
    fun `converter route resolves when enabled with photo requiring 4-3`() {
        for (enabled in booleanArrayOf(true, false))
            for (tc in booleanArrayOf(true, false))
                for (video in booleanArrayOf(true, false))
                    for (aspect in AspectRatio.entries) {
                        // VIDEO no longer consults the STILL aspect at all: there the recorded
                        // framing is the video size, so the 4:3 photo setting is unrelated.
                        val expected = enabled && tc && (video || aspect == AspectRatio.W4_3)
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
    fun `video shows the finder for ANY photo aspect - the setting is not consulted there`() {
        // The overview used to be photo-only because the gate read the 4:3 STILL aspect, which made
        // it appear/vanish mid-clip with no visible cause. The fix is to ignore that setting in
        // video, not to withhold the aid from the mode a long lens most needs it in (user-asked
        // 2026-07-29: "loupe is more required for video"). What must NOT return is aspect
        // sensitivity: every still aspect has to behave identically while recording.
        for (aspect in AspectRatio.entries) {
            assertTrue(
                "video finder must not depend on the still aspect ($aspect)",
                teleFinderVisible(true, true, true, aspect, punchIn = true),
            )
        }
    }

    @Test
    fun `video still obeys the loupe and magnification gates`() {
        // Allowing video must not weaken the two gates that make the same-stream overview honest.
        assertFalse("no punch-in", teleFinderVisible(true, true, true, AspectRatio.W16_9, punchIn = false))
        assertFalse(
            "below the zoom floor without a converter",
            teleFinderVisible(true, false, true, AspectRatio.W16_9, punchIn = true, zoomRatio = 2.9f),
        )
    }

    @Test
    fun `past the zoom floor the finder is offered without a converter`() {
        // The finder used to require TELE. A long DIGITAL zoom magnifies past the delivered field
        // just as a converter does, so the honest gate is the magnification, not the accessory.
        assertTrue(teleFinderVisible(true, false, false, AspectRatio.W4_3, punchIn = true, zoomRatio = 3f))
        assertTrue(teleFinderVisible(true, false, false, AspectRatio.W4_3, punchIn = true, zoomRatio = 10f))
    }

    @Test
    fun `below the floor a converterless route still refuses`() {
        // Guards the regression the old raw floor caused: at ordinary zoom the corner box duplicates
        // the main view ~1:1 and adds nothing.
        assertFalse(teleFinderVisible(true, false, false, AspectRatio.W4_3, punchIn = true, zoomRatio = 2.9f))
        assertFalse(teleFinderVisible(true, false, false, AspectRatio.W4_3, punchIn = true, zoomRatio = 1f))
    }

    @Test
    fun `a mounted converter still qualifies at any zoom`() {
        assertTrue(teleFinderVisible(true, true, false, AspectRatio.W4_3, punchIn = true, zoomRatio = 1f))
    }
}
