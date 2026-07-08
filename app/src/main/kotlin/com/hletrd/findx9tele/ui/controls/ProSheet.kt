package com.hletrd.findx9tele.ui.controls

import android.content.Context
import android.content.Intent
import android.util.Range
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
import com.hletrd.findx9tele.camera.AfSpotSize
import com.hletrd.findx9tele.camera.ExposureMode
import com.hletrd.findx9tele.camera.FrameLineType
import com.hletrd.findx9tele.camera.ExposureStep
import com.hletrd.findx9tele.camera.AspectRatio
import com.hletrd.findx9tele.camera.AudioScene
import com.hletrd.findx9tele.camera.AudioInputPreference
import com.hletrd.findx9tele.camera.BitrateLevel
import com.hletrd.findx9tele.camera.CameraUiState
import com.hletrd.findx9tele.camera.ColorEffect
import com.hletrd.findx9tele.camera.ColorTransfer
import com.hletrd.findx9tele.camera.DriveMode
import com.hletrd.findx9tele.camera.FnSlot
import com.hletrd.findx9tele.camera.HardwareKeyAction
import com.hletrd.findx9tele.camera.PeakingColor
import com.hletrd.findx9tele.camera.PeakingLevel
import com.hletrd.findx9tele.camera.ZebraLevel
import com.hletrd.findx9tele.camera.FocusMode
import com.hletrd.findx9tele.camera.GridType
import com.hletrd.findx9tele.camera.LensChoice
import com.hletrd.findx9tele.camera.MemorySlot
import com.hletrd.findx9tele.camera.MeteringMode
import com.hletrd.findx9tele.camera.ProcessingLevel
import com.hletrd.findx9tele.camera.ShutterMode
import com.hletrd.findx9tele.camera.ShutterTimer
import com.hletrd.findx9tele.camera.VideoCodec
import com.hletrd.findx9tele.camera.VideoStabMode
import com.hletrd.findx9tele.camera.VideoFrameRate
import com.hletrd.findx9tele.camera.WbMode
import com.hletrd.findx9tele.camera.videoBitRate
import com.hletrd.findx9tele.video.EncoderCaps
import com.hletrd.findx9tele.ui.CameraActions
import com.hletrd.findx9tele.ui.theme.CameraColors
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
    MY_MENU("My"),
    SHOOTING("Shoot"),
    EXPOSURE("Exposure"),
    FOCUS("Focus"),
    LENS("Lens"),
    VIDEO("Video"),
    PROCESSING("Image"),
    ASSISTS("Assist"),
    ADVANCED("Setup"),
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ProSheet(
    state: CameraUiState,
    actions: CameraActions,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialTab: ProSheetTab = ProSheetTab.SHOOTING,
    onTabChange: (ProSheetTab) -> Unit = {},
) {
    var selectedTab by remember { mutableStateOf(initialTab) }
    // A fixed, NON-draggable bottom panel — NOT Material3's ModalBottomSheet. The sheet let the whole
    // dialog be dragged upward past its rest position (the "bounce" the user saw), and Material3 1.4.0
    // exposes no way to disable that drag. A plain scrim + anchored panel can't be dragged at all;
    // it's dismissed only by the X, a scrim tap, or the system Back gesture.
    BackHandler(enabled = true, onBack = onDismiss)
    // Interaction sources for indication-free clickables (scrim dismiss + panel click-through block).
    val scrimInteraction = remember { MutableInteractionSource() }
    val panelInteraction = remember { MutableInteractionSource() }

    Box(modifier = modifier.fillMaxSize()) {
        // Scrim: tap outside the panel to dismiss.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(interactionSource = scrimInteraction, indication = null, onClick = onDismiss),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(CameraColors.Pill)
                // Consume taps on the panel so they don't fall through to the scrim below and dismiss.
                .clickable(interactionSource = panelInteraction, indication = null, onClick = {})
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Menu", color = CameraColors.TextPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                CloseButton(onClick = onDismiss)
            }

            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                TabRail(selected = selectedTab, onSelect = { selectedTab = it; onTabChange(it) })
                Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.08f)))
                // Content scroll. overscrollEffect = null removes the stretch glow at the ends; the
                // panel itself no longer drags, so there is nothing left to bounce. Weighted Box because
                // Modifier.weight is a RowScope extension.
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState(), overscrollEffect = null)
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        when (selectedTab) {
                            ProSheetTab.MY_MENU -> MyMenuTab(state, actions)
                            ProSheetTab.SHOOTING -> ShootingTab(state, actions)
                            ProSheetTab.EXPOSURE -> ExposureColorTab(state, actions)
                            ProSheetTab.FOCUS -> FocusTab(state, actions)
                            ProSheetTab.LENS -> LensTab(state, actions)
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
        ProSheetTab.MY_MENU -> {
            val p = Path().apply {
                moveTo(size.width * 0.5f, size.height * 0.12f)
                lineTo(size.width * 0.62f, size.height * 0.38f)
                lineTo(size.width * 0.9f, size.height * 0.42f)
                lineTo(size.width * 0.68f, size.height * 0.62f)
                lineTo(size.width * 0.74f, size.height * 0.9f)
                lineTo(size.width * 0.5f, size.height * 0.76f)
                lineTo(size.width * 0.26f, size.height * 0.9f)
                lineTo(size.width * 0.32f, size.height * 0.62f)
                lineTo(size.width * 0.1f, size.height * 0.42f)
                lineTo(size.width * 0.38f, size.height * 0.38f)
                close()
            }
            drawPath(p, color, style = stroke)
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
        ProSheetTab.LENS -> {
            // Lens glyph: nested optic rings with a solid center element.
            drawCircle(color, radius = size.minDimension * 0.46f, center = center, style = stroke)
            drawCircle(color, radius = size.minDimension * 0.28f, center = center, style = Stroke(width = 1.2.dp.toPx()))
            drawCircle(color, radius = size.minDimension * 0.1f, center = center)
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
private fun MyMenuTab(state: CameraUiState, actions: CameraActions) {
    TabTitle("My Menu")
    if (state.myMenuSlots.isEmpty()) {
        Text("Empty", color = CameraColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
    } else {
        state.myMenuSlots.forEach { slot ->
            LabelValueRow(
                label = fnSlotLabel(slot),
                valueLabel = fnSlotValue(slot, state),
                onClick = { performQuickFn(slot, state, actions) },
            )
        }
    }
    if (state.recentSettingSlots.isNotEmpty()) {
        SectionHeader("Recent")
        state.recentSettingSlots.forEach { slot ->
            LabelValueRow(
                label = fnSlotLabel(slot),
                valueLabel = fnSlotValue(slot, state),
                onClick = { performQuickFn(slot, state, actions) },
            )
        }
    }
}

@Composable
private fun MemoryRecallControls(state: CameraUiState, actions: CameraActions) {
    SectionHeader("MR")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        MemorySlot.entries.forEach { slot ->
            val saved = slot in state.savedMemorySlots
            FilterChip(
                selected = state.activeMemorySlot == slot,
                onClick = { actions.onRecallMemorySlot(slot) },
                enabled = saved,
                label = { Text(memorySlotLabel(slot)) },
                colors = pixelChipColors(),
                border = pixelChipBorder(state.activeMemorySlot == slot),
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        MemorySlot.entries.forEach { slot ->
            FilterChip(
                selected = false,
                onClick = { actions.onStoreMemorySlot(slot) },
                label = { Text("Save ${memorySlotLabel(slot)}") },
                colors = pixelChipColors(),
                border = pixelChipBorder(false),
            )
        }
    }
}

@Composable
private fun ShootingTab(state: CameraUiState, actions: CameraActions) {
    val caps = state.caps
    TabTitle("Shooting")
    MemoryRecallControls(state = state, actions = actions)
    PhotoFormatToggles(formats = state.photoFormats, onSetPhotoFormats = actions::onSetPhotoFormats)
    SegmentedSelector(
        label = "Aspect",
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
        label = "Drive",
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
    TabTitle("Exposure")
    SectionHeader("Exposure")
    // PASM-style: P (auto), S (shutter-priority, app auto-ISO), ISO (iso-priority, app auto-shutter),
    // M (manual). No aperture-priority — the tele aperture is fixed.
    SegmentedSelector(
        label = "Mode",
        options = ExposureMode.entries,
        selected = controls.exposureMode,
        labelFor = { it.letter },
        onSelect = actions::onExposureMode,
    )
    ToggleRow(label = "AE Lock", checked = controls.aeLock, onCheckedChange = actions::onToggleAeLock)
    SegmentedSelector(
        label = "Flicker",
        options = Antibanding.entries,
        selected = controls.antibanding,
        labelFor = ::antibandingLabel,
        onSelect = actions::onAntibanding,
    )
    SegmentedSelector(
        label = "Shutter",
        options = ShutterMode.entries,
        selected = controls.shutterMode,
        labelFor = ::shutterModeLabel,
        onSelect = actions::onShutterMode,
    )
    SegmentedSelector(
        label = "Step",
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
        enabled = controls.exposureMode == ExposureMode.ISO || controls.exposureMode == ExposureMode.MANUAL,
    )

    SectionHeader("Metering")
    SegmentedSelector(
        label = "Metering",
        options = MeteringMode.entries,
        selected = controls.meteringMode,
        labelFor = ::meteringModeLabel,
        onSelect = actions::onMeteringMode,
    )

    SectionHeader("WB")
    SegmentedSelector(
        label = "WB",
        options = WbMode.entries,
        selected = controls.wbMode,
        labelFor = ::wbModeLabel,
        onSelect = actions::onWbMode,
    )
    if (controls.wbMode == WbMode.MANUAL) {
        LabeledSlider(
            label = "Kelvin",
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
    // Sony Custom WB: frame a white/grey card and capture — freezes the AWB gains of that frame.
    FilterChip(
        selected = controls.wbMode == WbMode.CUSTOM,
        onClick = actions::onCaptureCustomWb,
        label = { Text("Capture Custom WB") },
        colors = pixelChipColors(),
        border = pixelChipBorder(controls.wbMode == WbMode.CUSTOM),
    )
    Text(
        "Frame a white or grey card, then tap.",
        color = CameraColors.TextSecondary,
        style = MaterialTheme.typography.labelSmall,
    )
    ToggleRow(label = "AWB Lock", checked = controls.awbLock, onCheckedChange = actions::onToggleAwbLock)
}

@Composable
private fun FocusTab(state: CameraUiState, actions: CameraActions) {
    val controls = state.controls
    TabTitle("Focus")
    SegmentedSelector(
        label = "AF",
        options = FocusMode.entries,
        selected = controls.focusMode,
        labelFor = ::focusModeLabel,
        onSelect = actions::onFocusMode,
    )
    // Sony Focus Area: Spot S/M/L — the size of the tap-AF/metering region.
    SegmentedSelector(
        label = "Spot Size",
        options = AfSpotSize.entries,
        selected = controls.afSpotSize,
        labelFor = { it.label },
        onSelect = actions::onAfSpotSize,
    )
    if (controls.focusMode != FocusMode.MANUAL) {
        ToggleRow(label = "AF Lock", checked = controls.afLock, onCheckedChange = actions::onAfLock)
    }
    ToggleRow(label = "Peaking", checked = state.focusPeaking, onCheckedChange = actions::onTogglePeaking)
    SegmentedSelector(
        label = "Peaking Level",
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
private fun LensTab(state: CameraUiState, actions: CameraActions) {
    TabTitle("Lens")
    // Picking a lens bundles teleconverter mode: 3× turns it ON (afocal 180° flip), every other lens
    // turns it OFF — one tap. The teleconverter is locked to the 3× periscope.
    SegmentedSelector(
        label = "Lens",
        options = LensChoice.entries,
        selected = state.lens,
        labelFor = ::lensLabel,
        onSelect = actions::onLens,
    )
    val focalCaption = when (state.lens) {
        LensChoice.ULTRAWIDE -> "14 mm"
        LensChoice.MAIN -> "23 mm"
        LensChoice.TELE3X -> "70 mm + TC = 300 mm"
        LensChoice.TELE10X -> "230 mm"
    }
    Text(focalCaption, color = CameraColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
    ToggleRow(
        label = "Teleconverter",
        checked = state.teleconverterMode,
        onCheckedChange = actions::onToggleTeleconverter,
    )
    Text(
        "3× lens only.",
        color = CameraColors.TextSecondary,
        style = MaterialTheme.typography.labelSmall,
    )

    // Stabilization lives here with the rest of the optics — it does not need its own menu tab
    // (feedback). HAL OIS+EIS path; OIS physically cuts per-frame motion blur at 300 mm.
    SegmentedSelector(
        label = "Image Stabilization",
        options = VideoStabMode.entries,
        selected = state.videoStabMode,
        labelFor = { it.label },
        onSelect = actions::onVideoStabMode,
    )
    val stabCaption = when (state.videoStabMode) {
        VideoStabMode.OFF -> "Off"
        VideoStabMode.STANDARD -> "OIS+EIS"
        VideoStabMode.ENHANCED -> "OIS+EIS, crop"
    }
    Text(stabCaption, color = CameraColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
    if (state.caps?.oisAvailable == true) {
        ToggleRow(label = "OIS", checked = state.controls.oisEnabled, onCheckedChange = actions::onToggleOis)
        Text(
            "Stills use OIS.",
            color = CameraColors.TextSecondary,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun VideoTab(state: CameraUiState, actions: CameraActions) {
    val caps = state.caps
    val codec = state.videoCodec
    val recordingMutable = !state.isRecording
    TabTitle("Video")
    if (state.isRecording) {
        LabelValueRow(
            label = "REC Lock",
            valueLabel = "Stop REC first",
        )
    }

    // Codecs are limited to what MediaCodecList actually advertises a muxable HW encoder for
    // (HEVC/AVC on this SoC).
    val codecOptions = remember { EncoderCaps.availableCodecs().ifEmpty { listOf(VideoCodec.HEVC, VideoCodec.AVC) } }
    SegmentedSelector(
        label = "Codec",
        options = codecOptions,
        selected = codec,
        labelFor = ::videoCodecLabel,
        onSelect = actions::onVideoCodec,
        enabled = recordingMutable,
    )

    // Open Gate records the full 4:3 sensor readout instead of a 16:9 crop; it swaps the resolution
    // list to the camera's 4:3 sizes.
    ToggleRow(
        label = "Open Gate 4:3",
        checked = state.openGate,
        onCheckedChange = actions::onToggleOpenGate,
        enabled = recordingMutable,
    )

    // Resolutions come from the SELECTED camera's real StreamConfigurationMap (4:3 when Open Gate,
    // else 16:9).
    val resolutionOptions = when {
        caps == null -> listOf(Size(3840, 2160), Size(1920, 1080))
        state.openGate -> caps.openGateVideoSizes
        else -> caps.availableVideoSizes
    }.ifEmpty { listOf(Size(3840, 2160), Size(1920, 1080)) }
    SegmentedSelector(
        label = "Resolution",
        options = resolutionOptions,
        selected = state.videoResolution,
        labelFor = ::videoResolutionLabel,
        onSelect = actions::onVideoResolution,
        enabled = recordingMutable,
    )

    // Frame rates gated per-resolution by real caps: normal rates need the camera to advertise the
    // integer fps (24/30/60 here); 120 needs a matching high-speed config; drop-frame variants
    // (23.976/29.97/59.94) ride their integer parent. 8K is capped ≤30.
    val fpsOptions = VideoFrameRate.availableFor(caps, state.videoResolution, codec)
    SegmentedSelector(
        label = "FPS",
        options = fpsOptions,
        selected = state.videoFrameRate,
        labelFor = ::videoFrameRateLabel,
        onSelect = actions::onVideoFrameRate,
        enabled = recordingMutable,
    )
    if (state.videoFrameRate.highSpeed) {
        Text(
            "Still capture off.",
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
        enabled = recordingMutable,
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

    ToggleRow(
        label = "Audio",
        checked = state.recordAudio,
        onCheckedChange = actions::onToggleRecordAudio,
        enabled = recordingMutable,
    )
    SegmentedSelector(
        label = "Input",
        options = AudioInputPreference.entries,
        selected = state.audioInputPreference,
        labelFor = { it.label },
        onSelect = actions::onAudioInputPreference,
        enabled = state.recordAudio && recordingMutable,
    )
    LabelValueRow(
        label = if (state.isRecording) "Audio Route" else "Input",
        valueLabel = state.audioRouteLabel,
    )
    // Directional audio: Sound Focus aims the mic array at the framed subject and tightens with zoom;
    // Sound Stage keeps a wider stereo image.
    SegmentedSelector(
        label = "Scene",
        options = AudioScene.entries,
        selected = state.audioScene,
        labelFor = { it.label },
        onSelect = actions::onAudioScene,
        enabled = state.recordAudio && recordingMutable,
    )
    if (state.audioScene == AudioScene.SOUND_FOCUS) {
        Text(
            "Directional pickup.",
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
        enabled = state.recordAudio && recordingMutable,
    )
    // Transfer (HLG/LOG/SDR) only drives the HEVC path; AVC always records 8-bit SDR, so the
    // selector is disabled there rather than pretending the choice applies.
    TransferSelector(
        transfer = state.transfer,
        onTransfer = actions::onTransfer,
        enabled = codec == VideoCodec.HEVC && recordingMutable,
    )
}

@Composable
private fun ProcessingTab(state: CameraUiState, actions: CameraActions) {
    val controls = state.controls
    TabTitle("Image")
    SegmentedSelector(
        label = "Sharpness",
        options = ProcessingLevel.entries,
        selected = controls.edge,
        labelFor = ::processingLevelLabel,
        onSelect = actions::onEdge,
    )
    SegmentedSelector(
        label = "NR",
        options = ProcessingLevel.entries,
        selected = controls.noiseReduction,
        labelFor = ::processingLevelLabel,
        onSelect = actions::onNoiseReduction,
    )
    SegmentedSelector(
        label = "Color",
        options = ColorEffect.entries,
        selected = controls.colorEffect,
        labelFor = ::colorEffectLabel,
        onSelect = actions::onColorEffect,
    )
}

@Composable
private fun AssistsTab(state: CameraUiState, actions: CameraActions) {
    TabTitle("Assist")
    // Gamma Display Assist (Sony): only meaningful while the Gamma is O-Log — the monitor shows the
    // normal image, the recorded file stays log.
    ToggleRow(
        label = "Gamma Disp. Assist",
        checked = state.gammaAssist,
        onCheckedChange = actions::onToggleGammaAssist,
        enabled = state.transfer == ColorTransfer.LOG,
    )
    SegmentedSelector(
        label = "Frame Lines",
        options = FrameLineType.entries,
        selected = state.frameLines,
        labelFor = { it.label },
        onSelect = actions::onFrameLines,
    )
    ToggleRow(label = "Zebra", checked = state.zebra, onCheckedChange = actions::onToggleZebra)
    SegmentedSelector(
        label = "Zebra IRE",
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
    TabTitle("Setup")
    LabelValueRow(
        label = "Privacy",
        valueLabel = "Open",
        onClick = { openPrivacyPolicy(context) },
    )
    ToggleRow(
        label = "Remember",
        checked = state.rememberSettings,
        onCheckedChange = actions::onToggleRememberSettings,
    )
    SectionHeader("Fn Bar")
    Text("Up to 8.", color = CameraColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
    FnSlotToggleList(selected = state.fnSlots, onSet = actions::onSetFnSlots)
    SectionHeader("My Menu")
    FnSlotToggleList(selected = state.myMenuSlots, onSet = actions::onSetMyMenuSlots)
    SectionHeader("Keys")
    SegmentedSelector(
        label = "Full Press",
        options = HardwareKeyAction.entries,
        selected = state.volumeKeyAction,
        labelFor = ::hardwareKeyActionLabel,
        onSelect = actions::onVolumeKeyAction,
    )
    SegmentedSelector(
        label = "Half Press",
        options = HardwareKeyAction.entries,
        selected = state.halfPressAction,
        labelFor = ::hardwareKeyActionLabel,
        onSelect = actions::onHalfPressAction,
    )
    LabelValueRow(
        label = "Camera ID",
        valueLabel = state.cameraOverrideId ?: "Default",
        onClick = if (state.cameraOverrideId != null) ({ actions.onCameraOverride(null) }) else null,
    )
    Text(
        "Log is under Video.",
        color = CameraColors.TextSecondary,
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun FnSlotToggleList(selected: List<FnSlot>, onSet: (List<FnSlot>) -> Unit) {
    FnSlot.entries.forEach { slot ->
        val checked = slot in selected
        ToggleRow(
            label = fnSlotLabel(slot),
            checked = checked,
            onCheckedChange = { enabled ->
                val next = if (enabled) selected + slot else selected.filterNot { it == slot }
                onSet(next)
            },
            enabled = checked || selected.size < 8,
        )
    }
}

internal fun fnSlotValue(slot: FnSlot, state: CameraUiState): String {
    val c = state.controls
    return when (slot) {
        FnSlot.EXPOSURE_MODE -> c.exposureMode.letter
        FnSlot.FOCUS -> focusModeLabel(c.focusMode)
        FnSlot.SHUTTER -> if (c.shutterMode == ShutterMode.ANGLE) "%.0f°".format(c.shutterAngle) else formatShutterSpeed(c.exposureTimeNs)
        FnSlot.ISO -> c.iso.toString()
        FnSlot.WB -> if (c.wbMode == WbMode.MANUAL) "${c.wbKelvin}K" else wbModeLabel(c.wbMode)
        FnSlot.EV -> "%+.1f".format(c.exposureCompensation * 0.333f)
        FnSlot.ZOOM -> "%.1fx".format(c.zoomRatio)
        FnSlot.STABILIZATION -> state.videoStabMode.label
        FnSlot.DRIVE -> driveModeLabel(state.driveMode)
        FnSlot.METERING -> meteringModeLabel(c.meteringMode)
        FnSlot.PEAKING -> if (state.focusPeaking) "On" else "Off"
        FnSlot.ZEBRA -> if (state.zebra) "On" else "Off"
        FnSlot.TRANSFER -> transferLabelShort(state.transfer)
        FnSlot.AUDIO_SCENE -> state.audioScene.label
        FnSlot.GRID -> gridTypeLabel(state.grid)
        FnSlot.LEVEL -> if (state.level) "On" else "Off"
        FnSlot.PUNCH_IN -> if (state.punchIn) "On" else "Off"
        FnSlot.TELECONVERTER -> if (state.teleconverterMode) "300mm" else "Off"
    }
}

internal fun performQuickFn(slot: FnSlot, state: CameraUiState, actions: CameraActions) {
    when (slot) {
        FnSlot.EXPOSURE_MODE -> actions.onExposureMode(nextExposureMode(state.controls.exposureMode))
        FnSlot.FOCUS -> actions.onFocusMode(nextFocusMode(state.controls.focusMode))
        FnSlot.SHUTTER -> actions.onShutterMode(if (state.controls.shutterMode == ShutterMode.SPEED) ShutterMode.ANGLE else ShutterMode.SPEED)
        FnSlot.ISO -> actions.onExposureMode(if (state.controls.exposureMode == com.hletrd.findx9tele.camera.ExposureMode.ISO) com.hletrd.findx9tele.camera.ExposureMode.PROGRAM else com.hletrd.findx9tele.camera.ExposureMode.ISO)
        FnSlot.WB -> actions.onWbMode(nextWbMode(state.controls.wbMode))
        FnSlot.EV -> actions.onExposureCompensation(0)
        FnSlot.ZOOM -> actions.onZoomRatio(1f)
        FnSlot.STABILIZATION -> actions.onVideoStabMode(nextVideoStabMode(state.videoStabMode))
        FnSlot.DRIVE -> actions.onDriveMode(nextDriveMode(state.driveMode))
        FnSlot.METERING -> actions.onMeteringMode(nextMeteringMode(state.controls.meteringMode))
        FnSlot.PEAKING -> actions.onTogglePeaking(!state.focusPeaking)
        FnSlot.ZEBRA -> actions.onToggleZebra(!state.zebra)
        FnSlot.TRANSFER -> actions.onTransfer(nextTransfer(state.transfer))
        FnSlot.AUDIO_SCENE -> actions.onAudioScene(nextAudioScene(state.audioScene))
        FnSlot.GRID -> actions.onGridType(nextGridType(state.grid))
        FnSlot.LEVEL -> actions.onToggleLevel(!state.level)
        FnSlot.PUNCH_IN -> actions.onTogglePunchIn(!state.punchIn)
        FnSlot.TELECONVERTER -> actions.onToggleTeleconverter(!state.teleconverterMode)
    }
}

private fun nextExposureMode(mode: com.hletrd.findx9tele.camera.ExposureMode): com.hletrd.findx9tele.camera.ExposureMode = when (mode) {
    com.hletrd.findx9tele.camera.ExposureMode.PROGRAM -> com.hletrd.findx9tele.camera.ExposureMode.SHUTTER
    com.hletrd.findx9tele.camera.ExposureMode.SHUTTER -> com.hletrd.findx9tele.camera.ExposureMode.ISO
    com.hletrd.findx9tele.camera.ExposureMode.ISO -> com.hletrd.findx9tele.camera.ExposureMode.MANUAL
    com.hletrd.findx9tele.camera.ExposureMode.MANUAL -> com.hletrd.findx9tele.camera.ExposureMode.PROGRAM
}

private fun nextFocusMode(mode: FocusMode): FocusMode = when (mode) {
    FocusMode.CONTINUOUS -> FocusMode.AUTO
    FocusMode.AUTO -> FocusMode.MANUAL
    FocusMode.MANUAL -> FocusMode.MACRO
    FocusMode.MACRO -> FocusMode.CONTINUOUS
}

private fun nextWbMode(mode: WbMode): WbMode = when (mode) {
    WbMode.AUTO -> WbMode.DAYLIGHT
    WbMode.DAYLIGHT -> WbMode.CLOUDY
    WbMode.CLOUDY -> WbMode.SHADE
    WbMode.SHADE -> WbMode.MANUAL
    WbMode.MANUAL -> WbMode.AUTO
    // CUSTOM is only ENTERED via "Capture Custom WB"; the Fn cycle steps past it back to AUTO.
    WbMode.INCANDESCENT, WbMode.FLUORESCENT, WbMode.CUSTOM -> WbMode.AUTO
}

private fun nextVideoStabMode(mode: VideoStabMode): VideoStabMode = when (mode) {
    VideoStabMode.OFF -> VideoStabMode.STANDARD
    VideoStabMode.STANDARD -> VideoStabMode.ENHANCED
    VideoStabMode.ENHANCED -> VideoStabMode.OFF
}

private fun nextDriveMode(mode: DriveMode): DriveMode = when (mode) {
    DriveMode.SINGLE -> DriveMode.BURST
    DriveMode.BURST -> DriveMode.AEB
    DriveMode.AEB -> DriveMode.TIMELAPSE
    DriveMode.TIMELAPSE -> DriveMode.SINGLE
}

private fun nextMeteringMode(mode: MeteringMode): MeteringMode = when (mode) {
    MeteringMode.MATRIX -> MeteringMode.CENTER
    MeteringMode.CENTER -> MeteringMode.SPOT
    MeteringMode.SPOT -> MeteringMode.MATRIX
}

private fun nextTransfer(transfer: ColorTransfer): ColorTransfer = when (transfer) {
    ColorTransfer.HLG -> ColorTransfer.LOG
    ColorTransfer.LOG -> ColorTransfer.SDR
    ColorTransfer.SDR -> ColorTransfer.HLG
}

private fun nextAudioScene(scene: AudioScene): AudioScene = when (scene) {
    AudioScene.STANDARD -> AudioScene.SOUND_FOCUS
    AudioScene.SOUND_FOCUS -> AudioScene.SOUND_STAGE
    AudioScene.SOUND_STAGE -> AudioScene.STANDARD
}

private fun nextGridType(type: GridType): GridType = when (type) {
    GridType.NONE -> GridType.THIRDS
    GridType.THIRDS -> GridType.GOLDEN
    GridType.GOLDEN -> GridType.SQUARE
    GridType.SQUARE -> GridType.CENTER
    GridType.CENTER -> GridType.NONE
}

private fun openPrivacyPolicy(context: Context) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, PRIVACY_POLICY_URL.toUri())) }
}

private const val PRIVACY_POLICY_URL = "https://hletrd.github.io/telecam-pro/privacy-policy/"
