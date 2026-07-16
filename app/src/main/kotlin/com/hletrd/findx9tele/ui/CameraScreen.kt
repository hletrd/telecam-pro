package com.hletrd.findx9tele.ui

import android.graphics.SurfaceTexture
import android.view.HapticFeedbackConstants
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import com.hletrd.findx9tele.camera.Antibanding
import com.hletrd.findx9tele.camera.AspectRatio
import com.hletrd.findx9tele.camera.AutoExposure
import com.hletrd.findx9tele.camera.BitrateLevel
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
import com.hletrd.findx9tele.camera.LensChoice
import com.hletrd.findx9tele.camera.MeteringMode
import com.hletrd.findx9tele.camera.MemorySlot
import com.hletrd.findx9tele.camera.PhotoFormats
import com.hletrd.findx9tele.camera.ProcessingLevel
import com.hletrd.findx9tele.camera.ShutterMode
import com.hletrd.findx9tele.camera.ShutterTimer
import com.hletrd.findx9tele.camera.TELECONVERTER_MAGNIFICATION
import com.hletrd.findx9tele.camera.VideoCodec
import com.hletrd.findx9tele.camera.WbMode
import com.hletrd.findx9tele.ui.controls.ManualDialCluster
import com.hletrd.findx9tele.ui.controls.ProSheet
import com.hletrd.findx9tele.ui.controls.ProSheetTab
import com.hletrd.findx9tele.ui.controls.aspectRatioLabel
import com.hletrd.findx9tele.ui.controls.exposureMeterCompensationEv
import com.hletrd.findx9tele.ui.controls.nextAspect
import com.hletrd.findx9tele.ui.controls.nextFlashMode
import com.hletrd.findx9tele.ui.controls.nextTimer
import com.hletrd.findx9tele.ui.controls.flashModeLabel
import com.hletrd.findx9tele.ui.controls.fnSlotLabel
import com.hletrd.findx9tele.ui.controls.fnSlotValue
import com.hletrd.findx9tele.ui.controls.performQuickFn
import com.hletrd.findx9tele.ui.controls.shutterTimerLabel
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
import kotlin.math.ln

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
    // Sony DISP: one tap declutters the viewfinder (hides OSD/meter/scopes/MR; keeps REC + framing).
    var dispClean by remember { mutableStateOf(false) }
    // In-app review overlay (last saved still, pinch-to-zoom for focus check). Open/closed lives in
    // CameraUiState (state.reviewOpen) so MainActivity's hardware-key handlers can refuse to fire
    // the shutter under the overlay. The reviewed uri is FROZEN here at open time so a timer/
    // timelapse capture completing mid-review can't swap the image being inspected.
    var reviewUri by remember { mutableStateOf<android.net.Uri?>(null) }
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

    fun openSheet(tab: ProSheetTab) {
        currentActions.value.onCameraInputBlockedChange(true)
        sheetInitialTab = tab
        sheetVisible = true
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

    // Live zoom readout: show a bar + "N.N×" whenever the zoom ratio moves (pinch or in-sheet slider),
    // then fade it out ~1.4 s after the last change (iPhone-style). Never shown at rest.
    var zoomVisible by remember { mutableStateOf(false) }
    // snapshotFlow + collectLatest instead of keying the effect on the raw ratio: keying restarted
    // (cancel + relaunch) a coroutine PER zoom tick — touch-sample rate during a pinch, ~30 Hz on a
    // hardware-slide glide — pure dispatcher churn on the busiest gesture. One long-lived collector
    // now watches the value; collectLatest restarts only the fade delay.
    val zoomRatioState = rememberUpdatedState(state.controls.zoomRatio)
    LaunchedEffect(Unit) {
        snapshotFlow { zoomRatioState.value }.collectLatest { ratio ->
            if (ratio != 1f) {
                zoomVisible = true
                delay(1400)
                zoomVisible = false
            } else {
                zoomVisible = false
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CameraColors.Background)
            .then(if (modalVisible) Modifier.clearAndSetSemantics { } else Modifier),
    ) {
        // The viewfinder is LETTERBOXED, not cover-cropped: the TextureView (plus every overlay that
        // must align with the image frame) lives in a centered box sized to the displayed preview
        // aspect, so the FULL capture field is always visible. Letterboxing at the Compose layer —
        // instead of scaling down inside GL — keeps three things correct for free: the GL surface is
        // exactly content-aspect so FlipRenderer's "cover" is a 1:1 fit (no crop), tap coordinates
        // normalize directly to the visible frame, and the AE/scope luma readback never sees black
        // bars (they exist only outside this box).
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .aspectRatio(state.previewAspect.coerceAtLeast(0.01f)),
        ) {
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
                    .pointerInput(Unit) {
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
                                    currentActions.value.onTapFocus(down.position.x / w, down.position.y / h)
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
        }

        // Emphasized REC display (Sony FX): a thin red frame while rolling — unmissable, even in
        // DISP-clean mode. Screen-fixed (not content-boxed) so it stays unmissable at every aspect.
        if (state.isRecording) {
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

        if (!dispClean) StatusBar(
            state = state,
            // NOT rotated: it's a wide top-left row, so a 90° spin about its center swings it off
            // screen. It stays fixed (readable in portrait); only the compact scopes counter-rotate.
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 12.dp, top = 60.dp),
        )

        if (state.halfPressActive) {
            Text(
                text = state.halfPressAction.label,
                color = CameraColors.ManualActive,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 106.dp)
                    // Compact short label → counter-rotates like the other glyphs ("AF-ON" reads
                    // upright in a landscape hold).
                    .rotate(overlayRotation)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
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
            if (!dispClean) {
                StatusInfoPill(state = state, modifier = Modifier.rotateLayout(overlayRotation))
            }
            if (state.isRecording) {
                RecordingIndicator(elapsedMs = state.recordElapsedMs, modifier = Modifier.rotateLayout(overlayRotation))
            }
            // Sony-style standby metering: input levels are visible while video is ARMED,
            // not just while rolling (the engine runs a levels-only mic tap in standby).
            if (state.mode == CaptureMode.VIDEO && state.recordAudio) {
                AudioMeter(level = state.audioLevel, modifier = Modifier.rotateLayout(overlayRotation))
            }
            if (!dispClean && state.histogram) {
                HistogramOverlay(data = state.histogramData, modifier = Modifier.rotateLayout(overlayRotation))
            }
            if (!dispClean && state.waveform) {
                WaveformOverlay(data = state.waveformData, modifier = Modifier.rotateLayout(overlayRotation))
            }
        }

        if (state.timerCountdownSec > 0) {
            // The 120 sp digit is the largest orientation-sensitive glyph on screen — a sideways
            // "6" reads ambiguously in a landscape self-timer, so it counter-rotates too.
            TimerCountdown(
                seconds = state.timerCountdownSec,
                modifier = Modifier.fillMaxSize(),
                rotationDegrees = overlayRotation,
            )
        }

        state.statusMessage?.let { message ->
            // Centered transient toast ("Saved" / "Video saved" / errors). Previously pinned near the
            // top, where it collided with the OSD status row (300mm / codec / etc.) — QA-reported.
            Text(
                text = message,
                color = CameraColors.TextPrimary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
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
            dispClean = dispClean,
            onToggleDisp = { dispClean = !dispClean },
            glyphRotation = overlayRotation,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 8.dp),
        )

        // Live zoom readout, centered under the TopBar; fades in on pinch/slider change. Moved off the
        // bottom cluster (it overlapped the MR1/MR2/MR3 strip) — a top-center HUD reads clearly and
        // stays clear of the manual dials + shutter row.
        AnimatedVisibility(
            visible = zoomVisible,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(300)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 64.dp),
        ) {
            // Display MAIN-relative magnification (the stock-app style: 3× at the 3× tele's native,
            // 10× at the 10×, 13× with the TC). baseMul = opened lens equiv / main equiv (70/23 ≈ 3.04
            // for the 3× tele); the afocal converter multiplies on top (300/70). So tele-native shows
            // ~3.0× (TC off) / 13–60× (TC on). TELE uses the CONSTANT display scale so the pill,
            // the snaps (30×/60×), and the ceiling all land on the same round numbers — the
            // caps-measured equiv (69.4 mm) would read 59.5× at the spec'd 60× ceiling.
            val mul = if (state.teleconverterMode) {
                com.hletrd.findx9tele.camera.TELE_DISPLAY_BASE
            } else {
                (state.caps?.equivalentFocalMm ?: 70f) / LensChoice.MAIN.targetEquivMm
            }
            ZoomIndicator(
                zoom = state.controls.zoomRatio * mul,
                range = state.caps?.zoomRatioRange?.let {
                    val hi = if (state.teleconverterMode) {
                        minOf(it.upper * mul, com.hletrd.findx9tele.camera.TELE_MAX_DISPLAY_ZOOM)
                    } else {
                        it.upper * mul
                    }
                    android.util.Range(it.lower * mul, hi)
                },
                numberRotation = overlayRotation,
            )
        }

        // Exposure meter: pinned to the LEFT edge as a vertical scale (the scopes own the right).
        // A fixed home beats the old jump between top/bottom as the dial opened (feedback).
        if (!dispClean) ExposureMeter(
            state = state,
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

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.6f),
                    ),
                )
                .navigationBarsPadding()
                .padding(top = 28.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!dispClean) MemoryRecallStrip(
                state = state,
                actions = actions,
                glyphRotation = overlayRotation,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(horizontal = 16.dp),
            )

            ManualDialCluster(
                state = state,
                actions = actions,
                onRequestWhiteBalanceSheet = { openSheet(ProSheetTab.EXPOSURE) },
                onOpenFnMenu = {
                    currentActions.value.onCameraInputBlockedChange(true)
                    fnOverlayVisible = true
                },
                modifier = Modifier.padding(horizontal = 12.dp),
            )

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
                teleconverterOn = state.teleconverterMode,
                lastMediaUri = state.lastMediaUri,
                onOpenReview = {
                    reviewUri = state.lastMediaUri
                    currentActions.value.onReviewOpenChange(true)
                },
                onShutter = onShutter,
                onSnapshot = actions::onCapturePhoto,
                onToggleTeleconverter = { actions.onToggleTeleconverter(!state.teleconverterMode) },
                teleconverterEnabled = !state.isRecording,
                cameraHealthy = state.primaryShutterHealthy,
                shutterEnabled = state.primaryShutterEnabled,
                stillCaptureAvailable = state.stillCaptureReady,
                glyphRotation = overlayRotation,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
            )
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
            onDismiss = { fnOverlayVisible = false },
        )
    }

    val frozenReviewUri = reviewUri
    if (state.reviewOpen && frozenReviewUri != null) {
        MediaReviewOverlay(
            uri = frozenReviewUri,
            onClose = {
                actions.onReviewOpenChange(false)
                reviewUri = null
            },
            onDelete = {
                actions.onDeleteLastMedia(frozenReviewUri)
                actions.onReviewOpenChange(false)
                reviewUri = null
            },
        )
    }
}

private fun String.isUrgentStatus(): Boolean =
    listOf("error", "fail", "unable", "unavailable", "denied", "insufficient")
        .any { contains(it, ignoreCase = true) }

/**
 * Rotates content by [degrees] AND reserves the ROTATED bounding box in layout — unlike Modifier.rotate,
 * a pure draw transform that keeps the original (unrotated) slot, which made rotated wide scopes overlap
 * their neighbours. For 90°/270° holds it swaps width/height so a stack of rotated scopes lays out
 * without collisions; the content is rotated via the placement layer.
 */
private fun Modifier.rotateLayout(degrees: Float): Modifier = this.layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val swap = swapsDimensions(degrees)
    val w = if (swap) placeable.height else placeable.width
    val h = if (swap) placeable.width else placeable.height
    layout(w, h) {
        placeable.placeRelativeWithLayer(
            x = (w - placeable.width) / 2,
            y = (h - placeable.height) / 2,
        ) { rotationZ = degrees }
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
    dispClean: Boolean,
    onToggleDisp: () -> Unit,
    modifier: Modifier = Modifier,
    glyphRotation: Float = 0f,
) {
    val recordingLocked = state.isRecording
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // Compact circular glyphs counter-rotate to stay upright as the phone turns (iPhone-style);
            // the TELE chip is wide text, so it stays fixed to avoid poking out of its slot.
            val glyphSpin = Modifier.rotate(glyphRotation)
            FlashButton(
                mode = state.controls.flash,
                onClick = { actions.onFlash(nextFlashMode(state.controls.flash)) },
                enabled = !recordingLocked,
                modifier = glyphSpin,
            )
            TimerButton(
                timer = state.timer,
                onClick = { actions.onTimer(nextTimer(state.timer)) },
                enabled = !recordingLocked,
                modifier = glyphSpin,
            )
            AspectButton(
                ratio = state.aspectRatio,
                onClick = { actions.onAspectRatio(nextAspect(state.aspectRatio)) },
                enabled = !recordingLocked,
                modifier = glyphSpin,
            )
            GridButton(
                active = state.grid != GridType.NONE,
                onClick = { actions.onGridType(if (state.grid == GridType.NONE) GridType.THIRDS else GridType.NONE) },
                modifier = glyphSpin,
            )
            TeleChip(
                active = state.teleconverterMode,
                enabled = !recordingLocked,
                onClick = { actions.onToggleTeleconverter(!state.teleconverterMode) },
            )
        }
        // Counter-rotate the settings glyph so it stays upright as the phone turns (iPhone-style).
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DispButton(active = dispClean, onClick = onToggleDisp, modifier = Modifier.rotate(glyphRotation))
            GearButton(onClick = onOpenSheet, modifier = Modifier.rotate(glyphRotation))
        }
    }
}

/**
 * Ghost circular translucent chrome button shared by every top-bar icon. The tappable area is a
 * 48 dp touch target (Material / WCAG 2.2 minimum) while the visible scrim stays a compact 36 dp, so
 * one-handed / gloved use on this 3168 px panel mis-taps far less without bloating the chrome.
 */
@Composable
private fun ChromeIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(CameraColors.ChromeScrim.copy(alpha = if (enabled) 0.45f else 0.22f)),
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
    val bg = when {
        active && enabled -> CameraColors.TextPrimary
        active -> CameraColors.TextPrimary.copy(alpha = 0.38f)
        else -> CameraColors.ChromeScrim.copy(alpha = if (enabled) 0.45f else 0.22f)
    }
    val fg = when {
        active -> Color.Black.copy(alpha = if (enabled) 1f else 0.55f)
        else -> CameraColors.TextPrimary.copy(alpha = if (enabled) 1f else 0.38f)
    }
    // Outer box carries the click + semantics at the 48 dp minimum touch target (every sibling
    // top-bar control already gets 48 dp); the 36 dp pill stays the VISUAL, so the layout look is
    // unchanged while the hit area stops being the row's one undersized outlier.
    Box(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .semantics {
                contentDescription = "Teleconverter"
                stateDescription = if (active) "On" else "Off"
                role = Role.Button
            }
            .clickable(enabled = enabled, onClick = onClick),
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
    val remaining: String? = when {
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
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
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
 * advertised range. Shown transiently while zooming (pinch or the in-sheet slider), like the stock
 * camera. Screen-fixed (not counter-rotated) — a compact centered HUD reads fine in any hold.
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
            text = "%.1f×".format(zoom),
            color = CameraColors.Accent,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .rotate(numberRotation)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MemoryRecallStrip(
    state: CameraUiState,
    actions: CameraActions,
    glyphRotation: Float,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.36f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MemorySlot.entries.forEach { slot ->
            val saved = slot in state.savedMemorySlots
            val active = state.activeMemorySlot == slot
            val enabled = !state.isRecording
            val name = state.memorySlotNames[slot] ?: slot.label
            val summary = state.memorySlotSummaries[slot].orEmpty()
            val bg = when {
                active -> Color(0xFFFFD60A)
                saved -> Color.White.copy(alpha = 0.14f)
                else -> Color.Transparent
            }
            val fg = when {
                active -> Color.Black
                saved && enabled -> CameraColors.TextPrimary
                saved -> CameraColors.TextPrimary.copy(alpha = 0.38f)
                else -> CameraColors.TextSecondary.copy(alpha = if (enabled) 1f else 0.38f)
            }
            // TeleChip-style hit-area split: the OUTER box carries the 48 dp minimum touch target,
            // the click and the semantics (with a labeled long-press action for TalkBack); the
            // compact visual pill keeps its original size inside — one-handed MR taps on a braced
            // 300 mm rig mis-hit far less without bloating the strip.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .sizeIn(minWidth = 40.dp, minHeight = 48.dp)
                    .semantics {
                        contentDescription = if (saved) {
                            "${slot.label} $name $summary"
                        } else {
                            "${slot.label} empty"
                        }
                        role = Role.Button
                    }
                    .combinedClickable(
                        enabled = enabled,
                        onClick = {
                            if (saved) actions.onRecallMemorySlot(slot) else actions.onStoreMemorySlot(slot)
                        },
                        onLongClickLabel = "Save current setup to ${slot.label}",
                        onLongClick = { actions.onStoreMemorySlot(slot) },
                    ),
            ) {
                Text(
                    text = slot.label,
                    color = fg,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .rotate(glyphRotation)
                        .clip(RoundedCornerShape(50))
                        .background(bg)
                        .padding(horizontal = 11.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun FnOverlay(
    state: CameraUiState,
    actions: CameraActions,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val slots = remember(state.activeFnSlots, state.myMenuSlots, state.recentSettingSlots) {
        (state.activeFnSlots + state.myMenuSlots + state.recentSettingSlots)
            .distinct()
            .take(12)
            .ifEmpty { FnSlot.DEFAULT }
    }
    val closeFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { closeFocusRequester.requestFocus() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.56f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = "Close Fn menu"
                    role = Role.Button
                }
                .clickable(onClick = onDismiss),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 14.dp)
                .padding(bottom = 154.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xF0181818))
                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
                .semantics {
                    paneTitle = "Function menu"
                    isTraversalGroup = true
                }
                // Consume blank-panel taps without exposing a nameless dummy Button.
                .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Fn", color = CameraColors.TextPrimary, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Box(
                    modifier = Modifier
                        .focusRequester(closeFocusRequester)
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .clip(RoundedCornerShape(50))
                        .semantics {
                            contentDescription = "Close function menu"
                            role = Role.Button
                        }
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Close",
                        color = CameraColors.TextSecondary,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
            slots.chunked(3).forEach { rowSlots ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowSlots.forEach { slot ->
                        FnOverlayTile(
                            slot = slot,
                            value = fnSlotValue(slot, state),
                            enabled = quickFnEnabled(slot, state),
                            onClick = {
                                performQuickFn(slot, state, actions)
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(3 - rowSlots.size) {
                        Spacer(modifier = Modifier.weight(1f))
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
) {
    Column(
        modifier = modifier
            .height(58.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = if (enabled) 0.09f else 0.04f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            .semantics {
                contentDescription = fnSlotLabel(slot)
                stateDescription = value
                role = Role.Button
            }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            fnSlotLabel(slot),
            color = CameraColors.TextSecondary.copy(alpha = if (enabled) 1f else 0.55f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
        Text(
            value,
            color = CameraColors.TextPrimary.copy(alpha = if (enabled) 1f else 0.55f),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

private fun quickFnEnabled(slot: FnSlot, state: CameraUiState): Boolean = when (slot) {
    FnSlot.TRANSFER -> !state.isRecording && state.videoCodec == VideoCodec.HEVC
    FnSlot.TELECONVERTER -> !state.isRecording
    FnSlot.OPEN_GATE -> state.mode == CaptureMode.VIDEO && !state.isRecording
    else -> true
}

@Composable
private fun ExposureMeter(state: CameraUiState, modifier: Modifier = Modifier) {
    // The shared helper returns the final signed stop amount. Do not multiply by the raw Camera2
    // compensation index again: that double-scaled positive values and reversed negative signs.
    val compensationEv = exposureMeterCompensationEv(state)
    val manualEv = manualMeterEv(state.controls.exposureMode, state.histogramData?.luma)
    val indicatorEv = if (state.controls.exposureMode == ExposureMode.MANUAL) manualEv else compensationEv
    val label = when {
        state.controls.exposureMode == ExposureMode.MANUAL && manualEv != null -> "M %+.1f".format(manualEv)
        state.controls.exposureMode == ExposureMode.MANUAL -> "M --"
        else -> "%+.1f".format(compensationEv)
    }
    // Vertical Sony-style scale: +3 EV at the top, -3 EV at the bottom, readout above it.
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.48f))
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, color = CameraColors.TextPrimary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        Canvas(modifier = Modifier.width(22.dp).height(150.dp)) {
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

@Composable
private fun ModeCarousel(
    mode: CaptureMode,
    onModeChange: (CaptureMode) -> Unit,
    modifier: Modifier = Modifier,
    glyphRotation: Float = 0f,
    enabled: Boolean = true,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            // The mode labels are SHORT ("Photo"/"Video"), so — iPhone-style — they DO counter-rotate
            // to stay upright as the phone turns (unlike the wide dial pills, which would overflow their
            // fixed row slots and are kept screen-fixed). The label + its underline rotate as one unit.
            ModeLabel(
                text = "Photo",
                active = mode == CaptureMode.PHOTO,
                enabled = enabled,
                onClick = { onModeChange(CaptureMode.PHOTO) },
                modifier = Modifier.rotate(glyphRotation),
            )
            ModeLabel(
                text = "Video",
                active = mode == CaptureMode.VIDEO,
                enabled = enabled,
                onClick = { onModeChange(CaptureMode.VIDEO) },
                modifier = Modifier.rotate(glyphRotation),
            )
        }
    }
}

@Composable
private fun ModeLabel(text: String, active: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                // The one HUD text element that had no scrim of its own: over a bright subject (sky,
                // snow, water — normal super-tele fare) the mid-gray inactive label fell under usable
                // contrast. Same treatment as every sibling HUD element.
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = HUD_TEXT_SCRIM_ALPHA))
                .padding(horizontal = 6.dp, vertical = 2.dp),
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
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .width(18.dp)
                    .height(2.dp)
                    .background(
                        if (active && enabled) CameraColors.TextPrimary else Color.Transparent,
                        RoundedCornerShape(1.dp),
                    ),
            )
        }
    }
}

/** Gallery thumbnail placeholder / shutter (photo|video) / teleconverter "flip" button row. */
@Composable
private fun ShutterRow(
    mode: CaptureMode,
    isRecording: Boolean,
    teleconverterOn: Boolean,
    lastMediaUri: android.net.Uri?,
    onOpenReview: () -> Unit,
    onShutter: () -> Unit,
    onSnapshot: () -> Unit,
    onToggleTeleconverter: () -> Unit,
    modifier: Modifier = Modifier,
    teleconverterEnabled: Boolean = true,
    glyphRotation: Float = 0f,
    cameraHealthy: Boolean = true,
    shutterEnabled: Boolean = true,
    stillCaptureAvailable: Boolean = true,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        // Counter-rotate the review thumbnail so its image reads upright as the phone turns.
        GalleryThumb(uri = lastMediaUri, onClick = onOpenReview, modifier = Modifier.rotate(glyphRotation))
        Spacer(modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (mode == CaptureMode.VIDEO && isRecording) {
                SnapshotButton(onClick = onSnapshot, enabled = stillCaptureAvailable)
            }
            ShutterButton(
                mode = mode,
                isRecording = isRecording,
                onClick = onShutter,
                cameraHealthy = cameraHealthy,
                enabled = shutterEnabled,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        LensFlipButton(active = teleconverterOn, enabled = teleconverterEnabled, onClick = onToggleTeleconverter)
    }
}

/** Large circular shutter: white ring; PHOTO = solid white; VIDEO idle = red dot; recording = red square. */
@Composable
private fun ShutterButton(
    mode: CaptureMode,
    isRecording: Boolean,
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
    Canvas(
        modifier = modifier
            .size(76.dp)
            .scale(shutterScale)
            // Camera down (opening, reconfiguring, or recovery exhausted): the tap would be
            // declined anyway — dim the button so it stops LOOKING ready in front of a black
            // viewfinder. Still tappable: the decline path surfaces its own status message.
            .alpha(if (cameraHealthy) 1f else 0.35f)
            .semantics {
                contentDescription = when {
                    mode == CaptureMode.PHOTO -> "Take photo"
                    isRecording -> "Stop recording"
                    else -> "Start recording"
                }
                role = Role.Button
                if (!enabled) disabled()
            }
            .clickable(enabled = enabled, interactionSource = interaction, indication = null) {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                onClick()
            },
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
    Box(
        modifier = modifier
            .size(48.dp)
            .alpha(if (enabled) 1f else 0.35f)
            .semantics {
                contentDescription = "Take photo while recording"
                role = Role.Button
            }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(36.dp)) {
            drawCircle(color = Color.White, radius = size.minDimension / 2f, style = Stroke(width = 2.dp.toPx()))
            drawCircle(color = Color.White, radius = size.minDimension * 0.32f)
        }
    }
}

/**
 * Teleconverter toggle, drawn as a lens glyph. The engine describes engaging the teleconverter as
 * an "afocal 180° flip" (see [com.hletrd.findx9tele.camera.CameraUiState.teleconverterMode]), so
 * this doubles as the pro camera's lens/teleconverter state slot —
 * this app has a single fixed tele lens, so there is nothing to flip to; the teleconverter toggle
 * is the closest in-contract analogue and is duplicated here (also reachable via the top-bar TELE
 * chip and the pro sheet's Stabilization tab) for quick access next to the shutter.
 */
@Composable
private fun LensFlipButton(active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val alpha = if (enabled) 1f else 0.38f
    val ringColor = if (active) CameraColors.Accent.copy(alpha = alpha) else Color.White.copy(alpha = 0.35f * alpha)
    val glyphColor = if (active) CameraColors.Accent.copy(alpha = alpha) else CameraColors.TextPrimary.copy(alpha = alpha)
    Box(
        modifier = modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(CameraColors.Pill)
            .border(1.5.dp, ringColor, CircleShape)
            .semantics {
                contentDescription = "Teleconverter"
                stateDescription = if (active) "On" else "Off"
                role = Role.Button
            }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(26.dp)) {
            drawCircle(glyphColor, radius = size.minDimension * 0.46f, style = Stroke(width = 1.4.dp.toPx()))
            drawCircle(glyphColor, radius = size.minDimension * 0.26f, style = Stroke(width = 1.2.dp.toPx()))
            drawCircle(glyphColor, radius = size.minDimension * 0.08f)
        }
    }
}

/** No-op [CameraActions] used only by [CameraScreenPreview]. */
private object PreviewCameraActions : CameraActions {
    override fun onPreviewSurfaceAvailable(surface: Surface, width: Int, height: Int) = Unit
    override fun onReviewOpenChange(open: Boolean) = Unit
    override fun onCameraInputBlockedChange(blocked: Boolean) = Unit
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

    override fun onTimer(timer: ShutterTimer) = Unit
    override fun onDriveMode(mode: DriveMode) = Unit
    override fun onIntervalSec(sec: Int) = Unit

    override fun onCapturePhoto() = Unit
    override fun onToggleRecording() = Unit
    override fun onHardwareHalfPress(active: Boolean) = Unit

    override fun onLens(choice: com.hletrd.findx9tele.camera.LensChoice) = Unit
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
 * not -340). Pure and internal — this sits in the documented already-shipped-wrong-once rotation
 * sign zone, so the quadrant boundaries are pinned by unit tests.
 */
internal fun shortestRotationTarget(current: Float, desiredDegrees: Float): Float {
    var delta = (desiredDegrees - current) % 360f
    if (delta > 180f) delta -= 360f
    if (delta < -180f) delta += 360f
    return current + delta
}

/**
 * Whether a rotateLayout at [degrees] swaps the reserved width/height (a ~90-degree/~270-degree
 * hold). Boundary-INCLUSIVE at 45/135/225/315 — pinned by tests so a range "cleanup" can't silently
 * un-swap a 90-degree hold.
 */
internal fun swapsDimensions(degrees: Float): Boolean {
    val norm = ((degrees % 360f) + 360f) % 360f
    return norm in 45f..135f || norm in 225f..315f
}
