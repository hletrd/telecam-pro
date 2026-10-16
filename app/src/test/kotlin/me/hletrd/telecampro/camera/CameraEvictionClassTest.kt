package me.hletrd.telecampro.camera

import android.hardware.camera2.CameraDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Eviction — another client taking the camera — is ordinary multitasking, not a fault, and the
 * health path must not announce it (user-reported intermittent "camera error" on app switches,
 * 2026-07-31). These pin the classifier both ways: eviction-class losses stay quiet, genuine
 * device/service faults keep their announcement.
 */
class CameraEvictionClassTest {

    @Test
    fun `in-use and max-cameras codes are eviction class`() {
        assertTrue(cameraErrorCodeIsEviction(CameraDevice.StateCallback.ERROR_CAMERA_IN_USE))
        assertTrue(cameraErrorCodeIsEviction(CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE))
    }

    @Test
    fun `device, service and disabled faults are NOT eviction class`() {
        assertFalse(cameraErrorCodeIsEviction(CameraDevice.StateCallback.ERROR_CAMERA_DEVICE))
        assertFalse(cameraErrorCodeIsEviction(CameraDevice.StateCallback.ERROR_CAMERA_SERVICE))
        assertFalse(cameraErrorCodeIsEviction(CameraDevice.StateCallback.ERROR_CAMERA_DISABLED))
    }

    @Test
    fun `typed disconnects classify as eviction, generic failures never do`() {
        assertTrue(cameraFailureIsEviction(CameraEvictedException("Camera disconnected")))
        assertFalse(cameraFailureIsEviction(IllegalStateException("Camera error 4")))
        assertFalse(cameraFailureIsEviction(RuntimeException("provider died")))
    }
}
