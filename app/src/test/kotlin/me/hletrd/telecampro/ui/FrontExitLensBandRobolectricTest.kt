package me.hletrd.telecampro.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import me.hletrd.telecampro.camera.CameraEngine
import me.hletrd.telecampro.camera.CameraFacing
import me.hletrd.telecampro.camera.CameraRoute
import me.hletrd.telecampro.camera.CameraRouteInventory
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.CaptureMode
import me.hletrd.telecampro.camera.LensChoice
import me.hletrd.telecampro.camera.LensInventory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * AGG4-23, ViewModel half: leaving FRONT onto the LOGICAL photo route must re-band the lens from
 * the unified zoom the trip returns, exactly like the engine transaction, or the rail and the
 * app-side Program handheld rule keep the stale pre-front lens until a later caps reconcile.
 */
@RunWith(RobolectricTestRunner::class)
class FrontExitLensBandRobolectricTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private var viewModel: CameraViewModel? = null

    @Test
    fun `leaving front onto the logical photo route re-bands the lens from the returned zoom`() {
        RobolectricEglSentinels.ensure()
        val engine = CameraEngine(app)
        // Keep any queued Camera2 setup inert; the VM fold is what is under test.
        CameraEngine::class.java.getDeclaredField("paused").apply { isAccessible = true }.setBoolean(engine, true)
        val vm = CameraViewModel(app, engine).also { viewModel = it }
        val routes = CameraRouteInventory(back = true, front = true, external = false)
        // Entered FRONT from an ultra-wide standalone Video at local 3.0 (unified 1.8), then
        // switched to Photo while FRONT: the return lands on the logical route.
        val state = CameraViewModel::class.java.getDeclaredField("_state")
            .apply { isAccessible = true }
            .get(vm) as MutableStateFlow<*>
        val seeded = (state.value as CameraUiState).copy(
            mode = CaptureMode.PHOTO,
            facing = CameraFacing.FRONT,
            activeCameraRoute = CameraRoute.FRONT,
            cameraRoutes = routes,
            lens = LensChoice.ULTRAWIDE,
            lensInventory = LensInventory.ALL,
            teleconverterMode = false,
        )
        MutableStateFlow::class.java.getMethod("setValue", Any::class.java).invoke(state, seeded)
        CameraViewModel::class.java.getDeclaredField("preFrontRearUnifiedZoom")
            .apply { isAccessible = true }
            .setFloat(vm, 1.8f)

        vm.onToggleFrontCamera()

        val after = vm.state.value
        assertEquals(CameraRoute.BACK, after.activeCameraRoute)
        assertEquals(1.8f, after.controls.zoomRatio, 1e-4f)
        assertEquals(LensChoice.MAIN, after.lens)
    }

    @After
    fun tearDown() {
        viewModel?.let { vm ->
            CameraViewModel::class.java.getDeclaredMethod("onCleared")
                .apply { isAccessible = true }
                .invoke(vm)
        }
    }
}
