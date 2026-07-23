package com.hletrd.findx9tele.ui

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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import com.hletrd.findx9tele.camera.Antibanding
import com.hletrd.findx9tele.camera.AspectRatio
import com.hletrd.findx9tele.camera.AutoExposure
import com.hletrd.findx9tele.camera.BitrateLevel
import com.hletrd.findx9tele.camera.CameraFacing
import com.hletrd.findx9tele.camera.CameraUiState
import com.hletrd.findx9tele.camera.CaptureMode
import com.hletrd.findx9tele.camera.ColorEffect
import com.hletrd.findx9tele.camera.ColorTransfer
import com.hletrd.findx9tele.camera.DriveMode
import com.hletrd.findx9tele.camera.ExposureMode
import com.hletrd.findx9tele.camera.FrameLineType
import com.hletrd.findx9tele.camera.FlashMode
import com.hletrd.findx9tele.camera.FnSlot
import com.hletrd.findx9tele.camera.FocusMode
import com.hletrd.findx9tele.camera.GridType
import com.hletrd.findx9tele.camera.HardwareKeyAction
import com.hletrd.findx9tele.camera.finderRect
import com.hletrd.findx9tele.camera.finderContainsTopLeftPoint
import com.hletrd.findx9tele.camera.teleFinderVisible
import com.hletrd.findx9tele.camera.LensChoice
import com.hletrd.findx9tele.camera.MediaDeleteScope
import com.hletrd.findx9tele.camera.MeteringMode
import com.hletrd.findx9tele.camera.PhotoFormats
import com.hletrd.findx9tele.camera.ProcessingLevel
import com.hletrd.findx9tele.camera.ShutterMode
import com.hletrd.findx9tele.camera.ShutterTimer
import com.hletrd.findx9tele.camera.TELECONVERTER_MAGNIFICATION
import com.hletrd.findx9tele.camera.VideoCodec
import com.hletrd.findx9tele.camera.WbMode
import com.hletrd.findx9tele.camera.controlAvailability
import com.hletrd.findx9tele.camera.controlCapabilities
import com.hletrd.findx9tele.ui.controls.CompactFnButton
import com.hletrd.findx9tele.ui.controls.DialType
import com.hletrd.findx9tele.ui.controls.ManualDialCluster
import com.hletrd.findx9tele.ui.controls.ProSheet
import com.hletrd.findx9tele.ui.controls.ProSheetTab
import com.hletrd.findx9tele.ui.controls.aspectRatioLabel
import com.hletrd.findx9tele.ui.controls.exposureMeterCompensationEv
import com.hletrd.findx9tele.ui.controls.nextAspect
import com.hletrd.findx9tele.ui.controls.nextAvailable
import com.hletrd.findx9tele.ui.controls.nextTimer
import com.hletrd.findx9tele.ui.controls.quickFnEnabled
import com.hletrd.findx9tele.ui.controls.flashModeLabel
import com.hletrd.findx9tele.ui.controls.fnSlotLabel
import com.hletrd.findx9tele.ui.controls.fnSlotValue
import com.hletrd.findx9tele.ui.controls.manualDialForFnSlot
import com.hletrd.findx9tele.ui.controls.manualDialTransition
import com.hletrd.findx9tele.ui.controls.performQuickFn
import com.hletrd.findx9tele.ui.controls.quickManualDialEnabled
import com.hletrd.findx9tele.ui.controls.shutterTimerLabel
import com.hletrd.findx9tele.ui.controls.trailingEdgeFadeScrollHint
import com.hletrd.findx9tele.ui.controls.whiteBalanceFnChipEnabled
import com.hletrd.findx9tele.ui.overlays.AspectMask
import com.hletrd.findx9tele.ui.overlays.AudioMeter
import com.hletrd.findx9tele.ui.overlays.FrameLinesOverlay
import com.hletrd.findx9tele.ui.overlays.FocusReticle
import com.hletrd.findx9tele.ui.overlays.GridOverlay
import com.hletrd.findx9tele.ui.overlays.HistogramOverlay
import com.hletrd.findx9tele.ui.overlays.HUD_TEXT_SCRIM_ALPHA
import com.hletrd.findx9tele.ui.overlays.LevelOverlay
import com.hletrd.findx9tele.ui.overlays.RecordingIndicator
import com.hletrd.findx9tele.ui.overlays.StatusBar
import com.hletrd.findx9tele.ui.overlays.TimerCountdown
import com.hletrd.findx9tele.ui.overlays.WaveformOverlay
import com.hletrd.findx9tele.ui.review.GalleryThumb
import com.hletrd.findx9tele.ui.review.MediaReviewOverlay
import com.hletrd.findx9tele.ui.theme.CameraColors
import com.hletrd.findx9tele.ui.theme.FindX9TeleTheme
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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
                punchIn = state.punchIn,
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

            if (state.frameLines != FrameLineType.OFF) {
                FrameLinesOverlay(type = state.frameLines, modifier = Modifier.fillMaxSize())
            }

            if (state.aspectRatio != AspectRatio.W4_3) {
                AspectMask(ratio = state.aspectRatio, modifier = Modifier.fillMaxSize())
            }

            if (state.tapPoint != null) {
                FocusReticle(point = state.tapPoint, indication = state.afIndication, modifier = Modifier.fillMaxSize())
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
                            .border(1.dp, Color.White.copy(alpha = 0.85f))
                            .semantics {
                                contentDescription = "Loupe overview"
                                stateDescription = "Non-interactive"
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
                // The panel's corner is a CONTINUOUS-CURVATURE squircle; a circular arc at the same
                // nominal radius reads visibly tighter than the glass (user-compared on device), so
                // scale up ~20% to visually match the physical curve.
                ((corner?.radius ?: 0) * 1.2f).toInt()
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

        if (state.level) {
            LevelOverlay(
                modifier = Modifier.fillMaxSize(),
                rollDegrees = state.levelRoll,
                deviceOrientation = state.deviceOrientation,
            )
        }

        Row(
            // NOT rotated: it's a wide top row, so a 90° spin about its center swings it off
            // screen. It stays fixed (readable in portrait); only the compact scopes counter-rotate.
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
                modifier = Modifier.weight(1f, fill = false),
            )
            // Battery/shots-remaining lives in the chrome row, not floating inside the image: on
            // the 4:3 layout the old in-preview TopEnd anchor left it hovering over the frame
            // (user-reported as visual clutter).
            if (detailsVisible) StatusInfoPill(state = state)
        }

        // One measured top-center lane owns every transient/held readout. Its first slot keeps the
        // focus states below the shooting OSD even when the zoom readout is hidden, while expanding
        // to the zoom's actual rotated/font-scaled height whenever it is visible.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
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
                        state.caps?.equivalentFocalMm,
                        frontFacing = state.facing == CameraFacing.FRONT,
                    )
                    ZoomIndicator(
                        zoom = state.controls.zoomRatio * mul,
                        range = state.caps?.zoomRatioRange?.let {
                            val hi = if (state.teleconverterMode) {
                                minOf(it.upper * mul, com.hletrd.findx9tele.camera.TELE_MAX_DISPLAY_ZOOM)
                            } else {
                                it.upper * mul
                            }
                            android.util.Range(minOf(it.lower * mul, hi), hi)
                        },
                        numberRotation = overlayRotation,
                    )
                }
            }

            if (showHalfPressLabel(state.halfPressActive, state.halfPressAction, state.tapFocusHeld)) {
                Text(
                    text = state.halfPressAction.label,
                    color = CameraColors.ManualActive,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .rotateLayout(overlayRotation)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = HUD_TEXT_SCRIM_ALPHA))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
            if (state.tapFocusHeld) {
                TapFocusHoldChip(
                    onReset = currentActions.value::onResetFocusPoint,
                    modifier = Modifier.rotateLayout(overlayRotation),
                )
            }
        }

        // Scopes/readouts stack in the top-end column. top = 100dp clears the OSD status row (which ends
        // ~90dp) — QA hit an overlap at 72dp. Each scope counter-rotates to stay horizontal as the phone
        // turns (rotateLayout reserves the ROTATED bounding box, so a 90° hold no longer makes the
        // histogram and waveform collide — the earlier plain rotate() did, which is why they were left
        // fixed before).
        // Keep camera controls spatially stable across portrait/landscape holds. Only compact labels
        // counter-rotate; the shutter/mode/Fn cluster stays anchored like a camera body control layout.
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(end = 12.dp, top = 100.dp),
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
                        contentDescription = "Self timer"
                        stateDescription = "${state.timerCountdownSec} seconds remaining"
                        liveRegion = LiveRegionMode.Assertive
                        role = Role.Button
                        onClick {
                            currentActions.value.onCapturePhoto()
                            true
                        }
                    }
                    .clickable(
                        onClickLabel = "Cancel self timer",
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
                    .background(Color.Black.copy(alpha = HUD_TEXT_SCRIM_ALPHA), RoundedCornerShape(8.dp))
                    .semantics {
                        liveRegion = if (message.isUrgentStatus()) {
                            LiveRegionMode.Assertive
                        } else {
                            LiveRegionMode.Polite
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
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
                        .padding(horizontal = if (landscapeOperator) 12.dp else 28.dp),
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
                    .padding(top = 12.dp, bottom = 8.dp)
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

    if (sheetVisible) {
        ProSheet(
            state = state,
            actions = actions,
            initialTab = sheetInitialTab,
            onTabChange = { sheetInitialTab = it },
            onDismiss = { sheetVisible = false },
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

internal fun String.isUrgentStatus(): Boolean =
    // "could not": the delete-failure statuses ("Could not delete media") matched no keyword and
    // rendered as polite toasts — found while pinning this classifier (TEST4-14).
    listOf("error", "fail", "unable", "unavailable", "denied", "insufficient", "could not")
        .any { contains(it, ignoreCase = true) }

/** Keeps successful acknowledgements quiet while leaving actionable failures readable. */
internal fun statusDisplayDurationMs(message: String?): Long? = when {
    message == null -> null
    message.isUrgentStatus() -> 6_000L
    listOf("saved", "deleted", "loaded").any { token -> message.contains(token, ignoreCase = true) } ->
        1_500L
    else -> 2_500L
}

internal data class RotatedLayoutBounds(val widthPx: Int, val heightPx: Int)

/** Exact axis-aligned bounds for a [widthPx] by [heightPx] rectangle rotated around its centre. */
internal fun rotatedLayoutBounds(widthPx: Int, heightPx: Int, degrees: Float): RotatedLayoutBounds {
    require(widthPx >= 0 && heightPx >= 0)
    if (!degrees.isFinite()) return RotatedLayoutBounds(widthPx, heightPx)

    val normalized = ((degrees.toDouble() % 360.0) + 360.0) % 360.0
    val radians = Math.toRadians(normalized)
    fun snapCardinal(value: Double): Double = when {
        value < 1e-7 -> 0.0
        1.0 - value < 1e-7 -> 1.0
        else -> value
    }
    val cosine = snapCardinal(abs(cos(radians)))
    val sine = snapCardinal(abs(sin(radians)))

    fun layoutCeil(value: Double): Int = when {
        value <= 0.0 -> 0
        value >= Int.MAX_VALUE.toDouble() -> Int.MAX_VALUE
        else -> ceil(value).toInt()
    }

    return RotatedLayoutBounds(
        widthPx = layoutCeil(widthPx * cosine + heightPx * sine),
        heightPx = layoutCeil(widthPx * sine + heightPx * cosine),
    )
}

internal fun constrainedRotatedLayoutBounds(
    widthPx: Int,
    heightPx: Int,
    degrees: Float,
    constraints: Constraints,
): RotatedLayoutBounds {
    val bounds = rotatedLayoutBounds(widthPx, heightPx, degrees)
    return RotatedLayoutBounds(
        widthPx = constraints.constrainWidth(bounds.widthPx),
        heightPx = constraints.constrainHeight(bounds.heightPx),
    )
}

internal fun showHalfPressLabel(
    active: Boolean,
    action: HardwareKeyAction,
    tapFocusHeld: Boolean,
): Boolean = active && !(action == HardwareKeyAction.AF_ON && tapFocusHeld)

/** Rotates content while reserving its exact animated axis-aligned bounds in layout. */
private fun Modifier.rotateLayout(degrees: Float): Modifier = this
    // This clip must wrap the custom layout. Putting it after layout() would clip against the
    // unrotated child's bounds instead of the constraint-valid rotated slot.
    .clipToBounds()
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
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
            if (state.mode == CaptureMode.PHOTO && (!compact || state.controls.flash != FlashMode.OFF)) {
                FlashButton(
                    mode = state.controls.flash,
                    onClick = { actions.onFlash(nextAvailable(state.controls.flash, availability.flashModes)) },
                    enabled = !recordingLocked && availability.flashModes.size > 1,
                    modifier = glyphSpin,
                )
            }
            if (state.mode == CaptureMode.PHOTO && (!compact || state.timer != ShutterTimer.OFF)) {
                TimerButton(
                    timer = state.timer,
                    onClick = { actions.onTimer(nextTimer(state.timer)) },
                    enabled = !recordingLocked,
                    modifier = glyphSpin,
                )
            }
            if (state.mode == CaptureMode.PHOTO && (!compact || state.aspectRatio != AspectRatio.W4_3)) {
                AspectButton(
                    ratio = state.aspectRatio,
                    onClick = { actions.onAspectRatio(nextAspect(state.aspectRatio)) },
                    enabled = !recordingLocked,
                    modifier = glyphSpin,
                )
            }
            if (!compact) {
                GridButton(
                    active = state.grid != GridType.NONE,
                    onClick = { actions.onGridType(if (state.grid == GridType.NONE) GridType.THIRDS else GridType.NONE) },
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
            FlipCameraButton(
                onClick = actions::onToggleFrontCamera,
                enabled = !recordingLocked,
                modifier = Modifier.rotate(glyphRotation),
            )
            DispButton(active = compact, onClick = onToggleDisp, modifier = Modifier.rotate(glyphRotation))
            GearButton(onClick = onOpenSheet, modifier = Modifier.rotate(glyphRotation))
        }
    }
}

/** Persistent, directly dismissible feedback for the tap-owned AF/AE point after its reticle fades. */
@Composable
private fun TapFocusHoldChip(onReset: () -> Unit, modifier: Modifier = Modifier) {
    val activate = onReset
    Box(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = HUD_TEXT_SCRIM_ALPHA))
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
            .clickable(role = Role.Button, onClickLabel = "Reset focus point", onClick = onReset)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "AF HOLD ×",
            color = CameraColors.Accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Ghost circular translucent chrome button shared by every top-bar icon. The tappable area is a
 * 48 dp touch target (Material / WCAG 2.2 minimum) while the visible scrim stays a compact 36 dp, so
 * one-handed / gloved use on this 3168 px panel mis-taps far less without bloating the chrome.
 *
 * The scrim rides [HUD_TEXT_SCRIM_ALPHA] — the same tested contrast floor 05486cb applied to the OSD
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
                .size(36.dp)
                .clip(CircleShape)
                .background(CameraColors.ChromeScrim.copy(alpha = HUD_TEXT_SCRIM_ALPHA)),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

@Composable
private fun FlashButton(mode: FlashMode, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val color = when (mode) {
        FlashMode.OFF -> CameraColors.TextSecondary
        FlashMode.TORCH -> CameraColors.Accent
        else -> CameraColors.TextPrimary
    }.copy(alpha = if (enabled) 1f else 0.38f)
    ChromeIconButton(onClick = onClick, contentDescription = "Flash ${flashModeLabel(mode)}", modifier = modifier, enabled = enabled) {
        Canvas(Modifier.size(16.dp)) {
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
                drawPath(bolt, color = color, style = Stroke(width = 1.4.dp.toPx()))
            }
            if (mode == FlashMode.OFF) {
                drawLine(color, Offset(0f, size.height * 0.06f), Offset(size.width, size.height * 0.94f), strokeWidth = 1.4.dp.toPx())
            }
        }
        if (mode == FlashMode.AUTO) {
            Text(
                text = "A",
                color = color,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 3.dp, bottom = 2.dp),
            )
        }
    }
}

@Composable
private fun TimerButton(timer: ShutterTimer, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    ChromeIconButton(onClick = onClick, contentDescription = "Self timer ${shutterTimerLabel(timer)}", modifier = modifier, enabled = enabled) {
        if (timer == ShutterTimer.OFF) {
            Canvas(Modifier.size(16.dp)) {
                val color = CameraColors.TextSecondary.copy(alpha = if (enabled) 1f else 0.38f)
                drawCircle(color, radius = size.minDimension / 2f, style = Stroke(width = 1.3.dp.toPx()))
                drawLine(color, center, Offset(center.x, center.y - size.height * 0.3f), strokeWidth = 1.2.dp.toPx())
                drawLine(color, center, Offset(center.x + size.width * 0.18f, center.y), strokeWidth = 1.2.dp.toPx())
            }
        } else {
            Text(timer.seconds.toString(), color = CameraColors.Accent.copy(alpha = if (enabled) 1f else 0.38f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AspectButton(ratio: AspectRatio, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    ChromeIconButton(onClick = onClick, contentDescription = "Aspect ratio ${aspectRatioLabel(ratio)}", modifier = modifier, enabled = enabled) {
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
private fun GridButton(active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = if (active) CameraColors.TextPrimary else CameraColors.TextSecondary
    ChromeIconButton(onClick = onClick, contentDescription = if (active) "Grid on" else "Grid off", modifier = modifier) {
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
        else -> CameraColors.ChromeScrim.copy(alpha = teleChipIdleScrimAlpha())
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
            Text("TELE", color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Test seam pinning TELE's idle plate to the app-wide, bright-frame contrast floor. */
internal fun teleChipIdleScrimAlpha(): Float = HUD_TEXT_SCRIM_ALPHA

/** Standard camera-flip glyph: two half-circle arrows chasing each other (front/rear switch). */
@Composable
private fun FlipCameraButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    ChromeIconButton(onClick = onClick, contentDescription = "Switch camera", modifier = modifier, enabled = enabled) {
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
            // Three rails at 1/4, 1/2, 3/4 height; knobs sit at varying x to imply adjustable levels.
            val rows = listOf(0.25f to 0.66f, 0.5f to 0.34f, 0.75f to 0.6f)
            val left = size.width * 0.12f
            val right = size.width * 0.88f
            rows.forEach { (yf, knobXf) ->
                val y = size.height * yf
                drawLine(color, Offset(left, y), Offset(right, y), strokeWidth = railStroke)
                val knobX = left + (right - left) * knobXf
                drawCircle(color = CameraColors.ChromeScrim.copy(alpha = 0.45f), radius = knobRadius * 1.4f, center = Offset(knobX, y))
                drawCircle(color, radius = knobRadius, center = Offset(knobX, y), style = Stroke(width = 1.4.dp.toPx()))
            }
        }
    }
}

/** Sony DISP toggle: viewfinder-frame glyph whose info lines drop out when the display is clean. */
@Composable
private fun DispButton(active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ChromeIconButton(
        onClick = onClick,
        contentDescription = if (active) "Show shooting info" else "Hide shooting info",
        modifier = modifier,
    ) {
        Canvas(Modifier.size(16.dp)) {
            val color = if (active) CameraColors.Accent else CameraColors.TextPrimary
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
                val bps = com.hletrd.findx9tele.camera.videoBitRate(
                    state.videoResolution.width, state.videoResolution.height,
                    state.videoFrameRate.encoderRate,
                    com.hletrd.findx9tele.camera.effectiveBpp(state.bitrateLevel, state.videoCodec), state.videoCodec,
                ).toLong() + 192_000L // + AAC
                val min = (state.freeBytes * 8L / bps.coerceAtLeast(1L)) / 60L
                when {
                    min >= 600 -> "9h+"
                    min >= 60 -> "${min / 60}h${min % 60}m"
                    else -> "${min}min"
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
            .background(Color.Black.copy(alpha = HUD_TEXT_SCRIM_ALPHA), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
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
        // (iPhone-style). The bar below stays horizontal — a generic level indicator reads fine at any
        // angle, and rotating it would collide with the surrounding chrome.
        Text(
            text = formatZoomMultiplier(zoom),
            color = CameraColors.Accent,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .rotateLayout(numberRotation)
                .background(Color.Black.copy(alpha = HUD_TEXT_SCRIM_ALPHA), RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
        Box(
            modifier = Modifier
                .width(180.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
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

internal const val FN_OVERLAY_COLUMN_COUNT = 4
internal const val FN_OVERLAY_HELD_COLUMN_COUNT = 2
internal const val FN_OVERLAY_MAX_SLOTS = 8
internal const val FN_OVERLAY_HELD_WIDTH_DP = 148
internal const val FN_OVERLAY_SCRIM_ALPHA = 0.22f

internal enum class FnOverlayAnchor { BOTTOM_CENTER, CENTER_START, CENTER_END }

/**
 * Raw-window edge for the Fn entry affordance. The activity stays portrait-locked, so a clockwise
 * hold (270 degrees) moves the physical bottom edge to raw end; portrait and a counter-clockwise
 * hold keep it at raw start. The entry and its opened tray therefore stay under the same thumb.
 */
internal enum class FnEntryAnchor { START, END }

internal data class FnOverlayLayoutPolicy(
    val rawColumnCount: Int,
    val anchor: FnOverlayAnchor,
)

internal enum class FnTileContentAxis {
    PORTRAIT,
    HELD_LANDSCAPE_LABEL_FIRST_RAW,
    HELD_LANDSCAPE_VALUE_FIRST_RAW,
}

/** Keep the shooting Fn menu mode-specific; My Menu and Recent remain in the settings sheet. */
internal fun fnOverlaySlots(mode: CaptureMode, activeSlots: List<FnSlot>): List<FnSlot> =
    activeSlots
        .distinct()
        .take(FN_OVERLAY_MAX_SLOTS)
        .ifEmpty { if (mode == CaptureMode.VIDEO) FnSlot.VIDEO_DEFAULT else FnSlot.PHOTO_DEFAULT }

internal fun fnOverlayLayoutPolicy(deviceOrientation: Int): FnOverlayLayoutPolicy =
    when (((deviceOrientation % 360) + 360) % 360) {
        90 -> FnOverlayLayoutPolicy(FN_OVERLAY_HELD_COLUMN_COUNT, FnOverlayAnchor.CENTER_START)
        270 -> FnOverlayLayoutPolicy(FN_OVERLAY_HELD_COLUMN_COUNT, FnOverlayAnchor.CENTER_END)
        else -> FnOverlayLayoutPolicy(FN_OVERLAY_COLUMN_COUNT, FnOverlayAnchor.BOTTOM_CENTER)
    }

internal fun fnEntryAnchor(deviceOrientation: Int): FnEntryAnchor =
    if (((deviceOrientation % 360) + 360) % 360 == 270) {
        FnEntryAnchor.END
    } else {
        FnEntryAnchor.START
    }

/**
 * Raw portrait-locked cells that become a physical 4x2 tray when the handset is held sideways.
 * Null cells preserve the intended physical row for mode-specific lists shorter than eight slots.
 */
internal fun fnOverlayGridRows(slots: List<FnSlot>, deviceOrientation: Int): List<List<FnSlot?>> {
    val visible = slots.take(FN_OVERLAY_MAX_SLOTS)
    return when (((deviceOrientation % 360) + 360) % 360) {
        90 -> MutableList<FnSlot?>(FN_OVERLAY_MAX_SLOTS) { null }.also { raw ->
            visible.forEachIndexed { index, slot ->
                val physicalRow = index / FN_OVERLAY_COLUMN_COUNT
                val physicalColumn = index % FN_OVERLAY_COLUMN_COUNT
                raw[physicalColumn * FN_OVERLAY_HELD_COLUMN_COUNT + (1 - physicalRow)] = slot
            }
        }.chunked(FN_OVERLAY_HELD_COLUMN_COUNT)
        270 -> MutableList<FnSlot?>(FN_OVERLAY_MAX_SLOTS) { null }.also { raw ->
            visible.forEachIndexed { index, slot ->
                val physicalRow = index / FN_OVERLAY_COLUMN_COUNT
                val physicalColumn = index % FN_OVERLAY_COLUMN_COUNT
                raw[(FN_OVERLAY_COLUMN_COUNT - 1 - physicalColumn) * FN_OVERLAY_HELD_COLUMN_COUNT + physicalRow] = slot
            }
        }.chunked(FN_OVERLAY_HELD_COLUMN_COUNT)
        else -> visible.chunked(FN_OVERLAY_COLUMN_COUNT).map { row ->
            row.map<FnSlot, FnSlot?> { it } + List(FN_OVERLAY_COLUMN_COUNT - row.size) { null }
        }
    }
}

internal fun fnTileContentAxis(deviceOrientation: Int): FnTileContentAxis =
    when (((deviceOrientation % 360) + 360) % 360) {
        // Raw X becomes perceived Y in the portrait-locked landscape hold. The ordering reverses
        // between quarter turns, so swap the raw children at 90° to keep label-above-value upright.
        90 -> FnTileContentAxis.HELD_LANDSCAPE_VALUE_FIRST_RAW
        270 -> FnTileContentAxis.HELD_LANDSCAPE_LABEL_FIRST_RAW
        else -> FnTileContentAxis.PORTRAIT
    }

/** Short visual copy for the narrow physical strip; accessibility keeps the complete slot label. */
internal fun fnOverlayVisualLabel(slot: FnSlot, heldLandscape: Boolean): String = when {
    !heldLandscape -> fnSlotLabel(slot)
    slot == FnSlot.STABILIZATION -> "Steady"
    slot == FnSlot.OPEN_GATE -> "Gate"
    else -> fnSlotLabel(slot)
}

/** Short visual values for held-landscape tiles; accessibility keeps the complete value. */
internal fun fnOverlayVisualValue(slot: FnSlot, value: String, heldLandscape: Boolean): String {
    if (!heldLandscape) return value
    return when (slot) {
        FnSlot.SHUTTER -> value.removePrefix("A ")
        FnSlot.ISO -> value.replaceFirst("A ", "A")
        FnSlot.WB -> when (value) {
            "Daylight" -> "Day"
            "Tungsten" -> "Tung."
            else -> value
        }
        FnSlot.STABILIZATION -> if (value == "Standard") "Std" else value
        FnSlot.DRIVE -> if (value == "Timelapse") "TL" else value
        FnSlot.AUDIO_SCENE -> when (value) {
            "Standard" -> "Std"
            "Sound Focus" -> "Focus"
            "Sound Stage" -> "Stage"
            else -> value
        }
        FnSlot.TELECONVERTER -> value.replace(" mm", "mm")
        else -> value
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
    val layoutPolicy = fnOverlayLayoutPolicy(state.deviceOrientation)
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
        val panelPlacement = when (layoutPolicy.anchor) {
            FnOverlayAnchor.BOTTOM_CENTER -> Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 14.dp)
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
                    .background(Color(0xFF181818))
                    .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
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
                        fontWeight = FontWeight.Bold,
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
                        modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
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
            .heightIn(min = 58.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = if (enabled) 0.09f else 0.04f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
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
            .padding(horizontal = 9.dp, vertical = 7.dp),
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
        fontWeight = FontWeight.Bold,
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
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = HUD_TEXT_SCRIM_ALPHA))
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, color = CameraColors.TextPrimary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        Canvas(modifier = Modifier.width(22.dp).height(if (compact) 96.dp else 150.dp)) {
            val cx = size.width / 2f
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

internal fun shouldShowExposureMeter(
    mode: ExposureMode,
    transient: Boolean,
): Boolean = mode == ExposureMode.MANUAL || transient

// Pure (plain enum + IntArray) and internal so the MANUAL-mode spot meter's three guard branches
// and clamp are unit-testable — a wrong needle here misleads every manual exposure decision.
internal fun manualMeterEv(mode: ExposureMode, luma: IntArray?): Float? {
    if (mode != ExposureMode.MANUAL) return null
    if (luma == null) return null
    var total = 0L
    luma.forEach { total += it }
    if (total == 0L) return null
    val mean = AutoExposure.meanLuma(luma).coerceAtLeast(0.001f)
    return log2(mean / AutoExposure.TARGET_LUMA).coerceIn(-3f, 3f)
}

private fun log2(value: Float): Float = (ln(value.toDouble()) / ln(2.0)).toFloat()

// ---------------------------------------------------------------------------
// Bottom cluster: mode carousel + shutter row (the manual dial cluster lives in ManualDials.kt).
// ---------------------------------------------------------------------------

/**
 * Top y of the letterboxed preview box. Unconditional vertical CENTERING left the 4:3 preview's
 * bottom edge cutting through the focal rail / Fn row — the bottom cluster is bottom-anchored, so
 * chrome straddled the image boundary and read as clipped. Instead, bias the preview UP just far
 * enough that the rest-state bottom cluster starts at (or below) the preview's bottom edge:
 *  - never above [topChromeMinPx] (the status bar + top icon row + OSD strip must stay clear),
 *  - never below the centered position (the preview may only move UP from center, so 16:9 — which
 *    can never clear the cluster — keeps its centered placement and the cluster overlays it fully
 *    INSIDE the image, same as before),
 *  - degenerate (preview taller than the space) falls back to the centered position.
 */
internal fun previewTopPx(
    availableHeightPx: Int,
    previewHeightPx: Int,
    topChromeMinPx: Int,
    bottomReservePx: Int,
): Int {
    val centerTop = (availableHeightPx - previewHeightPx) / 2
    val clearingTop = availableHeightPx - bottomReservePx - previewHeightPx
    if (centerTop <= topChromeMinPx) return centerTop
    return min(centerTop, max(topChromeMinPx, clearingTop))
}

internal data class FocalRailState(
    val selected: Boolean,
    val enabled: Boolean,
    val stateDescription: String,
    val accessibilityRole: Role,
)

internal fun focalRailState(
    choice: LensChoice,
    selectedLens: LensChoice,
    teleconverter: Boolean,
    cameraReady: Boolean,
    recording: Boolean,
): FocalRailState {
    val selected = choice == selectedLens
    val enabled = cameraReady && !recording
    val description = when {
        recording -> "Unavailable while recording"
        !cameraReady -> "Camera reconfiguring"
        selected && teleconverter && choice == LensChoice.TELE3X -> "Selected; teleconverter on"
        selected -> "Selected"
        else -> "Not selected"
    }
    // These presets are one mutually exclusive value, not pages of content. RadioButton lets
    // TalkBack announce that relationship truthfully; Android exports the active preset through
    // AccessibilityNodeInfo.isChecked rather than mislabelling each focal length as a tab.
    return FocalRailState(selected, enabled, description, Role.RadioButton)
}

/** Direct iPhone/Sony-familiar focal presets; TELE remains a separate, labeled converter action. */
@Composable
private fun FocalRail(
    state: CameraUiState,
    onLens: (LensChoice) -> Unit,
    modifier: Modifier = Modifier,
    glyphRotation: Float = 0f,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.Center) {
        Row(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LensChoice.entries.forEach { choice ->
                val presentation = focalRailState(
                    choice = choice,
                    selectedLens = state.lens,
                    teleconverter = state.teleconverterMode,
                    cameraReady = state.cameraReady,
                    recording = state.isRecording,
                )
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .rotate(glyphRotation)
                        .focusable()
                        // Selection and activation must live on the same outer node. A separate
                        // selected semantic followed by clickable exported selected=false from the
                        // actionable AccessibilityNodeInfo on PMA110.
                        .clearAndSetSemantics {
                            contentDescription = "${choice.label} lens"
                            stateDescription = presentation.stateDescription
                            role = presentation.accessibilityRole
                            selected = presentation.selected
                            if (!presentation.enabled) disabled()
                            onClick {
                                if (!presentation.enabled) return@onClick false
                                onLens(choice)
                                true
                            }
                        }
                        .selectable(
                            selected = presentation.selected,
                            enabled = presentation.enabled,
                            role = presentation.accessibilityRole,
                            onClick = { onLens(choice) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(
                                if (presentation.selected) CameraColors.TextPrimary
                                else Color.Black.copy(alpha = HUD_TEXT_SCRIM_ALPHA),
                            )
                            .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = choice.label,
                            color = if (presentation.selected) Color.Black else CameraColors.TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = if (presentation.selected) FontWeight.Bold else FontWeight.SemiBold,
                            modifier = Modifier.alpha(if (presentation.enabled) 1f else 0.38f),
                        )
                    }
                }
            }
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
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
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

internal data class ModeCarouselState(
    val selected: Boolean,
    val enabled: Boolean,
    val stateDescription: String,
    val accessibilityRole: Role,
)

internal fun modeCarouselState(active: Boolean, enabled: Boolean): ModeCarouselState =
    ModeCarouselState(
        selected = active,
        enabled = enabled,
        stateDescription = if (active) "Selected" else "Not selected",
        accessibilityRole = Role.RadioButton,
    )

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
                .background(Color.Black.copy(alpha = HUD_TEXT_SCRIM_ALPHA))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = text,
                color = when {
                    !enabled -> CameraColors.TextSecondary.copy(alpha = 0.45f)
                    active -> CameraColors.TextPrimary
                    else -> CameraColors.TextSecondary
                },
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                fontSize = if (active) 15.sp else 14.sp,
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
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (mode == CaptureMode.VIDEO && isRecording && !isRecordingStarting) {
                SnapshotButton(onClick = onSnapshot, enabled = stillCaptureAvailable)
            }
            ShutterButton(
                mode = mode,
                isRecording = isRecording,
                timerCountdownSec = timerCountdownSec,
                onClick = onShutter,
                cameraHealthy = cameraHealthy,
                enabled = shutterEnabled,
            )
        }
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
            // viewfinder. Still tappable: the decline path surfaces its own status message.
            .alpha(if (cameraHealthy) 1f else 0.35f)
            .focusable()
            .clearAndSetSemantics {
                contentDescription = when {
                    timerCountdownSec > 0 -> "Cancel self timer"
                    mode == CaptureMode.PHOTO -> "Take photo"
                    isRecording -> "Stop recording"
                    else -> "Start recording"
                }
                role = Role.Button
                stateDescription = when {
                    timerCountdownSec > 0 -> "$timerCountdownSec seconds remaining"
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
                onClickLabel = if (timerCountdownSec > 0) "Cancel self timer" else null,
                onClick = activate,
            ),
    ) {
        drawCircle(color = Color.White, radius = size.minDimension / 2f, style = Stroke(width = 4.dp.toPx()))
        when {
            mode == CaptureMode.PHOTO -> drawCircle(color = Color.White, radius = size.minDimension * 0.38f)
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
            drawCircle(color = Color.White, radius = size.minDimension / 2f, style = Stroke(width = 2.dp.toPx()))
            drawCircle(color = Color.White, radius = size.minDimension * 0.32f)
        }
    }
}

/** No-op [CameraActions] used only by previews and the debug-only snapshot Activity. */
internal object PreviewCameraActions : CameraActions {
    override fun onPreviewSurfaceAvailable(surface: Surface, width: Int, height: Int) = Unit
    override fun onReviewOpenChange(open: Boolean, uri: android.net.Uri): Boolean = false
    override fun onCameraInputBlockedChange(blocked: Boolean) = Unit
    override fun onStandbyAudioMeterVisibilityChanged(visible: Boolean) = Unit
    override fun onPreviewSurfaceChanged(width: Int, height: Int) = Unit
    override fun onPreviewSurfaceDestroyed() = Unit

    override fun onFocusMode(mode: FocusMode) = Unit
    override fun onFocusSlider(slider: Float) = Unit
    override fun onAfLock(locked: Boolean) = Unit
    override fun onTapFocus(nx: Float, ny: Float) = Unit
    override fun onResetFocusPoint() = Unit

    override fun onIso(iso: Int) = Unit
    override fun onShutterNs(ns: Long) = Unit
    override fun onExposureCompensation(ev: Int) = Unit
    override fun onExposureMode(mode: com.hletrd.findx9tele.camera.ExposureMode) = Unit
    override fun onToggleAutoExposure(auto: Boolean) = Unit
    override fun onToggleAeLock(locked: Boolean) = Unit
    override fun onAntibanding(mode: Antibanding) = Unit
    override fun onShutterMode(mode: ShutterMode) = Unit
    override fun onShutterAngle(angle: Float) = Unit
    override fun onExposureStep(step: com.hletrd.findx9tele.camera.ExposureStep) = Unit

    override fun onWbMode(mode: WbMode) = Unit
    override fun onWbKelvin(kelvin: Int) = Unit
    override fun onWbTint(tint: Int) = Unit
    override fun onToggleAwbLock(locked: Boolean) = Unit
    override fun onMeteringMode(mode: MeteringMode) = Unit

    override fun onEdge(level: ProcessingLevel) = Unit
    override fun onNoiseReduction(level: ProcessingLevel) = Unit
    override fun onColorEffect(effect: ColorEffect) = Unit

    override fun onFlash(mode: FlashMode) = Unit
    override fun onToggleOis(enabled: Boolean) = Unit
    override fun onZoomRatio(ratio: Float) = Unit
    override fun onPinchZoom(factor: Float) = Unit
    override fun onJpegQuality(quality: Int) = Unit

    override fun onModeChange(mode: CaptureMode) = Unit
    override fun onTransfer(transfer: ColorTransfer) = Unit
    override fun onSetPhotoFormats(formats: PhotoFormats) = Unit
    override fun onToggleHiResStill(enabled: Boolean) = Unit
    override fun onAspectRatio(ratio: AspectRatio) = Unit
    override fun onToggleRecordAudio(enabled: Boolean) = Unit
    override fun onAudioGain(gain: Float) = Unit
    override fun onAudioScene(scene: com.hletrd.findx9tele.camera.AudioScene) = Unit
    override fun onAudioInputPreference(preference: com.hletrd.findx9tele.camera.AudioInputPreference) = Unit
    override fun onToggleTeleconverter(enabled: Boolean) = Unit
    override fun onVideoCodec(codec: VideoCodec) = Unit
    override fun onBitrateLevel(level: BitrateLevel) = Unit
    override fun onVideoResolution(size: android.util.Size) = Unit
    override fun onVideoFrameRate(rate: com.hletrd.findx9tele.camera.VideoFrameRate) = Unit
    override fun onToggleOpenGate(enabled: Boolean) = Unit

    override fun onVideoStabMode(mode: com.hletrd.findx9tele.camera.VideoStabMode) = Unit

    override fun onTogglePeaking(enabled: Boolean) = Unit
    override fun onPeakingLevel(level: com.hletrd.findx9tele.camera.PeakingLevel) = Unit
    override fun onPeakingColor(color: com.hletrd.findx9tele.camera.PeakingColor) = Unit
    override fun onToggleZebra(enabled: Boolean) = Unit
    override fun onZebraLevel(level: com.hletrd.findx9tele.camera.ZebraLevel) = Unit
    override fun onToggleFalseColor(enabled: Boolean) = Unit
    override fun onToggleHistogram(enabled: Boolean) = Unit
    override fun onToggleWaveform(enabled: Boolean) = Unit
    override fun onToggleGammaAssist(enabled: Boolean) = Unit
    override fun onFrameLines(type: FrameLineType) = Unit
    override fun onAfSpotSize(size: com.hletrd.findx9tele.camera.AfSpotSize) = Unit
    override fun onCaptureCustomWb() = Unit
    override fun onGridType(type: GridType) = Unit
    override fun onToggleLevel(enabled: Boolean) = Unit
    override fun onTogglePunchIn(enabled: Boolean) = Unit
    override fun onToggleTeleFinder(enabled: Boolean) = Unit

    override fun onTimer(timer: ShutterTimer) = Unit
    override fun onDriveMode(mode: DriveMode) = Unit
    override fun onIntervalSec(sec: Int) = Unit

    override fun onCapturePhoto() = Unit
    override fun onToggleRecording() = Unit
    override fun onHardwareHalfPress(active: Boolean) = Unit

    override fun onLens(choice: com.hletrd.findx9tele.camera.LensChoice) = Unit
    override fun onToggleFrontCamera() = Unit
    override fun onCameraOverride(id: String?) = Unit
    override fun onToggleRememberSettings(enabled: Boolean) = Unit
    override fun onTogglePreserveLensSelection(enabled: Boolean) = Unit
    override fun onTogglePreserveTeleconverter(enabled: Boolean) = Unit
    override fun onSetPhotoFnSlots(slots: List<com.hletrd.findx9tele.camera.FnSlot>) = Unit
    override fun onSetVideoFnSlots(slots: List<com.hletrd.findx9tele.camera.FnSlot>) = Unit
    override fun onSetMyMenuSlots(slots: List<com.hletrd.findx9tele.camera.FnSlot>) = Unit
    override fun onStoreMemorySlot(slot: com.hletrd.findx9tele.camera.MemorySlot) = Unit
    override fun onRecallMemorySlot(slot: com.hletrd.findx9tele.camera.MemorySlot) = Unit
    override fun onVolumeKeyAction(action: com.hletrd.findx9tele.camera.HardwareKeyAction) = Unit
    override fun onHalfPressAction(action: com.hletrd.findx9tele.camera.HardwareKeyAction) = Unit
    override fun onDeleteLastMedia(uri: android.net.Uri) = Unit
}

@Preview(showBackground = true)
@Composable
private fun CameraScreenPreview() {
    FindX9TeleTheme {
        CameraScreen(state = CameraUiState(), actions = PreviewCameraActions)
    }
}

/**
 * Shortest-path angle unwrap for the glyph counter-rotation animation: accumulates an UNWRAPPED
 * target so the spring always takes the <=180-degree way around (a 350->10 transition moves +20,
 * not -340). Pure and internal because the rotation sign and wrap cases have regressed before.
 */
internal fun shortestRotationTarget(current: Float, desiredDegrees: Float): Float {
    var delta = (desiredDegrees - current) % 360f
    if (delta > 180f) delta -= 360f
    if (delta < -180f) delta += 360f
    return current + delta
}
