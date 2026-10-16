package me.hletrd.telecampro.ui

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import me.hletrd.telecampro.camera.CameraEngine
import me.hletrd.telecampro.camera.CameraRoute
import me.hletrd.telecampro.camera.CameraRouteInventory
import me.hletrd.telecampro.camera.CaptureMode
import me.hletrd.telecampro.camera.DeviceProfile
import me.hletrd.telecampro.camera.LensChoice
import me.hletrd.telecampro.camera.LensInventory
import me.hletrd.telecampro.camera.ManualControls
import me.hletrd.telecampro.camera.MemorySlot
import me.hletrd.telecampro.storage.SettingsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The ViewModel's optics doors convert zoom with the ENGINE's route law, never with
 * `CameraUiState.rawForcesStandalone` (AGG3-13 converted eight such sites; AGG4-50: only one was
 * pinned). Every door is driven for real against a real pre-start Engine, and after each door the
 * VM's published zoom/lens must equal the Engine's own packet — the Engine is the oracle, so a VM
 * argument that drifts (the stale PMA110 state default on a GENERIC device, the band instead of the
 * route lens, the pre-flip state) fails here instead of persisting a zoom the wire never carried.
 *
 * AGG4-48 rides here too: the FRONT round trip returns the operator's literal lens-local zoom on
 * Video, on standalone Photo+DNG (PMA110 law) and on the logical Photo route.
 */
@RunWith(RobolectricTestRunner::class)
class RouteLawDoorAgreementRobolectricTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val viewModels = mutableListOf<CameraViewModel>()

    @After fun tearDown() = viewModels.forEach(ViewModelTestAccess::clear)

    private class Rig(val vm: CameraViewModel, val engine: CameraEngine) {
        fun engineZoom(): Float = (
            CameraEngine::class.java.getDeclaredField("controls")
                .apply { isAccessible = true }
                .get(engine) as ManualControls
            ).zoomRatio

        fun engineLens(): LensChoice =
            CameraEngine::class.java.getDeclaredField("lensChoice")
                .apply { isAccessible = true }
                .get(engine) as LensChoice
    }

    private fun rig(profile: DeviceProfile, staleStateLaw: Boolean): Rig {
        RobolectricEglSentinels.ensure()
        val engine = CameraEngine(app)
        CameraEngine::class.java.getDeclaredField("deviceProfile")
            .apply { isAccessible = true }
            .set(engine, profile)
        // Both sides convert against the same published optical set (the inventory has landed).
        CameraEngine::class.java.getDeclaredField("acceptedOpticalPresets")
            .apply { isAccessible = true }
            .set(engine, LensInventory.ALL.optical)
        val vm = CameraViewModel(app, engine).also(viewModels::add)
        // A backgrounded Engine: every door still runs its synchronous optics transaction (the
        // packet both sides publish), while the asynchronous setupExecutor reconfigure — which on
        // a camera-less host fails and races a rollback onto main — returns at its `paused` gate.
        CameraEngine::class.java.getDeclaredField("paused")
            .apply { isAccessible = true }
            .setBoolean(engine, true)
        val routes = CameraRouteInventory(back = true, front = true, external = false)
        CameraEngine::class.java.getDeclaredField("cameraRouteInventory")
            .apply { isAccessible = true }
            .set(engine, routes)
        engine.onCameraRouteInventory?.invoke(routes, CameraRoute.BACK)
        idle()
        val state = ViewModelTestAccess.state(vm)
        state.value = state.value.copy(
            caps = ViewModelTestAccess.caps(zoomRatioRange = android.util.Range(0.6f, 20f)),
            // The PMA110 default the state holds until a GENERIC inventory publishes (AGG3-13).
            rawForcesStandalone = staleStateLaw,
        )
        return Rig(vm, engine)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun assertAgree(rig: Rig, door: String, compareLens: Boolean) {
        idle()
        assertEquals("$door: zoom", rig.engineZoom(), rig.vm.state.value.controls.zoomRatio, 1e-4f)
        if (compareLens) assertEquals("$door: lens", rig.engineLens(), rig.vm.state.value.lens)
    }

    private fun Rig.startOn(mode: CaptureMode, dng: Boolean, lens: LensChoice, zoom: Float) {
        if (mode == CaptureMode.VIDEO) vm.onModeChange(CaptureMode.VIDEO)
        vm.onSetPhotoFormats(vm.state.value.photoFormats.copy(dngRaw = dng))
        vm.onLens(lens)
        vm.onZoomRatio(zoom)
        idle()
    }

    private val profiles = listOf(
        "GENERIC with the stale PMA110 state law" to (DeviceProfile.GENERIC to true),
        "PMA110" to (DeviceProfile.PMA110 to true),
    )

    @Test fun `FRONT round trip returns the literal local zoom and matches the Engine`() {
        data class Case(val label: String, val mode: CaptureMode, val dng: Boolean, val lens: LensChoice, val zoom: Float)
        val cases = listOf(
            Case("video tele local 4", CaptureMode.VIDEO, false, LensChoice.TELE3X, 4f),
            Case("photo logical 5x", CaptureMode.PHOTO, false, LensChoice.MAIN, 5f),
            Case("photo dng 5x", CaptureMode.PHOTO, true, LensChoice.MAIN, 5f),
        )
        profiles.forEach { (name, setup) ->
            cases.forEach { case ->
                val rig = rig(setup.first, setup.second)
                rig.startOn(case.mode, case.dng, case.lens, case.zoom)
                val before = rig.vm.state.value.controls.zoomRatio
                assertAgree(rig, "$name ${case.label} start", compareLens = true)
                rig.vm.onToggleFrontCamera()
                assertAgree(rig, "$name ${case.label} enter FRONT", compareLens = true)
                rig.vm.onToggleFrontCamera()
                assertAgree(rig, "$name ${case.label} leave FRONT", compareLens = true)
                assertEquals("$name ${case.label} literal return", before, rig.vm.state.value.controls.zoomRatio, 1e-4f)
            }
        }
    }

    @Test fun `every optics door converts zoom with the Engine law`() {
        val doors: List<Pair<String, (Rig) -> Unit>> = listOf(
            "TC on" to { r -> r.vm.onToggleTeleconverter(true) },
            "TC off" to { r -> r.vm.onToggleTeleconverter(false) },
            "mode to video" to { r -> r.vm.onModeChange(CaptureMode.VIDEO) },
            "mode to photo" to { r -> r.vm.onModeChange(CaptureMode.PHOTO) },
            "DNG off" to { r -> r.vm.onSetPhotoFormats(r.vm.state.value.photoFormats.copy(dngRaw = false)) },
            "DNG on" to { r -> r.vm.onSetPhotoFormats(r.vm.state.value.photoFormats.copy(dngRaw = true)) },
            "lens tele" to { r -> r.vm.onLens(LensChoice.TELE3X) },
            "lens main" to { r -> r.vm.onLens(LensChoice.MAIN) },
            "front round trip" to { r ->
                r.vm.onToggleFrontCamera()
                r.vm.onToggleFrontCamera()
            },
        )
        profiles.forEach { (name, setup) ->
            val rig = rig(setup.first, setup.second)
            rig.startOn(CaptureMode.PHOTO, dng = true, lens = LensChoice.MAIN, zoom = 2f)
            assertAgree(rig, "$name start", compareLens = true)
            doors.forEach { (door, drive) ->
                drive(rig)
                assertAgree(rig, "$name $door", compareLens = true)
            }
        }
    }

    @Test fun `a bank saved while FRONT recalls to the zoom the flip back lands on`() {
        profiles.forEach { (name, setup) ->
            val rig = rig(setup.first, setup.second)
            rig.startOn(CaptureMode.PHOTO, dng = true, lens = LensChoice.MAIN, zoom = 5f)
            rig.vm.onToggleFrontCamera()
            rig.vm.onStoreMemorySlot(MemorySlot.MR3)
            val saved = checkNotNull(SettingsStore(app).loadPreset(MemorySlot.MR3)).controls.zoomRatio
            rig.vm.onToggleFrontCamera()
            // Zoom only: the lens BAND re-derived on leaving FRONT onto the logical route is
            // AGG4-23, owned by its own fix; this test pins the zoom scale and persistence.
            assertAgree(rig, "$name flip back", compareLens = false)
            assertEquals("$name persisted == live return", rig.engineZoom(), saved, 1e-4f)
            rig.vm.onZoomRatio(1f)
            idle()
            rig.vm.onRecallMemorySlot(MemorySlot.MR3)
            assertAgree(rig, "$name recall", compareLens = true)
            assertEquals("$name recall lands the saved zoom", saved, rig.engineZoom(), 1e-4f)
        }
    }
}
