package com.hletrd.findx9tele.ui

import android.util.Size
import android.view.Surface
import com.hletrd.findx9tele.camera.Antibanding
import com.hletrd.findx9tele.camera.AspectRatio
import com.hletrd.findx9tele.camera.BitrateLevel
import com.hletrd.findx9tele.camera.CaptureMode
import com.hletrd.findx9tele.camera.ColorEffect
import com.hletrd.findx9tele.camera.ColorTransfer
import com.hletrd.findx9tele.camera.DriveMode
import com.hletrd.findx9tele.camera.ExposureMode
import com.hletrd.findx9tele.camera.ExposureStep
import com.hletrd.findx9tele.camera.FlashMode
import com.hletrd.findx9tele.camera.FnSlot
import com.hletrd.findx9tele.camera.FocusMode
import com.hletrd.findx9tele.camera.GridType
import com.hletrd.findx9tele.camera.HardwareKeyAction
import com.hletrd.findx9tele.camera.AfSpotSize
import com.hletrd.findx9tele.camera.FrameLineType
import com.hletrd.findx9tele.camera.MeteringMode
import com.hletrd.findx9tele.camera.MemorySlot
import com.hletrd.findx9tele.camera.PeakingColor
import com.hletrd.findx9tele.camera.PeakingLevel
import com.hletrd.findx9tele.camera.PhotoFormats
import com.hletrd.findx9tele.camera.ProcessingLevel
import com.hletrd.findx9tele.camera.ShutterMode
import com.hletrd.findx9tele.camera.ShutterTimer
import com.hletrd.findx9tele.camera.VideoCodec
import com.hletrd.findx9tele.camera.VideoFrameRate
import com.hletrd.findx9tele.camera.WbMode

/**
 * Everything the stateless Compose UI can ask the engine to do. Implemented by CameraViewModel.
 * The UI renders [com.hletrd.findx9tele.camera.CameraUiState] and calls these — it never touches
 * Camera2/GL directly.
 */
interface CameraActions {
    // Preview surface lifecycle
    fun onPreviewSurfaceAvailable(surface: Surface, width: Int, height: Int)
    fun onPreviewSurfaceChanged(width: Int, height: Int)
    fun onPreviewSurfaceDestroyed()

    // Focus
    fun onFocusMode(mode: FocusMode)
    fun onFocusSlider(slider: Float)
    fun onAfLock(locked: Boolean)
    fun onTapFocus(nx: Float, ny: Float)
    fun onResetFocusPoint()

    // Exposure
    fun onIso(iso: Int)
    fun onShutterNs(ns: Long)
    fun onExposureCompensation(ev: Int)
    fun onExposureMode(mode: ExposureMode)
    fun onToggleAutoExposure(auto: Boolean)
    fun onToggleAeLock(locked: Boolean)
    fun onAntibanding(mode: Antibanding)
    fun onShutterMode(mode: ShutterMode)
    fun onShutterAngle(angle: Float)
    fun onExposureStep(step: ExposureStep)

    // White balance
    fun onWbMode(mode: WbMode)
    fun onWbKelvin(kelvin: Int)
    fun onWbTint(tint: Int)
    fun onToggleAwbLock(locked: Boolean)
    fun onMeteringMode(mode: MeteringMode)
    fun onAfSpotSize(size: AfSpotSize)
    fun onCaptureCustomWb()

    // Processing
    fun onEdge(level: ProcessingLevel)
    fun onNoiseReduction(level: ProcessingLevel)
    fun onColorEffect(effect: ColorEffect)

    // Optics / output
    fun onFlash(mode: FlashMode)
    fun onToggleOis(enabled: Boolean)
    fun onZoomRatio(ratio: Float)
    // Pinch-to-zoom on the viewfinder: [factor] is the incremental pinch scale (1.0 = no change).
    fun onPinchZoom(factor: Float)
    fun onJpegQuality(quality: Int)

    // Modes
    fun onModeChange(mode: CaptureMode)
    fun onTransfer(transfer: ColorTransfer)
    fun onSetPhotoFormats(formats: PhotoFormats)
    fun onAspectRatio(ratio: AspectRatio)
    fun onToggleRecordAudio(enabled: Boolean)
    fun onAudioGain(gain: Float)
    fun onAudioScene(scene: com.hletrd.findx9tele.camera.AudioScene)
    fun onAudioInputPreference(preference: com.hletrd.findx9tele.camera.AudioInputPreference)
    fun onToggleTeleconverter(enabled: Boolean)
    fun onVideoCodec(codec: VideoCodec)
    fun onBitrateLevel(level: BitrateLevel)
    fun onVideoResolution(size: Size)
    fun onVideoFrameRate(rate: VideoFrameRate)
    fun onToggleOpenGate(enabled: Boolean)

    // Stabilization
    fun onVideoStabMode(mode: com.hletrd.findx9tele.camera.VideoStabMode)

    // Viewfinder assists
    fun onTogglePeaking(enabled: Boolean)
    fun onPeakingLevel(level: PeakingLevel)
    fun onPeakingColor(color: PeakingColor)
    fun onToggleZebra(enabled: Boolean)
    fun onZebraLevel(level: com.hletrd.findx9tele.camera.ZebraLevel)
    fun onToggleFalseColor(enabled: Boolean)
    fun onToggleHistogram(enabled: Boolean)
    fun onToggleWaveform(enabled: Boolean)
    fun onToggleGammaAssist(enabled: Boolean)
    fun onFrameLines(type: FrameLineType)
    fun onGridType(type: GridType)
    fun onToggleLevel(enabled: Boolean)
    fun onTogglePunchIn(enabled: Boolean)

    // Drive
    fun onTimer(timer: ShutterTimer)
    fun onDriveMode(mode: DriveMode)
    fun onIntervalSec(sec: Int)

    // Shutter
    fun onCapturePhoto()
    fun onToggleRecording()
    fun onHardwareHalfPress(active: Boolean)

    // Lens (bundles teleconverter mode: 3× on, others off)
    fun onLens(choice: com.hletrd.findx9tele.camera.LensChoice)

    // Settings
    fun onCameraOverride(id: String?)
    fun onToggleRememberSettings(enabled: Boolean)
    fun onTogglePreserveLensSelection(enabled: Boolean)
    fun onTogglePreserveTeleconverter(enabled: Boolean)
    fun onSetPhotoFnSlots(slots: List<FnSlot>)
    fun onSetVideoFnSlots(slots: List<FnSlot>)
    fun onSetMyMenuSlots(slots: List<FnSlot>)
    fun onStoreMemorySlot(slot: MemorySlot)
    fun onRecallMemorySlot(slot: MemorySlot)
    fun onVolumeKeyAction(action: HardwareKeyAction)
    fun onHalfPressAction(action: HardwareKeyAction)
    fun onDeleteLastMedia(uri: android.net.Uri)

    /**
     * The full-screen media-review overlay opened/closed. Mirrored into CameraUiState.reviewOpen
     * so MainActivity's hardware-key handlers can refuse to fire the shutter under the overlay.
     */
    fun onReviewOpenChange(open: Boolean)
}
