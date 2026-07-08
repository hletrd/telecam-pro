package com.hletrd.findx9tele.ui

import android.graphics.SurfaceTexture
import android.view.HapticFeedbackConstants
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import com.hletrd.findx9tele.camera.Antibanding
import com.hletrd.findx9tele.camera.AspectRatio
import com.hletrd.findx9tele.camera.BitrateLevel
import com.hletrd.findx9tele.camera.CameraUiState
import com.hletrd.findx9tele.camera.CaptureMode
import com.hletrd.findx9tele.camera.ColorEffect
import com.hletrd.findx9tele.camera.ColorTransfer
import com.hletrd.findx9tele.camera.DriveMode
import com.hletrd.findx9tele.camera.FlashMode
import com.hletrd.findx9tele.camera.FocusMode
import com.hletrd.findx9tele.camera.GridType
import com.hletrd.findx9tele.camera.MeteringMode
import com.hletrd.findx9tele.camera.PhotoFormats
import com.hletrd.findx9tele.camera.ProcessingLevel
import com.hletrd.findx9tele.camera.ShutterMode
import com.hletrd.findx9tele.camera.ShutterTimer
import com.hletrd.findx9tele.camera.VideoCodec
import com.hletrd.findx9tele.camera.VideoStabMode
import com.hletrd.findx9tele.camera.WbMode
import com.hletrd.findx9tele.camera.effectiveExposureNs
import com.hletrd.findx9tele.ui.controls.ManualDialCluster
import com.hletrd.findx9tele.ui.controls.ProSheet
import com.hletrd.findx9tele.ui.controls.ProSheetTab
import com.hletrd.findx9tele.ui.controls.aspectRatioLabel
import com.hletrd.findx9tele.ui.controls.flashModeLabel
import com.hletrd.findx9tele.ui.controls.shutterTimerLabel
import com.hletrd.findx9tele.ui.overlays.AspectMask
import com.hletrd.findx9tele.ui.overlays.AudioMeter
import com.hletrd.findx9tele.ui.overlays.FocusReticle
import com.hletrd.findx9tele.ui.overlays.GridOverlay
import com.hletrd.findx9tele.ui.overlays.HistogramOverlay
import com.hletrd.findx9tele.ui.overlays.LevelOverlay
import com.hletrd.findx9tele.ui.overlays.RecordingIndicator
import com.hletrd.findx9tele.ui.overlays.StatusBar
import com.hletrd.findx9tele.ui.overlays.TimerCountdown
import com.hletrd.findx9tele.ui.overlays.WaveformOverlay
import com.hletrd.findx9tele.ui.review.GalleryThumb
import com.hletrd.findx9tele.ui.review.MediaReviewOverlay
import com.hletrd.findx9tele.ui.theme.CameraColors
import com.hletrd.findx9tele.ui.theme.FindX9TeleTheme
import kotlin.math.roundToInt

/**
 * Root camera UI, styled after the Google Pixel Camera app: a near-empty viewfinder at rest, a
 * thin row of quick toggles up top, and a bottom cluster of manual "Fn" dials + mode switch +
 * shutter. Everything else lives one tap away in [ProSheet], a Sony-menu-style tabbed settings
 * system. Stateless: everything shown comes from [state], every interaction is forwarded to
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
    // In-app review overlay (last saved still, pinch-to-zoom for focus check).
    var reviewOpen by remember { mutableStateOf(false) }
    // Remembers the last-viewed settings tab so the gear reopens where the user left off.
    var sheetInitialTab by remember { mutableStateOf(ProSheetTab.SHOOTING) }
    val currentActions = rememberUpdatedState(actions)

    fun openSheet(tab: ProSheetTab) {
        sheetInitialTab = tab
        sheetVisible = true
    }

    // Counter-rotates the on-screen glyphs/labels so they stay upright as the phone turns, even though
    // the activity is portrait-locked (like Pixel/Sony). The counter-rotation is +deviceOrientation:
    // GyroEis derives the discrete value from gravity via atan2(x,y), which yields dev=90 for a
    // COUNTER-clockwise (left) landscape and dev=270 for a clockwise (right) landscape — the opposite
    // of the naive assumption. So the glyph must rotate by +dev to undo the phone's turn (a −dev sign
    // left both landscapes 180° off — invisible on symmetric icons, obvious once text rotates).
    // Accumulate an UNWRAPPED target so the animation always takes the shortest ≤90° path.
    var overlayRotationTarget by remember { mutableFloatStateOf(state.deviceOrientation.toFloat()) }
    LaunchedEffect(state.deviceOrientation) {
        val desired = state.deviceOrientation.toFloat()
        var delta = (desired - overlayRotationTarget) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        overlayRotationTarget += delta
    }
    val overlayRotation by animateFloatAsState(targetValue = overlayRotationTarget, label = "overlayRotation")

    // Live zoom readout: show a bar + "N.N×" whenever the zoom ratio moves (pinch or in-sheet slider),
    // then fade it out ~1.4 s after the last change (iPhone-style). Never shown at rest.
    var zoomVisible by remember { mutableStateOf(false) }
    LaunchedEffect(state.controls.zoomRatio) {
        if (state.controls.zoomRatio != 1f) {
            zoomVisible = true
            delay(1400)
            zoomVisible = false
        } else {
            zoomVisible = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CameraColors.Background),
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
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

        GridOverlay(type = state.grid, modifier = Modifier.fillMaxSize())

        if (state.aspectRatio != AspectRatio.W4_3) {
            AspectMask(ratio = state.aspectRatio, modifier = Modifier.fillMaxSize())
        }

        if (state.level) {
            LevelOverlay(
                modifier = Modifier.fillMaxSize(),
                rollDegrees = state.levelRoll,
                deviceOrientation = state.deviceOrientation,
            )
        }

        if (state.tapPoint != null) {
            FocusReticle(point = state.tapPoint, modifier = Modifier.fillMaxSize())
        }

        StatusBar(
            state = state,
            // NOT rotated: it's a wide top-left row, so a 90° spin about its center swings it off
            // screen. It stays fixed (readable in portrait); only the compact scopes counter-rotate.
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 12.dp, top = 60.dp),
        )

        teleSafetyMessage(state)?.let { warning ->
            Text(
                text = warning,
                color = Color.Black,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 12.dp, top = 98.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFFFD60A).copy(alpha = 0.95f))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }

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
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(end = 12.dp, top = 100.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.isRecording) {
                RecordingIndicator(elapsedMs = state.recordElapsedMs, modifier = Modifier.rotateLayout(overlayRotation))
            }
            if (state.isRecording && state.recordAudio) {
                AudioMeter(level = state.audioLevel, modifier = Modifier.rotateLayout(overlayRotation))
            }
            if (state.histogram) {
                HistogramOverlay(data = state.histogramData, modifier = Modifier.rotateLayout(overlayRotation))
            }
            if (state.waveform) {
                WaveformOverlay(data = state.waveformData, modifier = Modifier.rotateLayout(overlayRotation))
            }
        }

        if (state.timerCountdownSec > 0) {
            TimerCountdown(seconds = state.timerCountdownSec, modifier = Modifier.fillMaxSize())
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
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        TopBar(
            state = state,
            actions = actions,
            onOpenSheet = { sheetVisible = true }, // reopen to the remembered last tab
            glyphRotation = overlayRotation,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 8.dp),
        )

        // Live zoom bar, centered above the bottom cluster; fades in on pinch/slider change.
        AnimatedVisibility(
            visible = zoomVisible,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(300)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 220.dp),
        ) {
            ZoomIndicator(zoom = state.controls.zoomRatio, range = state.caps?.zoomRatioRange, numberRotation = overlayRotation)
        }

        ExposureMeter(
            state = state,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 274.dp),
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
            ManualDialCluster(
                state = state,
                actions = actions,
                onRequestWhiteBalanceSheet = { openSheet(ProSheetTab.EXPOSURE) },
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            ModeCarousel(
                mode = state.mode,
                onModeChange = actions::onModeChange,
                glyphRotation = overlayRotation,
                modifier = Modifier.fillMaxWidth(),
            )

            ShutterRow(
                mode = state.mode,
                isRecording = state.isRecording,
                teleconverterOn = state.teleconverterMode,
                lastMediaUri = state.lastMediaUri,
                onOpenReview = { reviewOpen = true },
                onShutter = onShutter,
                onSnapshot = actions::onCapturePhoto,
                onToggleTeleconverter = { actions.onToggleTeleconverter(!state.teleconverterMode) },
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

    val reviewUri = state.lastMediaUri
    if (reviewOpen && reviewUri != null) {
        MediaReviewOverlay(
            uri = reviewUri,
            onClose = { reviewOpen = false },
            onDelete = {
                actions.onDeleteLastMedia()
                reviewOpen = false
            },
        )
    }
}

/**
 * Rotates content by [degrees] AND reserves the ROTATED bounding box in layout — unlike Modifier.rotate,
 * a pure draw transform that keeps the original (unrotated) slot, which made rotated wide scopes overlap
 * their neighbours. For 90°/270° holds it swaps width/height so a stack of rotated scopes lays out
 * without collisions; the content is rotated via the placement layer.
 */
private fun Modifier.rotateLayout(degrees: Float): Modifier = this.layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val norm = ((degrees % 360f) + 360f) % 360f
    val swap = norm in 45f..135f || norm in 225f..315f
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
    modifier: Modifier = Modifier,
    glyphRotation: Float = 0f,
) {
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
            FlashButton(mode = state.controls.flash, onClick = { actions.onFlash(nextFlashMode(state.controls.flash)) }, modifier = glyphSpin)
            TimerButton(timer = state.timer, onClick = { actions.onTimer(nextTimer(state.timer)) }, modifier = glyphSpin)
            AspectButton(ratio = state.aspectRatio, onClick = { actions.onAspectRatio(nextAspect(state.aspectRatio)) }, modifier = glyphSpin)
            GridButton(
                active = state.grid != GridType.NONE,
                onClick = { actions.onGridType(if (state.grid == GridType.NONE) GridType.THIRDS else GridType.NONE) },
                modifier = glyphSpin,
            )
            TeleChip(active = state.teleconverterMode, onClick = { actions.onToggleTeleconverter(!state.teleconverterMode) })
        }
        // Counter-rotate the settings glyph so it stays upright as the phone turns (iPhone-style).
        GearButton(onClick = onOpenSheet, modifier = Modifier.rotate(glyphRotation))
    }
}

private fun nextFlashMode(mode: FlashMode): FlashMode = when (mode) {
    FlashMode.OFF -> FlashMode.AUTO
    FlashMode.AUTO -> FlashMode.ON
    FlashMode.ON -> FlashMode.TORCH
    FlashMode.TORCH -> FlashMode.OFF
}

private fun nextTimer(timer: ShutterTimer): ShutterTimer = when (timer) {
    ShutterTimer.OFF -> ShutterTimer.SEC3
    ShutterTimer.SEC3 -> ShutterTimer.SEC10
    ShutterTimer.SEC10 -> ShutterTimer.OFF
}

private fun nextAspect(ratio: AspectRatio): AspectRatio = when (ratio) {
    AspectRatio.W4_3 -> AspectRatio.W16_9
    AspectRatio.W16_9 -> AspectRatio.W4_3
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
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(CameraColors.ChromeScrim.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

@Composable
private fun FlashButton(mode: FlashMode, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = when (mode) {
        FlashMode.OFF -> CameraColors.TextSecondary
        FlashMode.TORCH -> CameraColors.Accent
        else -> CameraColors.TextPrimary
    }
    ChromeIconButton(onClick = onClick, contentDescription = "Flash ${flashModeLabel(mode)}", modifier = modifier) {
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
private fun TimerButton(timer: ShutterTimer, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ChromeIconButton(onClick = onClick, contentDescription = "Self timer ${shutterTimerLabel(timer)}", modifier = modifier) {
        if (timer == ShutterTimer.OFF) {
            Canvas(Modifier.size(16.dp)) {
                val color = CameraColors.TextSecondary
                drawCircle(color, radius = size.minDimension / 2f, style = Stroke(width = 1.3.dp.toPx()))
                drawLine(color, center, Offset(center.x, center.y - size.height * 0.3f), strokeWidth = 1.2.dp.toPx())
                drawLine(color, center, Offset(center.x + size.width * 0.18f, center.y), strokeWidth = 1.2.dp.toPx())
            }
        } else {
            Text(timer.seconds.toString(), color = CameraColors.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AspectButton(ratio: AspectRatio, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ChromeIconButton(onClick = onClick, contentDescription = "Aspect ratio ${aspectRatioLabel(ratio)}", modifier = modifier) {
        Canvas(Modifier.size(18.dp)) {
            val color = CameraColors.TextPrimary
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
private fun TeleChip(active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bg = if (active) CameraColors.TextPrimary else CameraColors.ChromeScrim.copy(alpha = 0.45f)
    val fg = if (active) Color.Black else CameraColors.TextPrimary
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .semantics {
                contentDescription = "Teleconverter"
                stateDescription = if (active) "On" else "Off"
                role = Role.Button
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("TELE", color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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

@Composable
private fun ExposureMeter(state: CameraUiState, modifier: Modifier = Modifier) {
    val caps = state.caps
    val evStep = caps?.evStep?.let {
        if (it.denominator == 0) 1f / 3f else it.numerator.toFloat() / it.denominator.toFloat()
    } ?: (1f / 3f)
    val ev = (state.controls.exposureCompensation * evStep).coerceIn(-3f, 3f)
    val label = if (state.controls.exposureMode == com.hletrd.findx9tele.camera.ExposureMode.MANUAL) "M" else "%+.1f".format(ev)
    Row(
        modifier = modifier
            .width(208.dp)
            .height(34.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.48f))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, color = CameraColors.TextPrimary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        Canvas(modifier = Modifier.weight(1f).height(22.dp)) {
            val cy = size.height / 2f
            val start = 0f
            val end = size.width
            drawLine(Color.White.copy(alpha = 0.34f), Offset(start, cy), Offset(end, cy), strokeWidth = 1.2.dp.toPx())
            for (i in -3..3) {
                val x = (i + 3) / 6f * size.width
                val major = i == 0 || i == -3 || i == 3
                val half = if (major) 6.dp.toPx() else 3.dp.toPx()
                drawLine(
                    color = if (i == 0) Color.White.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.42f),
                    start = Offset(x, cy - half),
                    end = Offset(x, cy + half),
                    strokeWidth = if (major) 1.6.dp.toPx() else 1.dp.toPx(),
                )
            }
            if (state.controls.exposureMode != com.hletrd.findx9tele.camera.ExposureMode.MANUAL) {
                val x = ((ev + 3f) / 6f).coerceIn(0f, 1f) * size.width
                drawCircle(CameraColors.ManualActive, radius = 4.dp.toPx(), center = Offset(x, cy))
            }
        }
    }
}

private fun teleSafetyMessage(state: CameraUiState): String? {
    if (!state.teleconverterMode) return null
    if (!state.controls.oisEnabled) return "300mm WARN · OIS OFF"
    if (state.videoStabMode == VideoStabMode.OFF && state.mode == CaptureMode.VIDEO) return "300mm WARN · STAB OFF"
    val exposureNs = when {
        state.controls.exposureMode == com.hletrd.findx9tele.camera.ExposureMode.PROGRAM -> state.liveExposureNs
        else -> state.controls.effectiveExposureNs()
    } ?: return null
    return if (exposureNs > TELE_SAFE_SHUTTER_NS) "300mm WARN · ${formatSafetyShutter(exposureNs)}" else null
}

private fun formatSafetyShutter(ns: Long): String {
    val seconds = ns / 1_000_000_000.0
    return if (seconds >= 1.0) "%.1fs".format(seconds) else "1/${(1.0 / seconds).roundToInt().coerceAtLeast(1)}s"
}

private const val TELE_SAFE_SHUTTER_NS = 1_000_000_000L / 320L

// ---------------------------------------------------------------------------
// Bottom cluster: mode carousel + shutter row (the manual dial cluster lives in ManualDials.kt).
// ---------------------------------------------------------------------------

@Composable
private fun ModeCarousel(
    mode: CaptureMode,
    onModeChange: (CaptureMode) -> Unit,
    modifier: Modifier = Modifier,
    glyphRotation: Float = 0f,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            // The mode labels are SHORT ("Photo"/"Video"), so — iPhone-style — they DO counter-rotate
            // to stay upright as the phone turns (unlike the wide dial pills, which would overflow their
            // fixed row slots and are kept screen-fixed). The label + its underline rotate as one unit.
            ModeLabel(text = "Photo", active = mode == CaptureMode.PHOTO, onClick = { onModeChange(CaptureMode.PHOTO) }, modifier = Modifier.rotate(glyphRotation))
            ModeLabel(text = "Video", active = mode == CaptureMode.VIDEO, onClick = { onModeChange(CaptureMode.VIDEO) }, modifier = Modifier.rotate(glyphRotation))
        }
    }
}

@Composable
private fun ModeLabel(text: String, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            color = if (active) CameraColors.TextPrimary else CameraColors.TextSecondary,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            fontSize = if (active) 15.sp else 14.sp,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .width(18.dp)
                .height(2.dp)
                .background(
                    if (active) CameraColors.TextPrimary else Color.Transparent,
                    RoundedCornerShape(1.dp),
                ),
        )
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
    glyphRotation: Float = 0f,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        // Counter-rotate the review thumbnail so its image reads upright as the phone turns.
        GalleryThumb(uri = lastMediaUri, onClick = onOpenReview, modifier = Modifier.rotate(glyphRotation))
        Spacer(modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (mode == CaptureMode.VIDEO && isRecording) {
                SnapshotButton(onClick = onSnapshot)
            }
            ShutterButton(mode = mode, isRecording = isRecording, onClick = onShutter)
        }
        Spacer(modifier = Modifier.weight(1f))
        LensFlipButton(active = teleconverterOn, onClick = onToggleTeleconverter)
    }
}

/** Large circular shutter: white ring; PHOTO = solid white; VIDEO idle = red dot; recording = red square. */
@Composable
private fun ShutterButton(
    mode: CaptureMode,
    isRecording: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
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
            .semantics {
                contentDescription = when {
                    mode == CaptureMode.PHOTO -> "Take photo"
                    isRecording -> "Stop recording"
                    else -> "Start recording"
                }
                role = Role.Button
            }
            .clickable(interactionSource = interaction, indication = null) {
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
private fun SnapshotButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    // 48 dp touch target, 36 dp visual dot.
    Box(
        modifier = modifier
            .size(48.dp)
            .semantics {
                contentDescription = "Take photo while recording"
                role = Role.Button
            }
            .clickable(onClick = onClick),
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
 * this doubles as the "lens/flip" slot Pixel-style camera apps reserve for a front/back switch —
 * this app has a single fixed tele lens, so there is nothing to flip to; the teleconverter toggle
 * is the closest in-contract analogue and is duplicated here (also reachable via the top-bar TELE
 * chip and the pro sheet's Stabilization tab) for quick access next to the shutter.
 */
@Composable
private fun LensFlipButton(active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ringColor = if (active) CameraColors.Accent else Color.White.copy(alpha = 0.35f)
    val glyphColor = if (active) CameraColors.Accent else CameraColors.TextPrimary
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
            .clickable(onClick = onClick),
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
    override fun onPreviewSurfaceChanged(width: Int, height: Int) = Unit
    override fun onPreviewSurfaceDestroyed() = Unit

    override fun onFocusMode(mode: FocusMode) = Unit
    override fun onFocusSlider(slider: Float) = Unit
    override fun onAfLock(locked: Boolean) = Unit
    override fun onTapFocus(nx: Float, ny: Float) = Unit

    override fun onIso(iso: Int) = Unit
    override fun onShutterNs(ns: Long) = Unit
    override fun onExposureCompensation(ev: Int) = Unit
    override fun onExposureMode(mode: com.hletrd.findx9tele.camera.ExposureMode) = Unit
    override fun onToggleAutoExposure(auto: Boolean) = Unit
    override fun onToggleAeLock(locked: Boolean) = Unit
    override fun onAntibanding(mode: Antibanding) = Unit
    override fun onFps(fps: Int) = Unit
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
    override fun onSetFnSlots(slots: List<com.hletrd.findx9tele.camera.FnSlot>) = Unit
    override fun onSetMyMenuSlots(slots: List<com.hletrd.findx9tele.camera.FnSlot>) = Unit
    override fun onStoreMemorySlot(slot: com.hletrd.findx9tele.camera.MemorySlot) = Unit
    override fun onRecallMemorySlot(slot: com.hletrd.findx9tele.camera.MemorySlot) = Unit
    override fun onVolumeKeyAction(action: com.hletrd.findx9tele.camera.HardwareKeyAction) = Unit
    override fun onHalfPressAction(action: com.hletrd.findx9tele.camera.HardwareKeyAction) = Unit
    override fun onDeleteLastMedia() = Unit
}

@Preview(showBackground = true)
@Composable
private fun CameraScreenPreview() {
    FindX9TeleTheme {
        CameraScreen(state = CameraUiState(), actions = PreviewCameraActions)
    }
}
