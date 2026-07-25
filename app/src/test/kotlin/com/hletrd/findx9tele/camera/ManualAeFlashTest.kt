package com.hletrd.findx9tele.camera

import android.hardware.camera2.CameraMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the manual-AE (AE-OFF) flash decision: TORCH is a direct lamp control that needs NO AE
 * metering and must survive EVERY app-side exposure mode (field suspicion 2026-07-25: "torch dead
 * in ISO mode" — the request-side decision is TORCH-preserving by construction; a dark lamp with
 * FLASH_MODE=TORCH on the wire is a HAL/route matter, discriminated by the 3A trace's
 * flashMode/flashState fields).
 */
class ManualAeFlashTest {

    @Test
    fun `TORCH survives manual AE`() {
        assertEquals(
            CameraMetadata.FLASH_MODE_TORCH,
            manualAeFlashMode(FlashMode.TORCH, flashAvailable = true),
        )
    }

    @Test
    fun `AE firing variants resolve to OFF under manual AE`() {
        // AUTO/ON need AE metering (unusable at AE_MODE_OFF); OFF is OFF. Never null with
        // hardware present — an unset key would silently keep the previous request's lamp state.
        assertEquals(CameraMetadata.FLASH_MODE_OFF, manualAeFlashMode(FlashMode.OFF, flashAvailable = true))
        assertEquals(CameraMetadata.FLASH_MODE_OFF, manualAeFlashMode(FlashMode.AUTO, flashAvailable = true))
        assertEquals(CameraMetadata.FLASH_MODE_OFF, manualAeFlashMode(FlashMode.ON, flashAvailable = true))
    }

    @Test
    fun `absent flash hardware omits the key entirely`() {
        for (mode in FlashMode.entries) {
            assertNull(manualAeFlashMode(mode, flashAvailable = false))
        }
    }
}
