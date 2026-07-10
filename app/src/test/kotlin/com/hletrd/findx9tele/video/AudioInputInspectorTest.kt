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
}
