package com.hletrd.findx9tele.video

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure input-type mapping in AudioInputInspector. The AudioDeviceInfo.TYPE_* values are
 * compile-time int constants (inlined), so these are JVM-safe with no Robolectric/mocks.
 */
class AudioInputInspectorTest {

    @Test
    fun typeLabel_mapsEveryNamedBranch() {
        assertEquals("Phone mic", AudioInputInspector.typeLabel(AudioDeviceInfo.TYPE_BUILTIN_MIC))
        assertEquals("Wired mic", AudioInputInspector.typeLabel(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertEquals("USB mic", AudioInputInspector.typeLabel(AudioDeviceInfo.TYPE_USB_DEVICE))
        assertEquals("USB mic", AudioInputInspector.typeLabel(AudioDeviceInfo.TYPE_USB_ACCESSORY))
        assertEquals("USB mic", AudioInputInspector.typeLabel(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals("BT mic", AudioInputInspector.typeLabel(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertEquals("BLE mic", AudioInputInspector.typeLabel(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertEquals("BT hearing aid", AudioInputInspector.typeLabel(AudioDeviceInfo.TYPE_HEARING_AID))
    }

    @Test
    fun typeLabel_unknownTypeFallsBackToMic() {
        assertEquals("Mic", AudioInputInspector.typeLabel(AudioDeviceInfo.TYPE_UNKNOWN))
        assertEquals("Mic", AudioInputInspector.typeLabel(99999))
    }

    @Test
    fun isBluetoothInput_trueForEveryBluetoothType() {
        assertTrue(AudioInputInspector.isBluetoothInput(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertTrue(AudioInputInspector.isBluetoothInput(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertTrue(AudioInputInspector.isBluetoothInput(AudioDeviceInfo.TYPE_HEARING_AID))
    }

    @Test
    fun isBluetoothInput_falseForNonBluetoothTypes() {
        assertFalse(AudioInputInspector.isBluetoothInput(AudioDeviceInfo.TYPE_BUILTIN_MIC))
        assertFalse(AudioInputInspector.isBluetoothInput(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertFalse(AudioInputInspector.isBluetoothInput(AudioDeviceInfo.TYPE_USB_DEVICE))
        assertFalse(AudioInputInspector.isBluetoothInput(AudioDeviceInfo.TYPE_UNKNOWN))
    }

    // ---- resolveAudioInputStatus (TEST4-18/CR4-9): the pure status decision ----

    @Test
    fun status_autoWithNoDevices_isHonestlyUnavailable() {
        val s = resolveAudioInputStatus(emptyList(), com.hletrd.findx9tele.camera.AudioInputPreference.AUTO)
        assertFalse(s.available)
        org.junit.Assert.assertEquals("Auto · no mic detected", s.label)
    }

    @Test
    fun status_autoWithBuiltinOnly_labelsThePhoneMic() {
        val s = resolveAudioInputStatus(
            listOf(AudioInputPortInfo(AudioDeviceInfo.TYPE_BUILTIN_MIC, null)),
            com.hletrd.findx9tele.camera.AudioInputPreference.AUTO,
        )
        assertTrue(s.available)
        org.junit.Assert.assertEquals("Auto · Phone mic", s.label)
    }

    @Test
    fun status_autoPrefersARecognizedExternalMic() {
        val s = resolveAudioInputStatus(
            listOf(
                AudioInputPortInfo(AudioDeviceInfo.TYPE_BUILTIN_MIC, null),
                AudioInputPortInfo(AudioDeviceInfo.TYPE_USB_HEADSET, "Rode VideoMic"),
            ),
            com.hletrd.findx9tele.camera.AudioInputPreference.AUTO,
        )
        assertTrue(s.available)
        org.junit.Assert.assertEquals("Auto · USB mic · Rode VideoMic", s.label)
    }

    @Test
    fun status_autoSkipsUnrecognizedInputTypesForTheBuiltin() {
        // CR4-9: `type != BUILTIN` alone picked telephony/FM-tuner style ports and labeled them
        // like a mic; an unrecognized non-mic port must lose to the builtin.
        val s = resolveAudioInputStatus(
            listOf(
                AudioInputPortInfo(AudioDeviceInfo.TYPE_TELEPHONY, null),
                AudioInputPortInfo(AudioDeviceInfo.TYPE_BUILTIN_MIC, null),
            ),
            com.hletrd.findx9tele.camera.AudioInputPreference.AUTO,
        )
        assertTrue(s.available)
        org.junit.Assert.assertEquals("Auto · Phone mic", s.label)
    }

    @Test
    fun status_missingConcretePreference_isUnavailable() {
        val s = resolveAudioInputStatus(
            listOf(AudioInputPortInfo(AudioDeviceInfo.TYPE_BUILTIN_MIC, null)),
            com.hletrd.findx9tele.camera.AudioInputPreference.WIRED,
        )
        assertFalse(s.available)
        org.junit.Assert.assertEquals("Wired missing", s.label)
    }

    @Test
    fun status_matchedConcretePreference_isReady() {
        val s = resolveAudioInputStatus(
            listOf(
                AudioInputPortInfo(AudioDeviceInfo.TYPE_BUILTIN_MIC, null),
                AudioInputPortInfo(AudioDeviceInfo.TYPE_WIRED_HEADSET, "Lav"),
            ),
            com.hletrd.findx9tele.camera.AudioInputPreference.WIRED,
        )
        assertTrue(s.available)
        org.junit.Assert.assertEquals("Wired mic · Lav ready", s.label)
    }
}
