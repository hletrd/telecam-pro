package com.hletrd.findx9tele.ui.controls

import android.hardware.camera2.CameraMetadata
import com.hletrd.findx9tele.camera.CameraControlCapabilities
import com.hletrd.findx9tele.camera.FnSlot
import com.hletrd.findx9tele.camera.ManualControls
import com.hletrd.findx9tele.camera.WbMode
import com.hletrd.findx9tele.camera.controlAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickManualDialEnablementTest {

    @Test
    fun `exact modes and ranges expose all quick rulers`() {
        val caps = CameraControlCapabilities(
            supportsManualFocus = true,
            supportsManualSensor = true,
            supportsManualPostProcessing = true,
            hasFocusDistanceRange = true,
            hasIsoRange = true,
            hasExposureTimeRange = true,
            hasEvCompensationRange = true,
            hasZoomRatioRange = true,
            afModes = intArrayOf(CameraMetadata.CONTROL_AF_MODE_OFF),
            aeModes = intArrayOf(CameraMetadata.CONTROL_AE_MODE_OFF),
            awbModes = intArrayOf(CameraMetadata.CONTROL_AWB_MODE_OFF),
        )
        val availability = controlAvailability(caps, ManualControls())

        for (type in DialType.entries) {
            assertTrue(type.name, quickManualDialEnabled(type, availability))
        }
    }

    @Test
    fun `focus requires broad capability distance range and exact off mode`() {
        assertTrue(enabled(DialType.FOCUS, focus = true, focusRange = true, afOff = true))
        assertFalse(enabled(DialType.FOCUS, focus = false, focusRange = true, afOff = true))
        assertFalse(enabled(DialType.FOCUS, focus = true, focusRange = false, afOff = true))
        assertFalse(enabled(DialType.FOCUS, focus = true, focusRange = true, afOff = false))
    }

    @Test
    fun `manual exposure rulers require sensor ranges and exact ae off`() {
        for (type in listOf(DialType.SHUTTER, DialType.ISO)) {
            assertTrue(enabled(type, sensor = true, exposureRange = true, isoRange = true, aeOff = true))
            assertFalse(enabled(type, sensor = false, exposureRange = true, isoRange = true, aeOff = true))
            assertFalse(enabled(type, sensor = true, exposureRange = false, isoRange = true, aeOff = true))
            assertFalse(enabled(type, sensor = true, exposureRange = true, isoRange = false, aeOff = true))
            assertFalse(enabled(type, sensor = true, exposureRange = true, isoRange = true, aeOff = false))
        }
    }

    @Test
    fun `white balance requires manual post processing and exact awb off`() {
        assertTrue(enabled(DialType.WB, manualPost = true, awbOff = true))
        assertFalse(enabled(DialType.WB, manualPost = false, awbOff = true))
        assertFalse(enabled(DialType.WB, manualPost = true, awbOff = false))
    }

    @Test
    fun `white balance Fn admission separates preset navigation from manual ruler`() {
        data class Case(
            val label: String,
            val mode: WbMode,
            val caps: CameraControlCapabilities?,
            val chipEnabled: Boolean,
            val rulerEnabled: Boolean,
        )

        val manualCaps = CameraControlCapabilities(
            supportsManualPostProcessing = true,
            awbModes = intArrayOf(CameraMetadata.CONTROL_AWB_MODE_OFF),
        )
        val cases = listOf(
            Case(
                label = "preset-only",
                mode = WbMode.AUTO,
                caps = CameraControlCapabilities(
                    awbModes = intArrayOf(
                        CameraMetadata.CONTROL_AWB_MODE_AUTO,
                        CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
                    ),
                ),
                chipEnabled = true,
                rulerEnabled = false,
            ),
            Case("manual-only", WbMode.MANUAL, manualCaps, chipEnabled = true, rulerEnabled = true),
            Case(
                label = "mixed",
                mode = WbMode.AUTO,
                caps = CameraControlCapabilities(
                    supportsManualPostProcessing = true,
                    awbModes = intArrayOf(
                        CameraMetadata.CONTROL_AWB_MODE_AUTO,
                        CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
                        CameraMetadata.CONTROL_AWB_MODE_OFF,
                    ),
                ),
                chipEnabled = true,
                rulerEnabled = true,
            ),
            Case(
                label = "singleton",
                mode = WbMode.AUTO,
                caps = CameraControlCapabilities(
                    awbModes = intArrayOf(CameraMetadata.CONTROL_AWB_MODE_AUTO),
                ),
                chipEnabled = false,
                rulerEnabled = false,
            ),
            Case("caps-unavailable", WbMode.AUTO, null, chipEnabled = false, rulerEnabled = false),
        )

        cases.forEach { case ->
            val availability = controlAvailability(case.caps, ManualControls(wbMode = case.mode))
            assertEquals(
                "${case.label} chip",
                case.chipEnabled,
                whiteBalanceFnChipEnabled(case.mode, availability),
            )
            assertEquals(
                "${case.label} ruler",
                case.rulerEnabled,
                quickManualDialEnabled(DialType.WB, availability),
            )
        }
    }

    @Test
    fun `ev requires a real exposure path and nonzero compensation range`() {
        assertTrue(enabled(DialType.EV, sensor = true, exposureRange = true, isoRange = true, aeOff = true, evRange = true))
        assertFalse(enabled(DialType.EV, sensor = true, exposureRange = true, isoRange = true, aeOff = true, evRange = false))
        assertFalse(enabled(DialType.EV, evRange = true))
    }

    @Test
    fun `zoom requires a nonzero zoom range`() {
        assertTrue(enabled(DialType.ZOOM, zoomRange = true))
        assertFalse(enabled(DialType.ZOOM, zoomRange = false))
    }

    @Test
    fun `capability loss closes an already open ruler`() {
        val unavailable = controlAvailability(CameraControlCapabilities(), ManualControls())
        for (type in DialType.entries) {
            assertNull(type.name, reconcileOpenManualDial(type, unavailable))
        }
    }

    @Test
    fun `compact entry can retain a deliberately requested ruler`() {
        val available = controlAvailability(
            CameraControlCapabilities(hasZoomRatioRange = true),
            ManualControls(),
        )
        assertEquals(DialType.ZOOM, reconcileOpenManualDial(DialType.ZOOM, available))
    }

    @Test
    fun `numeric Fn slots open adjustments instead of destructive quick actions`() {
        val expected = mapOf(
            FnSlot.FOCUS to DialType.FOCUS,
            FnSlot.SHUTTER to DialType.SHUTTER,
            FnSlot.ISO to DialType.ISO,
            FnSlot.WB to DialType.WB,
            FnSlot.EV to DialType.EV,
            FnSlot.ZOOM to DialType.ZOOM,
        )
        expected.forEach { (slot, dial) -> assertEquals(slot.name, dial, manualDialForFnSlot(slot)) }
        assertNull(manualDialForFnSlot(FnSlot.EXPOSURE_MODE))
    }

    private fun enabled(
        type: DialType,
        focus: Boolean = false,
        focusRange: Boolean = false,
        afOff: Boolean = false,
        sensor: Boolean = false,
        exposureRange: Boolean = false,
        isoRange: Boolean = false,
        aeOff: Boolean = false,
        manualPost: Boolean = false,
        awbOff: Boolean = false,
        evRange: Boolean = false,
        zoomRange: Boolean = false,
    ): Boolean {
        val caps = CameraControlCapabilities(
            supportsManualFocus = focus,
            supportsManualSensor = sensor,
            supportsManualPostProcessing = manualPost,
            hasFocusDistanceRange = focusRange,
            hasExposureTimeRange = exposureRange,
            hasIsoRange = isoRange,
            hasEvCompensationRange = evRange,
            hasZoomRatioRange = zoomRange,
            afModes = if (afOff) intArrayOf(CameraMetadata.CONTROL_AF_MODE_OFF) else IntArray(0),
            aeModes = if (aeOff) intArrayOf(CameraMetadata.CONTROL_AE_MODE_OFF) else IntArray(0),
            awbModes = if (awbOff) intArrayOf(CameraMetadata.CONTROL_AWB_MODE_OFF) else IntArray(0),
        )
        return quickManualDialEnabled(type, controlAvailability(caps, ManualControls()))
    }
}
