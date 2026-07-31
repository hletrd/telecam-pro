package me.hletrd.telecampro.ui.controls

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
import androidx.core.net.toUri
import me.hletrd.telecampro.camera.AfSpotSize
import me.hletrd.telecampro.camera.ExposureMode
import me.hletrd.telecampro.camera.FrameLineType
import me.hletrd.telecampro.camera.ExposureStep
import me.hletrd.telecampro.camera.AspectRatio
import me.hletrd.telecampro.camera.AudioScene
import me.hletrd.telecampro.camera.AudioInputPreference
import me.hletrd.telecampro.camera.BitrateLevel
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.CameraFacing
import me.hletrd.telecampro.camera.CaptureMode
import me.hletrd.telecampro.camera.ColorTransfer
import me.hletrd.telecampro.camera.DriveMode
import me.hletrd.telecampro.camera.FnSlot
import me.hletrd.telecampro.camera.HardwareKeyAction
import me.hletrd.telecampro.camera.PeakingColor
import me.hletrd.telecampro.camera.PeakingLevel
import me.hletrd.telecampro.camera.ZebraLevel
import me.hletrd.telecampro.camera.FocusMode
import me.hletrd.telecampro.camera.GridType
import me.hletrd.telecampro.camera.LensChoice
import me.hletrd.telecampro.camera.MemorySlot
import me.hletrd.telecampro.camera.ShutterMode
import me.hletrd.telecampro.camera.MAX_TELECONVERTER_MAGNIFICATION
import me.hletrd.telecampro.camera.MIN_TELECONVERTER_MAGNIFICATION
import me.hletrd.telecampro.camera.ShutterTimer
import me.hletrd.telecampro.camera.PhoneModel
import me.hletrd.telecampro.camera.VideoCodec
import me.hletrd.telecampro.camera.VideoStabMode
import me.hletrd.telecampro.camera.VideoFrameRate
import me.hletrd.telecampro.camera.WbMode
import me.hletrd.telecampro.camera.ControlAvailability
import me.hletrd.telecampro.camera.controlAvailability
import me.hletrd.telecampro.camera.controlCapabilities
import me.hletrd.telecampro.camera.hiResToggleEnabled
import me.hletrd.telecampro.camera.videoBitRate
import me.hletrd.telecampro.camera.rawSelectable
import me.hletrd.telecampro.video.EncoderCaps
import me.hletrd.telecampro.ui.CameraActions
import me.hletrd.telecampro.ui.formatZoomMultiplier
import me.hletrd.telecampro.ui.theme.CameraColors
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

// (proSheetTabSelection/proSheetUsesSideLayout and the per-slot quick-Fn readout/dispatch live in
// FnQuickActions.kt, beside the ControlCycles.kt cycle helpers — non-composable so they stay
// host-testable apart from Compose emission; this file owns only Compose emission and the
// Intent-launching privacy-policy opener.)

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
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Menu", color = CameraColors.TextPrimary, style = MaterialTheme.typography.titleLarge)
                CloseButton(
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(closeFocusRequester),
                )
            }

            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                TabRail(selected = selectedTab, onSelect = { selectedTab = it; onTabChange(it) })
                // (The 0.08 rail divider that used to sit here is gone: on Pill it resolved to
                // #2E2E2F — below the visibility threshold — and the selected rail item already
                // carries a 10% block PLUS an accent bar PLUS a colour change.)
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
                            // A trailing Spacer inside a spacedBy column made the bottom inset
                            // 20 + 8 against a 16 dp top; the column states its own bottom instead.
                            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
                        // The page's BASE gap, not its only gap. At a uniform 20 dp a caption sat as
                        // far from the control it explains as a new section did from the last one;
                        // the rhythm is now 4 dp caption-to-control (Captioned), 12 dp between
                        // sibling controls, and 24 dp above a SectionHeader (which adds its own 12).
                        verticalArrangement = Arrangement.spacedBy(12.dp),
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
                // The shared raised-block fill; this pill used to spell it 0.08, one hundredth off the
                // 0.09 its two siblings (MiniTextButton, FnOverlayTile) draw.
                .background(CameraColors.Block),
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
            // One-off, and deliberately not CameraColors.Block (0.09): that token's KDoc names this
            // exact site as one it must not swallow. A rail item is SELECTED, an idle settings row
            // (0.05, MemoryPresetRow) is merely present, and a Block is a raised tappable slab —
            // three roles that would become one grey if they shared a number.
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
        Spacer(modifier = Modifier.height(4.dp))
        Canvas(modifier = Modifier.size(20.dp)) { drawTabIcon(tab, fg) }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = tab.label,
            color = fg,
            // labelSmall (11 sp), not a hand-rolled 10 sp: this is the sheet's PRIMARY navigation,
            // not a badge, and 10 sp was the smallest permanent text in the app. The longest label
            // ("Exposure") measures ~46 dp at 11 sp inside the fixed 68 dp box, so nothing wraps.
            // The weight is unconditional: swapping it on selection reflows that fixed box on every
            // tab change, and the accent bar plus the color change already carry the selection.
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Medium,
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
    Text(text, color = CameraColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
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
            // The app's own null token, not an instruction: this slot holds the bank's SUMMARY, and
            // the Save chip immediately to its right already says what to do about an empty one.
            summary = if (saved) summary else "--",
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
            // The idle-row surface: one-off at 0.05, the quietest wash in the app and the other site
            // CameraColors.Block's KDoc explicitly refuses to absorb. (The 0.18 alongside it is an
            // AMBER tint, not the white AffordanceEdge — same number, unrelated colour.)
            .background(if (active) CameraColors.ManualActive.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f))
            // The active row is already tinted amber; a neutral white border over that tint read as
            // a smudge rather than a selection. Idle takes the shared decorative hairline.
            .border(
                1.dp,
                if (active) CameraColors.ManualActive.copy(alpha = 0.35f) else CameraColors.Hairline,
                RoundedCornerShape(8.dp),
            )
            // Role + label on the primary recall action (cycle-6 D-11): without them the row read
            // as an anonymous clickable while its Save chip was fully labeled.
            .clickable(
                enabled = saved && !locked,
                role = Role.Button,
                onClickLabel = "Recall ${slot.label}",
                onClick = onRecall,
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = slot.label,
            color = if (active) CameraColors.ManualActive else CameraColors.TextSecondary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(36.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, color = CameraColors.TextPrimary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text(summary, color = CameraColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
        }
        // DES4-3: the d875eea 48 dp sweep covered the three shared selector components; this
        // standalone action chip (writes an MR bank) was left bare at ~32 dp.
        MinTouchTarget48 {
            FilterChip(
                selected = false,
                onClick = onSave,
                enabled = !locked,
                label = {
                    Text(
                        if (saved) "Update" else "Save",
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                colors = pixelChipColors(),
                border = pixelChipBorder(false, !locked),
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
        // Neither pure session truth nor pure device capability — see [rawSelectable]. Session truth
        // alone made the chip unreachable on the logical photo route (the route only moves BECAUSE
        // DNG is chosen); capability alone left it live in a 10-bit video session that drops both
        // still readers by design.
        rawAvailable = rawSelectable(
            deviceSupportsRaw = state.caps?.supportsRaw == true,
            rawInSession = state.photoSessionOutputs.raw,
            videoMode = state.mode == CaptureMode.VIDEO,
            hiResSession = state.photoSessionOutputs.hiRes,
            frontFacing = state.facing == CameraFacing.FRONT,
        ),
        // Session truth still drives the CAPTION, so the sheet can say RAW is not in force yet
        // without disabling the control that brings it into force.
        rawInSession = state.photoSessionOutputs.raw,
        onSetPhotoFormats = actions::onSetPhotoFormats,
        videoMode = state.mode == CaptureMode.VIDEO,
    )
    // Hi-res still: visible only when the SELECTED camera is a standalone route that actually
    // advertises a full-sensor size (the logical seamless camera never qualifies — its gralloc
    // rejects big blobs). Both facts arrive as ONE projected route fact, and enablement joins the
    // live mode/aspect axes through the shared hiResAdmitted predicate (cycle-6 architect F3 — the
    // old inline conjunction here was a third encoding of the admission axes). The row shows the
    // INTENT; the OSD HR tag shows accepted session truth.
    if (availability.hiResAdvertisedStandalone) {
        // The advertised dimensions are display copy only; every admission axis stays projected.
        // Constraints only, in the app's own ` · ` register: "Full-sensor still" restated the row
        // label directly above, and "Reduces low-light quality" was an editorial verdict on a
        // setting the operator just chose (docs/UX_POLICY.md). What earns the line is the three
        // SILENT consequences of the switch.
        val hiResCaption = caps?.hiResJpegSize?.let { hiResSize ->
            val mp = (hiResSize.width.toLong() * hiResSize.height / 1_000_000).toInt()
            "${hiResSize.width}×${hiResSize.height} · $mp MP · JPEG, 4:3, no RAW"
        }
        Captioned(hiResCaption) {
            ToggleRow(
                label = "High Resolution",
                checked = state.hiResStill,
                onCheckedChange = actions::onToggleHiResStill,
                enabled = hiResToggleEnabled(
                    availability = availability,
                    videoMode = state.mode == CaptureMode.VIDEO,
                    aspect = state.aspectRatio,
                    recording = state.isRecording,
                ),
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
        // TELE shows the converter-equivalent scale (13–60× on the kit optic) but writes the
        // lens-local ratio; the scale follows whichever converter the user declared.
        val zBase = if (state.teleconverterMode) {
            me.hletrd.telecampro.camera.teleDisplayBase(state.teleconverterMagnification)
        } else {
            1f
        }
        val loDisplay = range.lower * zBase
        val zHi = if (state.teleconverterMode) {
            minOf(range.upper * zBase, me.hletrd.telecampro.camera.TELE_MAX_DISPLAY_ZOOM)
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
    // "Release", the Sony group name for drive mode + interval + self-timer. A bare "Drive" header
    // would only echo the row directly under it, but WITHOUT a header here the three release rows
    // sat under "Format" and read as output-format settings.
    SectionHeader("Release")
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
    LabeledSlider(
        label = "ISO",
        valueLabel = controls.iso.toString(),
        value = controls.iso.toFloat().coerceIn(isoRange.lower.toFloat(), isoRange.upper.toFloat()),
        onValueChange = { actions.onIso(it.roundToInt()) },
        valueRange = isoRange.lower.toFloat()..isoRange.upper.toFloat(),
        enabled = availability.isoDialEnabled &&
            (controls.exposureMode == ExposureMode.ISO || controls.exposureMode == ExposureMode.MANUAL),
    )

    SegmentedSelector(
        label = "Metering",
        options = availability.meteringModes,
        selected = controls.meteringMode,
        labelFor = ::meteringModeLabel,
        onSelect = actions::onMeteringMode,
        enabled = availability.meteringModes.size > 1,
    )

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
    // Both refusal branches are word for word the toast the SAME refusal already emits from the
    // ViewModel (onCaptureCustomWb guards these two conditions in this order): the caption and the
    // toast are one instruction seen twice, not two instructions. "Camera reconfiguring" is also
    // the app's single name for !cameraReady everywhere else.
    Captioned(
        if (customWbCaptureEnabled) {
            "Aim at a white or gray card"
        } else if (!state.cameraReady) {
            "Camera reconfiguring"
        } else {
            "Use Auto WB with AWB Lock off"
        },
    ) {
        // DES4-3: standalone action chip missed by the d875eea sweep — same 48 dp wrapper.
        MinTouchTarget48 {
            FilterChip(
                selected = controls.wbMode == WbMode.CUSTOM,
                onClick = actions::onCaptureCustomWb,
                enabled = customWbCaptureEnabled,
                label = { Text("Capture Custom WB", style = MaterialTheme.typography.labelMedium) },
                colors = pixelChipColors(),
                border = pixelChipBorder(controls.wbMode == WbMode.CUSTOM, customWbCaptureEnabled),
            )
        }
    }
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
        // "None", not "No point": in a value slot that phrase reads as "there is no point". The
        // stateDescription below is the one that gets to be a sentence.
        valueLabel = if (state.tapFocusHeld) "Reset" else "None",
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
    // Every row in this section shares ONE gate (rearOpticsMutable), so the precondition is stated
    // ONCE for the section instead of once per dimmed row — the selfie route used to stack the same
    // dim sentence three times, 20 dp apart, under rows that were already grey.
    if (!rearRoute) {
        Text(
            "Rear camera only",
            color = CameraColors.TextSecondary,
            style = MaterialTheme.typography.labelSmall,
        )
    }
    Captioned(
        if (rearRoute) lensFocalCaption(state.lens, state.teleconverterMode, state.teleconverterFocalMm) else null,
    ) {
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
    }
    Captioned(if (rearRoute) "3× lens only" else null) {
        ToggleRow(
            label = "Teleconverter",
            checked = state.teleconverterMode,
            onCheckedChange = actions::onToggleTeleconverter,
            enabled = rearOpticsMutable,
        )
    }
    // The converter setting is a PAIR, asked in order: the phone decides which kits clamp on, the
    // converter decides the magnification. Dropdowns, not chips — the flat catalog of every brand's
    // optics read as a scrolling smear (user-rejected). Only the PHONE is ever resolved
    // automatically; passive glass cannot announce itself.
    DropdownRow(
        label = "Phone",
        options = PhoneModel.entries,
        selected = state.phoneModel,
        labelFor = { it.label },
        onSelect = actions::onPhoneModel,
        enabled = rearOpticsMutable,
    )
    // The computed focal for THIS phone's host lens, never the preset's product number: a "ZEISS
    // 200 mm" is 2.35x glass, and 2.35x on this phone's 70 mm periscope is 165 mm.
    val converterFocal = "${formatFocalMm(state.teleconverterFocalMm)} equiv."
    // Converter and Magnification are ONE setting (the slider exists only for a custom converter),
    // and the caption states what the pair resolves to — so all three bind as one block.
    Captioned(
        if (!rearRoute) {
            null
        } else {
            when {
                // Only the PHONE is ever detected, and only when Build.MODEL actually matched this
                // boot — a default that happens to be right is not a detection. Nothing is appended
                // otherwise: the earlier copy told the user to "set this to the optic you mounted",
                // which fired precisely BECAUSE they had just made a deliberate pick, so it read as
                // a warning about a correct action (this app does not nag — docs/UX_POLICY.md).
                // Two independent facts, joined by the app's own ` · ` — the old sentence period
                // ran them together right after the "equiv." abbreviation dot.
                state.phoneModelDetected -> "$converterFocal · ${state.phoneModel.label} detected"
                else -> converterFocal
            }
        },
    ) {
        DropdownRow(
            label = "Converter",
            // Narrowed to this phone's kits plus the fits-anything entries, so a converter that
            // cannot physically clamp on is not offerable in the first place.
            options = state.phoneModel.converters(),
            selected = state.teleconverterProfile,
            labelFor = { it.label },
            onSelect = actions::onTeleconverterProfile,
            enabled = rearOpticsMutable,
        )
        if (state.teleconverterProfile.isCustom) {
            LabeledSlider(
                label = "Magnification",
                valueLabel = "%.2f×".format(Locale.US, state.teleconverterMagnification),
                value = state.teleconverterCustomMagnification,
                onValueChange = actions::onTeleconverterCustomMagnification,
                valueRange = MIN_TELECONVERTER_MAGNIFICATION..MAX_TELECONVERTER_MAGNIFICATION,
                enabled = rearOpticsMutable,
            )
        }
    }

    // Stabilization lives here with the rest of the optics — it does not need its own menu tab
    // (feedback). HAL OIS+EIS path; OIS physically cuts per-frame motion blur at 300 mm.
    SectionHeader("Stabilization")
    Captioned(
        when (state.videoStabMode) {
            VideoStabMode.OFF -> "Off"
            VideoStabMode.STANDARD -> "OIS+EIS"
            VideoStabMode.ENHANCED -> "OIS+EIS, crop"
        },
    ) {
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
    }
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
        // A capability caption is [CameraColors.TextSecondary], NOT the recording red. The review's
        // counter-argument for red — "this one REPLACES its selector, so it is a harder dead-end than
        // the greyed siblings under live chips" — does not survive PhotoFormatToggles: its "Still
        // capture unavailable" caption fires exactly when all three format chips are disabled, i.e. it
        // is every bit as terminal, and it is already grey. So red here was ONE class spelled two ways,
        // in the one tab that also shows the real recording red mid-REC. Greying it also raises
        // contrast on the opaque sheet surface (6.35:1 vs red's 4.80:1 — see HudContrastTest).
        Text(
            "No supported resolution",
            color = CameraColors.TextSecondary,
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
        // Same capability-caption colour rule as the resolution branch above.
        Text(
            "No supported frame rate",
            color = CameraColors.TextSecondary,
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
    // Resolved encoder settings summary, e.g. "HEVC · 4K · 30p · 84 Mbps" — the exact computed
    // bitrate. The rate carries its "p" like the OSD's spec line does: in a list next to "4K" a
    // bare number reads as another dimension.
    val mbps = videoBitRate(
        state.videoResolution.width, state.videoResolution.height,
        state.videoFrameRate.encoderRate,
        me.hletrd.telecampro.camera.effectiveBpp(state.bitrateLevel, codec), codec,
    ) / 1_000_000
    LabelValueRow(
        label = "Encoder",
        valueLabel = "${videoCodecLabelShort(codec)} · ${videoResolutionLabel(state.videoResolution)} · ${state.videoFrameRate.label}p · $mbps Mbps",
    )
    // One terse source caveat (cycle-6 A26/D-08): the named log curves are real math, but they bake
    // onto the already-tone-mapped SDR stream — no scene-referred latitude is recovered (CLAUDE.md
    // "must not be marketed as such"). Same caption idiom as the hi-res/stabilization rows. Gated on
    // a non-SDR selection because the caveat is about a CURVE: printed under Transfer = SDR it
    // asserted a curve was applied where none is.
    // "SDR stream" stopped being true when video gained a 10-bit session; the honesty point was
    // never the bit depth but that the ISP has ALREADY tone-mapped what the curve is applied to.
    Captioned(
        if (state.transfer != ColorTransfer.SDR) "Applied to the camera's already tone-mapped stream" else null,
    ) {
        // Transfer is part of the encoded image format, so keep it with codec/rate controls instead
        // of below the unrelated audio controls.
        TransferSelector(
            transfer = state.transfer,
            onTransfer = actions::onTransfer,
            enabled = codec == VideoCodec.HEVC && recordingMutable,
        )
    }

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
        // NOT formatZoomMultiplier: that formatter is documented as the shared ZOOM typography, and
        // coupling a gain readout to it means any future zoom-only change (a suffix, a clamp, a
        // locale rule) silently reformats audio gain.
        valueLabel = "%.1f×".format(Locale.US, state.audioGain),
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
    // "Loupe" app-wide (cycle-6 D-04): Fn chip, key-action label, and LOUPE OSD tag already use it.
    ToggleRow(label = "Loupe", checked = state.punchIn, onCheckedChange = actions::onTogglePunchIn)
    // "Teleconverter", not the OSD's TELE tag: a viewfinder tag borrowed into a menu shouts next to
    // 19 Title Case siblings.
    SectionHeader("Teleconverter")
    // Every sibling toggle with non-obvious preconditions carries one of these (UX_POLICY: menu rows
    // are the sanctioned place for that copy, never the viewfinder). Without it, toggling this in
    // video, at 16:9, or with the loupe off does nothing visible and says nothing about why.
    // The magnification clause is NOT "Teleconverter" any more: the finder is offered at 3× on any
    // lens, because what it depends on is magnifying past the delivered field, not the accessory.
    // Same rule as the header four lines up: the caption's other three tokens are menu names, so the
    // sole borrowed OSD tag spelled itself out too.
    // Says what the aid IS, not the four gates it passes through. The dotted condition list read
    // like the predicate printed out, and it went stale the moment video qualified (2026-07-29) —
    // a caption that enumerates its own preconditions has to be re-edited every time one moves.
    Captioned("A corner view of the full frame while the loupe magnifies") {
        // Loupe Overview is a same-stream full-frame reference, never an automatic 1x camera feed.
        // Exact predicate: enabled + active punch-in + (TELE or past the zoom floor), and in PHOTO
        // also 4:3. Default remains off.
        //
        // Gated on the LOUPE specifically, the same way Zebra Level is gated on Zebra: the loupe is
        // this row's parent FEATURE (cycle 4 made it the gate axis — at a steady zoom the overview
        // duplicates the main view ~1:1, so without magnification there is nothing for it to show),
        // and its switch lives under a DIFFERENT section header, which is what makes the dependency
        // invisible from here. Ungated, this row would report "On" while provably drawing nothing
        // and saying nothing about why — the caption alone is passive and easy to read past.
        // Deliberately NOT gated on Photo/4:3/Teleconverter as well: those three are transient
        // shooting states, so gating on them would flicker the row in and out mid-shoot and block
        // pre-arming a persisted preference. The caption carries those; the switch carries the
        // parent.
        ToggleRow(
            label = "Loupe Overview",
            checked = state.teleFinder,
            onCheckedChange = actions::onToggleTeleFinder,
            enabled = state.punchIn,
        )
    }
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
    // footnote, deliberately non-interactive and dim. bodySmall, NOT labelSmall: these two are the
    // only multi-sentence PROSE in the sheet, and labelSmall is a label treatment (Medium weight)
    // that renders three sentences as a wall of emphasized micro-text.
    // ARRI marks are registered to the Cine Technik entity, not an "ARRI AG" (cycle-6 DS-8).
    Text(
        "S-Log is a trademark of Sony Group Corporation. LogC is a trademark of " +
            "Arnold & Richter Cine Technik GmbH & Co. Betriebs KG (ARRI). " +
            "This app is not affiliated with or endorsed by Sony or ARRI.",
        color = CameraColors.TextSecondary,
        style = MaterialTheme.typography.bodySmall,
    )
    // Bundled-typeface attribution (SIL OFL requires the license to travel with the font; the
    // full text ships in the repo at docs/licenses/inter-OFL.txt).
    Text(
        "UI typeface: Inter, © The Inter Project Authors, SIL Open Font License 1.1.",
        color = CameraColors.TextSecondary,
        style = MaterialTheme.typography.bodySmall,
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
    // Each shooting list only offers slots that can act in ITS mode (fnSlotAppliesTo). My Menu is a
    // settings surface, not a shooting one, so it keeps the full set.
    FnSlotEditor(selected = state.photoFnSlots, mode = CaptureMode.PHOTO, onSet = actions::onSetPhotoFnSlots)
    SectionHeader("Video Fn")
    FnSlotEditor(selected = state.videoFnSlots, mode = CaptureMode.VIDEO, onSet = actions::onSetVideoFnSlots)
    SectionHeader("My Menu")
    FnSlotEditor(selected = state.myMenuSlots, mode = null, onSet = actions::onSetMyMenuSlots)
    SectionHeader("Keys")
    // This one assignment governs the camera button's FULL press AND the volume keys
    // (MainActivity routes both to it) — say so in the label (cycle-6 D-13).
    SegmentedSelector(
        label = "Full Press / Volume",
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
    // The OPPO quick/action button (system-injected 781). Discrete click — momentary bindings
    // (AEL, Loupe) blip on/off within one press, which is honest to what the key delivers.
    SegmentedSelector(
        label = "Quick Button",
        options = HardwareKeyAction.entries,
        selected = state.quickButtonAction,
        labelFor = ::hardwareKeyActionLabel,
        onSelect = actions::onQuickButtonAction,
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
private fun FnSlotEditor(selected: List<FnSlot>, mode: CaptureMode?, onSet: (List<FnSlot>) -> Unit) {
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
    // A null [mode] means "no shooting mode owns this list" (My Menu) and offers everything.
    val available = FnSlot.entries.filterNot { it in normalized }
        .filter { mode == null || fnSlotAppliesTo(it, mode) }
    available.forEach { slot ->
        // Button-role action row, not a Switch: the old ToggleRow's checked state could never be
        // true (an added slot leaves this list), so TalkBack announced "Off" for what is an add
        // action (cycle-6 D-11). LabelValueRow is the app's plain-action row idiom.
        LabelValueRow(
            label = fnSlotLabel(slot),
            valueLabel = "Add",
            enabled = normalized.size < 8,
            onClick = { if (normalized.size < 8) onSet(normalized + slot) },
            // One list, one left edge: FnSlotOrderRow's 26 dp index column + 8 dp gap indents the
            // ordered names 34 dp, so the available rows directly beneath started at 0 dp and the
            // editor read as two stacked lists. LabelValueRow applies `modifier.fillMaxWidth()`
            // before its clickable, so the row narrows and shifts right with its 48 dp sizeIn and
            // its whole (narrower) surface still tappable.
            modifier = Modifier.padding(start = 34.dp),
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
        // Up to eight identical Up/Down/Remove triples sit in this list and MiniTextButton exports
        // only its bare text, so a TalkBack user heard "Up, button" eight times with no way to tell
        // WHICH slot they were reordering. The visual text stays compact; the spoken action names
        // the slot.
        val name = fnSlotLabel(slot)
        MiniTextButton(text = "Up", clickLabel = "Move $name up", enabled = index > 0, onClick = onMoveUp)
        MiniTextButton(text = "Down", clickLabel = "Move $name down", enabled = index < count - 1, onClick = onMoveDown)
        MiniTextButton(text = "Remove", clickLabel = "Remove $name", enabled = true, onClick = onRemove)
    }
}

@Composable
private fun MiniTextButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    clickLabel: String? = null,
) {
    val fg = if (enabled) CameraColors.TextPrimary else CameraColors.TextSecondary.copy(alpha = 0.45f)
    // Outer box carries the click at a 48 dp minimum touch target; the inner pill stays the compact
    // VISUAL (the Fn-slot editor packs Up/Down/Remove into a tight row), so the look is unchanged
    // while the hit area meets the a11y floor — same outer-box pattern as TeleChip/DialChip.
    Box(
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(enabled = enabled, onClickLabel = clickLabel, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(if (enabled) CameraColors.Block else CameraColors.BlockDisabled)
                // Carve-out from the shared 12/6 pill inset: three of these actions share one row,
                // so only the vertical joins the scale.
                .padding(horizontal = 9.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, color = fg, style = MaterialTheme.typography.labelSmall)
        }
    }
}

// (The next* cycle helpers and auto-exposure readout text live in ControlCycles.kt — shared with
// ManualDials/CameraScreen so the cycle orders can't drift between surfaces.)

private fun openPrivacyPolicy(context: Context) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, PRIVACY_POLICY_URL.toUri())) }
}

private const val PRIVACY_POLICY_URL = "https://hletrd.github.io/telecam-pro/privacy-policy/"
