package me.hletrd.telecampro.ui

import me.hletrd.telecampro.camera.CameraFacing
import me.hletrd.telecampro.camera.CameraRouteInventory
import me.hletrd.telecampro.camera.CameraRoute
import me.hletrd.telecampro.camera.CameraUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraRouteStartupTest {
    @Test
    fun `ordinary rear phone preserves its complete pre-enumeration optics state`() {
        val before = CameraUiState(
            facing = CameraFacing.BACK,
            teleconverterMode = true,
            controls = CameraUiState().controls.copy(zoomRatio = 3f),
        )
        val routes = CameraRouteInventory(back = true, front = true, external = false)
        val after = cameraRoutePublishedState(before, routes, CameraRoute.BACK, rawForcesStandalone = true)

        assertEquals(routes, after.cameraRoutes)
        assertEquals(before.facing, after.facing)
        assertEquals(before.teleconverterMode, after.teleconverterMode)
        assertEquals(before.controls, after.controls)
        assertEquals(before.rawForcesStandalone, after.rawForcesStandalone)
    }

    @Test
    fun `front-only startup publishes front and clears impossible rear optics`() {
        val routes = CameraRouteInventory(back = false, front = true, external = false)
        val after = cameraRoutePublishedState(
            CameraUiState(teleconverterMode = true, controls = CameraUiState().controls.copy(zoomRatio = 10f)),
            routes,
            CameraRoute.FRONT,
            rawForcesStandalone = false,
        )

        assertEquals(CameraFacing.FRONT, after.facing)
        assertFalse(after.teleconverterMode)
        assertEquals(1f, after.controls.zoomRatio)
        assertFalse(after.cameraRoutes.switchAvailable)
        assertFalse(after.rawForcesStandalone)
    }

    @Test
    fun `external plus front starts front but keeps a real switch destination`() {
        val routes = CameraRouteInventory(back = false, front = true, external = true)
        val after = cameraRoutePublishedState(
            CameraUiState(), routes, CameraRoute.FRONT, rawForcesStandalone = false,
        )
        assertEquals(CameraFacing.FRONT, after.facing)
        assertTrue(after.cameraRoutes.switchAvailable)
    }

    @Test
    fun `external startup is explicit lens-local and clears hidden tele`() {
        val routes = CameraRouteInventory(back = false, front = false, external = true)
        val after = cameraRoutePublishedState(
            CameraUiState(teleconverterMode = true, controls = CameraUiState().controls.copy(zoomRatio = 4f)),
            routes,
            CameraRoute.EXTERNAL,
            rawForcesStandalone = false,
        )

        assertEquals(CameraRoute.EXTERNAL, after.activeCameraRoute)
        assertEquals(CameraFacing.BACK, after.facing)
        assertFalse(after.teleconverterMode)
        assertEquals(1f, after.controls.zoomRatio)
    }
}
