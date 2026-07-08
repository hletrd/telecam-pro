package com.hletrd.findx9tele.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log

/**
 * Debug-only camera capability logger. It records camera characteristics plus available
 * capture-request and session keys to Logcat under tag [TAG].
 *
 * Run `adb logcat -s X9TeleVendor` while the app is open to confirm which Camera2 capabilities are
 * available on the device.
 */
object VendorTagInspector {
    const val TAG = "X9TeleVendor"

    fun logAll(manager: CameraManager) {
        runCatching {
            for (id in manager.cameraIdList) {
                logCamera(manager, id)
                val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
                for (pid in chars.physicalCameraIds) logCamera(manager, pid, parent = id)
            }
        }.onFailure { Log.w(TAG, "logAll failed: ${it.message}") }
    }

    private fun logCamera(manager: CameraManager, id: String, parent: String? = null) {
        val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: return
        val facing = chars.get(CameraCharacteristics.LENS_FACING)
        val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList()
        val size = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val header = if (parent == null) "Camera $id" else "Camera $id (physical of $parent)"
        Log.i(TAG, "== $header facing=$facing focalsMm=$focals sensorMm=$size ==")

        Log.i(TAG, "   physicalIds=${chars.physicalCameraIds}")
    }

    private fun valueString(value: Any?): String = when (value) {
        null -> "null"
        is IntArray -> value.joinToString(prefix = "[", postfix = "]")
        is FloatArray -> value.joinToString(prefix = "[", postfix = "]")
        is ByteArray -> value.joinToString(prefix = "[", postfix = "]") { it.toInt().toString() }
        else -> value.toString()
    }
}
