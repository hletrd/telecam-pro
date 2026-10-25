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
import me.hletrd.telecampro.camera.PhoneModel
import me.hletrd.telecampro.camera.lensInventoryOf
import me.hletrd.telecampro.ui.controls.lensLabel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * A lens chip APPLIES its own label (owner decision 2026-10-25, "follow how the maker does it"): the
 * real ViewModel door is driven against a real pre-start Engine, and after each tap both sides must
 * hold the same wire zoom, the same lens band, and a pill that reads the chip's label. PMA110 is the
 * first row — its four chips must keep landing on the historical 0.6 / 1 / 3 / 10.
 */
@RunWith(RobolectricTestRunner::class)
class MakerZoomChipsRobolectricTest {
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

    private fun setField(engine: CameraEngine, name: String, value: Any?) {
        CameraEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.set(engine, value)
    }

    private fun rig(
        profile: DeviceProfile,
        phone: PhoneModel,
        inventory: LensInventory,
        range: Pair<Float, Float>,
    ): Rig {
        RobolectricEglSentinels.ensure()
        val engine = CameraEngine(app)
        setField(engine, "deviceProfile", profile)
        // Both sides read the same published inventory measurement (it has landed).
        setField(engine, "acceptedOpticalPresets", inventory.optical)
        setField(engine, "acceptedMainEquivMm", inventory.presetEquivMm[LensChoice.MAIN])
        setField(engine, "acceptedHomeEquivMm", inventory.homeEquivMm)
        val vm = CameraViewModel(app, engine).also(viewModels::add)
        // Backgrounded: every door runs its synchronous optics transaction, the async reconfigure
        // returns at its `paused` gate (no camera on the host).
        CameraEngine::class.java.getDeclaredField("paused").apply { isAccessible = true }.setBoolean(engine, true)
        val routes = CameraRouteInventory(back = true, front = true, external = false)
        setField(engine, "cameraRouteInventory", routes)
        engine.onCameraRouteInventory?.invoke(routes, CameraRoute.BACK)
        idle()
        val state = ViewModelTestAccess.state(vm)
        state.value = state.value.copy(
            lensInventory = inventory,
            caps = ViewModelTestAccess.caps(
                zoomRatioRange = android.util.Range(range.first, range.second),
                equivalentFocalMm = inventory.homeEquivMm,
            ),
        )
        // The phone is the operator's declaration; this door also hands it to the engine.
        vm.onPhoneModel(phone)
        idle()
        return Rig(vm, engine)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Taps [choice] and returns the pill text on the logical Photo route, after checking agreement. */
    private fun Rig.tap(choice: LensChoice): String {
        vm.onLens(choice)
        idle()
        val s = vm.state.value
        assertEquals("$choice: zoom", engineZoom(), s.controls.zoomRatio, 0f)
        assertEquals("$choice: lens", engineLens(), s.lens)
        assertEquals("$choice: band", choice, s.lens)
        return formatDisplayZoom(s.controls.zoomRatio, s.mainRelativeZoomMultiplier)
    }

    @Test fun `PMA110 chips land on the historical presets`() {
        val inventory = lensInventoryOf(listOf(14f, 23.4f, 69.4f, 230f), 0.6f to 20f, homeEquivMm = 23.4f)
        val rig = rig(DeviceProfile.PMA110, PhoneModel.FIND_X9_ULTRA, inventory, 0.6f to 20f)
        val presets = rig.vm.state.value.zoomPresets
        mapOf(
            LensChoice.ULTRAWIDE to 0.6f,
            LensChoice.MAIN to 1f,
            LensChoice.TELE3X to 3f,
            LensChoice.TELE10X to 10f,
        ).forEach { (choice, wire) ->
            rig.tap(choice)
            assertEquals("$choice wire", wire.toRawBits(), rig.vm.state.value.controls.zoomRatio.toRawBits())
            assertEquals(1f, rig.vm.state.value.mainRelativeZoomMultiplier, 0f)
        }
        assertEquals(
            listOf("0.6×", "1×", "3×", "10×"),
            LensChoice.entries.map { lensLabel(it, presets) },
        )
    }

    @Test fun `Find X10's 2_8x chip reads 2_8x and its Video trip opens the tele`() {
        val inventory = lensInventoryOf(listOf(15f, 23f, 65f), 0.6f to 120f, homeEquivMm = 23f)
        val rig = rig(DeviceProfile.GENERIC, PhoneModel.FIND_X10, inventory, 0.6f to 120f)
        assertEquals("2.8×", rig.tap(LensChoice.TELE3X))
        assertEquals(2.8f, rig.vm.state.value.controls.zoomRatio, 0f)
        assertEquals("1.0×", rig.tap(LensChoice.MAIN))
        assertEquals("0.6×", rig.tap(LensChoice.ULTRAWIDE))
        rig.tap(LensChoice.TELE3X)
        rig.vm.onModeChange(CaptureMode.VIDEO)
        idle()
        // The standalone route lands on the 65 mm lens at its own 1x — not a 2.8x crop of the main.
        assertEquals(LensChoice.TELE3X, rig.vm.state.value.lens)
        assertEquals(1f, rig.vm.state.value.controls.zoomRatio, 0f)
        assertEquals(rig.engineZoom(), rig.vm.state.value.controls.zoomRatio, 0f)
        assertEquals(rig.engineLens(), rig.vm.state.value.lens)
    }

    @Test fun `X300 Ultra's MAIN chip reads 1_5x and its tele 3_7x on the logical route`() {
        val inventory = lensInventoryOf(listOf(14f, 35f, 85f), 0.4f to 30f, homeEquivMm = 35f)
        val rig = rig(DeviceProfile.GENERIC, PhoneModel.VIVO_X300_ULTRA, inventory, 0.4f to 30f)
        assertEquals("1.5×", rig.tap(LensChoice.MAIN))
        // The MAIN chip opens the main lens at the logical camera's own 1.0.
        assertEquals(1f, rig.vm.state.value.controls.zoomRatio, 0f)
        assertEquals("3.7×", rig.tap(LensChoice.TELE3X))
        assertEquals("0.6×", rig.tap(LensChoice.ULTRAWIDE))
        assertEquals("1.5×", lensLabel(LensChoice.MAIN, rig.vm.state.value.zoomPresets))
    }
}
