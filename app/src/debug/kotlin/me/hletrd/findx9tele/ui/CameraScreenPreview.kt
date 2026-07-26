package me.hletrd.findx9tele.ui

import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import me.hletrd.findx9tele.camera.Antibanding
import me.hletrd.findx9tele.camera.AspectRatio
import me.hletrd.findx9tele.camera.BitrateLevel
import me.hletrd.findx9tele.camera.CameraUiState
import me.hletrd.findx9tele.camera.CaptureMode
import me.hletrd.findx9tele.camera.ColorEffect
import me.hletrd.findx9tele.camera.ColorTransfer
import me.hletrd.findx9tele.camera.DriveMode
import me.hletrd.findx9tele.camera.FlashMode
import me.hletrd.findx9tele.camera.FocusMode
import me.hletrd.findx9tele.camera.FrameLineType
import me.hletrd.findx9tele.camera.GridType
import me.hletrd.findx9tele.camera.MeteringMode
import me.hletrd.findx9tele.camera.PhotoFormats
import me.hletrd.findx9tele.camera.ProcessingLevel
import me.hletrd.findx9tele.camera.ShutterMode
import me.hletrd.findx9tele.camera.ShutterTimer
import me.hletrd.findx9tele.camera.VideoCodec
import me.hletrd.findx9tele.camera.WbMode
import me.hletrd.findx9tele.ui.theme.FindX9TeleTheme

// Debug-only source set on purpose: release keeps isMinifyEnabled = false (deliberate, see
// app/build.gradle.kts), so an @Preview entry point and a ~110-method no-op CameraActions living in
// main would ship inside the AAB with nothing able to strip them. Same package as CameraScreen.kt,
// so the debug-only UiSnapshotActivity keeps resolving PreviewCameraActions unchanged.

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
    override fun onExposureMode(mode: me.hletrd.findx9tele.camera.ExposureMode) = Unit
    override fun onToggleAeLock(locked: Boolean) = Unit
    override fun onAntibanding(mode: Antibanding) = Unit
    override fun onShutterMode(mode: ShutterMode) = Unit
    override fun onShutterAngle(angle: Float) = Unit
    override fun onExposureStep(step: me.hletrd.findx9tele.camera.ExposureStep) = Unit

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
    override fun onAudioScene(scene: me.hletrd.findx9tele.camera.AudioScene) = Unit
    override fun onAudioInputPreference(preference: me.hletrd.findx9tele.camera.AudioInputPreference) = Unit
    override fun onToggleTeleconverter(enabled: Boolean) = Unit
    override fun onPhoneModel(model: me.hletrd.findx9tele.camera.PhoneModel) = Unit
    override fun onTeleconverterProfile(profile: me.hletrd.findx9tele.camera.TeleconverterProfile) = Unit
    override fun onTeleconverterCustomMagnification(value: Float) = Unit
    override fun onVideoCodec(codec: VideoCodec) = Unit
    override fun onBitrateLevel(level: BitrateLevel) = Unit
    override fun onVideoResolution(size: android.util.Size) = Unit
    override fun onVideoFrameRate(rate: me.hletrd.findx9tele.camera.VideoFrameRate) = Unit
    override fun onToggleOpenGate(enabled: Boolean) = Unit

    override fun onVideoStabMode(mode: me.hletrd.findx9tele.camera.VideoStabMode) = Unit

    override fun onTogglePeaking(enabled: Boolean) = Unit
    override fun onPeakingLevel(level: me.hletrd.findx9tele.camera.PeakingLevel) = Unit
    override fun onPeakingColor(color: me.hletrd.findx9tele.camera.PeakingColor) = Unit
    override fun onToggleZebra(enabled: Boolean) = Unit
    override fun onZebraLevel(level: me.hletrd.findx9tele.camera.ZebraLevel) = Unit
    override fun onToggleFalseColor(enabled: Boolean) = Unit
    override fun onToggleHistogram(enabled: Boolean) = Unit
    override fun onToggleWaveform(enabled: Boolean) = Unit
    override fun onToggleGammaAssist(enabled: Boolean) = Unit
    override fun onFrameLines(type: FrameLineType) = Unit
    override fun onAfSpotSize(size: me.hletrd.findx9tele.camera.AfSpotSize) = Unit
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

    override fun onLens(choice: me.hletrd.findx9tele.camera.LensChoice) = Unit
    override fun onToggleFrontCamera() = Unit
    override fun onCameraOverride(id: String?) = Unit
    override fun onToggleRememberSettings(enabled: Boolean) = Unit
    override fun onTogglePreserveLensSelection(enabled: Boolean) = Unit
    override fun onTogglePreserveTeleconverter(enabled: Boolean) = Unit
    override fun onSetPhotoFnSlots(slots: List<me.hletrd.findx9tele.camera.FnSlot>) = Unit
    override fun onSetVideoFnSlots(slots: List<me.hletrd.findx9tele.camera.FnSlot>) = Unit
    override fun onSetMyMenuSlots(slots: List<me.hletrd.findx9tele.camera.FnSlot>) = Unit
    override fun onStoreMemorySlot(slot: me.hletrd.findx9tele.camera.MemorySlot) = Unit
    override fun onRecallMemorySlot(slot: me.hletrd.findx9tele.camera.MemorySlot) = Unit
    override fun onVolumeKeyAction(action: me.hletrd.findx9tele.camera.HardwareKeyAction) = Unit
    override fun onHalfPressAction(action: me.hletrd.findx9tele.camera.HardwareKeyAction) = Unit
    override fun onDeleteLastMedia(uri: android.net.Uri) = Unit
}

@Preview(showBackground = true)
@Composable
private fun CameraScreenPreview() {
    FindX9TeleTheme {
        CameraScreen(state = CameraUiState(), actions = PreviewCameraActions)
    }
}
