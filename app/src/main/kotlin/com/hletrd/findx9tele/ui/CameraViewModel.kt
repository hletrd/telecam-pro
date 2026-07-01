package com.hletrd.findx9tele.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import com.hletrd.findx9tele.camera.Antibanding
import com.hletrd.findx9tele.camera.CameraEngine
import com.hletrd.findx9tele.camera.CameraUiState
import com.hletrd.findx9tele.camera.CaptureMode
import com.hletrd.findx9tele.camera.ColorEffect
import com.hletrd.findx9tele.camera.ColorTransfer
import com.hletrd.findx9tele.camera.FlashMode
import com.hletrd.findx9tele.camera.FocusMode
import com.hletrd.findx9tele.camera.GridType
import com.hletrd.findx9tele.camera.ManualControls
import com.hletrd.findx9tele.camera.PhotoFormats
import com.hletrd.findx9tele.camera.ProcessingLevel
import com.hletrd.findx9tele.camera.ShutterTimer
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
    private val recordTicker = object : Runnable {
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
    override fun onPreviewSurfaceChanged(width: Int, height: Int) = engine.onPreviewSurfaceChanged(width, height)
    override fun onPreviewSurfaceDestroyed() = engine.onPreviewSurfaceDestroyed()

    // ---- Focus ----
    override fun onFocusMode(mode: FocusMode) = updateControls { it.copy(focusMode = mode) }
    override fun onFocusSlider(slider: Float) {
        val min = _state.value.caps?.minFocusDistanceDiopters ?: 0f
        val d = FocusMapping.sliderToDiopters(slider, min)
        updateControls { it.copy(focusDistanceDiopters = d, focusMode = FocusMode.MANUAL) }
    }

    // ---- Exposure ----
    override fun onIso(iso: Int) = updateControls { it.copy(iso = iso, autoExposure = false) }
    override fun onShutterNs(ns: Long) = updateControls { it.copy(exposureTimeNs = ns, autoExposure = false) }
    override fun onExposureCompensation(ev: Int) = updateControls { it.copy(exposureCompensation = ev) }
    override fun onToggleAutoExposure(auto: Boolean) = updateControls { it.copy(autoExposure = auto) }
    override fun onToggleAeLock(locked: Boolean) = updateControls { it.copy(aeLock = locked) }
    override fun onAntibanding(mode: Antibanding) = updateControls { it.copy(antibanding = mode) }

    // ---- White balance ----
    override fun onToggleAutoWb(auto: Boolean) = updateControls { it.copy(autoWhiteBalance = auto) }
    override fun onWbKelvin(kelvin: Int) = updateControls { it.copy(wbKelvin = kelvin, autoWhiteBalance = false) }
    override fun onWbTint(tint: Int) = updateControls { it.copy(wbTint = tint, autoWhiteBalance = false) }
    override fun onToggleAwbLock(locked: Boolean) = updateControls { it.copy(awbLock = locked) }

    // ---- Processing ----
    override fun onEdge(level: ProcessingLevel) = updateControls { it.copy(edge = level) }
    override fun onNoiseReduction(level: ProcessingLevel) = updateControls { it.copy(noiseReduction = level) }
    override fun onColorEffect(effect: ColorEffect) = updateControls { it.copy(colorEffect = effect) }

    // ---- Optics / output ----
    override fun onFlash(mode: FlashMode) = updateControls { it.copy(flash = mode) }
    override fun onToggleOis(enabled: Boolean) = updateControls { it.copy(oisEnabled = enabled) }
    override fun onZoomRatio(ratio: Float) = updateControls { it.copy(zoomRatio = ratio) }
    override fun onJpegQuality(quality: Int) = updateControls { it.copy(jpegQuality = quality) }

    // ---- Modes ----
    override fun onModeChange(mode: CaptureMode) = _state.update { it.copy(mode = mode) }
    override fun onTransfer(transfer: ColorTransfer) {
        engine.setTransfer(transfer)
        _state.update { it.copy(transfer = transfer) }
    }
    override fun onSetPhotoFormats(formats: PhotoFormats) = _state.update { it.copy(photoFormats = formats) }
    override fun onToggleRecordAudio(enabled: Boolean) = _state.update { it.copy(recordAudio = enabled) }
    override fun onToggleTeleconverter(enabled: Boolean) {
        engine.setTeleconverterMode(enabled)
        _state.update { it.copy(teleconverterMode = enabled) }
    }

    // ---- Stabilization ----
    override fun onToggleEis(enabled: Boolean) {
        engine.setEisEnabled(enabled)
        _state.update { it.copy(eisEnabled = enabled) }
    }

    // ---- Assists ----
    override fun onTogglePeaking(enabled: Boolean) {
        engine.setPeaking(enabled)
        _state.update { it.copy(focusPeaking = enabled) }
    }
    override fun onToggleZebra(enabled: Boolean) {
        engine.setZebra(enabled)
        _state.update { it.copy(zebra = enabled) }
    }
    override fun onToggleFalseColor(enabled: Boolean) {
        engine.setFalseColor(enabled)
        _state.update { it.copy(falseColor = enabled) }
    }
    override fun onToggleHistogram(enabled: Boolean) = _state.update { it.copy(histogram = enabled) }
    override fun onGridType(type: GridType) = _state.update { it.copy(grid = type) }
    override fun onToggleLevel(enabled: Boolean) = _state.update { it.copy(level = enabled) }
    override fun onTogglePunchIn(enabled: Boolean) = _state.update { it.copy(punchIn = enabled) }

    // ---- Drive ----
    override fun onTimer(timer: ShutterTimer) = _state.update { it.copy(timer = timer) }

    // ---- Shutter ----
    override fun onCapturePhoto() {
        val seconds = _state.value.timer.seconds
        if (seconds <= 0) engine.capturePhoto(_state.value.photoFormats) else startCountdown(seconds)
    }

    private fun startCountdown(seconds: Int) {
        _state.update { it.copy(timerCountdownSec = seconds) }
        val tick = object : Runnable {
            override fun run() {
                val cur = _state.value.timerCountdownSec
                if (cur <= 1) {
                    _state.update { it.copy(timerCountdownSec = 0) }
                    engine.capturePhoto(_state.value.photoFormats)
                } else {
                    _state.update { it.copy(timerCountdownSec = cur - 1) }
                    mainHandler.postDelayed(this, 1000)
                }
            }
        }
        mainHandler.postDelayed(tick, 1000)
    }

    override fun onToggleRecording() {
        if (_state.value.isRecording) {
            engine.stopRecording()
            mainHandler.removeCallbacks(recordTicker)
            _state.update { it.copy(isRecording = false) }
        } else if (engine.startRecording(_state.value.recordAudio)) {
            recordStartMs = SystemClock.elapsedRealtime()
            mainHandler.post(recordTicker)
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
        mainHandler.removeCallbacksAndMessages(null)
        engine.release()
        super.onCleared()
    }
}
