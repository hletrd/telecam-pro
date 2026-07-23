package com.hletrd.findx9tele.ui.controls

import com.hletrd.findx9tele.camera.AudioScene
import com.hletrd.findx9tele.camera.CameraFacing
import com.hletrd.findx9tele.camera.CameraUiState
import com.hletrd.findx9tele.camera.ColorTransfer
import com.hletrd.findx9tele.camera.DriveMode
import com.hletrd.findx9tele.camera.ExposureMode
import com.hletrd.findx9tele.camera.FnSlot
import com.hletrd.findx9tele.camera.FocusMode
import com.hletrd.findx9tele.camera.FrameLineType
import com.hletrd.findx9tele.camera.GridType
import com.hletrd.findx9tele.camera.ManualControls
import com.hletrd.findx9tele.camera.MeteringMode
import com.hletrd.findx9tele.camera.ShutterMode
import com.hletrd.findx9tele.camera.VideoStabMode
import com.hletrd.findx9tele.camera.WbMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [fnSlotValue] — the per-slot readout every quick-Fn surface (Fn overlay, My Menu, Recent)
 * renders. The default-state table is EXHAUSTIVE over [FnSlot] so a new slot cannot ship without
 * deciding its readout; the focused cases pin each non-default branch (auto-driven Shutter/ISO
 * copy, cine angle, manual WB Kelvin, EV scaling, and the main-relative zoom scale).
 */
class FnSlotValueTest {

    @Test
    fun `default state readout is exhaustive over every slot`() {
        val state = CameraUiState()
        val expected = mapOf(
            FnSlot.EXPOSURE_MODE to "P",
            FnSlot.FOCUS to "AF-C",
            // Photo PROGRAM without an AE result yet: honest Auto placeholder, not a stale number.
            FnSlot.SHUTTER to "Auto --",
            FnSlot.ISO to "Auto --",
            FnSlot.WB to "Auto",
            FnSlot.EV to "+0.0",
            FnSlot.ZOOM to "1.0×",
            FnSlot.STABILIZATION to "Active",
            FnSlot.DRIVE to "Single",
            FnSlot.METERING to "Matrix",
            FnSlot.PEAKING to "Off",
            FnSlot.ZEBRA to "Off",
            FnSlot.TRANSFER to "HLG",
            FnSlot.AUDIO_SCENE to "Standard",
            FnSlot.GRID to "Thirds",
            FnSlot.LEVEL to "Off",
            FnSlot.PUNCH_IN to "Off",
            FnSlot.TELECONVERTER to "Off",
            FnSlot.OPEN_GATE to "Off",
            FnSlot.FRAME_LINES to "Off",
        )
        assertEquals(FnSlot.entries.toSet(), expected.keys)
        expected.forEach { (slot, value) -> assertEquals("$slot", value, fnSlotValue(slot, state)) }
    }

    @Test
    fun `program shutter and iso show the live AE-resolved values`() {
        val state = CameraUiState(liveExposureNs = 8_000_000L, liveIso = 800)
        assertEquals("Auto 1/125s", fnSlotValue(FnSlot.SHUTTER, state))
        assertEquals("Auto 800", fnSlotValue(FnSlot.ISO, state))
    }

    @Test
    fun `iso priority marks the loop-driven shutter while iso stays the owned value`() {
        val state = CameraUiState(
            controls = ManualControls(exposureMode = ExposureMode.ISO, iso = 1600),
        )
        assertEquals("Auto 1/125s", fnSlotValue(FnSlot.SHUTTER, state))
        assertEquals("1600", fnSlotValue(FnSlot.ISO, state))
    }

    @Test
    fun `shutter priority marks the loop-driven iso while shutter stays the owned value`() {
        val state = CameraUiState(
            controls = ManualControls(exposureMode = ExposureMode.SHUTTER, iso = 1600),
        )
        assertEquals("1/125s", fnSlotValue(FnSlot.SHUTTER, state))
        assertEquals("Auto 1600", fnSlotValue(FnSlot.ISO, state))
    }

    @Test
    fun `manual cine angle shows degrees not seconds`() {
        val state = CameraUiState(
            controls = ManualControls(
                exposureMode = ExposureMode.MANUAL,
                shutterMode = ShutterMode.ANGLE,
                shutterAngle = 172.8f,
            ),
        )
        assertEquals("173°", fnSlotValue(FnSlot.SHUTTER, state))
        assertEquals("M", fnSlotValue(FnSlot.EXPOSURE_MODE, state))
    }

    @Test
    fun `manual wb shows kelvin while presets show their label`() {
        val manual = CameraUiState(
            controls = ManualControls(wbMode = WbMode.MANUAL, wbKelvin = 5600),
        )
        assertEquals("5600K", fnSlotValue(FnSlot.WB, manual))
        val preset = CameraUiState(controls = ManualControls(wbMode = WbMode.DAYLIGHT))
        assertEquals("Daylight", fnSlotValue(FnSlot.WB, preset))
    }

    @Test
    fun `ev scales the index by the conventional third-stop fallback`() {
        val state = CameraUiState(
            controls = ManualControls(exposureMode = ExposureMode.SHUTTER, exposureCompensation = 3),
        )
        assertEquals("+1.0", fnSlotValue(FnSlot.EV, state))
        assertEquals(
            "-1.0",
            fnSlotValue(
                FnSlot.EV,
                CameraUiState(
                    controls = ManualControls(
                        exposureMode = ExposureMode.SHUTTER,
                        exposureCompensation = -3,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `zoom reads main-relative in tele and lens-local on the front camera`() {
        // TELE local 1.0 = the converter's 13x main-relative display base.
        val tele = CameraUiState(teleconverterMode = true)
        assertEquals("13.0×", fnSlotValue(FnSlot.ZOOM, tele))
        // FRONT has no place on the rear main-relative scale: honest lens-local ratio.
        val front = CameraUiState(
            facing = CameraFacing.FRONT,
            controls = ManualControls(zoomRatio = 2f),
        )
        assertEquals("2.0×", fnSlotValue(FnSlot.ZOOM, front))
    }

    @Test
    fun `enabled toggles and cycled enums read their active copy`() {
        val state = CameraUiState(
            focusPeaking = true,
            zebra = true,
            level = true,
            punchIn = true,
            teleconverterMode = true,
            openGate = true,
            grid = GridType.GOLDEN,
            frameLines = FrameLineType.CINEMA,
            transfer = ColorTransfer.SLOG3_CINE,
            audioScene = AudioScene.SOUND_FOCUS,
            videoStabMode = VideoStabMode.OFF,
            driveMode = DriveMode.TIMELAPSE,
            controls = ManualControls(
                focusMode = FocusMode.MANUAL,
                meteringMode = MeteringMode.SPOT,
            ),
        )
        assertEquals("On", fnSlotValue(FnSlot.PEAKING, state))
        assertEquals("On", fnSlotValue(FnSlot.ZEBRA, state))
        assertEquals("On", fnSlotValue(FnSlot.LEVEL, state))
        assertEquals("On", fnSlotValue(FnSlot.PUNCH_IN, state))
        assertEquals("300 mm", fnSlotValue(FnSlot.TELECONVERTER, state))
        assertEquals("4:3", fnSlotValue(FnSlot.OPEN_GATE, state))
        assertEquals("Golden", fnSlotValue(FnSlot.GRID, state))
        assertEquals("2.39:1", fnSlotValue(FnSlot.FRAME_LINES, state))
        assertEquals("SG3C", fnSlotValue(FnSlot.TRANSFER, state))
        assertEquals("Sound Focus", fnSlotValue(FnSlot.AUDIO_SCENE, state))
        assertEquals("Off", fnSlotValue(FnSlot.STABILIZATION, state))
        assertEquals("Timelapse", fnSlotValue(FnSlot.DRIVE, state))
        assertEquals("MF", fnSlotValue(FnSlot.FOCUS, state))
        assertEquals("Spot", fnSlotValue(FnSlot.METERING, state))
    }
}
