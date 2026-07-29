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

/**
 * Leaving FRONT must restore the framing the operator had, not the lens preset. Once TELE has been
 * used the preset is 3x for the rest of the session, so the preset fallback silently zoomed them in
 * on every flip back (user-reported 2026-07-28).
 */
class RearReturnZoomTest {

    @Test
    fun photoRestoresTheFramingHeldBeforeTheFrontTrip() {
        // Standing at 1x with the lens choice pinned to the 3x by earlier TELE use.
        assertTrue(rearReturnZoom(videoMode = false, preFrontZoom = 1f, lensPreset = 3f) == 1f)
        assertTrue(rearReturnZoom(videoMode = false, preFrontZoom = 5.5f, lensPreset = 3f) == 5.5f)
    }

    @Test
    fun videoReturnsToLensLocalOneRegardlessOfTheSnapshot() {
        // The video route pins a standalone lens, so a photo-scale ratio does not transfer.
        assertTrue(rearReturnZoom(videoMode = true, preFrontZoom = 5.5f, lensPreset = 3f) == 1f)
    }

    @Test
    fun theLensPresetIsTheFallbackWhenNothingWasCaptured() {
        // A recall or settings restore exits front atomically, without going through the flip.
        assertTrue(rearReturnZoom(videoMode = false, preFrontZoom = Float.NaN, lensPreset = 3f) == 3f)
        assertTrue(rearReturnZoom(videoMode = false, preFrontZoom = 0f, lensPreset = 3f) == 3f)
    }
}

/**
 * 10-bit costs the in-REC snapshot (its session rung drops JPEG/RAW because HLG10 + full-res JPEG +
 * RAW crashes this HAL), so it must be asked for only where the bits buy something.
 */
class TenBitSessionWantedTest {

    @Test
    fun videoWithANonSdrTransferWantsTenBit() {
        assertTrue(tenBitSessionWanted(videoMode = true, transfer = ColorTransfer.HLG))
        assertTrue(tenBitSessionWanted(videoMode = true, transfer = ColorTransfer.SLOG3))
        assertTrue(tenBitSessionWanted(videoMode = true, transfer = ColorTransfer.SLOG3_CINE))
        assertTrue(tenBitSessionWanted(videoMode = true, transfer = ColorTransfer.LOGC3))
    }

    @Test
    fun sdrVideoStaysEightBit() {
        assertFalse(tenBitSessionWanted(videoMode = true, transfer = ColorTransfer.SDR))
    }

    /** Photo must never pay the cost, whatever transfer happens to be remembered from video. */
    @Test
    fun photoNeverWantsTenBit() {
        ColorTransfer.entries.forEach {
            assertFalse("photo asked for 10-bit with $it", tenBitSessionWanted(videoMode = false, transfer = it))
        }
    }
}
