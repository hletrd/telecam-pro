package com.hletrd.findx9tele.ui

import android.app.Application
import android.net.Uri
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
import com.hletrd.findx9tele.camera.CameraCaps
import com.hletrd.findx9tele.camera.CameraEngine
import com.hletrd.findx9tele.camera.CameraReadyPublicationGate
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
import com.hletrd.findx9tele.camera.MediaDeleteScope
import com.hletrd.findx9tele.camera.MeteringMode
import com.hletrd.findx9tele.camera.MemorySlot
import com.hletrd.findx9tele.camera.PeakingColor
import com.hletrd.findx9tele.camera.PeakingLevel
import com.hletrd.findx9tele.camera.PhotoFormats
import com.hletrd.findx9tele.camera.PendingControlsDisposition
import com.hletrd.findx9tele.camera.acceptedOpticsAuxState
import com.hletrd.findx9tele.camera.controlAvailability
import com.hletrd.findx9tele.camera.controlCapabilities
import com.hletrd.findx9tele.camera.normalizeControlsForRoute
import com.hletrd.findx9tele.camera.normalizedFor
import com.hletrd.findx9tele.camera.normalizedForCaptureMode
import com.hletrd.findx9tele.camera.pendingControlsForTransition
import com.hletrd.findx9tele.camera.exposureUpperBoundForCaptureMode
import com.hletrd.findx9tele.camera.withDefaultIfEmpty
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
import com.hletrd.findx9tele.storage.RestoredDeleteScope
import com.hletrd.findx9tele.storage.SettingsStore
import com.hletrd.findx9tele.storage.StoredMediaOutputKind
import com.hletrd.findx9tele.video.AudioInputInspector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Holds [CameraUiState] and turns [CameraActions] into [CameraEngine] calls. UI-thread only. */
class CameraViewModel(app: Application) : AndroidViewModel(app), CameraActions {

    private val engine = CameraEngine(app)
    private val cameraReadyPublicationGate = CameraReadyPublicationGate()
    private val settingsStore = SettingsStore(app)
    private val _state = MutableStateFlow(CameraUiState())
    val state: StateFlow<CameraUiState> = _state.asStateFlow()
    // Video must clamp its live/request shutter to one frame, but that derived value must not erase
    // the photographer's Photo shutter (including ANGLE's dormant SPEED value). Persisted through
    // ExtraSettings so a process death while Video is selected still restores Photo faithfully.
    private var photoExposureTimeNs = ManualControls().exposureTimeNs

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

    /** Invalidates a whole-controls packet that belongs to an optics/settings state being replaced. */
    private fun cancelPendingControls() {
        mainHandler.removeCallbacks(applyControlsRunnable)
        applyScheduled = false
        pendingControls = pendingControlsForTransition(
            pendingControls,
            PendingControlsDisposition.CANCEL_FOR_REPLACEMENT,
        )
    }

    /** Lands the freshest user controls before a lens/TELE transaction derives its zoom packet. */
    private fun drainPendingControls() {
        pendingControlsForTransition(
            pendingControls,
            PendingControlsDisposition.DRAIN_BEFORE_OPTICS,
        )?.let(engine::setControls)
        cancelPendingControls()
    }

    private var countdownRunnable: Runnable? = null
    private var lifecycleStarted = false
    private var debugZoomReceiver: android.content.BroadcastReceiver? = null
    // Main-thread token for the one-shot Custom-WB sample. Any newer WB action makes an older
    // controller callback inert before it can publish gains or a stale status message.
    private var customWbSampleGeneration = 0L

    // Auto-dismisses the transient status toast ("Saved" / "Video saved" / errors) so it doesn't hang
    // on screen forever (QA: "video saved" stuck). Each new message re-arms the 2 s timer.
    private val clearStatusRunnable = Runnable { _state.update { it.copy(statusMessage = null) } }

    private var reticleHideRunnable: Runnable? = null

    // Owns every processed/raw URI for a capture and tombstones deleted ids so a late save callback
    // cannot resurrect a sibling after the user deleted the frozen review shot.
    private val captureOutputs = CaptureOutputTracker<Uri>(CAPTURE_OUTPUT_HISTORY)

    // The plain zoom-glide state (pending / ease / interacting / flush-scheduled) as one tested holder
    // so every optics-scale remap door invalidates it through the single invalidateZoomGlide() owner
    // (AGG3-51). Declared BEFORE init: applyLoaded()/restoreSettingsIfEnabled() runs during
    // construction and calls invalidateZoomGlide(), which dereferences this — a later declaration
    // would leave it null and NPE on a launch that restores saved settings.
    private val zoomGlide = ZoomGlideState()

    // The 16 ms trailing coalescer flush, held as a NAMED Runnable so a remap door / onStop can
    // cancel it — the old anonymous postDelayed lambda had no reference to remove (AGG3-26).
    // All four zoom Runnables are declared BEFORE the init block (CRIT4-12): invalidateZoomGlide()
    // runs DURING construction (applyLoaded in init), and fields declared below init are still
    // null there — the old layout passed nulls to Handler.removeCallbacks (platform-tolerated),
    // and a `by lazy` variant was WORSE (the lazy delegates are fields too; a construction-time
    // access NPE'd on the null delegate — device-caught 2026-07-18). Field initializers run
    // strictly top-to-bottom, so everything here is real by the time init executes.
    private val zoomTrailingFlush = Runnable {
        zoomGlide.flushScheduled = false
        if (!zoomGlide.pendingRatio.isNaN() && zoomGlide.pendingRatio != _state.value.controls.zoomRatio) flushZoom()
    }

    // Zoom-gesture lifecycle: every zoom input funnels through flushZoom, so "interacting" =
    // first flush → 700 ms after the last one. Drives the engine's smooth-preview boost.
    private val zoomInteractionEnd = Runnable {
        zoomGlide.interacting = false
        mainHandler.removeCallbacks(zoomQuietLanding)
        engine.setZoomInteraction(false)
    }

    // Quiet-window landing: one throttle window after the LAST flush, the exact (non-wide-aimed)
    // ratio lands on the HAL even though the 700 ms boost tail is still running — otherwise a clip
    // keeps the ~1.2×-wide framing after finger-up and a tail still frames wider than the finder.
    private val zoomQuietLanding = Runnable { engine.landExactZoom() }

    // Hardware slide-zoom easing: the camera button emits DISCRETE key repeats (~20 Hz), and applying
    // each 1.04x jump directly reads as stutter. Instead the steps move a TARGET and a ~30 Hz ticker
    // glides the actual ratio toward it (exponential approach in log-zoom space), so the preview
    // sweeps smoothly like a powered zoom rocker.
    private val zoomEaseTicker = object : Runnable {
        override fun run() {
            val target = zoomGlide.easeTarget ?: return
            val cur = currentZoomBase()
            // applyZoomRatio, NOT onZoomRatio: the public setter cancels the glide (manual takeover).
            // The per-tick math (incl. the non-finite/non-positive guard that once let a corrupted
            // ratio keep a NaN ticker alive forever) is the pure, unit-tested zoomEaseStep.
            when (val step = zoomEaseStep(cur, target)) {
                is ZoomEaseStep.Land -> {
                    zoomGlide.easeTarget = null
                    applyZoomRatio(step.target)
                }
                is ZoomEaseStep.Step -> {
                    val applied = applyZoomRatio(step.value)
                    // A cap/snap may make the mathematical target unreachable. Stop as soon as
                    // application makes no progress instead of keeping a 30 Hz loop alive forever.
                    if (applied.isFinite() && kotlin.math.abs(applied - cur) < 0.0001f) {
                        zoomGlide.easeTarget = null
                        return
                    }
                    mainHandler.postDelayed(this, 33)
                }
            }
        }
    }

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
            if (!lifecycleStarted || !_state.value.level) return
            _state.update { it.copy(levelRoll = engine.currentRollDegrees()) }
            mainHandler.postDelayed(this, 100)
        }
    }

    // Always-on: tracks the physical device orientation so overlays can rotate to stay upright even
    // though the activity is portrait-locked. Only writes state when the discrete value changes.
    private val orientationTicker = object : Runnable {
        override fun run() {
            if (!lifecycleStarted) return
            val o = engine.currentDeviceOrientation()
            if (o != _state.value.deviceOrientation) _state.update { it.copy(deviceOrientation = o) }
            mainHandler.postDelayed(this, 200)
        }
    }

    // Battery % + free storage for the OSD info pill, Sony-style. Slow tick — these move slowly.
    // The reads run on the shared io executor (PERF4-9): StatFs is filesystem I/O that can block
    // on a busy volume — exactly when a concurrent capture save is hammering it — and it sat on
    // the MAIN thread; the result posts back into state.
    private val infoTicker = object : Runnable {
        override fun run() {
            if (!lifecycleStarted) return
            ioExecutor.execute {
                val battery = readBatteryPct()
                val free = readFreeBytes()
                mainHandler.post {
                    if (lifecycleStarted) _state.update { it.copy(batteryPct = battery, freeBytes = free) }
                }
            }
            mainHandler.postDelayed(this, 10_000)
        }
    }

    // One shared background lane for the ViewModel's own MediaStore/StatFs work (PERF4-6/PERF4-9):
    // the restore, whole-family delete, and late-sibling delete paths each spawned a bare
    // unpooled Thread per invocation. Single-threaded so deletes stay ordered; shut down in
    // onCleared after the engine release completes.
    private val ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "vm-io").apply { isDaemon = true }
    }

    init {
        engine.onStatus = ::publishStatus
        // Caps arrive on the setup thread. Reconcile restored/schema-normalized zoom against the
        // selected camera's authoritative range on main before any delayed input can reuse it.
        engine.onCapsReady = { caps, generation ->
            mainHandler.post {
                if (!engine.isOpticsGenerationCurrent(generation)) return@post
                reconcileZoomToCaps(caps)
                reconcileFrameRate()
            }
        }
        engine.onVideoSizeChosen = { size, generation ->
            mainHandler.post {
                if (generation != null && !engine.isOpticsGenerationCurrent(generation)) return@post
                _state.update { it.copy(videoResolution = size) }
                reconcileFrameRate()
            }
        }
        // Displayed preview aspect (engine setup thread → StateFlow is thread-safe): sizes the
        // letterboxed viewfinder so it always shows the full capture field.
        engine.onPreviewAspect = { aspect, generation ->
            mainHandler.post {
                if (!engine.isOpticsGenerationCurrent(generation)) return@post
                _state.update { it.copy(previewAspect = aspect) }
            }
        }
        // Camera health (engine camera/setup threads → StateFlow is thread-safe): dims the shutter
        // while the session is down instead of silently declining taps.
        engine.onCameraReadyChange = readyChange@{ publication ->
            if (!cameraReadyPublicationGate.observe(publication)) return@readyChange
            if (!publication.ready) {
                // False is immediately authoritative. Preserve requested formats during the
                // transition, but clear accepted reader truth until a new owned Ready arrives.
                _state.update {
                    if (cameraReadyPublicationGate.owns(publication)) {
                        it.copy(cameraReady = false, photoSessionOutputs = publication.photoOutputs)
                    } else {
                        it
                    }
                }
            } else {
                mainHandler.post {
                    // A newer optics intent or pause/session reopen can land while this camera-thread
                    // callback is queued for main. Both generations bind its output snapshot.
                    if (!engine.isCameraReadyPublicationCurrent(publication)) return@post
                    var formatStatus: String? = null
                    _state.update { current ->
                        if (!cameraReadyPublicationGate.owns(publication)) return@update current
                        // RAW truth and the pre-TELE return baseline change only when a camera intent
                        // is accepted. Optimistic normalization made a failed TELE-off irreversible.
                        val accepted = acceptedOpticsAuxState(
                            teleconverter = current.teleconverterMode,
                            photoOutputs = publication.photoOutputs,
                            preTeleUnifiedZoom = preTeleUnifiedZoom,
                            photoFormats = current.photoFormats,
                        )
                        preTeleUnifiedZoom = accepted.preTeleUnifiedZoom
                        formatStatus = when {
                            !publication.photoOutputs.hasStillTarget ->
                                "Still capture unavailable in current session"
                            current.photoFormats.wantsProcessedStill &&
                                !accepted.photoFormats.wantsProcessedStill && accepted.photoFormats.dngRaw ->
                                "Processed still unavailable; using DNG"
                            current.photoFormats.dngRaw && !accepted.photoFormats.dngRaw ->
                                "RAW unavailable in current session"
                            else -> null
                        }
                        current.copy(
                            cameraReady = true,
                            photoSessionOutputs = publication.photoOutputs,
                            photoFormats = accepted.photoFormats,
                        )
                    }
                    if (cameraReadyPublicationGate.owns(publication)) formatStatus?.let(::showStatus)
                }
            }
        }
        engine.onOpticsRollback = {
                mode, lens, teleconverter, controls, restoredPhotoExposureTimeNs, overrideId, generation ->
            mainHandler.post {
                if (!engine.isOpticsGenerationCurrent(generation)) return@post
                cancelPendingControls()
                cancelCountdown()
                // The rollback restored a different optics scale: every in-flight glide value is an
                // ABSOLUTE ratio in the failed attempt's scale, so ease target / coalesced base /
                // throttled landing all invalidate together (same invariant as every optics-remap door).
                invalidateZoomGlide()
                clearTapFocus()
                // Engine snapshots this hidden bank inside the same generation-owned transaction as
                // visible optics, so even Ready-callback overlap restores the exact accepted value.
                photoExposureTimeNs = restoredPhotoExposureTimeNs
                _state.update {
                    it.copy(
                        mode = mode,
                        lens = lens,
                        teleconverterMode = teleconverter,
                        controls = controls,
                        cameraOverrideId = overrideId,
                    )
                }
                refreshProgramAppSide()
                scheduleSettingsSave()
            }
        }
        // DEBUG-only app-local zoom injection hook. Keep a receiver reference so ViewModel teardown
        // unregisters it; NOT_EXPORTED prevents arbitrary apps/shell broadcasts from controlling
        // camera framing while the process is alive.
        if (com.hletrd.findx9tele.BuildConfig.DEBUG) {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                    val ratio = i?.getFloatExtra("ratio", -1f) ?: -1f
                    if (ratio > 0f) applyZoomRatio(ratio)
                }
            }
            debugZoomReceiver = receiver
            app.registerReceiver(
                receiver,
                android.content.IntentFilter("me.hletrd.telecampro.DEBUG_ZOOM"),
                android.content.Context.RECEIVER_NOT_EXPORTED,
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
            // Feed the app-side auto-exposure loop only in the modes that DRIVE from it (PERF4-7):
            // SHUTTER, ISO, and app-side photo-P. MANUAL and video-P made this a ~6 Hz main-thread
            // wakeup into a no-op branch. The luma array is freshly allocated per callback, so it's
            // safe to hand to the main thread.
            val mode = s.controls.exposureMode
            val drivesAppSideAe = mode == ExposureMode.SHUTTER || mode == ExposureMode.ISO ||
                (mode == ExposureMode.PROGRAM && s.controls.programAppSide)
            if (h != null && drivesAppSideAe) mainHandler.post { applyAutoExposure(h.luma) }
        }
        engine.onAudioLevel = { lvl -> _state.update { it.copy(audioLevel = lvl) } }
        engine.onAudioRoute = { route -> _state.update { it.copy(audioRouteLabel = route) } }
        engine.onRecordingStarted = {
            mainHandler.post {
                val current = _state.value
                if (!current.isRecording || !current.isRecordingStarting) return@post
                recordStartMs = SystemClock.elapsedRealtime()
                mainHandler.removeCallbacks(recordTicker)
                _state.update { it.copy(isRecordingStarting = false, recordElapsedMs = 0) }
                mainHandler.post(recordTicker)
            }
        }
        engine.onRecordingTerminated = {
            // Codec/muxer failures originate on recorder drain threads. End REC state on main
            // immediately; finalization continues on the engine's dedicated executor.
            mainHandler.post {
                mainHandler.removeCallbacks(recordTicker)
                _state.update {
                    it.copy(
                        isRecording = false,
                        isRecordingStarting = false,
                        recordElapsedMs = 0,
                        audioRouteLabel = audioInputStatusLabel(it.audioInputPreference),
                    )
                }
                refreshStandbyAudioMeter()
            }
        }
        // AE-resolved ISO/shutter (auto mode) for the live dial readout; camera thread → StateFlow is
        // thread-safe, Compose observes on main. The controller only fires this on change.
        engine.onExposureInfo = { iso, exp -> _state.update { it.copy(liveIso = iso, liveExposureNs = exp) } }
        // Live lens focus distance for the Focus chip readout + the AF→MF handoff seed (camera
        // thread → StateFlow is thread-safe, same as the exposure readout above).
        engine.onFocusDistance = { d -> _state.update { it.copy(liveFocusDiopters = d) } }
        // Every successful processed/video output participates in capture-id-ordered review
        // ownership. It upgrades a RAW placeholder for the same capture, but cannot displace a newer
        // capture whose callback arrived first.
        engine.onMediaSaved = { uri, captureId ->
            recordCaptureOutput(uri, captureId, CaptureOutputKind.DISPLAYABLE)
        }
        // RAW-only is still a successful capture: a newer DNG owns a truthful RAW review tile until a
        // processed sibling upgrades it. A late RAW never displaces a processed/newer owner.
        engine.onRawSaved = { uri, captureId ->
            recordCaptureOutput(uri, captureId, CaptureOutputKind.RAW)
        }
        restoreSettingsIfEnabled()
        refreshProgramAppSide()
        // Restore the newest photo, RAW-only shot, or video and seed all proven siblings before
        // publishing review. Legacy filenames stay one-file delete scopes. Shared io lane, not a
        // bare Thread (PERF4-6).
        ioExecutor.execute execute@{
            val restored = MediaStoreWriter.latestOwnCapture(getApplication()) ?: return@execute
            val priorOutputs = restored.outputs.map { output ->
                PriorCaptureOutput(
                    output = output.output,
                    kind = when (output.kind) {
                        StoredMediaOutputKind.DISPLAYABLE -> CaptureOutputKind.DISPLAYABLE
                        StoredMediaOutputKind.RAW -> CaptureOutputKind.RAW
                    },
                )
            }
            val preferred = restored.preferred.output
            if (!captureOutputs.seedPriorCapture(priorOutputs, preferred)) return@execute
            val deleteScope = when (restored.deleteScope) {
                RestoredDeleteScope.CAPTURE_FAMILY -> MediaDeleteScope.CAPTURE_FAMILY
                RestoredDeleteScope.FILE_ONLY -> MediaDeleteScope.FILE_ONLY
            }
            _state.update {
                if (it.lastMediaUri == null && captureOutputs.isCurrentReviewOutput(preferred)) {
                    it.copy(lastMediaUri = preferred, lastMediaDeleteScope = deleteScope)
                } else {
                    it
                }
            }
        }
        refreshMemorySlotInfo()
        // Sweep any pending media orphaned by a prior crash/force-kill (record stop never ran).
        engine.cleanupOrphans()
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
        val requestedLens = when {
            preservedTeleconverter -> LensChoice.TELE3X
            honorPreserveOptions && !e.preserveLensSelection -> defaults.lens
            else -> e.lens
        }
        val requestedTeleconverter = requestedLens == LensChoice.TELE3X && preservedTeleconverter
        // Settings restore is the one path that bypasses the pickers' gating, so re-validate the
        // UI-gated enum values before they reach the engine: FPS_120 remains a dormant enum/session
        // path for schema compatibility and diagnostics, but the shipping picker excludes it because
        // rebuilding the constrained session SIGABRTs this HAL. A persisted codec the device can't
        // mux (APV) would likewise break recording.
        val safeFrameRate = if (e.videoFrameRate.highSpeed) ExtraSettings().videoFrameRate else e.videoFrameRate
        val safeCodec =
            if (com.hletrd.findx9tele.video.EncoderCaps.availableCodecs().contains(e.videoCodec)) e.videoCodec
            else ExtraSettings().videoCodec
        // Keep the exposure fps in lockstep with the restored video rate (mirrors onVideoFrameRate;
        // restoring them independently let the AE/shutter-angle math run at a stale fps).
        // If a launch-time preserve option deliberately changed the saved optics, reset framing to
        // that resolved home. Otherwise (including every MR recall) restore the exact saved zoom.
        val preserveChangedOptics = honorPreserveOptions && (
            (!e.preserveTeleconverter && e.teleconverter) ||
                (!e.preserveLensSelection && !requestedTeleconverter)
            )
        val requestedZoom = if (preserveChangedOptics) {
            if (requestedTeleconverter || e.mode == CaptureMode.VIDEO) 1f else requestedLens.zoomPreset
        } else {
            c.zoomRatio
        }
        val restoredOptics = restoredOptics(e.mode, requestedLens, requestedTeleconverter, requestedZoom)
        val restoredLens = restoredOptics.lens
        val restoredTeleconverter = restoredOptics.teleconverter
        // Clamp only when the currently accepted session is the same route as the restored target.
        // Outgoing caps are not authoritative across mode/lens recalls: applying a 0.5 s Video-lens
        // ceiling to a 4 s Photo bank would permanently destroy the photographer's saved shutter.
        // Target-route normalization still runs before that route publishes Ready.
        val currentState = _state.value
        val currentCapsDescribeTarget = restoredRouteUsesCurrentCaps(
            cameraReady = currentState.cameraReady,
            currentMode = currentState.mode,
            currentLens = currentState.lens,
            currentTeleconverter = currentState.teleconverterMode,
            currentOverrideId = currentState.cameraOverrideId,
            targetMode = e.mode,
            targetLens = restoredLens,
            targetTeleconverter = restoredTeleconverter,
        )
        val lastCapsExp = currentState.caps
            ?.takeIf { currentCapsDescribeTarget }
            ?.controlCapabilities()
        val expMin = lastCapsExp?.exposureTimeMinNs
        val expMax = lastCapsExp?.exposureTimeMaxNs
        val restoredExposure = restoredExposureState(
            targetMode = e.mode,
            activeExposureTimeNs = c.exposureTimeNs,
            storedPhotoExposureTimeNs = e.photoExposureTimeNs,
            authoritativeMinNs = expMin,
            authoritativeMaxNs = expMax,
        )
        val previousPhotoExposureTimeNs = photoExposureTimeNs
        photoExposureTimeNs = restoredExposure.photoExposureTimeNs
        val cSynced = c.copy(
            fps = safeFrameRate.fps,
            zoomRatio = restoredOptics.zoomRatio,
            exposureTimeNs = restoredExposure.activeExposureTimeNs,
        ).normalizedForCaptureMode(e.mode)
        val restoredVideoSize = parseVideoResolution(e.videoResolution)
        // Resolution and hidden Photo exposure join the optics transaction. A synchronous REC
        // rejection or asynchronous camera rollback must leave neither rejected bank behind.
        val opticsAccepted = engine.setResolvedOptics(
            enabledVideo = e.mode == CaptureMode.VIDEO,
            resolvedLens = restoredLens,
            resolvedTeleconverter = restoredTeleconverter,
            resolvedControls = cSynced,
            resolvedPhotoExposureTimeNs = photoExposureTimeNs,
            recalledVideoSize = restoredVideoSize,
        )
        if (!opticsAccepted) {
            photoExposureTimeNs = previousPhotoExposureTimeNs
            return
        }
        // The recalled packet supersedes a delayed manual-control snapshot from the prior setup.
        // These callbacks share the main queue, so cancelling immediately after synchronous
        // admission still precedes any stale trailing apply without mutating a rejected recall.
        cancelPendingControls()
        cancelCountdown()
        // MR recall / settings restore can change mode/lens/TC — i.e. the zoom SCALE. Any glide still
        // easing toward a target computed in the old scale (or a throttled landing about to fire) would
        // visibly drag the just-recalled framing away from the preset (same invariant as every remap door).
        invalidateZoomGlide()
        clearTapFocus()
        // Manual/priority modes need luma analysis even when scopes are hidden: priority AE drives
        // from it, and full manual uses it for the live exposure meter.
        engine.setAeMetering(exposureAnalysisRequired(cSynced))
        applyEngineTransfer(e.mode, e.transfer)
        engine.setGammaAssist(e.gammaAssist)
        engine.setVideoStabMode(e.videoStabMode)
        engine.setAspectRatio(e.aspectRatio)
        engine.setDriveMode(e.driveMode)
        engine.setIntervalSec(e.intervalSec)
        engine.setPeaking(e.focusPeaking)
        engine.setPeakingLevel(e.peakingLevel)
        engine.setPeakingColor(e.peakingColor)
        engine.setZebra(e.zebra)
        engine.setZebraLevel(e.zebraLevel)
        engine.setFalseColor(e.falseColor)
        engine.setAnalysis(e.histogram, e.waveform)
        engine.setPunchIn(e.punchIn)
        engine.setTeleFinder(e.teleFinder)
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
        _state.update {
            it.copy(
                rememberSettings = rememberSettings ?: it.rememberSettings,
                controls = cSynced,
                transfer = e.transfer,
                photoFormats = PhotoFormats(e.heif, e.jpeg, e.dngRaw).withDefaultIfEmpty(),
                mode = e.mode,
                lens = restoredLens,
                teleconverterMode = restoredTeleconverter,
                videoStabMode = e.videoStabMode,
                aspectRatio = e.aspectRatio,
                timer = e.timer,
                driveMode = e.driveMode,
                intervalSec = e.intervalSec,
                focusPeaking = e.focusPeaking,
                peakingLevel = e.peakingLevel,
                peakingColor = e.peakingColor,
                zebra = e.zebra,
                zebraLevel = e.zebraLevel,
                falseColor = e.falseColor,
                histogram = e.histogram,
                waveform = e.waveform,
                grid = e.grid,
                level = e.level,
                punchIn = e.punchIn,
                teleFinder = e.teleFinder,
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
        mainHandler.removeCallbacks(levelTicker)
        if (e.level && lifecycleStarted) mainHandler.post(levelTicker)
        mainHandler.removeCallbacks(clearStatusRunnable)
        statusDisplayDurationMs(status)?.let { durationMs ->
            mainHandler.postDelayed(clearStatusRunnable, durationMs)
        }
    }

    private fun currentExtras(): ExtraSettings = _state.value.let { s ->
        ExtraSettings(
            transfer = s.transfer,
            heif = s.photoFormats.heif,
            jpeg = s.photoFormats.jpeg,
            dngRaw = s.photoFormats.dngRaw,
            mode = s.mode,
            photoExposureTimeNs = if (s.mode == CaptureMode.PHOTO) {
                s.controls.exposureTimeNs
            } else {
                photoExposureTimeNs
            },
            lens = s.lens,
            teleconverter = s.teleconverterMode,
            videoStabMode = s.videoStabMode,
            aspectRatio = s.aspectRatio,
            timer = s.timer,
            driveMode = s.driveMode,
            intervalSec = s.intervalSec,
            focusPeaking = s.focusPeaking,
            peakingLevel = s.peakingLevel,
            peakingColor = s.peakingColor,
            zebra = s.zebra,
            zebraLevel = s.zebraLevel,
            falseColor = s.falseColor,
            histogram = s.histogram,
            waveform = s.waveform,
            grid = s.grid,
            level = s.level,
            punchIn = s.punchIn,
            teleFinder = s.teleFinder,
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

    private fun publishStatus(message: String?) {
        _state.update { it.copy(statusMessage = message) }
        mainHandler.removeCallbacks(clearStatusRunnable)
        statusDisplayDurationMs(message)?.let { durationMs ->
            mainHandler.postDelayed(clearStatusRunnable, durationMs)
        }
    }

    private fun showStatus(message: String) = publishStatus(message)

    private fun rejectIfRecording(message: String): Boolean {
        if (!_state.value.isRecording) return false
        showStatus(message)
        return true
    }

    fun onAppStatus(message: String) = showStatus(message)

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
        if (s.teleconverterMode) "300 mm" else "${s.lens.targetEquivMm.toInt()} mm"

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
    override fun onFocusMode(mode: FocusMode) {
        if (focusModeChangeClearsTapPoint(_state.value.controls.focusMode, mode)) {
            clearTapFocus()
        }
        updateControls(FnSlot.FOCUS) { c ->
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
    }
    override fun onFocusSlider(slider: Float) {
        if (focusModeChangeClearsTapPoint(_state.value.controls.focusMode, FocusMode.MANUAL)) {
            clearTapFocus()
        }
        val min = _state.value.caps?.minFocusDistanceDiopters ?: 0f
        val d = FocusMapping.sliderToDiopters(slider, min)
        updateControls(FnSlot.FOCUS) { it.copy(focusDistanceDiopters = d, focusMode = FocusMode.MANUAL) }
    }
    override fun onAfLock(locked: Boolean) = updateControls(FnSlot.FOCUS) { it.copy(afLock = locked) }
    override fun onTapFocus(nx: Float, ny: Float) {
        engine.setTapPoint(nx, ny)
        _state.update { it.copy(tapPoint = nx to ny, tapFocusHeld = true) }
        reticleHideRunnable?.let { mainHandler.removeCallbacks(it) }
        // The 2 s timer is VISUAL ONLY (keep the viewfinder quiet): it hides the reticle but must
        // NOT release the AF hold, the metering region, or the loupe center. The tapped focus
        // holds until a new tap, a focus-mode change, an explicit reset, or an optics remap —
        // the documented pro-camera lock semantics (AGG4-3; the old timer silently returned AF to
        // AF-C hunting 2 s after every tap and re-centered the loupe mid-composition).
        val hide = Runnable {
            _state.update { it.copy(tapPoint = null) }
            reticleHideRunnable = null
        }
        reticleHideRunnable = hide
        mainHandler.postDelayed(hide, 2000)
    }

    override fun onResetFocusPoint() = clearTapFocus()

    /**
     * The FUNCTIONAL tap-AF release: drops the AF_MODE_AUTO hold + metering region and re-centers
     * the loupe, plus the visual reticle. Called on explicit reset and from every optics-remap
     * door (mode/lens/TC/camera-override) — a tapped point is meaningless in the new field of
     * view, and since the 2 s reticle timer no longer resets the loupe, the doors are what keep a
     * stale tap-centered loupe from surviving a remap.
     */
    private fun clearTapFocus() {
        reticleHideRunnable?.let(mainHandler::removeCallbacks)
        reticleHideRunnable = null
        engine.clearTapPoint()
        _state.update { it.copy(tapPoint = null, tapFocusHeld = false) }
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
    override fun onWbMode(mode: WbMode) {
        customWbSampleGeneration++
        updateControls(FnSlot.WB) { it.copy(wbMode = mode) }
    }
    override fun onWbKelvin(kelvin: Int) {
        customWbSampleGeneration++
        updateControls(FnSlot.WB) { it.copy(wbKelvin = kelvin, wbMode = WbMode.MANUAL) }
    }
    override fun onWbTint(tint: Int) {
        customWbSampleGeneration++
        updateControls(FnSlot.WB) { it.copy(wbTint = tint, wbMode = WbMode.MANUAL) }
    }
    override fun onToggleAwbLock(locked: Boolean) {
        customWbSampleGeneration++
        updateControls(FnSlot.WB) { it.copy(awbLock = locked) }
    }
    override fun onMeteringMode(mode: MeteringMode) = updateControls(FnSlot.METERING) { it.copy(meteringMode = mode) }
    override fun onAfSpotSize(size: AfSpotSize) = updateControls(persist = true) { it.copy(afSpotSize = size) }
    override fun onCaptureCustomWb() {
        val current = _state.value
        val availability = controlAvailability(current.caps?.controlCapabilities(), current.controls)
        if (!current.cameraReady) {
            showStatus("Camera reconfiguring")
            return
        }
        if (!availability.customWbCaptureEnabled) {
            showStatus("Use Auto WB with AWB Lock off")
            return
        }
        // Land a just-selected AUTO/unlocked packet before the controller registers its tagged
        // request. Both posts share the camera handler, so the sample cannot race stale manual gains.
        drainPendingControls()
        val generation = ++customWbSampleGeneration
        engine.requestCustomWbSample { sample ->
            mainHandler.post {
                if (generation != customWbSampleGeneration) return@post
                if (sample == null) {
                    showStatus("Custom WB not measured")
                    return@post
                }
                val applied = engine.consumeCustomWbSampleIfCurrent(sample) { gains ->
                    updateControls(FnSlot.WB) {
                        it.copy(wbMode = WbMode.CUSTOM, customWbGains = gains)
                    }
                }
                showStatus(if (applied) "Custom WB set" else "Custom WB not measured")
            }
        }
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
        zoomGlide.easeTarget = null
        applyZoomRatio(ratio)
    }
    // Pinch events arrive at INPUT rate (up to ~120 Hz on this panel); applying each one drove a
    // whole-tree recomposition plus a setRepeatingRequest per event — the residual zoom jank after
    // the fast path landed. Coalesce: apply the first event immediately (no perceived latency),
    // then flush only the NEWEST value every 16 ms (~60 Hz) while the gesture continues. The plain
    // glide state lives in the [zoomGlide] holder (declared above init) so every optics-scale remap
    // door invalidates it through the single invalidateZoomGlide() owner (AGG3-51).


    private fun applyZoomRatio(ratio: Float): Float {
        val s = _state.value
        val range = s.caps?.zoomRatioRange
        val bounds = effectiveZoomBounds(range?.lower, range?.upper, s.teleconverterMode)
        val z = normalizeZoomRequest(ratio, currentZoomBase(), bounds, s.teleconverterMode)
        zoomGlide.pendingRatio = z
        if (zoomGlide.flushScheduled) return z // the scheduled flush picks up this newest value
        zoomGlide.flushScheduled = true
        flushZoom() // leading edge: first tick lands instantly
        mainHandler.postDelayed(zoomTrailingFlush, 16) // ~60 Hz: engine throttles HAL submits; GL follows
        return z
    }


    private fun flushZoom() {
        val z = zoomGlide.pendingRatio
        if (z.isNaN()) return
        // Zoom-OUT leading edge (AGG3-9, single-submit form per AGG4-1): GL zoomComp magnifies the
        // delivered frame instantly for zoom-IN but CANNOT widen past the delivered crop, so a
        // zoom-out's first tick must reach the HAL promptly. The old form submitted a fast-path
        // request here AND then setZoomInteraction(true)'s boost flip ran a full startPreview
        // rebuild carrying the same ratio — two back-to-back ~180 ms repeating-request stalls at
        // every fresh pinch-out (3-lane cycle-4 consensus). Now the leading tick only COMMITS the
        // ratio (engine controls + GL target + still-truth, no submit); the boost rebuild right
        // below is the edge's ONE submit and carries this z as its finalZoom. Mid-gesture ticks
        // keep the coalesced/throttled wide-aim path unchanged.
        val leadingWide = zoomGlide.isLeadingEdgeToWide(z, _state.value.controls.zoomRatio)
        if (leadingWide) engine.commitZoomForBoost(z)
        if (!zoomGlide.interacting) {
            zoomGlide.interacting = true
            engine.setZoomInteraction(true)
        }
        mainHandler.removeCallbacks(zoomInteractionEnd)
        mainHandler.postDelayed(zoomInteractionEnd, 700)
        mainHandler.removeCallbacks(zoomQuietLanding)
        mainHandler.postDelayed(zoomQuietLanding, 250)
        // Straight to the engine fast path (cached-builder resubmit) — updateControls would re-apply
        // the FULL control set. The leading zoom-OUT tick already submitted above; every other tick
        // submits here. Chip highlight follows the zoom band only on the seamless (photo) camera;
        // video zoom is lens-local. Persistence rides the debounced settings save.
        if (!leadingWide) engine.setZoomRatio(z)
        val s = _state.value
        val lensBand = if (!s.teleconverterMode && s.mode == CaptureMode.PHOTO) LensChoice.forZoom(z) else s.lens
        _state.update { it.copy(controls = it.controls.copy(zoomRatio = z), lens = lensBand) }
        // A pending throttled full-apply captured OLDER controls — refresh its zoom so it can't
        // briefly snap the ratio back when it lands.
        pendingControls = pendingControls?.copy(zoomRatio = z)
        markChanged(FnSlot.ZOOM)
        scheduleSettingsSave()
    }

    /** One hardware zoom-key repeat: nudge the ease target and make sure the glide ticker runs. */
    fun onHardwareZoomStep(factor: Float) {
        val range = _state.value.caps?.zoomRatioRange ?: return
        val bounds = effectiveZoomBounds(range.lower, range.upper, _state.value.teleconverterMode) ?: return
        val base = zoomGlide.easeTarget ?: currentZoomBase()
        val wasIdle = zoomGlide.easeTarget == null
        zoomGlide.easeTarget = (base * factor).coerceIn(bounds.lower, bounds.upper)
        if (wasIdle) mainHandler.post(zoomEaseTicker)
    }

    override fun onPinchZoom(factor: Float) {
        // Pinch multiplies the FRESHEST zoom (the coalesced pending value, NOT UI state): state only
        // updates at the 16 ms flush, and compounding each input event against that stale base made
        // the zoom crawl between flushes then jump at the boundary — the residual pinch jank.
        val range = _state.value.caps?.zoomRatioRange ?: return
        val next = (currentZoomBase() * factor).coerceIn(range.lower, range.upper)
        onZoomRatio(next)
    }

    /**
     * The freshest zoom value: the coalesced pending ratio while a flush window is open (UI state
     * lags it by up to 16 ms), else the state value. Every compounding zoom input (pinch factor,
     * hardware-key step, ease ticker) must use THIS as its base.
     */
    private fun currentZoomBase(): Float = zoomGlide.base(_state.value.controls.zoomRatio)

    /**
     * One-shot teardown of the whole zoom-glide lifecycle, called from EVERY optics-SCALE remap door
     * (mode / lens / TC / MR-recall / rollback / camera-override) AND onStop. A scale remap makes
     * every in-flight glide value an ABSOLUTE ratio in the OLD scale: the coalesced pending ratio, the
     * hardware-key ease target, and the throttled quiet-landing / interaction-end / 16 ms-flush
     * Runnables would each submit an old-scale ratio (or run a wasted AE/AF rebuild) through whatever
     * controller is live — plausibly the OUTGOING one, since a full reopen outlasts these 16/250/700 ms
     * callbacks (AGG3-10/TRC-1). This is the single door prior cycles hand-duplicated at ~10 sites and
     * forgot at several (AGG3-25/26/51, VER-3, ARCH-4): it clears every plain field via the holder and
     * cancels every matching timer.
     *
     * It deliberately does NOT call engine.setZoomInteraction(false): a synchronous boost-off would
     * fire setSmoothPreviewBoost(false) → a full startPreview() rebuild on a controller the remap may
     * discard. Resetting the ViewModel-side `interacting` flag is enough. A structural reopen gets a
     * fresh boost=false controller through `wireController`; a same-route commit goes through
     * `commitRetainedOpticsControls`, which folds exact controls and boost removal into its one
     * camera-thread request update. `resume()` covers an onStop-mid-gesture lifecycle return.
     */
    private fun invalidateZoomGlide() {
        zoomGlide.invalidateForRemap()
        mainHandler.removeCallbacks(zoomEaseTicker)
        mainHandler.removeCallbacks(zoomTrailingFlush)
        mainHandler.removeCallbacks(zoomQuietLanding)
        mainHandler.removeCallbacks(zoomInteractionEnd)
    }

    override fun onJpegQuality(quality: Int) = updateControls(persist = true) { it.copy(jpegQuality = quality) }

    // ---- Modes ----
    override fun onModeChange(mode: CaptureMode) {
        cancelCountdown()
        if (_state.value.isRecording && mode != _state.value.mode) {
            showStatus("Stop REC first")
            return
        }
        // Invalidate any delayed full-controls packet before resolving the transition; otherwise it
        // can overwrite the new mode's lens-local/unified zoom after the reconfigure is queued.
        cancelPendingControls()
        val before = _state.value
        val exposureState = modeExposureState(
            fromMode = before.mode,
            toMode = mode,
            controls = before.controls,
            rememberedPhotoExposureTimeNs = photoExposureTimeNs,
        )
        photoExposureTimeNs = exposureState.photoExposureTimeNs
        val optics = remapModeOptics(
            fromMode = before.mode,
            toMode = mode,
            lens = before.lens,
            teleconverter = before.teleconverterMode,
            controls = exposureState.controls,
        )
        _state.update {
            it.copy(mode = mode, lens = optics.lens, controls = optics.controls)
        }
        // The mode remap invalidated the zoom SCALE — the coalesced base, any hardware-key glide whose
        // absolute target was set in the old scale, and any throttled quiet-landing / interaction-end
        // that would otherwise submit an old-scale ratio through the outgoing controller (AGG3-10/25).
        invalidateZoomGlide()
        clearTapFocus()
        engine.setVideoMode(
            enabled = mode == CaptureMode.VIDEO,
            resolvedLens = optics.lens,
            resolvedControls = optics.controls,
            resolvedPhotoExposureTimeNs = photoExposureTimeNs,
        )
        applyEngineTransfer(mode, _state.value.transfer)
        refreshProgramAppSide() // photo P is app-side (min-shutter rule), video P is HAL AE
        // refreshProgramAppSide is intentionally a no-op when the already-published flag matches;
        // the analysis pipeline still needs an explicit mode-boundary update in that case.
        engine.setAeMetering(exposureAnalysisRequired(_state.value.controls))
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
        cancelCountdown()
        val s = _state.value
        _state.update {
            it.copy(
                photoFormats = formats.normalizedFor(s.photoSessionOutputs),
                activeMemorySlot = null,
            )
        }
        scheduleSettingsSave()
    }
    override fun onAspectRatio(ratio: AspectRatio) {
        cancelCountdown()
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
        cancelCountdown()
        drainPendingControls()
        // TELE pins the STANDALONE 3× camera (the converter's host lens; digital-only zoom, afocal
        // flip). OFF restores the EXACT pre-TELE framing — lens band + ratio in whatever mode is
        // active (mirrors the engine's unified-zoom snapshot; user-required round-trip fidelity).
        engine.setLens(com.hletrd.findx9tele.camera.LensChoice.TELE3X, enabled, restorePreTele = !enabled)
        _state.update {
            if (enabled) {
                preTeleUnifiedZoom = if (it.mode == CaptureMode.VIDEO) {
                    it.lens.zoomPreset * it.controls.zoomRatio.coerceAtLeast(1f)
                } else {
                    it.controls.zoomRatio
                }
                it.copy(
                    teleconverterMode = true,
                    lens = com.hletrd.findx9tele.camera.LensChoice.TELE3X,
                    controls = it.controls.copy(zoomRatio = 1f),
                )
            } else {
                val unified = preTeleUnifiedZoom.takeIf { z -> !z.isNaN() }
                    ?: com.hletrd.findx9tele.camera.LensChoice.TELE3X.zoomPreset
                val band = LensChoice.forZoom(unified)
                it.copy(
                    teleconverterMode = false,
                    lens = band,
                    controls = it.controls.copy(
                        zoomRatio = if (it.mode == CaptureMode.VIDEO) {
                            (unified / band.zoomPreset).coerceAtLeast(1f)
                        } else {
                            unified
                        },
                    ),
                )
            }
        }
        // The TC scale flip overwrote the coalesced base and invalidated any hardware-key glide /
        // throttled landing set in the pre-flip scale (same invariant as every optics-remap door).
        invalidateZoomGlide()
        clearTapFocus()
        markChanged(FnSlot.TELECONVERTER)
        saveSettingsIfEnabled()
    }
    // UI mirror of the engine's pre-TELE framing snapshot (unified main-relative zoom).
    private var preTeleUnifiedZoom = Float.NaN

    override fun onLens(choice: LensChoice) {
        if (rejectIfRecording("Stop REC first")) return
        cancelCountdown()
        drainPendingControls()
        // A lens pick is a ZOOM PRESET on the logical seamless camera (no reopen, no black gap).
        // TELE stays on only when it already is AND the pick is its 3× host lens; any other pick
        // exits converter shooting back to the seamless camera.
        val keepTc = _state.value.teleconverterMode && choice == LensChoice.TELE3X
        engine.setLens(choice, keepTc)
        _state.update {
            it.copy(
                lens = choice,
                teleconverterMode = keepTc,
                // Same engine mirror as onToggleTeleconverter: video lens picks are lens-local (1×).
                controls = it.controls.copy(
                    zoomRatio = if (keepTc || it.mode == CaptureMode.VIDEO) 1f else choice.zoomPreset,
                ),
            )
        }
        // The lens-preset rewrite overwrote the coalesced base and invalidated any hardware-key glide /
        // throttled landing set in the pre-pick scale (same invariant as every optics-remap door).
        invalidateZoomGlide()
        clearTapFocus()
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
        if (!engine.setVideoResolution(size)) return
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
        val current = _state.value
        val requested = current.controls.copy(fps = rate.fps)
        val controls = (current.caps?.let(requested::normalizedFor) ?: requested)
            .normalizedForCaptureMode(current.mode)
        engine.setControls(controls)
        // Re-base any pending throttled dial apply onto the new fps: the 40 ms trailing apply would
        // otherwise push its STALE snapshot (old fps) over this direct engine write moments later.
        pendingControls = pendingControls
            ?.copy(fps = rate.fps)
            ?.normalizedForCaptureMode(current.mode)
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

    private fun reconcileZoomToCaps(caps: CameraCaps) {
        val current = _state.value
        val range = caps.zoomRatioRange
        val normalizedControls = normalizeControlsForRoute(
            requested = current.controls,
            capabilities = caps.controlCapabilities(),
            mode = current.mode,
            teleconverter = current.teleconverterMode,
            capsLower = range?.lower,
            capsUpper = range?.upper,
        )
        val lens = if (current.mode == CaptureMode.PHOTO && !current.teleconverterMode) {
            LensChoice.forZoom(normalizedControls.zoomRatio)
        } else {
            current.lens
        }
        _state.update {
            it.copy(caps = caps, lens = lens, controls = normalizedControls)
        }
        pendingControls = pendingControls?.let { pending ->
            normalizeControlsForRoute(
                requested = pending,
                capabilities = caps.controlCapabilities(),
                mode = current.mode,
                teleconverter = current.teleconverterMode,
                capsLower = range?.lower,
                capsUpper = range?.upper,
            )
        }
        if (normalizedControls != current.controls) {
            engine.setAeMetering(exposureAnalysisRequired(normalizedControls))
            engine.setControls(normalizedControls)
            // Do NOT re-base a LIVE gesture's coalesced target (TR4-3): a caps callback landing
            // between a gesture's first flush and its 16 ms trailing flush would overwrite the
            // user's in-flight pending ratio with the route-normalized committed one, visibly
            // nudging the pinch. Idle pending values (interacting false) still re-base.
            if (!zoomGlide.pendingRatio.isNaN() && !zoomGlide.interacting) {
                zoomGlide.pendingRatio = normalizedControls.zoomRatio
            }
        }
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
        scheduleSettingsSave()
    }
    override fun onPeakingLevel(level: PeakingLevel) {
        engine.setPeakingLevel(level)
        _state.update { it.copy(peakingLevel = level) }
        markChanged(FnSlot.PEAKING)
        scheduleSettingsSave()
    }
    override fun onPeakingColor(color: PeakingColor) {
        engine.setPeakingColor(color)
        _state.update { it.copy(peakingColor = color) }
        markChanged(FnSlot.PEAKING)
        scheduleSettingsSave()
    }
    override fun onToggleZebra(enabled: Boolean) {
        engine.setZebra(enabled)
        _state.update { it.copy(zebra = enabled) }
        markChanged(FnSlot.ZEBRA)
        scheduleSettingsSave()
    }
    override fun onZebraLevel(level: ZebraLevel) {
        engine.setZebraLevel(level)
        _state.update { it.copy(zebraLevel = level) }
        markChanged(FnSlot.ZEBRA)
        scheduleSettingsSave()
    }
    override fun onToggleFalseColor(enabled: Boolean) {
        engine.setFalseColor(enabled)
        _state.update { it.copy(falseColor = enabled, activeMemorySlot = null) }
        scheduleSettingsSave()
    }
    override fun onToggleHistogram(enabled: Boolean) {
        engine.setAnalysis(enabled, _state.value.waveform)
        _state.update { it.copy(histogram = enabled, activeMemorySlot = null) }
        scheduleSettingsSave()
    }
    override fun onToggleWaveform(enabled: Boolean) {
        engine.setAnalysis(_state.value.histogram, enabled)
        _state.update { it.copy(waveform = enabled, activeMemorySlot = null) }
        scheduleSettingsSave()
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
        if (enabled && lifecycleStarted) mainHandler.post(levelTicker)
        markChanged(FnSlot.LEVEL)
        scheduleSettingsSave()
    }
    override fun onTogglePunchIn(enabled: Boolean) {
        engine.setPunchIn(enabled)
        _state.update { it.copy(punchIn = enabled) }
        markChanged(FnSlot.PUNCH_IN)
        scheduleSettingsSave()
    }
    override fun onToggleTeleFinder(enabled: Boolean) {
        engine.setTeleFinder(enabled)
        _state.update { it.copy(teleFinder = enabled) }
        scheduleSettingsSave()
    }

    // ---- Drive ----
    override fun onTimer(timer: ShutterTimer) {
        cancelCountdown()
        _state.update { it.copy(timer = timer, activeMemorySlot = null) }
        scheduleSettingsSave()
    }
    override fun onDriveMode(mode: DriveMode) {
        cancelCountdown()
        engine.setDriveMode(mode)
        _state.update { it.copy(driveMode = mode) }
        markChanged(FnSlot.DRIVE)
        scheduleSettingsSave()
    }
    override fun onIntervalSec(sec: Int) {
        cancelCountdown()
        engine.setIntervalSec(sec)
        _state.update { it.copy(intervalSec = sec) }
        markChanged(FnSlot.DRIVE)
        scheduleSettingsSave()
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
        val formats = _state.value.photoFormats
        if (engine.capturePhoto(formats)) {
            _state.update { it.copy(shutterFlashTick = it.shutterFlashTick + 1) }
        }
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
        val state = _state.value
        dispatchPhotoShutter(
            countdownSeconds = state.timerCountdownSec,
            stillCaptureReady = state.stillCaptureReady,
            configuredDelaySeconds = state.timer.seconds,
            cancelCountdown = ::cancelCountdown,
            // The engine's decline path surfaces the authoritative session status when a
            // preview-only session has no still target; no impossible countdown is started.
            fireShutter = ::fireShutterWithFeedback,
            startCountdown = ::startCountdown,
        )
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
            _state.update {
                it.copy(
                    isRecording = false,
                    isRecordingStarting = false,
                    recordElapsedMs = 0,
                    audioRouteLabel = audioInputStatusLabel(it.audioInputPreference),
                )
            }
        } else {
            val s = _state.value
            val inputStatus = audioInputStatus(s.audioInputPreference)
            // Optimistic starting state FIRST (starting-and-stoppable, no tally until the first
            // real encoder swap): engine admission now runs on the recorder executor — the mic
            // handoff wait and codec/muxer construction used to jank main at every REC press —
            // and a refused admission resets this state through the result callback (which may
            // run synchronously for an immediate refusal).
            _state.update {
                it.copy(
                    audioRouteLabel = if (s.recordAudio) "Starting..." else "Off",
                    isRecording = true,
                    isRecordingStarting = true,
                    recordElapsedMs = 0,
                )
            }
            if (s.recordAudio && !inputStatus.available) {
                showStatus("${inputStatus.label}; using default")
            }
            // THREAD CONTRACT: this callback runs on the RECORDER EXECUTOR for a queued admission,
            // or synchronously on MAIN for an immediate refusal. Its body must stay limited to
            // thread-safe primitives (StateFlow.update, Handler.removeCallbacks, the engine's own
            // gated calls) — main-confined ViewModel fields must not be touched here.
            engine.startRecording(s.recordAudio) { ok ->
                if (!ok) {
                    mainHandler.removeCallbacks(recordTicker)
                    _state.update {
                        it.copy(
                            isRecording = false,
                            isRecordingStarting = false,
                            recordElapsedMs = 0,
                            audioRouteLabel = audioInputStatusLabel(it.audioInputPreference),
                        )
                    }
                    refreshStandbyAudioMeter()
                }
            }
        }
        refreshStandbyAudioMeter()
    }

    override fun onCameraOverride(id: String?) {
        cancelCountdown()
        drainPendingControls()
        // A camera-id override reopens onto a different route (different zoom scale): abandon any
        // in-flight coalesced/gliding zoom the same way every other optics-remap door does.
        invalidateZoomGlide()
        clearTapFocus()
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

    override fun onReviewOpenChange(open: Boolean, uri: Uri): Boolean {
        // Pin before publishing the modal state: a concurrent capture callback may trim ordinary
        // history, but it cannot evict the exact family the confirmation copy now describes.
        val familyPinned = if (open) {
            captureOutputs.pinForReview(uri)
        } else {
            captureOutputs.releaseReviewPin(uri)
            false
        }
        _state.update {
            it.copy(
                reviewOpen = open,
                // Block immediately on open; Compose clears the shared gate after the last modal
                // closes, so review dismissal cannot briefly unblock a still-visible sheet/Fn menu.
                cameraInputBlocked = if (open) true else it.cameraInputBlocked,
            )
        }
        return familyPinned
    }

    override fun onCameraInputBlockedChange(blocked: Boolean) {
        _state.update { it.copy(cameraInputBlocked = blocked) }
    }

    override fun onDeleteLastMedia(uri: Uri) {
        // Freeze ownership and tombstone the id BEFORE the Binder calls. Any slower HEIF/JPEG/DNG
        // callback for the shot is then rejected and deleted instead of replacing the thumbnail.
        val deletePlan = captureOutputs.beginDelete(uri)
        val outputs = deletePlan.outputs
        // The open overlay can still hold the RAW URI after a processed sibling upgraded the
        // thumbnail. Clear whichever sibling currently owns review, not only the tapped URI.
        _state.update {
            if (it.lastMediaUri in outputs) {
                it.copy(lastMediaUri = null, lastMediaDeleteScope = MediaDeleteScope.FILE_ONLY)
            } else {
                it
            }
        }
        ioExecutor.execute {
            val survivors = outputs.filterTo(linkedSetOf()) { output ->
                !MediaStoreWriter.delete(getApplication(), output)
            }
            val restored = captureOutputs.restoreDeleteSurvivors(deletePlan, survivors)
            mainHandler.post {
                if (restored != null) {
                    _state.update { current ->
                        if (captureOutputs.isCurrentReviewOutput(restored)) {
                            current.copy(
                                lastMediaUri = restored,
                                lastMediaDeleteScope = if (deletePlan.captureId != null) {
                                    MediaDeleteScope.CAPTURE_FAMILY
                                } else {
                                    MediaDeleteScope.FILE_ONLY
                                },
                            )
                        } else {
                            current
                        }
                    }
                }
                showStatus(
                    when {
                        survivors.isEmpty() -> "Deleted"
                        restored != null -> "Some media could not be deleted — retry from review"
                        else -> "Some media could not be deleted — retry in Gallery"
                    },
                )
            }
        }
    }

    private fun recordCaptureOutput(uri: Uri, captureId: Int, kind: CaptureOutputKind) {
        when (captureOutputs.record(captureId, uri, kind)) {
            CaptureOutputDecision.DELETE -> deleteLateCaptureOutput(uri)
            CaptureOutputDecision.TRACK_ONLY -> Unit
            CaptureOutputDecision.REVIEW -> {
                // Callbacks arrive on independent camera/io/recorder threads. Recheck inside the
                // StateFlow CAS transform so a newer selection cannot be overwritten by an older
                // callback that was descheduled between tracker admission and UI publication.
                _state.update {
                    if (captureOutputs.isCurrentReviewOutput(uri)) {
                        it.copy(
                            lastMediaUri = uri,
                            lastMediaDeleteScope = MediaDeleteScope.CAPTURE_FAMILY,
                        )
                    } else {
                        it
                    }
                }
            }
        }
    }

    private fun deleteLateCaptureOutput(uri: Uri) {
        ioExecutor.execute {
            if (!MediaStoreWriter.delete(getApplication(), uri)) {
                mainHandler.post { showStatus("Could not delete a late shot file") }
            }
        }
    }

    // [persist] defaults to "has an Fn slot": user-facing setters WITHOUT a slot (antibanding, AF
    // spot size, flash, JPEG quality, …) pass persist = true explicitly — they mutate persisted
    // fields too, and a Recents swipe-kill right after (say) a flash toggle silently lost it.
    private fun updateControls(
        slot: FnSlot? = null,
        persist: Boolean = slot != null,
        block: (ManualControls) -> ManualControls,
    ) {
        val current = _state.value
        val requested = block(current.controls)
        val updated = (current.caps?.let(requested::normalizedFor) ?: requested)
            .normalizedForCaptureMode(current.mode)
        engine.setAeMetering(exposureAnalysisRequired(updated))
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
        val exposureUpperNs = expRange?.let {
            exposureUpperBoundForCaptureMode(s.mode, c.fps, it.upper)
        }
        when (c.exposureMode) {
            ExposureMode.SHUTTER -> if (isoRange != null) {
                AutoExposure.driveIso(luma, c.iso, isoRange.lower, isoRange.upper, evStops)?.let { newIso ->
                    updateControls { it.copy(iso = newIso) }
                }
            }
            ExposureMode.ISO -> if (expRange != null && exposureUpperNs != null && exposureUpperNs >= expRange.lower) {
                AutoExposure.driveShutterNs(luma, c.effectiveExposureNs(), expRange.lower, exposureUpperNs, evStops)?.let { newNs ->
                    updateControls { it.copy(exposureTimeNs = newNs) }
                }
            }
            ExposureMode.PROGRAM -> if (c.programAppSide && isoRange != null && expRange != null &&
                exposureUpperNs != null && exposureUpperNs >= expRange.lower
            ) {
                AutoExposure.driveProgram(
                    luma, c.iso, c.effectiveExposureNs(), preferredProgramShutterNs(s),
                    isoRange.lower, isoRange.upper, expRange.lower, exposureUpperNs, evStops,
                )?.let { (newIso, newNs) ->
                    updateControls { it.copy(iso = newIso, exposureTimeNs = newNs) }
                }
            }
            else -> Unit
        }
    }

    // ---- Lifecycle ----
    fun onStart() {
        if (lifecycleStarted) return
        lifecycleStarted = true
        refreshAudioInputStatus()
        engine.resume()
        refreshStandbyAudioMeter()
        // Re-arm the OSD tickers paused in onStop (level only if its overlay is enabled).
        mainHandler.removeCallbacks(levelTicker)
        mainHandler.removeCallbacks(orientationTicker)
        mainHandler.removeCallbacks(infoTicker)
        if (_state.value.level) mainHandler.post(levelTicker)
        mainHandler.post(orientationTicker)
        mainHandler.post(infoTicker)
    }
    fun onStop() {
        if (!lifecycleStarted) return
        lifecycleStarted = false
        customWbSampleGeneration++
        cancelCountdown()
        // engine.pause() finalizes any in-flight recording; keep the UI in sync so we don't return
        // to a phantom "recording" state with the timer still ticking.
        if (_state.value.isRecording) {
            mainHandler.removeCallbacks(recordTicker)
            _state.update { it.copy(isRecording = false, isRecordingStarting = false, recordElapsedMs = 0) }
        }
        // Nothing renders while backgrounded, but these self-rescheduling tickers kept waking the
        // main thread every 100/200 ms (and 10 s) indefinitely — pure battery/Doze cost. Paused
        // here, re-armed in onStart. The zoom glide is abandoned too (its target is stale by resume).
        mainHandler.removeCallbacks(levelTicker)
        mainHandler.removeCallbacks(orientationTicker)
        mainHandler.removeCallbacks(infoTicker)
        // Full glide teardown (AGG3-26/VER-3/ARCH-4): earlier onStop reset only the ease target and
        // left zoomPendingRatio / the 16 ms flush / the interacting flag / zoomInteractionEnd live, so a
        // background mid-pinch leaked a stale base into resume and stuck the boost edge off.
        invalidateZoomGlide()
        saveSettingsIfEnabled() // persist on background so the next launch restores them
        engine.setStandbyAudioMonitor(false) // release the mic while backgrounded
        engine.pause()
    }

    override fun onCleared() {
        mainHandler.removeCallbacksAndMessages(null)
        debugZoomReceiver?.let { receiver -> runCatching { getApplication<Application>().unregisterReceiver(receiver) } }
        debugZoomReceiver = null
        engine.detachCallbacks()
        // Ordered camera/codec/GL release contains bounded joins and can legitimately take seconds.
        // ViewModel teardown runs on main, so transfer ownership to a dedicated non-daemon thread.
        val ownedEngine = engine
        runCatching {
            Thread({ ownedEngine.release() }, "camera-engine-release").start()
        }.onFailure {
            // Thread creation failure is exceptional; preserve resource correctness as the fallback.
            ownedEngine.release()
        }
        // Let already-queued MediaStore deletes finish, then retire the lane (daemon thread, so a
        // shutdown that never drains cannot block process exit).
        runCatching { ioExecutor.shutdown() }
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

internal fun focusModeChangeClearsTapPoint(current: FocusMode, requested: FocusMode): Boolean =
    current != requested

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

/** Analysis readback is needed only for app-owned exposure or the manual meter. */
internal fun exposureAnalysisRequired(controls: ManualControls): Boolean =
    controls.exposureMode != ExposureMode.PROGRAM || controls.programAppSide

/**
 * Handheld-safe shutter target (ns) for app-side PROGRAM: the 1/(35mm-equivalent focal) rule at the
 * effective focal length (native × teleconverter magnification). Pure for unit tests.
 */
internal fun preferredProgramShutterNs(lensEquivMm: Float, teleconverterMode: Boolean): Long {
    val eff = lensEquivMm * (if (teleconverterMode) com.hletrd.findx9tele.camera.TELECONVERTER_MAGNIFICATION else 1f)
    return (1_000_000_000f / eff.coerceAtLeast(1f)).toLong()
}
