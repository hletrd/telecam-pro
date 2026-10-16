package me.hletrd.telecampro.ui

import android.content.Context
import me.hletrd.telecampro.R
import me.hletrd.telecampro.camera.AudioInputPreference
import me.hletrd.telecampro.video.AudioPortKind
import me.hletrd.telecampro.video.AudioRouteAvailability
import me.hletrd.telecampro.video.AudioRouteStatus

internal fun AudioInputPreference.resolve(context: Context): String = context.getString(
    when (this) {
        AudioInputPreference.AUTO -> R.string.value_auto
        AudioInputPreference.BUILT_IN -> R.string.value_phone
        AudioInputPreference.WIRED -> R.string.value_wired
        AudioInputPreference.USB -> R.string.value_usb
        AudioInputPreference.BLUETOOTH -> R.string.value_bluetooth
    },
)

private fun AudioPortKind.resolve(context: Context): String = context.getString(
    when (this) {
        AudioPortKind.PHONE -> R.string.audio_port_phone_mic
        AudioPortKind.WIRED -> R.string.audio_port_wired_mic
        AudioPortKind.USB -> R.string.audio_port_usb_mic
        AudioPortKind.BLUETOOTH -> R.string.audio_port_bluetooth_mic
        AudioPortKind.BLE -> R.string.audio_port_ble_mic
        AudioPortKind.HEARING_AID -> R.string.audio_port_hearing_aid
        AudioPortKind.OTHER -> R.string.audio_port_mic
    },
)

internal fun AudioRouteStatus.resolve(context: Context): String {
    val port = portKind?.resolve(context)?.let { kind ->
        productName?.let { "$kind · $it" } ?: kind
    }
    return when (availability) {
        AudioRouteAvailability.AUTO -> if (port == null) {
            context.getString(R.string.audio_route_auto)
        } else {
            context.getString(R.string.audio_route_auto_device, port)
        }
        AudioRouteAvailability.AUTO_NO_MIC -> context.getString(R.string.audio_route_auto_no_mic)
        AudioRouteAvailability.READY -> context.getString(R.string.audio_route_ready, port ?: preference.resolve(context))
        AudioRouteAvailability.UNAVAILABLE -> context.getString(
            R.string.audio_route_unavailable,
            preference.resolve(context),
        )
        AudioRouteAvailability.STARTING -> context.getString(R.string.audio_route_starting)
        AudioRouteAvailability.OFF -> context.getString(R.string.audio_route_off)
    }
}
