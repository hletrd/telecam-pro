package me.hletrd.telecampro.ui

import me.hletrd.telecampro.camera.CameraRoute
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.LensChoice
import me.hletrd.telecampro.camera.LensInventory
import me.hletrd.telecampro.camera.ManualControls
import me.hletrd.telecampro.camera.PhoneModel
import me.hletrd.telecampro.camera.TeleconverterProfile
import me.hletrd.telecampro.ui.overlays.statusBarFocalLabel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * AGG4-14: the OSD focal, the app-side PROGRAM handheld rule and the focus-detail exposure gate
 * read ONE effective focal, zoom included. Before, PROGRAM used the preset's nominal focal ×
 * converter with no zoom: at TC local 4× the OSD said ~1200 mm and PROGRAM held 1/300 s.
 *
 * PENDING DEVICE: this deliberately changes PMA110 exposure at zoom > 1 (a faster program shutter).
 */
@RunWith(RobolectricTestRunner::class) // CameraCaps carries android.util.Range/Size
class EffectiveFocalUnityTest {
    private data class Case(
        val label: String,
        val state: CameraUiState,
        val expectedMm: Float,
        val tele: Boolean,
        val osd: String,
    )

    private fun state(
        equivMm: Float,
        zoom: Float,
        lens: LensChoice,
        teleconverter: Boolean = false,
        route: CameraRoute = CameraRoute.BACK,
    ) = CameraUiState(
        caps = ViewModelTestAccess.caps(equivalentFocalMm = equivMm),
        controls = ManualControls(zoomRatio = zoom),
        lens = lens,
        teleconverterMode = teleconverter,
        activeCameraRoute = route,
    )

    private val cases = listOf(
        // Logical seamless camera: main-relative 9.5× on the 23 mm main, band still TELE3X (70 mm).
        Case("logical 9.5x", state(23f, 9.5f, LensChoice.TELE3X), 218.5f, tele = false, osd = "219 mm"),
        // Hasselblad 300 mm on the ~70 mm periscope at lens-local 4×.
        Case("TC local 4x", state(69.4f, 4f, LensChoice.TELE3X, teleconverter = true), 1200f, tele = true, osd = "1200 mm TELE"),
        // PMA110 DNG standalone MAIN at lens-local 3×.
        Case("DNG standalone 3x", state(23f, 3f, LensChoice.MAIN), 69f, tele = false, osd = "69 mm"),
        // FRONT never uses the retained rear band.
        Case("FRONT 2x", state(24f, 2f, LensChoice.TELE3X, route = CameraRoute.FRONT), 48f, tele = false, osd = "48 mm"),
        // OTHER phone + generic 1.5× on its measured 26 mm host.
        Case(
            "OTHER generic 1.5x",
            state(26f, 1f, LensChoice.TELE3X, teleconverter = true).copy(
                phoneModel = PhoneModel.OTHER,
                teleconverterProfile = TeleconverterProfile.GENERIC_1_5,
                lensInventory = LensInventory.ALL.copy(teleHostEquivMm = 26f),
            ),
            39f,
            tele = true,
            osd = "40 mm TELE",
        ),
    )

    @Test fun `OSD focal and the handheld program rule come from the same number`() {
        cases.forEach { case ->
            val eff = checkNotNull(case.state.effectiveEquivFocalMm) { case.label }
            assertEquals(case.label, case.expectedMm, eff, 0.01f)
            assertEquals(case.label, (1_000_000_000f / eff).toLong(), programHandheldShutterNs(case.state))
            assertEquals(case.label, case.osd, statusBarFocalLabel(eff, case.tele, "TELE"))
        }
    }

    @Test fun `before caps publish the nominal preset rule stands in and the OSD says --`() {
        val noCaps = CameraUiState(lens = LensChoice.TELE3X, teleconverterMode = true, controls = ManualControls(zoomRatio = 4f))
        assertEquals(null, noCaps.effectiveEquivFocalMm)
        assertEquals("--", statusBarFocalLabel(null, teleconverterMode = true, teleLabel = "TELE"))
        assertEquals(
            preferredProgramShutterNs(70f, teleconverterMode = true, noCaps.teleconverterMagnification),
            programHandheldShutterNs(noCaps),
        )
        val front = CameraUiState(activeCameraRoute = CameraRoute.FRONT, teleconverterMode = true, lens = LensChoice.TELE3X)
        assertEquals(handheldShutterNs(LensChoice.MAIN.targetEquivMm), programHandheldShutterNs(front))
    }
}
