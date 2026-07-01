package com.hletrd.findx9tele.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log

/**
 * On-device reverse-engineering aid: dumps every camera's characteristics keys (INCLUDING OPPO/QTI
 * vendor tags such as `com.oplus.ois.control.mode`, `com.oplus.custom.zoom.range`), plus the
 * available capture-request and session keys, to Logcat under tag [TAG].
 *
 * Run `adb logcat -s X9TeleVendor` while the app is open to discover which vendor tags the HAL
 * exposes to third-party apps — the basis for attempting native teleconverter stabilization.
 */
object VendorTagInspector {
    const val TAG = "X9TeleVendor"

    fun dumpAll(manager: CameraManager) {
        runCatching {
            for (id in manager.cameraIdList) {
                dumpCamera(manager, id)
                val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
                for (pid in chars.physicalCameraIds) dumpCamera(manager, pid, parent = id)
            }
        }.onFailure { Log.w(TAG, "dumpAll failed: ${it.message}") }
    }

    private fun dumpCamera(manager: CameraManager, id: String, parent: String? = null) {
        val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: return
        val facing = chars.get(CameraCharacteristics.LENS_FACING)
        val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList()
        val size = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val header = if (parent == null) "Camera $id" else "Camera $id (physical of $parent)"
        Log.i(TAG, "== $header facing=$facing focalsMm=$focals sensorMm=$size ==")

        chars.keys.forEach { key ->
            val name = key.name
            if (name.startsWith("com.oplus") || name.startsWith("org.quic") ||
                name.startsWith("org.codeaurora") || name.startsWith("com.qti")
            ) {
                Log.i(TAG, "  vendorChar $name = ${valueString(runCatching { chars.get(key) }.getOrNull())}")
            }
        }
        chars.availableCaptureRequestKeys.forEach { key ->
            if (key.name.contains('.')) Log.i(TAG, "  reqKey ${key.name}")
        }
        chars.availableSessionKeys?.forEach { key -> Log.i(TAG, "  sessionKey ${key.name}") }
    }

    private fun valueString(value: Any?): String = when (value) {
        null -> "null"
        is IntArray -> value.joinToString(prefix = "[", postfix = "]")
        is FloatArray -> value.joinToString(prefix = "[", postfix = "]")
        is ByteArray -> value.joinToString(prefix = "[", postfix = "]") { it.toInt().toString() }
        else -> value.toString()
    }
}
