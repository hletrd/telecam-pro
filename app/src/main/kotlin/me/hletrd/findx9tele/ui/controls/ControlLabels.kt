package me.hletrd.findx9tele.ui.controls

import me.hletrd.findx9tele.camera.Antibanding
import me.hletrd.findx9tele.camera.AspectRatio
import me.hletrd.findx9tele.camera.BitrateLevel
import me.hletrd.findx9tele.camera.ColorEffect
import me.hletrd.findx9tele.camera.ColorTransfer
import me.hletrd.findx9tele.camera.DriveMode
import me.hletrd.findx9tele.camera.FlashMode
import me.hletrd.findx9tele.camera.FnSlot
import me.hletrd.findx9tele.camera.FocusMode
import me.hletrd.findx9tele.camera.GridType
import me.hletrd.findx9tele.camera.HardwareKeyAction
import me.hletrd.findx9tele.camera.MeteringMode
import me.hletrd.findx9tele.camera.ProcessingLevel
import me.hletrd.findx9tele.camera.ShutterMode
import me.hletrd.findx9tele.camera.ShutterTimer
import me.hletrd.findx9tele.camera.VideoCodec
import me.hletrd.findx9tele.camera.VideoFrameRate
import me.hletrd.findx9tele.camera.WbMode
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

/**
 * A dropdown trigger reads as "<label>, <selected>" so a device UI test can find the row by its
 * label alone and assert the selection from the same node.
 */
internal fun dropdownSettingSemantics(label: String, selected: String): SettingSemantics =
    SettingSemantics(label = label, state = selected)

/**
 * The spoken NAME of one chip inside a [SegmentedSelector]-class row. Unlike the three builders above,
 * the row's visible label is a SIBLING of the chips, not part of them — so each chip announced only its
 * own value and the label became an orphan node. The Image tab stacks "Sharpness" and "NR"
 * consecutively and BOTH draw [processingLevelLabel], so a TalkBack user heard "Off, Fast, HQ" twice
 * over with nothing naming which control they were on. Carrying the row label into each chip's own name
 * is what makes a chip self-describing, and the name stays stable per option because it is built from
 * that chip's OWN value, never the row's current selection.
 *
 * A bare String, not a [SettingSemantics]: a chip has no state description to give. Its selected /
 * not-selected state comes from Material's own `selectable` semantics, and writing a `stateDescription`
 * here would REPLACE that announcement with the option name — the one fact the chip's name already
 * carries. This returned a pair whose second half nothing read, which is exactly what let a test pin
 * `state == option` and report coverage of a value no user could ever hear.
 */
internal fun segmentedOptionName(label: String, option: String): String = "$label $option"

// ---------------------------------------------------------------------------
// Enum -> short-label mappings
// ---------------------------------------------------------------------------

internal fun focusModeLabel(mode: FocusMode): String = when (mode) {
    FocusMode.MANUAL -> "MF"
    FocusMode.AUTO -> "AF"
    FocusMode.CONTINUOUS -> "AF-C"
    FocusMode.MACRO -> "Macro"
}

/**
 * The FOCUS dial chip's spoken STATE: its AF mode and its distance readout, in the order the pill
 * draws them.
 *
 * The chip is the one dial whose pill spends its LABEL slot on a live value — it draws
 * [focusModeLabel] where every sibling draws its own name — so the chip carries two facts, not one.
 * Pinning the node's name to the stable slot name ("Focus") fixed a name that was renamed on every
 * AF cycle, but it also left the mode with nowhere to go: name "Focus" + state "∞+42" speaks the
 * distance and drops the mode a sighted user reads right beside it. The mode is a VALUE, so it
 * belongs in the state with the distance, and the two are comma-separated so TalkBack pauses
 * between them instead of running "MF" into the digits ("Focus, MF, ∞+42").
 *
 * The drawn abbreviations are kept verbatim rather than expanded: MF / AF / AF-C are the printed
 * vocabulary of every camera body this app is modelled on, and inventing a second spoken wording
 * would put the sighted and TalkBack readings of the same pill out of step.
 */
internal fun focusDialStateDescription(mode: FocusMode, distance: String): String =
    "${focusModeLabel(mode)}, $distance"

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
internal fun lensLabel(lens: me.hletrd.findx9tele.camera.LensChoice): String = when (lens) {
    me.hletrd.findx9tele.camera.LensChoice.ULTRAWIDE -> "0.6×"
    me.hletrd.findx9tele.camera.LensChoice.MAIN -> "1×"
    me.hletrd.findx9tele.camera.LensChoice.TELE3X -> "3×"
    me.hletrd.findx9tele.camera.LensChoice.TELE10X -> "10×"
}

/**
 * Compact zoom typography for the TELE rail's magnification marks. The rail's own idiom is
 * `0.6× / 1× / 3× / 10×` — a decimal only where it carries information — so a derived mark rounds to
 * one decimal and then drops a zero one: 13.043 → "13×", 6.087 → "6.1×".
 *
 * Deliberately NOT the shared `formatZoomMultiplier`, which prints an unconditional decimal: that
 * formatter serves the LIVE zoom pill, where a digit appearing and disappearing mid-sweep would
 * shift the text width on every frame. A rail mark is a fixed value, so it can be typeset tightly.
 */
internal fun formatZoomMark(multiplier: Float): String {
    val tenths = kotlin.math.round(multiplier * 10f).toInt()
    return if (tenths % 10 == 0) "${tenths / 10}×" else "%.1f×".format(Locale.US, tenths / 10f)
}

// The ONE focal-readout typography: whole millimetres, no decimals — "300 mm", "165 mm". Every
// converter-derived focal (Fn tiles, lens caption, MR summary) reads through this so a custom
// magnification can never surface as "164.50 mm" on one surface and "165 mm" on another.
internal fun formatFocalMm(mm: Float): String = "${kotlin.math.round(mm).toInt()} mm"

// The 3× caption must be TRUTHFUL about the converter: lens picks are zoom presets that do NOT
// bundle TELE, so an unconditional "+ TC = 300 mm" claimed the afocal correction was active when
// the adjacent toggle was off — an operator could shoot a mounted converter uncorrected. The focal
// itself follows the SELECTED converter, so a generic 2× reads "140 mm equiv.", not the kit's 300.
internal fun lensFocalCaption(
    lens: me.hletrd.findx9tele.camera.LensChoice,
    teleconverter: Boolean,
    teleconverterFocalMm: Float,
): String = when (lens) {
    me.hletrd.findx9tele.camera.LensChoice.ULTRAWIDE -> "14 mm"
    me.hletrd.findx9tele.camera.LensChoice.MAIN -> "23 mm"
    me.hletrd.findx9tele.camera.LensChoice.TELE3X ->
        if (teleconverter) "${formatFocalMm(teleconverterFocalMm)} equiv." else "70 mm"
    me.hletrd.findx9tele.camera.LensChoice.TELE10X -> "230 mm"
}

internal fun driveModeLabel(mode: DriveMode): String = when (mode) {
    DriveMode.SINGLE -> "Single"
    DriveMode.BURST -> "Burst"
    DriveMode.AEB -> "AEB"
    DriveMode.TIMELAPSE -> "Timelapse"
}

internal fun fnSlotLabel(slot: FnSlot): String = slot.label

internal fun hardwareKeyActionLabel(action: HardwareKeyAction): String = action.label

internal fun aspectRatioLabel(ratio: AspectRatio): String = when (ratio) {
    AspectRatio.W16_9 -> "16:9"
    AspectRatio.W4_3 -> "4:3"
}

internal fun videoCodecLabel(codec: VideoCodec): String = when (codec) {
    VideoCodec.HEVC -> "HEVC"
    VideoCodec.AVC -> "H.264"
    // All-intra professional codec (ProRes / XAVC-I class), HW-accelerated, very high bitrate.
    // UNREACHABLE user-facing copy: APV is deliberately excluded from the offered codecs because
    // MediaMuxer (API 36) rejects it in MP4 (device-verified). EncoderCaps can never surface it, so
    // this branch exists only to keep the `when` exhaustive.
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

// (No absolute focus-distance formatter here on purpose: through the afocal converter the exit
// light is ~collimated and the lens sits near infinity, so a metre readout is meaningless. The
// manual focus ruler shows a RELATIVE position instead — formatFocusRelative in ManualDials.kt.
// The metre formatter was deleted 2026-07-26 with its test; it had no main-source callers and the
// repo was pinning a formatter it had deliberately stopped shipping.)

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
