package me.hletrd.telecampro.gl

import me.hletrd.telecampro.camera.PUNCH_IN_CROP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The loupe samples `center ± ((1 - crop) / zoomComp) / 2`. Bounding only the CENTRE to 0..1 lets
 * that window leave the texture, where the external sampler returns edge-clamped garbage — the
 * out-of-bounds smear seen when the loupe is dragged repeatedly against an edge.
 */
class PunchInClampTest {

    private val eps = 1e-5f

    @Test
    fun centerIsPulledInSoTheSampledWindowStaysInsideTheTexture() {
        val half = (1f - PUNCH_IN_CROP) / 2f
        assertEquals(half, clampPunchInCenter(0f, PUNCH_IN_CROP, 1f), eps)
        assertEquals(1f - half, clampPunchInCenter(1f, PUNCH_IN_CROP, 1f), eps)
    }

    @Test
    fun aCenteredLoupeIsUntouched() {
        assertEquals(0.5f, clampPunchInCenter(0.5f, PUNCH_IN_CROP, 1f), eps)
    }

    @Test
    fun alegalOffCenterPositionSurvives() {
        // Well inside the legal band: the clamp must not drag it toward the middle.
        assertEquals(0.35f, clampPunchInCenter(0.35f, PUNCH_IN_CROP, 1f), eps)
    }

    /** Sweeping the whole 0..1 range must never leave the window outside the texture. */
    @Test
    fun noInputEverProducesAnOutOfBoundsWindow() {
        val crop = PUNCH_IN_CROP
        val half = (1f - crop) / 2f
        for (i in 0..100) {
            val c = clampPunchInCenter(i / 100f, crop, 1f)
            assertTrue("low edge escaped at $i", c - half >= -eps)
            assertTrue("high edge escaped at $i", c + half <= 1f + eps)
        }
    }

    /** Zoom compensation shrinks the window, so it legally reaches closer to the edge. */
    @Test
    fun zoomCompensationWidensTheLegalBand() {
        val loose = clampPunchInCenter(0f, PUNCH_IN_CROP, zoomComp = 4f)
        val tight = clampPunchInCenter(0f, PUNCH_IN_CROP, zoomComp = 1f)
        assertTrue("a smaller window must be allowed nearer the edge", loose < tight)
    }

    /**
     * No crop means the window IS the whole frame: there is no legal off-centre position, and a
     * naive coerceIn(half, 1 - half) would throw on the inverted range.
     */
    @Test
    fun aFullFrameWindowPinsToTheCenterInsteadOfThrowing() {
        assertEquals(0.5f, clampPunchInCenter(0f, crop = 0f, zoomComp = 1f), eps)
        assertEquals(0.5f, clampPunchInCenter(1f, crop = 0f, zoomComp = 1f), eps)
    }

    @Test
    fun degenerateZoomCompIsTreatedAsNoMagnification() {
        // zoomComp < 1 cannot enlarge the window beyond the frame; it floors at 1.
        assertEquals(
            clampPunchInCenter(0f, PUNCH_IN_CROP, 1f),
            clampPunchInCenter(0f, PUNCH_IN_CROP, 0.2f),
            eps,
        )
    }
}
