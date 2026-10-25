package me.hletrd.telecampro.camera

import android.hardware.camera2.CameraAccessException

/**
 * Exactly one handler per session-configure attempt.
 *
 * When the HAL fails stream configuration, Camera2 does BOTH: `CameraDeviceImpl
 * .createCaptureSessionInternal` builds the session with `configureSuccess = false`, which POSTS
 * onConfigureFailed, and then rethrows the CameraAccessException synchronously out of
 * createCaptureSession. The controller retried from both, so one failure ran two retry chains and
 * reported the terminal failure twice — device-seen 2026-10-26 (RAW log + TELE on PMA110): two chains
 * walked the whole ladder in 2 ms, each reported "fallback ladder exhausted", and the engine scheduled
 * two recoveries whose reopens evicted each other. Camera-thread confined, like the ladder it guards:
 * the synchronous throw always reaches [claim] before the posted callback for the same attempt runs,
 * so that callback is ignored.
 */
internal class ConfigureAttemptGate {
    private var current = 0L
    private var claimed = true

    /** Starts a new attempt; every earlier attempt's late callback becomes inert. */
    fun begin(): Long {
        current++
        claimed = false
        return current
    }

    /** True exactly once for the current attempt; false for a stale or already-handled one. */
    fun claim(token: Long): Boolean {
        if (token != current || claimed) return false
        claimed = true
        return true
    }
}

/**
 * Whether a SYNCHRONOUS createCaptureSession failure means the CameraDevice itself is gone, so no later
 * ladder rung can configure on it. A HAL stream-configuration failure other than BAD_VALUE moves
 * Camera3Device into its ERROR state — every later call answers "The camera device has encountered a
 * serious error" — and surfaces here as CAMERA_ERROR; DISCONNECTED and DISABLED mean the device was
 * taken or withheld. A soft rejection (an unsupported combination) arrives as onConfigureFailed WITHOUT
 * a throw, or as a synchronous IllegalArgumentException, and the ladder continues as before.
 * Device-measured 2026-10-26: a RAW-only session on camera 4 under the TC operation mode threw
 * CAMERA_ERROR "Error configuring streams: Function not implemented (-38)", and every rung after it
 * failed instantly on the dead device. Takes the reason, not the exception, because android.jar stubs
 * cannot construct a CameraAccessException in a host test.
 */
internal fun configureFailureIsDeviceFatal(cameraAccessReason: Int?): Boolean =
    cameraAccessReason == CameraAccessException.CAMERA_ERROR ||
        cameraAccessReason == CameraAccessException.CAMERA_DISCONNECTED ||
        cameraAccessReason == CameraAccessException.CAMERA_DISABLED

/** [configureFailureIsDeviceFatal] over a thrown failure. */
internal fun configureFailureIsDeviceFatal(failure: Throwable): Boolean =
    configureFailureIsDeviceFatal((failure as? CameraAccessException)?.reason)

/**
 * The RAW log attempt killed the camera device at configure ([configureFailureIsDeviceFatal]). Its
 * usual fallback — retry the same rung on native log — cannot run on a dead device, so the controller
 * reports this typed failure once and the Engine stops offering RAW log on [routeKey] to the reopens
 * that bounded recovery performs. Without that memory every recovery tried RAW log again and died again
 * (device-seen 2026-10-26: three recoveries, then "camera unavailable", and the same on every relaunch
 * because TELE and RAW log are both restored).
 */
internal class RawLogConfigureFatalException(val routeKey: String, cause: Throwable) :
    IllegalStateException("RAW log session failed the camera device", cause)
