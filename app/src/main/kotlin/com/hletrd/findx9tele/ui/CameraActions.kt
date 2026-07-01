package com.hletrd.findx9tele.ui

import android.view.Surface
import com.hletrd.findx9tele.camera.CaptureMode
import com.hletrd.findx9tele.camera.ColorTransfer
import com.hletrd.findx9tele.camera.PhotoFormats

/**
 * Everything the (stateless) Compose UI can ask the engine to do. Implemented by CameraViewModel.
 * The UI never touches Camera2/GL directly; it renders [com.hletrd.findx9tele.camera.CameraUiState]
 * and calls these.
 */
interface CameraActions {
    // Preview surface lifecycle (from the SurfaceView the UI hosts).
    fun onPreviewSurfaceAvailable(surface: Surface, width: Int, height: Int)
    fun onPreviewSurfaceChanged(width: Int, height: Int)
    fun onPreviewSurfaceDestroyed()

    // Focus
    fun onFocusSlider(slider: Float)
    fun onToggleAutoFocus(auto: Boolean)

    // Exposure
    fun onIso(iso: Int)
    fun onShutterNs(ns: Long)
    fun onExposureCompensation(ev: Int)
    fun onToggleAutoExposure(auto: Boolean)

    // White balance
    fun onWbKelvin(kelvin: Int)
    fun onToggleAutoWb(auto: Boolean)

    // Modes / formats
    fun onModeChange(mode: CaptureMode)
    fun onTransfer(transfer: ColorTransfer)
    fun onSetPhotoFormats(formats: PhotoFormats)
    fun onToggleRecordAudio(enabled: Boolean)

    // Overlays
    fun onTogglePeaking(enabled: Boolean)
    fun onToggleZebra(enabled: Boolean)
    fun onToggleGrid(enabled: Boolean)
    fun onToggleLevel(enabled: Boolean)
    fun onTogglePunchIn(enabled: Boolean)

    // Shutter
    fun onCapturePhoto()
    fun onToggleRecording()

    // Settings
    fun onCameraOverride(id: String?)
}
