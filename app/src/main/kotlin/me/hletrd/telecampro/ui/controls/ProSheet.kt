package me.hletrd.telecampro.ui.controls

import me.hletrd.telecampro.R

import android.util.Range
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
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
import androidx.compose.ui.platform.LocalDensity
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
import me.hletrd.telecampro.camera.TeleconverterProfile
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
import me.hletrd.telecampro.ui.CameraActions
import me.hletrd.telecampro.ui.ExternalLaunchOutcome
import me.hletrd.telecampro.ui.ExternalNavigationFailure
import me.hletrd.telecampro.ui.ExternalNavigationRecovery
import me.hletrd.telecampro.ui.ExternalNavigationTarget
import me.hletrd.telecampro.ui.PrivacyPolicyFallbackDialog
import me.hletrd.telecampro.ui.externalNavigationFailure
import me.hletrd.telecampro.ui.launchExternal
import me.hletrd.telecampro.ui.modalFocusBoundary
import me.hletrd.telecampro.ui.formatZoomMultiplier
import me.hletrd.telecampro.ui.resolve
import me.hletrd.telecampro.ui.overlays.photoFormatLabel
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
internal enum class ProSheetTab { MY_MENU, SHOOTING, EXPOSURE, FOCUS, LENS, VIDEO, PROCESSING, ASSISTS, ADVANCED }

@Composable
private fun proSheetTabLabel(tab: ProSheetTab): String = stringResource(
    when (tab) {
        ProSheetTab.MY_MENU -> R.string.settings_tab_my
        ProSheetTab.SHOOTING -> R.string.settings_tab_shoot
        ProSheetTab.EXPOSURE -> R.string.settings_tab_exposure
        ProSheetTab.FOCUS -> R.string.settings_tab_focus
        ProSheetTab.LENS -> R.string.settings_tab_lens
        ProSheetTab.VIDEO -> R.string.settings_tab_video
        ProSheetTab.PROCESSING -> R.string.settings_tab_image
        ProSheetTab.ASSISTS -> R.string.settings_tab_assist
        ProSheetTab.ADVANCED -> R.string.settings_tab_setup
    },
)

@Composable
private fun phoneModelLabel(model: PhoneModel): String = stringResource(when (model) {
    PhoneModel.FIND_X9_ULTRA -> R.string.phone_oppo_find_x9_ultra
    PhoneModel.FIND_X9_PRO -> R.string.phone_oppo_find_x9_pro
    PhoneModel.VIVO_X200_ULTRA -> R.string.phone_vivo_x200_ultra
    PhoneModel.VIVO_X300_ULTRA -> R.string.phone_vivo_x300_ultra
    PhoneModel.OTHER -> R.string.phone_other
})

@Composable
private fun teleconverterProfileLabel(profile: TeleconverterProfile): String = stringResource(when (profile) {
    TeleconverterProfile.EXPLORER_300 -> R.string.converter_hasselblad_300
    TeleconverterProfile.HASSELBLAD_230 -> R.string.converter_hasselblad_230
    TeleconverterProfile.ZEISS_200_X200,
    TeleconverterProfile.ZEISS_200_X300,
    -> R.string.converter_zeiss_200
    TeleconverterProfile.ZEISS_400 -> R.string.converter_zeiss_400
    TeleconverterProfile.GENERIC_1_5 -> R.string.converter_generic_15
    TeleconverterProfile.GENERIC_2 -> R.string.converter_generic_2
    TeleconverterProfile.GENERIC_3 -> R.string.converter_generic_3
    TeleconverterProfile.CUSTOM -> R.string.converter_custom
})

/**
 * Production Phone declaration row shared by [LensTab] and its responsive contract tests.
 *
 * Keeping the catalog and localized labels inside this wrapper prevents a generic [DropdownRow]
 * test from passing while the real Phone consumer is wired to a different option or resource.
 */
@Composable
internal fun PhoneModelDropdown(
    selected: PhoneModel,
    onSelect: (PhoneModel) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val labels = PhoneModel.entries.associateWith { phoneModelLabel(it) }
    DropdownRow(
        label = stringResource(R.string.label_phone),
        options = PhoneModel.entries,
        selected = selected,
        labelFor = labels::getValue,
        onSelect = onSelect,
        modifier = modifier,
        enabled = enabled,
    )
}

/** Production Converter declaration row, including the selected phone's compatible catalog. */
@Composable
internal fun TeleconverterProfileDropdown(
    phone: PhoneModel,
    selected: TeleconverterProfile,
    onSelect: (TeleconverterProfile) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val options = phone.converters()
    val labels = options.associateWith { teleconverterProfileLabel(it) }
    DropdownRow(
        label = stringResource(R.string.label_converter),
        options = options,
        selected = selected,
        labelFor = labels::getValue,
        onSelect = onSelect,
        modifier = modifier,
        enabled = enabled,
    )
}

internal data class ProSheetTabSelection(val tab: ProSheetTab, val selected: Boolean)

// (proSheetTabSelection/proSheetUsesSideLayout and the per-slot quick-Fn readout/dispatch live in
// FnQuickActions.kt, beside the ControlCycles.kt cycle helpers — non-composable so they stay
// host-testable apart from Compose emission; this file owns only Compose emission and the
// external-navigation boundary.)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ProSheet(
    state: CameraUiState,
    actions: CameraActions,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialTab: ProSheetTab = ProSheetTab.SHOOTING,
    // Advances for every programmatic open, even when dismiss + reopen collapse into one Compose
    // frame and this composable never leaves the tree. Ordinary rail taps do not change it, so
    // their focus remains on the tab the operator just selected.
    openRequestId: Long = 0L,
    onTabChange: (ProSheetTab) -> Unit = {},
    // Dial-backed My Menu / Recent rows route HERE (close the sheet, open that value's ruler) —
    // the same transition the Fn overlay tile uses. performQuickFn's cycle fallback RESET these
    // values instead (zoom→1×, EV→0, exposure-MODE flips) with no affordance saying so: the same
    // FnSlot behaved differently per surface (cycle-6 designer D-01).
    onSelectManualDial: (DialType) -> Unit = {},
    externalLauncher: ((ExternalNavigationTarget) -> ExternalLaunchOutcome)? = null,
) {
    var selectedTab by remember { mutableStateOf(initialTab) }
    var externalFailure by remember(openRequestId) { mutableStateOf<ExternalNavigationFailure?>(null) }
    var showPrivacyFallback by remember(openRequestId) { mutableStateOf(false) }
    val context = LocalContext.current
    val openPrivacy = {
        val outcome = externalLauncher?.invoke(ExternalNavigationTarget.PRIVACY_POLICY)
            ?: launchExternal(context, ExternalNavigationTarget.PRIVACY_POLICY)
        externalFailure = externalNavigationFailure(ExternalNavigationTarget.PRIVACY_POLICY, outcome)
    }
    // A fixed, NON-draggable bottom panel — NOT Material3's ModalBottomSheet. The sheet let the whole
    // dialog be dragged upward past its rest position (the "bounce" the user saw), and Material3 1.4.0
    // exposes no way to disable that drag. A plain scrim + anchored panel can't be dragged at all;
    // it's dismissed only by the X, a scrim tap, or the system Back gesture.
    BackHandler(enabled = true, onBack = onDismiss)
    val closeFocusRequester = remember { FocusRequester() }
    LaunchedEffect(openRequestId) {
        selectedTab = initialTab
        closeFocusRequester.requestFocus()
    }

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
        val cameraSettingsPaneTitle = stringResource(R.string.a11y_camera_settings)
        Column(
            modifier = panelModifier
                .modalFocusBoundary()
                .clip(panelShape)
                .background(CameraColors.Pill)
                .semantics {
                    paneTitle = cameraSettingsPaneTitle
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
                Text(stringResource(R.string.label_menu), color = CameraColors.TextPrimary, style = MaterialTheme.typography.titleLarge)
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
                            ProSheetTab.ADVANCED -> AdvancedTab(
                                state = state,
                                actions = actions,
                                externalFailure = externalFailure,
                                onOpenPrivacy = openPrivacy,
                                onOpenPrivacyInApp = { showPrivacyFallback = true },
                            )
                        }
                    }
                }
            }
        }
    }
    if (showPrivacyFallback) {
        PrivacyPolicyFallbackDialog(onDismiss = { showPrivacyFallback = false })
    }
}

@Composable
private fun CloseButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val a11yCloseSettings = stringResource(R.string.a11y_close_settings)
    // 48 dp touch target; 32 dp visual pill.
    val activate = onClick
    Box(
        modifier = modifier
            .size(48.dp)
            .focusable()
            .clearAndSetSemantics {
                contentDescription = a11yCloseSettings
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
    val tabLabel = proSheetTabLabel(tab)
    val selectedState = stringResource(if (selected) R.string.a11y_selected else R.string.a11y_not_selected)
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
                contentDescription = tabLabel
                stateDescription = selectedState
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
            text = tabLabel,
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
            // A WRENCH, not the old magnifier: a lens universally reads as Search, and this rail
            // already spends the reticle/star/camera glyphs on literal category matches (UI review
            // #41). Same stroke primitives and weight as every sibling: an open C-jaw (arc) at the
            // top-left, a straight handle to the bottom-right.
            drawArc(
                color,
                startAngle = 300f,
                sweepAngle = 240f,
                useCenter = false,
                topLeft = Offset(size.width * 0.10f, size.height * 0.10f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.44f, size.height * 0.44f),
                style = stroke,
            )
            drawLine(
                color,
                Offset(size.width * 0.44f, size.height * 0.44f),
                Offset(size.width * 0.88f, size.height * 0.88f),
                strokeWidth = 2.6.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
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
    TabTitle(stringResource(R.string.section_my_menu))
    if (state.myMenuSlots.isEmpty()) {
        Text(stringResource(R.string.mr_slot_state_empty), color = CameraColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
    } else {
        state.myMenuSlots.forEach { slot ->
            QuickFnRow(slot, state, actions, availability, openDial)
        }
    }
    if (state.recentSettingSlots.isNotEmpty()) {
        SectionHeader(stringResource(R.string.section_recent))
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
        label = LocalContext.current.localizedLabel(slot),
        valueLabel = fnSlotValue(slot, state, LocalContext.current),
        enabled = enabled,
        onClick = {
            if (manualDial != null) openDial(manualDial) else performQuickFn(slot, state, actions)
        },
    )
}

@Composable
private fun MemoryRecallControls(state: CameraUiState, actions: CameraActions) {
    SectionHeader(stringResource(R.string.section_memory_recall))
    val labelContext = LocalContext.current
    MemorySlot.entries.forEach { slot ->
        val saved = slot in state.savedMemorySlots
        val presentation = state.memorySlotPresentations[slot]
        val focal = presentation?.let { formatFocalMm(it.focalMm) }.orEmpty()
        val generatedName = presentation?.let {
            when (it.mode) {
                CaptureMode.PHOTO -> stringResource(R.string.mr_default_photo_name, focal)
                CaptureMode.VIDEO -> stringResource(R.string.mr_default_video_name, transferLabel(it.transfer))
            }
        }.orEmpty()
        val generatedSummary = presentation?.let {
            when (it.mode) {
                CaptureMode.PHOTO ->
                    "$focal · ${exposureModeLetter(it.exposureMode)} · ${photoFormatLabel(it.photoFormats)}"
                CaptureMode.VIDEO ->
                    "$focal · ${videoResolutionLabelFor(it.videoWidth, it.videoHeight)} " +
                        "${videoFrameRateLabel(it.videoFrameRate)}p · ${transferLabel(it.transfer)} · " +
                        labelContext.localizedLabel(it.bitrateLevel)
            }
        }.orEmpty()
        MemoryPresetRow(
            slot = slot,
            name = if (saved) presentation?.customName ?: generatedName else stringResource(R.string.mr_slot_state_empty),
            // The app's own null token, not an instruction: this slot holds the bank's SUMMARY, and
            // the Save chip immediately to its right already says what to do about an empty one.
            summary = if (saved) presentation?.customSummary ?: generatedSummary else "--",
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
            // Independent quiet AMBER active-row wash, unrelated to the stronger white
            // AffordanceEdge interactive-boundary token.)
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
                onClickLabel = stringResource(R.string.action_recall_memory, slot.name),
                onClick = onRecall,
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = slot.name,
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
        MemoryPresetAction(saved = saved, enabled = !locked, onClick = onSave)
    }
}

/** One-shot MR write command; visually a chip, semantically an immediate button action. */
@Composable
internal fun MemoryPresetAction(
    saved: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ImmediateActionChip(
        label = stringResource(if (saved) R.string.action_update else R.string.action_save),
        active = false,
        enabled = enabled,
        onClick = onClick,
        modifier = modifier,
    )
}

internal data class ImmediateActionChipColors(
    val container: Color,
    val content: Color,
    val border: Color?,
)

/**
 * Paint for a click-only command. Active identity and action admission are independent axes:
 * Custom WB is active precisely while a new measurement is unavailable, so disabled paint must
 * cover both active states instead of relying on Material's click admission alone.
 */
internal fun immediateActionChipColors(active: Boolean, enabled: Boolean): ImmediateActionChipColors =
    when {
        active && enabled -> ImmediateActionChipColors(
            container = CameraColors.TextPrimary,
            content = Color.Black,
            border = null,
        )
        active -> ImmediateActionChipColors(
            container = CameraColors.TextPrimary.copy(alpha = DISABLED_ROW_ALPHA),
            content = Color.Black.copy(alpha = DISABLED_ROW_ALPHA),
            border = null,
        )
        enabled -> ImmediateActionChipColors(
            container = Color.Transparent,
            content = CameraColors.TextPrimary,
            border = CameraColors.AffordanceEdge,
        )
        else -> ImmediateActionChipColors(
            container = Color.Transparent,
            content = CameraColors.TextPrimary.copy(alpha = DISABLED_ROW_ALPHA),
            border = CameraColors.TextPrimary.copy(alpha = 0.12f),
        )
    }

/** Compact click-only command surface; [active] affects paint, never selectable semantics. */
@Composable
internal fun ImmediateActionChip(
    label: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = immediateActionChipColors(active, enabled)
    MinTouchTarget48 {
        Surface(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.semantics { role = Role.Button },
            shape = RoundedCornerShape(8.dp),
            color = colors.container,
            contentColor = colors.content,
            border = colors.border?.let { BorderStroke(1.dp, it) },
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun ShootingTab(state: CameraUiState, actions: CameraActions) {
    val labelContext = LocalContext.current
    val caps = state.caps
    // Same capability projection every other tab consumes (PERF4-8 remember pattern); the hi-res
    // row reads its route fact from here instead of re-deriving admission axes from raw caps.
    val availability = remember(state.caps, state.controls) {
        controlAvailability(state.caps?.controlCapabilities(), state.controls)
    }
    TabTitle(stringResource(R.string.settings_tab_shoot))
    SectionHeader(stringResource(R.string.section_format))
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
        heifAvailable = state.heifAvailable,
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
            stringResource(R.string.high_resolution_summary, hiResSize.width, hiResSize.height, mp)
        }
        Captioned(hiResCaption) {
            ToggleRow(
                label = stringResource(R.string.label_high_resolution),
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
        label = stringResource(R.string.label_aspect),
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
            // Framing state, not an output-format fact — the same misfiling Release fixed for the
            // drive rows (UI review #14). The Assist tab already owns "Framing" for overlays; here
            // the word covers the one live framing control this tab carries.
            SectionHeader(stringResource(R.string.section_framing))
            LabeledSlider(
                label = stringResource(R.string.label_zoom),
                valueLabel = formatZoomMultiplier(state.controls.zoomRatio * zBase),
                value = (state.controls.zoomRatio * zBase).coerceIn(loDisplay, zHi),
                onValueChange = { v -> actions.onZoomRatio(v / zBase) },
                valueRange = loDisplay..zHi,
            )
        }
    }
    LabeledSlider(
        // "Still", not "JPEG": the slider governs BOTH still containers (StillCapturePipeline hands
        // the same value to HeifCapture), and HEIF is a default output — the old name claimed the
        // control skipped a HEIF-only shooter's files (UI review #8).
        label = stringResource(R.string.label_still_quality),
        valueLabel = state.controls.jpegQuality.toString(),
        value = state.controls.jpegQuality.toFloat().coerceIn(1f, 100f),
        onValueChange = { actions.onJpegQuality(it.roundToInt()) },
        valueRange = 1f..100f,
    )
    // "Release", the Sony group name for drive mode + interval + self-timer. A bare "Drive" header
    // would only echo the row directly under it, but WITHOUT a header here the three release rows
    // sat under "Format" and read as output-format settings.
    SectionHeader(stringResource(R.string.section_release))
    SegmentedSelector(
        label = stringResource(R.string.label_drive),
        options = DriveMode.entries,
        selected = state.driveMode,
        labelFor = { labelContext.localizedLabel(it) },
        onSelect = actions::onDriveMode,
    )
    if (state.driveMode == DriveMode.TIMELAPSE) {
        TimelapseIntervalSlider(state.intervalSec, actions::onIntervalSec)
    }
    SegmentedSelector(
        label = stringResource(R.string.label_self_timer),
        options = ShutterTimer.entries,
        selected = state.timer,
        labelFor = { labelContext.localizedLabel(it) },
        onSelect = actions::onTimer,
    )
    MemoryRecallControls(state = state, actions = actions)
}

/** Integer production domain for the Timelapse Interval row, shared with its Compose contract test. */
@Composable
internal fun TimelapseIntervalSlider(intervalSec: Int, onIntervalSec: (Int) -> Unit) {
    LabeledSlider(
        label = stringResource(R.string.label_interval),
        valueLabel = "${intervalSec}s",
        value = intervalSec.toFloat().coerceIn(1f, 30f),
        onValueChange = { onIntervalSec(it.roundToInt()) },
        valueRange = 1f..30f,
        keyboardStep = 1f,
    )
}

@Composable
private fun ExposureColorTab(state: CameraUiState, actions: CameraActions) {
    val labelContext = LocalContext.current
    val controls = state.controls
    val caps = state.caps
    // remember(): the projection allocates ~9 filtered lists and caps/controls rarely change,
    // while telemetry ticks recompose the open tab ~10-25 Hz (PERF4-8; TopBar/ManualDials
    // already memoize the identical projection).
    val availability = remember(caps, controls) { controlAvailability(caps?.controlCapabilities(), controls) }
    TabTitle(stringResource(R.string.settings_tab_exposure))
    // PASM-style: P (auto), S (shutter-priority, app auto-ISO), ISO (iso-priority, app auto-shutter),
    // M (manual). No aperture-priority — the tele aperture is fixed.
    SegmentedSelector(
        label = stringResource(R.string.label_mode),
        options = availability.exposureModes,
        selected = controls.exposureMode,
        labelFor = { exposureModeLetter(it) },
        onSelect = actions::onExposureMode,
        enabled = availability.exposureModes.size > 1,
    )
    ToggleRow(
        label = stringResource(R.string.label_ae_lock),
        checked = controls.aeLock,
        onCheckedChange = actions::onToggleAeLock,
        enabled = availability.aeLockEnabled,
    )
    SegmentedSelector(
        label = stringResource(R.string.label_flicker),
        options = availability.antibandingModes,
        selected = controls.antibanding,
        labelFor = { labelContext.localizedLabel(it) },
        onSelect = actions::onAntibanding,
        enabled = availability.antibandingModes.size > 1,
    )
    SegmentedSelector(
        label = stringResource(R.string.label_shutter),
        options = ShutterMode.entries,
        selected = controls.shutterMode,
        labelFor = { labelContext.localizedLabel(it) },
        onSelect = actions::onShutterMode,
        enabled = availability.shutterDialEnabled,
    )
    SegmentedSelector(
        label = stringResource(R.string.label_step),
        options = ExposureStep.entries,
        selected = controls.exposureStep,
        labelFor = { "${exposureStepLabel(it)} EV" },
        onSelect = actions::onExposureStep,
        enabled = availability.shutterDialEnabled,
    )
    val isoRange = caps?.isoRange ?: Range(controls.iso, controls.iso)
    LabeledSlider(
        label = stringResource(R.string.label_iso),
        valueLabel = controls.iso.toString(),
        value = controls.iso.toFloat().coerceIn(isoRange.lower.toFloat(), isoRange.upper.toFloat()),
        onValueChange = { actions.onIso(it.roundToInt()) },
        valueRange = isoRange.lower.toFloat()..isoRange.upper.toFloat(),
        enabled = availability.isoDialEnabled &&
            (controls.exposureMode == ExposureMode.ISO || controls.exposureMode == ExposureMode.MANUAL),
    )

    SegmentedSelector(
        label = stringResource(R.string.label_metering),
        options = availability.meteringModes,
        selected = controls.meteringMode,
        labelFor = { labelContext.localizedLabel(it) },
        onSelect = actions::onMeteringMode,
        enabled = availability.meteringModes.size > 1,
    )

    // The one eyebrow this longest-scrolling tab gets (UI review #15): the exposure block above is
    // a single train of thought from Mode, but WB opens a different subject mid-scroll.
    SectionHeader(stringResource(R.string.section_white_balance))
    SegmentedSelector(
        label = stringResource(R.string.label_wb),
        options = availability.wbModes,
        selected = controls.wbMode,
        labelFor = { labelContext.localizedLabel(it) },
        onSelect = actions::onWbMode,
        enabled = availability.wbModes.size > 1,
    )
    if (controls.wbMode == WbMode.MANUAL) {
        LabeledSlider(
            label = stringResource(R.string.label_kelvin),
            valueLabel = "${controls.wbKelvin}K",
            value = controls.wbKelvin.toFloat().coerceIn(2000f, 10000f),
            onValueChange = { actions.onWbKelvin(it.roundToInt()) },
            valueRange = 2000f..10000f,
            enabled = availability.wbDialEnabled,
        )
        LabeledSlider(
            label = stringResource(R.string.label_tint),
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
    // toast are one instruction seen twice, not two instructions. "Camera reconfiguring…" is also
    // the app's single name for !cameraReady everywhere else.
    Captioned(
        if (customWbCaptureEnabled) {
            stringResource(R.string.custom_wb_aim_card)
        } else if (!state.cameraReady) {
            stringResource(R.string.status_camera_reconfiguring)
        } else {
            stringResource(R.string.status_use_auto_wb)
        },
    ) {
        ImmediateActionChip(
            label = stringResource(R.string.action_capture_custom_wb),
            active = controls.wbMode == WbMode.CUSTOM,
            enabled = customWbCaptureEnabled,
            onClick = actions::onCaptureCustomWb,
        )
    }
    ToggleRow(
        label = stringResource(R.string.label_awb_lock),
        checked = controls.awbLock,
        onCheckedChange = actions::onToggleAwbLock,
        enabled = availability.awbLockEnabled,
    )
}

@Composable
private fun FocusTab(state: CameraUiState, actions: CameraActions) {
    val labelContext = LocalContext.current
    val a11yResetFocusPoint = stringResource(R.string.a11y_reset_focus_point)
    val a11yTapFocusHeld = stringResource(R.string.a11y_tap_focus_held)
    val a11yNoTapFocusPoint = stringResource(R.string.a11y_no_tap_focus_point)
    val controls = state.controls
    // remember(): see PERF4-8 note in ExposureColorTab.
    val availability = remember(state.caps, controls) { controlAvailability(state.caps?.controlCapabilities(), controls) }
    TabTitle(stringResource(R.string.settings_tab_focus))
    SectionHeader(stringResource(R.string.section_autofocus))
    SegmentedSelector(
        label = stringResource(R.string.label_af),
        options = availability.focusModes,
        selected = controls.focusMode,
        labelFor = { labelContext.localizedLabel(it) },
        onSelect = actions::onFocusMode,
        enabled = availability.focusModes.size > 1,
    )
    // Sony Focus Area: Spot S/M/L — the size of the tap-AF/metering region.
    SegmentedSelector(
        label = stringResource(R.string.label_spot_size),
        options = AfSpotSize.entries,
        selected = controls.afSpotSize,
        labelFor = { labelContext.localizedLabel(it) },
        onSelect = actions::onAfSpotSize,
        enabled = availability.afSpotSizeEnabled,
    )
    if (controls.focusMode != FocusMode.MANUAL) {
        ToggleRow(
            label = stringResource(R.string.label_af_lock),
            checked = controls.afLock,
            onCheckedChange = actions::onAfLock,
            enabled = availability.afLockEnabled,
        )
    }
    LabelValueRow(
        label = stringResource(R.string.label_tap_focus),
        // "None", not "No point": in a value slot that phrase reads as "there is no point". The
        // stateDescription below is the one that gets to be a sentence.
        valueLabel = stringResource(
            if (state.tapFocusHeld) R.string.action_reset else R.string.value_none,
        ),
        enabled = state.tapFocusHeld,
        onClick = actions::onResetFocusPoint,
        modifier = Modifier.semantics {
            contentDescription = a11yResetFocusPoint
            stateDescription = if (state.tapFocusHeld) a11yTapFocusHeld else a11yNoTapFocusPoint
        },
    )
    SectionHeader(stringResource(R.string.section_mf_assist))
    ToggleRow(label = stringResource(R.string.label_peaking), checked = state.focusPeaking, onCheckedChange = actions::onTogglePeaking)
    SegmentedSelector(
        label = stringResource(R.string.label_peaking_level),
        options = PeakingLevel.entries,
        selected = state.peakingLevel,
        labelFor = { labelContext.localizedLabel(it) },
        onSelect = actions::onPeakingLevel,
        enabled = state.focusPeaking,
    )
    SegmentedSelector(
        label = stringResource(R.string.label_peaking_color),
        options = PeakingColor.entries,
        selected = state.peakingColor,
        labelFor = { labelContext.localizedLabel(it) },
        onSelect = actions::onPeakingColor,
        enabled = state.focusPeaking,
    )
}

@Composable
private fun LensTab(state: CameraUiState, actions: CameraActions) {
    val labelContext = LocalContext.current
    // Both onLens and onToggleTeleconverter refuse mid-REC deeper in the ViewModel (a full optics-
    // generation reopen — the afocal 180° flip — would tear the recording); these rows used to stay
    // visually hot and only silently no-op (a "Stop REC first" toast) on tap, inconsistent with My
    // Menu's dimmed-and-guarded quick-Fn rows (3825ae2). Both are also rear-only optics doors
    // (backOpticsDoorRefusal): on the selfie route they must dim like the viewfinder's
    // TeleChip/FocalRail go GONE — a bright row whose refusal lives only in a toast is the same
    // anti-pattern.
    val rearRoute = state.activeCameraRoute == me.hletrd.telecampro.camera.CameraRoute.BACK && state.cameraRoutes.back
    val recordingMutable = !state.isRecording
    val rearOpticsMutable = recordingMutable && rearRoute
    TabTitle(stringResource(R.string.settings_tab_lens))
    SectionHeader(stringResource(R.string.section_optics))
    // Every row in this section shares ONE gate (rearOpticsMutable), so the precondition is stated
    // ONCE for the section instead of once per dimmed row — the selfie route used to stack the same
    // dim sentence three times, 20 dp apart, under rows that were already grey.
    if (!rearRoute) {
        Text(
            stringResource(R.string.state_rear_camera_only),
            color = CameraColors.TextSecondary,
            style = MaterialTheme.typography.labelSmall,
        )
    }
    Captioned(
        if (rearRoute) {
            lensFocalCaption(
                state.lens,
                state.teleconverterMode,
                state.teleconverterFocalMm,
                // The SELECTED preset's own measured focal, never the active route's: on the
                // seamless photo route every preset rides one logical camera, so its ~23 mm
                // equivalent described 1x only and made 3x read "23 mm" (verification 2026-08-02).
                measuredEquivMm = state.lensInventory.presetEquivMm[state.lens] ?: 0f,
            )
        } else {
            null
        },
    ) {
        // Lens picks are ZOOM PRESETS on the seamless logical camera — they do NOT bundle the
        // teleconverter. TELE stays on only when it already is AND the pick is its 3× host lens; the
        // separate toggle below pins converter shooting (afocal 180° flip, standalone 3× camera).
        SegmentedSelector(
            label = stringResource(R.string.label_lens),
            // Same enumerated inventory the viewfinder rail uses — a preset this device cannot
            // reach must not be offered in the menu either.
            options = LensChoice.entries.filter { it in state.lensInventory.available },
            selected = state.lens,
            labelFor = ::lensLabel,
            onSelect = actions::onLens,
            enabled = rearOpticsMutable,
        )
    }
    // Names the lens the converter will ACTUALLY clamp onto: the app resolves that by closest
    // 35 mm-equivalent, which is the 3× periscope only where one exists (enumerated, not assumed —
    // a single-camera device would otherwise be told it uses a lens it does not have).
    val hostCaption = if (rearRoute) {
        when (val caption = teleconverterHostCaption(
            state.lensInventory.teleHostEquivMm,
            LensChoice.TELE3X in state.lensInventory.optical,
        )) {
            TeleconverterHostCaption.ThreeTimes -> stringResource(R.string.caption_converter_host_3x)
            is TeleconverterHostCaption.Measured -> stringResource(
                R.string.caption_converter_host_measured,
                caption.focalLabel,
            )
            TeleconverterHostCaption.Main -> stringResource(R.string.caption_converter_host_main)
        }
    } else {
        null
    }
    Captioned(hostCaption) {
        ToggleRow(
            label = stringResource(R.string.label_teleconverter),
            checked = state.teleconverterMode,
            onCheckedChange = actions::onToggleTeleconverter,
            enabled = rearOpticsMutable,
        )
    }
    // The converter setting is a PAIR, asked in order: the phone decides which kits clamp on, the
    // converter decides the magnification. Dropdowns, not chips — the flat catalog of every brand's
    // optics read as a scrolling smear (user-rejected). Only the PHONE is ever resolved
    // automatically; passive glass cannot announce itself.
    PhoneModelDropdown(
        selected = state.phoneModel,
        onSelect = actions::onPhoneModel,
        enabled = rearOpticsMutable,
    )
    // The computed focal for THIS phone's host lens, never the preset's product number: a "ZEISS
    // 200 mm" is 2.35x glass, and 2.35x on this phone's 70 mm periscope is 165 mm.
    val converterFocal = stringResource(
        R.string.converter_focal_equivalent,
        formatFocalMm(state.teleconverterFocalMm),
    )
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
                state.phoneModelDetected -> stringResource(
                    R.string.phone_detected_summary,
                    converterFocal,
                    phoneModelLabel(state.phoneModel),
                )
                else -> converterFocal
            }
        },
    ) {
        // Narrowed to this phone's kits plus the fits-anything entries, so a converter that cannot
        // physically clamp on is not offerable in the first place.
        TeleconverterProfileDropdown(
            phone = state.phoneModel,
            selected = state.teleconverterProfile,
            onSelect = actions::onTeleconverterProfile,
            enabled = rearOpticsMutable,
        )
        if (state.teleconverterProfile.isCustom) {
            LabeledSlider(
                label = stringResource(R.string.label_magnification),
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
    SectionHeader(stringResource(R.string.section_stabilization))
    val videoStabChoices = state.videoStabChoices.ifEmpty { listOf(state.videoStabMode) }
    Captioned(
        when (state.videoStabMode) {
            // null, not "Off": the selected chip already says Off, and a caption restating the
            // control 4 dp under itself is the slop class this file's convention exists to prevent
            // (UI review #10). The other branches earn their line. " · ", not a comma — the app's
            // one separator register (#17).
            VideoStabMode.OFF -> null
            VideoStabMode.STANDARD -> stringResource(R.string.stabilization_ois_eis)
            VideoStabMode.ENHANCED -> stringResource(R.string.stabilization_ois_eis_crop)
        },
    ) {
        SegmentedSelector(
            label = stringResource(R.string.label_mode),
            options = videoStabChoices,
            selected = state.videoStabMode,
            labelFor = { labelContext.localizedLabel(it) },
            onSelect = actions::onVideoStabMode,
            // Same REC guard as the Lens/TC rows above (CR4-6): onVideoStabMode refuses mid-REC with
            // a toast, so a visually-hot selector here silently no-oped while its siblings greyed out.
            enabled = recordingMutable && videoStabChoices.size > 1,
        )
    }
    if (state.caps?.oisAvailable == true) {
        ToggleRow(label = stringResource(R.string.label_photo_ois), checked = state.controls.oisEnabled, onCheckedChange = actions::onToggleOis)
    }
}

@Composable
private fun VideoTab(state: CameraUiState, actions: CameraActions) {
    val labelContext = LocalContext.current
    val caps = state.caps
    val codec = state.videoCodec
    val recordingMutable = !state.isRecording
    TabTitle(stringResource(R.string.settings_tab_video))
    if (state.isRecording) {
        LabelValueRow(
            label = stringResource(R.string.label_recording),
            valueLabel = stringResource(R.string.settings_locked),
        )
    }

    // Codecs are limited to what MediaCodecList actually advertises a muxable HW encoder for
    // (HEVC/AVC on this SoC).
    val codecOptions = state.availableVideoCodecs
    SectionHeader(stringResource(R.string.section_recording_format))
    SegmentedSelector(
        label = stringResource(R.string.label_codec),
        options = codecOptions,
        selected = codec,
        labelFor = ::videoCodecLabel,
        onSelect = actions::onVideoCodec,
        enabled = recordingMutable,
    )

    // Open Gate records the full 4:3 sensor readout instead of a 16:9 crop; it swaps the resolution
    // list to the camera's 4:3 sizes.
    ToggleRow(
        label = stringResource(R.string.label_open_gate_43),
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
            stringResource(R.string.state_no_supported_resolution),
            color = CameraColors.TextSecondary,
            style = MaterialTheme.typography.labelSmall,
        )
    } else {
        SegmentedSelector(
            label = stringResource(R.string.label_resolution),
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
            stringResource(R.string.state_no_supported_fps),
            color = CameraColors.TextSecondary,
            style = MaterialTheme.typography.labelSmall,
        )
    } else {
        SegmentedSelector(
            label = stringResource(R.string.label_fps),
            options = fpsOptions,
            selected = state.videoFrameRate,
            labelFor = ::videoFrameRateLabel,
            onSelect = actions::onVideoFrameRate,
            enabled = recordingMutable,
        )
    }
    SegmentedSelector(
        label = stringResource(R.string.label_bitrate),
        options = BitrateLevel.entries,
        selected = state.bitrateLevel,
        labelFor = { labelContext.localizedLabel(it) },
        onSelect = actions::onBitrateLevel,
        enabled = recordingMutable,
    )
    // Resolved encoder settings summary, e.g. "HEVC · 4K · 30p · 84 Mbps" — the exact computed
    // bitrate. The rate carries its "p" like the OSD's spec line does: in a list next to "4K" a
    // bare number reads as another dimension.
    val encodedSize = state.encodedVideoResolution
    val mbps = videoBitRate(
        encodedSize.width, encodedSize.height,
        state.videoFrameRate.encoderRate,
        me.hletrd.telecampro.camera.effectiveBpp(state.bitrateLevel, codec), codec,
    ) / 1_000_000
    // A derived READOUT, not a live control: it rides the caption idiom (dim, 4 dp under the
    // Bitrate group it summarizes) so the SemiBold row ladder stays reserved for things a finger
    // can change, and the 12 dp base gap returns to being the boundary before Gamma
    // (UI review #16).
    Text(
        "${videoCodecLabelShort(codec)} · ${videoResolutionLabel(encodedSize)} · ${videoFrameRateLabel(state.videoFrameRate)}p · $mbps Mbps",
        color = CameraColors.TextSecondary,
        style = MaterialTheme.typography.labelSmall,
    )
    // One terse source caveat (cycle-6 A26/D-08): the named log curves are real math, but they bake
    // onto the already-tone-mapped SDR stream — no scene-referred latitude is recovered (CLAUDE.md
    // "must not be marketed as such"). Same caption idiom as the hi-res/stabilization rows. Gated on
    // a non-SDR selection because the caveat is about a CURVE: printed under Transfer = SDR it
    // asserted a curve was applied where none is.
    // "SDR stream" stopped being true when video gained a 10-bit session; the honesty point was
    // never the bit depth but that the ISP has ALREADY tone-mapped what the curve is applied to.
    Captioned(
        if (state.transfer != ColorTransfer.SDR) stringResource(R.string.video_tone_mapped_source) else null,
    ) {
        // Transfer is part of the encoded image format, so keep it with codec/rate controls instead
        // of below the unrelated audio controls.
        TransferSelector(
            transfer = state.transfer,
            onTransfer = actions::onTransfer,
            codec = codec,
            enabled = codec == VideoCodec.HEVC && recordingMutable,
            tenBitEncodeAvailable = state.tenBitEncodeAvailable,
        )
    }

    SectionHeader(stringResource(R.string.label_audio))
    ToggleRow(
        // "Record Audio", not a verbatim restatement of the section header above it — and the bare
        // word now belongs to nothing, so the Fn "Direction" cycler no longer collides (UI review #3).
        label = stringResource(R.string.label_record_audio),
        checked = state.recordAudio,
        onCheckedChange = actions::onToggleRecordAudio,
        enabled = recordingMutable,
    )
    SegmentedSelector(
        label = stringResource(R.string.label_input),
        options = AudioInputPreference.entries,
        selected = state.audioInputPreference,
        labelFor = { labelContext.localizedLabel(it) },
        onSelect = actions::onAudioInputPreference,
        enabled = state.recordAudio && recordingMutable,
    )
    LabelValueRow(
        label = stringResource(R.string.label_route),
        valueLabel = state.audioRoute.resolve(LocalContext.current),
    )
    // Directional audio: Sound Focus aims the mic array at the framed subject and tightens with zoom;
    // Sound Stage keeps a wider stereo image.
    SegmentedSelector(
        // "Directionality", not "Scene": the user-facing question this row answers is WHERE the
        // mics listen (subject-aimed Sound Focus vs wide Sound Stage) — "Scene" read as a picture
        // style and was reported un-findable (2026-07-31).
        label = stringResource(R.string.label_directionality),
        options = AudioScene.entries,
        selected = state.audioScene,
        labelFor = { labelContext.localizedLabel(it) },
        onSelect = actions::onAudioScene,
        enabled = state.recordAudio && recordingMutable,
    )
    LabeledSlider(
        label = stringResource(R.string.label_gain),
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
    val labelContext = LocalContext.current
    val controls = state.controls
    // remember(): see PERF4-8 note in ExposureColorTab.
    val availability = remember(state.caps, controls) { controlAvailability(state.caps?.controlCapabilities(), controls) }
    TabTitle(stringResource(R.string.settings_tab_image))
    SectionHeader(stringResource(R.string.section_processing))
    SegmentedSelector(
        label = stringResource(R.string.label_sharpness),
        options = availability.edgeModes,
        selected = controls.edge,
        labelFor = { labelContext.localizedLabel(it) },
        onSelect = actions::onEdge,
        enabled = availability.edgeModes.size > 1,
    )
    SegmentedSelector(
        label = stringResource(R.string.label_nr),
        options = availability.noiseReductionModes,
        selected = controls.noiseReduction,
        labelFor = { labelContext.localizedLabel(it) },
        onSelect = actions::onNoiseReduction,
        enabled = availability.noiseReductionModes.size > 1,
    )
    SegmentedSelector(
        label = stringResource(R.string.label_color),
        options = availability.colorEffects,
        selected = controls.colorEffect,
        labelFor = { labelContext.localizedLabel(it) },
        onSelect = actions::onColorEffect,
        enabled = availability.colorEffects.size > 1,
    )
}

@Composable
private fun AssistsTab(state: CameraUiState, actions: CameraActions) {
    val labelContext = LocalContext.current
    TabTitle(stringResource(R.string.settings_tab_assist))
    SectionHeader(stringResource(R.string.section_monitor))
    // Gamma Display Assist (Sony): only meaningful while the Gamma is a log profile — the monitor
    // shows the normal image, the recorded file stays log.
    ToggleRow(
        label = stringResource(R.string.label_gamma_disp_assist),
        checked = state.gammaAssist,
        onCheckedChange = actions::onToggleGammaAssist,
        enabled = state.transfer.isLog,
    )
    SectionHeader(stringResource(R.string.section_exposure_aids))
    ToggleRow(label = stringResource(R.string.label_zebra), checked = state.zebra, onCheckedChange = actions::onToggleZebra)
    SegmentedSelector(
        // "Level" with % values (Sony: "Zebra Level"): the old label named IRE while every chip
        // printed a percent sign — one unit promised, another worn (UI review #19).
        label = stringResource(R.string.label_zebra_level),
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
    ToggleRow(label = stringResource(R.string.label_false_color), checked = state.falseColor, onCheckedChange = actions::onToggleFalseColor)
    ToggleRow(label = stringResource(R.string.label_histogram), checked = state.histogram, onCheckedChange = actions::onToggleHistogram)
    ToggleRow(label = stringResource(R.string.label_waveform), checked = state.waveform, onCheckedChange = actions::onToggleWaveform)
    SectionHeader(stringResource(R.string.section_framing))
    // Beside Grid, not under Monitor (UI review #20): both are framing overlays, and filing half of
    // them a section away made a user scanning for framing marks miss this one.
    SegmentedSelector(
        label = stringResource(R.string.label_frame_lines),
        options = FrameLineType.entries,
        selected = state.frameLines,
        labelFor = { labelContext.localizedLabel(it) },
        onSelect = actions::onFrameLines,
    )
    SegmentedSelector(
        label = stringResource(R.string.label_grid),
        options = GridType.entries,
        selected = state.grid,
        labelFor = { labelContext.localizedLabel(it) },
        onSelect = actions::onGridType,
    )
    ToggleRow(label = stringResource(R.string.label_level), checked = state.level, onCheckedChange = actions::onToggleLevel)
    SectionHeader(stringResource(R.string.section_focus_aids))
    // "Loupe" app-wide (cycle-6 D-04): Fn chip, key-action label, and LOUPE OSD tag already use it.
    ToggleRow(label = stringResource(R.string.label_loupe), checked = state.punchIn, onCheckedChange = actions::onTogglePunchIn)
    // NO section header here (UI review #9): the overview stopped being teleconverter-specific
    // when the zoom floor qualified any lens (2026-07-29) — the old "Teleconverter" header was the
    // stale claim the caption below already refutes — and the row's parent GATE is the Loupe toggle
    // directly above, the Zebra → Zebra Level adjacency model.
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
    Captioned(stringResource(R.string.loupe_overview_caption)) {
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
            label = stringResource(R.string.label_loupe_overview),
            checked = state.teleFinder,
            onCheckedChange = actions::onToggleTeleFinder,
            enabled = state.punchIn,
        )
    }
}

@Composable
private fun AdvancedTab(
    state: CameraUiState,
    actions: CameraActions,
    externalFailure: ExternalNavigationFailure?,
    onOpenPrivacy: () -> Unit,
    onOpenPrivacyInApp: () -> Unit,
) {
    val labelContext = LocalContext.current
    TabTitle(stringResource(R.string.settings_tab_setup))
    SectionHeader(stringResource(R.string.section_app))
    LabelValueRow(
        label = stringResource(R.string.action_privacy_policy),
        valueLabel = stringResource(R.string.action_view),
        onClick = onOpenPrivacy,
    )
    ExternalNavigationRecovery(
        failure = externalFailure,
        onOpenPrivacyInApp = onOpenPrivacyInApp,
    )
    // Own section eyebrow (UI review #43): a dim TextSecondary block at the uniform gap directly
    // under the interactive Privacy Policy row wore the exact shape of that row's caption while
    // being unrelated to it.
    SectionHeader(stringResource(R.string.section_legal))
    // Trademark attribution for the named log profiles offered in the Video tab — a legal
    // footnote, deliberately non-interactive and dim. bodySmall, NOT labelSmall: these two are the
    // only multi-sentence PROSE in the sheet, and labelSmall is a label treatment (Medium weight)
    // that renders three sentences as a wall of emphasized micro-text.
    // ARRI marks are registered to the Cine Technik entity, not an "ARRI AG" (cycle-6 DS-8).
    Text(
        stringResource(R.string.legal_trademark_log),
        color = CameraColors.TextSecondary,
        style = MaterialTheme.typography.bodySmall,
    )
    // The HARDWARE marks, which are the most VISIBLE brand use in this app: the phone and converter
    // pickers list them by name so an owner can recognise their own kit. Naming a product to say
    // "this works with that" is nominative use, but it still needs the owners stated — and the log
    // footnote above already set that precedent for Sony/ARRI while these went unattributed.
    Text(
        stringResource(R.string.legal_trademark_hardware),
        color = CameraColors.TextSecondary,
        style = MaterialTheme.typography.bodySmall,
    )
    // ONE affiliation disclaimer for every mark above, not one per paragraph. Adding the hardware
    // marks left the section saying "not affiliated" twice in four lines — the same assurance
    // stacked, which reads as boilerplate and is exactly what a Sony menu would not do.
    Text(
        stringResource(R.string.legal_no_affiliation),
        color = CameraColors.TextSecondary,
        style = MaterialTheme.typography.bodySmall,
    )
    // Bundled-typeface attribution (SIL OFL requires the license to travel with the font; the
    // full text ships in the repo at docs/licenses/inter-OFL.txt).
    Text(
        stringResource(R.string.legal_typeface),
        color = CameraColors.TextSecondary,
        style = MaterialTheme.typography.bodySmall,
    )
    // The app's OWN licence, which was missing entirely: the project called itself open source in
    // its README badge and store copy while granting no licence at all, which legally means all
    // rights reserved. Apache-2.0 as of 2026-08-03; §6 of that licence grants no trademark rights,
    // which is why the attributions above are separate from it.
    Text(
        stringResource(R.string.legal_app_license),
        color = CameraColors.TextSecondary,
        style = MaterialTheme.typography.bodySmall,
    )
    SectionHeader(stringResource(R.string.section_startup))
    ToggleRow(
        label = stringResource(R.string.label_remember_settings),
        checked = state.rememberSettings,
        onCheckedChange = actions::onToggleRememberSettings,
    )
    ToggleRow(
        label = stringResource(R.string.label_remember_lens),
        checked = state.preserveLensSelection,
        onCheckedChange = actions::onTogglePreserveLensSelection,
        enabled = state.rememberSettings,
    )
    ToggleRow(
        label = stringResource(R.string.label_remember_teleconverter),
        checked = state.preserveTeleconverter,
        onCheckedChange = actions::onTogglePreserveTeleconverter,
        enabled = state.rememberSettings,
    )
    SectionHeader(stringResource(R.string.section_photo_fn))
    // Each shooting list only offers slots that can act in ITS mode (fnSlotAppliesTo). My Menu is a
    // settings surface, not a shooting one, so it keeps the full set.
    FnSlotEditor(selected = state.photoFnSlots, mode = CaptureMode.PHOTO, onSet = actions::onSetPhotoFnSlots)
    SectionHeader(stringResource(R.string.section_video_fn))
    FnSlotEditor(selected = state.videoFnSlots, mode = CaptureMode.VIDEO, onSet = actions::onSetVideoFnSlots)
    SectionHeader(stringResource(R.string.section_my_menu))
    FnSlotEditor(selected = state.myMenuSlots, mode = null, onSet = actions::onSetMyMenuSlots)
    SectionHeader(stringResource(R.string.section_keys))
    // This one assignment governs the camera button's FULL press AND the volume keys
    // (MainActivity routes both to it) — say so in the label (cycle-6 D-13).
    SegmentedSelector(
        label = stringResource(R.string.label_full_press_volume),
        options = HardwareKeyAction.entries,
        selected = state.volumeKeyAction,
        labelFor = { labelContext.localizedLabel(it) },
        onSelect = actions::onVolumeKeyAction,
    )
    SegmentedSelector(
        label = stringResource(R.string.label_half_press),
        options = HardwareKeyAction.entries,
        selected = state.halfPressAction,
        labelFor = { labelContext.localizedLabel(it) },
        onSelect = actions::onHalfPressAction,
    )
    // The OPPO quick/action button (system-injected 781). Discrete click — momentary bindings
    // (AEL, Loupe) blip on/off within one press, which is honest to what the key delivers.
    SegmentedSelector(
        label = stringResource(R.string.label_quick_button),
        options = HardwareKeyAction.entries,
        selected = state.quickButtonAction,
        labelFor = { labelContext.localizedLabel(it) },
        onSelect = actions::onQuickButtonAction,
    )
    // A stale diagnostic override must remain recoverable, but normal release users should not see
    // an inert implementation-detail row.
    state.cameraOverrideId?.let { cameraId ->
        LabelValueRow(
            // The VALUE slot carries the ACTION, per every other tappable LabelValueRow ("View",
            // "Add"): the old layout put the raw id there, so tapping looked like it would edit the
            // id while it actually CLEARS the override (UI review #11).
            label = stringResource(R.string.camera_id_label, cameraId),
            valueLabel = stringResource(R.string.action_reset),
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
            label = LocalContext.current.localizedLabel(slot),
            valueLabel = stringResource(R.string.action_add),
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
internal fun FnSlotOrderRow(
    slot: FnSlot,
    index: Int,
    count: Int,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val name = LocalContext.current.localizedLabel(slot)
    val moveUpLabel = stringResource(R.string.action_move_up, name)
    val moveDownLabel = stringResource(R.string.action_move_down, name)
    val removeLabel = stringResource(R.string.action_remove_named, name)
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val compact = fnSlotOrderUsesCompactLayout(maxWidth.value, LocalDensity.current.fontScale)
        if (compact) {
            Column(modifier = Modifier.fillMaxWidth()) {
                FnSlotOrderIdentity(index = index, name = name)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MiniTextButton(text = "↑", clickLabel = moveUpLabel, enabled = index > 0, onClick = onMoveUp)
                    MiniTextButton(text = "↓", clickLabel = moveDownLabel, enabled = index < count - 1, onClick = onMoveDown)
                    MiniTextButton(text = "×", clickLabel = removeLabel, enabled = true, onClick = onRemove)
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FnSlotOrderIdentity(index = index, name = name, modifier = Modifier.weight(1f))
                MiniTextButton(text = stringResource(R.string.action_up), clickLabel = moveUpLabel, enabled = index > 0, onClick = onMoveUp)
                MiniTextButton(text = stringResource(R.string.action_down), clickLabel = moveDownLabel, enabled = index < count - 1, onClick = onMoveDown)
                MiniTextButton(text = stringResource(R.string.action_remove), clickLabel = removeLabel, enabled = true, onClick = onRemove)
            }
        }
    }
}

@Composable
private fun FnSlotOrderIdentity(index: Int, name: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
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
            text = name,
            color = CameraColors.TextPrimary,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f),
        )
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
            .semantics { clickLabel?.let { contentDescription = it } }
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
