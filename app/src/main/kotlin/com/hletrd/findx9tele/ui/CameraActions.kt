package com.hletrd.findx9tele.ui

import android.view.Surface
import com.hletrd.findx9tele.camera.Antibanding
import com.hletrd.findx9tele.camera.CaptureMode
import com.hletrd.findx9tele.camera.ColorEffect
import com.hletrd.findx9tele.camera.ColorTransfer
import com.hletrd.findx9tele.camera.FlashMode
import com.hletrd.findx9tele.camera.FocusMode
import com.hletrd.findx9tele.camera.GridType
import com.hletrd.findx9tele.camera.PhotoFormats
import com.hletrd.findx9tele.camera.ProcessingLevel
import com.hletrd.findx9tele.camera.ShutterTimer

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

    // Exposure
    fun onIso(iso: Int)
    fun onShutterNs(ns: Long)
    fun onExposureCompensation(ev: Int)
    fun onToggleAutoExposure(auto: Boolean)
    fun onToggleAeLock(locked: Boolean)
    fun onAntibanding(mode: Antibanding)

    // White balance
    fun onToggleAutoWb(auto: Boolean)
    fun onWbKelvin(kelvin: Int)
    fun onWbTint(tint: Int)
    fun onToggleAwbLock(locked: Boolean)

    // Processing
    fun onEdge(level: ProcessingLevel)
    fun onNoiseReduction(level: ProcessingLevel)
    fun onColorEffect(effect: ColorEffect)

    // Optics / output
    fun onFlash(mode: FlashMode)
    fun onToggleOis(enabled: Boolean)
    fun onZoomRatio(ratio: Float)
    fun onJpegQuality(quality: Int)

    // Modes
    fun onModeChange(mode: CaptureMode)
    fun onTransfer(transfer: ColorTransfer)
    fun onSetPhotoFormats(formats: PhotoFormats)
    fun onToggleRecordAudio(enabled: Boolean)
    fun onToggleTeleconverter(enabled: Boolean)

    // Stabilization
    fun onToggleEis(enabled: Boolean)

    // Viewfinder assists
    fun onTogglePeaking(enabled: Boolean)
    fun onToggleZebra(enabled: Boolean)
    fun onToggleFalseColor(enabled: Boolean)
    fun onToggleHistogram(enabled: Boolean)
    fun onGridType(type: GridType)
    fun onToggleLevel(enabled: Boolean)
    fun onTogglePunchIn(enabled: Boolean)

    // Drive
    fun onTimer(timer: ShutterTimer)

    // Shutter
    fun onCapturePhoto()
    fun onToggleRecording()

    // Settings
    fun onCameraOverride(id: String?)
}
