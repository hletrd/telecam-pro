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
import com.hletrd.findx9tele.camera.AudioInputPreference
import com.hletrd.findx9tele.camera.BitrateLevel
import com.hletrd.findx9tele.camera.CameraEngine
import com.hletrd.findx9tele.camera.CameraUiState
import com.hletrd.findx9tele.camera.CaptureMode
import com.hletrd.findx9tele.camera.ColorEffect
import com.hletrd.findx9tele.camera.ColorTransfer
import com.hletrd.findx9tele.camera.DriveMode
import com.hletrd.findx9tele.camera.AfSpotSize
import com.hletrd.findx9tele.camera.AutoExposure
import com.hletrd.findx9tele.camera.ExposureMode
import com.hletrd.findx9tele.camera.ExposureStep
import com.hletrd.findx9tele.camera.FrameLineType
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
import com.hletrd.findx9tele.video.AudioInputInspector
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

    // Battery % + free storage for the OSD info pill, Sony-style. Slow tick — these move slowly.
    private val infoTicker = object : Runnable {
        override fun run() {
            _state.update { it.copy(batteryPct = readBatteryPct(), freeBytes = readFreeBytes()) }
            mainHandler.postDelayed(this, 10_000)
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
        engine.onAudioRoute = { route -> _state.update { it.copy(audioRouteLabel = route) } }
        // AE-resolved ISO/shutter (auto mode) for the live dial readout; camera thread → StateFlow is
        // thread-safe, Compose observes on main. The controller only fires this on change.
        engine.onExposureInfo = { iso, exp -> _state.update { it.copy(liveIso = iso, liveExposureNs = exp) } }
        // Live lens focus distance for the Focus chip readout + the AF→MF handoff seed (camera
        // thread → StateFlow is thread-safe, same as the exposure readout above).
        engine.onFocusDistance = { d -> _state.update { it.copy(liveFocusDiopters = d) } }
        // Last saved still → gallery thumbnail + in-app review (io thread → StateFlow is thread-safe).
        engine.onMediaSaved = { uri -> _state.update { it.copy(lastMediaUri = uri) } }
        restoreSettingsIfEnabled()
        refreshProgramAppSide()
        // Seed the review thumbnail with the newest photo this app previously saved, so "last shot"
        // works on a fresh launch instead of only after the first capture of the session (feedback).
        Thread {
            val seeded = com.hletrd.findx9tele.storage.MediaStoreWriter.latestOwnImage(getApplication())
            if (seeded != null) _state.update { if (it.lastMediaUri == null) it.copy(lastMediaUri = seeded) else it }
        }.start()
        refreshMemorySlotInfo()
        // Sweep any pending media orphaned by a prior crash/force-kill (record stop never ran).
        engine.cleanupOrphans()
        if (_state.value.level) mainHandler.post(levelTicker)
        mainHandler.post(orientationTicker)
        mainHandler.post(infoTicker)
        refreshStandbyAudioMeter()
    }

    /** On launch, restore persisted pro settings (if the user enabled "Remember settings"). */
    private fun restoreSettingsIfEnabled() {
        if (!settingsStore.rememberEnabled) {
            _state.update { it.copy(rememberSettings = false) }
            return
        }
        val loaded = settingsStore.load()
        if (loaded == null) { _state.update { it.copy(rememberSettings = true) }; return }
        applyLoaded(loaded, rememberSettings = true, activeSlot = null, status = null, honorPreserveOptions = true)
    }

    private fun applyLoaded(
        loaded: SettingsStore.Loaded,
        rememberSettings: Boolean? = null,
        activeSlot: MemorySlot? = null,
        status: String? = null,
        honorPreserveOptions: Boolean = false,
    ) {
        val c = loaded.controls
        val e = loaded.extras
        val defaults = CameraUiState()
        val preservedTeleconverter =
            if (honorPreserveOptions && !e.preserveTeleconverter) defaults.teleconverterMode else e.teleconverter
        val restoredLens = when {
            preservedTeleconverter -> LensChoice.TELE3X
            honorPreserveOptions && !e.preserveLensSelection -> defaults.lens
            else -> e.lens
        }
        val restoredTeleconverter = restoredLens == LensChoice.TELE3X && preservedTeleconverter
        // Push to the engine (safe pre-start: these set @Volatile fields read when the camera opens).
        engine.setVideoMode(e.mode == CaptureMode.VIDEO)
        engine.setControls(c)
        // Manual/priority modes need luma analysis even when scopes are hidden: priority AE drives
        // from it, and full manual uses it for the live exposure meter.
        engine.setAeMetering(usesExposureAnalysis(c))
        // Pass the resolved teleconverter state directly so a "preserve lens but not TELE" restore
        // (independent toggles, e.g. lens=3× with the converter not preserved as attached) reopens the
        // camera once instead of setLens bundling TELE on and a separate setTeleconverterMode call
        // immediately correcting it back off.
        engine.setLens(restoredLens, restoredTeleconverter)
        applyEngineTransfer(e.mode, e.transfer)
        engine.setGammaAssist(e.gammaAssist)
        engine.setVideoStabMode(e.videoStabMode)
        engine.setAspectRatio(e.aspectRatio)
        engine.setVideoCodec(e.videoCodec)
        engine.setBitrateLevel(e.bitrateLevel)
        engine.setOpenGate(e.openGate)
        engine.setAudioGain(e.audioGain)
        engine.setAudioScene(e.audioScene)
        engine.setAudioInputPreference(e.audioInputPreference)
        engine.setVideoFrameRate(e.videoFrameRate)
        _state.update {
            it.copy(
                rememberSettings = rememberSettings ?: it.rememberSettings,
                controls = c,
                transfer = e.transfer,
                photoFormats = PhotoFormats(e.heif, e.jpeg, e.dngRaw),
                mode = e.mode,
                lens = restoredLens,
                teleconverterMode = restoredTeleconverter,
                videoStabMode = e.videoStabMode,
                aspectRatio = e.aspectRatio,
                grid = e.grid,
                videoCodec = e.videoCodec,
                bitrateLevel = e.bitrateLevel,
                videoFrameRate = e.videoFrameRate,
                openGate = e.openGate,
                recordAudio = e.recordAudio,
                audioGain = e.audioGain,
                audioScene = e.audioScene,
                gammaAssist = e.gammaAssist,
                frameLines = e.frameLines,
                audioInputPreference = e.audioInputPreference,
                audioRouteLabel = audioInputStatusLabel(e.audioInputPreference),
                photoFnSlots = e.photoFnSlots,
                videoFnSlots = e.videoFnSlots,
                myMenuSlots = e.myMenuSlots,
                volumeKeyAction = e.volumeKeyAction,
                halfPressAction = e.halfPressAction,
                preserveLensSelection = if (honorPreserveOptions) e.preserveLensSelection else it.preserveLensSelection,
                preserveTeleconverter = if (honorPreserveOptions) e.preserveTeleconverter else it.preserveTeleconverter,
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
            recordAudio = s.recordAudio,
            audioGain = s.audioGain,
            audioScene = s.audioScene,
            audioInputPreference = s.audioInputPreference,
            photoFnSlots = s.photoFnSlots,
            videoFnSlots = s.videoFnSlots,
            myMenuSlots = s.myMenuSlots,
            volumeKeyAction = s.volumeKeyAction,
            halfPressAction = s.halfPressAction,
            gammaAssist = s.gammaAssist,
            frameLines = s.frameLines,
            preserveLensSelection = s.preserveLensSelection,
            preserveTeleconverter = s.preserveTeleconverter,
        )
    }

    private fun saveSettingsIfEnabled() {
        if (_state.value.rememberSettings) settingsStore.save(_state.value.controls, currentExtras())
    }

    private fun readBatteryPct(): Int = runCatching {
        val bm = getApplication<Application>().getSystemService(android.content.Context.BATTERY_SERVICE)
            as android.os.BatteryManager
        bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }.getOrDefault(-1)

    private fun readFreeBytes(): Long = runCatching {
        android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path).availableBytes
    }.getOrDefault(-1L)

    /** Sony-style standby level check: meter the mic whenever video mode is armed but not rolling. */
    private fun refreshStandbyAudioMeter() {
        val s = _state.value
        engine.setStandbyAudioMonitor(s.mode == CaptureMode.VIDEO && s.recordAudio && !s.isRecording)
    }

    private fun applyEngineTransfer(
        mode: CaptureMode = _state.value.mode,
        transfer: ColorTransfer = _state.value.transfer,
    ) {
        // Gamma/Log monitoring is a VIDEO concern. Keeping O-Log selected for the next clip must not
        // make the still-photo viewfinder look flat/log.
        engine.setTransfer(if (mode == CaptureMode.VIDEO) transfer else ColorTransfer.SDR)
    }

    private fun showStatus(message: String) {
        _state.update { it.copy(statusMessage = message) }
        mainHandler.removeCallbacks(clearStatusRunnable)
        mainHandler.postDelayed(clearStatusRunnable, 2000)
    }

    private fun rejectIfRecording(message: String): Boolean {
        if (!_state.value.isRecording) return false
        showStatus(message)
        return true
    }

    fun onAppStatus(message: String) = showStatus(message)

    private fun usesExposureAnalysis(c: ManualControls): Boolean =
        c.exposureMode != ExposureMode.PROGRAM || c.programAppSide

    /**
     * PROGRAM runs app-side for STILLS — the auto min-shutter (1/focal rule) + Auto ISO a real P mode
     * gives, which the HAL AE cannot (no min-shutter hint → 1/30 s blur at 300 mm). The HAL AE keeps
     * video PROGRAM (its shutter conventions are frame-rate driven) and any flash-metered PROGRAM
     * (AUTO/ON flash metering only exists with AE ON).
     */
    private fun programShouldRunAppSide(mode: CaptureMode, flash: FlashMode): Boolean =
        mode == CaptureMode.PHOTO && flash != FlashMode.AUTO && flash != FlashMode.ON

    /** Recomputes [ManualControls.programAppSide] after mode/flash changes, seeding a smooth handoff. */
    private fun refreshProgramAppSide() {
        val live = _state.value
        val want = programShouldRunAppSide(live.mode, live.controls.flash)
        if (live.controls.programAppSide == want) return
        updateControls {
            if (want && it.exposureMode == ExposureMode.PROGRAM) {
                // HAL AE → app-side handoff: seed from the AE's last resolved values so exposure
                // doesn't jump, and force SPEED so the loop's exposureTimeNs is what the request uses.
                it.copy(
                    programAppSide = true,
                    shutterMode = ShutterMode.SPEED,
                    iso = live.liveIso ?: it.iso,
                    exposureTimeNs = live.liveExposureNs ?: it.exposureTimeNs,
                )
            } else {
                it.copy(programAppSide = want)
            }
        }
    }

    /** Handheld-safe shutter target for app-side PROGRAM: the 1/(35mm-equivalent focal) rule. */
    private fun preferredProgramShutterNs(s: CameraUiState): Long {
        val eff = s.lens.targetEquivMm * (if (s.teleconverterMode) 300f / 70f else 1f) // afocal TC ≈ 4.286×
        return (1_000_000_000f / eff.coerceAtLeast(1f)).toLong()
    }

    private fun audioInputStatus(preference: AudioInputPreference = _state.value.audioInputPreference) =
        AudioInputInspector.status(getApplication(), preference)

    private fun audioInputStatusLabel(preference: AudioInputPreference = _state.value.audioInputPreference): String =
        audioInputStatus(preference).label

    private fun refreshAudioInputStatus() {
        if (!_state.value.isRecording) {
            _state.update { it.copy(audioRouteLabel = audioInputStatusLabel(it.audioInputPreference)) }
        }
    }

    private fun refreshMemorySlotInfo(activeSlot: MemorySlot? = _state.value.activeMemorySlot) {
        val info = settingsStore.savedPresetInfo()
        _state.update {
            it.copy(
                savedMemorySlots = info.keys,
                memorySlotNames = info.mapValues { entry -> entry.value.name },
                memorySlotSummaries = info.mapValues { entry -> entry.value.summary },
                activeMemorySlot = activeSlot,
            )
        }
    }

    private fun presetNameFor(s: CameraUiState): String {
        val focal = focalSummary(s)
        return when (s.mode) {
            CaptureMode.PHOTO -> "Photo $focal"
            CaptureMode.VIDEO -> "Video ${transferSummary(s.transfer)}"
        }
    }

    private fun presetSummaryFor(s: CameraUiState): String = when (s.mode) {
        CaptureMode.PHOTO -> {
            val formats = buildList {
                if (s.photoFormats.heif) add("HEIF")
                if (s.photoFormats.jpeg) add("JPEG")
                if (s.photoFormats.dngRaw) add("DNG")
            }.ifEmpty { listOf("No still format") }.joinToString("+")
            "${focalSummary(s)} · ${s.controls.exposureMode.letter} · $formats"
        }
        CaptureMode.VIDEO -> {
            "${focalSummary(s)} · ${videoSizeSummary(s.videoResolution)} ${s.videoFrameRate.label}p · " +
                "${transferSummary(s.transfer)} · ${s.bitrateLevel.name.lowercase().replaceFirstChar { it.uppercase() }}"
        }
    }

    private fun focalSummary(s: CameraUiState): String =
        if (s.teleconverterMode) "300mm" else "${s.lens.targetEquivMm.toInt()}mm"

    private fun transferSummary(t: ColorTransfer): String = when (t) {
        ColorTransfer.HLG -> "HLG"
        ColorTransfer.LOG -> "O-Log"
        ColorTransfer.SDR -> "SDR"
    }

    private fun videoSizeSummary(size: Size): String = when {
        size.height >= 4320 -> "8K"
        size.height >= 2160 -> "4K"
        size.height >= 1440 -> "1440p"
        size.height >= 1080 -> "1080p"
        else -> "${size.width}x${size.height}"
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
        val mode = if (it.exposureMode == ExposureMode.PROGRAM || it.autoIsoDriven) ExposureMode.MANUAL else it.exposureMode
        it.copy(iso = iso, exposureMode = mode)
    }
    // Likewise for shutter: if the shutter is currently auto (PROGRAM or ISO), taking it over → MANUAL.
    override fun onShutterNs(ns: Long) = updateControls(FnSlot.SHUTTER) {
        val mode = if (it.exposureMode == ExposureMode.PROGRAM || it.autoShutterDriven) ExposureMode.MANUAL else it.exposureMode
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
        // Entering/leaving PROGRAM may flip the app-side flag (photo P is app-side, video P is HAL).
        refreshProgramAppSide()
    }
    // Legacy binary toggle (kept for any caller): Auto→PROGRAM, Manual→MANUAL.
    override fun onToggleAutoExposure(auto: Boolean) {
        val mode = if (auto) ExposureMode.PROGRAM else ExposureMode.MANUAL
        updateControls(FnSlot.EXPOSURE_MODE) { it.copy(exposureMode = mode) }
        refreshProgramAppSide()
    }
    override fun onToggleAeLock(locked: Boolean) = updateControls(FnSlot.EXPOSURE_MODE) { it.copy(aeLock = locked) }
    override fun onAntibanding(mode: Antibanding) = updateControls { it.copy(antibanding = mode) }
    override fun onFps(fps: Int) = updateControls { it.copy(fps = fps) }
    override fun onShutterMode(mode: ShutterMode) = updateControls(FnSlot.SHUTTER) { it.copy(shutterMode = mode) }
    override fun onShutterAngle(angle: Float) = updateControls(FnSlot.SHUTTER) {
        val mode = if (it.exposureMode == ExposureMode.PROGRAM || it.autoShutterDriven) ExposureMode.MANUAL else it.exposureMode
        it.copy(shutterAngle = angle, shutterMode = ShutterMode.ANGLE, exposureMode = mode)
    }
    override fun onExposureStep(step: ExposureStep) = updateControls { it.copy(exposureStep = step) }

    // ---- White balance ----
    override fun onWbMode(mode: WbMode) = updateControls(FnSlot.WB) { it.copy(wbMode = mode) }
    override fun onWbKelvin(kelvin: Int) = updateControls(FnSlot.WB) { it.copy(wbKelvin = kelvin, wbMode = WbMode.MANUAL) }
    override fun onWbTint(tint: Int) = updateControls(FnSlot.WB) { it.copy(wbTint = tint, wbMode = WbMode.MANUAL) }
    override fun onToggleAwbLock(locked: Boolean) = updateControls(FnSlot.WB) { it.copy(awbLock = locked) }
    override fun onMeteringMode(mode: MeteringMode) = updateControls(FnSlot.METERING) { it.copy(meteringMode = mode) }
    override fun onAfSpotSize(size: AfSpotSize) = updateControls { it.copy(afSpotSize = size) }
    override fun onCaptureCustomWb() {
        // Grey/white-card WB: freeze the AWB gains the HAL used on the latest frame (Sony Custom WB).
        val gains = engine.currentAwbGains()
        if (gains == null) {
            showStatus("No WB sample yet")
            return
        }
        updateControls(FnSlot.WB) { it.copy(wbMode = WbMode.CUSTOM, customWbGains = gains) }
        showStatus("Custom WB set")
    }

    // ---- Processing ----
    override fun onEdge(level: ProcessingLevel) = updateControls { it.copy(edge = level) }
    override fun onNoiseReduction(level: ProcessingLevel) = updateControls { it.copy(noiseReduction = level) }
    override fun onColorEffect(effect: ColorEffect) = updateControls { it.copy(colorEffect = effect) }

    // ---- Optics / output ----
    override fun onFlash(mode: FlashMode) {
        updateControls { it.copy(flash = mode) }
        refreshProgramAppSide() // AUTO/ON flash needs the HAL AE — photo P falls back off app-side
    }
    override fun onToggleOis(enabled: Boolean) = updateControls(FnSlot.STABILIZATION) { it.copy(oisEnabled = enabled) }
    override fun onZoomRatio(ratio: Float) = updateControls(FnSlot.ZOOM) { it.copy(zoomRatio = ratio) }
    // Hardware slide-zoom easing: the camera button emits DISCRETE key repeats (~20 Hz), and applying
    // each 1.04x jump directly reads as stutter. Instead the steps move a TARGET and a ~30 Hz ticker
    // glides the actual ratio toward it (exponential approach in log-zoom space), so the preview
    // sweeps smoothly like a powered zoom rocker.
    private var zoomEaseTarget: Float? = null
    private val zoomEaseTicker = object : Runnable {
        override fun run() {
            val target = zoomEaseTarget ?: return
            val cur = _state.value.controls.zoomRatio
            val next = (cur * Math.pow((target / cur).toDouble(), 0.4)).toFloat()
            if (kotlin.math.abs(kotlin.math.ln((target / next).toDouble())) < 0.004) {
                zoomEaseTarget = null
                onZoomRatio(target)
                return
            }
            onZoomRatio(next)
            mainHandler.postDelayed(this, 33)
        }
    }

    /** One hardware zoom-key repeat: nudge the ease target and make sure the glide ticker runs. */
    fun onHardwareZoomStep(factor: Float) {
        val range = _state.value.caps?.zoomRatioRange ?: return
        val base = zoomEaseTarget ?: _state.value.controls.zoomRatio
        val wasIdle = zoomEaseTarget == null
        zoomEaseTarget = (base * factor).coerceIn(range.lower, range.upper)
        if (wasIdle) mainHandler.post(zoomEaseTicker)
    }

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
        if (_state.value.isRecording && mode != _state.value.mode) {
            showStatus("Stop REC first")
            return
        }
        _state.update { it.copy(mode = mode) }
        engine.setVideoMode(mode == CaptureMode.VIDEO)
        applyEngineTransfer(mode, _state.value.transfer)
        refreshProgramAppSide() // photo P is app-side (min-shutter rule), video P is HAL AE
        refreshStandbyAudioMeter()
        markChanged(if (mode == CaptureMode.VIDEO) FnSlot.TRANSFER else FnSlot.EXPOSURE_MODE)
        // Persist the mode the instant it changes, not just on onStop: swiping the app from Recents
        // can kill the process before onStop's async prefs write flushes, which is why "last mode"
        // seemed not to stick. Writing here means the mode is already on disk well before any kill.
        saveSettingsIfEnabled()
    }
    override fun onTransfer(transfer: ColorTransfer) {
        if (rejectIfRecording("Stop REC first")) return
        applyEngineTransfer(_state.value.mode, transfer)
        _state.update { it.copy(transfer = transfer) }
        markChanged(FnSlot.TRANSFER)
    }
    override fun onSetPhotoFormats(formats: PhotoFormats) = _state.update { it.copy(photoFormats = formats, activeMemorySlot = null) }
    override fun onAspectRatio(ratio: AspectRatio) {
        engine.setAspectRatio(ratio)
        _state.update { it.copy(aspectRatio = ratio, activeMemorySlot = null) }
    }
    override fun onToggleRecordAudio(enabled: Boolean) {
        if (rejectIfRecording("Stop REC first")) return
        _state.update { it.copy(recordAudio = enabled, activeMemorySlot = null) }
        refreshStandbyAudioMeter()
        saveSettingsIfEnabled()
    }
    override fun onAudioGain(gain: Float) {
        if (rejectIfRecording("Stop REC first")) return
        engine.setAudioGain(gain)
        _state.update { it.copy(audioGain = gain, activeMemorySlot = null) }
        saveSettingsIfEnabled()
    }
    override fun onAudioScene(scene: com.hletrd.findx9tele.camera.AudioScene) {
        if (rejectIfRecording("Stop REC first")) return
        engine.setAudioScene(scene)
        _state.update { it.copy(audioScene = scene) }
        markChanged(FnSlot.AUDIO_SCENE)
    }
    override fun onAudioInputPreference(preference: AudioInputPreference) {
        if (rejectIfRecording("Stop REC first")) return
        engine.setAudioInputPreference(preference)
        _state.update {
            it.copy(
                audioInputPreference = preference,
                audioRouteLabel = if (it.isRecording) it.audioRouteLabel else audioInputStatusLabel(preference),
                activeMemorySlot = null,
            )
        }
        saveSettingsIfEnabled()
    }
    override fun onToggleTeleconverter(enabled: Boolean) {
        if (rejectIfRecording("Stop REC first")) return
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
        saveSettingsIfEnabled()
    }
    override fun onLens(choice: LensChoice) {
        if (rejectIfRecording("Stop REC first")) return
        // Bundled in the engine: resolves the lens id + flips teleconverter mode (3× on, else off)
        // in one reopen. Mirror both into UI state so the picker and the TELE chip stay in sync.
        engine.setLens(choice)
        _state.update { it.copy(lens = choice, teleconverterMode = choice.isTeleconverterLens) }
        markChanged(FnSlot.TELECONVERTER)
        saveSettingsIfEnabled()
    }
    override fun onVideoCodec(codec: VideoCodec) {
        if (rejectIfRecording("Stop REC first")) return
        engine.setVideoCodec(codec)
        _state.update { it.copy(videoCodec = codec, activeMemorySlot = null) }
        reconcileFrameRate()
    }
    override fun onBitrateLevel(level: BitrateLevel) {
        if (rejectIfRecording("Stop REC first")) return
        engine.setBitrateLevel(level)
        _state.update { it.copy(bitrateLevel = level, activeMemorySlot = null) }
    }
    override fun onVideoResolution(size: Size) {
        if (rejectIfRecording("Stop REC first")) return
        engine.setVideoResolution(size)
        _state.update { it.copy(videoResolution = size, activeMemorySlot = null) }
        reconcileFrameRate()
    }
    override fun onVideoFrameRate(rate: VideoFrameRate) {
        if (rejectIfRecording("Stop REC first")) return
        engine.setVideoFrameRate(rate)
        // Keep the exposure fps in step so the AE target-fps range, cine shutter angle and sensor
        // frame duration follow the selected video rate (drop-frame rates use their rounded parent).
        val controls = _state.value.controls.copy(fps = rate.fps)
        engine.setControls(controls)
        _state.update { it.copy(videoFrameRate = rate, controls = controls, activeMemorySlot = null) }
    }
    override fun onToggleOpenGate(enabled: Boolean) {
        if (rejectIfRecording("Stop REC first")) return
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

    override fun onToggleGammaAssist(enabled: Boolean) {
        engine.setGammaAssist(enabled)
        _state.update { it.copy(gammaAssist = enabled) }
        saveSettingsIfEnabled()
    }

    override fun onFrameLines(type: FrameLineType) {
        _state.update { it.copy(frameLines = type) }
        saveSettingsIfEnabled()
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
            _state.update { it.copy(isRecording = false, audioRouteLabel = audioInputStatusLabel(it.audioInputPreference)) }
        } else {
            val s = _state.value
            val inputStatus = audioInputStatus(s.audioInputPreference)
            if (s.recordAudio) {
                _state.update { it.copy(audioRouteLabel = inputStatus.label) }
                if (!inputStatus.available) showStatus("${inputStatus.label}; using default")
            }
            if (!engine.startRecording(s.recordAudio)) return
            recordStartMs = SystemClock.elapsedRealtime()
            mainHandler.post(recordTicker)
            _state.update {
                it.copy(
                    isRecording = true,
                    recordElapsedMs = 0,
                    audioRouteLabel = if (it.recordAudio) "Starting..." else "Off",
                )
            }
        }
        refreshStandbyAudioMeter()
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

    override fun onTogglePreserveLensSelection(enabled: Boolean) {
        _state.update { it.copy(preserveLensSelection = enabled, activeMemorySlot = null) }
        saveSettingsIfEnabled()
    }

    override fun onTogglePreserveTeleconverter(enabled: Boolean) {
        _state.update { it.copy(preserveTeleconverter = enabled, activeMemorySlot = null) }
        saveSettingsIfEnabled()
    }

    override fun onSetPhotoFnSlots(slots: List<FnSlot>) {
        val normalized = normalizedSlots(slots, FnSlot.PHOTO_DEFAULT)
        _state.update { it.copy(photoFnSlots = normalized, activeMemorySlot = null) }
        saveSettingsIfEnabled()
    }

    override fun onSetVideoFnSlots(slots: List<FnSlot>) {
        val normalized = normalizedSlots(slots, FnSlot.VIDEO_DEFAULT)
        _state.update { it.copy(videoFnSlots = normalized, activeMemorySlot = null) }
        saveSettingsIfEnabled()
    }

    override fun onSetMyMenuSlots(slots: List<FnSlot>) {
        val normalized = normalizedSlots(slots, FnSlot.MY_MENU_DEFAULT)
        _state.update { it.copy(myMenuSlots = normalized, activeMemorySlot = null) }
        saveSettingsIfEnabled()
    }

    override fun onStoreMemorySlot(slot: MemorySlot) {
        if (rejectIfRecording("Stop REC first")) return
        val snapshot = _state.value
        settingsStore.savePreset(
            slot,
            snapshot.controls,
            currentExtras(),
            name = presetNameFor(snapshot),
            summary = presetSummaryFor(snapshot),
        )
        refreshMemorySlotInfo(activeSlot = slot)
        showStatus("${slot.label} saved · ${presetNameFor(snapshot)}")
    }

    override fun onRecallMemorySlot(slot: MemorySlot) {
        if (_state.value.isRecording) {
            showStatus("Stop REC before ${slot.label}")
            return
        }
        val loaded = settingsStore.loadPreset(slot)
        if (loaded == null) {
            showStatus("${slot.label} empty")
            return
        }
        applyLoaded(loaded, activeSlot = slot, status = "${slot.label} loaded")
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
        engine.setAeMetering(usesExposureAnalysis(updated))
        _state.update { it.copy(controls = updated) }
        slot?.let(::markChanged)
        pendingControls = updated
        // Trailing throttle: apply at most every 40 ms with the newest value, but DON'T cancel a
        // pending apply — so a sustained gesture keeps landing updates live instead of only at the
        // end. (80 ms quantized the hardware slide-zoom into visible steps — user-reported stutter.)
        if (!applyScheduled) {
            applyScheduled = true
            mainHandler.postDelayed(applyControlsRunnable, 40)
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
            ExposureMode.PROGRAM -> if (c.programAppSide) {
                AutoExposure.driveProgram(
                    luma, c.iso, c.effectiveExposureNs(), preferredProgramShutterNs(s),
                    caps.isoRange, caps.exposureTimeRange, evStops,
                )?.let { (newIso, newNs) ->
                    updateControls { it.copy(iso = newIso, exposureTimeNs = newNs) }
                }
            }
            else -> Unit
        }
    }

    // ---- Lifecycle ----
    fun onStart() {
        refreshAudioInputStatus()
        engine.resume()
        refreshStandbyAudioMeter()
    }
    fun onStop() {
        // engine.pause() finalizes any in-flight recording; keep the UI in sync so we don't return
        // to a phantom "recording" state with the timer still ticking.
        if (_state.value.isRecording) {
            mainHandler.removeCallbacks(recordTicker)
            _state.update { it.copy(isRecording = false) }
        }
        saveSettingsIfEnabled() // persist on background so the next launch restores them
        engine.setStandbyAudioMonitor(false) // release the mic while backgrounded
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
