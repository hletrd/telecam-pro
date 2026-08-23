package me.hletrd.telecampro.video

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import me.hletrd.telecampro.camera.AudioInputPreference

internal data class AudioInputStatus(
    val route: AudioRouteStatus,
    val available: Boolean,
)

enum class AudioPortKind { PHONE, WIRED, USB, BLUETOOTH, BLE, HEARING_AID, OTHER }
enum class AudioRouteAvailability { AUTO, AUTO_NO_MIC, READY, UNAVAILABLE, STARTING, OFF }
data class AudioRouteStatus(
    val preference: AudioInputPreference,
    val availability: AudioRouteAvailability,
    val portKind: AudioPortKind? = null,
    val productName: String? = null,
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

    fun routeStatus(preference: AudioInputPreference, device: AudioDeviceInfo?): AudioRouteStatus {
        if (device == null) {
            return AudioRouteStatus(
                preference,
                if (preference == AudioInputPreference.AUTO) AudioRouteAvailability.AUTO
                else AudioRouteAvailability.UNAVAILABLE,
            )
        }
        val name = device.productName?.toString()?.takeIf { it.isNotBlank() && it != "Unknown" }
        return AudioRouteStatus(preference, AudioRouteAvailability.READY, portKind(device.type), name)
    }

    fun isBluetoothInput(type: Int): Boolean = type in BLUETOOTH_INPUT_TYPES

    private fun inputDevices(context: Context): List<AudioDeviceInfo> {
        val am = context.getSystemService(AudioManager::class.java) ?: return emptyList()
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.isSource }
    }

    private fun AudioDeviceInfo.matches(preference: AudioInputPreference): Boolean =
        matchesAudioPreference(type, preference)

    // internal (not private): opened for unit tests (plain TYPE_* int constants are JVM-safe).
    internal fun portKind(type: Int): AudioPortKind = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> AudioPortKind.PHONE
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> AudioPortKind.WIRED
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_USB_HEADSET -> AudioPortKind.USB
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioPortKind.BLUETOOTH
        AudioDeviceInfo.TYPE_BLE_HEADSET -> AudioPortKind.BLE
        AudioDeviceInfo.TYPE_HEARING_AID -> AudioPortKind.HEARING_AID
        else -> AudioPortKind.OTHER
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

/**
 * The audio-input status decision over plain ports (TEST4-18 — the branching used to live inside
 * status() against live AudioDeviceInfo and was untestable on the JVM):
 * - AUTO with no capture device: honest "No mic", unavailable (the old code composed the
 *   doubled "Auto · Auto" and reported ready).
 * - AUTO prefers a RECOGNIZED external mic (wired/USB/BT — CR4-9: `type != BUILTIN` alone could
 *   pick a telephony/FM tuner port and label it like a mic), else the built-in mic, else the
 *   first port (AUTO recording uses the system default route either way; the pick is a LABEL).
 * - A concrete preference reports "<port> ready" when an exact match exists, "<pref> unavailable"
 *   (the canonical audioUnavailableLabel wording the in-REC degradation paths already use) otherwise.
 */
internal fun resolveAudioInputStatus(
    ports: List<AudioInputPortInfo>,
    preference: AudioInputPreference,
): AudioInputStatus {
    if (preference == AudioInputPreference.AUTO) {
        // Capitalized after the separator like every sibling ("Auto · Phone mic"): the label is a
        // port name in that slot, not a sentence about one.
        if (ports.isEmpty()) return AudioInputStatus(
            AudioRouteStatus(preference, AudioRouteAvailability.AUTO_NO_MIC),
            available = false,
        )
        val recognizedExternal = AudioInputInspector.USB_INPUT_TYPES +
            AudioInputInspector.BLUETOOTH_INPUT_TYPES +
            AudioDeviceInfo.TYPE_WIRED_HEADSET
        val pick = ports.firstOrNull { it.type in recognizedExternal }
            ?: ports.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            ?: ports.first()
        return AudioInputStatus(
            AudioRouteStatus(
                preference,
                AudioRouteAvailability.AUTO,
                AudioInputInspector.portKind(pick.type),
                pick.productName?.takeIf { it.isNotBlank() && it != "Unknown" },
            ),
            available = true,
        )
    }
    val match = ports.firstOrNull { matchesAudioPreference(it.type, preference) }
    return if (match != null) {
        AudioInputStatus(
            AudioRouteStatus(
                preference,
                AudioRouteAvailability.READY,
                AudioInputInspector.portKind(match.type),
                match.productName?.takeIf { it.isNotBlank() && it != "Unknown" },
            ),
            available = true,
        )
    } else {
        AudioInputStatus(AudioRouteStatus(preference, AudioRouteAvailability.UNAVAILABLE), available = false)
    }
}
