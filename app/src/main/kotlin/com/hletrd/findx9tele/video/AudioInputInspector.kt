package com.hletrd.findx9tele.video

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.hletrd.findx9tele.camera.AudioInputPreference

internal data class AudioInputStatus(
    val label: String,
    val available: Boolean,
)

internal object AudioInputInspector {
    fun status(context: Context, preference: AudioInputPreference): AudioInputStatus {
        val devices = inputDevices(context)
        if (preference == AudioInputPreference.AUTO) {
            // With no capture device at all, routeLabel(AUTO, null) itself falls back to "Auto" —
            // composing that into "Auto · ${...}" produced the doubled "Auto · Auto" label AND
            // falsely reported AUTO as ready when there is nothing to record from.
            if (devices.isEmpty()) {
                return AudioInputStatus("Auto · no mic detected", available = false)
            }
            val device = devices.firstOrNull { it.type != AudioDeviceInfo.TYPE_BUILTIN_MIC }
                ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            return AudioInputStatus("Auto · ${routeLabel(preference, device)}", available = true)
        }

        val device = devices.firstOrNull { it.matches(preference) }
        return if (device != null) {
            AudioInputStatus("${routeLabel(preference, device)} ready", available = true)
        } else {
            AudioInputStatus("${preference.label} missing", available = false)
        }
    }

    fun preferredDevice(context: Context, preference: AudioInputPreference): AudioDeviceInfo? {
        if (preference == AudioInputPreference.AUTO) return null
        return inputDevices(context).firstOrNull { it.matches(preference) }
    }

    fun routeLabel(preference: AudioInputPreference, device: AudioDeviceInfo?): String {
        if (device == null) return if (preference == AudioInputPreference.AUTO) "Auto" else "${preference.label} missing"
        val name = device.productName?.toString()?.takeIf { it.isNotBlank() && it != "Unknown" }
        return listOfNotNull(typeLabel(device.type), name).joinToString(" · ")
    }

    fun isBluetoothInput(type: Int): Boolean = type in BLUETOOTH_INPUT_TYPES

    private fun inputDevices(context: Context): List<AudioDeviceInfo> {
        val am = context.getSystemService(AudioManager::class.java) ?: return emptyList()
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.isSource }
    }

    private fun AudioDeviceInfo.matches(preference: AudioInputPreference): Boolean = when (preference) {
        AudioInputPreference.AUTO -> false
        AudioInputPreference.BUILT_IN -> type == AudioDeviceInfo.TYPE_BUILTIN_MIC
        AudioInputPreference.WIRED -> type == AudioDeviceInfo.TYPE_WIRED_HEADSET
        AudioInputPreference.USB -> type in USB_INPUT_TYPES
        AudioInputPreference.BLUETOOTH -> type in BLUETOOTH_INPUT_TYPES
    }

    // internal (not private): opened for unit tests (plain TYPE_* int constants are JVM-safe).
    internal fun typeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Phone mic"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired mic"
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB mic"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT mic"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE mic"
        AudioDeviceInfo.TYPE_HEARING_AID -> "BT hearing aid"
        else -> "Mic"
    }

    private val USB_INPUT_TYPES = setOf(
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_USB_HEADSET,
    )

    private val BLUETOOTH_INPUT_TYPES = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_HEARING_AID,
    )
}
