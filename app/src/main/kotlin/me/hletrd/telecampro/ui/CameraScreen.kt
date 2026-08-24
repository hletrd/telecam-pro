package me.hletrd.telecampro.ui

import me.hletrd.telecampro.R

import android.graphics.SurfaceTexture
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.hletrd.telecampro.camera.unifiedZoom
import me.hletrd.telecampro.camera.AfIndication
import me.hletrd.telecampro.camera.AspectRatio
import me.hletrd.telecampro.camera.CameraFacing
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.CameraStatusLivePriority
import me.hletrd.telecampro.camera.CaptureMode
import me.hletrd.telecampro.camera.ExposureMode
import me.hletrd.telecampro.camera.FlashMode
import me.hletrd.telecampro.camera.FnSlot
import me.hletrd.telecampro.camera.GridType
import me.hletrd.telecampro.camera.finderRect
import me.hletrd.telecampro.camera.finderContainsTopLeftPoint
import me.hletrd.telecampro.camera.teleFinderVisible
import me.hletrd.telecampro.camera.LensChoice
import me.hletrd.telecampro.camera.RotationMath
import me.hletrd.telecampro.camera.ShutterTimer
import me.hletrd.telecampro.camera.ViewfinderFocusActionAvailability
import me.hletrd.telecampro.camera.controlAvailability
import me.hletrd.telecampro.camera.controlCapabilities
import me.hletrd.telecampro.camera.teleDisplayBase
import me.hletrd.telecampro.camera.viewfinderFocusActionAvailability
import me.hletrd.telecampro.storage.MediaProvenance
import me.hletrd.telecampro.ui.controls.formatZoomMark
import me.hletrd.telecampro.ui.controls.CompactFnButton
import me.hletrd.telecampro.ui.controls.DialType
import me.hletrd.telecampro.ui.controls.ManualDialCluster
import me.hletrd.telecampro.ui.controls.ProSheet
import me.hletrd.telecampro.ui.controls.ProSheetTab
import me.hletrd.telecampro.ui.controls.aspectRatioLabel
import me.hletrd.telecampro.ui.controls.exposureMeterCompensationEv
import me.hletrd.telecampro.ui.controls.flashChoicesFor
import me.hletrd.telecampro.ui.controls.flashDisplayMode
import me.hletrd.telecampro.ui.controls.nextAspect
import me.hletrd.telecampro.ui.controls.nextAvailable
import me.hletrd.telecampro.ui.controls.nextTimer
import me.hletrd.telecampro.ui.controls.toggledGridType
import me.hletrd.telecampro.ui.controls.quickFnEnabled
import me.hletrd.telecampro.ui.controls.flashModeLabel
import me.hletrd.telecampro.ui.controls.fnSlotIcon
import me.hletrd.telecampro.ui.controls.formatEvComp
import me.hletrd.telecampro.ui.controls.fnSlotLabel
import me.hletrd.telecampro.ui.controls.gridTypeLabel
import me.hletrd.telecampro.ui.controls.hardwareKeyActionLabel
import me.hletrd.telecampro.ui.controls.localizedLabel
import me.hletrd.telecampro.ui.controls.fnSlotValue
import me.hletrd.telecampro.ui.controls.manualDialForFnSlot
import me.hletrd.telecampro.ui.controls.manualDialTransition
import me.hletrd.telecampro.ui.controls.lensLabel
import me.hletrd.telecampro.ui.controls.performQuickFn
import me.hletrd.telecampro.ui.controls.quickManualDialEnabled
import me.hletrd.telecampro.ui.controls.shutterTimerLabel
import me.hletrd.telecampro.ui.controls.trailingEdgeFadeScrollHint
import me.hletrd.telecampro.ui.controls.whiteBalanceFnChipEnabled
import me.hletrd.telecampro.ui.overlays.AspectMask
import me.hletrd.telecampro.ui.overlays.AudioMeter
import me.hletrd.telecampro.ui.overlays.FrameLinesOverlay
import me.hletrd.telecampro.ui.overlays.FocusReticle
import me.hletrd.telecampro.ui.overlays.FocusResultLiveRegion
import me.hletrd.telecampro.ui.overlays.GridOverlay
import me.hletrd.telecampro.ui.overlays.HistogramOverlay
import me.hletrd.telecampro.ui.overlays.HudPlate
import me.hletrd.telecampro.ui.overlays.HorizonAccessibilityDirection
import me.hletrd.telecampro.ui.overlays.LevelOverlay
import me.hletrd.telecampro.ui.overlays.RecordingIndicator
import me.hletrd.telecampro.ui.overlays.StatusBar
import me.hletrd.telecampro.ui.overlays.TimerCountdown
import me.hletrd.telecampro.ui.overlays.WaveformOverlay
import me.hletrd.telecampro.ui.overlays.horizonAccessibilityState
import me.hletrd.telecampro.ui.overlays.levelDeviationDegrees
import me.hletrd.telecampro.ui.review.GalleryThumb
import me.hletrd.telecampro.ui.review.MediaReviewOverlay
import me.hletrd.telecampro.ui.theme.CameraColors
import me.hletrd.telecampro.ui.theme.hudGlyph
import kotlin.math.roundToInt

/**
 * Top inset every free-floating viewfinder lane starts below, so nothing lands on the OSD status
 * row. The row itself sits at 60 dp + statusBars and its labelMedium + 6 dp padding ends ~88-90 dp;
 * QA hit an overlap on the scopes column at 72 dp. ONE constant so the lanes cannot drift apart
 * again — the top-center lane was still at 64 dp and the centered 180 dp zoom bar overlapped a
 * long (full-DISP video) status strip during a pinch.
 */
private val OSD_CLEARANCE_TOP = 100.dp

/**
 * [OSD_CLEARANCE_TOP] scaled by the user's font setting. The 100 dp was derived at font scale 1.0
 * ("the status row ends ~88-90 dp"), but that row's height is text-driven: at the accessibility
 * range's top (scale 2.0, Android 13+) it ends around 104 dp and the two fixed lanes below started
 * INSIDE it (2026-08-02 review). Only the text-driven part scales — the status bar inset and the
 * 60 dp row offset above it are fixed — so the constant grows by the ~40 dp of type it reserves.
 */
@Composable
private fun osdClearanceTop(): Dp {
    val scale = LocalDensity.current.fontScale.coerceIn(1f, 2f)
    return OSD_CLEARANCE_TOP + (40.dp * (scale - 1f))
}

@Composable
internal fun localizedTimerCountdownDescription(seconds: Int): String =
    pluralStringResource(R.plurals.a11y_seconds_remaining, seconds, seconds)

@Composable
private fun localizedRemainingCapacityDescription(capacity: RemainingCapacity): String = when (capacity) {
    is RemainingCapacity.Shots -> if (capacity.saturated) {
        pluralStringResource(
            R.plurals.a11y_saturated_shots_remaining,
            capacity.count,
            capacity.count,
        )
    } else {
        pluralStringResource(R.plurals.a11y_shots_remaining, capacity.count, capacity.count)
    }
    is RemainingCapacity.Duration -> when {
        capacity.saturated && capacity.hours > 0 && capacity.minutes > 0 -> stringResource(
            R.string.a11y_saturated_hours_minutes_remaining,
            pluralStringResource(R.plurals.a11y_hours_unit, capacity.hours, capacity.hours),
            pluralStringResource(R.plurals.a11y_minutes_unit, capacity.minutes, capacity.minutes),
        )
        capacity.saturated && capacity.hours > 0 -> pluralStringResource(
            R.plurals.a11y_saturated_hours_remaining,
            capacity.hours,
            capacity.hours,
        )
        capacity.saturated -> pluralStringResource(
            R.plurals.a11y_saturated_minutes_remaining,
            capacity.minutes,
            capacity.minutes,
        )
        capacity.hours > 0 && capacity.minutes > 0 -> stringResource(
            R.string.a11y_hours_minutes_join,
            pluralStringResource(R.plurals.a11y_hours_unit, capacity.hours, capacity.hours),
            pluralStringResource(R.plurals.a11y_minutes_remaining, capacity.minutes, capacity.minutes),
        )
        capacity.hours > 0 -> pluralStringResource(
            R.plurals.a11y_hours_remaining,
            capacity.hours,
            capacity.hours,
        )
        else -> pluralStringResource(
            R.plurals.a11y_minutes_remaining,
            capacity.minutes,
            capacity.minutes,
        )
    }
}

/** The one production formatter used by the status pill and bilingual semantics tests. */
@Composable
internal fun localizedStatusInfoDescription(batteryPct: Int, remaining: String?, video: Boolean): String {
    val battery = batteryPct.takeIf { it >= 0 }?.let {
        pluralStringResource(R.plurals.a11y_battery_percent, it, it)
    }
    val media = parseRemainingCapacity(remaining, video)?.let {
        localizedRemainingCapacityDescription(it)
    }
    return when {
        battery != null && media != null -> stringResource(R.string.a11y_status_join, battery, media)
        battery != null -> battery
        else -> media.orEmpty()
    }
}

/** Full-screen touch cancellation with semantics bounded to the visible centered countdown text. */
@Composable
internal fun SelfTimerCountdownOverlay(
    seconds: Int,
    accessibilityLabel: String,
    accessibilityStateDescription: String,
    rotationDegrees: Float,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnCancel by rememberUpdatedState(onCancel)
    Box(
        modifier = modifier
            .fillMaxSize()
            // Touch-anywhere cancellation remains, but this full-screen surface is deliberately
            // absent from the accessibility action tree. The shutter is the one semantic command.
            .pointerInput(Unit) {
                detectTapGestures(onTap = { currentOnCancel() })
            },
    ) {
        TimerCountdown(
            seconds = seconds,
            accessibilityLabel = accessibilityLabel,
            accessibilityStateDescription = accessibilityStateDescription,
            modifier = Modifier.fillMaxSize(),
            rotationDegrees = rotationDegrees,
        )
    }
}

/** One semantics projection for the preview's capability-dependent focus commands. */
internal fun Modifier.viewfinderFocusSemantics(
    contentDescription: String,
    stateDescription: String? = null,
    availability: ViewfinderFocusActionAvailability,
    focusAtCenterLabel: String,
    resetFocusPointLabel: String,
    onFocusAtCenter: () -> Unit,
    onResetFocusPoint: () -> Unit,
): Modifier = semantics {
    this.contentDescription = contentDescription
    stateDescription?.let { this.stateDescription = it }
    customActions = buildList {
        if (availability.focusAtCenter) {
            add(CustomAccessibilityAction(focusAtCenterLabel) {
                onFocusAtCenter()
                true
            })
        }
        if (availability.resetFocusPoint) {
            add(CustomAccessibilityAction(resetFocusPointLabel) {
                onResetFocusPoint()
                true
            })
        }
    }
}

internal fun Modifier.viewfinderKeyboardActions(
    availability: ViewfinderFocusActionAvailability,
    onFocusAtCenter: () -> Unit,
    onResetFocusPoint: () -> Unit,
): Modifier = this
    .onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.DirectionCenter -> {
                if (!availability.focusAtCenter) return@onPreviewKeyEvent false
                onFocusAtCenter()
                true
            }
            Key.Backspace, Key.Delete -> {
                if (!availability.resetFocusPoint) return@onPreviewKeyEvent false
                onResetFocusPoint()
                true
            }
            else -> false
        }
    }
    .focusable(enabled = availability.focusAtCenter || availability.resetFocusPoint)

private enum class ModalFocusOrigin { SETTINGS, FUNCTION_MENU, GALLERY }

/**
 * One quiet state description on the durable viewfinder node.
 *
 * AF keeps its current inspectable state here while [FocusResultLiveRegion] owns only terminal
 * change announcements. The horizon uses five-degree buckets and no live region, so the sensor can
 * update its Canvas freely without making accessibility speech chatter at frame rate.
 */
@Composable
internal fun localizedViewfinderStateDescription(
    afIndication: AfIndication,
    afActive: Boolean,
    levelEnabled: Boolean,
    levelRollDegrees: Float,
    deviceOrientation: Int,
): String? {
    val af = if (afActive) {
        stringResource(
            when (afIndication) {
                AfIndication.FOCUSED -> R.string.a11y_focus_locked
                AfIndication.FAILED -> R.string.a11y_autofocus_failed
                AfIndication.SCANNING -> R.string.a11y_autofocus_searching
                AfIndication.IDLE -> R.string.a11y_focus_point
            },
        )
    } else {
        null
    }
    val horizon = if (levelEnabled) {
        horizonAccessibilityState(
            levelDeviationDegrees(levelRollDegrees, deviceOrientation),
        )?.let { state ->
            when (state.direction) {
                HorizonAccessibilityDirection.LEVEL -> stringResource(R.string.a11y_horizon_level)
                HorizonAccessibilityDirection.LEFT -> stringResource(
                    R.string.a11y_horizon_tilt_left,
                    state.degrees,
                )
                HorizonAccessibilityDirection.RIGHT -> stringResource(
                    R.string.a11y_horizon_tilt_right,
                    state.degrees,
                )
            }
        }
    } else {
        null
    }
    return when {
        af != null && horizon != null -> stringResource(R.string.a11y_status_join, af, horizon)
        af != null -> af
        else -> horizon
    }
}

/**
 * Root camera UI, styled after Sony Alpha / Xperia Pro operation: a clear viewfinder at rest, compact
 * status readouts, and a bottom cluster of manual "Fn" dials + mode switch + shutter. Everything else
 * lives one tap away in [ProSheet], a Sony-menu-style tabbed settings system. Stateless: everything
 * shown comes from [state], every interaction is forwarded to
 * [actions]. Hosts the GL preview via a [TextureView] (an external GL thread renders into its
 * SurfaceTexture) and layers overlays and chrome on top.
 */
@Composable
fun CameraScreen(
    state: CameraUiState,
    actions: CameraActions,
    modifier: Modifier = Modifier,
    // Narrow host seam for production-composition tests. Shipping keeps the TextureView factory
    // below; tests may substitute a plain View while exercising the real CameraScreen chrome and
    // modal doors without starting a native preview surface.
    previewViewFactory: ((android.content.Context) -> android.view.View)? = null,
    // A plain ComposeRule context has no Display. Production reads the real window; the same narrow
    // host seam lets production-composition tests pin the phone's inert ROTATION_0 contract.
    windowRotationOverrideDeg: Int? = null,
) {
    val a11yCameraViewfinder = stringResource(R.string.a11y_camera_viewfinder)
    val a11yLoupeOverview = stringResource(R.string.a11y_loupe_overview)
    val a11ySelfTimer = stringResource(R.string.a11y_self_timer)
    val a11yFocusAtCenter = stringResource(R.string.a11y_focus_at_center)
    val a11yResetFocusPoint = stringResource(R.string.a11y_reset_focus_point)
    var sheetVisible by remember { mutableStateOf(false) }
    // Start preview-first. DISP adds the detailed OSD and inline dials for deliberate setup; compact
    // mode still preserves active/critical state and opens one requested ruler at a time.
    var detailsVisible by remember { mutableStateOf(false) }
    var openManualDial by remember { mutableStateOf<DialType?>(null) }
    // In-app review overlay (last saved still, pinch-to-zoom for focus check). The complete frozen
    // identity lives in CameraUiState, not in this composition: camera policy can temporarily replace
    // CameraScreen with PermissionGate, and the returning screen must reconstruct the exact review
    // rather than strand its ViewModel family pin and REVIEW input owner behind no visible overlay.
    // Remembers the last-viewed settings tab so the gear reopens where the user left off.
    var sheetInitialTab by remember { mutableStateOf(ProSheetTab.MY_MENU) }
    // A programmatic open is an EVENT, not just a tab value. My Menu's non-manual WB row dismisses
    // and reopens the sheet in one callback; Compose can batch false -> true without ever disposing
    // ProSheet, so the old tab's unkeyed remember survives unless this identity advances.
    var sheetOpenRequestId by remember { mutableLongStateOf(0L) }
    var fnOverlayVisible by remember { mutableStateOf(false) }
    val settingsFocusRequester = remember { FocusRequester() }
    val functionMenuFocusRequester = remember { FocusRequester() }
    val galleryFocusRequester = remember { FocusRequester() }
    val focusRestoreScope = rememberCoroutineScope()
    var modalFocusOrigin by remember { mutableStateOf<ModalFocusOrigin?>(null) }
    val currentActions = rememberUpdatedState(actions)
    val modalVisible = sheetVisible || fnOverlayVisible ||
        state.openReview != null || state.ownerlessDeleteConsentPending

    // MainActivity owns hardware camera keys outside Compose. Mirror every full-screen modal into
    // CameraUiState so volume/camera/zoom/focus input cannot operate the hidden viewfinder behind it.
    LaunchedEffect(modalVisible) {
        currentActions.value.onCameraInputBlockedChange(modalVisible)
    }
    LaunchedEffect(state.openReview) {
        if (state.openReview != null) {
            if (modalFocusOrigin == null) modalFocusOrigin = ModalFocusOrigin.GALLERY
        } else if (modalFocusOrigin == ModalFocusOrigin.GALLERY) {
            modalFocusOrigin = null
            repeat(2) { withFrameNanos { } }
            galleryFocusRequester.requestFocus()
        }
    }
    LaunchedEffect(detailsVisible, modalVisible) {
        val exposed = detailsVisible && !modalVisible
        currentActions.value.onStandbyAudioMeterVisibilityChanged(exposed)
        // Same exposure truth gates the ~6 Hz histogram/waveform publication (perf review #6):
        // the scope overlays compose only under detailsVisible, and a full-screen modal above
        // them leaves no composed consumer either.
        currentActions.value.onScopesVisibilityChanged(exposed)
        // The MANUAL exposure meter renders without expanded DISP but is still hidden by any modal.
        currentActions.value.onExposureMeterVisibilityChanged(!modalVisible)
    }
    DisposableEffect(Unit) {
        onDispose {
            currentActions.value.onStandbyAudioMeterVisibilityChanged(false)
            currentActions.value.onScopesVisibilityChanged(false)
            currentActions.value.onExposureMeterVisibilityChanged(false)
        }
    }

    fun openSheet(tab: ProSheetTab) {
        modalFocusOrigin = ModalFocusOrigin.SETTINGS
        currentActions.value.onCameraInputBlockedChange(true)
        sheetInitialTab = tab
        sheetOpenRequestId += 1L
        sheetVisible = true
    }

    fun restoreFocusAfterModal(requester: FocusRequester) {
        focusRestoreScope.launch {
            // One frame applies modal removal/finder re-enablement; the second addresses the opener
            // after its focus target participates in the new tree.
            repeat(2) { withFrameNanos { } }
            requester.requestFocus()
        }
    }

    fun selectManualDial(type: DialType) {
        val controls = state.controls
        val transition = manualDialTransition(
            requested = type,
            currentlyOpen = openManualDial,
            exposureMode = controls.exposureMode,
            focusMode = controls.focusMode,
            wbMode = controls.wbMode,
        )
        transition.exposureMode?.let(currentActions.value::onExposureMode)
        transition.focusMode?.let(currentActions.value::onFocusMode)
        openManualDial = transition.openDial
        if (transition.openExposureSheet) openSheet(ProSheetTab.EXPOSURE)
    }

    // The app WINDOW's rotation away from the device's natural orientation. Locked portrait is
    // always ROTATION_0, so every term derived from this is inert on a phone — but from Android 16 a
    // display whose smaller side is >= 600dp IGNORES screenOrientation and hands this activity a
    // LANDSCAPE window (API 37 removes the opt-out; see docs/BACKLOG.md). Keyed on LocalConfiguration
    // so it re-reads on a configuration change: the activity declares configChanges for orientation,
    // so it is NOT recreated and only recomposition can pick the new value up.
    val windowConfiguration = LocalConfiguration.current
    val windowContext = LocalContext.current
    val windowRotationDeg = remember(windowConfiguration, windowRotationOverrideDeg) {
        windowRotationOverrideDeg ?: run {
            when (windowContext.display.rotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
        }
    }
    // The GL preview draw needs the same term to keep the field upright in a rotated window. Sent
    // through CameraActions like every other interaction; the engine parks it in the replayed
    // renderer snapshot so a GL generation replacement cannot lose it.
    LaunchedEffect(windowRotationDeg) {
        currentActions.value.onWindowRotationChanged(windowRotationDeg)
    }

    // Counter-rotates compact on-screen glyphs/labels so they stay upright as the phone turns.
    // GyroEis derives the discrete device value from gravity via atan2(x,y), which yields dev=90 for
    // a COUNTER-clockwise (left) landscape and dev=270 for a clockwise (right) landscape — the
    // opposite of the naive assumption; a −dev sign left both landscapes 180° off (invisible on
    // symmetric icons, obvious once text rotates). Since 2026-08-04 the glyph takes only the
    // RESIDUAL between gravity and the WINDOW (RotationMath.glyphRotationDegrees): when the window
    // has already turned with the device the layout is upright on its own and no rotation is owed,
    // where the old bare +dev would have laid every label on its side. Locked portrait is
    // ROTATION_0, so this reduces exactly to the historical +dev.
    // Accumulate an UNWRAPPED target so the animation always takes the shortest ≤90° path.
    val glyphRotationDeg = RotationMath.glyphRotationDegrees(
        state.deviceOrientation,
        windowRotationDeg,
        windowFollowsDevice(windowConfiguration.smallestScreenWidthDp),
    )
    var overlayRotationTarget by remember { mutableFloatStateOf(glyphRotationDeg.toFloat()) }
    LaunchedEffect(glyphRotationDeg) {
        overlayRotationTarget = shortestRotationTarget(overlayRotationTarget, glyphRotationDeg.toFloat())
    }
    val overlayRotation by animateFloatAsState(targetValue = overlayRotationTarget, label = "overlayRotation")

    // Live zoom readout: show a bar + "N.N×" whenever the zoom ratio CHANGES (including a genuine
    // move to exactly 1×), then fade it out ~1.4 s after the last change. Dropping snapshotFlow's
    // initial sample prevents a restored non-1× setup from flashing the pill on launch.
    var zoomVisible by remember { mutableStateOf(false) }
    // snapshotFlow + collectLatest instead of keying the effect on the raw ratio: keying restarted
    // (cancel + relaunch) a coroutine PER zoom tick — touch-sample rate during a pinch, ~30 Hz on a
    // hardware-slide glide — pure dispatcher churn on the busiest gesture. One long-lived collector
    // now watches the value; collectLatest restarts only the fade delay.
    val zoomRatioState = rememberUpdatedState(state.controls.zoomRatio)
    LaunchedEffect(Unit) {
        snapshotFlow { zoomRatioState.value }.drop(1).collectLatest {
            zoomVisible = true
            delay(1400)
            zoomVisible = false
        }
    }

    // In auto-exposure modes the full-height meter is feedback, not persistent status. Show it
    // briefly after an EV change; Manual keeps the live scene meter because it is part of exposure
    // operation. The compact Fn value remains available without covering the frame at rest.
    var exposureMeterTransient by remember { mutableStateOf(false) }
    val exposureCompensationState = rememberUpdatedState(state.controls.exposureCompensation)
    LaunchedEffect(Unit) {
        snapshotFlow { exposureCompensationState.value }.drop(1).collectLatest {
            exposureMeterTransient = true
            delay(1400)
            exposureMeterTransient = false
        }
    }

    // The VIEWFINDER is physical camera geometry, not text flow: the loupe-overview border is
    // AbsoluteAlignment.BottomLeft, the Fn entry chip and tray are AbsoluteAlignment + absolutePadding
    // (both already force Ltr locally), and the GL scissor box is in raw window coordinates — while
    // the status OSD, the scopes column, the exposure meter and the gallery thumb are locale-relative
    // Start/End. Under an RTL system language the HUD therefore HALF-mirrored: the exposure meter
    // (documented as pinned to the LEFT edge, "the scopes own the right") swapped with the scopes
    // while the absolutely-placed elements stayed put. One Ltr scope over the whole viewfinder keeps
    // the two coordinate systems from disagreeing — the same reasoning already applied twice locally,
    // applied once at the root. ProSheet / FnOverlay / MediaReview are emitted OUTSIDE this Box and
    // stay locale-relative: they are reading surfaces, not camera geometry.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CameraColors.Background)
            // Apply the exclusion owner only while a modal exists. Leaving a canFocus=true parent
            // focusProperties node installed after close intercepted exact opener requesters.
            .then(if (modalVisible) Modifier.finderFocusEnabled(false) else Modifier)
            .then(if (modalVisible) Modifier.clearAndSetSemantics { } else Modifier),
    ) {
        // LIVE since 2026-08-04. This stayed dormant while "GL sampling, capture masks, tap mapping
        // and encoder framing" shared a portrait-window contract; that contract is now broken in the
        // three places that actually assumed it, and the two that must NOT change were left alone
        // deliberately:
        //   - preview rotation + displayed aspect: RotationMath.windowPreviewRotationDegrees /
        //     displayedPreviewAspect, applied through the per-call rotationOverrideDeg.
        //     DEVICE-VERIFIED on TB336ZU — the brightness asymmetry moved top → left (90° CCW, the
        //     designed sign) and the preview width in a 2560-wide window went 1216 → 2560 px.
        //   - tap mapping: RotationMath.unrotateViewPoint inside mapTapFocusGeometry.
        //   - glyph counter-rotation: RotationMath.glyphRotationDegrees.
        //   - capture masks and encoder framing are UNCHANGED ON PURPOSE. Both derive from GRAVITY,
        //     not from window shape, so a clip records the same field however the window is turned.
        // Chrome has one full-width arrangement on every window: bar along the top, capture cluster
        // along the bottom. Orientation moves no control; only what must be READ rotates through
        // `overlayRotation` (2026-08-05 owner decision). The preview therefore reserves no operator
        // side column and stays centered in the complete window width.
        //
        // It holds because handsets stay portrait-LOCKED (MainActivity.lockPortraitOnHandsets): a
        // turned handset window is ~420 dp tall and a top bar plus a bottom cluster would eat it
        // from both ends. Large screens rotate freely and have the height to spare — verified on
        // TB336ZU at 2560x1600, where the same cluster sits along the bottom with room above it.
        // previewAspect is the field as it displays in the device's NATURAL orientation; a rotated
        // window shows it W/H-swapped. Device-measured on TB336ZU (2026-08-04): without the swap a
        // 2560x1600 landscape window drew the portrait 3:4 box at ~1200x1600 and pillarboxed away
        // ~53% of the width. Inert at ROTATION_0, so the phone keeps the exact prior box.
        val displayedPreviewAspect = RotationMath
            .displayedPreviewAspect(state.previewAspect, windowRotationDeg)
            .coerceAtLeast(0.01f)
        // Rest-state height of the bottom cluster, feeding [previewTopPx]. Frozen while a manual
        // dial is open: the cluster growing upward must overlay the preview like every transient
        // panel, not shove the viewfinder around mid-interaction.
        var bottomClusterRestHeightPx by remember { mutableIntStateOf(0) }
        // Published by the preview placement below and consumed by the Loupe Overview border; the
        // engine forwards the same value to GL so the scissor box and this border stay one rect.
        var finderBottomClearanceFraction by remember { mutableFloatStateOf(0f) }
        val sixteenDpPx = with(LocalDensity.current) { 16.dp.roundToPx() }
        // The preview box's resolved top, exported for the TopBar's seam rule (the bar renders in a
        // SIBLING scope of the BoxWithConstraints that computes it).
        var previewTopForChromePx by remember { mutableIntStateOf(0) }
        // The viewfinder is LETTERBOXED, not cover-cropped: the TextureView (plus every overlay that
        // must align with the image frame) lives in a box sized to the displayed preview aspect, so
        // the FULL capture field is always visible. Letterboxing at the Compose layer — instead of
        // scaling down inside GL — keeps three things correct for free: the GL surface is exactly
        // content-aspect so FlipRenderer's "cover" is a 1:1 fit (no crop), tap coordinates
        // normalize directly to the visible frame, and the AE/scope luma readback never sees black
        // bars (they exist only outside this box). VERTICAL PLACEMENT is adaptive, not centered:
        // see [previewTopPx] — the 4:3 preview biases upward so the bottom cluster (focal rail /
        // Fn / mode / shutter) sits below the image instead of straddling its bottom edge. The
        // offset only moves the box; every overlay and tap normalization is box-relative.
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            // FIT INSIDE both axes (see [previewBoxWidthPx]) — a landscape/split/freeform window is
            // height-bound, and the old width-bound-only math pushed the viewfinder off-window there.
            val bottomReserveForLayoutPx = bottomClusterRestHeightPx
            val previewWidthPx = previewBoxWidthPx(
                availableWidthPx = constraints.maxWidth,
                availableHeightPx = constraints.maxHeight,
                aspect = displayedPreviewAspect,
            )
            val previewHeightPx = (previewWidthPx / displayedPreviewAspect).toInt()
            val topOffsetPx = previewTopPx(
                availableHeightPx = constraints.maxHeight,
                previewHeightPx = previewHeightPx,
                // Status bar + the 56dp top icon row + the OSD strip line + breathing room. A dp
                // constant (not a measured top bar) keeps the preview from re-laying-out when the
                // OSD strip toggles; the strip overlays the letterbox area harmlessly either way.
                topChromeMinPx = with(density) {
                    WindowInsets.statusBars.getTop(this) + 100.dp.roundToPx()
                },
                bottomReservePx = bottomReserveForLayoutPx,
            )
            previewTopForChromePx = topOffsetPx
            // How far the preview runs BEHIND the bottom chrome, plus breathing room — the number
            // the Loupe Overview needs and the one no fraction of the preview box could stand in
            // for. Measured, the preview overshoots the chrome by 13 dp on a 411 dp phone in 4:3
            // and 90 dp on a 941 dp tablet, so a fraction tuned on either shape sat the overview
            // on the focal rail on the other. Kept as a FRACTION of the preview height because GL
            // resolves the same rect in pixels from its own surface size; a fraction is the only
            // form both can read without a unit conversion.
            finderBottomClearanceFraction = if (previewHeightPx > 0) {
                val previewBottomPx = topOffsetPx + previewHeightPx
                val chromeTopPx = constraints.maxHeight - bottomReserveForLayoutPx
                val overshootPx = (previewBottomPx - chromeTopPx).coerceAtLeast(0)
                (overshootPx + sixteenDpPx).toFloat() / previewHeightPx
            } else 0f
            // Push to the engine so GL scissors the overview to the same rect this border draws.
            // LaunchedEffect keyed on the value: it changes only when the aspect, the mode or the
            // window size does, never at frame rate.
            LaunchedEffect(finderBottomClearanceFraction) {
                currentActions.value.onFinderBottomClearanceChanged(finderBottomClearanceFraction)
            }
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                // TopCenter centres the frame across the complete window width. The one-layout rule
                // has no operator side columns to reserve or compensate for.
                .offset { IntOffset(0, topOffsetPx) }
                // Explicit width (not fillMaxWidth) so a height-bound window letterboxes on the
                // SIDES; TopCenter then centres it horizontally. aspectRatio still derives the
                // height, keeping one source of truth for the box shape.
                .width(with(density) { previewWidthPx.toDp() })
                .aspectRatio(displayedPreviewAspect),
        ) {
            val finderVisible = teleFinderVisible(
                enabled = state.teleFinder,
                teleconverter = state.teleconverterMode,
                videoMode = state.mode == CaptureMode.VIDEO,
                aspect = state.aspectRatio,
                punchIn = state.punchInActive,
                zoomRatio = state.unifiedZoom,
            )
            // Read inside the gesture loop through rememberUpdatedState, NOT as a pointerInput key.
            // The predicate carries the LIVE zoom, so a pinch crossing FINDER_MIN_ZOOM flips it
            // mid-gesture; keying pointerInput on it restarted the coroutine under the operator's
            // fingers — the restarted awaitFirstDown never matches already-pressed pointers, so zoom
            // froze for the rest of the pinch and onPinchEnd never fired (review C9).
            val liveFinderVisible by rememberUpdatedState(finderVisible)
            val focusActionAvailability = viewfinderFocusActionAvailability(
                cameraReady = state.cameraReady,
                maxAfRegions = state.caps?.maxAfRegions ?: 0,
                focusMode = state.controls.focusMode,
                afModes = state.caps?.afModes ?: IntArray(0),
                tapFocusHeld = state.tapFocusHeld,
            )
            val viewfinderStateDescription = localizedViewfinderStateDescription(
                afIndication = state.afIndication,
                afActive = state.tapPoint != null || state.tapFocusHeld,
                levelEnabled = state.level,
                levelRollDegrees = state.levelRoll,
                deviceOrientation = state.deviceOrientation,
            )
            var viewfinderKeyboardFocused by remember { mutableStateOf(false) }
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .onFocusChanged { viewfinderKeyboardFocused = it.isFocused }
                    .then(
                        if (viewfinderKeyboardFocused) {
                            Modifier
                                .border(3.dp, Color.Black)
                                .border(1.dp, CameraColors.Accent)
                        } else {
                            Modifier
                        },
                    )
                    .viewfinderFocusSemantics(
                        contentDescription = a11yCameraViewfinder,
                        stateDescription = viewfinderStateDescription,
                        availability = focusActionAvailability,
                        focusAtCenterLabel = a11yFocusAtCenter,
                        resetFocusPointLabel = a11yResetFocusPoint,
                        onFocusAtCenter = { currentActions.value.onTapFocus(0.5f, 0.5f) },
                        onResetFocusPoint = { currentActions.value.onResetFocusPoint() },
                    )
                    .viewfinderKeyboardActions(
                        availability = focusActionAvailability,
                        onFocusAtCenter = { currentActions.value.onTapFocus(0.5f, 0.5f) },
                        onResetFocusPoint = { currentActions.value.onResetFocusPoint() },
                    )
                    // Tap-to-focus AND pinch-to-zoom share ONE gesture loop. Two separate pointerInput
                    // blocks (detectTapGestures + detectTransformGestures) fought each other: the tap
                    // detector consumed the gesture and killed the pinch after ~2 frames, so the pinch
                    // scale never left 1.0 (device-diagnosed via ZoomDbg). Handling both in a single
                    // awaitEachGesture removes the conflict: two fingers → pinch-zoom, a clean single
                    // stationary touch → tap-focus.
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var maxPointers = 1
                            var zoomed = false
                            var dragged = false
                            var cancelled = false
                            while (true) {
                                val event = awaitPointerEvent()
                                // Android ACTION_CANCEL and ACTION_UP both collapse to a
                                // pressed=false PointerInputChange. Retain the raw terminal identity
                                // so the overlay-defense cancel cannot manufacture a tap action.
                                val rawMotionEvent = event.motionEvent
                                if (
                                    rawMotionEvent == null ||
                                    rawMotionEvent.actionMasked == MotionEvent.ACTION_CANCEL
                                ) {
                                    cancelled = true
                                    break
                                }
                                val pressed = event.changes.count { it.pressed }
                                if (pressed == 0) break
                                maxPointers = maxOf(maxPointers, pressed)
                                if (pressed >= 2) {
                                    val zoom = event.calculateZoom()
                                    if (zoom != 1f) {
                                        zoomed = true
                                        currentActions.value.onPinchZoom(zoom)
                                    }
                                    event.changes.forEach { it.consume() }
                                } else {
                                    val cur = event.changes.firstOrNull { it.id == down.id }?.position
                                    if (cur != null && (cur - down.position).getDistance() > viewConfiguration.touchSlop) {
                                        dragged = true
                                    }
                                }
                            }
                            // Report the TRUE pinch boundary (AGG4-14). The ViewModel otherwise has
                            // to infer finger-up from the 250 ms quiet landing, which leaves a
                            // re-pinch started inside the previous gesture's 700 ms tail without its
                            // zoom-OUT leading edge. Gated on `zoomed` so a tap or a single-finger
                            // drag — neither of which touched the zoom pipeline — cannot claim to
                            // have ended a pinch.
                            if (zoomed) currentActions.value.onPinchEnd()
                            // Only a clean single-finger tap (no second finger, no pinch, no drag) focuses.
                            if (
                                maxPointers == 1 && !zoomed && !dragged &&
                                !cancelled
                            ) {
                                val w = size.width.toFloat()
                                val h = size.height.toFloat()
                                if (w > 0f && h > 0f) {
                                    if (!liveFinderVisible || !finderContainsTopLeftPoint(
                                            pointX = down.position.x,
                                            pointY = down.position.y,
                                            boxWidth = w,
                                            boxHeight = h,
                                        )
                                    ) {
                                        currentActions.value.onTapFocus(down.position.x / w, down.position.y / h)
                                    }
                                }
                            }
                        }
                    },
                factory = previewViewFactory ?: { context ->
                    // TextureView (not SurfaceView): its content composites inside the view hierarchy, so
                    // the GL preview draws over the opaque Compose background and the Compose overlays
                    // (grid/reticle/chrome) layer on top of it. A SurfaceView's surface sits behind the
                    // app window and would be occluded by the background — the source of the black preview.
                    var previewSurface: Surface? = null
                    TextureView(context).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(
                                texture: SurfaceTexture,
                                width: Int,
                                height: Int,
                            ) {
                                val surface = Surface(texture)
                                previewSurface = surface
                                currentActions.value.onPreviewSurfaceAvailable(surface, width, height)
                            }

                            override fun onSurfaceTextureSizeChanged(
                                texture: SurfaceTexture,
                                width: Int,
                                height: Int,
                            ) {
                                currentActions.value.onPreviewSurfaceChanged(width, height)
                            }

                            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                currentActions.value.onPreviewSurfaceDestroyed()
                                previewSurface?.release()
                                previewSurface = null
                                return true
                            }

                            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                        }
                    }
                },
            )

            // Framing-coupled overlays stay INSIDE the aspect box so grid/frame-lines/crop-mask/reticle
            // geometry maps 1:1 onto the visible image, not onto the whole screen.
            GridOverlay(type = state.grid, modifier = Modifier.fillMaxSize())

            // Bare calls like GridOverlay above: each of these three owns its own "draw nothing"
            // early return and says so in its KDoc, so an outer guard only states the predicate a
            // second time, in a second file, free to drift from it.
            FrameLinesOverlay(type = state.frameLines, modifier = Modifier.fillMaxSize())

            AspectMask(ratio = state.aspectRatio, modifier = Modifier.fillMaxSize())

            FocusReticle(point = state.tapPoint, indication = state.afIndication, modifier = Modifier.fillMaxSize())
            FocusResultLiveRegion(
                indication = state.afIndication,
                active = state.tapFocusHeld,
                modifier = Modifier.size(1.dp),
            )

            // The level belongs to the FRAMING overlays, so it lives inside the aspect box with the
            // grid. Outside it (where this used to sit) `fillMaxSize` is the whole SCREEN, so the
            // gauge centred on the screen's midpoint rather than the image's — visibly low, because
            // the bottom control cluster is taller than the top bar (user-reported 2026-07-28;
            // measured ~1581 px against an image centre of ~1545).
            if (state.level) {
                LevelOverlay(
                    modifier = Modifier.fillMaxSize(),
                    rollDegrees = state.levelRoll,
                    deviceOrientation = state.deviceOrientation,
                )
            }

            // Shutter blink: a ~90 ms black flash over the image the instant the shutter fires —
            // the still itself takes pipeline-depth × frame-duration before exposing, and with no
            // immediate acknowledgment every press reads as lag (user-reported). Inside the aspect
            // box so only the image area blinks, mirror-style.
            var shutterBlink by remember { mutableStateOf(false) }
            LaunchedEffect(state.shutterFlashTick) {
                if (state.shutterFlashTick > 0) {
                    shutterBlink = true
                    kotlinx.coroutines.delay(90)
                    shutterBlink = false
                }
            }
            if (shutterBlink) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)))
            }

            // Loupe Overview border: frames a re-draw of the FULL current-camera stream, not a 1x
            // camera feed. Exact predicate: user toggle + active punch-in + (TELE or unified zoom
            // >= 3x). Photo additionally requires 4:3; Video ignores the unrelated still aspect.
            // The shared teleFinderVisible predicate is the same gate the engine resolves for GL,
            // so the border and overview content cannot drift. The rect comes from the same pure finderRect
            // the GL scissor uses — sized from the FULL aspect box, with a right inset and measured
            // bottom-chrome clearance (the previous padding-before-fillMaxWidth chain shrank it ~6% below
            // the GL content box). Absolute anchor + absolute offset: the GL box has no layout
            // direction, so the
            // border must not mirror to bottom-left under RTL system locales. Square corners trace
            // the sharp GL scissor rect.
            if (finderVisible) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val rect = finderRect(
                        boxWidth = maxWidth.value,
                        boxHeight = maxHeight.value,
                        bottomClearance = maxHeight.value * finderBottomClearanceFraction,
                    )
                    Box(
                        modifier = Modifier
                            .align(AbsoluteAlignment.BottomLeft)
                            .absoluteOffset(x = rect.x.dp, y = (-rect.y).dp)
                            .size(rect.width.dp, rect.height.dp)
                            // One-off structural stroke, not ink and not a composition guide: it
                            // traces the GL scissor rect of a SECOND rendering of the camera frame,
                            // so it must read against arbitrary live pixels on both of its sides.
                            // Hence 0.85 rather than the 0.40 GuideLine the thirds/frame-line rules
                            // use — those sit over ONE image and may recede; this one delimits two.
                            .border(1.dp, Color.White.copy(alpha = 0.85f))
                            .semantics {
                                // Name only. The node declares no actions, so TalkBack already
                                // announces it as non-interactive; a stateDescription saying so spent
                                // a spoken line restating what the absence of an action already says.
                                contentDescription = a11yLoupeOverview
                            }
                            // Consume the overview's pointer stream as well as guarding the viewfinder's
                            // focus dispatch. It is a framing reference, never a second focus plane.
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false).consume()
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        event.changes.forEach { it.consume() }
                                        if (event.changes.none { it.pressed }) break
                                    }
                                }
                            },
                    )
                }
            }

            // Camera-switch dip (ui/SwitchCoverPolicy.kt owns WHEN; this owns only how it looks).
            // Drawn in COMPOSE, above the preview, never in GL: `drawFrame` early-returns when
            // there is no texture update and no preview EGL surface, so a GL-side fade would
            // freeze mid-animation at exactly the photo↔video EGLSurface recreate, would need its
            // own redraw ticker, would have to be excluded from the encoder and analysis draws,
            // and would add swaps while EGL ownership is being handed around. A Compose layer
            // composites independently of the camera and cannot be starved by a dead producer.
            // Scoped to the aspect box like the shutter blink, and emitted LAST inside it: during a
            // reopen nothing image-derived is trustworthy, so the grid, frame lines, aspect mask,
            // reticle and loupe-overview border — all composition guides for an image that is not
            // there — go under it too. Everything that says WHICH state the user switched to lives
            // OUTSIDE this box (OSD/status row, focal rail, TELE chip, mode carousel, shutter) and
            // is emitted later still, so it stays fully readable across the dip. No pointerInput:
            // the cover must not change what a tap during a switch does.
            // Asymmetric timing: the fade-IN races the outgoing stream's death, while the fade-OUT
            // also covers the gap between the terminal Ready commit and the first delivered frame
            // of the new stream.
            androidx.compose.animation.AnimatedVisibility(
                visible = state.switchCoverVisible,
                enter = fadeIn(tween(110)),
                exit = fadeOut(tween(220)),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(modifier = Modifier.fillMaxSize().background(CameraColors.Background))
            }
        }
        }

        // Emphasized REC display (Sony FX): a thin red frame while rolling — unmissable, even in
        // DISP-clean mode. Screen-fixed (not content-boxed) so it stays unmissable at every aspect.
        if (state.isRecording && !state.isRecordingStarting) {
            // Tally border: follow the panel's physical rounded corners — a square border's corner
            // segments fall OUTSIDE the visible rounded area on this display and simply vanish
            // (user-reported). The exact radius comes from the WindowInsets RoundedCorner API.
            val tallyView = LocalView.current
            val tallyRadius = remember(tallyView) {
                val corner = tallyView.rootWindowInsets
                    ?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)
                // Use the radius the panel REPORTS, unscaled.
                //
                // This was ×1.2 to make a circular arc "read like" the glass squircle. It does the
                // opposite where it matters: a larger R turns the corner sooner, so the border's
                // straight runs end early and it visibly pulls away from the edges — reported on
                // device as a gap along each side (2026-07-29). A tally is an EDGE indicator first;
                // hugging the sides matters more than matching the curvature of the corner, and the
                // reported radius is the only figure that is actually about this panel.
                (corner?.radius ?: 0)
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(
                        3.dp,
                        CameraColors.Record,
                        RoundedCornerShape(with(LocalDensity.current) { tallyRadius.toDp() }),
                    ),
            )
        }

        // The OSD row and the button row are ONE piece of chrome and must move together. This
        // offset used to be computed just above TopBar and applied only there, so in 16:9 video —
        // the one aspect whose preview starts high enough to trigger it — the buttons shifted down
        // onto the OSD row, which is pinned at a fixed 60 dp. Device-measured: buttons y=332-500
        // over OSD text at y=391-436, with STEADY/LOUPE/battery squeezed into the 28 px gaps
        // between buttons. Hoisted here so the row below can take the same shift.
        // When the preview is too tall to clear the top chrome (the 16:9 portrait frame:
        // previewTopPx's centered branch fires and the image starts ABOVE the chips' default home),
        // the chip plates used to STRADDLE the seam — a ~10 dp sliver of each translucent plate over
        // the image, the rest invisible on the black band, reading as amputated bumps (UI review
        // #5, measured). The rule the seam work already established: chrome sits wholly on the
        // image or wholly on the band, never across the edge. Here only "wholly on the image" is
        // possible (the band above is what is too short), so the bar shifts down to previewTop+8dp
        // exactly when its default home would collide.
        val topBarDensity = LocalDensity.current
        val statusBarPx = WindowInsets.statusBars.getTop(topBarDensity)
        val eightDpPx = with(topBarDensity) { 8.dp.roundToPx() }
        var topBarHeightPx by remember { mutableIntStateOf(0) }
        val topBarDefaultTopPx = statusBarPx + eightDpPx
        // Offset ONLY on a genuine straddle — the preview's top edge falling INSIDE the bar's
        // default vertical span. Fully-on-band (4:3: preview starts below the bar) and
        // fully-on-image placements are both fine as-is; an unconditional offset pushed the bar
        // down onto the image even when its default home was wholly on the band (caught on-device
        // during this fix's own verification).
        val topBarSeamOffsetPx = if (
            topBarHeightPx > 0 &&
            previewTopForChromePx > topBarDefaultTopPx &&
            previewTopForChromePx < topBarDefaultTopPx + topBarHeightPx
        ) {
            previewTopForChromePx + eightDpPx - topBarDefaultTopPx
        } else {
            0
        }

        Row(
            // The full-width CONTAINER stays fixed — spinning a fillMaxWidth row would swing a
            // screen-wide box off screen no matter how the slot is reserved. Its children rotate
            // individually below, each of which sizes to its own content.
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .fillMaxWidth()
                // The same 12 dp edge inset applies in every window shape. The 60 dp top inset clears
                // the single full-width horizontal button row; orientation never moves it to a side.
                .padding(start = 12.dp, end = 12.dp, top = 60.dp)
                // Same shift as the button row above: they are one piece of chrome, and the fixed
                // 60 dp only clears the buttons while the buttons are in their default home.
                .offset { IntOffset(0, topBarSeamOffsetPx) },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            StatusBar(
                state = state,
                compact = !detailsVisible,
                // The status plate (STEADY/OIS+/MUTE/4:3, the focal readout, the lock tags) now
                // counter-rotates with the phone like every other readout. It was left screen-fixed
                // when the only tool was `Modifier.rotate`, a DRAW transform that leaves the
                // unrotated box in layout — which is what "swings it off screen" meant. rotateLayout
                // reserves the rotated axis-aligned bounds CLAMPED to the parent's constraints and
                // measures the child along the axis its width actually runs after turning, which is
                // what already let the two scopes rotate without colliding. The plate sizes to its
                // content (`weight(fill = false)` + its own horizontalScroll), so it is not the
                // screen-wide box the old comment warned about.
                modifier = Modifier
                    .weight(1f, fill = false)
                    .rotateLayout(overlayRotation),
            )
            // Battery/shots-remaining lives in the chrome row, not floating inside the image: on
            // the 4:3 layout the old in-preview TopEnd anchor left it hovering over the frame
            // (user-reported as visual clutter).
            // Same treatment for its row-mate: leaving battery/shots screen-fixed beside a rotating
            // status plate would just move the inconsistency one element to the right.
            if (detailsVisible) {
                StatusInfoPill(state = state, modifier = Modifier.rotateLayout(overlayRotation))
            }
        }

        // One measured top-center lane owns every transient/held readout. Its first slot keeps the
        // focus states below the shooting OSD even when the zoom readout is hidden, while expanding
        // to the zoom's actual rotated/font-scaled height whenever it is visible.
        // Same OSD_CLEARANCE_TOP as the scopes column: this lane sat at 64 dp while the status row
        // runs ~60-88 dp, and StatusBar grows horizontally with its tag count (weight(1f, fill =
        // false) from x = 12 dp). A full-DISP video strip crosses the centered 180 dp zoom bar, so
        // the zoom pill landed on top of the OSD mid-pinch — the same overlap QA already hit on the
        // top-END column at 72 dp.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = osdClearanceTop()),
            horizontalAlignment = Alignment.CenterHorizontally,
            // Matches the taller scopes column on the top-end edge: two free-floating overlay lanes
            // on the same screen edge should not run different rhythms.
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.sizeIn(minHeight = 34.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = zoomVisible,
                    enter = fadeIn(tween(120)),
                    exit = fadeOut(tween(300)),
                ) {
                    val mul = zoomDisplayMultiplier(
                        state.teleconverterMode,
                        state.teleconverterMagnification,
                        state.caps?.equivalentFocalMm,
                        frontFacing = state.facing == CameraFacing.FRONT,
                        activeRoute = state.activeCameraRoute,
                    )
                    ZoomIndicator(
                        zoom = state.controls.zoomRatio * mul,
                        range = state.caps?.zoomRatioRange?.let {
                            val hi = if (state.teleconverterMode) {
                                minOf(it.upper * mul, me.hletrd.telecampro.camera.TELE_MAX_DISPLAY_ZOOM)
                            } else {
                                it.upper * mul
                            }
                            android.util.Range(minOf(it.lower * mul, hi), hi)
                        },
                        // ONLY the number turns. Rotating the whole indicator was tried and
                        // rejected on device: the bar is a horizontal scale and reads fine at any
                        // angle, while turning it swung a 180 dp-wide box through the chrome.
                        numberRotation = overlayRotation,
                    )
                }
            }

            if (showHalfPressLabel(state.halfPressActive, state.halfPressAction, state.tapFocusHeld)) {
                Text(
                    text = LocalContext.current.localizedLabel(state.halfPressAction),
                    color = CameraColors.ManualActive,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .rotateLayout(overlayRotation)
                        .clip(RoundedCornerShape(50))
                        .background(HudPlate)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            if (state.tapFocusHeld) {
                TapFocusHoldChip(
                    onReset = currentActions.value::onResetFocusPoint,
                    modifier = Modifier.rotateLayout(overlayRotation),
                )
            }
        }

        // Scopes/readouts stack in the top-end column, at the shared OSD_CLEARANCE_TOP that clears the
        // OSD status row (which ends ~90dp) — QA hit an overlap at 72dp. Each scope counter-rotates to stay horizontal as the phone
        // turns (rotateLayout reserves the ROTATED bounding box, so a 90° hold no longer makes the
        // histogram and waveform collide — the earlier plain rotate() did, which is why they were left
        // fixed before).
        // Keep camera controls spatially stable across portrait/landscape holds. Only compact labels
        // counter-rotate; the shutter/mode/Fn cluster stays anchored like a camera body control layout.
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(end = 12.dp, top = osdClearanceTop()),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.isRecording && !state.isRecordingStarting) {
                RecordingIndicator(elapsedMs = state.recordElapsedMs, modifier = Modifier.rotateLayout(overlayRotation))
            }
            // Sony-style standby metering: input levels are visible while video is ARMED,
            // not just while rolling (the engine runs a levels-only mic tap in standby).
            if (state.mode == CaptureMode.VIDEO && state.recordAudio && (detailsVisible || state.isRecording)) {
                AudioMeter(
                    levels = state.audioLevels,
                    overloads = state.audioOverloadStates,
                    modifier = Modifier.rotateLayout(overlayRotation),
                )
            }
            // Composed on the SAME predicate that gates their data publication (review 2026-08-01):
            // the Fn overlay's 22%-alpha scrim deliberately leaves chrome legible, so a scope left
            // composed under it read as a live instrument while its data was frozen pre-modal —
            // and in MANUAL the identical overlay kept updating, making the freeze look like a
            // bug. An instrument that cannot be live is not shown (same rule as the exposure
            // meter's Fn suppression).
            if (detailsVisible && !modalVisible && state.histogram) {
                HistogramOverlay(data = state.histogramData, modifier = Modifier.rotateLayout(overlayRotation))
            }
            if (detailsVisible && !modalVisible && state.waveform) {
                WaveformOverlay(data = state.waveformData, modifier = Modifier.rotateLayout(overlayRotation))
            }
        }

        if (state.timerCountdownSec > 0) {
            val countdownDescription = localizedTimerCountdownDescription(state.timerCountdownSec)
            // The 120 sp digit is the largest orientation-sensitive glyph on screen — a sideways
            // "6" reads ambiguously in a landscape self-timer, so it counter-rotates too.
            SelfTimerCountdownOverlay(
                seconds = state.timerCountdownSec,
                // "Self-timer": one feature, one spelling across every spoken string.
                accessibilityLabel = a11ySelfTimer,
                accessibilityStateDescription = countdownDescription,
                rotationDegrees = overlayRotation,
                onCancel = { currentActions.value.onCapturePhoto() },
            )
        }

        state.status?.let { status ->
            val message = status.resolve(LocalContext.current)
            // Centered transient toast ("Video saved" / errors). Previously pinned near the
            // top, where it collided with the OSD status row (300mm / codec / etc.) — QA-reported.
            // This is the channel for capture/permission/storage ERRORS, so its scrim rides the tested
            // contrast floor (05486cb) like every sibling pill — 0.55 cleared 4.5 only by a hair and
            // was one alpha tweak from regressing the app's most important on-screen text.
            CriticalCameraStatusPlate(
                message = message,
                livePriority = status.livePriority,
                overlayRotation = overlayRotation,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        TopBar(
            state = state,
            actions = actions,
            onOpenSheet = {
                openSheet(sheetInitialTab) // reopen to the remembered last tab
            },
            settingsModifier = Modifier.focusRequester(settingsFocusRequester),
            compact = !detailsVisible,
            onToggleDisp = {
                openManualDial = null
                detailsVisible = !detailsVisible
            },
            glyphRotation = overlayRotation,
            modifier = Modifier
                // The bar belongs to the full window on every device. TopBarContainer keeps its
                // scrolling leading group and fixed trailing group at the two window edges.
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 8.dp)
                .onSizeChanged { topBarHeightPx = it.height }
                .offset { IntOffset(0, topBarSeamOffsetPx) },
        )

        // Exposure meter: pinned to the LEFT edge as a vertical scale (the scopes own the right).
        // A fixed home beats the old jump between top/bottom as the dial opened (feedback).
        // Suppressed while the Fn overlay is open: the held-landscape tray anchors to the SAME
        // left column and drew straight through the meter under the translucent scrim
        // (user-reported 2026-07-31). A modal Fn owns the screen; the meter is not consultable
        // through a scrim anyway, and it returns the frame the overlay closes.
        if (!fnOverlayVisible &&
            shouldShowExposureMeter(state.controls.exposureMode, exposureMeterTransient)
        ) ExposureMeter(
            state = state,
            compact = !detailsVisible,
            modifier = Modifier
                .align(Alignment.CenterStart)
                // 12 dp start — the ONE left inset every left-anchored element shares (status OSD,
                // exposure meter, Fn chip row); mixed 10/12/16 insets read as misalignment.
                .padding(start = 12.dp),
            // Only the READOUT turns, not the scale. Rotating the whole meter was tried and
            // rejected on device: the bar is a vertical scale whose position carries the value and
            // it reads at any angle, while its figures do not (user-specified 2026-07-29).
            glyphRotation = overlayRotation,
        )

        val onShutter = remember(state.mode) {
            {
                if (state.mode == CaptureMode.PHOTO) {
                    currentActions.value.onCapturePhoto()
                } else {
                    currentActions.value.onToggleRecording()
                }
            }
        }

        val manualPane: @Composable () -> Unit = {
            ManualDialCluster(
                state = state,
                actions = actions,
                openDial = openManualDial,
                onSelectDial = ::selectManualDial,
                onCloseDial = { openManualDial = null },
                glyphRotation = overlayRotation,
                compact = !detailsVisible,
                onOpenFnMenu = {
                    modalFocusOrigin = ModalFocusOrigin.FUNCTION_MENU
                    currentActions.value.onCameraInputBlockedChange(true)
                    fnOverlayVisible = true
                },
                fnButtonModifier = Modifier.focusRequester(functionMenuFocusRequester),
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        val capturePane: @Composable () -> Unit = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    // GONE while FRONT (not disabled): the 0.6/1/3/10 presets are rear-lens
                    // concepts — the selfie route has exactly one lens, so a disabled rail would
                    // advertise choices that cannot exist (same rationale as the TELE chip).
                    if (state.activeCameraRoute == me.hletrd.telecampro.camera.CameraRoute.BACK && state.cameraRoutes.back) {
                        FocalRail(
                            state = state,
                            onLens = actions::onLens,
                            onTeleZoomMark = actions::onTeleZoomMark,
                            glyphRotation = overlayRotation,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (!detailsVisible) {
                        // Residual, not raw gravity — see the FnOverlay note: with the window free to
                        // turn, gravity alone would move Fn to the thumb edge of a layout that had
                        // already moved. It also made two identical tablets disagree (Fn LEFT of the
                        // chips on TB331FC, RIGHT on TB336ZU) purely from each one's stale flat-desk
                        // gravity hold.
                        val entryAnchor = fnEntryAnchor(overlayRotation.roundToInt())
                        CompactFnButton(
                            onClick = {
                                modalFocusOrigin = ModalFocusOrigin.FUNCTION_MENU
                                currentActions.value.onCameraInputBlockedChange(true)
                                fnOverlayVisible = true
                            },
                            glyphRotation = overlayRotation,
                            modifier = Modifier
                                .focusRequester(functionMenuFocusRequester)
                                .then(when (entryAnchor) {
                                FnEntryAnchor.START -> Modifier
                                    .align(AbsoluteAlignment.CenterLeft)
                                    .absolutePadding(left = 12.dp)
                                FnEntryAnchor.END -> Modifier
                                    .align(AbsoluteAlignment.CenterRight)
                                    .absolutePadding(right = 12.dp)
                                }),
                        )
                    }
                }

                ModeCarousel(
                    mode = state.mode,
                    onModeChange = actions::onModeChange,
                    enabled = !state.isRecording,
                    glyphRotation = overlayRotation,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Keyed on the two fields the lambda actually reads (perf review #11): capturing
                // `state` minted a new lambda per emission, dragging ShutterRow into every 10 Hz
                // telemetry recomposition. currentActions is a recomposition-stable holder, safe
                // inside the remembered closure.
                val reviewOpenUri = state.lastMediaUri
                val reviewEnabled = reviewTargetEnabled(
                    recordingStarting = state.isRecordingStarting,
                    recording = state.isRecording,
                    recordingFinalizing = state.isRecordingFinalizing,
                )
                val onOpenReview = remember(reviewOpenUri) {
                    {
                        if (reviewOpenUri != null) {
                            modalFocusOrigin = ModalFocusOrigin.GALLERY
                            currentActions.value.onReviewOpenChange(true, reviewOpenUri)
                            Unit
                        } else {
                            // Nothing to review (fresh or reinstalled app): the tap asks for the
                            // capture-restore instead — and, via MainActivity's decorator, for the
                            // visual-media permission a reinstall restore needs (2026-08-01).
                            currentActions.value.onGalleryAccessRequested()
                        }
                    }
                }
                ShutterRow(
                    mode = state.mode,
                    isRecording = state.isRecording,
                    isRecordingStarting = state.isRecordingStarting,
                    timelapseRunning = state.timelapseRunning,
                    timerCountdownSec = state.timerCountdownSec,
                    lastMediaUri = state.lastMediaUri,
                    lastMediaProvenance = state.lastMediaProvenance,
                    onOpenReview = onOpenReview,
                    galleryModifier = Modifier.focusRequester(galleryFocusRequester),
                    reviewEnabled = reviewEnabled,
                    onShutter = onShutter,
                    onSnapshot = actions::onCapturePhoto,
                    cameraHealthy = state.primaryShutterHealthy,
                    shutterEnabled = state.primaryShutterEnabled,
                    stillCaptureAvailable = state.stillCaptureReady,
                    glyphRotation = overlayRotation,
                    modifier = Modifier
                        .fillMaxWidth()
                        // 12 dp, the ONE left inset (see the rule stated above the top-start
                        // column). GalleryThumb is CenterStart inside this Box, so the old 28 dp
                        // portrait value put the app's one bottom-left element 16 dp inboard of the
                        // Fn chip row, focal rail, exposure meter, status OSD and top bar. Nothing
                        // else here depends on the inset: the shutter is centred and the snapshot
                        // dot is a fixed offset from centre. The 52 dp thumb stays over the floor.
                        .padding(horizontal = 12.dp),
                )
            }
        }

        val operatorChrome = Modifier
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.6f),
                ),
            )
            .navigationBarsPadding()

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // Rest-state measurement for the preview's adaptive top ([previewTopPx]); a
                // dial-open growth spike must not re-place the viewfinder, so only the closed
                // state records. BEFORE the chrome/padding modifiers, so the reported height is
                // the cluster's FULL outer extent (nav-bar inset and the 12/20 dp panel padding
                // included): measured inside them it ran ~40 dp short, which let a 4:3 preview's
                // bottom edge cut through the Fn circle on the FRONT route — the exact
                // chrome-straddles-the-seam defect this reserve exists to prevent (UI review #6).
                .onSizeChanged {
                    if (openManualDial == null) bottomClusterRestHeightPx = it.height
                }
                .then(operatorChrome)
                // bottom 20: the gesture-nav inset on this panel is thin, and 8 dp left the
                // shutter nearly touching the home-bar swipe zone (user-reported 2026-07-25).
                .padding(top = 12.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            // Keep the dial cluster composed at zero height in compact rest state. Disposing it
            // on close skipped the MF-assist cleanup and left the auto loupe enabled.
            manualPane()
            if (detailsVisible || openManualDial != null) Spacer(modifier = Modifier.height(8.dp))
            capturePane()
        }
    }
    } // end of the viewfinder's Ltr scope

    if (sheetVisible) {
        ProSheet(
            state = state,
            actions = actions,
            initialTab = sheetInitialTab,
            openRequestId = sheetOpenRequestId,
            onTabChange = { sheetInitialTab = it },
            onDismiss = {
                sheetVisible = false
                modalFocusOrigin = null
                restoreFocusAfterModal(settingsFocusRequester)
            },
            onSelectManualDial = ::selectManualDial,
        )
    }

    if (fnOverlayVisible) {
        FnOverlay(
            state = state,
            actions = actions,
            onSelectManualDial = ::selectManualDial,
            onDismiss = {
                fnOverlayVisible = false
                modalFocusOrigin = null
                restoreFocusAfterModal(functionMenuFocusRequester)
            },
            glyphRotation = overlayRotation,
        )
    }

    val frozenReview = state.openReview
    if (frozenReview != null) {
        MediaReviewOverlay(
            uri = frozenReview.uri,
            deleteScope = frozenReview.deleteScope,
            provenance = frozenReview.provenance,
            overlayRotation = overlayRotation,
            onClose = {
                actions.onReviewOpenChange(false, frozenReview.uri)
            },
            onDelete = {
                actions.onDeleteLastMedia(frozenReview.uri, frozenReview.provenance)
                actions.onReviewOpenChange(false, frozenReview.uri)
            },
        )
    }
}

/** Central capture/storage truth, kept readable and bounded in the operator's held orientation. */
@Composable
internal fun CriticalCameraStatusPlate(
    message: String,
    livePriority: CameraStatusLivePriority,
    overlayRotation: Float,
    modifier: Modifier = Modifier,
) {
    Text(
        text = message,
        color = CameraColors.TextPrimary,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier
            .rotateLayout(overlayRotation)
            .background(HudPlate, RoundedCornerShape(8.dp))
            .semantics {
                liveRegion = if (livePriority == CameraStatusLivePriority.ASSERTIVE) {
                    LiveRegionMode.Assertive
                } else {
                    LiveRegionMode.Polite
                }
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

// (The pure status/rotation/Fn-overlay/meter/rail policy helpers live in CameraScreenPolicy.kt —
// hoisted to a non-composable file so they stay host-testable apart from Compose emission.)

/** Rotates content while reserving its exact animated axis-aligned bounds in layout. */
// `internal`, not private, so a host Compose test can render the real status plate through the real
// modifier: its geometry helpers are unit-tested on their own, but "the wide OSD plate still FITS
// once rotated" is a property of the composed pair and was the actual risk in turning it on.
internal fun Modifier.rotateLayout(degrees: Float): Modifier = this
    // This clip must wrap the custom layout. Putting it after layout() would clip against the
    // unrotated child's bounds instead of the constraint-valid rotated slot.
    .clipToBounds()
    .layout { measurable, constraints ->
        // Measure on the axis the child's own width will RUN along after the rotation. Past the 45°
        // crossover that is the parent's height, and measuring against the parent's width instead
        // is what ellipsized the held-landscape Fn readouts inside a tile that had ample room along
        // its long side. The AABB below still uses the ORIGINAL constraints — the reserved slot has
        // to satisfy the parent, only the child's own measurement space turns with it.
        val childConstraints =
            if (rotatedMeasureAxisSwapped(degrees)) swappedMeasureConstraints(constraints) else constraints
        val placeable = measurable.measure(childConstraints)
        val bounds = constrainedRotatedLayoutBounds(
            widthPx = placeable.width,
            heightPx = placeable.height,
            degrees = degrees,
            constraints = constraints,
        )
        val centeredX = (bounds.widthPx - placeable.width) / 2f
        val centeredY = (bounds.heightPx - placeable.height) / 2f
        val placementX = centeredX.toInt()
        val placementY = centeredY.toInt()
        layout(bounds.widthPx, bounds.heightPx) {
            placeable.placeWithLayer(x = placementX, y = placementY) {
                // Preserve half-pixel centering so an unconstrained, ceil-rounded AABB is not
                // accidentally shaved by the outer clip.
                translationX = centeredX - placementX
                translationY = centeredY - placementY
                rotationZ = degrees
            }
        }
    }

// ---------------------------------------------------------------------------
// Top bar: quick toggles (flash/timer/aspect/grid/teleconverter) + settings entry point.
// ---------------------------------------------------------------------------


/**
 * The top bar's outer container: one Row across the window, SpaceBetween, so the leading (scrolling)
 * group and the fixed trailing group stay pinned to opposite ends.
 *
 * Orientation moves no control, so the bar has exactly this one full-width shape on every device.
 */
@Composable
private fun TopBarContainer(
    modifier: Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** One group inside [TopBarContainer], laid out along its Row axis. */
@Composable
private fun TopBarGroup(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun TopBar(
    state: CameraUiState,
    actions: CameraActions,
    onOpenSheet: () -> Unit,
    settingsModifier: Modifier = Modifier,
    compact: Boolean,
    onToggleDisp: () -> Unit,
    modifier: Modifier = Modifier,
    glyphRotation: Float = 0f,
) {
    val recordingLocked = state.isRecording
    // Keyed remember: capability projection allocates ~9 filtered lists; recomputing it on EVERY
    // recomposition made each 5-10 Hz telemetry tick (audio level, roll, REC timer) re-derive it
    // although caps/controls hadn't changed.
    val availability = remember(state.caps, state.controls) {
        controlAvailability(state.caps?.controlCapabilities(), state.controls)
    }
    val topBarScroll = rememberScrollState()
    // The grid toggle is two-state but the setting is five-state: remember what "on" meant so an
    // off→on round trip restores GOLDEN/SQUARE/CENTER instead of collapsing every choice to THIRDS.
    // Session-scoped on purpose — the grid TYPE itself is the persisted value (SettingsStore).
    var lastActiveGrid by rememberSaveable { mutableStateOf(GridType.THIRDS) }
    LaunchedEffect(state.grid) { if (state.grid != GridType.NONE) lastActiveGrid = state.grid }
    TopBarContainer(modifier) {
        TopBarGroup(
            modifier = Modifier
                .weight(1f)
                .trailingEdgeFadeScrollHint(topBarScroll)
                .horizontalScroll(topBarScroll),
        ) {
            // Compact circular glyphs counter-rotate to stay upright as the phone turns (iPhone-style);
            // the TELE chip is wide text, so it stays fixed to avoid poking out of its slot.
            val glyphSpin = Modifier.rotate(glyphRotation)
            // Flash lives in BOTH modes: torch is a video light and rides the repeating request
            // identically, but the button used to be gated to PHOTO and there is no FLASH Fn slot
            // or menu row — so a video light was unreachable AND unindicated, and a TORCH left over
            // from photo stayed lit with no control. flashChoicesFor narrows video to OFF/TORCH
            // (AE flash metering is still-only) and flashDisplayMode makes a leftover AUTO/ON read
            // as OFF there instead of claiming a metering mode video can never use.
            val flashChoices = flashChoicesFor(state.mode, availability.flashModes)
            val flashDisplay = flashDisplayMode(state.mode, state.controls.flash)
            // One predicate for all four toggles (chromeToggles): full DISP draws every one, compact
            // keeps only the non-default states. The per-toggle "which value is the quiet one"
            // comparison lives in there with it — spelling it out here is how GRID lost its clause.
            val chrome = chromeToggles(
                compact = compact,
                photo = state.mode == CaptureMode.PHOTO,
                flash = flashDisplay,
                timer = state.timer,
                aspect = state.aspectRatio,
                grid = state.grid,
            )
            if (chrome.flash) {
                FlashButton(
                    mode = flashDisplay,
                    onClick = { actions.onFlash(nextAvailable(flashDisplay, flashChoices)) },
                    enabled = !recordingLocked && flashChoices.size > 1,
                    modifier = glyphSpin,
                )
            }
            if (chrome.timer) {
                TimerButton(
                    timer = state.timer,
                    onClick = { actions.onTimer(nextTimer(state.timer)) },
                    enabled = !recordingLocked,
                    modifier = glyphSpin,
                )
            }
            if (chrome.aspect) {
                AspectButton(
                    ratio = state.aspectRatio,
                    onClick = { actions.onAspectRatio(nextAspect(state.aspectRatio)) },
                    enabled = !recordingLocked,
                    modifier = glyphSpin,
                )
            }
            // An ACTIVE grid keeps its button in compact too: the grid lines are drawn unconditionally
            // over the live image, and this is the only control that clears them.
            if (chrome.grid) {
                GridButton(
                    type = state.grid,
                    onClick = { actions.onGridType(toggledGridType(state.grid, lastActiveGrid)) },
                    modifier = glyphSpin,
                )
            }
        }
        // Counter-rotate the settings glyph so it stays upright as the phone turns (iPhone-style).
        TopBarGroup {
            // FIXED slot (like flip/DISP/gear), not the scrolling row: as the last scrolling item
            // the chip vanished off-screen whenever photo full-DISP filled the row — the app's
            // headline function must keep one stable, always-visible home in every rear mode.
            // GONE (not disabled) while FRONT: the converter is a rear-3× accessory, so the chip is
            // a rear-only concept with no meaningful disabled state on the selfie route.
            if (state.activeCameraRoute == me.hletrd.telecampro.camera.CameraRoute.BACK && state.cameraRoutes.back) {
                TeleChip(
                    active = state.teleconverterMode,
                    enabled = !recordingLocked,
                    onClick = { actions.onToggleTeleconverter(!state.teleconverterMode) },
                )
            }
            // Fixed (non-scrolling) slot like DISP/settings: flipping must stay reachable while the
            // leading chip row is scrolled, and it never disappears in compact mode.
            //
            // Briefly removed 2026-07-28 while the selfie route looked broken, then restored: the
            // mirror roles are correct, and the "front preview is zoomed" report turned out not to
            // be a front-camera fault at all — the punch-in LOUPE crops the preview to
            // 1 - PUNCH_IN_CROP = 40% of the frame, preview-only by design, on whichever camera is
            // live. It simply reads louder on a 21.5 mm-equivalent selfie lens than at rear 3x.
            CameraSwitchButton(
                available = state.cameraRoutes.switchAvailable,
                onClick = actions::onToggleFrontCamera,
                enabled = !recordingLocked,
                modifier = Modifier.rotate(glyphRotation),
                frontFacing = state.facing == CameraFacing.FRONT,
                activeRoute = state.activeCameraRoute,
            )
            DispButton(infoHidden = compact, onClick = onToggleDisp, modifier = Modifier.rotate(glyphRotation))
            GearButton(
                onClick = onOpenSheet,
                modifier = settingsModifier.rotate(glyphRotation),
            )
        }
    }
}

/** Persistent, directly dismissible feedback for the tap-owned AF/AE point after its reticle fades. */
@Composable
private fun TapFocusHoldChip(onReset: () -> Unit, modifier: Modifier = Modifier) {
    val a11yResetFocusPoint = stringResource(R.string.a11y_reset_focus_point)
    val a11yTapFocusHeld = stringResource(R.string.a11y_tap_focus_held)
    val activate = onReset
    // Outer box carries the click, focus and the 48 dp minimum touch target; the visual pill is the
    // INNER box, same pattern as TeleChip and DialChip. The plate used to sit on this outer box with
    // horizontal-only padding, so the drawn slab was the full 48 dp tall while its TopCenter
    // lane-mates — the half-press label and the ZoomIndicator readout, both on the shared 12/6 inset
    // — draw ~26-31 dp. Moving the plate inward changes no hit area, only the painted rectangle.
    Box(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .focusable()
            .clearAndSetSemantics {
                contentDescription = a11yResetFocusPoint
                stateDescription = a11yTapFocusHeld
                role = Role.Button
                onClick {
                    activate()
                    true
                }
            }
            .clickable(role = Role.Button, onClickLabel = a11yResetFocusPoint, onClick = onReset),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            // The viewfinder's compact form of the menu's "Tap Focus" row (ProSheet.kt); the a11y
            // state above spells the same concept out as "Tap focus held". It replaced "AF HOLD",
            // which read as a sibling of the unrelated AFL exposure-lock tag in the OSD.
            // "AF HOLD" remains the INTERNAL concept name (CameraEngine, docs/ARCHITECTURE.md).
            text = stringResource(R.string.osd_tap_af),
            color = CameraColors.Accent,
            style = hudGlyph(11.sp),
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(HudPlate)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * Ghost circular translucent chrome button shared by every top-bar icon. The tappable area is a
 * 48 dp touch target (Material / WCAG 2.2 minimum) while the visible scrim stays a compact 36 dp, so
 * one-handed / gloved use on this 3168 px panel mis-taps far less without bloating the chrome.
 *
 * The scrim is the shared [HudPlate] — the same tested contrast floor 05486cb applied to the OSD
 * readouts — because the earlier 0.45 disc failed it badly (secondary #9E9E9E glyphs ≈1.25:1, white
 * ≈3.35:1 over a bright sky), leaving flash/grid/aspect state unreadable outdoors. The enabled/
 * disabled affordance is carried by the glyph's own alpha (each content lambda dims to 0.38 when
 * disabled), not by fading the disc: a mid-gray disc near the glyph's own gray (≈0.5 alpha) is
 * actually LOWER contrast than either the floor or the old 0.22, so a single floor alpha is correct.
 */
@Composable
internal fun ChromeIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    stateDescription: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val activate = onClick
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                this.contentDescription = contentDescription
                // Optional: for the chrome buttons whose glyph does not name the state it is IN
                // (the flip button's arrows look the same on either camera), the state has to be
                // spoken separately — clearAndSetSemantics drops anything the children exported.
                stateDescription?.let { this.stateDescription = it }
                role = Role.Button
                if (!enabled) disabled()
                onClick {
                    if (!enabled) return@onClick false
                    activate()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // background(shape) rather than clip(CircleShape) + background(): the plate draws identically
        // either way, but a CLIP here silently shaves any content that reaches past the disc instead
        // of letting it overflow where it can be seen. That is precisely how both badges shipped with
        // their lower halves cut off (see [chromeBadgePlateClearanceDp]). The ripple stays circular
        // regardless — indication is bounded by the OUTER box's clip, which carries the clickable.
        Box(
            modifier = Modifier
                .size(CHROME_PLATE_DP.dp)
                .background(HudPlate, CircleShape),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

/**
 * The state badge both [FlashButton] and [TimerButton] paint on the chrome plate: flash AUTO's "A",
 * the armed self-timer's seconds. One helper because the two sites were byte-identical — and both
 * wrong the same way, the timer having copied the flash button's placement on the belief it was
 * tuned. Placement is derived, not eyeballed: see [chromeBadgePlateClearanceDp] for the annulus
 * argument that puts it under the glyph instead of in the corner, and
 * [chromeBadgeGlyphClearanceDp] for the gap above it.
 */
@Composable
private fun BoxScope.PlateBadge(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        // dp-pinned rather than sp — see [CHROME_BADGE_TEXT_DP].
        style = hudGlyph(with(LocalDensity.current) { CHROME_BADGE_TEXT_DP.dp.toSp() }),
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = CHROME_BADGE_BOTTOM_INSET_DP.dp),
    )
}

@Composable
private fun FlashButton(mode: FlashMode, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val color = when (mode) {
        FlashMode.OFF -> CameraColors.TextSecondary
        FlashMode.TORCH -> CameraColors.Accent
        else -> CameraColors.TextPrimary
    }.copy(alpha = if (enabled) 1f else 0.38f)
    // Name constant, value in the state: the bolt glyph does not spell which of the four modes is
    // live, but folding the mode INTO the name renamed the node on every cycle, which is what
    // TalkBack tracks focus by. Same split GridButton and FlipCameraButton already use.
    ChromeIconButton(
        onClick = onClick,
        contentDescription = stringResource(R.string.label_flash),
        stateDescription = LocalContext.current.localizedLabel(mode),
        modifier = modifier,
        enabled = enabled,
    ) {
        Canvas(Modifier.size(CHROME_GLYPH_BOX_DP.dp)) {
            // Round joins. The path's bottom vertex is a 31.6-degree spike sitting exactly on the box
            // edge, and Skia miters it (1/sin(15.8 deg) = 3.67, inside the default limit of 4) into a
            // needle reaching 2.6 dp BELOW the 16 dp box this glyph claims to occupy — over the badge
            // and past the size every other chrome glyph is measured by. A round join caps the same
            // vertex at half a stroke, which is the bound [CHROME_GLYPH_INK_BELOW_CENTRE_DP] assumes.
            val boltStroke = Stroke(width = 1.4.dp.toPx(), join = StrokeJoin.Round)
            val bolt = Path().apply {
                moveTo(size.width * 0.56f, 0f)
                lineTo(size.width * 0.08f, size.height * 0.6f)
                lineTo(size.width * 0.44f, size.height * 0.6f)
                lineTo(size.width * 0.38f, size.height)
                lineTo(size.width * 0.92f, size.height * 0.36f)
                lineTo(size.width * 0.52f, size.height * 0.36f)
                close()
            }
            if (mode == FlashMode.ON || mode == FlashMode.TORCH) {
                drawPath(bolt, color = color)
            } else {
                drawPath(bolt, color = color, style = boltStroke)
            }
            if (mode == FlashMode.OFF) {
                drawLine(color, Offset(0f, size.height * 0.06f), Offset(size.width, size.height * 0.94f), strokeWidth = 1.4.dp.toPx())
            }
        }
        if (mode == FlashMode.AUTO) {
            PlateBadge(text = "A", color = color)
        }
    }
}

@Composable
private fun TimerButton(timer: ShutterTimer, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val armed = timer != ShutterTimer.OFF
    // Clock in BOTH states, seconds as a [PlateBadge] under it. The armed branch used to DELETE the clock and
    // draw the bare digit, i.e. it dropped the control's identity in exactly the state worth spotting —
    // the exact opposite of the rule DispButton's own KDoc states below ("both always drawn — the glyph
    // is the control's identity, not a preview of its state"), and of every other chrome button on this
    // row, which keeps its pictograph and changes colour. Compounding it: the OSD's `T3s` tag is suppressed
    // in compact and `timer` is not in compactShootingStatusVisible, so an armed self-timer's only mark
    // in the preview-first finder was an unlabelled blue digit in a row of pictographs.
    //
    // Accent for armed / TextSecondary for off is the row's shared engaged rule (GridButton, TeleChip,
    // DispButton), and the OFF branch therefore draws exactly what it drew before — full-alpha
    // secondary, unchanged geometry.
    val color = (if (armed) CameraColors.Accent else CameraColors.TextSecondary)
        .copy(alpha = if (enabled) 1f else 0.38f)
    ChromeIconButton(
        onClick = onClick,
        contentDescription = stringResource(R.string.a11y_self_timer),
        stateDescription = LocalContext.current.localizedLabel(timer),
        modifier = modifier,
        enabled = enabled,
    ) {
        Canvas(Modifier.size(CHROME_GLYPH_BOX_DP.dp)) {
            drawCircle(color, radius = size.minDimension / 2f, style = Stroke(width = 1.3.dp.toPx()))
            drawLine(color, center, Offset(center.x, center.y - size.height * 0.3f), strokeWidth = 1.2.dp.toPx())
            drawLine(color, center, Offset(center.x + size.width * 0.18f, center.y), strokeWidth = 1.2.dp.toPx())
        }
        if (armed) {
            PlateBadge(text = timer.seconds.toString(), color = color)
        }
    }
}

@Composable
private fun AspectButton(ratio: AspectRatio, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    ChromeIconButton(
        onClick = onClick,
        contentDescription = stringResource(R.string.label_aspect_ratio),
        stateDescription = aspectRatioLabel(ratio),
        modifier = modifier,
        enabled = enabled,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val color = CameraColors.TextPrimary.copy(alpha = if (enabled) 1f else 0.38f)
            val sw = 1.4.dp.toPx()
            when (ratio) {
                AspectRatio.W4_3 -> drawRect(
                    color,
                    topLeft = Offset(size.width * 0.1f, size.height * 0.2f),
                    size = Size(size.width * 0.8f, size.height * 0.6f),
                    style = Stroke(width = sw),
                )
                AspectRatio.W16_9 -> drawRect(
                    color,
                    topLeft = Offset(size.width * 0.04f, size.height * 0.3f),
                    size = Size(size.width * 0.92f, size.height * 0.4f),
                    style = Stroke(width = sw),
                )
            }
        }
    }
}

@Composable
private fun GridButton(type: GridType, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val active = type != GridType.NONE
    // Accent for engaged, like every other chrome toggle on this row (TimerButton armed, TeleChip,
    // DispButton). White-for-on made Grid the row's one dissenter. Accent is pinned >= 4.5:1 at the
    // shared HUD scrim.
    val color = if (active) CameraColors.Accent else CameraColors.TextSecondary
    // Name the grid, not just on/off: the glyph draws thirds whichever type is active, so "Grid on"
    // told a TalkBack user nothing about which of the five is framing their shot.
    ChromeIconButton(
        onClick = onClick,
        contentDescription = stringResource(R.string.label_grid),
        stateDescription = LocalContext.current.localizedLabel(type),
        modifier = modifier,
    ) {
        Canvas(Modifier.size(16.dp)) {
            val sw = 1.2.dp.toPx()
            drawRect(color, topLeft = Offset.Zero, size = this.size, style = Stroke(width = sw))
            val x1 = size.width / 3f
            val x2 = size.width * 2 / 3f
            val y1 = size.height / 3f
            val y2 = size.height * 2 / 3f
            drawLine(color, Offset(x1, 0f), Offset(x1, size.height), strokeWidth = sw * 0.8f)
            drawLine(color, Offset(x2, 0f), Offset(x2, size.height), strokeWidth = sw * 0.8f)
            drawLine(color, Offset(0f, y1), Offset(size.width, y1), strokeWidth = sw * 0.8f)
            drawLine(color, Offset(0f, y2), Offset(size.width, y2), strokeWidth = sw * 0.8f)
        }
    }
}

@Composable
internal fun TeleChip(active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val onDesc = stringResource(R.string.a11y_state_on)
    val offDesc = stringResource(R.string.a11y_state_off)
    val labelTeleconverter = stringResource(R.string.label_teleconverter)
    val activate = onClick
    val bg = when {
        active && enabled -> CameraColors.TextPrimary
        active -> CameraColors.TextPrimary.copy(alpha = 0.38f)
        else -> HudPlate
    }
    val fg = when {
        active -> Color.Black.copy(alpha = if (enabled) 1f else 0.55f)
        // Idle label reads OFF at a glance: full-brightness white on the scrim pill looked like an
        // engaged state (user-reported) — the dim secondary weight marks it as an available toggle,
        // with the filled white pill reserved for TC actually ON.
        else -> CameraColors.TextPrimary.copy(alpha = if (enabled) 0.62f else 0.30f)
    }
    // Outer box carries the click + semantics at the 48 dp minimum touch target (every sibling
    // top-bar control already gets 48 dp); the 36 dp pill stays the VISUAL, so the layout look is
    // unchanged while the hit area stops being the row's one undersized outlier.
    Box(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = labelTeleconverter
                stateDescription = if (active) onDesc else offDesc
                role = Role.Button
                if (!enabled) disabled()
                onClick {
                    if (!enabled) return@onClick false
                    activate()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(36.dp)
                .clip(RoundedCornerShape(50))
                .background(bg)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.osd_tele), color = fg, style = hudGlyph(11.sp))
        }
    }
}

/** Standard camera-flip glyph: two half-circle arrows chasing each other (front/rear switch). */
@Composable
private fun FlipCameraButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    frontFacing: Boolean = false,
    activeRoute: me.hletrd.telecampro.camera.CameraRoute =
        if (frontFacing) me.hletrd.telecampro.camera.CameraRoute.FRONT else me.hletrd.telecampro.camera.CameraRoute.BACK,
) {
    ChromeIconButton(
        onClick = onClick,
        contentDescription = stringResource(R.string.a11y_switch_camera),
        modifier = modifier,
        enabled = enabled,
        // The glyph is the same on both cameras, so without this a TalkBack user has no way at all
        // to tell which camera is live — and entering FRONT silently forces the teleconverter off.
        stateDescription = stringResource(
            when (activeRoute) {
                me.hletrd.telecampro.camera.CameraRoute.FRONT -> R.string.a11y_front_camera
                me.hletrd.telecampro.camera.CameraRoute.EXTERNAL -> R.string.a11y_external_camera
                me.hletrd.telecampro.camera.CameraRoute.BACK -> R.string.a11y_rear_camera
            },
        ),
    ) {
        Canvas(Modifier.size(18.dp)) {
            val color = CameraColors.TextPrimary.copy(alpha = if (enabled) 1f else 0.38f)
            val sw = 1.4.dp.toPx()
            val inset = size.minDimension * 0.14f
            val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
            val arcTopLeft = Offset(inset, inset)
            // Two opposing 120° arcs; each ends in a small filled arrowhead pointing along its sweep.
            drawArc(color, startAngle = -160f, sweepAngle = 120f, useCenter = false, topLeft = arcTopLeft, size = arcSize, style = Stroke(width = sw))
            drawArc(color, startAngle = 20f, sweepAngle = 120f, useCenter = false, topLeft = arcTopLeft, size = arcSize, style = Stroke(width = sw))
            val r = arcSize.width / 2f
            fun arrowHead(angleDeg: Float, tangentDeg: Float) {
                val rad = Math.toRadians(angleDeg.toDouble())
                val tip = Offset(
                    center.x + r * kotlin.math.cos(rad).toFloat(),
                    center.y + r * kotlin.math.sin(rad).toFloat(),
                )
                val tRad = Math.toRadians(tangentDeg.toDouble())
                val dir = Offset(kotlin.math.cos(tRad).toFloat(), kotlin.math.sin(tRad).toFloat())
                val normal = Offset(-dir.y, dir.x)
                val len = size.minDimension * 0.18f
                val head = Path().apply {
                    moveTo(tip.x + dir.x * len * 0.7f, tip.y + dir.y * len * 0.7f)
                    lineTo(tip.x - normal.x * len * 0.5f, tip.y - normal.y * len * 0.5f)
                    lineTo(tip.x + normal.x * len * 0.5f, tip.y + normal.y * len * 0.5f)
                    close()
                }
                drawPath(head, color)
            }
            // Arrowheads at each arc's end, tangent to the circle in the sweep direction.
            arrowHead(angleDeg = -40f, tangentDeg = 50f)
            arrowHead(angleDeg = 140f, tangentDeg = 230f)
        }
    }
}

/** Emits no dead switch action when the enumerated inventory has only one route side. */
@Composable
internal fun CameraSwitchButton(
    available: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    frontFacing: Boolean = false,
    activeRoute: me.hletrd.telecampro.camera.CameraRoute =
        if (frontFacing) me.hletrd.telecampro.camera.CameraRoute.FRONT else me.hletrd.telecampro.camera.CameraRoute.BACK,
) {
    if (!available) return
    FlipCameraButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        frontFacing = frontFacing,
        activeRoute = activeRoute,
    )
}

@Composable
private fun GearButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    // A "tune" / sliders glyph (three horizontal rails, each with a knob at a different position) —
    // reads more clearly as "settings" than the old hand-drawn gear on this dense panel.
    ChromeIconButton(onClick = onClick, contentDescription = stringResource(R.string.a11y_open_settings), modifier = modifier) {
        Canvas(Modifier.size(18.dp)) {
            val color = CameraColors.TextPrimary
            val railStroke = 1.6.dp.toPx()
            val knobRadius = size.minDimension * 0.11f
            // Knob halo: a local darkening under each knob ring so the white rail does not read
            // straight THROUGH the knob and flatten the glyph into three plain lines. Deliberately
            // NOT the HUD text scrim — it is a ~2 px disc inside an 18 dp glyph that already sits on
            // its own ChromeIconButton plate, not a plate behind text, so HUD_TEXT_SCRIM_ALPHA's
            // 4.5:1 TEXT floor does not apply here and at 0.82 the halo would read as a filled dot.
            // Spelled as its own black so no consumer of a scrim token hand-picks an alpha again.
            val knobHalo = Color.Black.copy(alpha = 0.45f)
            // Three rails at 1/4, 1/2, 3/4 height; knobs sit at varying x to imply adjustable levels.
            val rows = listOf(0.25f to 0.66f, 0.5f to 0.34f, 0.75f to 0.6f)
            val left = size.width * 0.12f
            val right = size.width * 0.88f
            rows.forEach { (yf, knobXf) ->
                val y = size.height * yf
                drawLine(color, Offset(left, y), Offset(right, y), strokeWidth = railStroke)
                val knobX = left + (right - left) * knobXf
                drawCircle(color = knobHalo, radius = knobRadius * 1.4f, center = Offset(knobX, y))
                drawCircle(color, radius = knobRadius, center = Offset(knobX, y), style = Stroke(width = 1.4.dp.toPx()))
            }
        }
    }
}

/**
 * Sony DISP toggle: a viewfinder-frame glyph with two info lines (both always drawn — the glyph is
 * the control's identity, not a preview of its state).
 *
 * [infoHidden] is the CURRENT display state, i.e. `compact`, which starts true. The colour follows
 * the info, not the button: Accent means the shooting info is ON SCREEN. The old `active = compact`
 * naming lit the glyph Accent at launch while nothing was displayed and dimmed it once the OSD
 * appeared — the inverse of "Accent = active highlight". Both colours are pinned >= 4.5:1 at the
 * shared scrim.
 */
@Composable
private fun DispButton(infoHidden: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ChromeIconButton(
        onClick = onClick,
        contentDescription = stringResource(
            if (infoHidden) R.string.a11y_show_shooting_info else R.string.a11y_hide_shooting_info
        ),
        modifier = modifier,
    ) {
        Canvas(Modifier.size(16.dp)) {
            val color = if (infoHidden) CameraColors.TextSecondary else CameraColors.Accent
            val sw = 1.3.dp.toPx()
            drawRect(color, style = Stroke(width = sw))
            drawLine(color, Offset(size.width * 0.22f, size.height * 0.36f), Offset(size.width * 0.78f, size.height * 0.36f), strokeWidth = sw)
            drawLine(color, Offset(size.width * 0.22f, size.height * 0.64f), Offset(size.width * 0.55f, size.height * 0.64f), strokeWidth = sw)
        }
    }
}

/**
 * Battery % + remaining media (Sony viewfinder staple). Video mode estimates minutes at the CURRENT
 * encode bitrate; photo mode estimates shots from the enabled formats. Rough by design — it answers
 * "do I have enough left", not accounting.
 */
@Composable
private fun StatusInfoPill(state: CameraUiState, modifier: Modifier = Modifier) {
    if (state.batteryPct < 0 && state.freeBytes <= 0) return
    // remember(): the bitrate/remaining derivation re-ran on every recomposition (~10-25 Hz from
    // telemetry ticks) though its real inputs change on the 10 s info tick or a settings change
    // (PERF4-2).
    val remaining: String? = remember(
        state.freeBytes, state.mode, state.encodedVideoResolution, state.videoFrameRate,
        state.bitrateLevel, state.videoCodec, state.photoFormats,
    ) {
        when {
            state.freeBytes <= 0 -> null
            state.mode == CaptureMode.VIDEO -> {
                val encodedSize = state.encodedVideoResolution
                val bps = me.hletrd.telecampro.camera.videoBitRate(
                    encodedSize.width, encodedSize.height,
                    state.videoFrameRate.encoderRate,
                    me.hletrd.telecampro.camera.effectiveBpp(state.bitrateLevel, state.videoCodec), state.videoCodec,
                ).toLong() + 192_000L // + AAC
                val min = (state.freeBytes * 8L / bps.coerceAtLeast(1L)) / 60L
                when {
                    min >= 600 -> "9h+"
                    min >= 60 -> "${min / 60}h${min % 60}m"
                    // "m", not "min": the two branches above already spell minutes that way.
                    else -> "${min}m"
                }
            }
            else -> {
                var perShot = 0L
                if (state.photoFormats.heif) perShot += 8_000_000L
                if (state.photoFormats.jpeg) perShot += 6_000_000L
                if (state.photoFormats.dngRaw) perShot += 26_000_000L
                if (perShot == 0L) perShot = 8_000_000L
                val shots = state.freeBytes / perShot
                if (shots > 9999) "9999+" else "$shots"
            }
        }
    }
    val statusDescription = localizedStatusInfoDescription(
        batteryPct = state.batteryPct,
        remaining = remaining,
        video = state.mode == CaptureMode.VIDEO,
    )
    Row(
        modifier = modifier
            .background(HudPlate, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
            // The pill is ONE readout, so it clears its leaves and speaks them together: unmerged,
            // TalkBack read the raw glyphs — "45m" (which is a distance aloud) and a bare "1234"
            // that names nothing. The visible shortening stays; only the spoken form spells it out.
            .clearAndSetSemantics {
                contentDescription = statusDescription
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.batteryPct >= 0) {
            Text(
                "${state.batteryPct}%",
                color = if (state.batteryPct <= 15) CameraColors.AlarmText else CameraColors.TextPrimary,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        remaining?.let {
            Text(it, color = CameraColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * Live zoom readout: a "N.N×" pill over a thin bar that fills to the zoom's position within the lens's
 * advertised range. The number stays upright as the phone turns; the bar remains screen-fixed.
 */
@Composable
private fun ZoomIndicator(
    zoom: Float,
    range: android.util.Range<Float>?,
    modifier: Modifier = Modifier,
    numberRotation: Float = 0f,
) {
    val min = range?.lower ?: 1f
    val max = range?.upper ?: 10f
    val fraction = if (max > min) ((zoom - min) / (max - min)).coerceIn(0f, 1f) else 0f
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // The "N.N×" readout is short, so it counter-rotates to stay upright as the phone turns
        // (iPhone-style). The bar below stays horizontal — a generic level indicator reads fine at
        // any angle, and rotating it would collide with the surrounding chrome.
        Text(
            text = formatZoomMultiplier(zoom),
            color = CameraColors.Accent,
            style = hudGlyph(15.sp),
            modifier = Modifier
                .rotateLayout(numberRotation)
                .background(HudPlate, RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
        Box(
            modifier = Modifier
                .width(180.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                // The bar was the one chrome readout drawn BARE on the live image, while the "N.N×"
                // pill directly above it sits on the plate. Over a bright sky the 0.25-white track
                // composited to white and vanished, and the Accent fill alone measures ≈2.1:1 against
                // white — so the scale reference disappeared and the bar read as a floating blue
                // segment. Chained backgrounds draw in chain order, so the EARLIER one is underneath and
                // the plate goes FIRST: over a dark scene the track composites to the exact #404040 it
                // did before (82% black over black is black), and
                // over a bright one it keeps a real track to be a fraction OF. HudContrastTest pins
                // both halves.
                .background(HudPlate)
                // One-off: the EMPTY half of a fill bar — a slab the Accent fill is measured as a
                // fraction OF, not a stroke and not ink. HudContrastTest pins this exact 0.25
                // against the plate, so the number is asserted where it is spelled.
                .background(Color.White.copy(alpha = 0.25f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(CameraColors.Accent),
            )
        }
    }
}

@Composable
internal fun FnOverlay(
    state: CameraUiState,
    actions: CameraActions,
    onSelectManualDial: (DialType) -> Unit,
    onDismiss: () -> Unit,
    glyphRotation: Float = 0f,
) {
    val a11yCloseFunctionMenu = stringResource(R.string.a11y_close_function_menu)
    val functionMenuPane = stringResource(R.string.a11y_function_menu)
    val labelContext = LocalContext.current
    val dismiss = onDismiss
    BackHandler(onBack = onDismiss)
    val slots = remember(state.mode, state.activeFnSlots) {
        fnOverlaySlots(state.mode, state.activeFnSlots)
    }
    // Every "held sideways" adaptation below keys on the glyph RESIDUAL, not on raw gravity.
    // Handsets remain portrait-locked, so their held 90/270 residual still docks the tray at the
    // physical bottom edge. Only sw600dp+ windows may absorb the turn; there the residual is 0 and
    // reshaping the already-rotated layout would turn it twice. Rounding the animated float is safe:
    // the value is normalized downstream, and a mid-tween anchor flip is invisible against the tween.
    val glyphOrientation = glyphRotation.roundToInt()
    val trayAnchor = fnOverlayAnchor(glyphOrientation)
    val gridRows = remember(slots, glyphOrientation) {
        fnOverlayGridRows(slots, glyphOrientation)
    }
    val contentAxis = fnTileContentAxis(glyphOrientation)
    val availability = remember(state.caps, state.controls) {
        controlAvailability(state.caps?.controlCapabilities(), state.controls)
    }
    val closeFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { closeFocusRequester.requestFocus() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = FN_OVERLAY_SCRIM_ALPHA)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Touch-only scrim: the explicit 48 dp Close control below is the modal's sole
                // named close action for TalkBack, Switch Access, and UI automation.
                .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) },
        )
        val panelPlacement = when (trayAnchor) {
            FnOverlayAnchor.BOTTOM_CENTER -> Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 14.dp)
                // 154 dp clears the cluster's two ACTION rows and nothing above them: measured up
                // from the same navigation-bar inset, the cluster spends 20 (its own bottom pad) +
                // 76 (shutter row) + 8 + 48 (mode pair) = 152 dp on them, so the tray's bottom edge
                // lands 2 dp clear of the mode pair. The focal rail above that (160-208 dp) is
                // deliberately UNDER the tray — the whole screen is scrimmed while Fn is open, and
                // the rail's lens presets are not what the tray is competing with for attention.
                //
                // NOT interchangeable with bottomClusterRestHeightPx, despite that being a live
                // measurement of the same cluster: it reports the Column's CONTENT height only
                // (the 12/20 dp padding and navigationBarsPadding sit left of its onSizeChanged, so
                // they are excluded → ~188 dp here), and it grows with the DISP dial-chip row, which
                // would make the tray's anchor move with detail state. It is measured for
                // previewTopPx's reserve, which wants exactly that behaviour; this anchor does not.
                .padding(bottom = 154.dp)
                .fillMaxWidth()
            FnOverlayAnchor.CENTER_START -> Modifier
                .align(AbsoluteAlignment.CenterLeft)
                .absolutePadding(left = 14.dp, top = 14.dp, bottom = 14.dp)
                .width(FN_OVERLAY_HELD_WIDTH_DP.dp)
            FnOverlayAnchor.CENTER_END -> Modifier
                .align(AbsoluteAlignment.CenterRight)
                .absolutePadding(right = 14.dp, top = 14.dp, bottom = 14.dp)
                .width(FN_OVERLAY_HELD_WIDTH_DP.dp)
        }
        // Raw placement, grid order, and label/value axes describe physical camera controls rather
        // than reading order. Keep that coordinate space absolute under RTL locales; each Text still
        // applies Unicode bidi shaping to its own localized content.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column(
                modifier = panelPlacement
                    .modalFocusBoundary()
                    .clip(RoundedCornerShape(8.dp))
                    // The full-screen scrim stays light, but the compact panel itself is opaque so
                    // focal-rail values cannot read as a second line inside held-landscape Fn tiles.
                    // Pill IS that opaque panel grey — the settings sheet uses it — so a hand-rolled
                    // 0xFF181818 here was only a second, near-identical panel colour.
                    .background(CameraColors.Pill)
                    .border(1.dp, CameraColors.Hairline, RoundedCornerShape(8.dp))
                    .semantics {
                        paneTitle = functionMenuPane
                        isTraversalGroup = true
                    }
                    // Consume blank-panel taps without exposing a nameless dummy Button.
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Short glyphs counter-rotate with device orientation; the wide header Row stays
                    // screen-fixed because rotating a wide box would poke it out of its layout slot.
                    Text(
                        stringResource(R.string.label_fn),
                        color = CameraColors.TextPrimary,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.rotate(glyphRotation),
                    )
                    Box(
                        modifier = Modifier
                            .focusRequester(closeFocusRequester)
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .clip(RoundedCornerShape(50))
                            .focusable()
                            .clearAndSetSemantics {
                                contentDescription = a11yCloseFunctionMenu
                                role = Role.Button
                                onClick {
                                    dismiss()
                                    true
                                }
                            }
                            .clickable(role = Role.Button, onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.action_close),
                            color = CameraColors.TextSecondary,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .rotate(glyphRotation)
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
                gridRows.forEach { rowSlots ->
                    Row(
                        // Preserve empty raw cells/rows for custom lists shorter than eight. Without
                        // the row floor an all-null held row collapses and changes the perceived 4x2
                        // slot position even though fnOverlayGridRows intentionally retained it.
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowSlots.forEach { slot ->
                            if (slot == null) {
                                Spacer(modifier = Modifier.weight(1f))
                            } else {
                                val manualDial = manualDialForFnSlot(slot)
                                val enabled = quickFnEnabled(slot, state) && when (manualDial) {
                                    DialType.WB -> whiteBalanceFnChipEnabled(state.controls.wbMode, availability)
                                    null -> true
                                    else -> quickManualDialEnabled(manualDial, availability)
                                }
                                FnOverlayTile(
                                    slot = slot,
                                    value = fnSlotValue(slot, state, labelContext),
                                    enabled = enabled,
                                    onClick = {
                                        if (manualDial != null) {
                                            onSelectManualDial(manualDial)
                                            onDismiss()
                                        } else {
                                            // Cycle/toggle actions keep the context visible so several
                                            // shooting choices can be prepared in one Fn visit.
                                            performQuickFn(slot, state, actions)
                                        }
                                    },
                                    compactValue = fnOverlayCompactValue(slot, state),
                                    glyphRotation = glyphRotation,
                                    contentAxis = contentAxis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun FnOverlayTile(
    slot: FnSlot,
    value: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compactValue: FnOverlayCompactValue? = null,
    glyphRotation: Float = 0f,
    contentAxis: FnTileContentAxis = FnTileContentAxis.PORTRAIT,
) {
    val activate = onClick
    val heldLandscape = contentAxis != FnTileContentAxis.PORTRAIT
    val localizedSlotLabel = LocalContext.current.localizedLabel(slot)
    val visualLabel = fnOverlayVisualLabel(
        slot,
        heldLandscape,
        localizedSlotLabel,
        stringResource(R.string.fn_short_stabilization),
        stringResource(R.string.fn_short_open_gate),
    )
    val visualValue = fnOverlayVisualValue(compactValue, value, heldLandscape)
    val foregroundAlpha = if (enabled) 1f else 0.55f
    Box(
        modifier = modifier
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) CameraColors.Block else CameraColors.BlockDisabled)
            .border(1.dp, CameraColors.Hairline, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = localizedSlotLabel
                stateDescription = value
                role = Role.Button
                if (!enabled) disabled()
                onClick {
                    if (!enabled) return@onClick false
                    activate()
                    true
                }
            }
            // Carve-out from the shared 12/6 pill inset: this tile sits in the width-contended
            // 148 dp held tray (CameraScreenPolicy), so only the vertical joins the scale.
            .padding(horizontal = 9.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (heldLandscape) {
            // The portrait-locked Activity becomes a narrow physical strip when held sideways.
            // Separating glyphs on the raw X axis stacks them on the held device's Y axis. The icon
            // rides WITH the label glyph (one rotated unit) so the pair stays readable at 90°.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (contentAxis == FnTileContentAxis.HELD_LANDSCAPE_VALUE_FIRST_RAW) {
                    FnOverlayTileValue(visualValue, foregroundAlpha, Modifier.rotateLayout(glyphRotation))
                    FnOverlayTileLabel(visualLabel, foregroundAlpha, Modifier.rotateLayout(glyphRotation), icon = fnSlotIcon(slot))
                } else {
                    FnOverlayTileLabel(visualLabel, foregroundAlpha, Modifier.rotateLayout(glyphRotation), icon = fnSlotIcon(slot))
                    FnOverlayTileValue(visualValue, foregroundAlpha, Modifier.rotateLayout(glyphRotation))
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .rotateLayout(glyphRotation),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                FnOverlayTileLabel(visualLabel, foregroundAlpha, icon = fnSlotIcon(slot))
                FnOverlayTileValue(visualValue, foregroundAlpha)
            }
        }
    }
}

/** Short visual values for held-landscape tiles; accessibility keeps [fullValue] unchanged. */
@Composable
internal fun fnOverlayVisualValue(
    compactValue: FnOverlayCompactValue?,
    fullValue: String,
    heldLandscape: Boolean,
): String {
    if (!heldLandscape) return fullValue
    return when (val compact = compactValue) {
        is FnOverlayCompactValue.Auto -> stringResource(R.string.fn_short_auto_value, compact.value)
        FnOverlayCompactValue.WhiteBalanceDaylight -> stringResource(R.string.fn_short_daylight)
        FnOverlayCompactValue.WhiteBalanceTungsten -> stringResource(R.string.fn_short_tungsten)
        FnOverlayCompactValue.Standard -> stringResource(R.string.fn_short_standard)
        FnOverlayCompactValue.Timelapse -> stringResource(R.string.fn_short_timelapse)
        FnOverlayCompactValue.SoundFocus -> stringResource(R.string.fn_short_sound_focus)
        FnOverlayCompactValue.SoundStage -> stringResource(R.string.fn_short_sound_stage)
        is FnOverlayCompactValue.TeleconverterFocal ->
            stringResource(R.string.fn_short_focal_mm, compact.millimeters)
        null -> fullValue
    }
}

@Composable
private fun FnOverlayTileLabel(
    text: String,
    alpha: Float,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    // Icon + label as ONE unit (user-requested 2026-07-31: text-only tiles read as a wall of
    // words). The icon inherits the label's secondary ink and alpha so a dimmed tile dims whole;
    // contentDescription null because the tile's clearAndSetSemantics already names the slot —
    // a second name here would double-announce.
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = CameraColors.TextSecondary.copy(alpha = alpha),
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text,
            color = CameraColors.TextSecondary.copy(alpha = alpha),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FnOverlayTileValue(text: String, alpha: Float, modifier: Modifier = Modifier) {
    Text(
        text,
        color = CameraColors.TextPrimary.copy(alpha = alpha),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

// quickFnEnabled moved to ControlCycles.kt — one shared per-slot availability for every quick-Fn
// surface (the Fn overlay here, plus My Menu / Recent rows in ProSheet).

@Composable
private fun ExposureMeter(
    state: CameraUiState,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    // Counter-rotation for the READOUT only: the scale itself is positional and reads at any angle.
    glyphRotation: Float = 0f,
) {
    // The shared helper returns the final signed stop amount. Do not multiply by the raw Camera2
    // compensation index again: that double-scaled positive values and reversed negative signs.
    val compensationEv = exposureMeterCompensationEv(state)
    // remember(): manualMeterEv sums 256 luma bins and the label formats strings — keyed to the
    // real inputs so a level/audio telemetry tick doesn't recompute them (PERF4-2).
    val manualEv = remember(state.controls.exposureMode, state.histogramData) {
        manualMeterEv(state.controls.exposureMode, state.histogramData?.luma)
    }
    val indicatorEv = if (state.controls.exposureMode == ExposureMode.MANUAL) manualEv else compensationEv
    val label = remember(state.controls.exposureMode, manualEv, compensationEv) {
        when {
            state.controls.exposureMode == ExposureMode.MANUAL && manualEv != null -> "M %+.1f".format(java.util.Locale.US, manualEv)
            state.controls.exposureMode == ExposureMode.MANUAL -> "M --"
            else -> formatEvComp(compensationEv)
        }
    }
    // Vertical Sony-style scale: +3 EV at the top, -3 EV at the bottom, readout above it.
    // 6/8 DELIBERATELY, not the 12/6 HUD pill inset: this is the one HUD plate whose content is a
    // vertical instrument rather than a line of text, and its axis needs are the inverse of a pill's.
    // The track's extreme ticks are drawn AT y=0 and y=height (`y = (3 - i) / 6 * height` for
    // i = ±3, the widest/major ones), so vertically the padding is the ONLY clearance the ±3 EV ends
    // get — 6 dp would leave ~5 dp under a 1.6 dp stroke. Horizontally the 22 dp Canvas already
    // carries its own 5 dp gutter (major ticks span cx±6 dp of an 11 dp centre), so 6 dp there yields
    // ~11 dp of visual clearance, and the pill's 12 dp would fatten a 34 dp column plate to 46 dp
    // with no text to protect. Left as-is by measurement, not by omission.
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(HudPlate)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            label,
            color = CameraColors.TextPrimary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            // Short glyph, so it counter-rotates to stay upright as the phone turns — the same
            // treatment the zoom readout and the OSD tags get. The scale below stays put.
            modifier = Modifier.rotateLayout(glyphRotation),
        )
        Canvas(modifier = Modifier.width(22.dp).height(if (compact) 96.dp else 150.dp)) {
            val cx = size.width / 2f
            // Three one-off alphas, one instrument: this is a hand-drawn EV scale whose parts are
            // ranked against each other, not against anything else in the app. 0.34 spine (the axis
            // you read positions along), 0.75 zero-EV datum, 0.42 for the remaining ticks. They are
            // a local hierarchy — reusing a token for any one of them would tie an unrelated
            // surface's future to this scale's legibility.
            drawLine(Color.White.copy(alpha = 0.34f), Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 1.2.dp.toPx())
            for (i in -3..3) {
                // +EV up: EV i sits at y = (3 - i)/6 of the track.
                val y = (3 - i) / 6f * size.height
                val major = i == 0 || i == -3 || i == 3
                val half = if (major) 6.dp.toPx() else 3.dp.toPx()
                drawLine(
                    color = if (i == 0) Color.White.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.42f),
                    start = Offset(cx - half, y),
                    end = Offset(cx + half, y),
                    strokeWidth = if (major) 1.6.dp.toPx() else 1.dp.toPx(),
                )
            }
            if (indicatorEv != null) {
                val y = ((3f - indicatorEv) / 6f).coerceIn(0f, 1f) * size.height
                drawCircle(CameraColors.ManualActive, radius = 4.dp.toPx(), center = Offset(cx, y))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Bottom cluster: mode carousel + shutter row (the manual dial cluster lives in ManualDials.kt).
// ---------------------------------------------------------------------------

/**
 * Direct iPhone/Sony-familiar focal presets; TELE remains a separate, labeled converter action.
 *
 * The rail has TWO faces. Off the converter it picks a LENS. On it, the lens is pinned to the
 * converter's host optic — every non-3× pick already EXITED converter shooting (`onLens` keeps TELE
 * only for [LensChoice.TELE3X]), so those three chips were three ways to leave, and the TELE toggle
 * in the top chrome remains the one deliberate exit. In their place the rail picks DIGITAL ZOOM, as
 * total magnification (13×/30×/60× on the kit optic). Those numbers are read from the device's own
 * lens information every recomposition ([teleZoomMarks] over the live `CONTROL_ZOOM_RATIO_RANGE`),
 * never written as literals: a converter with a different magnification moves the base, and a lens
 * with less digital headroom simply offers fewer marks.
 */
@Composable
internal fun FocalRail(
    state: CameraUiState,
    onLens: (LensChoice) -> Unit,
    onTeleZoomMark: (Float) -> Unit,
    modifier: Modifier = Modifier,
    glyphRotation: Float = 0f,
) {
    // Scrollable, because the rail's LENGTH is not fixed: teleZoomMarks derives its marks from the
    // live zoom range and the converter's magnification, so a weaker converter earns more marks and
    // the row outgrows the screen. It used to be a plain centred Row, which simply clipped the end
    // chips against the edge (user-reported 2026-07-29). Centring is kept for the common case that
    // fits — Arrangement.Center inside a scrollable row still centres content narrower than the
    // viewport — and the trailing fade is the same affordance the top bar and Fn row already use.
    val railScroll = rememberScrollState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .focalRailViewportScroll(railScroll),
        horizontalArrangement = Arrangement.Center,
    ) {
        Row(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.teleconverterMode) {
                val range = state.caps?.zoomRatioRange
                val marks = teleZoomMarks(
                    range?.lower,
                    range?.upper,
                    state.teleconverterMagnification,
                )
                // The rail speaks TOTAL magnification while the controls carry the LENS-LOCAL ratio,
                // so the current framing crosses into the marks' scale exactly once, here.
                val activeMark = selectedTeleZoomMark(
                    marks,
                    state.controls.zoomRatio * teleDisplayBase(state.teleconverterMagnification),
                )
                marks.forEach { mark ->
                    // ONE value feeds the drawn text and the spoken name so they cannot drift, and
                    // the spoken name says what the chip IS — a zoom mark is not a lens.
                    val label = formatZoomMark(mark)
                    RailChip(
                        label = label,
                        contentDescription = stringResource(R.string.a11y_lens_zoom, label),
                        presentation = teleZoomMarkState(
                            selected = mark == activeMark,
                            cameraReady = state.cameraReady,
                            recording = state.isRecording,
                        ),
                        onClick = { onTeleZoomMark(mark) },
                        glyphRotation = glyphRotation,
                    )
                }
            } else {
                // ENUMERATED, not hardcoded: only the presets this hardware can actually deliver
                // (see LensInventory). A single surviving preset is not a choice, so the rail stays
                // empty rather than showing one dead-looking chip — pinch still covers that device's
                // whole range.
                val offered = LensChoice.entries.filter { it in state.lensInventory.available }
                    .takeIf { it.size > 1 }
                    .orEmpty()
                offered.forEach { choice ->
                    val optical = choice in state.lensInventory.optical
                    RailChip(
                        label = lensLabel(choice),
                        // Same honesty rule the tele marks already follow: a digital-zoom preset is
                        // not a lens, so it must not be spoken as one.
                        contentDescription = stringResource(
                            if (optical) R.string.a11y_lens_optical else R.string.a11y_lens_zoom,
                            lensLabel(choice),
                        ),
                        presentation = focalRailState(
                            choice = choice,
                            selectedLens = state.lens,
                            teleconverter = state.teleconverterMode,
                            cameraReady = state.cameraReady,
                            recording = state.isRecording,
                        ),
                        onClick = { onLens(choice) },
                        glyphRotation = glyphRotation,
                    )
                }
            }
        }
    }
}

/**
 * Keeps the focal-rail fade in viewport draw coordinates.
 *
 * The fade must wrap the scroll modifier. Reversing these calls evaluates the mask in the wider
 * content coordinate space, moving its ramp off the visible edge and restoring a hard-cut chip.
 * Kept as one production seam so the regression can exercise rendered coordinates directly.
 */
internal fun Modifier.focalRailViewportScroll(scrollState: ScrollState): Modifier =
    trailingEdgeFadeScrollHint(scrollState).horizontalScroll(scrollState)

/** One rail chip: identical box, semantics, and plate treatment for a lens pick and a zoom mark. */
internal data class FocalRailVisualColors(
    val container: Color,
    val selectionOverlay: Color = Color.Transparent,
    val border: Color,
    val label: Color,
)

/** Enabled and disabled rail states keep distinct visual vocabularies as well as semantics. */
internal fun focalRailVisualColors(presentation: FocalRailState): FocalRailVisualColors = when {
    presentation.enabled && presentation.selected -> FocalRailVisualColors(
        container = CameraColors.TextPrimary,
        border = CameraColors.AffordanceEdge,
        label = Color.Black,
    )
    presentation.enabled -> FocalRailVisualColors(
        container = HudPlate,
        border = CameraColors.AffordanceEdge,
        label = CameraColors.TextPrimary,
    )
    presentation.selected -> FocalRailVisualColors(
        // Keep the shared live-frame contrast floor. The quiet wash alone converged to white over
        // sky/snow and erased the active focal choice for the whole REC/reconfigure interval.
        container = HudPlate,
        selectionOverlay = CameraColors.TextPrimary.copy(alpha = 0.12f),
        border = CameraColors.TextPrimary.copy(alpha = 0.12f),
        label = CameraColors.TextPrimary.copy(alpha = 0.38f),
    )
    else -> FocalRailVisualColors(
        container = HudPlate,
        border = CameraColors.TextPrimary.copy(alpha = 0.12f),
        label = CameraColors.TextPrimary.copy(alpha = 0.38f),
    )
}

@Composable
internal fun RailChip(
    label: String,
    contentDescription: String,
    presentation: FocalRailState,
    onClick: () -> Unit,
    glyphRotation: Float,
    modifier: Modifier = Modifier,
) {
    val description = contentDescription
    val interactionSource = remember { MutableInteractionSource() }
    val indication = LocalIndication.current
    val colors = focalRailVisualColors(presentation)
    val selectionDescription = stringResource(
        when (presentation.state) {
            CameraControlSelectionState.UNAVAILABLE_WHILE_RECORDING -> R.string.a11y_unavailable_while_recording
            CameraControlSelectionState.CAMERA_RECONFIGURING -> R.string.status_camera_reconfiguring
            CameraControlSelectionState.SELECTED -> R.string.a11y_selected
            CameraControlSelectionState.SELECTED_TELECONVERTER_ON -> R.string.a11y_selected_teleconverter_on
            CameraControlSelectionState.NOT_SELECTED -> R.string.a11y_not_selected
        },
    )
    // The 48 dp outer box is the one focus, selection, semantics, and activation owner. Its shared
    // interaction source is rendered by the clipped inner pill, so keyboard traversal sees one node
    // while the press ripple keeps the compact stadium treatment instead of flashing a 48 dp slab.
    Box(
        modifier = modifier
            .size(48.dp)
            .rotate(glyphRotation)
            .selectable(
                selected = presentation.selected,
                enabled = presentation.enabled,
                role = presentation.accessibilityRole,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            // Selection and activation must live on the same outer node. A separate
            // selected semantic followed by clickable exported selected=false from the
            // actionable AccessibilityNodeInfo on PMA110.
            .clearAndSetSemantics {
                this.contentDescription = description
                stateDescription = selectionDescription
                role = presentation.accessibilityRole
                selected = presentation.selected
                if (!presentation.enabled) disabled()
                onClick {
                    if (!presentation.enabled) return@onClick false
                    onClick()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                // Render the outer owner's indication inside the visual pill's clip.
                .clip(CircleShape)
                .indication(interactionSource, indication)
                .background(colors.container)
                .background(colors.selectionOverlay)
                .border(1.dp, colors.border, CircleShape)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = colors.label,
                // SemiBold(600) vs Medium(500): a weight step that actually RENDERS.
                // The old Bold/SemiBold pair resolved to one bundled face (600), so the
                // selection was carried by the filled pill alone (BACKLOG UI16).
                style = hudGlyph(
                    12.sp,
                    if (presentation.selected) FontWeight.SemiBold else FontWeight.Medium,
                ),
            )
        }
    }
}

@Composable
private fun ModeCarousel(
    mode: CaptureMode,
    onModeChange: (CaptureMode) -> Unit,
    modifier: Modifier = Modifier,
    glyphRotation: Float = 0f,
    enabled: Boolean = true,
) {
    Row(modifier = modifier.selectableGroup(), horizontalArrangement = Arrangement.Center) {
        // 8 dp like the two rows above it (Fn chips, focal rail); at 20 dp this row ran 2.5x the
        // rhythm of the row directly above. Photo and Video stay clearly separate: each pill now
        // carries 12 dp of internal inset, so the visual gap between the labels is 8 + 24 = 32 dp.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // The mode labels are SHORT ("Photo"/"Video"), so — iPhone-style — they DO counter-rotate
            // to stay upright as the phone turns (unlike the wide dial pills, which would overflow their
            // fixed row slots and are kept screen-fixed). The label + its underline rotate as one unit.
            ModeLabel(
                text = stringResource(R.string.mode_photo),
                active = mode == CaptureMode.PHOTO,
                enabled = enabled,
                onClick = { onModeChange(CaptureMode.PHOTO) },
                modifier = Modifier.rotateLayout(glyphRotation),
            )
            ModeLabel(
                text = stringResource(R.string.mode_video),
                active = mode == CaptureMode.VIDEO,
                enabled = enabled,
                onClick = { onModeChange(CaptureMode.VIDEO) },
                modifier = Modifier.rotateLayout(glyphRotation),
            )
        }
    }
}

@Composable
internal fun ModeLabel(text: String, active: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val modeButtonDescription = stringResource(R.string.a11y_mode_button, text)
    val presentation = modeCarouselState(active, enabled)
    val selectionDescription = stringResource(
        if (presentation.state == CameraControlSelectionState.SELECTED) R.string.a11y_selected else R.string.a11y_not_selected,
    )
    val activate = onClick
    Box(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .selectable(
                selected = presentation.selected,
                enabled = presentation.enabled,
                role = presentation.accessibilityRole,
                onClick = onClick,
            )
            .clearAndSetSemantics {
                contentDescription = modeButtonDescription
                stateDescription = selectionDescription
                role = presentation.accessibilityRole
                selected = presentation.selected
                if (!presentation.enabled) disabled()
                onClick {
                    if (!presentation.enabled) return@onClick false
                    activate()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                // The one HUD text element that had no scrim of its own: over a bright subject (sky,
                // snow, water — normal super-tele fare) the mid-gray inactive label fell under usable
                // contrast. Same treatment as every sibling HUD element.
                .clip(RoundedCornerShape(50))
                .background(HudPlate)
                // The ONE HUD pill inset, 12/6. Fifteen pills used to be padded by hand — six
                // horizontal values and seven vertical — and disagreeing neighbours sit adjacent
                // (StatusBar above StatusInfoPill, DialChip under RulerReadout). Every visual pill
                // lives inside its own 48 dp box, so unifying the inset moves no touch target. Two
                // width-contended rows keep a 9 dp horizontal and are marked as such.
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = text,
                color = when {
                    !enabled -> CameraColors.TextSecondary.copy(alpha = 0.45f)
                    active -> CameraColors.TextPrimary
                    else -> CameraColors.TextSecondary
                },
                // ONE size for both states. The old 15/14 sp step moved each label's measured width
                // ~4 dp and, because the pair is centered, BOTH labels physically shifted on every
                // mode switch — visible motion under the thumb on the most-used control in the app.
                // Selection is already carried by weight, color, and the 20x2 dp underline below.
                style = hudGlyph(14.sp, if (active) FontWeight.SemiBold else FontWeight.Normal),
            )
            Spacer(modifier = Modifier.height(3.dp))
            Box(
                modifier = Modifier
                    .width(20.dp)
                    .height(2.dp)
                    .background(
                        if (active && enabled) CameraColors.TextPrimary else Color.Transparent,
                        RoundedCornerShape(1.dp),
                    ),
            )
        }
    }
}

/** Gallery thumbnail and centered shutter row. TELE lives only in the labeled top-bar chip. */
@Composable
private fun ShutterRow(
    mode: CaptureMode,
    isRecording: Boolean,
    isRecordingStarting: Boolean,
    timelapseRunning: Boolean,
    timerCountdownSec: Int,
    lastMediaUri: android.net.Uri?,
    lastMediaProvenance: MediaProvenance,
    onOpenReview: () -> Unit,
    galleryModifier: Modifier = Modifier,
    reviewEnabled: Boolean,
    onShutter: () -> Unit,
    onSnapshot: () -> Unit,
    modifier: Modifier = Modifier,
    glyphRotation: Float = 0f,
    cameraHealthy: Boolean = true,
    shutterEnabled: Boolean = true,
    stillCaptureAvailable: Boolean = true,
) {
    // BoxWithConstraints, not a plain Box: the snapshot-dot clamp below must measure THIS ROW, and
    // the row is not the screen. Configuration.screenWidthDp (and LocalWindowInfo.containerSize)
    // report the display/window, which in split-screen, freeform, or a letterboxed large-screen
    // window is wider than the row that actually holds these controls — so the clamp would go
    // slack exactly in the window shapes it exists for.
    BoxWithConstraints(modifier = modifier) {
        val rowWidth = maxWidth
        // Counter-rotate the review thumbnail so its image reads upright as the phone turns.
        GalleryThumb(
            uri = lastMediaUri,
            provenance = lastMediaProvenance,
            onClick = onOpenReview,
            enabled = reviewEnabled,
            modifier = galleryModifier.align(Alignment.CenterStart).rotate(glyphRotation),
        )
        // The shutter/stop control is anchored at the EXACT box center so it never moves when the
        // in-REC snapshot dot appears (cycle-6 D-10: the old centered Row re-centered the pair at
        // REC start, shifting the control ~31 dp at the moment the thumb is on it). The dot offsets
        // from the fixed shutter instead: 38 dp shutter half + 14 dp gap + 24 dp dot half = 76 dp.
        if (mode == CaptureMode.VIDEO && isRecording && !isRecordingStarting) {
            // The row has already consumed its external 12 dp padding. Resolve against LOCAL width
            // and refuse the secondary action when three non-overlapping targets cannot fit; moving
            // the dot toward the shutter to clear Gallery produced a 10 dp shutter overlap at 320 dp.
            snapshotOffsetForRow(rowWidth.value)?.let { snapshotOffsetDp ->
                SnapshotButton(
                    onClick = onSnapshot,
                    enabled = stillCaptureAvailable,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(x = -snapshotOffsetDp.dp),
                )
            }
        }
        ShutterButton(
            mode = mode,
            isRecording = isRecording,
            timelapseRunning = timelapseRunning,
            timerCountdownSec = timerCountdownSec,
            onClick = onShutter,
            cameraHealthy = cameraHealthy,
            enabled = shutterEnabled,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/** Pure local-row geometry for the optional 48 dp in-REC still target. */
internal fun snapshotOffsetForRow(rowWidthDp: Float): Float? {
    if (!rowWidthDp.isFinite() || rowWidthDp <= 0f) return null
    val halfWidth = rowWidthDp / 2f
    val minimumOffsetFromShutter = SHUTTER_TARGET_DP / 2f + SNAPSHOT_TARGET_DP / 2f
    val maximumOffsetBeforeGallery = halfWidth - GALLERY_TARGET_DP - SNAPSHOT_TARGET_DP / 2f
    if (maximumOffsetBeforeGallery < minimumOffsetFromShutter) return null
    return SNAPSHOT_IDEAL_OFFSET_DP.coerceAtMost(maximumOffsetBeforeGallery)
        .coerceAtLeast(minimumOffsetFromShutter)
}

private const val GALLERY_TARGET_DP = 52f
private const val SNAPSHOT_TARGET_DP = 48f
private const val SHUTTER_TARGET_DP = 76f
private const val SNAPSHOT_IDEAL_OFFSET_DP = 76f

/** Internal test probe; custom semantics keys are not exposed to accessibility services. */
internal val ShutterVisualAlpha = SemanticsPropertyKey<Float>("ShutterVisualAlpha")
internal val ShutterKeyboardFocused = SemanticsPropertyKey<Boolean>("ShutterKeyboardFocused")

/** The single value applied to both the composed layer and its non-announced test probe. */
internal fun shutterVisualAlpha(cameraHealthy: Boolean): Float = if (cameraHealthy) 1f else 0.35f

/** Large circular shutter: white ring; PHOTO = solid white; VIDEO idle = red dot; recording = red square. */
@Composable
internal fun ShutterButton(
    mode: CaptureMode,
    isRecording: Boolean,
    timelapseRunning: Boolean = false,
    timerCountdownSec: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cameraHealthy: Boolean = true,
    enabled: Boolean = true,
) {
    val cancelSelfTimerDesc = stringResource(R.string.a11y_cancel_self_timer)
    val takePhotoDesc = stringResource(R.string.a11y_take_photo)
    val stopRecordingDesc = stringResource(R.string.a11y_stop_recording)
    val stopTimelapseDesc = stringResource(R.string.a11y_stop_timelapse)
    val startRecordingDesc = stringResource(R.string.a11y_start_recording)
    val readyDesc = stringResource(R.string.a11y_state_ready)
    val unavailableDesc = stringResource(R.string.a11y_state_unavailable)
    val countdownDesc = localizedTimerCountdownDescription(timerCountdownSec)
    // Tactile confirmation: a brief press-scale + a CONFIRM haptic so the shutter never fires "into
    // the void" (designer UX-2). Full-screen flash / thumbnail fly-in are deferred.
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var keyboardFocused by remember { mutableStateOf(false) }
    val shutterScale by animateFloatAsState(if (pressed) 0.9f else 1f, label = "shutterScale")
    val visualAlpha = shutterVisualAlpha(cameraHealthy)
    val activate = {
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        onClick()
    }
    Box(
        modifier = modifier
            .size(76.dp)
            .scale(shutterScale)
            // Camera down (opening, reconfiguring, or recovery exhausted): a NEW capture/start
            // would be declined anyway, so dim the button instead of making it look ready in front
            // of a black viewfinder. Already-owned REC and timelapse work keeps full-strength Stop
            // paint through that transition; a running self-timer remains the sole
            // healthy-false-but-enabled case because its full-screen countdown already carries the
            // cancellation state. An unavailable new action is swallowed, not declined with a
            // status message, so this dimming is its feedback.
            .alpha(visualAlpha)
            .onFocusChanged { keyboardFocused = it.isFocused }
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = if (timerCountdownSec > 0) cancelSelfTimerDesc else null,
                onClick = activate,
            )
            .clearAndSetSemantics {
                contentDescription = when {
                    timelapseRunning -> stopTimelapseDesc
                    timerCountdownSec > 0 -> cancelSelfTimerDesc
                    mode == CaptureMode.PHOTO -> takePhotoDesc
                    isRecording -> stopRecordingDesc
                    else -> startRecordingDesc
                }
                role = Role.Button
                stateDescription = when {
                    timerCountdownSec > 0 -> countdownDesc
                    enabled -> readyDesc
                    else -> unavailableDesc
                }
                if (!enabled) disabled()
                // Unknown/custom semantics properties are invisible to TalkBack. This key proves
                // the exact value already applied to the graphics layer without asking Robolectric
                // to rasterize a custom Canvas, which it does not do.
                this[ShutterVisualAlpha] = visualAlpha
                this[ShutterKeyboardFocused] = keyboardFocused
                onClick {
                    if (!enabled) return@onClick false
                    activate()
                    true
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // Inset the ring by half the stroke: a centered stroke at minDimension/2 hangs 2 dp outside
            // the canvas, and the unhealthy-dim `alpha(0.35f)` forces a clipping composition layer
            // (alpha < 1 implies clip in Compose) — the overhang got sliced only while dimmed, reading
            // as a faceted ring on the video shutter (user-reported 2026-07-25).
            val ringStroke = 4.dp.toPx()
            drawCircle(color = CameraColors.TextPrimary, radius = (size.minDimension - ringStroke) / 2f, style = Stroke(width = ringStroke))
            when {
                timelapseRunning || mode == CaptureMode.VIDEO && isRecording -> {
                    val rectSize = size.minDimension * 0.42f
                    drawRoundRect(
                        color = CameraColors.Record,
                        topLeft = Offset((size.width - rectSize) / 2f, (size.height - rectSize) / 2f),
                        size = Size(rectSize, rectSize),
                        cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx()),
                    )
                }
                mode == CaptureMode.PHOTO -> drawCircle(color = CameraColors.TextPrimary, radius = size.minDimension * 0.38f)
                mode == CaptureMode.VIDEO && !isRecording -> drawCircle(color = CameraColors.Record, radius = size.minDimension * 0.38f)
            }
        }
        ShutterFocusIndicator(keyboardFocused, Modifier.fillMaxSize())
    }
}

/** Composed outside the custom Canvas so host capture and device snapshots can verify its paint. */
@Composable
internal fun ShutterFocusIndicator(focused: Boolean, modifier: Modifier = Modifier) {
    Box(modifier) {
        if (focused) {
            // Separate rings instead of chained borders: chained border modifiers overdraw one
            // another at the same edge and diluted Accent to ~50% (#455060) on black, only 2.57:1.
            // Black survives bright finder frames; the inset full-opacity Accent identifies focus
            // on dark frames.
            Box(
                Modifier
                    .fillMaxSize()
                    .border(5.dp, Color.Black, CircleShape),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(5.dp)
                    .border(2.dp, CameraColors.Accent, CircleShape),
            )
        }
    }
}

/**
 * Small snapshot dot shown only while recording video so a still can be pulled mid-clip. Calls
 * straight into [CameraActions.onCapturePhoto] — the JPEG/RAW readers stay attached for the whole
 * recording.
 */
@Composable
internal fun SnapshotButton(onClick: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    val a11yTakePhotoWhileRecording = stringResource(R.string.a11y_take_photo_while_recording)
    val state = stringResource(if (enabled) R.string.a11y_state_ready else R.string.a11y_state_unavailable)
    // 48 dp touch target, 36 dp visual dot.
    val activate = onClick
    Box(
        modifier = modifier
            .size(48.dp)
            .alpha(if (enabled) 1f else 0.35f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = a11yTakePhotoWhileRecording
                role = Role.Button
                stateDescription = state
                if (!enabled) disabled()
                onClick {
                    if (!enabled) return@onClick false
                    activate()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(36.dp)) {
            drawCircle(color = CameraColors.TextPrimary, radius = size.minDimension / 2f, style = Stroke(width = 2.dp.toPx()))
            drawCircle(color = CameraColors.TextPrimary, radius = size.minDimension * 0.32f)
        }
    }
}
