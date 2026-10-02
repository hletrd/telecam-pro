package me.hletrd.telecampro.ui

import android.util.Range
import me.hletrd.telecampro.camera.CameraFacing
import me.hletrd.telecampro.camera.CameraRoute
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.CaptureMode
import me.hletrd.telecampro.camera.LensChoice
import me.hletrd.telecampro.camera.LensInventory
import me.hletrd.telecampro.camera.PhotoFormats
import me.hletrd.telecampro.camera.TELECONVERTER_MAGNIFICATION
import me.hletrd.telecampro.camera.teleDisplayBase
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * AGG5-12 / AGG5-49: the HUD pill, the ZOOM Fn chip/value, the Quick Zoom ruler and the Shoot-tab
 * Zoom slider all read [mainRelativeZoomMultiplier]. These rows pin the ROUTE INPUTS that property
 * reads off live state — `CameraCaps` needs `android.util.Range`, hence Robolectric — so the slider
 * (which used to read lens-local "1.0×" under a "3.0×" chip on every rear standalone route) and the
 * logical route (which read "3.1×" at the 3× preset on PMA110's 23.4 mm camera) cannot drift again.
 */
@RunWith(RobolectricTestRunner::class)
class MainRelativeZoomMultiplierRobolectricTest {
    private val pmaInventory = LensInventory(
        available = LensChoice.entries.toSet(),
        optical = LensChoice.entries.toSet(),
        presetEquivMm = mapOf(LensChoice.MAIN to 23.4f, LensChoice.TELE3X to 69.4f),
    )

    private fun state(
        mode: CaptureMode,
        equivMm: Float,
        dng: Boolean = false,
        rawForcesStandalone: Boolean = true,
        inventory: LensInventory = pmaInventory,
        teleconverter: Boolean = false,
        facing: CameraFacing = CameraFacing.BACK,
        route: CameraRoute = CameraRoute.BACK,
    ) = CameraUiState(
        mode = mode,
        photoFormats = PhotoFormats(heif = true, dngRaw = dng),
        rawForcesStandalone = rawForcesStandalone,
        lensInventory = inventory,
        teleconverterMode = teleconverter,
        facing = facing,
        activeCameraRoute = route,
        caps = ViewModelTestAccess.caps(zoomRatioRange = Range(1f, 10f), equivalentFocalMm = equivMm),
    )

    @Test
    fun `logical photo route reads its wire zoom 1 to 1`() {
        val s = state(CaptureMode.PHOTO, equivMm = 23.4f)
        assertEquals(1f, s.mainRelativeZoomMultiplier, 0f)
        assertEquals("3.0×", formatDisplayZoom(3f, s.mainRelativeZoomMultiplier))
    }

    @Test
    fun `video and DNG photo standalone routes read the 70 mm lens as 3x on every surface`() {
        for (s in listOf(
            state(CaptureMode.VIDEO, equivMm = 69.4f),
            state(CaptureMode.PHOTO, equivMm = 69.4f, dng = true),
        )) {
            val mul = s.mainRelativeZoomMultiplier
            // MRG5-4: 23.4 mm is inside the caption band, so the nominal 23 mm divides (byte-identical).
            assertEquals(69.4f / 23f, mul, 1e-5f)
            val slider = zoomRulerScale(1f, 10f, mul, s.teleconverterMode)
            assertEquals("3.0×", formatZoomMultiplier(slider.display(1f)))
            assertEquals("3.0×", formatDisplayZoom(1f, mul))
            assertEquals(2f, slider.localForDisplay(2f * mul), 1e-4f)
        }
    }

    @Test
    fun `a GENERIC device keeps DNG photo on the logical scale`() {
        val s = state(CaptureMode.PHOTO, equivMm = 26f, dng = true, rawForcesStandalone = false)
        assertEquals(1f, s.mainRelativeZoomMultiplier, 0f)
    }

    @Test
    fun `a 26 mm tablet main reads 1x on its standalone video route`() {
        val tablet = LensInventory(
            available = setOf(LensChoice.MAIN, LensChoice.TELE3X),
            optical = setOf(LensChoice.MAIN),
            presetEquivMm = mapOf(LensChoice.MAIN to 26f, LensChoice.TELE3X to 78f),
        )
        val s = state(CaptureMode.VIDEO, equivMm = 26f, inventory = tablet)
        assertEquals(1f, s.mainRelativeZoomMultiplier, 0f)
        assertEquals("3.0×", formatDisplayZoom(3f, s.mainRelativeZoomMultiplier))
    }

    @Test
    fun `TELE and FRONT keep their own scales`() {
        assertEquals(
            teleDisplayBase(TELECONVERTER_MAGNIFICATION),
            state(CaptureMode.PHOTO, equivMm = 69.4f, teleconverter = true).mainRelativeZoomMultiplier,
            0f,
        )
        assertEquals(
            1f,
            state(CaptureMode.VIDEO, equivMm = 21.5f, facing = CameraFacing.FRONT, route = CameraRoute.FRONT)
                .mainRelativeZoomMultiplier,
            0f,
        )
    }
}
