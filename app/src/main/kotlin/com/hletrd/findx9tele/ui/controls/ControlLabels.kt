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
import com.hletrd.findx9tele.camera.MemorySlot
import com.hletrd.findx9tele.camera.MeteringMode
import com.hletrd.findx9tele.camera.ProcessingLevel
import com.hletrd.findx9tele.camera.ShutterMode
import com.hletrd.findx9tele.camera.ShutterTimer
import com.hletrd.findx9tele.camera.VideoCodec
import com.hletrd.findx9tele.camera.VideoFrameRate
import com.hletrd.findx9tele.camera.WbMode
import com.hletrd.findx9tele.focus.FocusMapping
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Non-composable label/format helpers for the pro controls, hoisted (behavior-locked, verbatim) out
 * of ProControls.kt: every enum -> short-label mapping, the shutter-speed/focus-distance formatters,
 * and the accessibility [SettingSemantics] pairs. These pin the exact user-facing copy shown across
 * the settings sheet, Fn surfaces, dials, and OSD, so they live apart from Compose emission where
 * plain host JUnit can snapshot them. The one framework-typed wrapper, `videoResolutionLabel(Size)`,
 * stays in ProControls.kt (android.util.Size is not mocked on the JVM) and delegates to
 * [videoResolutionLabelFor].
 */

internal data class SettingSemantics(val label: String, val state: String)

internal fun sliderSettingSemantics(label: String, value: String): SettingSemantics =
    SettingSemantics(label = label, state = value)

internal fun toggleSettingSemantics(label: String, checked: Boolean): SettingSemantics =
    SettingSemantics(label = label, state = if (checked) "On" else "Off")

// ---------------------------------------------------------------------------
// Enum -> short-label mappings
// ---------------------------------------------------------------------------

internal fun focusModeLabel(mode: FocusMode): String = when (mode) {
    FocusMode.MANUAL -> "MF"
    FocusMode.AUTO -> "AF"
    FocusMode.CONTINUOUS -> "AF-C"
    FocusMode.MACRO -> "Macro"
}

internal fun antibandingLabel(mode: Antibanding): String = when (mode) {
    Antibanding.AUTO -> "Auto"
    Antibanding.HZ50 -> "50Hz"
    Antibanding.HZ60 -> "60Hz"
    Antibanding.OFF -> "Off"
}

internal fun processingLevelLabel(level: ProcessingLevel): String = when (level) {
    ProcessingLevel.OFF -> "Off"
    ProcessingLevel.FAST -> "Fast"
    ProcessingLevel.HIGH_QUALITY -> "HQ"
}

internal fun colorEffectLabel(effect: ColorEffect): String = when (effect) {
    ColorEffect.NONE -> "None"
    ColorEffect.MONO -> "Mono"
    ColorEffect.NEGATIVE -> "Negative"
    ColorEffect.SEPIA -> "Sepia"
    ColorEffect.AQUA -> "Aqua"
    ColorEffect.POSTERIZE -> "Posterize"
}

internal fun flashModeLabel(mode: FlashMode): String = when (mode) {
    FlashMode.OFF -> "Off"
    FlashMode.AUTO -> "Auto"
    FlashMode.ON -> "On"
    FlashMode.TORCH -> "Torch"
}

internal fun gridTypeLabel(type: GridType): String = when (type) {
    GridType.NONE -> "None"
    GridType.THIRDS -> "Thirds"
    GridType.GOLDEN -> "Golden"
    GridType.SQUARE -> "Square"
    GridType.CENTER -> "Center"
}

internal fun shutterTimerLabel(timer: ShutterTimer): String = when (timer) {
    ShutterTimer.OFF -> "Off"
    ShutterTimer.SEC3 -> "3s"
    ShutterTimer.SEC10 -> "10s"
}

internal fun shutterModeLabel(mode: ShutterMode): String = when (mode) {
    ShutterMode.SPEED -> "Speed"
    ShutterMode.ANGLE -> "Angle"
}

internal fun wbModeLabel(mode: WbMode): String = when (mode) {
    WbMode.AUTO -> "Auto"
    WbMode.INCANDESCENT -> "Tungsten"
    WbMode.FLUORESCENT -> "Fluor."
    WbMode.DAYLIGHT -> "Daylight"
    WbMode.CLOUDY -> "Cloudy"
    WbMode.SHADE -> "Shade"
    WbMode.CUSTOM -> "Custom"
    WbMode.MANUAL -> "Manual"
}

internal fun meteringModeLabel(mode: MeteringMode): String = when (mode) {
    MeteringMode.MATRIX -> "Matrix"
    MeteringMode.CENTER -> "Center"
    MeteringMode.SPOT -> "Spot"
}

// Magnification labels throughout (0.6×/1×/3×/10×), matching stock camera apps — "UW" was the odd
// one out (user feedback).
internal fun lensLabel(lens: com.hletrd.findx9tele.camera.LensChoice): String = when (lens) {
    com.hletrd.findx9tele.camera.LensChoice.ULTRAWIDE -> "0.6×"
    com.hletrd.findx9tele.camera.LensChoice.MAIN -> "1×"
    com.hletrd.findx9tele.camera.LensChoice.TELE3X -> "3×"
    com.hletrd.findx9tele.camera.LensChoice.TELE10X -> "10×"
}

// The 3× caption must be TRUTHFUL about the converter: lens picks are zoom presets that do NOT
// bundle TELE, so an unconditional "+ TC = 300 mm" claimed the afocal correction was active when
// the adjacent toggle was off — an operator could shoot a mounted converter uncorrected.
internal fun lensFocalCaption(
    lens: com.hletrd.findx9tele.camera.LensChoice,
    teleconverter: Boolean,
): String = when (lens) {
    com.hletrd.findx9tele.camera.LensChoice.ULTRAWIDE -> "14 mm"
    com.hletrd.findx9tele.camera.LensChoice.MAIN -> "23 mm"
    com.hletrd.findx9tele.camera.LensChoice.TELE3X -> if (teleconverter) "300 mm equiv." else "70 mm"
    com.hletrd.findx9tele.camera.LensChoice.TELE10X -> "230 mm"
}

internal fun driveModeLabel(mode: DriveMode): String = when (mode) {
    DriveMode.SINGLE -> "Single"
    DriveMode.BURST -> "Burst"
    DriveMode.AEB -> "AEB"
    DriveMode.TIMELAPSE -> "Timelapse"
}

internal fun fnSlotLabel(slot: FnSlot): String = slot.label

internal fun memorySlotLabel(slot: MemorySlot): String = slot.label

internal fun hardwareKeyActionLabel(action: HardwareKeyAction): String = action.label

internal fun aspectRatioLabel(ratio: AspectRatio): String = when (ratio) {
    AspectRatio.W16_9 -> "16:9"
    AspectRatio.W4_3 -> "4:3"
}

internal fun videoCodecLabel(codec: VideoCodec): String = when (codec) {
    VideoCodec.HEVC -> "HEVC"
    VideoCodec.AVC -> "H.264"
    // All-intra professional codec (ProRes / XAVC-I class), HW-accelerated, very high bitrate.
    VideoCodec.APV -> "APV Intra"
}

internal fun videoFrameRateLabel(rate: VideoFrameRate): String = rate.label

/** Compact codec name for the encoder-summary row (no "(SW, slow)" qualifier). */
internal fun videoCodecLabelShort(codec: VideoCodec): String = when (codec) {
    VideoCodec.HEVC -> "HEVC"
    VideoCodec.AVC -> "H.264"
    VideoCodec.APV -> "APV"
}

internal fun bitrateLevelLabel(level: BitrateLevel): String = when (level) {
    BitrateLevel.LOW -> "Low"
    BitrateLevel.MEDIUM -> "Medium"
    BitrateLevel.HIGH -> "High"
    BitrateLevel.ULTRA -> "Ultra"
    BitrateLevel.MAX -> "Max"
}

/** Plain-int core of [videoResolutionLabel] (android.util.Size is not mocked on the JVM). */
internal fun videoResolutionLabelFor(width: Int, height: Int): String {
    val is43 = height * 4 == width * 3
    // 4:3 classes key on HEIGHT (TEST4-11/P5.9): the K-name of a 4:3 frame is defined by the
    // vertical resolution its class implies (4K 4:3 = 3840x2880), so a nonstandard-width size
    // classifies by what it vertically resolves, not by the widest width bucket it crosses.
    if (is43) return when {
        height >= 5760 -> "8K 4:3"
        height >= 2880 -> "4K 4:3"
        height >= 1920 -> "2.5K 4:3"
        height >= 1440 -> "1080 4:3"
        else -> "${width}×$height"
    }
    return when (height) {
        4320 -> "8K"
        2160 -> "4K"
        1440 -> "1440p"
        1080 -> "1080p"
        720 -> "720p"
        else -> "${width}×$height"
    }
}

// ---------------------------------------------------------------------------
// Exposure: shutter/focus display helpers (shared by the exposure tab and the manual dials)
// ---------------------------------------------------------------------------

// Conventional shutter-speed denominators, so an exact 2^k time (e.g. 1/128 s) displays as the
// camera-standard value a photographer expects (1/125 s).
private val NICE_SHUTTER_DENOM = intArrayOf(
    1, 2, 3, 4, 5, 6, 8, 10, 13, 15, 20, 25, 30, 40, 50, 60, 80, 100, 125, 160, 200, 250, 320,
    400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000,
)

internal fun formatShutterSpeed(ns: Long): String {
    val seconds = ns / 1_000_000_000.0
    // Every numeric readout in the pro UI pins Locale.US: a camera speed/aperture/EV is a fixed
    // photographic convention ("0.8s", not the comma-decimal "0,8s" a German locale would print),
    // matching the Locale.US the capture path already uses (CameraEngine.kt SimpleDateFormat). All
    // sibling `.format` calls in the controls/overlays pass Locale.US for the same reason.
    return when {
        seconds >= 10.0 -> "%.0fs".format(Locale.US, seconds)
        seconds >= 1.0 -> "%.1fs".format(Locale.US, seconds)
        else -> {
            val denom = 1.0 / seconds
            val nice = NICE_SHUTTER_DENOM.minByOrNull { kotlin.math.abs(it - denom) } ?: denom.roundToInt().coerceAtLeast(1)
            // Times in [0.667 s, 1 s) have no conventional 1/x form — snapping produced the
            // nonsensical "1/1s" (e.g. 0.75 s). Show decimal seconds there like real bodies do.
            if (nice <= 1) "%.1fs".format(Locale.US, seconds) else "1/${nice}s"
        }
    }
}

/** Human-readable focus distance. Infinity for diopters <= 0. Shared with the manual focus ruler. */
internal fun formatFocusDistance(diopters: Float): String {
    val meters = FocusMapping.dioptersToMeters(diopters)
    return when {
        meters.isInfinite() -> "∞"
        meters < 1f -> "${(meters * 100).roundToInt()}cm"
        else -> "%.2fm".format(Locale.US, meters)
    }
}

/** Transfer-function display label: what the footage IS, not just the enum name. */
internal fun transferLabel(transfer: ColorTransfer): String = when (transfer) {
    ColorTransfer.HLG -> "HLG"
    // The log profiles are GL-baked standard curves applied to the display-referred SDR stream
    // (the architecture inherited from the removed O-Log2 option). The native HAL log key is INERT
    // for third-party Camera2 on this device (settled 2026-07-09) — see CLAUDE.md /
    // CameraEngine.setTransfer.
    ColorTransfer.SLOG3 -> "S-Log3"
    ColorTransfer.SLOG3_CINE -> "S-Log3.Cine"
    ColorTransfer.LOGC3 -> "LogC3"
    ColorTransfer.SDR -> "SDR"
}

/** Compact transfer name for the video-mode quick chip and the OSD. */
internal fun transferLabelShort(transfer: ColorTransfer): String = when (transfer) {
    ColorTransfer.HLG -> "HLG"
    ColorTransfer.SLOG3 -> "SLOG3"
    // SG3C = the community-standard shorthand for S-Gamut3.Cine (the full name won't fit the OSD).
    ColorTransfer.SLOG3_CINE -> "SG3C"
    ColorTransfer.LOGC3 -> "LOGC3"
    ColorTransfer.SDR -> "SDR"
}
