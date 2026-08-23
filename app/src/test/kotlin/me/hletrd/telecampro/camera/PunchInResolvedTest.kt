package me.hletrd.telecampro.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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
    private val pma110Optical = LensChoice.entries.toSet()

    @Test
    fun logicalPhotoRestoresTheUnifiedFramingHeldBeforeTheFrontTrip() {
        // Standing at 1x with the lens choice pinned to the 3x by earlier TELE use.
        assertEquals(1f, rearReturnZoom(false, 1f, 3f, pma110Optical), 0f)
        assertEquals(5.5f, rearReturnZoom(false, 5.5f, 3f, pma110Optical), 0f)
    }

    @Test
    fun videoAndPhotoDngConvertCanonicalFramingToTheSameStandaloneWireRatio() {
        val videoStandalone = standaloneRouteWanted(true, false, true)
        val photoDngStandalone = standaloneRouteWanted(false, true, true)
        val fromVideo = unifiedZoomOf(LensChoice.TELE3X, 2f, videoStandalone, pma110Optical)
        val fromPhotoDng = unifiedZoomOf(LensChoice.TELE3X, 2f, photoDngStandalone, pma110Optical)

        // Video -> FRONT -> Photo+DNG: both endpoints are standalone, and unified 6x returns local 2x.
        assertEquals(6f, fromVideo, 0f)
        assertEquals(2f, rearReturnZoom(photoDngStandalone, fromVideo, 3f, pma110Optical), 1e-4f)
        // Photo+DNG -> FRONT -> Video is the reverse transition and owns the same framing.
        assertEquals(6f, fromPhotoDng, 0f)
        assertEquals(2f, rearReturnZoom(videoStandalone, fromPhotoDng, 3f, pma110Optical), 1e-4f)
    }

    @Test
    fun fallbackPresetIsConvertedForTheTargetRoute() {
        // A recall or settings restore exits front atomically, without going through the flip.
        assertEquals(3f, rearReturnZoom(false, Float.NaN, 3f, pma110Optical), 0f)
        assertEquals(1f, rearReturnZoom(true, Float.NaN, 3f, pma110Optical), 0f)
    }

    @Test
    fun cropOnlyStandaloneRouteUsesItsPhysicalOneXBase() {
        // On a one-camera tablet the selected 3x band is a crop of MAIN, so unified 3x returns as
        // local 3x rather than being divided by the band or flattened to local 1x.
        assertEquals(
            3f,
            rearReturnZoom(true, 3f, 3f, setOf(LensChoice.MAIN)),
            1e-4f,
        )
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

/**
 * RAW is impossible on the logical photo camera — it advertises the capability and then errors the
 * whole device seconds after a still that carries a RAW target. Every physical camera here really
 * does support RAW, so wanting DNG moves photo onto a standalone lens instead of denying it.
 */
class StandaloneRouteWantedTest {

    @Test
    fun videoAlwaysTakesTheStandaloneRoute() {
        assertTrue(standaloneRouteWanted(videoMode = true, rawWanted = false))
        assertTrue(standaloneRouteWanted(videoMode = true, rawWanted = true))
    }

    @Test
    fun photoWithoutRawKeepsTheSeamlessLogicalRoute() {
        assertFalse(standaloneRouteWanted(videoMode = false, rawWanted = false))
    }

    /** The point of the change: DNG at ANY focal length, not only through the teleconverter. */
    @Test
    fun photoWantingRawSwitchesToAStandaloneLens() {
        assertTrue(standaloneRouteWanted(videoMode = false, rawWanted = true))
    }

    /** Turning DNG back off must restore seamless zoom rather than stranding the standalone route. */
    @Test
    fun droppingRawReturnsToTheLogicalRoute() {
        assertTrue(standaloneRouteWanted(videoMode = false, rawWanted = true))
        assertFalse(standaloneRouteWanted(videoMode = false, rawWanted = false))
    }
}
