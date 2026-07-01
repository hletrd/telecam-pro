package com.hletrd.findx9tele.ui

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
import com.hletrd.findx9tele.camera.EisStrength
import com.hletrd.findx9tele.camera.ProcessingLevel
import com.hletrd.findx9tele.camera.ShutterMode
import com.hletrd.findx9tele.camera.ShutterTimer
import com.hletrd.findx9tele.camera.VideoCodec
import com.hletrd.findx9tele.camera.WbMode
import com.hletrd.findx9tele.ui.controls.FocusSlider
import com.hletrd.findx9tele.ui.controls.ProPanel
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
import com.hletrd.findx9tele.ui.theme.FindX9TeleTheme

/**
 * Root camera UI. Stateless: everything shown comes from [state], every interaction is forwarded
 * to [actions]. Hosts the GL preview via a plain [SurfaceView] (an external GL thread renders into
 * it) and layers overlays, the collapsible pro panel, and the bottom bar on top.
 */
@Composable
fun CameraScreen(
    state: CameraUiState,
    actions: CameraActions,
    modifier: Modifier = Modifier,
) {
    var panelExpanded by remember { mutableStateOf(false) }
    val currentActions = rememberUpdatedState(actions)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        if (w > 0f && h > 0f) currentActions.value.onTapFocus(offset.x / w, offset.y / h)
                    }
                },
            factory = { context ->
                val surfaceView = SurfaceView(context)
                surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        currentActions.value.onPreviewSurfaceAvailable(
                            holder.surface,
                            surfaceView.width,
                            surfaceView.height,
                        )
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int,
                    ) {
                        currentActions.value.onPreviewSurfaceChanged(width, height)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        currentActions.value.onPreviewSurfaceDestroyed()
                    }
                })
                surfaceView
            },
        )

        GridOverlay(type = state.grid, modifier = Modifier.fillMaxSize())

        if (state.aspectRatio != AspectRatio.FULL) {
            AspectMask(ratio = state.aspectRatio, modifier = Modifier.fillMaxSize())
        }

        if (state.level) {
            LevelOverlay(rollDegrees = state.levelRoll, modifier = Modifier.fillMaxSize())
        }

        if (state.tapPoint != null) {
            FocusReticle(point = state.tapPoint, modifier = Modifier.fillMaxSize())
        }

        val cameraLabel = remember(state.caps) {
            val caps = state.caps
            when {
                caps == null -> "-"
                caps.physicalId != null -> "${caps.logicalId}:${caps.physicalId}"
                else -> caps.logicalId
            }
        }
        StatusBar(
            cameraLabel = cameraLabel,
            equivFocalMm = state.caps?.equivalentFocalMm ?: 0f,
            teleconverter = state.teleconverterMode,
            transfer = state.transfer,
            photoFormats = state.photoFormats,
            eis = state.eisEnabled,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.isRecording) {
                RecordingIndicator(elapsedMs = state.recordElapsedMs)
            }
            if (state.isRecording && state.recordAudio) {
                AudioMeter(level = state.audioLevel)
            }
            if (state.histogram) {
                HistogramOverlay(data = state.histogramData)
            }
            if (state.waveform) {
                WaveformOverlay(data = state.waveformData)
            }
        }

        if (state.timerCountdownSec > 0) {
            TimerCountdown(seconds = state.timerCountdownSec, modifier = Modifier.fillMaxSize())
        }

        state.statusMessage?.let { message ->
            Text(
                text = message,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 56.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FocusSlider(
                    focusDistanceDiopters = state.controls.focusDistanceDiopters,
                    minFocusDiopters = state.caps?.minFocusDistanceDiopters ?: 0f,
                    focusMode = state.controls.focusMode,
                    onFocusSlider = actions::onFocusSlider,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            ProPanel(
                expanded = panelExpanded,
                state = state,
                actions = actions,
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

            BottomBar(
                mode = state.mode,
                isRecording = state.isRecording,
                panelExpanded = panelExpanded,
                onModeChange = actions::onModeChange,
                onShutter = onShutter,
                onSnapshot = actions::onCapturePhoto,
                onToggleSettings = { panelExpanded = !panelExpanded },
            )
        }
    }
}

/** Mode toggle (PHOTO/VIDEO) + large shutter/record button + small pro-panel settings toggle. */
@Composable
private fun BottomBar(
    mode: CaptureMode,
    isRecording: Boolean,
    panelExpanded: Boolean,
    onModeChange: (CaptureMode) -> Unit,
    onShutter: () -> Unit,
    onSnapshot: () -> Unit,
    onToggleSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = mode == CaptureMode.PHOTO,
                onClick = { onModeChange(CaptureMode.PHOTO) },
                label = { Text("사진") },
            )
            FilterChip(
                selected = mode == CaptureMode.VIDEO,
                onClick = { onModeChange(CaptureMode.VIDEO) },
                label = { Text("동영상") },
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            ShutterButton(mode = mode, isRecording = isRecording, onClick = onShutter)
            if (mode == CaptureMode.VIDEO && isRecording) {
                SnapshotButton(onClick = onSnapshot)
            }
        }

        SettingsToggle(expanded = panelExpanded, onClick = onToggleSettings)
    }
}

/** Large circular shutter (photo) / record (video, turns into a square while recording) button. */
@Composable
private fun ShutterButton(
    mode: CaptureMode,
    isRecording: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fillColor = if (mode == CaptureMode.VIDEO) Color(0xFFFF3B30) else Color.White
    Canvas(
        modifier = modifier
            .size(72.dp)
            .clickable(onClick = onClick),
    ) {
        drawCircle(color = Color.White, radius = size.minDimension / 2f, style = Stroke(width = 4.dp.toPx()))
        if (isRecording) {
            val rectSize = size.minDimension * 0.4f
            drawRoundRect(
                color = fillColor,
                topLeft = Offset((size.width - rectSize) / 2f, (size.height - rectSize) / 2f),
                size = Size(rectSize, rectSize),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
            )
        } else {
            drawCircle(color = fillColor, radius = size.minDimension * 0.38f)
        }
    }
}

/**
 * Small circular snapshot button (a plain white dot), shown only while recording video so a still
 * can be pulled mid-clip. Calls straight into [CameraActions.onCapturePhoto] — the JPEG/RAW
 * readers stay attached to the session for the whole recording.
 */
@Composable
private fun SnapshotButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .size(44.dp)
            .clickable(onClick = onClick),
    ) {
        drawCircle(color = Color.White, radius = size.minDimension / 2f, style = Stroke(width = 2.dp.toPx()))
        drawCircle(color = Color.White, radius = size.minDimension * 0.32f)
    }
}

/** Small circular toggle for the collapsible pro panel; drawn with a gear glyph (no icon library). */
@Composable
private fun SettingsToggle(
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (expanded) Color(0xFF4C9AFF) else Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "⚙", color = Color.White, style = MaterialTheme.typography.titleMedium)
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
    override fun onToggleAutoExposure(auto: Boolean) = Unit
    override fun onToggleAeLock(locked: Boolean) = Unit
    override fun onAntibanding(mode: Antibanding) = Unit
    override fun onFps(fps: Int) = Unit
    override fun onShutterMode(mode: ShutterMode) = Unit
    override fun onShutterAngle(angle: Float) = Unit

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
    override fun onJpegQuality(quality: Int) = Unit

    override fun onModeChange(mode: CaptureMode) = Unit
    override fun onTransfer(transfer: ColorTransfer) = Unit
    override fun onSetPhotoFormats(formats: PhotoFormats) = Unit
    override fun onAspectRatio(ratio: AspectRatio) = Unit
    override fun onToggleRecordAudio(enabled: Boolean) = Unit
    override fun onAudioGain(gain: Float) = Unit
    override fun onToggleTeleconverter(enabled: Boolean) = Unit
    override fun onVideoCodec(codec: VideoCodec) = Unit
    override fun onBitrateLevel(level: BitrateLevel) = Unit
    override fun onVideoResolution(size: android.util.Size) = Unit

    override fun onToggleEis(enabled: Boolean) = Unit
    override fun onEisStrength(strength: EisStrength) = Unit

    override fun onTogglePeaking(enabled: Boolean) = Unit
    override fun onToggleZebra(enabled: Boolean) = Unit
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

    override fun onCameraOverride(id: String?) = Unit
}

@Preview(showBackground = true)
@Composable
private fun CameraScreenPreview() {
    FindX9TeleTheme {
        CameraScreen(state = CameraUiState(), actions = PreviewCameraActions)
    }
}
