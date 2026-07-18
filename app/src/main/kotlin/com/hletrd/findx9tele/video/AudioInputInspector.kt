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
    fun status(context: Context, preference: AudioInputPreference): AudioInputStatus =
        // The selection/label decision is the pure resolveAudioInputStatus (TEST4-18/CR4-9) —
        // this wrapper only harvests the live device list into plain (type, name) ports.
        resolveAudioInputStatus(
            inputDevices(context).map { AudioInputPortInfo(it.type, it.productName?.toString()) },
            preference,
        )

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

    private fun AudioDeviceInfo.matches(preference: AudioInputPreference): Boolean =
        matchesAudioPreference(type, preference)

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

    internal val USB_INPUT_TYPES = setOf(
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_USB_HEADSET,
    )

    internal val BLUETOOTH_INPUT_TYPES = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_HEARING_AID,
    )
}

/** Plain (type, productName) projection of an [AudioDeviceInfo] input port — JVM-testable. */
internal data class AudioInputPortInfo(val type: Int, val productName: String?)

/** Pure form of the per-preference match used by both status resolution and preferredDevice. */
internal fun matchesAudioPreference(type: Int, preference: AudioInputPreference): Boolean =
    when (preference) {
        AudioInputPreference.AUTO -> false
        AudioInputPreference.BUILT_IN -> type == AudioDeviceInfo.TYPE_BUILTIN_MIC
        AudioInputPreference.WIRED -> type == AudioDeviceInfo.TYPE_WIRED_HEADSET
        AudioInputPreference.USB -> type in AudioInputInspector.USB_INPUT_TYPES
        AudioInputPreference.BLUETOOTH -> type in AudioInputInspector.BLUETOOTH_INPUT_TYPES
    }

/** The label for one port: type label plus a meaningful product name. */
internal fun audioPortLabel(port: AudioInputPortInfo): String {
    val name = port.productName?.takeIf { it.isNotBlank() && it != "Unknown" }
    return listOfNotNull(AudioInputInspector.typeLabel(port.type), name).joinToString(" · ")
}

/**
 * The audio-input status decision over plain ports (TEST4-18 — the branching used to live inside
 * status() against live AudioDeviceInfo and was untestable on the JVM):
 * - AUTO with no capture device: honest "no mic detected", unavailable (the old code composed the
 *   doubled "Auto · Auto" and reported ready).
 * - AUTO prefers a RECOGNIZED external mic (wired/USB/BT — CR4-9: `type != BUILTIN` alone could
 *   pick a telephony/FM tuner port and label it like a mic), else the built-in mic, else the
 *   first port (AUTO recording uses the system default route either way; the pick is a LABEL).
 * - A concrete preference reports "<port> ready" when an exact match exists, "<pref> missing"
 *   (unavailable) otherwise.
 */
internal fun resolveAudioInputStatus(
    ports: List<AudioInputPortInfo>,
    preference: AudioInputPreference,
): AudioInputStatus {
    if (preference == AudioInputPreference.AUTO) {
        if (ports.isEmpty()) return AudioInputStatus("Auto · no mic detected", available = false)
        val recognizedExternal = AudioInputInspector.USB_INPUT_TYPES +
            AudioInputInspector.BLUETOOTH_INPUT_TYPES +
            AudioDeviceInfo.TYPE_WIRED_HEADSET
        val pick = ports.firstOrNull { it.type in recognizedExternal }
            ?: ports.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            ?: ports.first()
        return AudioInputStatus("Auto · ${audioPortLabel(pick)}", available = true)
    }
    val match = ports.firstOrNull { matchesAudioPreference(it.type, preference) }
    return if (match != null) {
        AudioInputStatus("${audioPortLabel(match)} ready", available = true)
    } else {
        AudioInputStatus("${preference.label} missing", available = false)
    }
}
