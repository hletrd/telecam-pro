package me.hletrd.telecampro.video

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
        assertEquals(AudioPortKind.PHONE, AudioInputInspector.portKind(AudioDeviceInfo.TYPE_BUILTIN_MIC))
        assertEquals(AudioPortKind.WIRED, AudioInputInspector.portKind(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertEquals(AudioPortKind.USB, AudioInputInspector.portKind(AudioDeviceInfo.TYPE_USB_DEVICE))
        assertEquals(AudioPortKind.USB, AudioInputInspector.portKind(AudioDeviceInfo.TYPE_USB_ACCESSORY))
        assertEquals(AudioPortKind.USB, AudioInputInspector.portKind(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals(AudioPortKind.BLUETOOTH, AudioInputInspector.portKind(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertEquals(AudioPortKind.BLE, AudioInputInspector.portKind(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertEquals(AudioPortKind.HEARING_AID, AudioInputInspector.portKind(AudioDeviceInfo.TYPE_HEARING_AID))
    }

    @Test
    fun typeLabel_unknownTypeFallsBackToMic() {
        assertEquals(AudioPortKind.OTHER, AudioInputInspector.portKind(AudioDeviceInfo.TYPE_UNKNOWN))
        assertEquals(AudioPortKind.OTHER, AudioInputInspector.portKind(99999))
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

    // ---- matchesAudioPreference: the pure per-preference match ----

    @Test
    fun matchesAudioPreference_autoNeverMatchesAConcretePort() {
        // AUTO means "system default route" — preferredDevice() must return null, so the match
        // itself is false for every type.
        for (type in listOf(
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_UNKNOWN,
        )) {
            assertFalse(
                "AUTO must not match type $type",
                matchesAudioPreference(type, me.hletrd.telecampro.camera.AudioInputPreference.AUTO),
            )
        }
    }

    @Test
    fun matchesAudioPreference_concretePreferencesMatchTheirExactTypeSets() {
        val builtIn = me.hletrd.telecampro.camera.AudioInputPreference.BUILT_IN
        val usb = me.hletrd.telecampro.camera.AudioInputPreference.USB
        val bluetooth = me.hletrd.telecampro.camera.AudioInputPreference.BLUETOOTH

        assertTrue(matchesAudioPreference(AudioDeviceInfo.TYPE_BUILTIN_MIC, builtIn))
        assertFalse(matchesAudioPreference(AudioDeviceInfo.TYPE_WIRED_HEADSET, builtIn))

        assertTrue(matchesAudioPreference(AudioDeviceInfo.TYPE_USB_DEVICE, usb))
        assertTrue(matchesAudioPreference(AudioDeviceInfo.TYPE_USB_ACCESSORY, usb))
        assertTrue(matchesAudioPreference(AudioDeviceInfo.TYPE_USB_HEADSET, usb))
        assertFalse(matchesAudioPreference(AudioDeviceInfo.TYPE_BUILTIN_MIC, usb))

        assertTrue(matchesAudioPreference(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, bluetooth))
        assertTrue(matchesAudioPreference(AudioDeviceInfo.TYPE_BLE_HEADSET, bluetooth))
        assertTrue(matchesAudioPreference(AudioDeviceInfo.TYPE_HEARING_AID, bluetooth))
        assertFalse(matchesAudioPreference(AudioDeviceInfo.TYPE_USB_DEVICE, bluetooth))
    }

    // ---- resolveAudioInputStatus (TEST4-18/CR4-9): the pure status decision ----

    @Test
    fun status_autoWithNoDevices_isHonestlyUnavailable() {
        val s = resolveAudioInputStatus(emptyList(), me.hletrd.telecampro.camera.AudioInputPreference.AUTO)
        assertFalse(s.available)
        assertEquals(AudioRouteAvailability.AUTO_NO_MIC, s.route.availability)
    }

    @Test
    fun status_autoWithBuiltinOnly_labelsThePhoneMic() {
        val s = resolveAudioInputStatus(
            listOf(AudioInputPortInfo(AudioDeviceInfo.TYPE_BUILTIN_MIC, null)),
            me.hletrd.telecampro.camera.AudioInputPreference.AUTO,
        )
        assertTrue(s.available)
        assertEquals(AudioPortKind.PHONE, s.route.portKind)
    }

    @Test
    fun status_autoPrefersARecognizedExternalMic() {
        val s = resolveAudioInputStatus(
            listOf(
                AudioInputPortInfo(AudioDeviceInfo.TYPE_BUILTIN_MIC, null),
                AudioInputPortInfo(AudioDeviceInfo.TYPE_USB_HEADSET, "Rode VideoMic"),
            ),
            me.hletrd.telecampro.camera.AudioInputPreference.AUTO,
        )
        assertTrue(s.available)
        assertEquals(AudioPortKind.USB, s.route.portKind)
        assertEquals("Rode VideoMic", s.route.productName)
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
            me.hletrd.telecampro.camera.AudioInputPreference.AUTO,
        )
        assertTrue(s.available)
        assertEquals(AudioPortKind.PHONE, s.route.portKind)
    }

    @Test
    fun status_autoWithOnlyUnrecognizedPorts_labelsTheFirstPort() {
        // No recognized external mic AND no builtin: AUTO still records via the system default
        // route, so the label falls back to the first port rather than lying "No mic".
        val s = resolveAudioInputStatus(
            listOf(
                AudioInputPortInfo(AudioDeviceInfo.TYPE_FM_TUNER, "FM"),
                AudioInputPortInfo(AudioDeviceInfo.TYPE_TELEPHONY, null),
            ),
            me.hletrd.telecampro.camera.AudioInputPreference.AUTO,
        )
        assertTrue(s.available)
        assertEquals(AudioPortKind.OTHER, s.route.portKind)
        assertEquals("FM", s.route.productName)
    }

    @Test
    fun status_missingConcretePreference_isUnavailable() {
        val s = resolveAudioInputStatus(
            listOf(AudioInputPortInfo(AudioDeviceInfo.TYPE_BUILTIN_MIC, null)),
            me.hletrd.telecampro.camera.AudioInputPreference.WIRED,
        )
        assertFalse(s.available)
        // Pins the CROSS-SURFACE wording, not just this branch: the Route row must read the same
        // before REC and during it, so this label has to BE audioUnavailableLabel's output.
        assertEquals(AudioRouteAvailability.UNAVAILABLE, s.route.availability)
        assertEquals(me.hletrd.telecampro.camera.AudioInputPreference.WIRED, s.route.preference)
    }

    @Test
    fun status_matchedConcretePreference_isReady() {
        val s = resolveAudioInputStatus(
            listOf(
                AudioInputPortInfo(AudioDeviceInfo.TYPE_BUILTIN_MIC, null),
                AudioInputPortInfo(AudioDeviceInfo.TYPE_WIRED_HEADSET, "Lav"),
            ),
            me.hletrd.telecampro.camera.AudioInputPreference.WIRED,
        )
        assertTrue(s.available)
        assertEquals(AudioRouteAvailability.READY, s.route.availability)
        assertEquals(AudioPortKind.WIRED, s.route.portKind)
        assertEquals("Lav", s.route.productName)
    }
}
