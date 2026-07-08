package com.hletrd.findx9tele.ui.controls

import android.content.Context
import android.content.Intent
import android.util.Range
import android.util.Size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.hletrd.findx9tele.camera.Antibanding
import com.hletrd.findx9tele.camera.ExposureStep
import com.hletrd.findx9tele.camera.AspectRatio
import com.hletrd.findx9tele.camera.AudioScene
import com.hletrd.findx9tele.camera.BitrateLevel
import com.hletrd.findx9tele.camera.CameraUiState
import com.hletrd.findx9tele.camera.ColorEffect
import com.hletrd.findx9tele.camera.DriveMode
import com.hletrd.findx9tele.camera.EisStrength
import com.hletrd.findx9tele.camera.PeakingColor
import com.hletrd.findx9tele.camera.PeakingLevel
import com.hletrd.findx9tele.camera.ZebraLevel
import com.hletrd.findx9tele.camera.FocusMode
import com.hletrd.findx9tele.camera.GridType
import com.hletrd.findx9tele.camera.LensChoice
import com.hletrd.findx9tele.camera.MeteringMode
import com.hletrd.findx9tele.camera.ProcessingLevel
import com.hletrd.findx9tele.camera.ShutterMode
import com.hletrd.findx9tele.camera.ShutterTimer
import com.hletrd.findx9tele.camera.VendorLogMode
import com.hletrd.findx9tele.camera.VideoCodec
import com.hletrd.findx9tele.camera.VideoStabMode
import com.hletrd.findx9tele.camera.VideoFrameRate
import com.hletrd.findx9tele.camera.WbMode
import com.hletrd.findx9tele.camera.videoBitRate
import com.hletrd.findx9tele.video.EncoderCaps
import com.hletrd.findx9tele.ui.CameraActions
import com.hletrd.findx9tele.ui.theme.CameraColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The Sony-menu-style structured settings system opened by the top-bar gear button.
 *
 * The on-screen ruler dials and top-bar quick toggles are the "Fn" layer — fast access to the
 * handful of controls changed shot-to-shot. Everything else lives here, organized into fixed
 * category tabs on a left rail (mirroring Sony's own camera menu), rather than one long scroll.
 * Every row is a thin wrapper around a [CameraActions] method; this file owns no camera state.
 */
internal enum class ProSheetTab(val label: String) {
    SHOOTING("Shooting"),
    EXPOSURE("Exposure/Color"),
    FOCUS("Focus"),
    STABILIZATION("Stabilization"),
    VIDEO("Video"),
    PROCESSING("Processing"),
    ASSISTS("Assists"),
    ADVANCED("Advanced"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProSheet(
    state: CameraUiState,
    actions: CameraActions,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialTab: ProSheetTab = ProSheetTab.SHOOTING,
    onTabChange: (ProSheetTab) -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(initialTab) }
    val dismiss: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) onDismiss() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
        containerColor = CameraColors.Pill,
        contentColor = CameraColors.TextPrimary,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(modifier = Modifier.padding(top = 10.dp).width(36.dp).height(4.dp).background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(2.dp)))
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Settings", color = CameraColors.TextPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                CloseButton(onClick = dismiss)
            }

            Row(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.82f)) {
                TabRail(selected = selectedTab, onSelect = { selectedTab = it; onTabChange(it) })
                Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.08f)))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    when (selectedTab) {
                        ProSheetTab.SHOOTING -> ShootingTab(state, actions)
                        ProSheetTab.EXPOSURE -> ExposureColorTab(state, actions)
                        ProSheetTab.FOCUS -> FocusTab(state, actions)
                        ProSheetTab.STABILIZATION -> StabilizationTab(state, actions)
                        ProSheetTab.VIDEO -> VideoTab(state, actions)
                        ProSheetTab.PROCESSING -> ProcessingTab(state, actions)
                        ProSheetTab.ASSISTS -> AssistsTab(state, actions)
                        ProSheetTab.ADVANCED -> AdvancedTab(state, actions)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun CloseButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    // 44 dp touch target; 32 dp visual pill.
    Box(
        modifier = modifier
            .size(44.dp)
            .semantics {
                contentDescription = "Close settings"
                role = Role.Button
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(12.dp)) {
                val c = CameraColors.TextPrimary
                drawLine(c, Offset(0f, 0f), Offset(size.width, size.height), strokeWidth = 1.6.dp.toPx())
                drawLine(c, Offset(size.width, 0f), Offset(0f, size.height), strokeWidth = 1.6.dp.toPx())
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Left tab rail — Sony-menu-style category icons
// ---------------------------------------------------------------------------

@Composable
private fun TabRail(selected: ProSheetTab, onSelect: (ProSheetTab) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .width(76.dp)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
    ) {
        ProSheetTab.entries.forEach { tab ->
            TabRailItem(tab = tab, selected = tab == selected, onClick = { onSelect(tab) })
        }
    }
}

@Composable
private fun TabRailItem(tab: ProSheetTab, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val fg = if (selected) CameraColors.TextPrimary else CameraColors.TextSecondary
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(if (selected) Color.White.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .width(20.dp)
                .height(2.dp)
                .background(if (selected) CameraColors.Accent else Color.Transparent, RoundedCornerShape(1.dp)),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Canvas(modifier = Modifier.size(20.dp)) { drawTabIcon(tab, fg) }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = tab.label,
            color = fg,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            textAlign = TextAlign.Center,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.width(68.dp),
        )
    }
}

/** Minimal abstract glyphs per settings category. No icon library — plain Canvas primitives. */
private fun DrawScope.drawTabIcon(tab: ProSheetTab, color: Color) {
    val stroke = Stroke(width = 1.6.dp.toPx())
    when (tab) {
        ProSheetTab.SHOOTING -> {
            drawRoundRect(color, topLeft = Offset(size.width * 0.05f, size.height * 0.3f), size = androidx.compose.ui.geometry.Size(size.width * 0.9f, size.height * 0.55f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style = stroke)
            drawRect(color, topLeft = Offset(size.width * 0.35f, size.height * 0.14f), size = androidx.compose.ui.geometry.Size(size.width * 0.3f, size.height * 0.18f), style = stroke)
            drawCircle(color, radius = size.minDimension * 0.16f, center = Offset(size.width / 2f, size.height * 0.58f), style = stroke)
        }
        ProSheetTab.EXPOSURE -> {
            drawCircle(color, radius = size.minDimension * 0.22f, center = center, style = stroke)
            val r1 = size.minDimension * 0.3f
            val r2 = size.minDimension * 0.46f
            for (i in 0 until 8) {
                val angle = (Math.PI * 2 * i / 8).toFloat()
                val dx = kotlin.math.cos(angle)
                val dy = kotlin.math.sin(angle)
                drawLine(color, Offset(center.x + dx * r1, center.y + dy * r1), Offset(center.x + dx * r2, center.y + dy * r2), strokeWidth = 1.4.dp.toPx())
            }
        }
        ProSheetTab.FOCUS -> {
            drawCircle(color, radius = size.minDimension * 0.4f, center = center, style = stroke)
            drawCircle(color, radius = size.minDimension * 0.08f, center = center)
            drawLine(color, Offset(center.x, 0f), Offset(center.x, size.height * 0.14f), strokeWidth = 1.4.dp.toPx())
            drawLine(color, Offset(center.x, size.height * 0.86f), Offset(center.x, size.height), strokeWidth = 1.4.dp.toPx())
            drawLine(color, Offset(0f, center.y), Offset(size.width * 0.14f, center.y), strokeWidth = 1.4.dp.toPx())
            drawLine(color, Offset(size.width * 0.86f, center.y), Offset(size.width, center.y), strokeWidth = 1.4.dp.toPx())
        }
        ProSheetTab.STABILIZATION -> {
            drawCircle(color, radius = size.minDimension * 0.16f, center = center, style = stroke)
            drawCircle(color, radius = size.minDimension * 0.32f, center = center, style = Stroke(width = 1.2.dp.toPx()))
            drawCircle(color, radius = size.minDimension * 0.46f, center = center, style = Stroke(width = 1.dp.toPx()))
        }
        ProSheetTab.VIDEO -> {
            drawRoundRect(color, topLeft = Offset(size.width * 0.08f, size.height * 0.18f), size = androidx.compose.ui.geometry.Size(size.width * 0.84f, size.height * 0.64f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style = stroke)
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(size.width * 0.4f, size.height * 0.36f)
                lineTo(size.width * 0.4f, size.height * 0.64f)
                lineTo(size.width * 0.64f, size.height * 0.5f)
                close()
            }
            drawPath(path, color)
        }
        ProSheetTab.PROCESSING -> {
            val xs = listOf(0.28f, 0.5f, 0.72f)
            val knobY = listOf(0.35f, 0.62f, 0.45f)
            xs.forEachIndexed { i, xf ->
                val x = size.width * xf
                drawLine(color, Offset(x, size.height * 0.12f), Offset(x, size.height * 0.88f), strokeWidth = 1.2.dp.toPx())
                drawCircle(color, radius = size.minDimension * 0.09f, center = Offset(x, size.height * knobY[i]))
            }
        }
        ProSheetTab.ASSISTS -> {
            val inset = size.width * 0.12f
            val mid = size.width / 2f
            val midY = size.height / 2f
            drawRect(color, topLeft = Offset(inset, inset), size = androidx.compose.ui.geometry.Size(mid - inset - 1.dp.toPx(), midY - inset - 1.dp.toPx()), style = stroke)
            drawRect(color, topLeft = Offset(mid + 1.dp.toPx(), inset), size = androidx.compose.ui.geometry.Size(size.width - inset - mid - 1.dp.toPx(), midY - inset - 1.dp.toPx()), style = stroke)
            drawRect(color, topLeft = Offset(inset, midY + 1.dp.toPx()), size = androidx.compose.ui.geometry.Size(mid - inset - 1.dp.toPx(), size.height - inset - midY - 1.dp.toPx()), style = stroke)
            drawRect(color, topLeft = Offset(mid + 1.dp.toPx(), midY + 1.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width - inset - mid - 1.dp.toPx(), size.height - inset - midY - 1.dp.toPx()), style = stroke)
        }
        ProSheetTab.ADVANCED -> {
            drawCircle(color, radius = size.minDimension * 0.4f, center = Offset(size.width * 0.38f, size.height * 0.38f), style = stroke)
            drawCircle(color, radius = size.minDimension * 0.16f, center = Offset(size.width * 0.38f, size.height * 0.38f))
            drawLine(color, Offset(size.width * 0.58f, size.height * 0.58f), Offset(size.width * 0.92f, size.height * 0.92f), strokeWidth = 2.2.dp.toPx())
        }
    }
}

// ---------------------------------------------------------------------------
// Tab pages
// ---------------------------------------------------------------------------

@Composable
private fun TabTitle(text: String) {
    Text(text, color = CameraColors.TextPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun ShootingTab(state: CameraUiState, actions: CameraActions) {
    val caps = state.caps
    TabTitle("Shooting")
    PhotoFormatToggles(formats = state.photoFormats, onSetPhotoFormats = actions::onSetPhotoFormats)
    SegmentedSelector(
        label = "Aspect Ratio",
        options = AspectRatio.entries,
        selected = state.aspectRatio,
        labelFor = ::aspectRatioLabel,
        onSelect = actions::onAspectRatio,
    )
    caps?.zoomRatioRange?.let { range ->
        LabeledSlider(
            label = "Zoom",
            valueLabel = "%.1fx".format(state.controls.zoomRatio),
            value = state.controls.zoomRatio.coerceIn(range.lower, range.upper),
            onValueChange = actions::onZoomRatio,
            valueRange = range.lower..range.upper,
        )
    }
    LabeledSlider(
        label = "JPEG Quality",
        valueLabel = state.controls.jpegQuality.toString(),
        value = state.controls.jpegQuality.toFloat().coerceIn(1f, 100f),
        onValueChange = { actions.onJpegQuality(it.roundToInt()) },
        valueRange = 1f..100f,
    )
    SectionHeader("Drive")
    SegmentedSelector(
        label = "Drive Mode",
        options = DriveMode.entries,
        selected = state.driveMode,
        labelFor = ::driveModeLabel,
        onSelect = actions::onDriveMode,
    )
    if (state.driveMode == DriveMode.TIMELAPSE) {
        LabeledSlider(
            label = "Interval",
            valueLabel = "${state.intervalSec}s",
            value = state.intervalSec.toFloat().coerceIn(1f, 30f),
            onValueChange = { actions.onIntervalSec(it.roundToInt()) },
            valueRange = 1f..30f,
        )
    }
    SegmentedSelector(
        label = "Self-Timer",
        options = ShutterTimer.entries,
        selected = state.timer,
        labelFor = ::shutterTimerLabel,
        onSelect = actions::onTimer,
    )
}

@Composable
private fun ExposureColorTab(state: CameraUiState, actions: CameraActions) {
    val controls = state.controls
    val caps = state.caps
    TabTitle("Exposure/Color")
    SectionHeader("Exposure")
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("Mode", color = CameraColors.TextPrimary, style = MaterialTheme.typography.labelMedium)
        AutoManualToggle(auto = controls.autoExposure, onToggle = actions::onToggleAutoExposure)
    }
    ToggleRow(label = "AE Lock", checked = controls.aeLock, onCheckedChange = actions::onToggleAeLock)
    SegmentedSelector(
        label = "Anti-Flicker",
        options = Antibanding.entries,
        selected = controls.antibanding,
        labelFor = ::antibandingLabel,
        onSelect = actions::onAntibanding,
    )
    SegmentedSelector(
        label = "Shutter Mode",
        options = ShutterMode.entries,
        selected = controls.shutterMode,
        labelFor = ::shutterModeLabel,
        onSelect = actions::onShutterMode,
    )
    SegmentedSelector(
        label = "Exposure Step",
        options = ExposureStep.entries,
        selected = controls.exposureStep,
        labelFor = { "${it.label} EV" },
        onSelect = actions::onExposureStep,
    )
    val isoRange = caps?.isoRange ?: Range(controls.iso, controls.iso)
    LabeledSlider(
        label = "ISO",
        valueLabel = controls.iso.toString(),
        value = controls.iso.toFloat().coerceIn(isoRange.lower.toFloat(), isoRange.upper.toFloat()),
        onValueChange = { actions.onIso(it.roundToInt()) },
        valueRange = isoRange.lower.toFloat()..isoRange.upper.toFloat(),
        enabled = !controls.autoExposure,
    )

    SectionHeader("Metering")
    SegmentedSelector(
        label = "Metering",
        options = MeteringMode.entries,
        selected = controls.meteringMode,
        labelFor = ::meteringModeLabel,
        onSelect = actions::onMeteringMode,
    )

    SectionHeader("White Balance")
    SegmentedSelector(
        label = "Preset",
        options = WbMode.entries,
        selected = controls.wbMode,
        labelFor = ::wbModeLabel,
        onSelect = actions::onWbMode,
    )
    if (controls.wbMode == WbMode.MANUAL) {
        LabeledSlider(
            label = "Color Temp",
            valueLabel = "${controls.wbKelvin}K",
            value = controls.wbKelvin.toFloat().coerceIn(2000f, 10000f),
            onValueChange = { actions.onWbKelvin(it.roundToInt()) },
            valueRange = 2000f..10000f,
        )
        LabeledSlider(
            label = "Tint",
            valueLabel = "%+d".format(controls.wbTint),
            value = controls.wbTint.toFloat().coerceIn(-50f, 50f),
            onValueChange = { actions.onWbTint(it.roundToInt()) },
            valueRange = -50f..50f,
        )
    }
    ToggleRow(label = "AWB Lock", checked = controls.awbLock, onCheckedChange = actions::onToggleAwbLock)
}

@Composable
private fun FocusTab(state: CameraUiState, actions: CameraActions) {
    val controls = state.controls
    TabTitle("Focus")
    SegmentedSelector(
        label = "AF Mode",
        options = FocusMode.entries,
        selected = controls.focusMode,
        labelFor = ::focusModeLabel,
        onSelect = actions::onFocusMode,
    )
    if (controls.focusMode != FocusMode.MANUAL) {
        ToggleRow(label = "AF Lock", checked = controls.afLock, onCheckedChange = actions::onAfLock)
    }
    ToggleRow(label = "Focus Peaking", checked = state.focusPeaking, onCheckedChange = actions::onTogglePeaking)
    SegmentedSelector(
        label = "Peaking Sensitivity",
        options = PeakingLevel.entries,
        selected = state.peakingLevel,
        labelFor = { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } },
        onSelect = actions::onPeakingLevel,
        enabled = state.focusPeaking,
    )
    SegmentedSelector(
        label = "Peaking Color",
        options = PeakingColor.entries,
        selected = state.peakingColor,
        labelFor = { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } },
        onSelect = actions::onPeakingColor,
        enabled = state.focusPeaking,
    )
}

@Composable
private fun StabilizationTab(state: CameraUiState, actions: CameraActions) {
    val caps = state.caps
    TabTitle("Lens")
    // Picking a lens bundles teleconverter mode: 3× turns it ON (afocal 180° flip + gyro-EIS scaled
    // to the ~300 mm effective focal), every other lens turns it OFF — one tap.
    SegmentedSelector(
        label = "Lens (bundles teleconverter on 3×)",
        options = LensChoice.entries,
        selected = state.lens,
        labelFor = ::lensLabel,
        onSelect = actions::onLens,
    )
    val focalCaption = when (state.lens) {
        LensChoice.ULTRAWIDE -> "≈ 14 mm ultra-wide"
        LensChoice.MAIN -> "≈ 23 mm main"
        LensChoice.TELE3X -> "70 mm → 300 mm with the teleconverter (EIS ×4.3, 180° flip)"
        LensChoice.TELE10X -> "≈ 230 mm periscope"
    }
    Text(focalCaption, color = CameraColors.TextSecondary, style = MaterialTheme.typography.labelSmall)

    SectionHeader("Video Stabilization")
    // The stock camera's approach: engage the HAL's OIS+EIS ("super steady") so OIS physically cuts
    // per-frame motion blur at 300 mm. App-side gyro EIS only warps whole frames — it steadies
    // jitter but can't de-blur a fixed 1/60 s frame. Modes gated by what the lens reports.
    SegmentedSelector(
        label = "Video Stabilization",
        options = VideoStabMode.entries,
        selected = state.videoStabMode,
        labelFor = { it.label },
        onSelect = actions::onVideoStabMode,
    )
    val stabCaption = when (state.videoStabMode) {
        VideoStabMode.OFF -> "No stabilization."
        VideoStabMode.GYRO -> "App gyro EIS scaled to the effective focal — steadies frame jitter, " +
            "but does NOT reduce per-frame motion blur (only OIS can)."
        VideoStabMode.STANDARD -> "HAL OIS+EIS. OIS moves the lens during exposure → less motion blur at 300 mm."
        VideoStabMode.ENHANCED -> "HAL preview-stabilization (the stock 'super steady' path) — strongest " +
            "OIS+EIS; best motion-blur reduction on the tele. Crops the frame slightly."
    }
    Text(stabCaption, color = CameraColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
    if (state.videoStabMode == VideoStabMode.GYRO) {
        SegmentedSelector(
            label = "EIS Strength",
            options = EisStrength.entries,
            selected = state.eisStrength,
            labelFor = ::eisStrengthLabel,
            onSelect = actions::onEisStrength,
        )
    }
    if (caps?.oisAvailable == true) {
        ToggleRow(label = "Optical Stabilization (OIS)", checked = state.controls.oisEnabled, onCheckedChange = actions::onToggleOis)
        Text(
            "Keep OIS on for the tele: it de-blurs each frame. Note a still capture bypasses EIS, so " +
                "a fast shutter (≈1/320 s+) plus OIS gives the sharpest handheld 300 mm photos.",
            color = CameraColors.TextSecondary,
            style = MaterialTheme.typography.labelSmall,
        )
    }
    ToggleRow(
        label = "Teleconverter (300mm, 180° flip)",
        checked = state.teleconverterMode,
        onCheckedChange = actions::onToggleTeleconverter,
    )
}

@Composable
private fun VideoTab(state: CameraUiState, actions: CameraActions) {
    val caps = state.caps
    val codec = state.videoCodec
    TabTitle("Video")

    // Codecs are limited to what MediaCodecList actually advertises an encoder for (HEVC/AVC are HW;
    // AV1 is software-only on this SoC, flagged in its chip label).
    val codecOptions = remember { EncoderCaps.availableCodecs().ifEmpty { listOf(VideoCodec.HEVC, VideoCodec.AVC) } }
    SegmentedSelector(
        label = "Codec",
        options = codecOptions,
        selected = codec,
        labelFor = ::videoCodecLabel,
        onSelect = actions::onVideoCodec,
    )
    if (codec == VideoCodec.AV1) {
        Text(
            "AV1 uses the software encoder on this device — slow, ≤1080p / ≤30fps. Use HEVC for 4K/high-fps.",
            color = CameraColors.TextSecondary,
            style = MaterialTheme.typography.labelSmall,
        )
    }

    // Open Gate records the full 4:3 sensor readout instead of a 16:9 crop; it swaps the resolution
    // list to the camera's 4:3 sizes.
    ToggleRow(label = "Open Gate (4:3 full sensor)", checked = state.openGate, onCheckedChange = actions::onToggleOpenGate)

    // Resolutions come from the SELECTED camera's real StreamConfigurationMap (4:3 when Open Gate,
    // else 16:9). AV1 (SW) is clamped to ≤1080p.
    val allSizes = when {
        caps == null -> listOf(Size(3840, 2160), Size(1920, 1080))
        state.openGate -> caps.openGateVideoSizes
        else -> caps.availableVideoSizes
    }.ifEmpty { listOf(Size(3840, 2160), Size(1920, 1080)) }
    val resolutionOptions = if (codec == VideoCodec.AV1) allSizes.filter { it.width <= 1920 }.ifEmpty { listOf(Size(1920, 1080)) } else allSizes
    SegmentedSelector(
        label = "Resolution",
        options = resolutionOptions,
        selected = state.videoResolution,
        labelFor = ::videoResolutionLabel,
        onSelect = actions::onVideoResolution,
    )

    // Frame rates gated per-resolution by real caps: normal rates need the camera to advertise the
    // integer fps (24/30/60 here); 120 needs a matching high-speed config; drop-frame variants
    // (23.976/29.97/59.94) ride their integer parent. 8K is capped ≤30.
    val fpsOptions = VideoFrameRate.availableFor(caps, state.videoResolution, codec)
    SegmentedSelector(
        label = "Frame Rate",
        options = fpsOptions,
        selected = state.videoFrameRate,
        labelFor = ::videoFrameRateLabel,
        onSelect = actions::onVideoFrameRate,
    )
    if (state.videoFrameRate.highSpeed) {
        Text(
            "High-speed capture (slow-motion) — records via a constrained high-speed session; still capture is off while selected.",
            color = CameraColors.TextSecondary,
            style = MaterialTheme.typography.labelSmall,
        )
    }

    SegmentedSelector(
        label = "Bitrate",
        options = BitrateLevel.entries,
        selected = state.bitrateLevel,
        labelFor = ::bitrateLevelLabel,
        onSelect = actions::onBitrateLevel,
    )
    // Resolved encoder settings summary, e.g. "HEVC · 4K · 30 · 84 Mbps" — the exact computed bitrate.
    val mbps = videoBitRate(
        state.videoResolution.width, state.videoResolution.height,
        state.videoFrameRate.encoderRate,
        com.hletrd.findx9tele.camera.effectiveBpp(state.bitrateLevel, codec), codec,
    ) / 1_000_000
    LabelValueRow(
        label = "Encoder",
        valueLabel = "${videoCodecLabelShort(codec)} · ${videoResolutionLabel(state.videoResolution)} · ${state.videoFrameRate.label} · $mbps Mbps",
    )

    ToggleRow(label = "Record Audio", checked = state.recordAudio, onCheckedChange = actions::onToggleRecordAudio)
    // Directional audio — the stock Sound Focus (aims the mic array at the framed subject, tightens
    // with zoom — the 300 mm use case) / Sound Stage (wider spatial stereo), via the vendor audio-HAL.
    SegmentedSelector(
        label = "Audio Scene",
        options = AudioScene.entries,
        selected = state.audioScene,
        labelFor = { it.label },
        onSelect = actions::onAudioScene,
        enabled = state.recordAudio,
    )
    if (state.audioScene == AudioScene.SOUND_FOCUS) {
        Text(
            "Aims the mic toward the subject and narrows with zoom (best for distant 300 mm subjects).",
            color = CameraColors.TextSecondary,
            style = MaterialTheme.typography.labelSmall,
        )
    }
    LabeledSlider(
        label = "Gain",
        valueLabel = "%.1fx".format(state.audioGain),
        value = state.audioGain,
        onValueChange = actions::onAudioGain,
        valueRange = 0f..2f,
        enabled = state.recordAudio,
    )
    // Transfer (HLG/LOG/SDR) only drives the HEVC path; AVC/AV1 always record 8-bit SDR, so the
    // selector is disabled there rather than pretending the choice applies.
    TransferSelector(
        transfer = state.transfer,
        onTransfer = actions::onTransfer,
        enabled = codec == VideoCodec.HEVC,
    )
}

@Composable
private fun ProcessingTab(state: CameraUiState, actions: CameraActions) {
    val controls = state.controls
    TabTitle("Processing")
    SegmentedSelector(
        label = "Sharpness",
        options = ProcessingLevel.entries,
        selected = controls.edge,
        labelFor = ::processingLevelLabel,
        onSelect = actions::onEdge,
    )
    SegmentedSelector(
        label = "Noise Reduction",
        options = ProcessingLevel.entries,
        selected = controls.noiseReduction,
        labelFor = ::processingLevelLabel,
        onSelect = actions::onNoiseReduction,
    )
    SegmentedSelector(
        label = "Color Effect",
        options = ColorEffect.entries,
        selected = controls.colorEffect,
        labelFor = ::colorEffectLabel,
        onSelect = actions::onColorEffect,
    )
}

@Composable
private fun AssistsTab(state: CameraUiState, actions: CameraActions) {
    TabTitle("Assists")
    ToggleRow(label = "Zebra", checked = state.zebra, onCheckedChange = actions::onToggleZebra)
    SegmentedSelector(
        label = "Zebra Level",
        options = ZebraLevel.entries,
        selected = state.zebraLevel,
        labelFor = {
            when (it) {
                ZebraLevel.IRE70 -> "70%"
                ZebraLevel.IRE85 -> "85%"
                ZebraLevel.IRE95 -> "95%"
                ZebraLevel.CLIP100 -> "100%"
            }
        },
        onSelect = actions::onZebraLevel,
        enabled = state.zebra,
    )
    ToggleRow(label = "False Color", checked = state.falseColor, onCheckedChange = actions::onToggleFalseColor)
    ToggleRow(label = "Histogram", checked = state.histogram, onCheckedChange = actions::onToggleHistogram)
    ToggleRow(label = "Waveform", checked = state.waveform, onCheckedChange = actions::onToggleWaveform)
    SegmentedSelector(
        label = "Grid",
        options = GridType.entries,
        selected = state.grid,
        labelFor = ::gridTypeLabel,
        onSelect = actions::onGridType,
    )
    ToggleRow(label = "Level", checked = state.level, onCheckedChange = actions::onToggleLevel)
    ToggleRow(label = "Punch-In", checked = state.punchIn, onCheckedChange = actions::onTogglePunchIn)
}

@Composable
private fun AdvancedTab(state: CameraUiState, actions: CameraActions) {
    val context = LocalContext.current
    TabTitle("Advanced")
    LabelValueRow(
        label = "Privacy Policy",
        valueLabel = "Open",
        onClick = { openPrivacyPolicy(context) },
    )
    ToggleRow(
        label = "Remember Settings",
        checked = state.rememberSettings,
        onCheckedChange = actions::onToggleRememberSettings,
    )
    LabelValueRow(
        label = "Camera Override",
        valueLabel = state.cameraOverrideId ?: "Default",
        onClick = if (state.cameraOverrideId != null) ({ actions.onCameraOverride(null) }) else null,
    )
    SegmentedSelector(
        label = "Native Log (HAL, experimental)",
        options = VendorLogMode.entries,
        selected = state.vendorLogMode,
        labelFor = { if (it == VendorLogMode.ON) "On" else "Off" },
        onSelect = actions::onVendorLogMode,
    )
    Text(
        "Drives the device's own log key (com.oplus.log.video.mode) so the ISP emits a " +
            "scene-referred log stream from sensor data — more latitude than the GL curve, which " +
            "can only re-map the display SDR output. Bypasses the GL curve and tags the file " +
            "BT.2020 full-range. Note: not white-balanced (warm scenes read warm — set WB in grade) " +
            "and not a drop-in for OPPO's O-Log2 LUT; use TF O-Log2 for a LUT-accurate file. Not " +
            "persisted across launches.",
        color = CameraColors.TextSecondary,
        style = MaterialTheme.typography.labelSmall,
    )

    SectionHeader("Vendor Features (experimental)")
    ToggleRow(label = "In-Sensor Zoom", checked = state.vendorInSensorZoom, onCheckedChange = actions::onVendorInSensorZoom)
    Text(
        "In-sensor zoom (EnableInsensorZoom — sensor-domain crop-zoom, cleaner than digital zoom), a " +
            "QTI HAL session feature the device exposes. Reopens the camera; not persisted. (Auto HDR / " +
            "Ideal RAW / APV / macro / custom-LUT vendor keys were tried but excluded — device-verified " +
            "they break capture or crash the camera HAL on this device.)",
        color = CameraColors.TextSecondary,
        style = MaterialTheme.typography.labelSmall,
    )
}

private fun openPrivacyPolicy(context: Context) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, PRIVACY_POLICY_URL.toUri())) }
}

private const val PRIVACY_POLICY_URL = "https://hletrd.github.io/telecam-pro/privacy-policy/"
