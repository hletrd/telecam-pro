package me.hletrd.telecampro.ui

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import me.hletrd.telecampro.camera.BackOpticsRefusal
import me.hletrd.telecampro.camera.CameraEngine
import me.hletrd.telecampro.camera.CameraRoute
import me.hletrd.telecampro.camera.CameraRouteInventory
import me.hletrd.telecampro.camera.CameraStatusMessage
import me.hletrd.telecampro.camera.LensChoice
import me.hletrd.telecampro.camera.backOpticsDoorRefusal
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * AGG4-16: the ViewModel's rear-only door and the Engine's defensive twin must answer from the SAME
 * input. They shared the predicate's body but not its argument — the VM passed
 * `facing == FRONT || !cameraRoutes.back`, the Engine `activeCameraRoute != BACK` — so on an
 * EXTERNAL route with a back camera in the inventory the VM published an optimistic lens/TC change
 * the Engine refused, with no rollback to correct it.
 */
@RunWith(RobolectricTestRunner::class)
class RearOpticsDoorAgreementRobolectricTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val viewModels = mutableListOf<CameraViewModel>()

    @After fun tearDown() = viewModels.forEach(ViewModelTestAccess::clear)

    private val inventories = listOf(
        CameraRouteInventory(back = true, front = true, external = true),
        CameraRouteInventory(back = false, front = true, external = true),
    )

    @Test fun `the shared decision refuses every non-rear active route`() {
        assertEquals(BackOpticsRefusal.NONE, backOpticsDoorRefusal(false, CameraRoute.BACK))
        assertEquals(BackOpticsRefusal.FRONT_ROUTE, backOpticsDoorRefusal(false, CameraRoute.FRONT))
        assertEquals(BackOpticsRefusal.FRONT_ROUTE, backOpticsDoorRefusal(false, CameraRoute.EXTERNAL))
        CameraRoute.entries.forEach { route ->
            assertEquals(BackOpticsRefusal.RECORDING, backOpticsDoorRefusal(true, route))
        }
    }

    @Test fun `ViewModel and Engine gates agree over the route and inventory table`() {
        CameraRoute.entries.forEach { route ->
            inventories.filter { route != CameraRoute.BACK || it.back }.forEach { inventory ->
                val expected = backOpticsDoorRefusal(false, route) == BackOpticsRefusal.FRONT_ROUTE
                assertEquals("engine $route $inventory", expected, engineRefuses(route))
                assertEquals("viewmodel $route $inventory", expected, viewModelRefuses(route, inventory))
            }
        }
    }

    private fun setEngineRoute(engine: CameraEngine, route: CameraRoute) {
        CameraEngine::class.java.getDeclaredField("activeCameraRoute")
            .apply { isAccessible = true }
            .set(engine, route)
    }

    private fun engineRefuses(route: CameraRoute): Boolean {
        RobolectricEglSentinels.ensure()
        val engine = CameraEngine(app)
        setEngineRoute(engine, route)
        val statuses = mutableListOf<CameraStatusMessage>()
        engine.onStatus = { status -> status?.message?.let(statuses::add) }
        engine.setLens(LensChoice.TELE3X)
        return CameraStatusMessage.SWITCH_TO_REAR_FIRST in statuses
    }

    private fun viewModelRefuses(route: CameraRoute, inventory: CameraRouteInventory): Boolean {
        RobolectricEglSentinels.ensure()
        val engine = CameraEngine(app)
        val vm = CameraViewModel(app, engine).also(viewModels::add)
        setEngineRoute(engine, route)
        val state = ViewModelTestAccess.state(vm)
        state.value = state.value.copy(
            cameraRoutes = inventory,
            activeCameraRoute = route,
            facing = route.facing,
            lens = LensChoice.MAIN,
            status = null,
        )
        vm.onLens(LensChoice.TELE3X)
        shadowOf(Looper.getMainLooper()).idle()
        val refused = vm.state.value.status?.message == CameraStatusMessage.SWITCH_TO_REAR_FIRST
        // A refusal must leave the optimistic lens unpublished (the AGG4-16 symptom).
        if (refused) assertEquals(LensChoice.MAIN, vm.state.value.lens)
        return refused
    }
}
