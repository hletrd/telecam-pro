package com.hletrd.findx9tele.ui.controls

import com.hletrd.findx9tele.camera.Antibanding
import com.hletrd.findx9tele.camera.AspectRatio
import com.hletrd.findx9tele.camera.BitrateLevel
import com.hletrd.findx9tele.camera.ColorEffect
import com.hletrd.findx9tele.camera.ColorTransfer
import com.hletrd.findx9tele.camera.DriveMode
import com.hletrd.findx9tele.camera.FlashMode
import com.hletrd.findx9tele.camera.FnSlot
import com.hletrd.findx9tele.camera.FocusMode
import com.hletrd.findx9tele.camera.GridType
import com.hletrd.findx9tele.camera.HardwareKeyAction
import com.hletrd.findx9tele.camera.LensChoice
import com.hletrd.findx9tele.camera.MemorySlot
import com.hletrd.findx9tele.camera.MeteringMode
import com.hletrd.findx9tele.camera.ProcessingLevel
import com.hletrd.findx9tele.camera.ShutterMode
import com.hletrd.findx9tele.camera.ShutterTimer
import com.hletrd.findx9tele.camera.VideoCodec
import com.hletrd.findx9tele.camera.VideoFrameRate
import com.hletrd.findx9tele.camera.WbMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Snapshot-pins the exact user-facing copy of every enum -> label formatter in ControlLabels.kt
 * (the shutter/focus formatters, lensFocalCaption, SettingSemantics, and videoResolutionLabelFor
 * have their own dedicated tests). Each mapping is asserted EXHAUSTIVELY: the expected map must
 * cover every enum entry, so adding a value without deciding its label fails here instead of
 * silently shipping an enum name to the UI.
 */
class ControlLabelsTest {

    private inline fun <reified T : Enum<T>> assertLabels(
        expected: Map<T, String>,
        label: (T) -> String,
    ) {
        assertEquals(enumValues<T>().toSet(), expected.keys)
        expected.forEach { (value, copy) -> assertEquals("$value", copy, label(value)) }
    }

    @Test
    fun `focus mode labels`() = assertLabels(
        mapOf(
            FocusMode.MANUAL to "MF",
            FocusMode.AUTO to "AF",
            FocusMode.CONTINUOUS to "AF-C",
            FocusMode.MACRO to "Macro",
        ),
        ::focusModeLabel,
    )

    @Test
    fun `antibanding labels`() = assertLabels(
        mapOf(
            Antibanding.AUTO to "Auto",
            Antibanding.HZ50 to "50Hz",
            Antibanding.HZ60 to "60Hz",
            Antibanding.OFF to "Off",
        ),
        ::antibandingLabel,
    )

    @Test
    fun `processing level labels`() = assertLabels(
        mapOf(
            ProcessingLevel.OFF to "Off",
            ProcessingLevel.FAST to "Fast",
            ProcessingLevel.HIGH_QUALITY to "HQ",
        ),
        ::processingLevelLabel,
    )

    @Test
    fun `color effect labels`() = assertLabels(
        mapOf(
            ColorEffect.NONE to "None",
            ColorEffect.MONO to "Mono",
            ColorEffect.NEGATIVE to "Negative",
            ColorEffect.SEPIA to "Sepia",
            ColorEffect.AQUA to "Aqua",
            ColorEffect.POSTERIZE to "Posterize",
        ),
        ::colorEffectLabel,
    )

    @Test
    fun `flash mode labels`() = assertLabels(
        mapOf(
            FlashMode.OFF to "Off",
            FlashMode.AUTO to "Auto",
            FlashMode.ON to "On",
            FlashMode.TORCH to "Torch",
        ),
        ::flashModeLabel,
    )

    @Test
    fun `grid type labels`() = assertLabels(
        mapOf(
            GridType.NONE to "None",
            GridType.THIRDS to "Thirds",
            GridType.GOLDEN to "Golden",
            GridType.SQUARE to "Square",
            GridType.CENTER to "Center",
        ),
        ::gridTypeLabel,
    )

    @Test
    fun `shutter timer labels`() = assertLabels(
        mapOf(
            ShutterTimer.OFF to "Off",
            ShutterTimer.SEC3 to "3s",
            ShutterTimer.SEC10 to "10s",
        ),
        ::shutterTimerLabel,
    )

    @Test
    fun `shutter mode labels`() = assertLabels(
        mapOf(
            ShutterMode.SPEED to "Speed",
            ShutterMode.ANGLE to "Angle",
        ),
        ::shutterModeLabel,
    )

    @Test
    fun `wb mode labels`() = assertLabels(
        mapOf(
            WbMode.AUTO to "Auto",
            WbMode.INCANDESCENT to "Tungsten",
            WbMode.FLUORESCENT to "Fluor.",
            WbMode.DAYLIGHT to "Daylight",
            WbMode.CLOUDY to "Cloudy",
            WbMode.SHADE to "Shade",
            WbMode.CUSTOM to "Custom",
            WbMode.MANUAL to "Manual",
        ),
        ::wbModeLabel,
    )

    @Test
    fun `metering mode labels`() = assertLabels(
        mapOf(
            MeteringMode.MATRIX to "Matrix",
            MeteringMode.CENTER to "Center",
            MeteringMode.SPOT to "Spot",
        ),
        ::meteringModeLabel,
    )

    @Test
    fun `lens labels use magnification not UW`() = assertLabels(
        mapOf(
            LensChoice.ULTRAWIDE to "0.6×",
            LensChoice.MAIN to "1×",
            LensChoice.TELE3X to "3×",
            LensChoice.TELE10X to "10×",
        ),
        ::lensLabel,
    )

    @Test
    fun `drive mode labels`() = assertLabels(
        mapOf(
            DriveMode.SINGLE to "Single",
            DriveMode.BURST to "Burst",
            DriveMode.AEB to "AEB",
            DriveMode.TIMELAPSE to "Timelapse",
        ),
        ::driveModeLabel,
    )

    @Test
    fun `fn slot labels`() = assertLabels(
        mapOf(
            FnSlot.EXPOSURE_MODE to "AE",
            FnSlot.FOCUS to "Focus",
            FnSlot.SHUTTER to "Shutter",
            FnSlot.ISO to "ISO",
            FnSlot.WB to "WB",
            FnSlot.EV to "EV",
            FnSlot.ZOOM to "Zoom",
            FnSlot.STABILIZATION to "Stabilization",
            FnSlot.DRIVE to "Drive",
            FnSlot.METERING to "Meter",
            FnSlot.PEAKING to "Peaking",
            FnSlot.ZEBRA to "Zebra",
            FnSlot.TRANSFER to "Gamma",
            FnSlot.AUDIO_SCENE to "Audio",
            FnSlot.GRID to "Grid",
            FnSlot.LEVEL to "Level",
            FnSlot.PUNCH_IN to "Loupe",
            FnSlot.TELECONVERTER to "Tele",
            FnSlot.OPEN_GATE to "Open Gate",
            FnSlot.FRAME_LINES to "Frame",
        ),
        ::fnSlotLabel,
    )

    @Test
    fun `memory slot labels`() = assertLabels(
        mapOf(
            MemorySlot.MR1 to "MR1",
            MemorySlot.MR2 to "MR2",
            MemorySlot.MR3 to "MR3",
        ),
        ::memorySlotLabel,
    )

    @Test
    fun `hardware key action labels`() = assertLabels(
        mapOf(
            HardwareKeyAction.SHUTTER to "Shutter/REC",
            HardwareKeyAction.AF_ON to "AF-ON",
            HardwareKeyAction.AEL to "AEL",
            HardwareKeyAction.PUNCH_IN to "Loupe",
            HardwareKeyAction.ZOOM_IN to "Zoom In",
            HardwareKeyAction.ZOOM_OUT to "Zoom Out",
            HardwareKeyAction.NONE to "None",
        ),
        ::hardwareKeyActionLabel,
    )

    @Test
    fun `aspect ratio labels`() = assertLabels(
        mapOf(
            AspectRatio.W16_9 to "16:9",
            AspectRatio.W4_3 to "4:3",
        ),
        ::aspectRatioLabel,
    )

    @Test
    fun `video codec labels`() = assertLabels(
        mapOf(
            VideoCodec.HEVC to "HEVC",
            VideoCodec.AVC to "H.264",
            VideoCodec.APV to "APV Intra",
        ),
        ::videoCodecLabel,
    )

    @Test
    fun `short video codec labels`() = assertLabels(
        mapOf(
            VideoCodec.HEVC to "HEVC",
            VideoCodec.AVC to "H.264",
            VideoCodec.APV to "APV",
        ),
        ::videoCodecLabelShort,
    )

    @Test
    fun `video frame rate labels`() = assertLabels(
        mapOf(
            VideoFrameRate.FPS_23_976 to "23.976",
            VideoFrameRate.FPS_24 to "24",
            VideoFrameRate.FPS_25 to "25",
            VideoFrameRate.FPS_29_97 to "29.97",
            VideoFrameRate.FPS_30 to "30",
            VideoFrameRate.FPS_50 to "50",
            VideoFrameRate.FPS_59_94 to "59.94",
            VideoFrameRate.FPS_60 to "60",
            VideoFrameRate.FPS_120 to "120",
        ),
        ::videoFrameRateLabel,
    )

    @Test
    fun `bitrate level labels`() = assertLabels(
        mapOf(
            BitrateLevel.LOW to "Low",
            BitrateLevel.MEDIUM to "Medium",
            BitrateLevel.HIGH to "High",
            BitrateLevel.ULTRA to "Ultra",
            BitrateLevel.MAX to "Max",
        ),
        ::bitrateLevelLabel,
    )

    @Test
    fun `transfer labels say what the footage is`() = assertLabels(
        mapOf(
            ColorTransfer.HLG to "HLG",
            ColorTransfer.SLOG3 to "S-Log3",
            ColorTransfer.SLOG3_CINE to "S-Log3.Cine",
            ColorTransfer.LOGC3 to "LogC3",
            ColorTransfer.SDR to "SDR",
        ),
        ::transferLabel,
    )

    @Test
    fun `short transfer labels fit the OSD`() = assertLabels(
        mapOf(
            ColorTransfer.HLG to "HLG",
            ColorTransfer.SLOG3 to "SLOG3",
            // SG3C = the community shorthand for S-Gamut3.Cine.
            ColorTransfer.SLOG3_CINE to "SG3C",
            ColorTransfer.LOGC3 to "LOGC3",
            ColorTransfer.SDR to "SDR",
        ),
        ::transferLabelShort,
    )
}
