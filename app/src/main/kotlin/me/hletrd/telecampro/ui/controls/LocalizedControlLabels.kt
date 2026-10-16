package me.hletrd.telecampro.ui.controls

import android.content.Context
import androidx.annotation.StringRes
import me.hletrd.telecampro.R
import me.hletrd.telecampro.camera.*

@StringRes private fun FocusMode.labelRes() = when (this) { FocusMode.MANUAL -> R.string.value_manual; FocusMode.AUTO -> R.string.value_auto; FocusMode.CONTINUOUS -> R.string.value_continuous; FocusMode.MACRO -> R.string.value_macro }
@StringRes private fun Antibanding.labelRes() = when (this) { Antibanding.AUTO -> R.string.value_auto; Antibanding.HZ50 -> R.string.value_50hz; Antibanding.HZ60 -> R.string.value_60hz; Antibanding.OFF -> R.string.value_off }
@StringRes private fun ProcessingLevel.labelRes() = when (this) { ProcessingLevel.OFF -> R.string.value_off; ProcessingLevel.FAST -> R.string.value_fast; ProcessingLevel.HIGH_QUALITY -> R.string.value_high_quality }
@StringRes private fun ColorEffect.labelRes() = when (this) { ColorEffect.NONE -> R.string.value_none; ColorEffect.MONO -> R.string.value_monochrome; ColorEffect.NEGATIVE -> R.string.value_negative; ColorEffect.SEPIA -> R.string.value_sepia; ColorEffect.AQUA -> R.string.value_aqua; ColorEffect.POSTERIZE -> R.string.value_posterize }
@StringRes private fun FlashMode.labelRes() = when (this) { FlashMode.OFF -> R.string.value_off; FlashMode.AUTO -> R.string.value_auto; FlashMode.ON -> R.string.value_on; FlashMode.TORCH -> R.string.value_torch }
@StringRes private fun GridType.labelRes() = when (this) { GridType.NONE -> R.string.value_off; GridType.THIRDS -> R.string.value_thirds; GridType.GOLDEN -> R.string.value_golden_ratio; GridType.SQUARE -> R.string.value_square; GridType.CENTER -> R.string.value_center }
@StringRes private fun ShutterTimer.labelRes() = when (this) { ShutterTimer.OFF -> R.string.value_off; ShutterTimer.SEC3 -> R.string.value_3_seconds; ShutterTimer.SEC10 -> R.string.value_10_seconds }
@StringRes private fun ShutterMode.labelRes() = when (this) { ShutterMode.SPEED -> R.string.value_speed; ShutterMode.ANGLE -> R.string.value_angle }
@StringRes private fun WbMode.labelRes() = when (this) { WbMode.AUTO -> R.string.value_auto; WbMode.INCANDESCENT -> R.string.value_tungsten; WbMode.FLUORESCENT -> R.string.value_fluorescent; WbMode.DAYLIGHT -> R.string.value_daylight; WbMode.CLOUDY -> R.string.value_cloudy; WbMode.SHADE -> R.string.value_shade; WbMode.CUSTOM -> R.string.value_custom; WbMode.MANUAL -> R.string.value_manual }
@StringRes private fun MeteringMode.labelRes() = when (this) { MeteringMode.MATRIX -> R.string.value_matrix; MeteringMode.CENTER -> R.string.value_center; MeteringMode.SPOT -> R.string.value_spot }
@StringRes private fun DriveMode.labelRes() = when (this) { DriveMode.SINGLE -> R.string.value_single; DriveMode.BURST -> R.string.value_burst; DriveMode.AEB -> R.string.value_aeb; DriveMode.TIMELAPSE -> R.string.value_timelapse }
@StringRes private fun VideoStabMode.labelRes() = when (this) { VideoStabMode.OFF -> R.string.value_off; VideoStabMode.STANDARD -> R.string.value_standard; VideoStabMode.ENHANCED -> R.string.value_active }
@StringRes private fun AudioScene.labelRes() = when (this) { AudioScene.STANDARD -> R.string.value_standard; AudioScene.SOUND_FOCUS -> R.string.value_sound_focus; AudioScene.SOUND_STAGE -> R.string.value_sound_stage }
@StringRes private fun AudioInputPreference.labelRes() = when (this) { AudioInputPreference.AUTO -> R.string.value_auto; AudioInputPreference.BUILT_IN -> R.string.value_phone; AudioInputPreference.WIRED -> R.string.value_wired; AudioInputPreference.USB -> R.string.value_usb; AudioInputPreference.BLUETOOTH -> R.string.value_bluetooth }
@StringRes private fun HardwareKeyAction.labelRes() = when (this) { HardwareKeyAction.SHUTTER -> R.string.value_shutter_rec; HardwareKeyAction.AF_ON -> R.string.value_af_on; HardwareKeyAction.AEL -> R.string.value_ael; HardwareKeyAction.PUNCH_IN -> R.string.label_loupe; HardwareKeyAction.ZOOM_IN -> R.string.value_zoom_in; HardwareKeyAction.ZOOM_OUT -> R.string.value_zoom_out; HardwareKeyAction.NONE -> R.string.value_none }
@StringRes private fun BitrateLevel.labelRes() = when (this) { BitrateLevel.LOW -> R.string.value_low; BitrateLevel.MEDIUM -> R.string.value_medium; BitrateLevel.HIGH -> R.string.value_high; BitrateLevel.ULTRA -> R.string.value_ultra; BitrateLevel.MAX -> R.string.value_max }
@StringRes private fun FnSlot.labelRes() = when (this) {
    FnSlot.EXPOSURE_MODE -> R.string.label_mode; FnSlot.FOCUS -> R.string.settings_tab_focus
    FnSlot.SHUTTER -> R.string.label_shutter; FnSlot.ISO -> R.string.label_iso
    FnSlot.WB -> R.string.label_wb; FnSlot.EV -> R.string.label_ev; FnSlot.ZOOM -> R.string.label_zoom
    FnSlot.STABILIZATION -> R.string.label_stabilization; FnSlot.DRIVE -> R.string.label_drive
    FnSlot.METERING -> R.string.label_meter; FnSlot.PEAKING -> R.string.label_peaking
    FnSlot.ZEBRA -> R.string.label_zebra; FnSlot.TRANSFER -> R.string.label_gamma
    FnSlot.AUDIO_SCENE -> R.string.label_directionality; FnSlot.GRID -> R.string.label_grid
    FnSlot.LEVEL -> R.string.label_level; FnSlot.PUNCH_IN -> R.string.label_loupe
    FnSlot.TELECONVERTER -> R.string.label_tele; FnSlot.OPEN_GATE -> R.string.label_open_gate
    FnSlot.FRAME_LINES -> R.string.label_frame; FnSlot.FLASH -> R.string.label_flash
    FnSlot.TIMER -> R.string.label_self_timer; FnSlot.ASPECT -> R.string.label_aspect
    FnSlot.AUDIO_INPUT -> R.string.label_mic_input
}

internal fun Context.localizedLabel(value: FocusMode) = getString(value.labelRes())
internal fun Context.localizedLabel(value: Antibanding) = getString(value.labelRes())
internal fun Context.localizedLabel(value: ProcessingLevel) = getString(value.labelRes())
internal fun Context.localizedLabel(value: ColorEffect) = getString(value.labelRes())
internal fun Context.localizedLabel(value: FlashMode) = getString(value.labelRes())
internal fun Context.localizedLabel(value: GridType) = getString(value.labelRes())
internal fun Context.localizedLabel(value: ShutterTimer) = getString(value.labelRes())
internal fun Context.localizedLabel(value: ShutterMode) = getString(value.labelRes())
internal fun Context.localizedLabel(value: WbMode) = getString(value.labelRes())
internal fun Context.localizedLabel(value: MeteringMode) = getString(value.labelRes())
internal fun Context.localizedLabel(value: DriveMode) = getString(value.labelRes())
internal fun Context.localizedLabel(value: VideoStabMode) = getString(value.labelRes())
internal fun Context.localizedLabel(value: AudioScene) = getString(value.labelRes())
internal fun Context.localizedLabel(value: AudioInputPreference) = getString(value.labelRes())
internal fun Context.localizedLabel(value: HardwareKeyAction) = getString(value.labelRes())
internal fun Context.localizedLabel(value: BitrateLevel) = getString(value.labelRes())
internal fun Context.localizedLabel(value: FnSlot) = getString(value.labelRes())
internal fun Context.localizedLabel(value: FrameLineType) = when (value) {
    FrameLineType.OFF -> getString(R.string.value_off)
    FrameLineType.CINEMA -> "2.39:1"
    FrameLineType.SQUARE -> "1:1"
    FrameLineType.VERTICAL -> "9:16"
}
internal fun Context.localizedLabel(value: AfSpotSize) = getString(when (value) {
    AfSpotSize.SMALL -> R.string.value_small; AfSpotSize.MEDIUM -> R.string.value_medium
    AfSpotSize.LARGE -> R.string.value_large
})
internal fun Context.localizedLabel(value: PeakingLevel) = getString(when (value) {
    PeakingLevel.LOW -> R.string.value_low; PeakingLevel.MEDIUM -> R.string.value_medium
    PeakingLevel.HIGH -> R.string.value_high
})
internal fun Context.localizedLabel(value: PeakingColor) = getString(when (value) {
    PeakingColor.RED -> R.string.value_red; PeakingColor.GREEN -> R.string.value_green
    PeakingColor.BLUE -> R.string.value_blue; PeakingColor.YELLOW -> R.string.value_yellow
    PeakingColor.MAGENTA -> R.string.value_magenta
})
