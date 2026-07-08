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
import com.hletrd.findx9tele.camera.AutoExposure
import com.hletrd.findx9tele.camera.ExposureMode
import com.hletrd.findx9tele.camera.ExposureStep
import com.hletrd.findx9tele.camera.effectiveExposureNs
import com.hletrd.findx9tele.camera.FlashMode
import com.hletrd.findx9tele.camera.FnSlot
import com.hletrd.findx9tele.camera.FocusMode
import com.hletrd.findx9tele.camera.GridType
import com.hletrd.findx9tele.camera.HardwareKeyAction
import com.hletrd.findx9tele.camera.LensChoice
import com.hletrd.findx9tele.camera.ManualControls
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
import com.hletrd.findx9tele.camera.ZebraLevel
import com.hletrd.findx9tele.focus.FocusMapping
import com.hletrd.findx9tele.storage.ExtraSettings
import com.hletrd.findx9tele.storage.MediaStoreWriter
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

    // Throttles engine.setControls() so rapid drags don't rebuild the repeating request per tick, but
    // still apply the LATEST value at a steady ~12 Hz WHILE the gesture continues (a plain debounce
    // starved: continuous pinch/drag kept resetting the timer, so zoom only landed after the finger
    // lifted). First change schedules an apply; changes within the window just refresh pendingControls.
    private var pendingControls: ManualControls? = null
    private var applyScheduled = false
    private val applyControlsRunnable = Runnable {
        applyScheduled = false
        pendingControls?.let { engine.setControls(it) }
        pendingControls = null
    }

    private var countdownRunnable: Runnable? = null

    // Auto-dismisses the transient status toast ("Saved" / "Video saved" / errors) so it doesn't hang
    // on screen forever (QA: "video saved" stuck). Each new message re-arms the 2 s timer.
    private val clearStatusRunnable = Runnable { _state.update { it.copy(statusMessage = null) } }

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
        engine.onStatus = { msg ->
            _state.update { it.copy(statusMessage = msg) }
            mainHandler.removeCallbacks(clearStatusRunnable)
            if (msg != null) mainHandler.postDelayed(clearStatusRunnable, 2000)
        }
        // caps/size arrive on the engine's setup thread; hop to main before touching the engine again.
        engine.onCapsReady = { caps -> _state.update { it.copy(caps = caps) }; mainHandler.post { reconcileFrameRate() } }
        engine.onVideoSizeChosen = { size -> _state.update { it.copy(videoResolution = size) }; mainHandler.post { reconcileFrameRate() } }
        engine.onAnalysis = { h, w ->
            _state.update { it.copy(histogramData = h, waveformData = w) }
            // Feed the app-side auto-exposure loop (SHUTTER/ISO priority). The luma array is freshly
            // allocated per callback, so it's safe to hand to the main thread. No-op in P/M.
            if (h != null) mainHandler.post { applyAutoExposure(h.luma) }
        }
        engine.onAudioLevel = { lvl -> _state.update { it.copy(audioLevel = lvl) } }
        // AE-resolved ISO/shutter (auto mode) for the live dial readout; camera thread → StateFlow is
        // thread-safe, Compose observes on main. The controller only fires this on change.
        engine.onExposureInfo = { iso, exp -> _state.update { it.copy(liveIso = iso, liveExposureNs = exp) } }
        // Live lens focus distance for the Focus chip readout + the AF→MF handoff seed (camera
        // thread → StateFlow is thread-safe, same as the exposure readout above).
        engine.onFocusDistance = { d -> _state.update { it.copy(liveFocusDiopters = d) } }
        // Last saved still → gallery thumbnail + in-app review (io thread → StateFlow is thread-safe).
        engine.onMediaSaved = { uri -> _state.update { it.copy(lastMediaUri = uri) } }
        restoreSettingsIfEnabled()
        _state.update { it.copy(savedMemorySlots = settingsStore.savedPresetSlots()) }
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
        applyLoaded(loaded, rememberSettings = true, activeSlot = null, status = null)
    }

    private fun applyLoaded(
        loaded: SettingsStore.Loaded,
        rememberSettings: Boolean? = null,
        activeSlot: MemorySlot? = null,
        status: String? = null,
    ) {
        val c = loaded.controls
        val e = loaded.extras
        // Push to the engine (safe pre-start: these set @Volatile fields read when the camera opens).
        engine.setControls(c)
        // If a priority mode was restored, arm the app-side AE luma metering so it drives on launch.
        engine.setAeMetering(c.exposureMode == ExposureMode.SHUTTER || c.exposureMode == ExposureMode.ISO)
        engine.setLens(e.lens)
        engine.setTransfer(e.transfer)
        engine.setTeleconverterMode(e.teleconverter)
        engine.setVideoStabMode(e.videoStabMode)
        engine.setAspectRatio(e.aspectRatio)
        engine.setVideoCodec(e.videoCodec)
        engine.setBitrateLevel(e.bitrateLevel)
        engine.setOpenGate(e.openGate)
        engine.setAudioScene(e.audioScene)
        engine.setVideoFrameRate(e.videoFrameRate)
        _state.update {
            it.copy(
                rememberSettings = rememberSettings ?: it.rememberSettings,
                controls = c,
                transfer = e.transfer,
                photoFormats = PhotoFormats(e.heif, e.jpeg, e.dngRaw),
                mode = e.mode,
                lens = e.lens,
                teleconverterMode = e.teleconverter,
                videoStabMode = e.videoStabMode,
                aspectRatio = e.aspectRatio,
                grid = e.grid,
                videoCodec = e.videoCodec,
                bitrateLevel = e.bitrateLevel,
                videoFrameRate = e.videoFrameRate,
                openGate = e.openGate,
                audioScene = e.audioScene,
                fnSlots = e.fnSlots,
                myMenuSlots = e.myMenuSlots,
                volumeKeyAction = e.volumeKeyAction,
                halfPressAction = e.halfPressAction,
                activeMemorySlot = activeSlot,
                statusMessage = status,
            )
        }
        if (status != null) {
            mainHandler.removeCallbacks(clearStatusRunnable)
            mainHandler.postDelayed(clearStatusRunnable, 2000)
        }
    }

    private fun currentExtras(): ExtraSettings = _state.value.let { s ->
        ExtraSettings(
            transfer = s.transfer,
            heif = s.photoFormats.heif,
            jpeg = s.photoFormats.jpeg,
            dngRaw = s.photoFormats.dngRaw,
            mode = s.mode,
            lens = s.lens,
            teleconverter = s.teleconverterMode,
            videoStabMode = s.videoStabMode,
            aspectRatio = s.aspectRatio,
            grid = s.grid,
            videoCodec = s.videoCodec,
            bitrateLevel = s.bitrateLevel,
            videoFrameRate = s.videoFrameRate,
            openGate = s.openGate,
            audioScene = s.audioScene,
            fnSlots = s.fnSlots,
            myMenuSlots = s.myMenuSlots,
            volumeKeyAction = s.volumeKeyAction,
            halfPressAction = s.halfPressAction,
        )
    }

    private fun saveSettingsIfEnabled() {
        if (_state.value.rememberSettings) settingsStore.save(_state.value.controls, currentExtras())
    }

    private fun showStatus(message: String) {
        _state.update { it.copy(statusMessage = message) }
        mainHandler.removeCallbacks(clearStatusRunnable)
        mainHandler.postDelayed(clearStatusRunnable, 2000)
    }

    private fun markChanged(slot: FnSlot) {
        _state.update { s ->
            val recent = (listOf(slot) + s.recentSettingSlots.filterNot { it == slot }).take(RECENT_SETTING_LIMIT)
            s.copy(recentSettingSlots = recent, activeMemorySlot = null)
        }
    }

    private fun normalizedSlots(slots: List<FnSlot>, fallback: List<FnSlot>): List<FnSlot> =
        slots.distinct().take(FN_SLOT_LIMIT).ifEmpty { fallback }

    // ---- Preview surface ----
    override fun onPreviewSurfaceAvailable(surface: Surface, width: Int, height: Int) =
        engine.onPreviewSurfaceAvailable(surface, width, height)
    override fun onPreviewSurfaceChanged(width: Int, height: Int) = engine.onPreviewSurfaceChanged(width, height)
    override fun onPreviewSurfaceDestroyed() = engine.onPreviewSurfaceDestroyed()

    // ---- Focus ----
    override fun onFocusMode(mode: FocusMode) = updateControls(FnSlot.FOCUS) { c ->
        // AF→MF handoff: entering MANUAL seeds the slider from the LIVE lens position, so fine
        // focus starts from AF's solution (near ∞ through the afocal converter) instead of a stale
        // or 0-diopter value — the workflow is "AF once, then trim by hand".
        val live = _state.value.liveFocusDiopters
        val min = _state.value.caps?.minFocusDistanceDiopters ?: 0f
        if (mode == FocusMode.MANUAL && c.focusMode != FocusMode.MANUAL && live != null && min > 0f) {
            c.copy(focusMode = mode, focusDistanceDiopters = live.coerceIn(0f, min))
        } else {
            c.copy(focusMode = mode)
        }
    }
    override fun onFocusSlider(slider: Float) {
        val min = _state.value.caps?.minFocusDistanceDiopters ?: 0f
        val d = FocusMapping.sliderToDiopters(slider, min)
        updateControls(FnSlot.FOCUS) { it.copy(focusDistanceDiopters = d, focusMode = FocusMode.MANUAL) }
    }
    override fun onAfLock(locked: Boolean) = updateControls(FnSlot.FOCUS) { it.copy(afLock = locked) }
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
    // Dragging the ISO dial only makes sense when the user owns ISO (ISO/MANUAL). If ISO is currently
    // auto (PROGRAM or SHUTTER), taking manual control of it drops to MANUAL.
    override fun onIso(iso: Int) = updateControls(FnSlot.ISO) {
        val mode = if (it.autoExposure || it.autoIsoDriven) ExposureMode.MANUAL else it.exposureMode
        it.copy(iso = iso, exposureMode = mode)
    }
    // Likewise for shutter: if the shutter is currently auto (PROGRAM or ISO), taking it over → MANUAL.
    override fun onShutterNs(ns: Long) = updateControls(FnSlot.SHUTTER) {
        val mode = if (it.autoExposure || it.autoShutterDriven) ExposureMode.MANUAL else it.exposureMode
        it.copy(exposureTimeNs = ns, exposureMode = mode)
    }
    override fun onExposureCompensation(ev: Int) = updateControls(FnSlot.EV) { it.copy(exposureCompensation = ev) }
    override fun onExposureMode(mode: ExposureMode) {
        val live = _state.value
        updateControls(FnSlot.EXPOSURE_MODE) {
            // ISO priority auto-drives the shutter as a plain exposure time, so force SPEED — an ANGLE
            // derivation would override the value the AE loop writes into exposureTimeNs.
            val shutterMode = if (mode == ExposureMode.ISO) ShutterMode.SPEED else it.shutterMode
            // Smooth handoff out of PROGRAM: seed the now-user-owned ISO/shutter from the HAL AE's last
            // resolved values so S/ISO/M start correctly exposed instead of jumping to stale defaults.
            val fromProgram = it.exposureMode == ExposureMode.PROGRAM
            val iso = if (fromProgram) (live.liveIso ?: it.iso) else it.iso
            val exp = if (fromProgram) (live.liveExposureNs ?: it.exposureTimeNs) else it.exposureTimeNs
            it.copy(exposureMode = mode, shutterMode = shutterMode, iso = iso, exposureTimeNs = exp)
        }
        // Force the GL luma readback on while a priority mode needs to meter; off otherwise.
        engine.setAeMetering(mode == ExposureMode.SHUTTER || mode == ExposureMode.ISO)
    }
    // Legacy binary toggle (kept for any caller): Auto→PROGRAM, Manual→MANUAL.
    override fun onToggleAutoExposure(auto: Boolean) =
        updateControls(FnSlot.EXPOSURE_MODE) { it.copy(exposureMode = if (auto) ExposureMode.PROGRAM else ExposureMode.MANUAL) }
    override fun onToggleAeLock(locked: Boolean) = updateControls(FnSlot.EXPOSURE_MODE) { it.copy(aeLock = locked) }
    override fun onAntibanding(mode: Antibanding) = updateControls { it.copy(antibanding = mode) }
    override fun onFps(fps: Int) = updateControls { it.copy(fps = fps) }
    override fun onShutterMode(mode: ShutterMode) = updateControls(FnSlot.SHUTTER) { it.copy(shutterMode = mode) }
    override fun onShutterAngle(angle: Float) = updateControls(FnSlot.SHUTTER) {
        val mode = if (it.autoExposure || it.autoShutterDriven) ExposureMode.MANUAL else it.exposureMode
        it.copy(shutterAngle = angle, shutterMode = ShutterMode.ANGLE, exposureMode = mode)
    }
    override fun onExposureStep(step: ExposureStep) = updateControls { it.copy(exposureStep = step) }

    // ---- White balance ----
    override fun onWbMode(mode: WbMode) = updateControls(FnSlot.WB) { it.copy(wbMode = mode) }
    override fun onWbKelvin(kelvin: Int) = updateControls(FnSlot.WB) { it.copy(wbKelvin = kelvin, wbMode = WbMode.MANUAL) }
    override fun onWbTint(tint: Int) = updateControls(FnSlot.WB) { it.copy(wbTint = tint, wbMode = WbMode.MANUAL) }
    override fun onToggleAwbLock(locked: Boolean) = updateControls(FnSlot.WB) { it.copy(awbLock = locked) }
    override fun onMeteringMode(mode: MeteringMode) = updateControls(FnSlot.METERING) { it.copy(meteringMode = mode) }

    // ---- Processing ----
    override fun onEdge(level: ProcessingLevel) = updateControls { it.copy(edge = level) }
    override fun onNoiseReduction(level: ProcessingLevel) = updateControls { it.copy(noiseReduction = level) }
    override fun onColorEffect(effect: ColorEffect) = updateControls { it.copy(colorEffect = effect) }

    // ---- Optics / output ----
    override fun onFlash(mode: FlashMode) = updateControls { it.copy(flash = mode) }
    override fun onToggleOis(enabled: Boolean) = updateControls(FnSlot.STABILIZATION) { it.copy(oisEnabled = enabled) }
    override fun onZoomRatio(ratio: Float) = updateControls(FnSlot.ZOOM) { it.copy(zoomRatio = ratio) }
    override fun onPinchZoom(factor: Float) {
        // Pinch multiplies the current zoom ratio, clamped to the lens's advertised range; reuses the
        // debounced onZoomRatio path so the in-sheet Zoom slider and the viewfinder pinch stay in sync.
        val range = _state.value.caps?.zoomRatioRange ?: return
        val next = (_state.value.controls.zoomRatio * factor).coerceIn(range.lower, range.upper)
        onZoomRatio(next)
    }
    override fun onJpegQuality(quality: Int) = updateControls { it.copy(jpegQuality = quality) }

    // ---- Modes ----
    override fun onModeChange(mode: CaptureMode) {
        cancelCountdown()
        // Leaving Video mode while recording would orphan the clip (the shutter's stop affordance is
        // gone). Stop and save it first so the mode switch can't strand an in-progress recording.
        if (_state.value.isRecording && mode != CaptureMode.VIDEO) onToggleRecording()
        _state.update { it.copy(mode = mode) }
        markChanged(if (mode == CaptureMode.VIDEO) FnSlot.TRANSFER else FnSlot.EXPOSURE_MODE)
        // Persist the mode the instant it changes, not just on onStop: swiping the app from Recents
        // can kill the process before onStop's async prefs write flushes, which is why "last mode"
        // seemed not to stick. Writing here means the mode is already on disk well before any kill.
        saveSettingsIfEnabled()
    }
    override fun onTransfer(transfer: ColorTransfer) {
        engine.setTransfer(transfer)
        _state.update { it.copy(transfer = transfer) }
        markChanged(FnSlot.TRANSFER)
    }
    override fun onSetPhotoFormats(formats: PhotoFormats) = _state.update { it.copy(photoFormats = formats, activeMemorySlot = null) }
    override fun onAspectRatio(ratio: AspectRatio) {
        engine.setAspectRatio(ratio)
        _state.update { it.copy(aspectRatio = ratio, activeMemorySlot = null) }
    }
    override fun onToggleRecordAudio(enabled: Boolean) = _state.update { it.copy(recordAudio = enabled, activeMemorySlot = null) }
    override fun onAudioGain(gain: Float) {
        engine.setAudioGain(gain)
        _state.update { it.copy(audioGain = gain, activeMemorySlot = null) }
    }
    override fun onAudioScene(scene: com.hletrd.findx9tele.camera.AudioScene) {
        engine.setAudioScene(scene)
        _state.update { it.copy(audioScene = scene) }
        markChanged(FnSlot.AUDIO_SCENE)
    }
    override fun onToggleTeleconverter(enabled: Boolean) {
        // The teleconverter is only meaningful on the 3× periscope. Turning it ON from any other lens
        // switches to 3× (which itself bundles teleconverter mode on, one reopen); onLens keeps the
        // lens picker + TELE chips in sync. Turning it OFF just clears the afocal flip on the 3× lens.
        if (enabled && _state.value.lens != com.hletrd.findx9tele.camera.LensChoice.TELE3X) {
            onLens(com.hletrd.findx9tele.camera.LensChoice.TELE3X)
            return
        }
        engine.setTeleconverterMode(enabled)
        _state.update { it.copy(teleconverterMode = enabled) }
        markChanged(FnSlot.TELECONVERTER)
    }
    override fun onLens(choice: LensChoice) {
        // Bundled in the engine: resolves the lens id + flips teleconverter mode (3× on, else off)
        // in one reopen. Mirror both into UI state so the picker and the TELE chip stay in sync.
        engine.setLens(choice)
        _state.update { it.copy(lens = choice, teleconverterMode = choice.isTeleconverterLens) }
        markChanged(FnSlot.TELECONVERTER)
    }
    override fun onVideoCodec(codec: VideoCodec) {
        engine.setVideoCodec(codec)
        _state.update { it.copy(videoCodec = codec, activeMemorySlot = null) }
        reconcileFrameRate()
    }
    override fun onBitrateLevel(level: BitrateLevel) {
        engine.setBitrateLevel(level)
        _state.update { it.copy(bitrateLevel = level, activeMemorySlot = null) }
    }
    override fun onVideoResolution(size: Size) {
        engine.setVideoResolution(size)
        _state.update { it.copy(videoResolution = size, activeMemorySlot = null) }
        reconcileFrameRate()
    }
    override fun onVideoFrameRate(rate: VideoFrameRate) {
        engine.setVideoFrameRate(rate)
        // Keep the exposure fps in step so the AE target-fps range, cine shutter angle and sensor
        // frame duration follow the selected video rate (drop-frame rates use their rounded parent).
        val controls = _state.value.controls.copy(fps = rate.fps)
        engine.setControls(controls)
        _state.update { it.copy(videoFrameRate = rate, controls = controls, activeMemorySlot = null) }
    }
    override fun onToggleOpenGate(enabled: Boolean) {
        engine.setOpenGate(enabled)
        _state.update { it.copy(openGate = enabled, activeMemorySlot = null) }
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
    override fun onVideoStabMode(mode: com.hletrd.findx9tele.camera.VideoStabMode) {
        engine.setVideoStabMode(mode)
        _state.update { it.copy(videoStabMode = mode) }
        markChanged(FnSlot.STABILIZATION)
    }

    // ---- Assists ----
    override fun onTogglePeaking(enabled: Boolean) {
        engine.setPeaking(enabled)
        _state.update { it.copy(focusPeaking = enabled) }
        markChanged(FnSlot.PEAKING)
    }
    override fun onPeakingLevel(level: PeakingLevel) {
        engine.setPeakingLevel(level)
        _state.update { it.copy(peakingLevel = level) }
        markChanged(FnSlot.PEAKING)
    }
    override fun onPeakingColor(color: PeakingColor) {
        engine.setPeakingColor(color)
        _state.update { it.copy(peakingColor = color) }
        markChanged(FnSlot.PEAKING)
    }
    override fun onToggleZebra(enabled: Boolean) {
        engine.setZebra(enabled)
        _state.update { it.copy(zebra = enabled) }
        markChanged(FnSlot.ZEBRA)
    }
    override fun onZebraLevel(level: ZebraLevel) {
        engine.setZebraLevel(level)
        _state.update { it.copy(zebraLevel = level) }
        markChanged(FnSlot.ZEBRA)
    }
    override fun onToggleFalseColor(enabled: Boolean) {
        engine.setFalseColor(enabled)
        _state.update { it.copy(falseColor = enabled, activeMemorySlot = null) }
    }
    override fun onToggleHistogram(enabled: Boolean) {
        engine.setAnalysis(enabled, _state.value.waveform)
        _state.update { it.copy(histogram = enabled, activeMemorySlot = null) }
    }
    override fun onToggleWaveform(enabled: Boolean) {
        engine.setAnalysis(_state.value.histogram, enabled)
        _state.update { it.copy(waveform = enabled, activeMemorySlot = null) }
    }
    override fun onGridType(type: GridType) {
        _state.update { it.copy(grid = type) }
        markChanged(FnSlot.GRID)
    }
    override fun onToggleLevel(enabled: Boolean) {
        _state.update { it.copy(level = enabled) }
        mainHandler.removeCallbacks(levelTicker)
        if (enabled) mainHandler.post(levelTicker)
        markChanged(FnSlot.LEVEL)
    }
    override fun onTogglePunchIn(enabled: Boolean) {
        engine.setPunchIn(enabled)
        _state.update { it.copy(punchIn = enabled) }
        markChanged(FnSlot.PUNCH_IN)
    }

    // ---- Drive ----
    override fun onTimer(timer: ShutterTimer) = _state.update { it.copy(timer = timer, activeMemorySlot = null) }
    override fun onDriveMode(mode: DriveMode) {
        engine.setDriveMode(mode)
        _state.update { it.copy(driveMode = mode) }
        markChanged(FnSlot.DRIVE)
    }
    override fun onIntervalSec(sec: Int) {
        engine.setIntervalSec(sec)
        _state.update { it.copy(intervalSec = sec) }
        markChanged(FnSlot.DRIVE)
    }

    // ---- Shutter ----
    /** Hardware full-press key: defaults to shutter/REC, but can be reassigned in Advanced. */
    fun onHardwareFullKey(active: Boolean) {
        performHardwareAction(_state.value.volumeKeyAction, active)
    }

    /** Backward-compatible entry for existing callers: one-shot full press. */
    fun onHardwareShutter() = onHardwareFullKey(active = true)

    override fun onHardwareHalfPress(active: Boolean) {
        _state.update { it.copy(halfPressActive = active) }
        performHardwareAction(_state.value.halfPressAction, active)
    }

    private fun performHardwareAction(action: HardwareKeyAction, active: Boolean) {
        when (action) {
            HardwareKeyAction.SHUTTER -> if (active) {
                if (_state.value.mode == CaptureMode.PHOTO) onCapturePhoto() else onToggleRecording()
            }
            HardwareKeyAction.AF_ON -> if (active) onTapFocus(0.5f, 0.5f)
            HardwareKeyAction.AEL -> onToggleAeLock(active)
            HardwareKeyAction.PUNCH_IN -> onTogglePunchIn(active)
            HardwareKeyAction.ZOOM_IN -> if (active) onPinchZoom(HARDWARE_ZOOM_STEP)
            HardwareKeyAction.ZOOM_OUT -> if (active) onPinchZoom(1f / HARDWARE_ZOOM_STEP)
            HardwareKeyAction.NONE -> Unit
        }
    }

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

    override fun onSetFnSlots(slots: List<FnSlot>) {
        val normalized = normalizedSlots(slots, FnSlot.DEFAULT)
        _state.update { it.copy(fnSlots = normalized, activeMemorySlot = null) }
        saveSettingsIfEnabled()
    }

    override fun onSetMyMenuSlots(slots: List<FnSlot>) {
        val normalized = normalizedSlots(slots, FnSlot.MY_MENU_DEFAULT)
        _state.update { it.copy(myMenuSlots = normalized, activeMemorySlot = null) }
        saveSettingsIfEnabled()
    }

    override fun onStoreMemorySlot(slot: MemorySlot) {
        settingsStore.savePreset(slot, _state.value.controls, currentExtras())
        _state.update { it.copy(savedMemorySlots = settingsStore.savedPresetSlots(), activeMemorySlot = slot) }
        showStatus("${slot.label} saved")
    }

    override fun onRecallMemorySlot(slot: MemorySlot) {
        if (_state.value.isRecording) {
            showStatus("Stop recording before recalling ${slot.label}")
            return
        }
        val loaded = settingsStore.loadPreset(slot)
        if (loaded == null) {
            showStatus("${slot.label} is empty")
            return
        }
        applyLoaded(loaded, activeSlot = slot, status = "${slot.label} recalled")
    }

    override fun onVolumeKeyAction(action: HardwareKeyAction) {
        _state.update { it.copy(volumeKeyAction = action, activeMemorySlot = null) }
        saveSettingsIfEnabled()
    }

    override fun onHalfPressAction(action: HardwareKeyAction) {
        _state.update { it.copy(halfPressAction = action, activeMemorySlot = null) }
        saveSettingsIfEnabled()
    }

    override fun onDeleteLastMedia() {
        val uri = _state.value.lastMediaUri ?: return
        MediaStoreWriter.delete(getApplication(), uri)
        _state.update { it.copy(lastMediaUri = null) }
        showStatus("Deleted")
    }

    private fun updateControls(slot: FnSlot? = null, block: (ManualControls) -> ManualControls) {
        val updated = block(_state.value.controls)
        _state.update { it.copy(controls = updated) }
        slot?.let(::markChanged)
        pendingControls = updated
        // Trailing throttle: apply at most every 80 ms with the newest value, but DON'T cancel a
        // pending apply — so a sustained gesture keeps landing updates live instead of only at the end.
        if (!applyScheduled) {
            applyScheduled = true
            mainHandler.postDelayed(applyControlsRunnable, 80)
        }
    }

    /**
     * App-side auto-exposure for SHUTTER/ISO priority: meter the preview luma and nudge the free
     * variable toward the EV-shifted mid-grey target. Writes the driven value WITHOUT changing the
     * mode (unlike the user-facing onIso/onShutterNs, which take manual control). Main thread.
     */
    private fun applyAutoExposure(luma: IntArray) {
        val s = _state.value
        val caps = s.caps ?: return
        val c = s.controls
        val evStep = caps.evStep.let {
            if (it.denominator == 0) 1f / 3f else it.numerator.toFloat() / it.denominator.toFloat()
        }
        val evStops = c.exposureCompensation * evStep
        when (c.exposureMode) {
            ExposureMode.SHUTTER ->
                AutoExposure.driveIso(luma, c.iso, caps.isoRange, evStops)?.let { newIso ->
                    updateControls { it.copy(iso = newIso) }
                }
            ExposureMode.ISO ->
                AutoExposure.driveShutterNs(luma, c.effectiveExposureNs(), caps.exposureTimeRange, evStops)?.let { newNs ->
                    updateControls { it.copy(exposureTimeNs = newNs) }
                }
            else -> Unit
        }
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

    private companion object {
        const val RECENT_SETTING_LIMIT = 6
        const val FN_SLOT_LIMIT = 8
        const val HARDWARE_ZOOM_STEP = 1.15f
    }
}
