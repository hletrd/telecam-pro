package com.hletrd.findx9tele.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import com.hletrd.findx9tele.camera.CameraEngine
import com.hletrd.findx9tele.camera.CameraUiState
import com.hletrd.findx9tele.camera.CaptureMode
import com.hletrd.findx9tele.camera.ColorTransfer
import com.hletrd.findx9tele.camera.ManualControls
import com.hletrd.findx9tele.camera.PhotoFormats
import com.hletrd.findx9tele.focus.FocusMapping
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Holds [CameraUiState] and turns [CameraActions] into [CameraEngine] calls. UI-thread only. */
class CameraViewModel(app: Application) : AndroidViewModel(app), CameraActions {

    private val engine = CameraEngine(app)
    private val _state = MutableStateFlow(CameraUiState())
    val state: StateFlow<CameraUiState> = _state.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recordStartMs = 0L
    private val ticker = object : Runnable {
        override fun run() {
            _state.update { it.copy(recordElapsedMs = SystemClock.elapsedRealtime() - recordStartMs) }
            mainHandler.postDelayed(this, 200)
        }
    }

    init {
        engine.onStatus = { msg -> _state.update { it.copy(statusMessage = msg) } }
        engine.onCapsReady = { caps -> _state.update { it.copy(caps = caps) } }
    }

    // ---- Preview surface ----
    override fun onPreviewSurfaceAvailable(surface: Surface, width: Int, height: Int) =
        engine.onPreviewSurfaceAvailable(surface, width, height)

    override fun onPreviewSurfaceChanged(width: Int, height: Int) =
        engine.onPreviewSurfaceChanged(width, height)

    override fun onPreviewSurfaceDestroyed() = engine.onPreviewSurfaceDestroyed()

    // ---- Focus ----
    override fun onFocusSlider(slider: Float) {
        val min = _state.value.caps?.minFocusDistanceDiopters ?: 0f
        val d = FocusMapping.sliderToDiopters(slider, min)
        updateControls { it.copy(focusDistanceDiopters = d, autoFocus = false) }
    }

    override fun onToggleAutoFocus(auto: Boolean) = updateControls { it.copy(autoFocus = auto) }

    // ---- Exposure ----
    override fun onIso(iso: Int) = updateControls { it.copy(iso = iso, autoExposure = false) }
    override fun onShutterNs(ns: Long) = updateControls { it.copy(exposureTimeNs = ns, autoExposure = false) }
    override fun onExposureCompensation(ev: Int) = updateControls { it.copy(exposureCompensation = ev) }
    override fun onToggleAutoExposure(auto: Boolean) = updateControls { it.copy(autoExposure = auto) }

    // ---- White balance ----
    override fun onWbKelvin(kelvin: Int) = updateControls { it.copy(wbKelvin = kelvin, autoWhiteBalance = false) }
    override fun onToggleAutoWb(auto: Boolean) = updateControls { it.copy(autoWhiteBalance = auto) }

    // ---- Modes / formats ----
    override fun onModeChange(mode: CaptureMode) = _state.update { it.copy(mode = mode) }
    override fun onTransfer(transfer: ColorTransfer) {
        engine.setTransfer(transfer)
        _state.update { it.copy(transfer = transfer) }
    }
    override fun onSetPhotoFormats(formats: PhotoFormats) = _state.update { it.copy(photoFormats = formats) }
    override fun onToggleRecordAudio(enabled: Boolean) = _state.update { it.copy(recordAudio = enabled) }

    // ---- Overlays ----
    override fun onTogglePeaking(enabled: Boolean) {
        engine.setPeaking(enabled)
        _state.update { it.copy(focusPeaking = enabled) }
    }
    override fun onToggleZebra(enabled: Boolean) {
        engine.setZebra(enabled)
        _state.update { it.copy(zebra = enabled) }
    }
    override fun onToggleGrid(enabled: Boolean) = _state.update { it.copy(grid = enabled) }
    override fun onToggleLevel(enabled: Boolean) = _state.update { it.copy(level = enabled) }
    override fun onTogglePunchIn(enabled: Boolean) = _state.update { it.copy(punchIn = enabled) }

    // ---- Shutter ----
    override fun onCapturePhoto() = engine.capturePhoto(_state.value.photoFormats)

    override fun onToggleRecording() {
        if (_state.value.isRecording) {
            engine.stopRecording()
            mainHandler.removeCallbacks(ticker)
            _state.update { it.copy(isRecording = false) }
        } else if (engine.startRecording(_state.value.recordAudio)) {
            recordStartMs = SystemClock.elapsedRealtime()
            mainHandler.post(ticker)
            _state.update { it.copy(isRecording = true, recordElapsedMs = 0) }
        }
    }

    override fun onCameraOverride(id: String?) {
        engine.setCameraOverride(id)
        _state.update { it.copy(cameraOverrideId = id) }
    }

    private inline fun updateControls(block: (ManualControls) -> ManualControls) {
        val updated = block(_state.value.controls)
        engine.setControls(updated)
        _state.update { it.copy(controls = updated) }
    }

    override fun onCleared() {
        mainHandler.removeCallbacks(ticker)
        engine.release()
        super.onCleared()
    }
}
