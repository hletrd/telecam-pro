package com.hletrd.findx9tele.ui.controls

import android.util.Range
import android.util.Rational
import android.util.Size
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hletrd.findx9tele.camera.Antibanding
import com.hletrd.findx9tele.camera.AspectRatio
import com.hletrd.findx9tele.camera.BitrateLevel
import com.hletrd.findx9tele.camera.CameraCaps
import com.hletrd.findx9tele.camera.CameraUiState
import com.hletrd.findx9tele.camera.ColorEffect
import com.hletrd.findx9tele.camera.ColorTransfer
import com.hletrd.findx9tele.camera.DriveMode
import com.hletrd.findx9tele.camera.EisStrength
import com.hletrd.findx9tele.camera.FlashMode
import com.hletrd.findx9tele.camera.FocusMode
import com.hletrd.findx9tele.camera.GridType
import com.hletrd.findx9tele.camera.ManualControls
import com.hletrd.findx9tele.camera.MeteringMode
import com.hletrd.findx9tele.camera.PhotoFormats
import com.hletrd.findx9tele.camera.ProcessingLevel
import com.hletrd.findx9tele.camera.ShutterMode
import com.hletrd.findx9tele.camera.ShutterTimer
import com.hletrd.findx9tele.camera.VideoCodec
import com.hletrd.findx9tele.camera.WbMode
import com.hletrd.findx9tele.camera.effectiveExposureNs
import com.hletrd.findx9tele.focus.FocusMapping
import com.hletrd.findx9tele.ui.CameraActions
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// ---------------------------------------------------------------------------
// Focus (slider lives here; called directly from CameraScreen)
// ---------------------------------------------------------------------------

/**
 * Manual-focus slider mapped through [FocusMapping]. Displays the resolved distance in
 * meters/centimeters, or "∞" at zero diopters. Disabled unless [focusMode] is MANUAL and the lens
 * has a manual-focus range (fixed-focus, [minFocusDiopters] <= 0, disables it).
 */
@Composable
fun FocusSlider(
    focusDistanceDiopters: Float,
    minFocusDiopters: Float,
    focusMode: FocusMode,
    onFocusSlider: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sliderPos = remember(focusDistanceDiopters, minFocusDiopters) {
        FocusMapping.dioptersToSlider(focusDistanceDiopters, minFocusDiopters)
    }
    val distanceLabel = remember(focusDistanceDiopters) { formatFocusDistance(focusDistanceDiopters) }
    val enabled = focusMode == FocusMode.MANUAL && minFocusDiopters > 0f
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("초점", color = Color.White, style = MaterialTheme.typography.labelMedium)
            Text(distanceLabel, color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
        Slider(
            value = sliderPos,
            onValueChange = onFocusSlider,
            enabled = enabled,
            valueRange = 0f..1f,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun formatFocusDistance(diopters: Float): String {
    val meters = FocusMapping.dioptersToMeters(diopters)
    return when {
        meters.isInfinite() -> "∞"
        meters < 1f -> "${(meters * 100).roundToInt()}cm"
        else -> "%.2fm".format(meters)
    }
}

// ---------------------------------------------------------------------------
// Small reusable building blocks shared by every section below
// ---------------------------------------------------------------------------

/** Section header used to group rows (Focus/Exposure/White balance/...). */
@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Color(0xFF8E8E93), style = MaterialTheme.typography.labelSmall, modifier = modifier)
}

/** Reusable Auto/Manual segmented toggle used by exposure and white-balance rows. */
@Composable
fun AutoManualToggle(
    auto: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    autoLabel: String = "자동",
    manualLabel: String = "수동",
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = auto, onClick = { onToggle(true) }, label = { Text(autoLabel) })
        FilterChip(selected = !auto, onClick = { onToggle(false) }, label = { Text(manualLabel) })
    }
}

/** Exclusive segmented selector (FilterChip row) for a fixed set of enum options. */
@Composable
private fun <T> SegmentedSelector(
    label: String,
    options: List<T>,
    selected: T,
    labelFor: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(labelFor(option)) },
                )
            }
        }
    }
}

/** Label + value header with a slider beneath it. */
@Composable
private fun LabeledSlider(
    label: String,
    valueLabel: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
            Text(valueLabel, color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Label + Switch row used by every boolean toggle in the panel. */
@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// ---------------------------------------------------------------------------
// Enum -> Korean short-label mappings
// ---------------------------------------------------------------------------

private fun focusModeLabel(mode: FocusMode): String = when (mode) {
    FocusMode.MANUAL -> "MF"
    FocusMode.AUTO -> "AF"
    FocusMode.CONTINUOUS -> "AF-C"
    FocusMode.MACRO -> "매크로"
}

private fun antibandingLabel(mode: Antibanding): String = when (mode) {
    Antibanding.AUTO -> "자동"
    Antibanding.HZ50 -> "50Hz"
    Antibanding.HZ60 -> "60Hz"
    Antibanding.OFF -> "끄기"
}

private fun processingLevelLabel(level: ProcessingLevel): String = when (level) {
    ProcessingLevel.OFF -> "끄기"
    ProcessingLevel.FAST -> "빠름"
    ProcessingLevel.HIGH_QUALITY -> "고품질"
}

private fun colorEffectLabel(effect: ColorEffect): String = when (effect) {
    ColorEffect.NONE -> "없음"
    ColorEffect.MONO -> "흑백"
    ColorEffect.NEGATIVE -> "네거티브"
    ColorEffect.SEPIA -> "세피아"
    ColorEffect.AQUA -> "아쿠아"
    ColorEffect.POSTERIZE -> "포스터라이즈"
}

private fun flashModeLabel(mode: FlashMode): String = when (mode) {
    FlashMode.OFF -> "끄기"
    FlashMode.AUTO -> "자동"
    FlashMode.ON -> "켜기"
    FlashMode.TORCH -> "손전등"
}

private fun gridTypeLabel(type: GridType): String = when (type) {
    GridType.NONE -> "없음"
    GridType.THIRDS -> "3분할"
    GridType.GOLDEN -> "황금비"
    GridType.SQUARE -> "정사각형"
    GridType.CENTER -> "중앙"
}

private fun shutterTimerLabel(timer: ShutterTimer): String = when (timer) {
    ShutterTimer.OFF -> "끄기"
    ShutterTimer.SEC3 -> "3초"
    ShutterTimer.SEC10 -> "10초"
}

private fun shutterModeLabel(mode: ShutterMode): String = when (mode) {
    ShutterMode.SPEED -> "속도"
    ShutterMode.ANGLE -> "각도"
}

private fun eisStrengthLabel(strength: EisStrength): String = when (strength) {
    EisStrength.LOW -> "약"
    EisStrength.MEDIUM -> "중"
    EisStrength.HIGH -> "강"
}

private fun wbModeLabel(mode: WbMode): String = when (mode) {
    WbMode.AUTO -> "자동"
    WbMode.INCANDESCENT -> "백열"
    WbMode.FLUORESCENT -> "형광"
    WbMode.DAYLIGHT -> "주광"
    WbMode.CLOUDY -> "흐림"
    WbMode.SHADE -> "그늘"
    WbMode.MANUAL -> "수동"
}

private fun meteringModeLabel(mode: MeteringMode): String = when (mode) {
    MeteringMode.MATRIX -> "평가"
    MeteringMode.CENTER -> "중앙"
    MeteringMode.SPOT -> "스팟"
}

private fun driveModeLabel(mode: DriveMode): String = when (mode) {
    DriveMode.SINGLE -> "단일"
    DriveMode.BURST -> "연사"
    DriveMode.AEB -> "AEB"
    DriveMode.TIMELAPSE -> "타임랩스"
}

private fun aspectRatioLabel(ratio: AspectRatio): String = when (ratio) {
    AspectRatio.FULL -> "전체"
    AspectRatio.W16_9 -> "16:9"
    AspectRatio.W4_3 -> "4:3"
    AspectRatio.W1_1 -> "1:1"
}

private fun videoCodecLabel(codec: VideoCodec): String = when (codec) {
    VideoCodec.HEVC -> "HEVC"
    VideoCodec.AVC -> "H.264"
}

private fun bitrateLevelLabel(level: BitrateLevel): String = when (level) {
    BitrateLevel.LOW -> "낮음"
    BitrateLevel.MEDIUM -> "보통"
    BitrateLevel.HIGH -> "높음"
}

/** "2160" -> "4K", "1080" -> "1080p", etc.; unrecognized heights fall back to "WxH". */
private fun videoResolutionLabel(size: Size): String = when (size.height) {
    4320 -> "8K"
    2160 -> "4K"
    1440 -> "1440p"
    1080 -> "1080p"
    else -> "${size.width}x${size.height}"
}

// ---------------------------------------------------------------------------
// Exposure: ISO / Shutter / EV helpers
// ---------------------------------------------------------------------------

private fun shutterNsToSlider(ns: Long, range: Range<Long>): Float {
    if (range.lower >= range.upper) return 0f
    val lo = ln(range.lower.toDouble())
    val hi = ln(range.upper.toDouble())
    val v = ln(ns.coerceIn(range.lower, range.upper).toDouble())
    return ((v - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)
}

private fun sliderToShutterNs(slider: Float, range: Range<Long>): Long {
    if (range.lower >= range.upper) return range.lower
    val lo = ln(range.lower.toDouble())
    val hi = ln(range.upper.toDouble())
    val v = exp(lo + slider.coerceIn(0f, 1f) * (hi - lo))
    return v.roundToLong().coerceIn(range.lower, range.upper)
}

private fun formatShutterSpeed(ns: Long): String {
    val seconds = ns / 1_000_000_000.0
    return if (seconds >= 1.0) {
        "%.1fs".format(seconds)
    } else {
        val denominator = (1.0 / seconds).roundToInt().coerceAtLeast(1)
        "1/${denominator}s"
    }
}

// ---------------------------------------------------------------------------
// Transfer / formats / audio (shared standalone rows)
// ---------------------------------------------------------------------------

/** HLG / LOG transfer-function selector. */
@Composable
fun TransferSelector(
    transfer: ColorTransfer,
    onTransfer: (ColorTransfer) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = transfer == ColorTransfer.HLG,
            onClick = { onTransfer(ColorTransfer.HLG) },
            label = { Text("HLG") },
        )
        FilterChip(
            selected = transfer == ColorTransfer.LOG,
            onClick = { onTransfer(ColorTransfer.LOG) },
            label = { Text("LOG") },
        )
    }
}

/** HEIF / DNG output-format toggles; both may be enabled simultaneously. */
@Composable
fun PhotoFormatToggles(
    formats: PhotoFormats,
    onSetPhotoFormats: (PhotoFormats) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = formats.heif,
            onClick = { onSetPhotoFormats(formats.copy(heif = !formats.heif)) },
            label = { Text("HEIF") },
        )
        FilterChip(
            selected = formats.dngRaw,
            onClick = { onSetPhotoFormats(formats.copy(dngRaw = !formats.dngRaw)) },
            label = { Text("DNG") },
        )
    }
}

// ---------------------------------------------------------------------------
// Sections
// ---------------------------------------------------------------------------

@Composable
private fun FocusSection(controls: ManualControls, actions: CameraActions, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("초점")
        SegmentedSelector(
            label = "모드",
            options = FocusMode.entries,
            selected = controls.focusMode,
            labelFor = ::focusModeLabel,
            onSelect = actions::onFocusMode,
        )
        if (controls.focusMode != FocusMode.MANUAL) {
            ToggleRow(label = "AF 잠금", checked = controls.afLock, onCheckedChange = actions::onAfLock)
        }
    }
}

@Composable
private fun ExposureSection(
    controls: ManualControls,
    caps: CameraCaps?,
    actions: CameraActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("노출")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("모드", color = Color.White, style = MaterialTheme.typography.labelMedium)
            AutoManualToggle(auto = controls.autoExposure, onToggle = actions::onToggleAutoExposure)
        }

        val isoRange = caps?.isoRange ?: Range(controls.iso, controls.iso)
        LabeledSlider(
            label = "ISO",
            valueLabel = controls.iso.toString(),
            value = controls.iso.toFloat().coerceIn(isoRange.lower.toFloat(), isoRange.upper.toFloat()),
            onValueChange = { actions.onIso(it.roundToInt()) },
            valueRange = isoRange.lower.toFloat()..isoRange.upper.toFloat(),
            enabled = !controls.autoExposure,
        )

        SegmentedSelector(
            label = "셔터 모드",
            options = ShutterMode.entries,
            selected = controls.shutterMode,
            labelFor = ::shutterModeLabel,
            onSelect = actions::onShutterMode,
        )

        if (controls.shutterMode == ShutterMode.ANGLE) {
            LabeledSlider(
                label = "셔터 각도",
                valueLabel = "%.0f° (%s)".format(controls.shutterAngle, formatShutterSpeed(controls.effectiveExposureNs())),
                value = controls.shutterAngle,
                onValueChange = actions::onShutterAngle,
                valueRange = 1f..360f,
                enabled = !controls.autoExposure,
            )
        } else {
            val shutterRange = caps?.exposureTimeRange ?: Range(controls.exposureTimeNs, controls.exposureTimeNs)
            val shutterSlider = remember(controls.exposureTimeNs, shutterRange) {
                shutterNsToSlider(controls.exposureTimeNs, shutterRange)
            }
            LabeledSlider(
                label = "셔터",
                valueLabel = formatShutterSpeed(controls.exposureTimeNs),
                value = shutterSlider,
                onValueChange = { actions.onShutterNs(sliderToShutterNs(it, shutterRange)) },
                valueRange = 0f..1f,
                enabled = !controls.autoExposure,
            )
        }

        val fpsOptions = caps?.availableFps?.takeIf { it.isNotEmpty() } ?: listOf(24, 30, 60)
        SegmentedSelector(
            label = "FPS",
            options = fpsOptions,
            selected = controls.fps,
            labelFor = { it.toString() },
            onSelect = actions::onFps,
        )

        val evRange = caps?.evRange ?: Range(0, 0)
        val evStep = caps?.evStep ?: Rational(1, 3)
        val stepValue = evStep.numerator.toFloat() / evStep.denominator.toFloat()
        val evLo = minOf(evRange.lower, evRange.upper).toFloat()
        val evHi = maxOf(evRange.lower, evRange.upper).toFloat()
        LabeledSlider(
            label = "EV",
            valueLabel = "%+.1f EV".format(controls.exposureCompensation * stepValue),
            value = controls.exposureCompensation.toFloat().coerceIn(evLo, evHi),
            onValueChange = { actions.onExposureCompensation(it.roundToInt()) },
            valueRange = evLo..evHi,
            enabled = controls.autoExposure,
        )

        ToggleRow(label = "AE 잠금", checked = controls.aeLock, onCheckedChange = actions::onToggleAeLock)

        SegmentedSelector(
            label = "안티밴딩",
            options = Antibanding.entries,
            selected = controls.antibanding,
            labelFor = ::antibandingLabel,
            onSelect = actions::onAntibanding,
        )

        SegmentedSelector(
            label = "측광",
            options = MeteringMode.entries,
            selected = controls.meteringMode,
            labelFor = ::meteringModeLabel,
            onSelect = actions::onMeteringMode,
        )
    }
}

@Composable
private fun WhiteBalanceSection(controls: ManualControls, actions: CameraActions, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("화이트밸런스")
        SegmentedSelector(
            label = "모드",
            options = WbMode.entries,
            selected = controls.wbMode,
            labelFor = ::wbModeLabel,
            onSelect = actions::onWbMode,
        )
        if (controls.wbMode == WbMode.MANUAL) {
            LabeledSlider(
                label = "색온도",
                valueLabel = "${controls.wbKelvin}K",
                value = controls.wbKelvin.toFloat().coerceIn(2000f, 10000f),
                onValueChange = { actions.onWbKelvin(it.roundToInt()) },
                valueRange = 2000f..10000f,
            )
            LabeledSlider(
                label = "틴트",
                valueLabel = "%+d".format(controls.wbTint),
                value = controls.wbTint.toFloat().coerceIn(-50f, 50f),
                onValueChange = { actions.onWbTint(it.roundToInt()) },
                valueRange = -50f..50f,
            )
            ToggleRow(label = "AWB 잠금", checked = controls.awbLock, onCheckedChange = actions::onToggleAwbLock)
        }
    }
}

@Composable
private fun ProcessingSection(controls: ManualControls, actions: CameraActions, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("이미지 처리")
        SegmentedSelector(
            label = "샤프니스",
            options = ProcessingLevel.entries,
            selected = controls.edge,
            labelFor = ::processingLevelLabel,
            onSelect = actions::onEdge,
        )
        SegmentedSelector(
            label = "노이즈 감소",
            options = ProcessingLevel.entries,
            selected = controls.noiseReduction,
            labelFor = ::processingLevelLabel,
            onSelect = actions::onNoiseReduction,
        )
        SegmentedSelector(
            label = "색상 효과",
            options = ColorEffect.entries,
            selected = controls.colorEffect,
            labelFor = ::colorEffectLabel,
            onSelect = actions::onColorEffect,
        )
    }
}

@Composable
private fun OpticsOutputSection(
    controls: ManualControls,
    caps: CameraCaps?,
    actions: CameraActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("광학/출력")
        SegmentedSelector(
            label = "플래시",
            options = FlashMode.entries,
            selected = controls.flash,
            labelFor = ::flashModeLabel,
            onSelect = actions::onFlash,
        )
        if (caps?.oisAvailable == true) {
            ToggleRow(label = "손떨림보정(OIS)", checked = controls.oisEnabled, onCheckedChange = actions::onToggleOis)
        }
        caps?.zoomRatioRange?.let { range ->
            LabeledSlider(
                label = "줌",
                valueLabel = "%.1fx".format(controls.zoomRatio),
                value = controls.zoomRatio.coerceIn(range.lower, range.upper),
                onValueChange = actions::onZoomRatio,
                valueRange = range.lower..range.upper,
            )
        }
        LabeledSlider(
            label = "JPEG 품질",
            valueLabel = controls.jpegQuality.toString(),
            value = controls.jpegQuality.toFloat().coerceIn(1f, 100f),
            onValueChange = { actions.onJpegQuality(it.roundToInt()) },
            valueRange = 1f..100f,
        )
    }
}

@Composable
private fun ModeSection(state: CameraUiState, actions: CameraActions, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("모드")
        ToggleRow(
            label = "텔레컨버터(300mm)",
            checked = state.teleconverterMode,
            onCheckedChange = actions::onToggleTeleconverter,
        )
        ToggleRow(label = "손떨림보정(EIS)", checked = state.eisEnabled, onCheckedChange = actions::onToggleEis)
        SegmentedSelector(
            label = "EIS 강도",
            options = EisStrength.entries,
            selected = state.eisStrength,
            labelFor = ::eisStrengthLabel,
            onSelect = actions::onEisStrength,
        )
        TransferSelector(transfer = state.transfer, onTransfer = actions::onTransfer)
        PhotoFormatToggles(formats = state.photoFormats, onSetPhotoFormats = actions::onSetPhotoFormats)
        SegmentedSelector(
            label = "화면비",
            options = AspectRatio.entries,
            selected = state.aspectRatio,
            labelFor = ::aspectRatioLabel,
            onSelect = actions::onAspectRatio,
        )
        ToggleRow(label = "오디오 녹음", checked = state.recordAudio, onCheckedChange = actions::onToggleRecordAudio)
        LabeledSlider(
            label = "게인",
            valueLabel = "%.1fx".format(state.audioGain),
            value = state.audioGain,
            onValueChange = actions::onAudioGain,
            valueRange = 0f..2f,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("카메라 오버라이드", color = Color.White, style = MaterialTheme.typography.labelMedium)
            Text(
                text = state.cameraOverrideId ?: "기본",
                color = Color(0xFF4C9AFF),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clickable(enabled = state.cameraOverrideId != null) {
                    actions.onCameraOverride(null)
                },
            )
        }
    }
}

@Composable
private fun AssistsSection(state: CameraUiState, actions: CameraActions, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("어시스트")
        ToggleRow(label = "포커스 피킹", checked = state.focusPeaking, onCheckedChange = actions::onTogglePeaking)
        ToggleRow(label = "제브라", checked = state.zebra, onCheckedChange = actions::onToggleZebra)
        ToggleRow(label = "폴스컬러", checked = state.falseColor, onCheckedChange = actions::onToggleFalseColor)
        ToggleRow(label = "히스토그램", checked = state.histogram, onCheckedChange = actions::onToggleHistogram)
        ToggleRow(label = "웨이브폼", checked = state.waveform, onCheckedChange = actions::onToggleWaveform)
        SegmentedSelector(
            label = "그리드",
            options = GridType.entries,
            selected = state.grid,
            labelFor = ::gridTypeLabel,
            onSelect = actions::onGridType,
        )
        ToggleRow(label = "수평계", checked = state.level, onCheckedChange = actions::onToggleLevel)
        ToggleRow(label = "펀치인", checked = state.punchIn, onCheckedChange = actions::onTogglePunchIn)
    }
}

@Composable
private fun DriveSection(state: CameraUiState, actions: CameraActions, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("드라이브")
        SegmentedSelector(
            label = "드라이브 모드",
            options = DriveMode.entries,
            selected = state.driveMode,
            labelFor = ::driveModeLabel,
            onSelect = actions::onDriveMode,
        )
        if (state.driveMode == DriveMode.TIMELAPSE) {
            LabeledSlider(
                label = "간격",
                valueLabel = "${state.intervalSec}초 간격",
                value = state.intervalSec.toFloat().coerceIn(1f, 30f),
                onValueChange = { actions.onIntervalSec(it.roundToInt()) },
                valueRange = 1f..30f,
            )
        }
        SegmentedSelector(
            label = "셀프타이머",
            options = ShutterTimer.entries,
            selected = state.timer,
            labelFor = ::shutterTimerLabel,
            onSelect = actions::onTimer,
        )
    }
}

@Composable
private fun VideoSection(state: CameraUiState, actions: CameraActions, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("동영상")
        SegmentedSelector(
            label = "코덱",
            options = VideoCodec.entries,
            selected = state.videoCodec,
            labelFor = ::videoCodecLabel,
            onSelect = actions::onVideoCodec,
        )
        val resolutionOptions = state.caps?.availableVideoSizes?.takeIf { it.isNotEmpty() }
            ?: listOf(Size(3840, 2160), Size(1920, 1080))
        SegmentedSelector(
            label = "해상도",
            options = resolutionOptions,
            selected = state.videoResolution,
            labelFor = ::videoResolutionLabel,
            onSelect = actions::onVideoResolution,
        )
        SegmentedSelector(
            label = "비트레이트",
            options = BitrateLevel.entries,
            selected = state.bitrateLevel,
            labelFor = ::bitrateLevelLabel,
            onSelect = actions::onBitrateLevel,
        )
    }
}

// ---------------------------------------------------------------------------
// Collapsible pro panel (composition root for every section above)
// ---------------------------------------------------------------------------

/**
 * Collapsible pro-control panel composed of the sections above. Purely presentational: every
 * value is read from [state] and every interaction is forwarded straight to the matching
 * [CameraActions] method. Scrolls vertically since it holds many rows.
 */
@Composable
fun ProPanel(
    expanded: Boolean,
    state: CameraUiState,
    actions: CameraActions,
    modifier: Modifier = Modifier,
) {
    if (!expanded) return
    val controls = state.controls
    val caps = state.caps
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        FocusSection(controls = controls, actions = actions)
        ExposureSection(controls = controls, caps = caps, actions = actions)
        WhiteBalanceSection(controls = controls, actions = actions)
        ProcessingSection(controls = controls, actions = actions)
        OpticsOutputSection(controls = controls, caps = caps, actions = actions)
        ModeSection(state = state, actions = actions)
        VideoSection(state = state, actions = actions)
        AssistsSection(state = state, actions = actions)
        DriveSection(state = state, actions = actions)
    }
}
