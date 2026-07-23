package com.hletrd.findx9tele.ui.controls

import android.content.Context
import android.content.Intent
import android.util.Range
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import com.hletrd.findx9tele.camera.CameraFacing
import com.hletrd.findx9tele.camera.CaptureMode
import com.hletrd.findx9tele.camera.ColorEffect
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
import com.hletrd.findx9tele.camera.ControlAvailability
import com.hletrd.findx9tele.camera.controlAvailability
import com.hletrd.findx9tele.camera.controlCapabilities
import com.hletrd.findx9tele.camera.hiResToggleEnabled
import com.hletrd.findx9tele.camera.videoBitRate
import com.hletrd.findx9tele.video.EncoderCaps
import com.hletrd.findx9tele.ui.CameraActions
import com.hletrd.findx9tele.ui.formatDisplayZoom
import com.hletrd.findx9tele.ui.formatZoomMultiplier
import com.hletrd.findx9tele.ui.theme.CameraColors
import java.util.Locale
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

internal data class ProSheetTabSelection(val tab: ProSheetTab, val selected: Boolean)

internal fun proSheetTabSelection(selected: ProSheetTab): List<ProSheetTabSelection> =
    ProSheetTab.entries.map { ProSheetTabSelection(it, it == selected) }

internal fun proSheetUsesSideLayout(width: Float, height: Float): Boolean = width > height

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ProSheet(
    state: CameraUiState,
    actions: CameraActions,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialTab: ProSheetTab = ProSheetTab.SHOOTING,
    onTabChange: (ProSheetTab) -> Unit = {},
    // Dial-backed My Menu / Recent rows route HERE (close the sheet, open that value's ruler) —
    // the same transition the Fn overlay tile uses. performQuickFn's cycle fallback RESET these
    // values instead (zoom→1×, EV→0, exposure-MODE flips) with no affordance saying so: the same
    // FnSlot behaved differently per surface (cycle-6 designer D-01).
    onSelectManualDial: (DialType) -> Unit = {},
) {
    var selectedTab by remember { mutableStateOf(initialTab) }
    // A fixed, NON-draggable bottom panel — NOT Material3's ModalBottomSheet. The sheet let the whole
    // dialog be dragged upward past its rest position (the "bounce" the user saw), and Material3 1.4.0
    // exposes no way to disable that drag. A plain scrim + anchored panel can't be dragged at all;
    // it's dismissed only by the X, a scrim tap, or the system Back gesture.
    BackHandler(enabled = true, onBack = onDismiss)
    val closeFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { closeFocusRequester.requestFocus() }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val sideLayout = proSheetUsesSideLayout(maxWidth.value, maxHeight.value)
        // Scrim: tap outside the panel to dismiss.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                // Outside taps remain a convenient touch dismissal, but the scrim is deliberately
                // absent from accessibility traversal. The explicit 48 dp X below is the sole
                // named Close-settings action, so switch/keyboard users do not encounter a
                // duplicate full-screen button before the actual settings panel.
                .pointerInput(onDismiss) {
                    detectTapGestures(onTap = { onDismiss() })
                }
        )

        val panelModifier = if (sideLayout) {
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(0.72f)
        } else {
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
        }
        val panelShape = if (sideLayout) {
            RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)
        } else {
            RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        }
        Column(
            modifier = panelModifier
                .clip(panelShape)
                .background(CameraColors.Pill)
                .semantics {
                    paneTitle = "Camera settings"
                    isTraversalGroup = true
                }
                // Consume panel taps without adding a nameless dummy Button to the semantics tree.
                .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Menu", color = CameraColors.TextPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                CloseButton(
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(closeFocusRequester),
                )
            }

            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                TabRail(selected = selectedTab, onSelect = { selectedTab = it; onTabChange(it) })
                Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.08f)))
                // Content scroll. overscrollEffect = null removes the stretch glow at the ends; the
                // panel itself no longer drags, so there is nothing left to bounce. Weighted Box because
                // Modifier.weight is a RowScope extension.
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    // Each tab owns its own scroll position: a single shared rememberScrollState()
                    // opened every tab at the PREVIOUS tab's offset (scroll Setup near its bottom,
                    // pick Lens → Lens opened mid-page with its title hidden). Saveable per tab so
                    // the offsets survive process recreation. Positional scoping via the key()
                    // composable, NOT rememberSaveable's custom-key overload — that overload is
                    // deprecated (QA4-1; its own deprecation text names the state-sharing/loss bug
                    // class this surface already shipped twice) and violates the repo's
                    // no-deprecated-APIs policy.
                    val tabScrollStates = ProSheetTab.entries.associateWith { tab ->
                        key(tab) {
                            rememberSaveable(saver = ScrollState.Saver) {
                                ScrollState(initial = 0)
                            }
                        }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(tabScrollStates.getValue(selectedTab), overscrollEffect = null)
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        when (selectedTab) {
                            ProSheetTab.MY_MENU -> MyMenuTab(
                                state,
                                actions,
                                // Dismiss FIRST: the WB transition may re-open the sheet at the
                                // Exposure tab (manualDialTransition.openExposureSheet), and a
                                // trailing dismiss would immediately close that reopen.
                                openDial = { dial ->
                                    onDismiss()
                                    onSelectManualDial(dial)
                                },
                            )
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
    // 48 dp touch target; 32 dp visual pill.
    val activate = onClick
    Box(
        modifier = modifier
            .size(48.dp)
            .focusable()
            .clearAndSetSemantics {
                contentDescription = "Close settings"
                role = Role.Button
                onClick {
                    activate()
                    true
                }
            }
            .clickable(role = Role.Button, onClick = onClick),
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
            .selectableGroup()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
    ) {
        proSheetTabSelection(selected).forEach { item ->
            TabRailItem(
                tab = item.tab,
                selected = item.selected,
                onClick = { onSelect(item.tab) },
            )
        }
    }
}

@Composable
private fun TabRailItem(tab: ProSheetTab, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val fg = if (selected) CameraColors.TextPrimary else CameraColors.TextSecondary
    val activate = onClick
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(if (selected) Color.White.copy(alpha = 0.10f) else Color.Transparent)
            // Keep the visible icon/label and the Tab action on one accessibility node. Without
            // this merge Android exported an unnamed focusable parent plus a separate inert Text,
            // so switch/TalkBack users could focus a tab without hearing which tab it was.
            .focusable()
            .clearAndSetSemantics {
                contentDescription = tab.label
                stateDescription = if (selected) "Selected" else "Not selected"
                role = Role.Tab
                this.selected = selected
                onClick {
                    activate()
                    true
                }
            }
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
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
private fun MyMenuTab(
    state: CameraUiState,
    actions: CameraActions,
    openDial: (DialType) -> Unit,
) {
    // Same availability gate as the Fn overlay tiles: a dial-backed row must not open a ruler its
    // mode/caps cannot honor (the exact predicate pair FnOverlay uses).
    val availability = remember(state.caps, state.controls) {
        controlAvailability(state.caps?.controlCapabilities(), state.controls)
    }
    TabTitle("My Menu")
    if (state.myMenuSlots.isEmpty()) {
        Text("Empty", color = CameraColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
    } else {
        state.myMenuSlots.forEach { slot ->
            QuickFnRow(slot, state, actions, availability, openDial)
        }
    }
    if (state.recentSettingSlots.isNotEmpty()) {
        SectionHeader("Recent")
        state.recentSettingSlots.forEach { slot ->
            QuickFnRow(slot, state, actions, availability, openDial)
        }
    }
}

@Composable
private fun QuickFnRow(
    slot: FnSlot,
    state: CameraUiState,
    actions: CameraActions,
    availability: ControlAvailability,
    openDial: (DialType) -> Unit,
) {
    // Dial-backed slots open their value's ruler (like the Fn overlay tile); only genuine
    // cycle/toggle slots fall through to performQuickFn. The old unconditional performQuickFn
    // path silently RESET dial values from a row that read like a status line (D-01).
    val manualDial = manualDialForFnSlot(slot)
    val enabled = quickFnEnabled(slot, state) && when (manualDial) {
        DialType.WB -> whiteBalanceFnChipEnabled(state.controls.wbMode, availability)
        null -> true
        else -> quickManualDialEnabled(manualDial, availability)
    }
    LabelValueRow(
        label = fnSlotLabel(slot),
        valueLabel = fnSlotValue(slot, state),
        enabled = enabled,
        onClick = {
            if (manualDial != null) openDial(manualDial) else performQuickFn(slot, state, actions)
        },
    )
}

@Composable
private fun MemoryRecallControls(state: CameraUiState, actions: CameraActions) {
    SectionHeader("MR")
    MemorySlot.entries.forEach { slot ->
        val saved = slot in state.savedMemorySlots
        val name = state.memorySlotNames[slot] ?: slot.label
        val summary = state.memorySlotSummaries[slot].orEmpty()
        MemoryPresetRow(
            slot = slot,
            name = if (saved) name else "Empty",
            summary = if (saved) summary else "Save current setup",
            active = state.activeMemorySlot == slot,
            saved = saved,
            locked = state.isRecording,
            onRecall = { actions.onRecallMemorySlot(slot) },
            onSave = { actions.onStoreMemorySlot(slot) },
        )
    }
}

@Composable
private fun MemoryPresetRow(
    slot: MemorySlot,
    name: String,
    summary: String,
    active: Boolean,
    saved: Boolean,
    locked: Boolean,
    onRecall: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) Color(0xFFFFD60A).copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = if (active) 0.28f else 0.10f), RoundedCornerShape(8.dp))
            .clickable(enabled = saved && !locked, onClick = onRecall)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = slot.label,
            color = if (active) Color(0xFFFFD60A) else CameraColors.TextSecondary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(36.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, color = CameraColors.TextPrimary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text(summary, color = CameraColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
        }
        // DES4-3: the d875eea 48 dp sweep covered the three shared selector components; this
        // standalone action chip (writes an MR bank) was left bare at ~32 dp.
        MinTouchTargetChip {
            FilterChip(
                selected = false,
                onClick = onSave,
                enabled = !locked,
                label = { Text(if (saved) "Update" else "Save") },
                colors = pixelChipColors(),
                border = pixelChipBorder(false),
            )
        }
    }
}

@Composable
private fun ShootingTab(state: CameraUiState, actions: CameraActions) {
    val caps = state.caps
    // Same capability projection every other tab consumes (PERF4-8 remember pattern); the hi-res
    // row reads its route fact from here instead of re-deriving admission axes from raw caps.
    val availability = remember(state.caps, state.controls) {
        controlAvailability(state.caps?.controlCapabilities(), state.controls)
    }
    TabTitle("Shooting")
    SectionHeader("Format")
    PhotoFormatToggles(
        formats = state.photoFormats,
        processedAvailable = state.photoSessionOutputs.processed,
        rawAvailable = state.photoSessionOutputs.raw,
        onSetPhotoFormats = actions::onSetPhotoFormats,
    )
    // Hi-res still: visible only when the SELECTED camera is a standalone route that actually
    // advertises a full-sensor size (the logical seamless camera never qualifies — its gralloc
    // rejects big blobs). Both facts arrive as ONE projected route fact, and enablement joins the
    // live mode/aspect axes through the shared hiResAdmitted predicate (cycle-6 architect F3 — the
    // old inline conjunction here was a third encoding of the admission axes). The row shows the
    // INTENT; the OSD HR tag shows accepted session truth.
    if (availability.hiResAdvertisedStandalone) {
        ToggleRow(
            label = "High resolution",
            checked = state.hiResStill,
            onCheckedChange = actions::onToggleHiResStill,
            enabled = hiResToggleEnabled(
                availability = availability,
                videoMode = state.mode == CaptureMode.VIDEO,
                aspect = state.aspectRatio,
                recording = state.isRecording,
            ),
        )
        // The advertised dimensions are display copy only; every admission axis stays projected.
        caps?.hiResJpegSize?.let { hiResSize ->
            val mp = (hiResSize.width.toLong() * hiResSize.height / 1_000_000).toInt()
            Text(
                "${hiResSize.width}×${hiResSize.height} (${mp}MP). " +
                    "Full-sensor still. JPEG only, 4:3, RAW off. Reduces low-light quality.",
                color = CameraColors.TextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
    SegmentedSelector(
        label = "Aspect",
        options = AspectRatio.entries,
        selected = state.aspectRatio,
        labelFor = ::aspectRatioLabel,
        onSelect = actions::onAspectRatio,
    )
    caps?.zoomRatioRange?.let { range ->
        // TELE shows the converter-equivalent scale (13–60×) but writes the lens-local ratio.
        val zBase = if (state.teleconverterMode) com.hletrd.findx9tele.camera.TELE_DISPLAY_BASE else 1f
        val loDisplay = range.lower * zBase
        val zHi = if (state.teleconverterMode) {
            minOf(range.upper * zBase, com.hletrd.findx9tele.camera.TELE_MAX_DISPLAY_ZOOM)
        } else {
            range.upper
        }
        // Defensive guard (mirrors the sibling ZoomRuler in ManualDials.kt): coerceIn/ClosedRange
        // THROW on lower > upper. Unreachable on this device's advertised caps, but a pathological
        // tele caps profile crossing the TELE display ceiling would otherwise crash every
        // recomposition of this tab.
        if (zHi > loDisplay) {
            LabeledSlider(
                label = "Zoom",
                valueLabel = formatZoomMultiplier(state.controls.zoomRatio * zBase),
                value = (state.controls.zoomRatio * zBase).coerceIn(loDisplay, zHi),
                onValueChange = { v -> actions.onZoomRatio(v / zBase) },
                valueRange = loDisplay..zHi,
            )
        }
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
    MemoryRecallControls(state = state, actions = actions)
}

@Composable
private fun ExposureColorTab(state: CameraUiState, actions: CameraActions) {
    val controls = state.controls
    val caps = state.caps
    // remember(): the projection allocates ~9 filtered lists and caps/controls rarely change,
    // while telemetry ticks recompose the open tab ~10-25 Hz (PERF4-8; TopBar/ManualDials
    // already memoize the identical projection).
    val availability = remember(caps, controls) { controlAvailability(caps?.controlCapabilities(), controls) }
    TabTitle("Exposure")
    SectionHeader("Exposure")
    // PASM-style: P (auto), S (shutter-priority, app auto-ISO), ISO (iso-priority, app auto-shutter),
    // M (manual). No aperture-priority — the tele aperture is fixed.
    SegmentedSelector(
        label = "Mode",
        options = availability.exposureModes,
        selected = controls.exposureMode,
        labelFor = { it.letter },
        onSelect = actions::onExposureMode,
        enabled = availability.exposureModes.size > 1,
    )
    ToggleRow(
        label = "AE Lock",
        checked = controls.aeLock,
        onCheckedChange = actions::onToggleAeLock,
        enabled = availability.aeLockEnabled,
    )
    SegmentedSelector(
        label = "Flicker",
        options = availability.antibandingModes,
        selected = controls.antibanding,
        labelFor = ::antibandingLabel,
        onSelect = actions::onAntibanding,
        enabled = availability.antibandingModes.size > 1,
    )
    SectionHeader("Shutter")
    SegmentedSelector(
        label = "Shutter",
        options = ShutterMode.entries,
        selected = controls.shutterMode,
        labelFor = ::shutterModeLabel,
        onSelect = actions::onShutterMode,
        enabled = availability.shutterDialEnabled,
    )
    SegmentedSelector(
        label = "Step",
        options = ExposureStep.entries,
        selected = controls.exposureStep,
        labelFor = { "${it.label} EV" },
        onSelect = actions::onExposureStep,
        enabled = availability.shutterDialEnabled,
    )
    val isoRange = caps?.isoRange ?: Range(controls.iso, controls.iso)
    SectionHeader("ISO")
    LabeledSlider(
        label = "ISO",
        valueLabel = controls.iso.toString(),
        value = controls.iso.toFloat().coerceIn(isoRange.lower.toFloat(), isoRange.upper.toFloat()),
        onValueChange = { actions.onIso(it.roundToInt()) },
        valueRange = isoRange.lower.toFloat()..isoRange.upper.toFloat(),
        enabled = availability.isoDialEnabled &&
            (controls.exposureMode == ExposureMode.ISO || controls.exposureMode == ExposureMode.MANUAL),
    )

    SectionHeader("Metering")
    SegmentedSelector(
        label = "Metering",
        options = availability.meteringModes,
        selected = controls.meteringMode,
        labelFor = ::meteringModeLabel,
        onSelect = actions::onMeteringMode,
        enabled = availability.meteringModes.size > 1,
    )

    SectionHeader("WB")
    SegmentedSelector(
        label = "WB",
        options = availability.wbModes,
        selected = controls.wbMode,
        labelFor = ::wbModeLabel,
        onSelect = actions::onWbMode,
        enabled = availability.wbModes.size > 1,
    )
    if (controls.wbMode == WbMode.MANUAL) {
        LabeledSlider(
            label = "Kelvin",
            valueLabel = "${controls.wbKelvin}K",
            value = controls.wbKelvin.toFloat().coerceIn(2000f, 10000f),
            onValueChange = { actions.onWbKelvin(it.roundToInt()) },
            valueRange = 2000f..10000f,
            enabled = availability.wbDialEnabled,
        )
        LabeledSlider(
            label = "Tint",
            valueLabel = "%+d".format(Locale.US, controls.wbTint),
            value = controls.wbTint.toFloat().coerceIn(-50f, 50f),
            onValueChange = { actions.onWbTint(it.roundToInt()) },
            valueRange = -50f..50f,
            enabled = availability.wbDialEnabled,
        )
    }
    // Sony Custom WB: frame a white/grey card and capture a fresh accepted-session AWB sample.
    val customWbCaptureEnabled = state.cameraReady && availability.customWbCaptureEnabled
    // DES4-3: standalone action chip missed by the d875eea sweep — same 48 dp wrapper.
    MinTouchTargetChip {
        FilterChip(
            selected = controls.wbMode == WbMode.CUSTOM,
            onClick = actions::onCaptureCustomWb,
            enabled = customWbCaptureEnabled,
            label = { Text("Capture Custom WB") },
            colors = pixelChipColors(),
            border = pixelChipBorder(controls.wbMode == WbMode.CUSTOM),
        )
    }
    Text(
        if (customWbCaptureEnabled) {
            "Aim at a white or gray card."
        } else if (!state.cameraReady) {
            "Camera busy."
        } else {
            "Requires Auto WB with AWB Lock off."
        },
        color = CameraColors.TextSecondary,
        style = MaterialTheme.typography.labelSmall,
    )
    ToggleRow(
        label = "AWB Lock",
        checked = controls.awbLock,
        onCheckedChange = actions::onToggleAwbLock,
        enabled = availability.awbLockEnabled,
    )
}

@Composable
private fun FocusTab(state: CameraUiState, actions: CameraActions) {
    val controls = state.controls
    // remember(): see PERF4-8 note in ExposureColorTab.
    val availability = remember(state.caps, controls) { controlAvailability(state.caps?.controlCapabilities(), controls) }
    TabTitle("Focus")
    SectionHeader("Autofocus")
    SegmentedSelector(
        label = "AF",
        options = availability.focusModes,
        selected = controls.focusMode,
        labelFor = ::focusModeLabel,
        onSelect = actions::onFocusMode,
        enabled = availability.focusModes.size > 1,
    )
    // Sony Focus Area: Spot S/M/L — the size of the tap-AF/metering region.
    SegmentedSelector(
        label = "Spot Size",
        options = AfSpotSize.entries,
        selected = controls.afSpotSize,
        labelFor = { it.label },
        onSelect = actions::onAfSpotSize,
        enabled = availability.afSpotSizeEnabled,
    )
    if (controls.focusMode != FocusMode.MANUAL) {
        ToggleRow(
            label = "AF Lock",
            checked = controls.afLock,
            onCheckedChange = actions::onAfLock,
            enabled = availability.afLockEnabled,
        )
    }
    LabelValueRow(
        label = "Tap Focus",
        valueLabel = if (state.tapFocusHeld) "Reset" else "No point",
        enabled = state.tapFocusHeld,
        onClick = actions::onResetFocusPoint,
        modifier = Modifier.semantics {
            contentDescription = "Reset focus point"
            stateDescription = if (state.tapFocusHeld) "Tap focus held" else "No tap focus point"
        },
    )
    SectionHeader("MF Assist")
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
    // Both onLens and onToggleTeleconverter refuse mid-REC deeper in the ViewModel (a full optics-
    // generation reopen — the afocal 180° flip — would tear the recording); these rows used to stay
    // visually hot and only silently no-op (a "Stop REC first" toast) on tap, inconsistent with My
    // Menu's dimmed-and-guarded quick-Fn rows (3825ae2). Both are also rear-only optics doors
    // (backOpticsDoorRefusal): on the selfie route they must dim like the viewfinder's
    // TeleChip/FocalRail go GONE — a bright row whose refusal lives only in a toast is the same
    // anti-pattern.
    val rearRoute = state.facing == CameraFacing.BACK
    val recordingMutable = !state.isRecording
    val rearOpticsMutable = recordingMutable && rearRoute
    TabTitle("Lens")
    SectionHeader("Optics")
    // Lens picks are ZOOM PRESETS on the seamless logical camera — they do NOT bundle the
    // teleconverter. TELE stays on only when it already is AND the pick is its 3× host lens; the
    // separate toggle below pins converter shooting (afocal 180° flip, standalone 3× camera).
    SegmentedSelector(
        label = "Lens",
        options = LensChoice.entries,
        selected = state.lens,
        labelFor = ::lensLabel,
        onSelect = actions::onLens,
        enabled = rearOpticsMutable,
    )
    Text(
        if (rearRoute) lensFocalCaption(state.lens, state.teleconverterMode) else "Rear camera only.",
        color = CameraColors.TextSecondary,
        style = MaterialTheme.typography.labelSmall,
    )
    ToggleRow(
        label = "Teleconverter",
        checked = state.teleconverterMode,
        onCheckedChange = actions::onToggleTeleconverter,
        enabled = rearOpticsMutable,
    )
    Text(
        if (rearRoute) "3× lens only." else "Rear camera only.",
        color = CameraColors.TextSecondary,
        style = MaterialTheme.typography.labelSmall,
    )

    // Stabilization lives here with the rest of the optics — it does not need its own menu tab
    // (feedback). HAL OIS+EIS path; OIS physically cuts per-frame motion blur at 300 mm.
    SectionHeader("Stabilization")
    SegmentedSelector(
        label = "Mode",
        options = VideoStabMode.entries,
        selected = state.videoStabMode,
        labelFor = { it.label },
        onSelect = actions::onVideoStabMode,
        // Same REC guard as the Lens/TC rows above (CR4-6): onVideoStabMode refuses mid-REC with
        // a toast, so a visually-hot selector here silently no-oped while its siblings greyed out.
        enabled = recordingMutable,
    )
    val stabCaption = when (state.videoStabMode) {
        VideoStabMode.OFF -> "Off"
        VideoStabMode.STANDARD -> "OIS+EIS"
        VideoStabMode.ENHANCED -> "OIS+EIS, crop"
    }
    Text(stabCaption, color = CameraColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
    if (state.caps?.oisAvailable == true) {
        ToggleRow(label = "Photo OIS", checked = state.controls.oisEnabled, onCheckedChange = actions::onToggleOis)
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
            label = "Recording",
            valueLabel = "Settings locked",
        )
    }

    // Codecs are limited to what MediaCodecList actually advertises a muxable HW encoder for
    // (HEVC/AVC on this SoC).
    val codecOptions = remember { EncoderCaps.availableCodecs().ifEmpty { listOf(VideoCodec.HEVC, VideoCodec.AVC) } }
    SectionHeader("Recording Format")
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
    val resolutionOptions = caps?.let {
        if (state.openGate) it.openGateVideoSizes else it.availableVideoSizes
    }.orEmpty()
    if (resolutionOptions.isEmpty()) {
        Text(
            "No supported resolution.",
            color = CameraColors.Record,
            style = MaterialTheme.typography.labelSmall,
        )
    } else {
        SegmentedSelector(
            label = "Resolution",
            options = resolutionOptions,
            selected = state.videoResolution,
            labelFor = ::videoResolutionLabel,
            onSelect = actions::onVideoResolution,
            enabled = recordingMutable,
        )
    }

    // Frame rates gated per-resolution by real caps: normal rates need the camera to advertise the
    // integer fps (24/30/60 here), and drop-frame variants (23.976/29.97/59.94) ride their integer
    // parent. FPS_120/session machinery remains dormant for diagnostics: availableFor excludes it
    // unconditionally because the constrained high-speed session SIGABRTs this HAL. 8K is capped ≤30.
    val fpsOptions = VideoFrameRate.availableFor(caps, state.videoResolution, codec)
    if (fpsOptions.isEmpty()) {
        Text(
            "No supported frame rate for this setup.",
            color = CameraColors.Record,
            style = MaterialTheme.typography.labelSmall,
        )
    } else {
        SegmentedSelector(
            label = "FPS",
            options = fpsOptions,
            selected = state.videoFrameRate,
            labelFor = ::videoFrameRateLabel,
            onSelect = actions::onVideoFrameRate,
            enabled = recordingMutable,
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
    // Transfer is part of the encoded image format, so keep it with codec/rate controls instead of
    // below the unrelated audio controls.
    TransferSelector(
        transfer = state.transfer,
        onTransfer = actions::onTransfer,
        enabled = codec == VideoCodec.HEVC && recordingMutable,
    )

    SectionHeader("Audio")
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
        label = "Route",
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
    LabeledSlider(
        label = "Gain",
        valueLabel = formatZoomMultiplier(state.audioGain),
        value = state.audioGain,
        onValueChange = actions::onAudioGain,
        valueRange = 0f..2f,
        enabled = state.recordAudio && recordingMutable,
    )
}

@Composable
private fun ProcessingTab(state: CameraUiState, actions: CameraActions) {
    val controls = state.controls
    // remember(): see PERF4-8 note in ExposureColorTab.
    val availability = remember(state.caps, controls) { controlAvailability(state.caps?.controlCapabilities(), controls) }
    TabTitle("Image")
    SectionHeader("Processing")
    SegmentedSelector(
        label = "Sharpness",
        options = availability.edgeModes,
        selected = controls.edge,
        labelFor = ::processingLevelLabel,
        onSelect = actions::onEdge,
        enabled = availability.edgeModes.size > 1,
    )
    SegmentedSelector(
        label = "NR",
        options = availability.noiseReductionModes,
        selected = controls.noiseReduction,
        labelFor = ::processingLevelLabel,
        onSelect = actions::onNoiseReduction,
        enabled = availability.noiseReductionModes.size > 1,
    )
    SegmentedSelector(
        label = "Color",
        options = availability.colorEffects,
        selected = controls.colorEffect,
        labelFor = ::colorEffectLabel,
        onSelect = actions::onColorEffect,
        enabled = availability.colorEffects.size > 1,
    )
}

@Composable
private fun AssistsTab(state: CameraUiState, actions: CameraActions) {
    TabTitle("Assist")
    SectionHeader("Monitor")
    // Gamma Display Assist (Sony): only meaningful while the Gamma is a log profile — the monitor
    // shows the normal image, the recorded file stays log.
    ToggleRow(
        label = "Gamma Disp. Assist",
        checked = state.gammaAssist,
        onCheckedChange = actions::onToggleGammaAssist,
        enabled = state.transfer.isLog,
    )
    SegmentedSelector(
        label = "Frame Lines",
        options = FrameLineType.entries,
        selected = state.frameLines,
        labelFor = { it.label },
        onSelect = actions::onFrameLines,
    )
    SectionHeader("Exposure Aids")
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
    SectionHeader("Framing")
    SegmentedSelector(
        label = "Grid",
        options = GridType.entries,
        selected = state.grid,
        labelFor = ::gridTypeLabel,
        onSelect = actions::onGridType,
    )
    ToggleRow(label = "Level", checked = state.level, onCheckedChange = actions::onToggleLevel)
    SectionHeader("Focus Aids")
    ToggleRow(label = "Punch-In", checked = state.punchIn, onCheckedChange = actions::onTogglePunchIn)
    SectionHeader("TELE")
    // Loupe Overview is a same-stream full-frame reference, never an automatic 1x camera feed.
    // Exact predicate: enabled + Photo + 4:3 + TELE + active punch-in. Default remains off.
    ToggleRow(label = "Loupe Overview", checked = state.teleFinder, onCheckedChange = actions::onToggleTeleFinder)
}

@Composable
private fun AdvancedTab(state: CameraUiState, actions: CameraActions) {
    val context = LocalContext.current
    TabTitle("Setup")
    SectionHeader("App")
    LabelValueRow(
        label = "Privacy Policy",
        valueLabel = "View",
        onClick = { openPrivacyPolicy(context) },
    )
    // Trademark attribution for the named log profiles offered in the Video tab — a legal
    // footnote, deliberately non-interactive and dim (small text like SectionHeader).
    Text(
        "S-Log is a trademark of Sony Group Corporation. ARRI and LogC are trademarks of ARRI AG. " +
            "This app is not affiliated with or endorsed by Sony or ARRI.",
        color = CameraColors.TextSecondary,
        style = MaterialTheme.typography.labelSmall,
    )
    // Bundled-typeface attribution (SIL OFL requires the license to travel with the font; the
    // full text ships in the repo at docs/licenses/inter-OFL.txt).
    Text(
        "UI typeface: Inter, © The Inter Project Authors, SIL Open Font License 1.1.",
        color = CameraColors.TextSecondary,
        style = MaterialTheme.typography.labelSmall,
    )
    SectionHeader("Startup")
    ToggleRow(
        label = "Remember Settings",
        checked = state.rememberSettings,
        onCheckedChange = actions::onToggleRememberSettings,
    )
    ToggleRow(
        label = "Remember Lens",
        checked = state.preserveLensSelection,
        onCheckedChange = actions::onTogglePreserveLensSelection,
        enabled = state.rememberSettings,
    )
    ToggleRow(
        label = "Remember Teleconverter",
        checked = state.preserveTeleconverter,
        onCheckedChange = actions::onTogglePreserveTeleconverter,
        enabled = state.rememberSettings,
    )
    SectionHeader("Photo Fn")
    FnSlotEditor(selected = state.photoFnSlots, onSet = actions::onSetPhotoFnSlots)
    SectionHeader("Video Fn")
    FnSlotEditor(selected = state.videoFnSlots, onSet = actions::onSetVideoFnSlots)
    SectionHeader("My Menu")
    FnSlotEditor(selected = state.myMenuSlots, onSet = actions::onSetMyMenuSlots)
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
    // A stale diagnostic override must remain recoverable, but normal release users should not see
    // an inert implementation-detail row.
    state.cameraOverrideId?.let { cameraId ->
        LabelValueRow(
            label = "Camera ID",
            valueLabel = cameraId,
            onClick = { actions.onCameraOverride(null) },
        )
    }
}

@Composable
private fun FnSlotEditor(selected: List<FnSlot>, onSet: (List<FnSlot>) -> Unit) {
    val normalized = selected.distinct().take(8)
    if (normalized.isNotEmpty()) {
        normalized.forEachIndexed { index, slot ->
            FnSlotOrderRow(
                slot = slot,
                index = index,
                count = normalized.size,
                onMoveUp = {
                    if (index > 0) onSet(normalized.toMutableList().apply {
                        val item = removeAt(index)
                        add(index - 1, item)
                    })
                },
                onMoveDown = {
                    if (index < normalized.lastIndex) onSet(normalized.toMutableList().apply {
                        val item = removeAt(index)
                        add(index + 1, item)
                    })
                },
                onRemove = { onSet(normalized.filterNot { it == slot }) },
            )
        }
    }
    val available = FnSlot.entries.filterNot { it in normalized }
    available.forEach { slot ->
        ToggleRow(
            label = "Add ${fnSlotLabel(slot)}",
            checked = false,
            onCheckedChange = { enabled ->
                if (enabled && normalized.size < 8) onSet(normalized + slot)
            },
            enabled = normalized.size < 8,
        )
    }
}

@Composable
private fun FnSlotOrderRow(
    slot: FnSlot,
    index: Int,
    count: Int,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "%02d".format(Locale.US, index + 1),
            color = CameraColors.TextSecondary,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(26.dp),
        )
        Text(
            text = fnSlotLabel(slot),
            color = CameraColors.TextPrimary,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f),
        )
        MiniTextButton(text = "Up", enabled = index > 0, onClick = onMoveUp)
        MiniTextButton(text = "Down", enabled = index < count - 1, onClick = onMoveDown)
        MiniTextButton(text = "Remove", enabled = true, onClick = onRemove)
    }
}

@Composable
private fun MiniTextButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val fg = if (enabled) CameraColors.TextPrimary else CameraColors.TextSecondary.copy(alpha = 0.45f)
    // Outer box carries the click at a 48 dp minimum touch target; the inner pill stays the compact
    // VISUAL (the Fn-slot editor packs Up/Down/Remove into a tight row), so the look is unchanged
    // while the hit area meets the a11y floor — same outer-box pattern as TeleChip/DialChip.
    Box(
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = if (enabled) 0.09f else 0.04f))
                .padding(horizontal = 9.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, color = fg, style = MaterialTheme.typography.labelSmall)
        }
    }
}

internal fun fnSlotValue(slot: FnSlot, state: CameraUiState): String {
    val c = state.controls
    return when (slot) {
        FnSlot.EXPOSURE_MODE -> c.exposureMode.letter
        FnSlot.FOCUS -> focusModeLabel(c.focusMode)
        FnSlot.SHUTTER -> when {
            c.exposureMode == ExposureMode.PROGRAM -> "Auto ${autoShutterText(state)}"
            c.autoShutterDriven -> "Auto ${formatShutterSpeed(c.exposureTimeNs)}"
            c.shutterMode == ShutterMode.ANGLE -> "%.0f°".format(Locale.US, c.shutterAngle)
            else -> formatShutterSpeed(c.exposureTimeNs)
        }
        FnSlot.ISO -> when {
            c.exposureMode == ExposureMode.PROGRAM -> "Auto ${autoIsoText(state)}"
            c.autoIsoDriven -> "Auto ${c.iso}"
            else -> c.iso.toString()
        }
        FnSlot.WB -> if (c.wbMode == WbMode.MANUAL) "${c.wbKelvin}K" else wbModeLabel(c.wbMode)
        FnSlot.EV -> "%+.1f".format(Locale.US, evCompStops(state))
        // Same main-relative display scale and formatter as the HUD pill and persistent Fn row.
        FnSlot.ZOOM -> formatDisplayZoom(
            c.zoomRatio,
            state.teleconverterMode,
            state.caps?.equivalentFocalMm,
            frontFacing = state.facing == com.hletrd.findx9tele.camera.CameraFacing.FRONT,
        )
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
        FnSlot.TELECONVERTER -> if (state.teleconverterMode) "300 mm" else "Off"
        FnSlot.OPEN_GATE -> if (state.openGate) "4:3" else "Off"
        FnSlot.FRAME_LINES -> state.frameLines.label
    }
}

internal fun performQuickFn(slot: FnSlot, state: CameraUiState, actions: CameraActions) {
    // Defense in depth for EVERY caller surface (Fn overlay, My Menu, Recent): the visual
    // enabled/dimmed state lives at the row, but the action itself must refuse too — My Menu rows
    // used to invoke this unguarded, making them the one path that could toggle the teleconverter
    // (the afocal 180° flip) or the transfer curve mid-recording.
    if (!quickFnEnabled(slot, state)) return
    // Plain call (not remember): this runs once per quick-Fn TAP, not per recomposition.
    val availability = controlAvailability(state.caps?.controlCapabilities(), state.controls)
    when (slot) {
        FnSlot.EXPOSURE_MODE -> actions.onExposureMode(
            nextAvailable(state.controls.exposureMode, availability.exposureModes),
        )
        FnSlot.FOCUS -> actions.onFocusMode(nextAvailable(state.controls.focusMode, availability.focusModes))
        FnSlot.SHUTTER -> if (availability.shutterDialEnabled) actions.onShutterMode(
            if (state.controls.shutterMode == ShutterMode.SPEED) ShutterMode.ANGLE else ShutterMode.SPEED,
        )
        FnSlot.ISO -> actions.onExposureMode(
            if (state.controls.exposureMode == ExposureMode.ISO) ExposureMode.PROGRAM
            else if (ExposureMode.ISO in availability.exposureModes) ExposureMode.ISO
            else nextAvailable(state.controls.exposureMode, availability.exposureModes),
        )
        FnSlot.WB -> actions.onWbMode(nextAvailable(state.controls.wbMode, availability.wbModes))
        FnSlot.EV -> if (availability.evDialEnabled) actions.onExposureCompensation(0)
        FnSlot.ZOOM -> if (availability.zoomDialEnabled) actions.onZoomRatio(1f)
        FnSlot.STABILIZATION -> actions.onVideoStabMode(nextVideoStabMode(state.videoStabMode))
        FnSlot.DRIVE -> actions.onDriveMode(nextDriveMode(state.driveMode))
        FnSlot.METERING -> actions.onMeteringMode(
            nextAvailable(state.controls.meteringMode, availability.meteringModes),
        )
        FnSlot.PEAKING -> actions.onTogglePeaking(!state.focusPeaking)
        FnSlot.ZEBRA -> actions.onToggleZebra(!state.zebra)
        FnSlot.TRANSFER -> actions.onTransfer(nextTransfer(state.transfer))
        FnSlot.AUDIO_SCENE -> actions.onAudioScene(nextAudioScene(state.audioScene))
        FnSlot.GRID -> actions.onGridType(nextGridType(state.grid))
        FnSlot.LEVEL -> actions.onToggleLevel(!state.level)
        FnSlot.PUNCH_IN -> actions.onTogglePunchIn(!state.punchIn)
        FnSlot.TELECONVERTER -> actions.onToggleTeleconverter(!state.teleconverterMode)
        FnSlot.OPEN_GATE -> actions.onToggleOpenGate(!state.openGate) // gate lives in quickFnEnabled
        FnSlot.FRAME_LINES -> actions.onFrameLines(nextFrameLine(state.frameLines))
    }
}

// (The next* cycle helpers and auto-exposure readout text live in ControlCycles.kt — shared with
// ManualDials/CameraScreen so the cycle orders can't drift between surfaces.)

private fun openPrivacyPolicy(context: Context) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, PRIVACY_POLICY_URL.toUri())) }
}

private const val PRIVACY_POLICY_URL = "https://hletrd.github.io/telecam-pro/privacy-policy/"
