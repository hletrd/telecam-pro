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
    // still apply the LATEST value at a steady 25 Hz (every 40 ms) WHILE the gesture continues (a
    // plain debounce starved: continuous pinch/drag kept resetting the timer, so zoom only landed
    // after the finger lifted). First change schedules an apply; changes within the window just
    // refresh pendingControls.
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

    // capture-sequence id → its DNG sibling URI (bounded history), plus the id of the currently
    // displayed lastMediaUri. Guarded by synchronized(captureOutputs): the save callbacks land on
    // the engine's camera/io threads while Delete runs on main.
    private val captureOutputs = LinkedHashMap<Int, android.net.Uri>()
    private var lastDisplayedCaptureId = 0

    // Trailing debounce for persistence: every user-driven change to a PERSISTED setting schedules
    // one synchronous commit shortly after the LAST change. This closes the Recents-swipe-kill loss
    // window for dial/slider changes (previously only saved on onStop) WITHOUT the old failure mode
    // of a synchronous ~60-key commit on every drag frame (the audio-gain slider did exactly that).
    private val settingsSaveRunnable = Runnable { saveSettingsIfEnabled() }
    private fun scheduleSettingsSave() {
        mainHandler.removeCallbacks(settingsSaveRunnable)
        mainHandler.postDelayed(settingsSaveRunnable, SETTINGS_SAVE_DEBOUNCE_MS)
    }

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
        // Displayed preview aspect (engine setup thread → StateFlow is thread-safe): sizes the
        // letterboxed viewfinder so it always shows the full capture field.
        engine.onPreviewAspect = { aspect -> _state.update { it.copy(previewAspect = aspect) } }
        // Camera health (engine camera/setup threads → StateFlow is thread-safe): dims the shutter
        // while the session is down instead of silently declining taps.
        engine.onCameraReadyChange = { ready -> _state.update { it.copy(cameraReady = ready) } }
        // DEBUG: adb-driven zoom injection to test lens switching without a real pinch (adb can't
        // simulate a two-finger pinch on this device). Usage:
        //   adb shell am broadcast -a me.hletrd.telecampro.DEBUG_ZOOM --ef ratio 0.5
        if (com.hletrd.findx9tele.BuildConfig.DEBUG) {
            app.registerReceiver(
                object : android.content.BroadcastReceiver() {
                    override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                        val ratio = i?.getFloatExtra("ratio", -1f) ?: -1f
                        if (ratio > 0f) applyZoomRatio(ratio)
                    }
                },
                android.content.IntentFilter("me.hletrd.telecampro.DEBUG_ZOOM"),
                android.content.Context.RECEIVER_EXPORTED,
            )
        }
        // AF state (camera thread → StateFlow is thread-safe): colors the tap-AF reticle.
        engine.onAfIndication = { ind -> _state.update { it.copy(afIndication = ind) } }
        engine.onAnalysis = { h, w ->
            // Publish scope data into UI state only when something actually renders it (the
            // histogram/waveform overlays or the MANUAL-mode exposure meter). App-side AE reads the
            // callback arg directly, so with scopes hidden the ~6 Hz analysis tick no longer forces
            // a whole-CameraUiState emission (root-recomposition churn during manual shooting).
            val s = _state.value
            if (s.histogram || s.waveform || s.controls.exposureMode == ExposureMode.MANUAL) {
                _state.update { it.copy(histogramData = h, waveformData = w) }
            }
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
        // Last saved displayable media → gallery thumbnail + in-app review (io thread → StateFlow is
        // thread-safe). The capture id groups it with any DNG sibling for a whole-shot Delete.
        engine.onMediaSaved = { uri, captureId ->
            synchronized(captureOutputs) { lastDisplayedCaptureId = captureId }
            _state.update { it.copy(lastMediaUri = uri) }
        }
        // DNG sibling of a capture (never displayed): remembered so review-Delete removes it too.
        engine.onRawSaved = { uri, captureId ->
            synchronized(captureOutputs) {
                captureOutputs[captureId] = uri
                while (captureOutputs.size > CAPTURE_OUTPUT_HISTORY) {
                    captureOutputs.remove(captureOutputs.keys.first())
                }
            }
        }
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
        // Settings restore is the one path that bypasses the pickers' gating, so re-validate the
        // UI-gated enum values before they reach the engine: a persisted high-speed rate would
        // rebuild the SIGABRT-ing constrained session on next launch (FPS_120 is intentionally
        // unselectable), and a persisted codec the device can't mux (APV) would break recording.
        val safeFrameRate = if (e.videoFrameRate.highSpeed) ExtraSettings().videoFrameRate else e.videoFrameRate
        val safeCodec =
            if (com.hletrd.findx9tele.video.EncoderCaps.availableCodecs().contains(e.videoCodec)) e.videoCodec
            else ExtraSettings().videoCodec
        // Keep the exposure fps in lockstep with the restored video rate (mirrors onVideoFrameRate;
        // restoring them independently let the AE/shutter-angle math run at a stale fps).
        val cSynced = c.copy(fps = safeFrameRate.fps)
        // Push to the engine (safe pre-start: these set @Volatile fields read when the camera opens).
        engine.setVideoMode(e.mode == CaptureMode.VIDEO)
        engine.setControls(cSynced)
        // Manual/priority modes need luma analysis even when scopes are hidden: priority AE drives
        // from it, and full manual uses it for the live exposure meter.
        engine.setAeMetering(usesExposureAnalysis(cSynced))
        // Pass the resolved teleconverter state directly so a "preserve lens but not TELE" restore
        // (independent toggles, e.g. lens=3× with the converter not preserved as attached) reopens the
        // camera once instead of setLens bundling TELE on and a separate setTeleconverterMode call
        // immediately correcting it back off.
        engine.setLens(restoredLens, restoredTeleconverter)
        applyEngineTransfer(e.mode, e.transfer)
        engine.setGammaAssist(e.gammaAssist)
        engine.setVideoStabMode(e.videoStabMode)
        engine.setAspectRatio(e.aspectRatio)
        engine.setVideoCodec(safeCodec)
        engine.setBitrateLevel(e.bitrateLevel)
        engine.setOpenGate(e.openGate)
        engine.setAudioGain(e.audioGain)
        engine.setAudioScene(e.audioScene)
        engine.setAudioInputPreference(e.audioInputPreference)
        engine.setVideoFrameRate(safeFrameRate)
        // Restore the user-selected recording resolution ("Remember Settings" previously dropped it
        // silently — the engine re-picked the largest size on every launch). The engine re-validates
        // the request against the live caps once the camera opens and falls back to auto if the
        // size is no longer offered (lens change, aspect mismatch with openGate).
        val restoredVideoSize = parseVideoResolution(e.videoResolution)
        restoredVideoSize?.let { engine.setVideoResolution(it) }
        _state.update {
            it.copy(
                rememberSettings = rememberSettings ?: it.rememberSettings,
                controls = cSynced,
                transfer = e.transfer,
                photoFormats = PhotoFormats(e.heif, e.jpeg, e.dngRaw),
                mode = e.mode,
                lens = restoredLens,
                teleconverterMode = restoredTeleconverter,
                videoStabMode = e.videoStabMode,
                aspectRatio = e.aspectRatio,
                grid = e.grid,
                videoCodec = safeCodec,
                bitrateLevel = e.bitrateLevel,
                videoFrameRate = safeFrameRate,
                videoResolution = restoredVideoSize ?: it.videoResolution,
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
            videoResolution = "${s.videoResolution.width}x${s.videoResolution.height}",
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

    /** Recomputes [ManualControls.programAppSide] after mode/flash/exposure-mode changes, seeding a smooth handoff. */
    private fun refreshProgramAppSide() {
        val live = _state.value
        val want = programShouldRunAppSide(live.mode, live.controls.exposureMode, live.controls.flash)
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
    private fun preferredProgramShutterNs(s: CameraUiState): Long =
        preferredProgramShutterNs(s.lens.targetEquivMm, s.teleconverterMode)

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

    /** Parses a persisted "WxH" video-resolution string; null for "" or anything malformed. */
    private fun parseVideoResolution(raw: String): Size? {
        val parts = raw.split('x')
        if (parts.size != 2) return null
        val w = parts[0].toIntOrNull() ?: return null
        val h = parts[1].toIntOrNull() ?: return null
        return if (w > 0 && h > 0) Size(w, h) else null
    }

    // No 8K bucket: chooseVideoSize caps the recording width at 3840, so 2160-tall is the ceiling.
    private fun videoSizeSummary(size: Size): String = when {
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
    override fun onIso(iso: Int) {
        updateControls(FnSlot.ISO) {
            val mode = if (it.exposureMode == ExposureMode.PROGRAM || it.autoIsoDriven) ExposureMode.MANUAL else it.exposureMode
            it.copy(iso = iso, exposureMode = mode)
        }
        refreshProgramAppSide() // taking manual control can leave PROGRAM → the app-side flag follows
    }
    // Likewise for shutter: if the shutter is currently auto (PROGRAM or ISO), taking it over → MANUAL.
    override fun onShutterNs(ns: Long) {
        updateControls(FnSlot.SHUTTER) {
            val mode = if (it.exposureMode == ExposureMode.PROGRAM || it.autoShutterDriven) ExposureMode.MANUAL else it.exposureMode
            it.copy(exposureTimeNs = ns, exposureMode = mode)
        }
        refreshProgramAppSide()
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
    override fun onAntibanding(mode: Antibanding) = updateControls(persist = true) { it.copy(antibanding = mode) }
    // (onFps was removed: dead API surface — controls.fps is always driven by onVideoFrameRate.)
    override fun onShutterMode(mode: ShutterMode) = updateControls(FnSlot.SHUTTER) { it.copy(shutterMode = mode) }
    override fun onShutterAngle(angle: Float) {
        updateControls(FnSlot.SHUTTER) {
            val mode = if (it.exposureMode == ExposureMode.PROGRAM || it.autoShutterDriven) ExposureMode.MANUAL else it.exposureMode
            it.copy(shutterAngle = angle, shutterMode = ShutterMode.ANGLE, exposureMode = mode)
        }
        refreshProgramAppSide()
    }
    override fun onExposureStep(step: ExposureStep) = updateControls(persist = true) { it.copy(exposureStep = step) }

    // ---- White balance ----
    override fun onWbMode(mode: WbMode) = updateControls(FnSlot.WB) { it.copy(wbMode = mode) }
    override fun onWbKelvin(kelvin: Int) = updateControls(FnSlot.WB) { it.copy(wbKelvin = kelvin, wbMode = WbMode.MANUAL) }
    override fun onWbTint(tint: Int) = updateControls(FnSlot.WB) { it.copy(wbTint = tint, wbMode = WbMode.MANUAL) }
    override fun onToggleAwbLock(locked: Boolean) = updateControls(FnSlot.WB) { it.copy(awbLock = locked) }
    override fun onMeteringMode(mode: MeteringMode) = updateControls(FnSlot.METERING) { it.copy(meteringMode = mode) }
    override fun onAfSpotSize(size: AfSpotSize) = updateControls(persist = true) { it.copy(afSpotSize = size) }
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
    override fun onEdge(level: ProcessingLevel) = updateControls(persist = true) { it.copy(edge = level) }
    override fun onNoiseReduction(level: ProcessingLevel) = updateControls(persist = true) { it.copy(noiseReduction = level) }
    override fun onColorEffect(effect: ColorEffect) = updateControls(persist = true) { it.copy(colorEffect = effect) }

    // ---- Optics / output ----
    override fun onFlash(mode: FlashMode) {
        // persist=true: flash is the most common per-shot toggle among the slot-less setters, and a
        // Recents swipe-kill right after toggling it silently lost the change.
        updateControls(persist = true) { it.copy(flash = mode) }
        refreshProgramAppSide() // AUTO/ON flash needs the HAL AE — photo P falls back off app-side
    }
    override fun onToggleOis(enabled: Boolean) = updateControls(FnSlot.STABILIZATION) { it.copy(oisEnabled = enabled) }
    override fun onZoomRatio(ratio: Float) {
        // Any direct zoom input (pinch, dial, in-sheet slider) takes over from an in-flight hardware
        // slide glide — otherwise the ~30 Hz ease ticker keeps dragging the ratio back toward its
        // now-stale target every 33 ms, fighting the finger.
        zoomEaseTarget = null
        applyZoomRatio(ratio)
    }
    // Pinch events arrive at INPUT rate (up to ~120 Hz on this panel); applying each one drove a
    // whole-tree recomposition plus a setRepeatingRequest per event — the residual zoom jank after
    // the fast path landed. Coalesce: apply the first event immediately (no perceived latency),
    // then flush only the NEWEST value every ~33 ms while the gesture continues.
    private var zoomFlushScheduled = false
    private var zoomPendingRatio = Float.NaN

    private fun applyZoomRatio(ratio: Float) {
        val range = _state.value.caps?.zoomRatioRange
        val z = if (range != null) ratio.coerceIn(range.lower, range.upper) else ratio
        zoomPendingRatio = z
        if (zoomFlushScheduled) return // the scheduled flush picks up this newest value
        zoomFlushScheduled = true
        flushZoom() // leading edge: first tick lands instantly
        mainHandler.postDelayed({
            zoomFlushScheduled = false
            if (!zoomPendingRatio.isNaN() && zoomPendingRatio != _state.value.controls.zoomRatio) flushZoom()
        }, 16) // ~60 Hz: the engine throttles HAL submits itself; GL follows every flush
    }

    // Zoom-gesture lifecycle: every zoom input funnels through flushZoom, so "interacting" =
    // first flush → 700 ms after the last one. Drives the engine's smooth-preview boost.
    private var zoomInteracting = false
    private val zoomInteractionEnd = Runnable {
        zoomInteracting = false
        engine.setZoomInteraction(false)
    }

    private fun flushZoom() {
        val z = zoomPendingRatio
        if (z.isNaN()) return
        if (!zoomInteracting) {
            zoomInteracting = true
            engine.setZoomInteraction(true)
        }
        mainHandler.removeCallbacks(zoomInteractionEnd)
        mainHandler.postDelayed(zoomInteractionEnd, 700)
        // Straight to the engine fast path (cached-builder resubmit) — updateControls would re-apply
        // the FULL control set. Chip highlight follows the zoom band only on the seamless (photo)
        // camera; video zoom is lens-local. Persistence rides the debounced settings save.
        engine.setZoomRatio(z)
        val s = _state.value
        val lensBand = if (!s.teleconverterMode && s.mode == CaptureMode.PHOTO) LensChoice.forZoom(z) else s.lens
        _state.update { it.copy(controls = it.controls.copy(zoomRatio = z), lens = lensBand) }
        // A pending throttled full-apply captured OLDER controls — refresh its zoom so it can't
        // briefly snap the ratio back when it lands.
        pendingControls = pendingControls?.copy(zoomRatio = z)
        markChanged(FnSlot.ZOOM)
        scheduleSettingsSave()
    }
    // Hardware slide-zoom easing: the camera button emits DISCRETE key repeats (~20 Hz), and applying
    // each 1.04x jump directly reads as stutter. Instead the steps move a TARGET and a ~30 Hz ticker
    // glides the actual ratio toward it (exponential approach in log-zoom space), so the preview
    // sweeps smoothly like a powered zoom rocker.
    private var zoomEaseTarget: Float? = null
    private val zoomEaseTicker = object : Runnable {
        override fun run() {
            val target = zoomEaseTarget ?: return
            // Non-finite/non-positive guard: a zoomRatio of 0/NaN (e.g. a corrupted persisted value
            // slipping past the load clamp) would make pow/ln produce NaN, and NaN comparisons are
            // always false — the ticker would re-post itself at ~30 Hz FOREVER with a broken readout.
            val cur = currentZoomBase().takeIf { it.isFinite() && it > 0f } ?: run {
                zoomEaseTarget = null
                applyZoomRatio(target)
                return
            }
            val next = (cur * Math.pow((target / cur).toDouble(), 0.4)).toFloat()
            if (!next.isFinite() || next <= 0f) {
                zoomEaseTarget = null
                applyZoomRatio(target)
                return
            }
            // applyZoomRatio, NOT onZoomRatio: the public setter cancels the glide (manual takeover).
            if (kotlin.math.abs(kotlin.math.ln((target / next).toDouble())) < 0.004) {
                zoomEaseTarget = null
                applyZoomRatio(target)
                return
            }
            applyZoomRatio(next)
            mainHandler.postDelayed(this, 33)
        }
    }

    /** One hardware zoom-key repeat: nudge the ease target and make sure the glide ticker runs. */
    fun onHardwareZoomStep(factor: Float) {
        val range = _state.value.caps?.zoomRatioRange ?: return
        val base = zoomEaseTarget ?: currentZoomBase()
        val wasIdle = zoomEaseTarget == null
        zoomEaseTarget = (base * factor).coerceIn(range.lower, range.upper)
        if (wasIdle) mainHandler.post(zoomEaseTicker)
    }

    override fun onPinchZoom(factor: Float) {
        // Pinch multiplies the FRESHEST zoom (the coalesced pending value, NOT UI state): state only
        // updates at the ~33 ms flush, and compounding each input event against that stale base made
        // the zoom crawl between flushes then jump at the boundary — the residual pinch jank.
        val range = _state.value.caps?.zoomRatioRange ?: return
        val next = (currentZoomBase() * factor).coerceIn(range.lower, range.upper)
        onZoomRatio(next)
    }

    /**
     * The freshest zoom value: the coalesced pending ratio while a flush window is open (UI state
     * lags it by up to ~33 ms), else the state value. Every compounding zoom input (pinch factor,
     * hardware-key step, ease ticker) must use THIS as its base.
     */
    private fun currentZoomBase(): Float {
        val pending = zoomPendingRatio
        return if (!pending.isNaN()) pending else _state.value.controls.zoomRatio
    }

    override fun onJpegQuality(quality: Int) = updateControls(persist = true) { it.copy(jpegQuality = quality) }

    // ---- Modes ----
    override fun onModeChange(mode: CaptureMode) {
        cancelCountdown()
        if (_state.value.isRecording && mode != _state.value.mode) {
            showStatus("Stop REC first")
            return
        }
        // Mirror the engine's mode-flip zoom remap (unified main-relative ↔ lens-local; see
        // CameraEngine.setVideoMode) so the zoom pill/dial and lens chip agree with the camera.
        _state.update {
            var lens = it.lens
            var controls = it.controls
            if (!it.teleconverterMode) {
                if (mode == CaptureMode.VIDEO && it.mode == CaptureMode.PHOTO) {
                    lens = LensChoice.forZoom(controls.zoomRatio)
                    controls = controls.copy(zoomRatio = (controls.zoomRatio / lens.zoomPreset).coerceAtLeast(1f))
                } else if (mode == CaptureMode.PHOTO && it.mode == CaptureMode.VIDEO) {
                    controls = controls.copy(
                        zoomRatio = (lens.zoomPreset * controls.zoomRatio.coerceAtLeast(1f)).coerceIn(0.6f, 20f),
                    )
                }
            }
            it.copy(mode = mode, lens = lens, controls = controls)
        }
        zoomPendingRatio = Float.NaN // the remap invalidated the coalesced base
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
        scheduleSettingsSave()
    }
    override fun onSetPhotoFormats(formats: PhotoFormats) {
        _state.update { it.copy(photoFormats = formats, activeMemorySlot = null) }
        scheduleSettingsSave()
    }
    override fun onAspectRatio(ratio: AspectRatio) {
        engine.setAspectRatio(ratio)
        _state.update { it.copy(aspectRatio = ratio, activeMemorySlot = null) }
        scheduleSettingsSave()
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
        // Debounced, not immediate: this rides a slider, and a synchronous full-prefs commit per
        // drag frame stuttered the main thread. The trailing save still lands within ~0.5 s.
        scheduleSettingsSave()
    }
    override fun onAudioScene(scene: com.hletrd.findx9tele.camera.AudioScene) {
        if (rejectIfRecording("Stop REC first")) return
        engine.setAudioScene(scene)
        _state.update { it.copy(audioScene = scene) }
        markChanged(FnSlot.AUDIO_SCENE)
        scheduleSettingsSave()
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
        // TELE pins the STANDALONE 3× camera (the converter's host lens; digital-only zoom, afocal
        // flip). OFF returns to the seamless logical camera at the 3× preset so framing carries
        // over. Both are one setLens camera switch.
        engine.setLens(com.hletrd.findx9tele.camera.LensChoice.TELE3X, enabled)
        _state.update {
            it.copy(
                teleconverterMode = enabled,
                lens = com.hletrd.findx9tele.camera.LensChoice.TELE3X,
                controls = it.controls.copy(
                    zoomRatio = if (enabled) 1f else com.hletrd.findx9tele.camera.LensChoice.TELE3X.zoomPreset,
                ),
            )
        }
        zoomPendingRatio = Float.NaN // preset/TC zoom overwrote the coalesced base
        markChanged(FnSlot.TELECONVERTER)
        saveSettingsIfEnabled()
    }
    override fun onLens(choice: LensChoice) {
        if (rejectIfRecording("Stop REC first")) return
        // A lens pick is a ZOOM PRESET on the logical seamless camera (no reopen, no black gap).
        // TELE stays on only when it already is AND the pick is its 3× host lens; any other pick
        // exits converter shooting back to the seamless camera.
        val keepTc = _state.value.teleconverterMode && choice == LensChoice.TELE3X
        engine.setLens(choice, keepTc)
        _state.update {
            it.copy(
                lens = choice,
                teleconverterMode = keepTc,
                controls = it.controls.copy(zoomRatio = if (keepTc) 1f else choice.zoomPreset),
            )
        }
        zoomPendingRatio = Float.NaN // preset/TC zoom overwrote the coalesced base
        markChanged(FnSlot.TELECONVERTER)
        saveSettingsIfEnabled()
    }
    override fun onVideoCodec(codec: VideoCodec) {
        if (rejectIfRecording("Stop REC first")) return
        engine.setVideoCodec(codec)
        _state.update { it.copy(videoCodec = codec, activeMemorySlot = null) }
        reconcileFrameRate()
        scheduleSettingsSave()
    }
    override fun onBitrateLevel(level: BitrateLevel) {
        if (rejectIfRecording("Stop REC first")) return
        engine.setBitrateLevel(level)
        _state.update { it.copy(bitrateLevel = level, activeMemorySlot = null) }
        scheduleSettingsSave()
    }
    override fun onVideoResolution(size: Size) {
        if (rejectIfRecording("Stop REC first")) return
        engine.setVideoResolution(size)
        _state.update { it.copy(videoResolution = size, activeMemorySlot = null) }
        reconcileFrameRate()
        // The one pro setting "Remember Settings" used to drop: a user's 1080p pick silently
        // reverted to 4K on relaunch. Persisted like every sibling video setting.
        scheduleSettingsSave()
    }
    override fun onVideoFrameRate(rate: VideoFrameRate) {
        if (rejectIfRecording("Stop REC first")) return
        engine.setVideoFrameRate(rate)
        // Keep the exposure fps in step so the AE target-fps range, cine shutter angle and sensor
        // frame duration follow the selected video rate (drop-frame rates use their rounded parent).
        val controls = _state.value.controls.copy(fps = rate.fps)
        engine.setControls(controls)
        // Re-base any pending throttled dial apply onto the new fps: the 40 ms trailing apply would
        // otherwise push its STALE snapshot (old fps) over this direct engine write moments later.
        pendingControls = pendingControls?.copy(fps = rate.fps)
        _state.update { it.copy(videoFrameRate = rate, controls = controls, activeMemorySlot = null) }
        scheduleSettingsSave()
    }
    override fun onToggleOpenGate(enabled: Boolean) {
        if (rejectIfRecording("Stop REC first")) return
        engine.setOpenGate(enabled)
        _state.update { it.copy(openGate = enabled, activeMemorySlot = null) }
        reconcileFrameRate()
        scheduleSettingsSave()
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
        // Mid-clip the HAL would apply the new OIS/EIS profile LIVE (setVideoStabMode rebuilds the
        // repeating request immediately) — a visible stabilization discontinuity baked into the
        // file. Same gate as every other session-reconfiguring control.
        if (rejectIfRecording("Stop REC first")) return
        engine.setVideoStabMode(mode)
        _state.update { it.copy(videoStabMode = mode) }
        markChanged(FnSlot.STABILIZATION)
        scheduleSettingsSave()
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
        scheduleSettingsSave()
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
    /**
     * Fires the capture AND blinks the viewfinder immediately. The still takes pipeline-depth ×
     * frame-duration before it even starts exposing (~0.9 s measured in low light) — with no
     * instant acknowledgment every press reads as shutter lag or a dead button.
     */
    private fun fireShutterWithFeedback() {
        _state.update { it.copy(shutterFlashTick = it.shutterFlashTick + 1) }
        engine.capturePhoto(_state.value.photoFormats)
    }

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
        if (seconds <= 0) fireShutterWithFeedback() else startCountdown(seconds)
    }

    private fun startCountdown(seconds: Int) {
        _state.update { it.copy(timerCountdownSec = seconds) }
        val tick = object : Runnable {
            override fun run() {
                val cur = _state.value.timerCountdownSec
                if (cur <= 1) {
                    _state.update { it.copy(timerCountdownSec = 0) }
                    countdownRunnable = null
                    fireShutterWithFeedback()
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
        // Debounced: the editor's Up/Down/Add/Remove taps fired one synchronous ~60-key commit per
        // press; a reorder burst now lands as one trailing commit (loss window ≤ 500 ms, per the
        // documented debounce contract).
        scheduleSettingsSave()
    }

    override fun onSetVideoFnSlots(slots: List<FnSlot>) {
        val normalized = normalizedSlots(slots, FnSlot.VIDEO_DEFAULT)
        _state.update { it.copy(videoFnSlots = normalized, activeMemorySlot = null) }
        scheduleSettingsSave()
    }

    override fun onSetMyMenuSlots(slots: List<FnSlot>) {
        val normalized = normalizedSlots(slots, FnSlot.MY_MENU_DEFAULT)
        _state.update { it.copy(myMenuSlots = normalized, activeMemorySlot = null) }
        scheduleSettingsSave()
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

    override fun onReviewOpenChange(open: Boolean) {
        _state.update { it.copy(reviewOpen = open) }
    }

    override fun onDeleteLastMedia() {
        val uri = _state.value.lastMediaUri ?: return
        // Delete the WHOLE shot: the displayed HEIF/JPEG plus the DNG sibling saved by the same
        // shutter press (it used to survive, orphaned in the gallery). ContentResolver.delete is a
        // Binder round-trip per file — run both off the main thread.
        val rawSibling = synchronized(captureOutputs) { captureOutputs.remove(lastDisplayedCaptureId) }
        _state.update { it.copy(lastMediaUri = null) }
        Thread {
            MediaStoreWriter.delete(getApplication(), uri)
            rawSibling?.let { MediaStoreWriter.delete(getApplication(), it) }
        }.start()
        showStatus("Deleted")
    }

    // [persist] defaults to "has an Fn slot": user-facing setters WITHOUT a slot (antibanding, AF
    // spot size, flash, JPEG quality, …) pass persist = true explicitly — they mutate persisted
    // fields too, and a Recents swipe-kill right after (say) a flash toggle silently lost it.
    private fun updateControls(
        slot: FnSlot? = null,
        persist: Boolean = slot != null,
        block: (ManualControls) -> ManualControls,
    ) {
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
        // Manual-control changes used to persist only on onStop — the exact Recents-swipe-kill loss
        // class the project fixed twice for mode/lens. A USER change schedules a debounced commit;
        // the app-side AE loop's driven writes are excluded (they'd re-arm the debounce ~6×/s
        // forever) — an AE-driven value is transient by nature and restored by the loop anyway.
        if (persist) scheduleSettingsSave()
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
        val isoRange = caps.isoRange
        val expRange = caps.exposureTimeRange
        when (c.exposureMode) {
            ExposureMode.SHUTTER -> if (isoRange != null) {
                AutoExposure.driveIso(luma, c.iso, isoRange.lower, isoRange.upper, evStops)?.let { newIso ->
                    updateControls { it.copy(iso = newIso) }
                }
            }
            ExposureMode.ISO -> if (expRange != null) {
                AutoExposure.driveShutterNs(luma, c.effectiveExposureNs(), expRange.lower, expRange.upper, evStops)?.let { newNs ->
                    updateControls { it.copy(exposureTimeNs = newNs) }
                }
            }
            ExposureMode.PROGRAM -> if (c.programAppSide && isoRange != null && expRange != null) {
                AutoExposure.driveProgram(
                    luma, c.iso, c.effectiveExposureNs(), preferredProgramShutterNs(s),
                    isoRange.lower, isoRange.upper, expRange.lower, expRange.upper, evStops,
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
        // Re-arm the OSD tickers paused in onStop (level only if its overlay is enabled).
        if (_state.value.level) mainHandler.post(levelTicker)
        mainHandler.post(orientationTicker)
        mainHandler.post(infoTicker)
    }
    fun onStop() {
        // engine.pause() finalizes any in-flight recording; keep the UI in sync so we don't return
        // to a phantom "recording" state with the timer still ticking.
        if (_state.value.isRecording) {
            mainHandler.removeCallbacks(recordTicker)
            _state.update { it.copy(isRecording = false) }
        }
        // Nothing renders while backgrounded, but these self-rescheduling tickers kept waking the
        // main thread every 100/200 ms (and 10 s) indefinitely — pure battery/Doze cost. Paused
        // here, re-armed in onStart. The zoom glide is abandoned too (its target is stale by resume).
        mainHandler.removeCallbacks(levelTicker)
        mainHandler.removeCallbacks(orientationTicker)
        mainHandler.removeCallbacks(infoTicker)
        zoomEaseTarget = null
        mainHandler.removeCallbacks(zoomEaseTicker)
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
        // How many recent captures keep their DNG-sibling mapping for whole-shot Delete.
        const val CAPTURE_OUTPUT_HISTORY = 8
        // Trailing window for the debounced settings commit (see scheduleSettingsSave).
        const val SETTINGS_SAVE_DEBOUNCE_MS = 500L
    }
}

/**
 * PROGRAM runs app-side for STILLS — the auto min-shutter (1/focal rule) + Auto ISO a real P mode
 * gives, which the HAL AE cannot (no min-shutter hint → 1/30 s blur at 300 mm). The HAL AE keeps
 * video PROGRAM (its shutter conventions are frame-rate driven) and any flash-metered PROGRAM
 * (AUTO/ON flash metering only exists with AE ON). Requires [exposureMode] == PROGRAM so the flag
 * means exactly what its name says — in S/ISO/M it is false, not a stale leftover. Top-level and
 * Android-free so the P-mode routing matrix is unit-testable.
 */
internal fun programShouldRunAppSide(mode: CaptureMode, exposureMode: ExposureMode, flash: FlashMode): Boolean =
    mode == CaptureMode.PHOTO && exposureMode == ExposureMode.PROGRAM &&
        flash != FlashMode.AUTO && flash != FlashMode.ON

/**
 * Handheld-safe shutter target (ns) for app-side PROGRAM: the 1/(35mm-equivalent focal) rule at the
 * effective focal length (native × teleconverter magnification). Pure for unit tests.
 */
internal fun preferredProgramShutterNs(lensEquivMm: Float, teleconverterMode: Boolean): Long {
    val eff = lensEquivMm * (if (teleconverterMode) com.hletrd.findx9tele.camera.TELECONVERTER_MAGNIFICATION else 1f)
    return (1_000_000_000f / eff.coerceAtLeast(1f)).toLong()
}
