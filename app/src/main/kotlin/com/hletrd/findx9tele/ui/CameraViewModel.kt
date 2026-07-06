package com.hletrd.findx9tele.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import com.hletrd.findx9tele.camera.Antibanding
import com.hletrd.findx9tele.camera.AspectRatio
import com.hletrd.findx9tele.camera.BitrateLevel
import com.hletrd.findx9tele.camera.CameraEngine
import com.hletrd.findx9tele.camera.CameraUiState
import com.hletrd.findx9tele.camera.CaptureMode
import com.hletrd.findx9tele.camera.ColorEffect
import com.hletrd.findx9tele.camera.ColorTransfer
import com.hletrd.findx9tele.camera.DriveMode
import com.hletrd.findx9tele.camera.EisStrength
import com.hletrd.findx9tele.camera.ExposureStep
import com.hletrd.findx9tele.camera.FlashMode
import com.hletrd.findx9tele.camera.FocusMode
import com.hletrd.findx9tele.camera.GridType
import com.hletrd.findx9tele.camera.ManualControls
import com.hletrd.findx9tele.camera.MeteringMode
import com.hletrd.findx9tele.camera.PhotoFormats
import com.hletrd.findx9tele.camera.ProcessingLevel
import com.hletrd.findx9tele.camera.ShutterMode
import com.hletrd.findx9tele.camera.ShutterTimer
import com.hletrd.findx9tele.camera.VideoCodec
import com.hletrd.findx9tele.camera.VideoFrameRate
import com.hletrd.findx9tele.camera.WbMode
import com.hletrd.findx9tele.focus.FocusMapping
import com.hletrd.findx9tele.storage.ExtraSettings
import com.hletrd.findx9tele.storage.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Holds [CameraUiState] and turns [CameraActions] into [CameraEngine] calls. UI-thread only. */
class CameraViewModel(app: Application) : AndroidViewModel(app), CameraActions {

    private val engine = CameraEngine(app)
    private val settingsStore = SettingsStore(app)
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

    // Debounces engine.setControls() so rapid slider drags don't rebuild the repeating request per tick.
    private var pendingControls: ManualControls? = null
    private val applyControlsRunnable = Runnable {
        pendingControls?.let { engine.setControls(it) }
        pendingControls = null
    }

    private var countdownRunnable: Runnable? = null

    private var reticleHideRunnable: Runnable? = null

    private val levelTicker = object : Runnable {
        override fun run() {
            _state.update { it.copy(levelRoll = engine.currentRollDegrees()) }
            mainHandler.postDelayed(this, 100)
        }
    }

    // Always-on: tracks the physical device orientation so overlays can rotate to stay upright even
    // though the activity is portrait-locked. Only writes state when the discrete value changes.
    private val orientationTicker = object : Runnable {
        override fun run() {
            val o = engine.currentDeviceOrientation()
            if (o != _state.value.deviceOrientation) _state.update { it.copy(deviceOrientation = o) }
            mainHandler.postDelayed(this, 200)
        }
    }

    init {
        engine.onStatus = { msg -> _state.update { it.copy(statusMessage = msg) } }
        // caps/size arrive on the engine's setup thread; hop to main before touching the engine again.
        engine.onCapsReady = { caps -> _state.update { it.copy(caps = caps) }; mainHandler.post { reconcileFrameRate() } }
        engine.onVideoSizeChosen = { size -> _state.update { it.copy(videoResolution = size) }; mainHandler.post { reconcileFrameRate() } }
        engine.onAnalysis = { h, w -> _state.update { it.copy(histogramData = h, waveformData = w) } }
        engine.onAudioLevel = { lvl -> _state.update { it.copy(audioLevel = lvl) } }
        // AE-resolved ISO/shutter (auto mode) for the live dial readout; camera thread → StateFlow is
        // thread-safe, Compose observes on main. The controller only fires this on change.
        engine.onExposureInfo = { iso, exp -> _state.update { it.copy(liveIso = iso, liveExposureNs = exp) } }
        // Last saved still → gallery thumbnail + in-app review (io thread → StateFlow is thread-safe).
        engine.onMediaSaved = { uri -> _state.update { it.copy(lastMediaUri = uri) } }
        restoreSettingsIfEnabled()
        // Sweep any pending media orphaned by a prior crash/force-kill (record stop never ran).
        engine.cleanupOrphans()
        if (_state.value.level) mainHandler.post(levelTicker)
        mainHandler.post(orientationTicker)
    }

    /** On launch, restore persisted pro settings (if the user enabled "Remember settings"). */
    private fun restoreSettingsIfEnabled() {
        if (!settingsStore.rememberEnabled) return
        val loaded = settingsStore.load()
        if (loaded == null) { _state.update { it.copy(rememberSettings = true) }; return }
        val c = loaded.controls
        val e = loaded.extras
        // Push to the engine (safe pre-start: these set @Volatile fields read when the camera opens).
        engine.setControls(c)
        engine.setTransfer(e.transfer)
        engine.setTeleconverterMode(e.teleconverter)
        engine.setEisEnabled(e.eisEnabled)
        engine.setEisStrength(e.eisStrength)
        engine.setAspectRatio(e.aspectRatio)
        engine.setVideoCodec(e.videoCodec)
        engine.setBitrateLevel(e.bitrateLevel)
        engine.setOpenGate(e.openGate)
        engine.setVideoFrameRate(e.videoFrameRate)
        _state.update {
            it.copy(
                rememberSettings = true,
                controls = c,
                transfer = e.transfer,
                photoFormats = PhotoFormats(e.heif, e.jpeg, e.dngRaw),
                mode = e.mode,
                teleconverterMode = e.teleconverter,
                eisEnabled = e.eisEnabled,
                eisStrength = e.eisStrength,
                aspectRatio = e.aspectRatio,
                grid = e.grid,
                videoCodec = e.videoCodec,
                bitrateLevel = e.bitrateLevel,
                videoFrameRate = e.videoFrameRate,
                openGate = e.openGate,
            )
        }
    }

    private fun currentExtras(): ExtraSettings = _state.value.let { s ->
        ExtraSettings(
            transfer = s.transfer,
            heif = s.photoFormats.heif,
            jpeg = s.photoFormats.jpeg,
            dngRaw = s.photoFormats.dngRaw,
            mode = s.mode,
            teleconverter = s.teleconverterMode,
            eisEnabled = s.eisEnabled,
            eisStrength = s.eisStrength,
            aspectRatio = s.aspectRatio,
            grid = s.grid,
            videoCodec = s.videoCodec,
            bitrateLevel = s.bitrateLevel,
            videoFrameRate = s.videoFrameRate,
            openGate = s.openGate,
        )
    }

    private fun saveSettingsIfEnabled() {
        if (_state.value.rememberSettings) settingsStore.save(_state.value.controls, currentExtras())
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
    override fun onAfLock(locked: Boolean) = updateControls { it.copy(afLock = locked) }
    override fun onTapFocus(nx: Float, ny: Float) {
        engine.setTapPoint(nx, ny)
        _state.update { it.copy(tapPoint = nx to ny) }
        reticleHideRunnable?.let { mainHandler.removeCallbacks(it) }
        val hide = Runnable {
            _state.update { it.copy(tapPoint = null) }
            reticleHideRunnable = null
        }
        reticleHideRunnable = hide
        mainHandler.postDelayed(hide, 2000)
    }

    // ---- Exposure ----
    override fun onIso(iso: Int) = updateControls { it.copy(iso = iso, autoExposure = false) }
    override fun onShutterNs(ns: Long) = updateControls { it.copy(exposureTimeNs = ns, autoExposure = false) }
    override fun onExposureCompensation(ev: Int) = updateControls { it.copy(exposureCompensation = ev) }
    override fun onToggleAutoExposure(auto: Boolean) = updateControls { it.copy(autoExposure = auto) }
    override fun onToggleAeLock(locked: Boolean) = updateControls { it.copy(aeLock = locked) }
    override fun onAntibanding(mode: Antibanding) = updateControls { it.copy(antibanding = mode) }
    override fun onFps(fps: Int) = updateControls { it.copy(fps = fps) }
    override fun onShutterMode(mode: ShutterMode) = updateControls { it.copy(shutterMode = mode) }
    override fun onShutterAngle(angle: Float) =
        updateControls { it.copy(shutterAngle = angle, shutterMode = ShutterMode.ANGLE, autoExposure = false) }
    override fun onExposureStep(step: ExposureStep) = updateControls { it.copy(exposureStep = step) }

    // ---- White balance ----
    override fun onWbMode(mode: WbMode) = updateControls { it.copy(wbMode = mode) }
    override fun onWbKelvin(kelvin: Int) = updateControls { it.copy(wbKelvin = kelvin, wbMode = WbMode.MANUAL) }
    override fun onWbTint(tint: Int) = updateControls { it.copy(wbTint = tint, wbMode = WbMode.MANUAL) }
    override fun onToggleAwbLock(locked: Boolean) = updateControls { it.copy(awbLock = locked) }
    override fun onMeteringMode(mode: MeteringMode) = updateControls { it.copy(meteringMode = mode) }

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
    override fun onModeChange(mode: CaptureMode) {
        cancelCountdown()
        // Leaving Video mode while recording would orphan the clip (the shutter's stop affordance is
        // gone). Stop and save it first so the mode switch can't strand an in-progress recording.
        if (_state.value.isRecording && mode != CaptureMode.VIDEO) onToggleRecording()
        _state.update { it.copy(mode = mode) }
    }
    override fun onTransfer(transfer: ColorTransfer) {
        engine.setTransfer(transfer)
        _state.update { it.copy(transfer = transfer) }
    }
    override fun onSetPhotoFormats(formats: PhotoFormats) = _state.update { it.copy(photoFormats = formats) }
    override fun onAspectRatio(ratio: AspectRatio) {
        engine.setAspectRatio(ratio)
        _state.update { it.copy(aspectRatio = ratio) }
    }
    override fun onToggleRecordAudio(enabled: Boolean) = _state.update { it.copy(recordAudio = enabled) }
    override fun onAudioGain(gain: Float) {
        engine.setAudioGain(gain)
        _state.update { it.copy(audioGain = gain) }
    }
    override fun onToggleTeleconverter(enabled: Boolean) {
        engine.setTeleconverterMode(enabled)
        _state.update { it.copy(teleconverterMode = enabled) }
    }
    override fun onVideoCodec(codec: VideoCodec) {
        engine.setVideoCodec(codec)
        _state.update { it.copy(videoCodec = codec) }
        reconcileFrameRate()
    }
    override fun onBitrateLevel(level: BitrateLevel) {
        engine.setBitrateLevel(level)
        _state.update { it.copy(bitrateLevel = level) }
    }
    override fun onVideoResolution(size: Size) {
        engine.setVideoResolution(size)
        _state.update { it.copy(videoResolution = size) }
        reconcileFrameRate()
    }
    override fun onVideoFrameRate(rate: VideoFrameRate) {
        engine.setVideoFrameRate(rate)
        // Keep the exposure fps in step so the AE target-fps range, cine shutter angle and sensor
        // frame duration follow the selected video rate (drop-frame rates use their rounded parent).
        val controls = _state.value.controls.copy(fps = rate.fps)
        engine.setControls(controls)
        _state.update { it.copy(videoFrameRate = rate, controls = controls) }
    }
    override fun onToggleOpenGate(enabled: Boolean) {
        engine.setOpenGate(enabled)
        _state.update { it.copy(openGate = enabled) }
        reconcileFrameRate()
    }

    /**
     * After a change to resolution / codec / open-gate, ensure the selected [VideoFrameRate] is still
     * one the current camera can deliver for the new size+codec; if not, snap to the nearest valid
     * rate (preferring the same rounded fps) so the encoder is never handed an impossible rate.
     */
    private fun reconcileFrameRate() {
        val s = _state.value
        val allowed = VideoFrameRate.availableFor(s.caps, s.videoResolution, s.videoCodec)
        if (s.videoFrameRate in allowed) return
        val replacement = allowed.minByOrNull { kotlin.math.abs(it.fps - s.videoFrameRate.fps) } ?: return
        onVideoFrameRate(replacement)
    }

    // ---- Stabilization ----
    override fun onToggleEis(enabled: Boolean) {
        engine.setEisEnabled(enabled)
        _state.update { it.copy(eisEnabled = enabled) }
    }
    override fun onEisStrength(strength: EisStrength) {
        engine.setEisStrength(strength)
        _state.update { it.copy(eisStrength = strength) }
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
    override fun onToggleHistogram(enabled: Boolean) {
        engine.setAnalysis(enabled, _state.value.waveform)
        _state.update { it.copy(histogram = enabled) }
    }
    override fun onToggleWaveform(enabled: Boolean) {
        engine.setAnalysis(_state.value.histogram, enabled)
        _state.update { it.copy(waveform = enabled) }
    }
    override fun onGridType(type: GridType) = _state.update { it.copy(grid = type) }
    override fun onToggleLevel(enabled: Boolean) {
        _state.update { it.copy(level = enabled) }
        mainHandler.removeCallbacks(levelTicker)
        if (enabled) mainHandler.post(levelTicker)
    }
    override fun onTogglePunchIn(enabled: Boolean) {
        engine.setPunchIn(enabled)
        _state.update { it.copy(punchIn = enabled) }
    }

    // ---- Drive ----
    override fun onTimer(timer: ShutterTimer) = _state.update { it.copy(timer = timer) }
    override fun onDriveMode(mode: DriveMode) {
        engine.setDriveMode(mode)
        _state.update { it.copy(driveMode = mode) }
    }
    override fun onIntervalSec(sec: Int) {
        engine.setIntervalSec(sec)
        _state.update { it.copy(intervalSec = sec) }
    }

    // ---- Shutter ----
    override fun onCapturePhoto() {
        if (_state.value.timerCountdownSec > 0) return // countdown already in progress; ignore re-tap
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
                    countdownRunnable = null
                    engine.capturePhoto(_state.value.photoFormats)
                } else {
                    _state.update { it.copy(timerCountdownSec = cur - 1) }
                    mainHandler.postDelayed(this, 1000)
                }
            }
        }
        countdownRunnable = tick
        mainHandler.postDelayed(tick, 1000)
    }

    private fun cancelCountdown() {
        countdownRunnable?.let { mainHandler.removeCallbacks(it) }
        countdownRunnable = null
        if (_state.value.timerCountdownSec != 0) _state.update { it.copy(timerCountdownSec = 0) }
    }

    override fun onToggleRecording() {
        cancelCountdown()
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

    override fun onToggleRememberSettings(enabled: Boolean) {
        settingsStore.rememberEnabled = enabled
        _state.update { it.copy(rememberSettings = enabled) }
        if (enabled) saveSettingsIfEnabled() // capture the current setup immediately
    }

    private inline fun updateControls(block: (ManualControls) -> ManualControls) {
        val updated = block(_state.value.controls)
        _state.update { it.copy(controls = updated) }
        pendingControls = updated
        mainHandler.removeCallbacks(applyControlsRunnable)
        mainHandler.postDelayed(applyControlsRunnable, 80)
    }

    // ---- Lifecycle ----
    fun onStart() = engine.resume()
    fun onStop() {
        // engine.pause() finalizes any in-flight recording; keep the UI in sync so we don't return
        // to a phantom "recording" state with the timer still ticking.
        if (_state.value.isRecording) {
            mainHandler.removeCallbacks(recordTicker)
            _state.update { it.copy(isRecording = false) }
        }
        saveSettingsIfEnabled() // persist on background so the next launch restores them
        engine.pause()
    }

    override fun onCleared() {
        mainHandler.removeCallbacksAndMessages(null)
        engine.release()
        // ViewModel.onCleared() is @EmptySuper (empty base impl) — do not call super (lint EmptySuperCall).
    }
}
