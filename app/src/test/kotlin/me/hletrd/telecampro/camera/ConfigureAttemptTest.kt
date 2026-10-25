package me.hletrd.telecampro.camera

import android.hardware.camera2.CameraAccessException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The configure-attempt gate, the dead-device classifier and the RAW log route key (ConfigureAttempt.kt). */
class ConfigureAttemptTest {

    @Test
    fun `a configure failure is handled once, by whichever path reports it first`() {
        val gate = ConfigureAttemptGate()
        val attempt = gate.begin()
        // createCaptureSession's synchronous throw…
        assertTrue(gate.claim(attempt))
        // …and the onConfigureFailed Camera2 also posted for the same attempt.
        assertFalse(gate.claim(attempt))
    }

    @Test
    fun `a newer attempt makes every older attempt's late callback inert`() {
        val gate = ConfigureAttemptGate()
        val first = gate.begin()
        val second = gate.begin()
        assertFalse(gate.claim(first))
        assertTrue(gate.claim(second))
        assertFalse(gate.claim(second))
        // Nothing is claimable before the first attempt begins.
        assertFalse(ConfigureAttemptGate().claim(0L))
    }

    @Test
    fun `only reasons that mean the device is gone stop the ladder`() {
        assertTrue(configureFailureIsDeviceFatal(CameraAccessException.CAMERA_ERROR))
        assertTrue(configureFailureIsDeviceFatal(CameraAccessException.CAMERA_DISCONNECTED))
        assertTrue(configureFailureIsDeviceFatal(CameraAccessException.CAMERA_DISABLED))
        // In-use is eviction-class and has its own path; a non-CameraAccessException is a soft rejection.
        assertFalse(configureFailureIsDeviceFatal(CameraAccessException.CAMERA_IN_USE))
        assertFalse(configureFailureIsDeviceFatal(CameraAccessException.MAX_CAMERAS_IN_USE))
        assertFalse(configureFailureIsDeviceFatal(null as Int?))
        assertFalse(configureFailureIsDeviceFatal(IllegalArgumentException("unsupported combination")))
    }

    @Test
    fun `the RAW log route key separates the camera and the converter declaration`() {
        assertEquals("4//tc", rawLogRouteKey("4", null, teleconverter = true))
        assertNotEquals(rawLogRouteKey("4", null, true), rawLogRouteKey("4", null, false))
        assertNotEquals(rawLogRouteKey("4", null, true), rawLogRouteKey("2", null, true))
        assertNotEquals(rawLogRouteKey("0", "4", true), rawLogRouteKey("4", null, true))
    }
}
