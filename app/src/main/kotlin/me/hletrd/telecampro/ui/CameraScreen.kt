package me.hletrd.telecampro.ui

import android.graphics.SurfaceTexture
import android.view.HapticFeedbackConstants
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import me.hletrd.telecampro.camera.AspectRatio
import me.hletrd.telecampro.camera.CameraFacing
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.CaptureMode
import me.hletrd.telecampro.camera.ExposureMode
import me.hletrd.telecampro.camera.FlashMode
import me.hletrd.telecampro.camera.FnSlot
import me.hletrd.telecampro.camera.GridType
import me.hletrd.telecampro.camera.finderRect
import me.hletrd.telecampro.camera.finderContainsTopLeftPoint
import me.hletrd.telecampro.camera.teleFinderVisible
import me.hletrd.telecampro.camera.LensChoice
import me.hletrd.telecampro.camera.MediaDeleteScope
import me.hletrd.telecampro.camera.ShutterTimer
import me.hletrd.telecampro.camera.controlAvailability
import me.hletrd.telecampro.camera.controlCapabilities
import me.hletrd.telecampro.camera.teleDisplayBase
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
import me.hletrd.telecampro.ui.controls.fnSlotLabel
import me.hletrd.telecampro.ui.controls.gridTypeLabel
import me.hletrd.telecampro.ui.controls.fnSlotValue
import me.hletrd.telecampro.ui.controls.manualDialForFnSlot
import me.hletrd.telecampro.ui.controls.manualDialTransition
import me.hletrd.telecampro.ui.controls.performQuickFn
import me.hletrd.telecampro.ui.controls.quickManualDialEnabled
import me.hletrd.telecampro.ui.controls.shutterTimerLabel
import me.hletrd.telecampro.ui.controls.trailingEdgeFadeScrollHint
import me.hletrd.telecampro.ui.controls.whiteBalanceFnChipEnabled
import me.hletrd.telecampro.ui.overlays.AspectMask
import me.hletrd.telecampro.ui.overlays.AudioMeter
import me.hletrd.telecampro.ui.overlays.FrameLinesOverlay
import me.hletrd.telecampro.ui.overlays.FocusReticle
import me.hletrd.telecampro.ui.overlays.GridOverlay
import me.hletrd.telecampro.ui.overlays.HistogramOverlay
import me.hletrd.telecampro.ui.overlays.HudPlate
import me.hletrd.telecampro.ui.overlays.LevelOverlay
import me.hletrd.telecampro.ui.overlays.RecordingIndicator
import me.hletrd.telecampro.ui.overlays.StatusBar
import me.hletrd.telecampro.ui.overlays.TimerCountdown
import me.hletrd.telecampro.ui.overlays.WaveformOverlay
import me.hletrd.telecampro.ui.review.GalleryThumb
import me.hletrd.telecampro.ui.review.MediaReviewOverlay
import me.hletrd.telecampro.ui.theme.CameraColors
import me.hletrd.telecampro.ui.theme.hudGlyph

/**
 * Top inset every free-floating viewfinder lane starts below, so nothing lands on the OSD status
 * row. The row itself sits at 60 dp + statusBars and its labelMedium + 6 dp padding ends ~88-90 dp;
 * QA hit an overlap on the scopes column at 72 dp. ONE constant so the lanes cannot drift apart
 * again — the top-center lane was still at 64 dp and the centered 180 dp zoom bar overlapped a
 * long (full-DISP video) status strip during a pinch.
 */
private val OSD_CLEARANCE_TOP = 100.dp

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
) {
    var sheetVisible by remember { mutableStateOf(false) }
    // Start preview-first. DISP adds the detailed OSD and inline dials for deliberate setup; compact
    // mode still preserves active/critical state and opens one requested ruler at a time.
    var detailsVisible by remember { mutableStateOf(false) }
    var openManualDial by remember { mutableStateOf<DialType?>(null) }
    // In-app review overlay (last saved still, pinch-to-zoom for focus check). Open/closed lives in
    // CameraUiState (state.reviewOpen) so MainActivity's hardware-key handlers can refuse to fire
    // the shutter under the overlay. The reviewed uri is FROZEN here at open time so a timer/
    // timelapse capture completing mid-review can't swap the image being inspected.
    var reviewUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var reviewDeleteScope by remember { mutableStateOf(MediaDeleteScope.FILE_ONLY) }
    // Remembers the last-viewed settings tab so the gear reopens where the user left off.
    var sheetInitialTab by remember { mutableStateOf(ProSheetTab.MY_MENU) }
    var fnOverlayVisible by remember { mutableStateOf(false) }
    val currentActions = rememberUpdatedState(actions)
    val modalVisible = sheetVisible || fnOverlayVisible || (state.reviewOpen && reviewUri != null)

    // MainActivity owns hardware camera keys outside Compose. Mirror every full-screen modal into
    // CameraUiState so volume/camera/zoom/focus input cannot operate the hidden viewfinder behind it.
    LaunchedEffect(modalVisible) {
        currentActions.value.onCameraInputBlockedChange(modalVisible)
    }
    LaunchedEffect(detailsVisible, modalVisible) {
        currentActions.value.onStandbyAudioMeterVisibilityChanged(detailsVisible && !modalVisible)
    }
    DisposableEffect(Unit) {
        onDispose { currentActions.value.onStandbyAudioMeterVisibilityChanged(false) }
    }

    fun openSheet(tab: ProSheetTab) {
        currentActions.value.onCameraInputBlockedChange(true)
        sheetInitialTab = tab
        sheetVisible = true
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

    // Counter-rotates compact on-screen glyphs/labels so they stay upright as the phone turns, even
    // though the activity is portrait-locked. The counter-rotation is +deviceOrientation:
    // GyroEis derives the discrete value from gravity via atan2(x,y), which yields dev=90 for a
    // COUNTER-clockwise (left) landscape and dev=270 for a clockwise (right) landscape — the opposite
    // of the naive assumption. So the glyph must rotate by +dev to undo the phone's turn (a −dev sign
    // left both landscapes 180° off — invisible on symmetric icons, obvious once text rotates).
    // Accumulate an UNWRAPPED target so the animation always takes the shortest ≤90° path.
    var overlayRotationTarget by remember { mutableFloatStateOf(state.deviceOrientation.toFloat()) }
    LaunchedEffect(state.deviceOrientation) {
        overlayRotationTarget = shortestRotationTarget(overlayRotationTarget, state.deviceOrientation.toFloat())
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
            .then(if (modalVisible) Modifier.clearAndSetSemantics { } else Modifier),
    ) {
        // GL sampling, capture masks, tap mapping, and encoder framing currently share a deliberate
        // portrait-window contract. Keep the alternative operator layout dormant until that entire
        // orientation pipeline is implemented and device-verified together.
        val landscapeOperator = false
        val displayedPreviewAspect = state.previewAspect.coerceAtLeast(0.01f)
        // Rest-state height of the bottom cluster, feeding [previewTopPx]. Frozen while a manual
        // dial is open: the cluster growing upward must overlay the preview like every transient
        // panel, not shove the viewfinder around mid-interaction.
        var bottomClusterRestHeightPx by remember { mutableIntStateOf(0) }
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
            val previewHeightPx = (constraints.maxWidth / displayedPreviewAspect).toInt()
            val topOffsetPx = previewTopPx(
                availableHeightPx = constraints.maxHeight,
                previewHeightPx = previewHeightPx,
                // Status bar + the 56dp top icon row + the OSD strip line + breathing room. A dp
                // constant (not a measured top bar) keeps the preview from re-laying-out when the
                // OSD strip toggles; the strip overlays the letterbox area harmlessly either way.
                topChromeMinPx = with(density) {
                    WindowInsets.statusBars.getTop(this) + 100.dp.roundToPx()
                },
                bottomReservePx = bottomClusterRestHeightPx,
            )
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, topOffsetPx) }
                .aspectRatio(displayedPreviewAspect),
        ) {
            val finderVisible = teleFinderVisible(
                enabled = state.teleFinder,
                teleconverter = state.teleconverterMode,
                videoMode = state.mode == CaptureMode.VIDEO,
                aspect = state.aspectRatio,
                punchIn = state.punchInActive,
                zoomRatio = state.controls.zoomRatio,
            )
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics {
                        contentDescription = "Camera viewfinder"
                        customActions = listOf(
                            CustomAccessibilityAction("Focus at center") {
                                currentActions.value.onTapFocus(0.5f, 0.5f)
                                true
                            },
                            CustomAccessibilityAction("Reset focus point") {
                                currentActions.value.onResetFocusPoint()
                                true
                            },
                        )
                    }
                    // Tap-to-focus AND pinch-to-zoom share ONE gesture loop. Two separate pointerInput
                    // blocks (detectTapGestures + detectTransformGestures) fought each other: the tap
                    // detector consumed the gesture and killed the pinch after ~2 frames, so the pinch
                    // scale never left 1.0 (device-diagnosed via ZoomDbg). Handling both in a single
                    // awaitEachGesture removes the conflict: two fingers → pinch-zoom, a clean single
                    // stationary touch → tap-focus.
                    .pointerInput(finderVisible) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var maxPointers = 1
                            var zoomed = false
                            var dragged = false
                            while (true) {
                                val event = awaitPointerEvent()
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
                            if (maxPointers == 1 && !zoomed && !dragged) {
                                val w = size.width.toFloat()
                                val h = size.height.toFloat()
                                if (w > 0f && h > 0f) {
                                    if (!finderVisible || !finderContainsTopLeftPoint(
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
                factory = { context ->
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
            // camera feed. Exact predicate: user toggle + Photo + 4:3 + TELE + active punch-in. The
            // shared teleFinderVisible predicate is the same gate the engine resolves for GL, so the
            // border and overview content cannot drift. The rect comes from the same pure finderRect
            // the GL scissor uses — sized from the FULL aspect box, with independent side/bottom
            // clearance (the previous padding-before-fillMaxWidth chain shrank the border ~6% below
            // the GL content box). Absolute anchor + absolute offset: the GL box has no layout
            // direction, so the
            // border must not mirror to bottom-right under RTL system locales. Square corners trace
            // the sharp GL scissor rect.
            if (finderVisible) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val rect = finderRect(maxWidth.value, maxHeight.value)
                    Box(
                        modifier = Modifier
                            .align(AbsoluteAlignment.BottomLeft)
                            .absoluteOffset(x = rect.x.dp, y = (-rect.y).dp)
                            .size(rect.width.dp, rect.height.dp)
                            // One-off structural stroke, not ink and not a composition guide: it
                            // traces the GL scissor rect of a SECOND rendering of the camera frame,
                            // so it must read against arbitrary live pixels on both of its sides.
                            // Hence 0.85 rather than the 0.55 GuideLine the thirds/frame-line rules
                            // use — those sit over ONE image and may recede; this one delimits two.
                            .border(1.dp, Color.White.copy(alpha = 0.85f))
                            .semantics {
                                // Name only. The node declares no actions, so TalkBack already
                                // announces it as non-interactive; a stateDescription saying so spent
                                // a spoken line restating what the absence of an action already says.
                                contentDescription = "Loupe overview"
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

        Row(
            // The full-width CONTAINER stays fixed — spinning a fillMaxWidth row would swing a
            // screen-wide box off screen no matter how the slot is reserved. Its children rotate
            // individually below, each of which sizes to its own content.
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 60.dp),
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
                .padding(top = OSD_CLEARANCE_TOP),
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
                        // The WHOLE indicator turns, not just the number: a screen-fixed bar under
                        // an upright readout reads as a broken pairing in landscape, because the bar
                        // then runs across the viewer's vertical (user-reported 2026-07-29). Passing
                        // 0 here and rotating the composable keeps the number from turning twice.
                        numberRotation = 0f,
                        modifier = Modifier.rotateLayout(overlayRotation),
                    )
                }
            }

            if (showHalfPressLabel(state.halfPressActive, state.halfPressAction, state.tapFocusHeld)) {
                Text(
                    text = state.halfPressAction.label,
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
                .padding(end = 12.dp, top = OSD_CLEARANCE_TOP),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.isRecording && !state.isRecordingStarting) {
                RecordingIndicator(elapsedMs = state.recordElapsedMs, modifier = Modifier.rotateLayout(overlayRotation))
            }
            // Sony-style standby metering: input levels are visible while video is ARMED,
            // not just while rolling (the engine runs a levels-only mic tap in standby).
            if (state.mode == CaptureMode.VIDEO && state.recordAudio && (detailsVisible || state.isRecording)) {
                AudioMeter(level = state.audioLevel, modifier = Modifier.rotateLayout(overlayRotation))
            }
            if (detailsVisible && state.histogram) {
                HistogramOverlay(data = state.histogramData, modifier = Modifier.rotateLayout(overlayRotation))
            }
            if (detailsVisible && state.waveform) {
                WaveformOverlay(data = state.waveformData, modifier = Modifier.rotateLayout(overlayRotation))
            }
        }

        if (state.timerCountdownSec > 0) {
            // The 120 sp digit is the largest orientation-sensitive glyph on screen — a sideways
            // "6" reads ambiguously in a landscape self-timer, so it counter-rotates too.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .focusable()
                    .clearAndSetSemantics {
                        // "Self-timer": one feature, one spelling across every spoken string.
                        contentDescription = "Self-timer"
                        stateDescription = timerCountdownDescription(state.timerCountdownSec)
                        // POLITE, not Assertive: the stateDescription changes at 1 Hz, so Assertive
                        // made a 10 s timer interrupt TalkBack ten times. The sibling ticking readout
                        // already takes the opposite line for the same reason (RecordingIndicator:
                        // "elapsed telemetry must not be re-announced every second").
                        liveRegion = LiveRegionMode.Polite
                        role = Role.Button
                        onClick {
                            currentActions.value.onCapturePhoto()
                            true
                        }
                    }
                    .clickable(
                        onClickLabel = "Cancel self-timer",
                        role = Role.Button,
                        onClick = { currentActions.value.onCapturePhoto() },
                    ),
            ) {
                TimerCountdown(
                    seconds = state.timerCountdownSec,
                    modifier = Modifier.fillMaxSize(),
                    rotationDegrees = overlayRotation,
                )
            }
        }

        state.statusMessage?.let { message ->
            // Centered transient toast ("Video saved" / errors). Previously pinned near the
            // top, where it collided with the OSD status row (300mm / codec / etc.) — QA-reported.
            // This is the channel for capture/permission/storage ERRORS, so its scrim rides the tested
            // contrast floor (05486cb) like every sibling pill — 0.55 cleared 4.5 only by a hair and
            // was one alpha tweak from regressing the app's most important on-screen text.
            Text(
                text = message,
                color = CameraColors.TextPrimary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(HudPlate, RoundedCornerShape(8.dp))
                    .semantics {
                        liveRegion = if (message.isUrgentStatus()) {
                            LiveRegionMode.Assertive
                        } else {
                            LiveRegionMode.Polite
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        TopBar(
            state = state,
            actions = actions,
            onOpenSheet = {
                // Block Activity-owned camera keys before Compose can draw the modal.
                currentActions.value.onCameraInputBlockedChange(true)
                sheetVisible = true // reopen to the remembered last tab
            },
            compact = !detailsVisible,
            onToggleDisp = {
                openManualDial = null
                detailsVisible = !detailsVisible
            },
            glyphRotation = overlayRotation,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 8.dp),
        )

        // Exposure meter: pinned to the LEFT edge as a vertical scale (the scopes own the right).
        // A fixed home beats the old jump between top/bottom as the dial opened (feedback).
        if (shouldShowExposureMeter(state.controls.exposureMode, exposureMeterTransient)) ExposureMeter(
            state = state,
            compact = !detailsVisible,
            modifier = Modifier
                .align(Alignment.CenterStart)
                // 12 dp start — the ONE left inset every left-anchored element shares (status OSD,
                // exposure meter, Fn chip row); mixed 10/12/16 insets read as misalignment.
                .padding(start = 12.dp),
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
                    currentActions.value.onCameraInputBlockedChange(true)
                    fnOverlayVisible = true
                },
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        val capturePane: @Composable () -> Unit = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(if (landscapeOperator) 4.dp else 8.dp),
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    // GONE while FRONT (not disabled): the 0.6/1/3/10 presets are rear-lens
                    // concepts — the selfie route has exactly one lens, so a disabled rail would
                    // advertise choices that cannot exist (same rationale as the TELE chip).
                    if (state.facing == CameraFacing.BACK) {
                        FocalRail(
                            state = state,
                            onLens = actions::onLens,
                            onTeleZoomMark = actions::onTeleZoomMark,
                            glyphRotation = overlayRotation,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (!detailsVisible) {
                        val entryAnchor = fnEntryAnchor(state.deviceOrientation)
                        CompactFnButton(
                            onClick = {
                                currentActions.value.onCameraInputBlockedChange(true)
                                fnOverlayVisible = true
                            },
                            glyphRotation = overlayRotation,
                            modifier = when (entryAnchor) {
                                FnEntryAnchor.START -> Modifier
                                    .align(AbsoluteAlignment.CenterLeft)
                                    .absolutePadding(left = 12.dp)
                                FnEntryAnchor.END -> Modifier
                                    .align(AbsoluteAlignment.CenterRight)
                                    .absolutePadding(right = 12.dp)
                            },
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

                ShutterRow(
                    mode = state.mode,
                    isRecording = state.isRecording,
                    isRecordingStarting = state.isRecordingStarting,
                    timerCountdownSec = state.timerCountdownSec,
                    lastMediaUri = state.lastMediaUri,
                    onOpenReview = {
                        state.lastMediaUri?.let { uri ->
                            val familyPinned = currentActions.value.onReviewOpenChange(true, uri)
                            reviewUri = uri
                            reviewDeleteScope = if (familyPinned) {
                                state.lastMediaDeleteScope
                            } else {
                                MediaDeleteScope.FILE_ONLY
                            }
                        }
                    },
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

        if (landscapeOperator) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .fillMaxWidth(0.82f)
                    .then(operatorChrome)
                    .padding(top = 10.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(modifier = Modifier.weight(1f)) { manualPane() }
                Box(modifier = Modifier.weight(1f)) { capturePane() }
            }
        } else {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .then(operatorChrome)
                    // bottom 20: the gesture-nav inset on this panel is thin, and 8 dp left the
                    // shutter nearly touching the home-bar swipe zone (user-reported 2026-07-25).
                    .padding(top = 12.dp, bottom = 20.dp)
                    // Rest-state measurement for the preview's adaptive top ([previewTopPx]); a
                    // dial-open growth spike must not re-place the viewfinder, so only the closed
                    // state records.
                    .onSizeChanged {
                        if (openManualDial == null) bottomClusterRestHeightPx = it.height
                    },
                verticalArrangement = Arrangement.Top,
            ) {
                // Keep the dial cluster composed at zero height in compact rest state. Disposing it
                // on close skipped the MF-assist cleanup and left the auto loupe enabled.
                manualPane()
                if (detailsVisible || openManualDial != null) Spacer(modifier = Modifier.height(8.dp))
                capturePane()
            }
        }
    }
    } // end of the viewfinder's Ltr scope

    if (sheetVisible) {
        ProSheet(
            state = state,
            actions = actions,
            initialTab = sheetInitialTab,
            onTabChange = { sheetInitialTab = it },
            onDismiss = { sheetVisible = false },
            onSelectManualDial = ::selectManualDial,
        )
    }

    if (fnOverlayVisible) {
        FnOverlay(
            state = state,
            actions = actions,
            onSelectManualDial = ::selectManualDial,
            onDismiss = { fnOverlayVisible = false },
            glyphRotation = overlayRotation,
        )
    }

    val frozenReviewUri = reviewUri
    if (state.reviewOpen && frozenReviewUri != null) {
        MediaReviewOverlay(
            uri = frozenReviewUri,
            deleteScope = reviewDeleteScope,
            overlayRotation = overlayRotation,
            onClose = {
                actions.onReviewOpenChange(false, frozenReviewUri)
                reviewUri = null
            },
            onDelete = {
                actions.onDeleteLastMedia(frozenReviewUri)
                actions.onReviewOpenChange(false, frozenReviewUri)
                reviewUri = null
            },
        )
    }
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

@Composable
private fun TopBar(
    state: CameraUiState,
    actions: CameraActions,
    onOpenSheet: () -> Unit,
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .trailingEdgeFadeScrollHint(topBarScroll)
                .horizontalScroll(topBarScroll),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // FIXED slot (like flip/DISP/gear), not the scrolling row: as the last scrolling item
            // the chip vanished off-screen whenever photo full-DISP filled the row — the app's
            // headline function must keep one stable, always-visible home in every rear mode.
            // GONE (not disabled) while FRONT: the converter is a rear-3× accessory, so the chip is
            // a rear-only concept with no meaningful disabled state on the selfie route.
            if (state.facing == CameraFacing.BACK) {
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
            FlipCameraButton(
                onClick = actions::onToggleFrontCamera,
                enabled = !recordingLocked,
                modifier = Modifier.rotate(glyphRotation),
                frontFacing = state.facing == CameraFacing.FRONT,
            )
            DispButton(infoHidden = compact, onClick = onToggleDisp, modifier = Modifier.rotate(glyphRotation))
            GearButton(onClick = onOpenSheet, modifier = Modifier.rotate(glyphRotation))
        }
    }
}

/** Persistent, directly dismissible feedback for the tap-owned AF/AE point after its reticle fades. */
@Composable
private fun TapFocusHoldChip(onReset: () -> Unit, modifier: Modifier = Modifier) {
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
                contentDescription = "Reset focus point"
                stateDescription = "Tap focus held"
                role = Role.Button
                onClick {
                    activate()
                    true
                }
            }
            .clickable(role = Role.Button, onClickLabel = "Reset focus point", onClick = onReset),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            // The viewfinder's compact form of the menu's "Tap Focus" row (ProSheet.kt); the a11y
            // state above spells the same concept out as "Tap focus held". It replaced "AF HOLD",
            // which read as a sibling of the unrelated AFL exposure-lock tag in the OSD.
            // "AF HOLD" remains the INTERNAL concept name (CameraEngine, docs/ARCHITECTURE.md).
            text = "TAP AF ×",
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
private fun ChromeIconButton(
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
            .focusable()
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
            }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
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
        contentDescription = "Flash",
        stateDescription = flashModeLabel(mode),
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
        contentDescription = "Self-timer",
        stateDescription = shutterTimerLabel(timer),
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
        contentDescription = "Aspect ratio",
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
        contentDescription = "Grid",
        stateDescription = gridTypeLabel(type),
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
private fun TeleChip(active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
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
            .focusable()
            .clearAndSetSemantics {
                contentDescription = "Teleconverter"
                stateDescription = if (active) "On" else "Off"
                role = Role.Button
                if (!enabled) disabled()
                onClick {
                    if (!enabled) return@onClick false
                    activate()
                    true
                }
            }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
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
            Text("TELE", color = fg, style = hudGlyph(11.sp))
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
) {
    ChromeIconButton(
        onClick = onClick,
        contentDescription = "Switch camera",
        modifier = modifier,
        enabled = enabled,
        // The glyph is the same on both cameras, so without this a TalkBack user has no way at all
        // to tell which camera is live — and entering FRONT silently forces the teleconverter off.
        stateDescription = if (frontFacing) "Front camera" else "Rear camera",
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

@Composable
private fun GearButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    // A "tune" / sliders glyph (three horizontal rails, each with a knob at a different position) —
    // reads more clearly as "settings" than the old hand-drawn gear on this dense panel.
    ChromeIconButton(onClick = onClick, contentDescription = "Open settings", modifier = modifier) {
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
        contentDescription = if (infoHidden) "Show shooting info" else "Hide shooting info",
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
        state.freeBytes, state.mode, state.videoResolution, state.videoFrameRate,
        state.bitrateLevel, state.videoCodec, state.photoFormats,
    ) {
        when {
            state.freeBytes <= 0 -> null
            state.mode == CaptureMode.VIDEO -> {
                val bps = me.hletrd.telecampro.camera.videoBitRate(
                    state.videoResolution.width, state.videoResolution.height,
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
    Row(
        modifier = modifier
            .background(HudPlate, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
            // The pill is ONE readout, so it clears its leaves and speaks them together: unmerged,
            // TalkBack read the raw glyphs — "45m" (which is a distance aloud) and a bare "1234"
            // that names nothing. The visible shortening stays; only the spoken form spells it out.
            .clearAndSetSemantics {
                contentDescription = statusInfoDescription(
                    batteryPct = state.batteryPct,
                    remaining = remaining,
                    video = state.mode == CaptureMode.VIDEO,
                )
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.batteryPct >= 0) {
            Text(
                "${state.batteryPct}%",
                color = if (state.batteryPct <= 15) CameraColors.Record else CameraColors.TextPrimary,
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
 * advertised range. The caller rotates the WHOLE thing so the readout and its scale stay a pair —
 * `rotateLayout` reserves the ROTATED bounding box, so the wider landscape footprint is laid out
 * rather than overlapping its neighbours.
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
        // [numberRotation] survives for callers that place the readout inside already-rotated
        // chrome; the viewfinder passes 0 and rotates the whole indicator instead.
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
private fun FnOverlay(
    state: CameraUiState,
    actions: CameraActions,
    onSelectManualDial: (DialType) -> Unit,
    onDismiss: () -> Unit,
    glyphRotation: Float = 0f,
) {
    val dismiss = onDismiss
    BackHandler(onBack = onDismiss)
    val slots = remember(state.mode, state.activeFnSlots) {
        fnOverlaySlots(state.mode, state.activeFnSlots)
    }
    val trayAnchor = fnOverlayAnchor(state.deviceOrientation)
    val gridRows = remember(slots, state.deviceOrientation) {
        fnOverlayGridRows(slots, state.deviceOrientation)
    }
    val contentAxis = fnTileContentAxis(state.deviceOrientation)
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
                    .clip(RoundedCornerShape(8.dp))
                    // The full-screen scrim stays light, but the compact panel itself is opaque so
                    // focal-rail values cannot read as a second line inside held-landscape Fn tiles.
                    // Pill IS that opaque panel grey — the settings sheet uses it — so a hand-rolled
                    // 0xFF181818 here was only a second, near-identical panel colour.
                    .background(CameraColors.Pill)
                    .border(1.dp, CameraColors.Hairline, RoundedCornerShape(8.dp))
                    .semantics {
                        paneTitle = "Function menu"
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
                        "Fn",
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
                                contentDescription = "Close function menu"
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
                            "Close",
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
                                    value = fnSlotValue(slot, state),
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
private fun FnOverlayTile(
    slot: FnSlot,
    value: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyphRotation: Float = 0f,
    contentAxis: FnTileContentAxis = FnTileContentAxis.PORTRAIT,
) {
    val activate = onClick
    val heldLandscape = contentAxis != FnTileContentAxis.PORTRAIT
    val visualLabel = fnOverlayVisualLabel(slot, heldLandscape)
    val visualValue = fnOverlayVisualValue(slot, value, heldLandscape)
    val foregroundAlpha = if (enabled) 1f else 0.55f
    Box(
        modifier = modifier
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) CameraColors.Block else CameraColors.BlockDisabled)
            .border(1.dp, CameraColors.Hairline, RoundedCornerShape(8.dp))
            .focusable()
            .clearAndSetSemantics {
                contentDescription = fnSlotLabel(slot)
                stateDescription = value
                role = Role.Button
                if (!enabled) disabled()
                onClick {
                    if (!enabled) return@onClick false
                    activate()
                    true
                }
            }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            // Carve-out from the shared 12/6 pill inset: this tile sits in the width-contended
            // 148 dp held tray (CameraScreenPolicy), so only the vertical joins the scale.
            .padding(horizontal = 9.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (heldLandscape) {
            // The portrait-locked Activity becomes a narrow physical strip when held sideways.
            // Separating glyphs on the raw X axis stacks them on the held device's Y axis.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (contentAxis == FnTileContentAxis.HELD_LANDSCAPE_VALUE_FIRST_RAW) {
                    FnOverlayTileValue(visualValue, foregroundAlpha, Modifier.rotateLayout(glyphRotation))
                    FnOverlayTileLabel(visualLabel, foregroundAlpha, Modifier.rotateLayout(glyphRotation))
                } else {
                    FnOverlayTileLabel(visualLabel, foregroundAlpha, Modifier.rotateLayout(glyphRotation))
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
                FnOverlayTileLabel(visualLabel, foregroundAlpha)
                FnOverlayTileValue(visualValue, foregroundAlpha)
            }
        }
    }
}

@Composable
private fun FnOverlayTileLabel(text: String, alpha: Float, modifier: Modifier = Modifier) {
    Text(
        text,
        color = CameraColors.TextSecondary.copy(alpha = alpha),
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
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
            else -> "%+.1f".format(java.util.Locale.US, compensationEv)
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
        Text(label, color = CameraColors.TextPrimary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
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
private fun FocalRail(
    state: CameraUiState,
    onLens: (LensChoice) -> Unit,
    onTeleZoomMark: (Float) -> Unit,
    modifier: Modifier = Modifier,
    glyphRotation: Float = 0f,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.Center) {
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
                        contentDescription = "$label zoom",
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
                LensChoice.entries.forEach { choice ->
                    RailChip(
                        label = choice.label,
                        contentDescription = "${choice.label} lens",
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

/** One rail chip: identical box, semantics, and plate treatment for a lens pick and a zoom mark. */
@Composable
private fun RailChip(
    label: String,
    contentDescription: String,
    presentation: FocalRailState,
    onClick: () -> Unit,
    glyphRotation: Float,
) {
    val description = contentDescription
    Box(
        modifier = Modifier
            .size(48.dp)
            .rotate(glyphRotation)
            .focusable()
            // Selection and activation must live on the same outer node. A separate
            // selected semantic followed by clickable exported selected=false from the
            // actionable AccessibilityNodeInfo on PMA110.
            .clearAndSetSemantics {
                this.contentDescription = description
                stateDescription = presentation.stateDescription
                role = presentation.accessibilityRole
                selected = presentation.selected
                if (!presentation.enabled) disabled()
                onClick {
                    if (!presentation.enabled) return@onClick false
                    onClick()
                    true
                }
            }
            .selectable(
                selected = presentation.selected,
                enabled = presentation.enabled,
                role = presentation.accessibilityRole,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(
                    if (presentation.selected) CameraColors.TextPrimary
                    else HudPlate,
                )
                .border(1.dp, CameraColors.AffordanceEdge, CircleShape)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = if (presentation.selected) Color.Black else CameraColors.TextPrimary,
                // SemiBold(600) vs Medium(500): a weight step that actually RENDERS.
                // The old Bold/SemiBold pair resolved to one bundled face (600), so the
                // selection was carried by the filled pill alone (BACKLOG UI16).
                style = hudGlyph(
                    12.sp,
                    if (presentation.selected) FontWeight.SemiBold else FontWeight.Medium,
                ),
                modifier = Modifier.alpha(if (presentation.enabled) 1f else 0.38f),
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
                text = "Photo",
                active = mode == CaptureMode.PHOTO,
                enabled = enabled,
                onClick = { onModeChange(CaptureMode.PHOTO) },
                modifier = Modifier.rotateLayout(glyphRotation),
            )
            ModeLabel(
                text = "Video",
                active = mode == CaptureMode.VIDEO,
                enabled = enabled,
                onClick = { onModeChange(CaptureMode.VIDEO) },
                modifier = Modifier.rotateLayout(glyphRotation),
            )
        }
    }
}

@Composable
private fun ModeLabel(text: String, active: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val presentation = modeCarouselState(active, enabled)
    val activate = onClick
    Box(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .focusable()
            .clearAndSetSemantics {
                contentDescription = "$text mode"
                stateDescription = presentation.stateDescription
                role = presentation.accessibilityRole
                selected = presentation.selected
                if (!presentation.enabled) disabled()
                onClick {
                    if (!presentation.enabled) return@onClick false
                    activate()
                    true
                }
            }
            .selectable(
                selected = presentation.selected,
                enabled = presentation.enabled,
                role = presentation.accessibilityRole,
                onClick = onClick,
            ),
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
    timerCountdownSec: Int,
    lastMediaUri: android.net.Uri?,
    onOpenReview: () -> Unit,
    onShutter: () -> Unit,
    onSnapshot: () -> Unit,
    modifier: Modifier = Modifier,
    glyphRotation: Float = 0f,
    cameraHealthy: Boolean = true,
    shutterEnabled: Boolean = true,
    stillCaptureAvailable: Boolean = true,
) {
    Box(modifier = modifier) {
        // Counter-rotate the review thumbnail so its image reads upright as the phone turns.
        GalleryThumb(
            uri = lastMediaUri,
            onClick = onOpenReview,
            modifier = Modifier.align(Alignment.CenterStart).rotate(glyphRotation),
        )
        // The shutter/stop control is anchored at the EXACT box center so it never moves when the
        // in-REC snapshot dot appears (cycle-6 D-10: the old centered Row re-centered the pair at
        // REC start, shifting the control ~31 dp at the moment the thumb is on it). The dot offsets
        // from the fixed shutter instead: 38 dp shutter half + 14 dp gap + 24 dp dot half = 76 dp.
        if (mode == CaptureMode.VIDEO && isRecording && !isRecordingStarting) {
            SnapshotButton(
                onClick = onSnapshot,
                enabled = stillCaptureAvailable,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(x = (-76).dp),
            )
        }
        ShutterButton(
            mode = mode,
            isRecording = isRecording,
            timerCountdownSec = timerCountdownSec,
            onClick = onShutter,
            cameraHealthy = cameraHealthy,
            enabled = shutterEnabled,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/** Large circular shutter: white ring; PHOTO = solid white; VIDEO idle = red dot; recording = red square. */
@Composable
private fun ShutterButton(
    mode: CaptureMode,
    isRecording: Boolean,
    timerCountdownSec: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cameraHealthy: Boolean = true,
    enabled: Boolean = true,
) {
    // Tactile confirmation: a brief press-scale + a CONFIRM haptic so the shutter never fires "into
    // the void" (designer UX-2). Full-screen flash / thumbnail fly-in are deferred.
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shutterScale by animateFloatAsState(if (pressed) 0.9f else 1f, label = "shutterScale")
    val activate = {
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        onClick()
    }
    Canvas(
        modifier = modifier
            .size(76.dp)
            .scale(shutterScale)
            // Camera down (opening, reconfiguring, or recovery exhausted): the tap would be
            // declined anyway — dim the button so it stops LOOKING ready in front of a black
            // viewfinder. It is also DISABLED in that state (shutterEnabled folds in cameraReady,
            // so the only healthy-false-but-enabled case is a running self-timer, whose tap
            // cancels): the tap is swallowed, not declined with a message. The dimming IS the
            // feedback — an earlier version of this comment promised a status message that the
            // enabled=shutterEnabled clickable below can never reach.
            .alpha(if (cameraHealthy) 1f else 0.35f)
            .focusable()
            .clearAndSetSemantics {
                contentDescription = when {
                    timerCountdownSec > 0 -> "Cancel self-timer"
                    mode == CaptureMode.PHOTO -> "Take photo"
                    isRecording -> "Stop recording"
                    else -> "Start recording"
                }
                role = Role.Button
                stateDescription = when {
                    timerCountdownSec > 0 -> timerCountdownDescription(timerCountdownSec)
                    enabled -> "Ready"
                    else -> "Unavailable"
                }
                if (!enabled) disabled()
                onClick {
                    if (!enabled) return@onClick false
                    activate()
                    true
                }
            }
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = if (timerCountdownSec > 0) "Cancel self-timer" else null,
                onClick = activate,
            ),
    ) {
        // Inset the ring by half the stroke: a centered stroke at minDimension/2 hangs 2 dp outside
        // the canvas, and the unhealthy-dim `alpha(0.35f)` forces a clipping composition layer
        // (alpha < 1 implies clip in Compose) — the overhang got sliced only while dimmed, reading
        // as a faceted ring on the video shutter (user-reported 2026-07-25).
        val ringStroke = 4.dp.toPx()
        drawCircle(color = CameraColors.TextPrimary, radius = (size.minDimension - ringStroke) / 2f, style = Stroke(width = ringStroke))
        when {
            mode == CaptureMode.PHOTO -> drawCircle(color = CameraColors.TextPrimary, radius = size.minDimension * 0.38f)
            mode == CaptureMode.VIDEO && !isRecording -> drawCircle(color = CameraColors.Record, radius = size.minDimension * 0.38f)
            else -> {
                val rectSize = size.minDimension * 0.42f
                drawRoundRect(
                    color = CameraColors.Record,
                    topLeft = Offset((size.width - rectSize) / 2f, (size.height - rectSize) / 2f),
                    size = Size(rectSize, rectSize),
                    cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx()),
                )
            }
        }
    }
}

/**
 * Small snapshot dot shown only while recording video so a still can be pulled mid-clip. Calls
 * straight into [CameraActions.onCapturePhoto] — the JPEG/RAW readers stay attached for the whole
 * recording.
 */
@Composable
private fun SnapshotButton(onClick: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    // 48 dp touch target, 36 dp visual dot.
    val activate = onClick
    Box(
        modifier = modifier
            .size(48.dp)
            .alpha(if (enabled) 1f else 0.35f)
            .focusable()
            .clearAndSetSemantics {
                contentDescription = "Take photo while recording"
                role = Role.Button
                stateDescription = if (enabled) "Ready" else "Unavailable"
                if (!enabled) disabled()
                onClick {
                    if (!enabled) return@onClick false
                    activate()
                    true
                }
            }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(36.dp)) {
            drawCircle(color = CameraColors.TextPrimary, radius = size.minDimension / 2f, style = Stroke(width = 2.dp.toPx()))
            drawCircle(color = CameraColors.TextPrimary, radius = size.minDimension * 0.32f)
        }
    }
}
