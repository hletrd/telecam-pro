package me.hletrd.telecampro.ui

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import me.hletrd.telecampro.camera.Antibanding
import me.hletrd.telecampro.camera.AfIndication
import me.hletrd.telecampro.camera.AspectRatio
import me.hletrd.telecampro.camera.AudioInputPreference
import me.hletrd.telecampro.camera.BitrateLevel
import me.hletrd.telecampro.camera.BackOpticsRefusal
import me.hletrd.telecampro.camera.CameraCaps
import me.hletrd.telecampro.camera.CameraEngine
import me.hletrd.telecampro.camera.CameraFacing
import me.hletrd.telecampro.camera.CameraReadyPublication
import me.hletrd.telecampro.camera.CameraReadyPublicationGate
import me.hletrd.telecampro.camera.backOpticsDoorRefusal
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.CaptureMode
import me.hletrd.telecampro.camera.ColorEffect
import me.hletrd.telecampro.camera.ColorTransfer
import me.hletrd.telecampro.camera.DriveMode
import me.hletrd.telecampro.camera.AfSpotSize
import me.hletrd.telecampro.camera.AutoExposure
import me.hletrd.telecampro.camera.ExposureMode
import me.hletrd.telecampro.camera.ExposureStep
import me.hletrd.telecampro.camera.FrameLineType
import me.hletrd.telecampro.camera.effectiveExposureNs
import me.hletrd.telecampro.camera.FlashMode
import me.hletrd.telecampro.camera.FnSlot
import me.hletrd.telecampro.BuildConfig
import me.hletrd.telecampro.camera.FocusDetailData
import me.hletrd.telecampro.camera.FocusMode
import me.hletrd.telecampro.camera.GridType
import me.hletrd.telecampro.camera.HardwareKeyAction
import me.hletrd.telecampro.camera.LensChoice
import me.hletrd.telecampro.camera.ManualControls
import me.hletrd.telecampro.camera.MediaDeleteScope
import me.hletrd.telecampro.camera.MeteringMode
import me.hletrd.telecampro.camera.MemorySlot
import me.hletrd.telecampro.camera.PeakingColor
import me.hletrd.telecampro.camera.PeakingLevel
import me.hletrd.telecampro.camera.PhotoFormats
import me.hletrd.telecampro.camera.PendingControlsDisposition
import me.hletrd.telecampro.camera.acceptedOpticsAuxState
import me.hletrd.telecampro.camera.controlAvailability
import me.hletrd.telecampro.camera.controlCapabilities
import me.hletrd.telecampro.camera.previewBrightnessSimulationSaturated
import me.hletrd.telecampro.camera.normalizeControlsForRoute
import me.hletrd.telecampro.camera.normalizeAudioGain
import me.hletrd.telecampro.camera.normalizeFnSlots
import me.hletrd.telecampro.camera.normalizedFor
import me.hletrd.telecampro.camera.normalizedForCaptureMode
import me.hletrd.telecampro.camera.pendingControlsForTransition
import me.hletrd.telecampro.camera.seedExposureForRouteChange
import me.hletrd.telecampro.camera.exposureUpperBoundForCaptureMode
import me.hletrd.telecampro.camera.withDefaultIfEmpty
import me.hletrd.telecampro.camera.ProcessingLevel
import me.hletrd.telecampro.camera.ShutterMode
import me.hletrd.telecampro.camera.ShutterTimer
import me.hletrd.telecampro.camera.TapFocusPublicationGate
import me.hletrd.telecampro.camera.TeleconverterProfile
import me.hletrd.telecampro.camera.PhoneModel
import me.hletrd.telecampro.camera.defaultConverterFor
import me.hletrd.telecampro.camera.detectPhone
import me.hletrd.telecampro.camera.effectiveMagnification
import me.hletrd.telecampro.camera.normalizeMagnification
import me.hletrd.telecampro.camera.reconcileConverter
import me.hletrd.telecampro.camera.teleDisplayBase
import me.hletrd.telecampro.camera.VideoCodec
import me.hletrd.telecampro.camera.VideoFrameRate
import me.hletrd.telecampro.camera.WbMode
import me.hletrd.telecampro.camera.ZebraLevel
import me.hletrd.telecampro.camera.rearReturnZoom
import me.hletrd.telecampro.camera.rawSelectable
import me.hletrd.telecampro.ui.controls.bitrateLevelLabel
import me.hletrd.telecampro.ui.controls.formatFocalMm
import me.hletrd.telecampro.ui.controls.transferLabel
import me.hletrd.telecampro.ui.controls.videoResolutionLabel
import me.hletrd.telecampro.ui.overlays.photoFormatLabel
import me.hletrd.telecampro.focus.FocusMapping
import me.hletrd.telecampro.focus.MACRO_HOLD_MS
import me.hletrd.telecampro.focus.FocusConfidenceHold
import me.hletrd.telecampro.focus.focusConfidenceCandidate
import me.hletrd.telecampro.focus.frameDefocusCandidate
import me.hletrd.telecampro.focus.macroTooCloseCandidate
import me.hletrd.telecampro.storage.ExtraSettings
import me.hletrd.telecampro.storage.MediaStoreWriter
import me.hletrd.telecampro.storage.RestoredDeleteScope
import me.hletrd.telecampro.storage.SettingsStore
import me.hletrd.telecampro.storage.StoredMediaOutputKind
import me.hletrd.telecampro.video.AudioInputInspector
import me.hletrd.telecampro.video.audioUnavailableLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Holds [CameraUiState] and turns [CameraActions] into [CameraEngine] calls. UI-thread only. */
// The engine is a defaulted constructor parameter (the ONE test seam this class exposes): host
// tests inject or observe it while production behavior is unchanged. @JvmOverloads emits the
// plain (Application) overload that androidx's reflective AndroidViewModelFactory requires — the
// viewModels() construction path never sees the two-arg constructor.
class CameraViewModel @JvmOverloads constructor(
    app: Application,
    private val engine: CameraEngine = CameraEngine(app),
) : AndroidViewModel(app), CameraActions {

    private val cameraReadyPublicationGate = CameraReadyPublicationGate()
    private val tapFocusPublicationGate = TapFocusPublicationGate()
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

    // ---- Camera-switch dip (ui/SwitchCoverPolicy.kt) ----
    // Main-confined: Ready publications already post here, and the Not-Ready branch is posted below
    // so both the fold and its two timers share one thread. [switchCoverSequence] repeats the gate's
    // latest-wins rule locally — the two branches are delivered from DIFFERENT engine threads, so
    // their arrival order on main is not the order they were minted in, and an inverted pair would
    // otherwise raise a cover the newer publication had already released.
    private var switchCover = SwitchCoverState()
    private var switchCoverSequence = 0L
    private var switchCoverDeadlineEpoch = 0L
    private val switchCoverGraceRunnable = Runnable {
        // The grace expired with the cover still owed: only now does black reach the screen.
        if (switchCover.covered) _state.update { it.copy(switchCoverVisible = true) }
    }
    private val switchCoverDeadlineRunnable = Runnable {
        applySwitchCover(switchCover.onReleaseDeadline(switchCoverDeadlineEpoch))
    }

    /**
     * Installs a folded [SwitchCoverState] and re-arms its timers on the RISING edge only, so the
     * repeated Not-Ready publications of one reopen cannot restart the fade or extend the deadline.
     */
    private fun applySwitchCover(next: SwitchCoverState) {
        val raised = next.covered && !switchCover.covered
        val released = !next.covered && switchCover.covered
        switchCover = next
        if (raised) {
            switchCoverDeadlineEpoch = next.epoch
            mainHandler.removeCallbacks(switchCoverGraceRunnable)
            mainHandler.removeCallbacks(switchCoverDeadlineRunnable)
            mainHandler.postDelayed(switchCoverGraceRunnable, SWITCH_COVER_GRACE_MS)
            mainHandler.postDelayed(switchCoverDeadlineRunnable, SWITCH_COVER_RELEASE_DEADLINE_MS)
        } else if (released) {
            mainHandler.removeCallbacks(switchCoverGraceRunnable)
            mainHandler.removeCallbacks(switchCoverDeadlineRunnable)
            _state.update { if (it.switchCoverVisible) it.copy(switchCoverVisible = false) else it }
        }
    }

    /** Folds one camera-health publication into the dip. Main thread only. */
    private fun foldSwitchCover(publication: CameraReadyPublication) {
        if (publication.sequence <= switchCoverSequence) return
        switchCoverSequence = publication.sequence
        applySwitchCover(
            switchCover.onPublication(
                ready = publication.ready,
                sessionGeneration = publication.sessionGeneration,
                opticsGeneration = publication.opticsGeneration,
            ),
        )
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
    private var standbyMeterVisible = false
    private var standbyMeterEnabled = false
    // Main-confined identity for optimistic REC UI. A queued refusal may arrive after stop/new-start
    // or lifecycle teardown; only the exact attempt that submitted it may reconcile the state.
    private var recordingAttemptGeneration = 0L
    private var debugZoomReceiver: android.content.BroadcastReceiver? = null
    private var debugZslSpikeReceiver: android.content.BroadcastReceiver? = null
    // Main-thread token for the one-shot Custom-WB sample. Any newer WB action makes an older
    // controller callback inert before it can publish gains or a stale status message.
    private var customWbSampleGeneration = 0L

    // Auto-dismisses the transient status toast ("Video saved" / errors) so it doesn't hang
    // on screen forever (QA: "video saved" stuck). Each new message re-arms the 2 s timer.
    private val clearStatusRunnable = Runnable { _state.update { it.copy(statusMessage = null) } }

    private var reticleHideRunnable: Runnable? = null
    // Tap publications can originate on camera/setup/main threads while the visual timeout runs on
    // main. Keep timer ownership and its StateFlow mutation atomic so an already-running old timeout
    // cannot erase a newer accepted point after removeCallbacks loses that race.
    private val tapFocusUiTimerLock = Any()

    // Owns every processed/raw URI for a capture and tombstones deleted ids so a late save callback
    // cannot resurrect a sibling after the user deleted the frozen review shot.
    private val captureOutputs = CaptureOutputTracker<Uri>(CAPTURE_OUTPUT_HISTORY)

    // The plain zoom-glide state (pending / ease / interacting / flush-scheduled) as one tested holder
    // so every optics-scale remap door invalidates it through the single invalidateOpticsDerivedState() owner
    // (AGG3-51). Declared BEFORE init: applyLoaded()/restoreSettingsIfEnabled() runs during
    // construction and calls invalidateOpticsDerivedState(), which dereferences this — a later declaration
    // would leave it null and NPE on a launch that restores saved settings.
    private val zoomGlide = ZoomGlideState()

    // The ONE android.os.Build.MODEL read in the app (see seedTeleconverterProfile): it may only
    // pre-select which teleconverter entry starts SELECTED. Declared BEFORE init for the same
    // reason as the zoom state above — seedTeleconverterProfile() runs during construction.
    private val deviceModel: String = android.os.Build.MODEL.orEmpty()

    // Resolved once from [deviceModel]. Kept as a FIELD, not recomputed at each use, because it is
    // the reference every later write of `phoneModel` compares against: the caption may claim a
    // detection only while the SELECTED phone is still the one Build.MODEL actually named. Without
    // that comparison, overriding the dropdown to "Other phone" left the caption asserting
    // "Detected Other phone." — a detection the app never made.
    private val detectedPhone: PhoneModel? = detectPhone(deviceModel)

    // The 16 ms trailing coalescer flush, held as a NAMED Runnable so a remap door / onStop can
    // cancel it — the old anonymous postDelayed lambda had no reference to remove (AGG3-26).
    // All four zoom Runnables are declared BEFORE the init block (CRIT4-12): invalidateOpticsDerivedState()
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
    // It also RE-ARMS the zoom-OUT leading edge (AGG4-14): reaching here means the pipeline went
    // quiet for a full throttle window, which is the only re-arm signal available to the input paths
    // with no finger-up (hardware slide-zoom key repeats, the ease ticker). onPinchEnd re-arms
    // sooner when touch actually reports the boundary; whichever lands first wins, both are idempotent.
    private val zoomQuietLanding = Runnable {
        zoomGlide.leadingEdgeArmed = true
        engine.landExactZoom()
    }

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

    // Focus-confidence tag: the hold turns two flickery instantaneous signals — the AF/lens-position
    // one (AF_LIMIT) and the frame-detail one (FRAME_DETAIL) — into one stable OSD tag (700 ms
    // persist, instant clear — focus/MacroProximity.kt). Main-thread confined; AF/focus-distance/
    // analysis callbacks post the refresh here, and a pending candidate schedules exactly one
    // delayed re-check so a static scene (no further AF/lens events) still flips the tag on when
    // the hold elapses.
    private val focusConfidenceHold = FocusConfidenceHold()
    private val focusConfidenceRefreshRunnable = Runnable { refreshFocusConfidence() }

    // Newest frame-detail verdict and when it landed. Main-thread confined (written in the
    // onAnalysis main post, read only here), so plain fields are correct and cheap.
    private var lastFocusDetail: FocusDetailData? = null
    // Last traced focus-confidence candidate, so the DEBUG trace only fires on a change. Seeded
    // with a sentinel rather than null: the steady state IS "no candidate", so a null seed made the
    // very first evaluation compare equal and the trace never emitted at all (self-inflicted, found
    // on device 2026-07-25) — the first evaluation is exactly the one worth seeing.
    private val focusTraceUnset = Any()
    private var lastFocusConfidenceTrace: Any? = focusTraceUnset
    // ...plus a 2 s heartbeat: a change-gated trace shows the VERDICT but not the INPUTS moving,
    // and the inputs are what a refusal has to be diagnosed from.
    private var lastFocusTraceAtMs = 0L
    private var lastFocusDetailAtMs: Long = 0L

    // Bumped at every optics door. The GL generation OUTLIVES a route change, so an analysis frame
    // drawn from the outgoing route can still be in flight when the door opens; its main post would
    // otherwise land as fresh (< 1 s) evidence and, via the delayed re-check, publish a verdict
    // about a lens the app is no longer using. Read on the analysis executor, compared on main.
    @Volatile
    private var focusEvidenceEpoch: Long = 0L

    private fun refreshFocusConfidence() {
        val s = _state.value
        val now = SystemClock.uptimeMillis()
        val afLimit = macroTooCloseCandidate(
            afIndication = s.afIndication,
            focusMode = s.controls.focusMode,
            liveFocusDiopters = s.liveFocusDiopters,
            minFocusDiopters = s.caps?.minFocusDistanceDiopters ?: 0f,
        )
        val frameDetail = frameDefocusCandidate(
            detail = lastFocusDetail?.verdict,
            detailAgeMs = if (lastFocusDetail == null) Long.MAX_VALUE else now - lastFocusDetailAtMs,
            focusMode = s.controls.focusMode,
            afIndication = s.afIndication,
            recording = s.isRecording,
            recordingStarting = s.isRecordingStarting,
            zoomInteracting = zoomGlide.interacting,
            // Result metadata, NOT controls.effectiveExposureNs(): the analysed frame rode the
            // trade-capped PREVIEW exposure, while the intended still can be seconds long.
            exposureNs = s.liveExposureNs,
            handheldShutterNs = preferredProgramShutterNs(s),
        )
        val candidate = focusConfidenceCandidate(afLimit = afLimit, frameDetail = frameDetail)
        // DEBUG-only verdict trace. Without it an on-device check can only observe the TAG, so a
        // silent detector is indistinguishable from a refused one — and every refusal here is a
        // deliberate gate (dark frame, unjudgeable scene, mid-scan, stale stats) whose firing you
        // need to SEE to trust. Change-gated: this runs on every AF event and every ~6 Hz analysis
        // tick, and an unconditional line would burn ColorOS's 300-row process quota outright.
        if (BuildConfig.DEBUG &&
            (candidate != lastFocusConfidenceTrace || now - lastFocusTraceAtMs > 2_000L)
        ) {
            lastFocusConfidenceTrace = candidate
            lastFocusTraceAtMs = now
            Log.i(
                "FocusConfidence",
                "candidate=$candidate afLimit=$afLimit frameDetail=$frameDetail " +
                    "verdict=${lastFocusDetail?.verdict} tiles=${lastFocusDetail?.judgeableTiles}" +
                    "/${lastFocusDetail?.totalTiles} soft=${lastFocusDetail?.softTiles} " +
                    "bestRatio=${lastFocusDetail?.bestRatio} ageMs=" +
                    (if (lastFocusDetail == null) -1L else now - lastFocusDetailAtMs) +
                    " af=${s.afIndication} focusMode=${s.controls.focusMode}" +
                    " expNs=${s.liveExposureNs} handheldNs=${preferredProgramShutterNs(s)}",
            )
        }
        val show = focusConfidenceHold.update(candidate, now)
        if (focusConfidenceHold.pending(now)) {
            mainHandler.removeCallbacks(focusConfidenceRefreshRunnable)
            mainHandler.postDelayed(focusConfidenceRefreshRunnable, MACRO_HOLD_MS + 20)
        }
        // Emit ONLY on a latched change: this refresh runs on every AF/lens event AND every ~6 Hz
        // analysis tick, so an unconditional _state.update would re-run the whole root
        // recomposition at input rate (PERF4-7).
        if (s.focusConfidence != show) _state.update { it.copy(focusConfidence = show) }
        // Arm the GL-side metric only while the tag could possibly appear. It never forces a
        // readback (it rides the scope/AE one), so this only decides whether the per-pixel
        // curvature math runs at all — a MANUAL-focus shooter pays nothing.
        engine.setFocusDetail(
            focusDetailAnalysisRequired(s.controls.focusMode, s.isRecording, s.isRecordingStarting),
        )
    }

    /**
     * Drops held focus-confidence evidence at an optics door. Analysis frames belong to a ROUTE: a
     * TELE frame must never publish a verdict for the 1× route the app just switched to.
     */
    private fun invalidateFocusConfidence() {
        focusEvidenceEpoch++
        lastFocusDetail = null
        lastFocusDetailAtMs = 0L
        focusConfidenceHold.reset()
        mainHandler.removeCallbacks(focusConfidenceRefreshRunnable)
        if (_state.value.focusConfidence != null) _state.update { it.copy(focusConfidence = null) }
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
        engine.onTapFocusChange = tapFocusChange@{ publication ->
            tapFocusPublicationGate.applyIfLatest(publication) {
                if (publication.held) {
                    publication.point?.let(::showTapFocusUi)
                } else {
                    clearTapFocusUi()
                }
            }
        }
        // Caps arrive on the setup thread. Reconcile restored/schema-normalized zoom against the
        // selected camera's authoritative range on main before any delayed input can reuse it.
        engine.onCapsReady = { caps, generation ->
            mainHandler.post {
                if (!engine.isOpticsGenerationCurrent(generation)) return@post
                reconcileZoomToCaps(caps)
                reconcileFrameRate()
                // Macro hint is static per route: resolve the closer-focusing lens label once per
                // caps delivery, and re-evaluate the tag against the new route's min focus.
                val hintLabel = engine.closerFocusingLensLabel(caps)
                _state.update { it.copy(macroCloserLensLabel = hintLabel) }
                refreshFocusConfidence()
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
            // The switch dip is main-confined and folds BOTH branches; the gate above has already
            // established latest-wins ordering, and foldSwitchCover repeats it against its own
            // sequence because these posts originate on two different engine threads.
            mainHandler.post { foldSwitchCover(publication) }
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
                    // Captured inside the transform, assigned after it (tracer T10): update()
                    // retries on CAS contention, and writing the field mid-transform feeds run 1's
                    // output into run 2's `preTeleUnifiedZoom` input.
                    var acceptedPreTele = Float.NaN
                    var acceptedApplied = false
                    _state.update { current ->
                        // Reset PER ATTEMPT: update() retries on CAS contention, and a retry that
                        // LOSES gate ownership must not leave attempt 1's acceptedApplied=true
                        // behind — the post-transform assignment would then apply a pre-TELE
                        // baseline from a transform that never committed (review L7; the T10
                        // comment above already bans the mirror-image leak).
                        acceptedPreTele = Float.NaN
                        acceptedApplied = false
                        formatStatus = null
                        if (!cameraReadyPublicationGate.owns(publication)) return@update current
                        // RAW truth and the pre-TELE return baseline change only when a camera intent
                        // is accepted. Optimistic normalization made a failed TELE-off irreversible.
                        val accepted = acceptedOpticsAuxState(
                            teleconverter = current.teleconverterMode,
                            photoOutputs = publication.photoOutputs,
                            preTeleUnifiedZoom = preTeleUnifiedZoom,
                            photoFormats = current.photoFormats,
                        )
                        acceptedPreTele = accepted.preTeleUnifiedZoom
                        acceptedApplied = true
                        formatStatus = when {
                            // VIDEO never reports this. Stills during a take are not a feature this
                            // app owes anyone -- a professional body records, it does not offer a
                            // JPEG mid-clip -- and the 10-bit video session deliberately drops the
                            // still readers to buy the bit depth. Announcing that as a loss made a
                            // designed trade read as a fault on every mode switch (user-reported).
                            current.mode == CaptureMode.VIDEO -> null
                            !publication.photoOutputs.hasStillTarget ->
                                "Still capture unavailable"
                            current.photoFormats.wantsProcessedStill &&
                                !accepted.photoFormats.wantsProcessedStill && accepted.photoFormats.dngRaw ->
                                // Word for word the engine's capture-time refusal and the
                                // PhotoFormatToggles caption: this fires on Ready publication and
                                // those fire at the shutter, so one user sees all three for one
                                // output mask.
                                "HEIF/JPEG unavailable; DNG only"
                            // Keyed on whether this route can EVER deliver RAW, not on whether the
                            // session happens to carry it yet: the accepted session no longer edits
                            // the RAW axis (see acceptedOpticsAuxState), and on the logical photo
                            // route a DNG request is about to be honoured by the reopen it triggers,
                            // so "not carrying RAW right now" would announce a fault mid-transition.
                            current.photoFormats.dngRaw && !rawSelectable(
                                deviceSupportsRaw = true,
                                rawInSession = publication.photoOutputs.raw,
                                videoMode = current.mode == CaptureMode.VIDEO,
                                hiResSession = publication.photoOutputs.hiRes,
                                frontFacing = current.facing == CameraFacing.FRONT,
                            ) -> "RAW unavailable"
                            else -> null
                        }
                        current.copy(
                            cameraReady = true,
                            photoSessionOutputs = publication.photoOutputs,
                            photoFormats = accepted.photoFormats,
                        )
                    }
                    if (acceptedApplied) preTeleUnifiedZoom = acceptedPreTele
                    if (cameraReadyPublicationGate.owns(publication)) formatStatus?.let(::showStatus)
                }
            }
        }
        engine.onOpticsRollback = {
                mode, lens, teleconverter, facing, controls, restoredPhotoExposureTimeNs, userPin,
                restoredPreTeleUnifiedZoom, generation ->
            mainHandler.post {
                if (!engine.isOpticsGenerationCurrent(generation)) return@post
                // "Camera unchanged": the failed door never closed the outgoing session, so it is
                // still streaming. Drop the dip now rather than blacking out live picture until the
                // deadline — and remember this generation, because the rollback's OWN trailing
                // Not-Ready (posted right behind this one, from the same thread) carries it.
                applySwitchCover(switchCover.onOpticsRollback(generation))
                cancelPendingControls()
                cancelCountdown()
                // The rollback restored a different optics scale: every in-flight glide value is an
                // ABSOLUTE ratio in the failed attempt's scale, so ease target / coalesced base /
                // throttled landing all invalidate together (same invariant as every optics-remap door).
                invalidateOpticsDerivedState()
                clearTapFocusUi()
                // Engine snapshots this hidden bank inside the same generation-owned transaction as
                // visible optics, so even Ready-callback overlap restores the exact accepted value.
                photoExposureTimeNs = restoredPhotoExposureTimeNs
                // Mirror the engine's restored pre-TELE snapshot: recall resets this mirror eagerly,
                // and without the rollback leg a FAILED recall left NaN here while the engine
                // restored its value — the next TC-off then showed the preset while the wire
                // restored the retained framing (verification S4).
                preTeleUnifiedZoom = restoredPreTeleUnifiedZoom
                _state.update {
                    it.copy(
                        mode = mode,
                        lens = lens,
                        teleconverterMode = teleconverter,
                        facing = facing,
                        controls = controls,
                        // The engine publishes only a GENUINE diagnostic pin here (its routed-target
                        // pin stays internal) — so a routine failed door can no longer surface the
                        // Setup Camera ID row or poison the same-route recall fast path.
                        cameraOverrideId = userPin,
                    )
                }
                refreshProgramAppSide()
                scheduleSettingsSave()
            }
        }
        // DEBUG-only app-local zoom injection hook. Keep a receiver reference so ViewModel teardown
        // unregisters it; NOT_EXPORTED prevents arbitrary apps/shell broadcasts from controlling
        // camera framing while the process is alive. NOTE (device-confirmed 2026-07-25): on API 36
        // NOT_EXPORTED also rejects adb-shell broadcasts (result=0, enqueued, never delivered), so
        // shell-driven debugging goes through MainActivity's DEBUG intent-extra hook instead
        // (`am start ... -f 0x20000000 --ez/-e ...` → debugSetZslSpike/debugApplyZoom below); these
        // receivers remain for app-internal senders.
        if (me.hletrd.telecampro.BuildConfig.DEBUG) {
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
            // Cycle-8 S4a: adb-driven pseudo-ZSL streaming-spike toggle (full-res YUV joins the
            // repeating targets on the logical photo route; stills refused while measuring).
            val zslReceiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                    engine.setZslSpike(i?.getBooleanExtra("enabled", false) ?: false)
                }
            }
            debugZslSpikeReceiver = zslReceiver
            app.registerReceiver(
                zslReceiver,
                android.content.IntentFilter("me.hletrd.telecampro.DEBUG_ZSL_SPIKE"),
                android.content.Context.RECEIVER_NOT_EXPORTED,
            )
        }
        // AF state (camera thread → StateFlow is thread-safe): colors the tap-AF reticle. The
        // macro too-close evaluation is main-thread confined (its hold keeps time state).
        engine.onAfIndication = { ind ->
            _state.update { it.copy(afIndication = ind) }
            mainHandler.post(focusConfidenceRefreshRunnable)
        }
        engine.onAnalysis = { h, w, f ->
            // Publish scope data into UI state only when something actually renders it (the
            // histogram/waveform overlays or the MANUAL-mode exposure meter). App-side AE reads the
            // callback arg directly, so with scopes hidden the ~6 Hz analysis tick no longer forces
            // a whole-CameraUiState emission (root-recomposition churn during manual shooting).
            val s = _state.value
            if (s.histogram || s.waveform || s.controls.exposureMode == ExposureMode.MANUAL) {
                _state.update { it.copy(histogramData = h, waveformData = w) }
            }
            // Frame-detail verdict: stash it (plain fields, read only on main) and re-evaluate the
            // focus-confidence tag there. Note this deliberately does NOT ride the scopes-visible
            // branch above — it is independent of histogram/waveform/MANUAL — and refreshFocus-
            // Confidence emits only when the LATCHED tag changes, so the ~6 Hz tick cannot re-run
            // the root recomposition (PERF4-7).
            if (f != null) {
                val epoch = focusEvidenceEpoch
                mainHandler.post {
                    // Dropped if an optics door opened between the readback and this post.
                    if (focusEvidenceEpoch != epoch) return@post
                    lastFocusDetail = f
                    lastFocusDetailAtMs = SystemClock.uptimeMillis()
                    refreshFocusConfidence()
                }
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
        engine.onStandbyAudioAvailable = {
            mainHandler.post {
                val current = _state.value
                if (!current.isRecording && standbyMeterVisible) {
                    _state.update {
                        it.copy(audioRouteLabel = audioInputStatusLabel(it.audioInputPreference))
                    }
                }
            }
        }
        engine.onStandbyAudioUnavailable = {
            mainHandler.post {
                val current = _state.value
                if (!current.isRecording && standbyMeterVisible) {
                    _state.update {
                        // The canonical helper, not a hand-rolled copy of its format: every Route-row
                        // degradation must keep spelling this one way.
                        it.copy(audioRouteLabel = audioUnavailableLabel(it.audioInputPreference.label))
                    }
                    publishStatus("Standby microphone unavailable")
                }
            }
        }
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
        engine.onFocusDistance = { d ->
            _state.update { it.copy(liveFocusDiopters = d) }
            mainHandler.post(focusConfidenceRefreshRunnable)
        }
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
        // A publish-failed output is deliberately RETAINED for launch recovery — unless its
        // family is already tombstoned (deleted). Then retention would resurrect part of a deleted
        // capture on the next launch (the late-RAW timeline: family deleted before the DNG's
        // pending row even existed, so the C3 delete-time sweep could not have seen it). Only the
        // tombstoned case deletes; a live capture keeps its recovery row (verification S3).
        engine.onStillPublishRetained = { uri, captureId ->
            if (captureOutputs.isDeleted(captureId)) {
                ioExecutor.execute { MediaStoreWriter.delete(getApplication(), uri) }
            }
        }
        seedPhoneModel()
        restoreSettingsIfEnabled()
        refreshProgramAppSide()
        // Sweep prior-process pending rows first, then restore the newest published family. This
        // includes a row adopted by recovery without ever letting provider probes delay Camera2
        // startup. CaptureOutputTracker prevents a late launch result from displacing live output.
        engine.cleanupOrphans {
            restoreLatestPublishedCapture()
        }
        refreshMemorySlotInfo()
        refreshStandbyAudioMeter()
    }

    private fun restoreLatestPublishedCapture() {
        // Legacy filenames stay one-file delete scopes. The ViewModel's ordered I/O lane also keeps
        // this query serialized with review deletion; shutdown rejection simply means teardown won.
        runCatching {
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
        }
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
            if (me.hletrd.telecampro.video.EncoderCaps.availableCodecs().contains(e.videoCodec)) e.videoCodec
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
        // The recalled packet carries its OWN converter, and the TELE zoom ceiling is derived from
        // it — resolve the magnification before anything clamps a zoom against it.
        // SettingsStore already reconciled the persisted pair; re-running it here costs nothing and
        // keeps this path correct for any caller that hands over a hand-built ExtraSettings.
        val restoredConverter = reconcileConverter(e.phoneModel, e.teleconverterProfile)
        val restoredMagnification =
            effectiveMagnification(restoredConverter, e.teleconverterCustomMagnification)
        val restoredOptics = restoredOptics(
            mode = e.mode,
            requestedLens = requestedLens,
            teleconverter = requestedTeleconverter,
            teleconverterMagnification = restoredMagnification,
            savedZoomRatio = requestedZoom,
        )
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
            currentFrontFacing = currentState.facing == CameraFacing.FRONT,
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
        // programAppSide is DERIVED, never persisted, so every loaded packet arrives with it
        // false. Derive it INTO the packet as a pure field write BEFORE setResolvedOptics — the
        // engine transaction then carries the correct flag atomically with the route. It must NOT
        // be re-derived after the fact through refreshProgramAppSide(): that routes the recalled
        // packet through updateControls, which re-normalizes the WHOLE packet against the caps in
        // _state — still the OUTGOING route's at that instant — clamping e.g. a MAIN preset's
        // 5 dpt manual focus to TELE's 0.833 ceiling and persisting the destruction (verification
        // must-fix, 2026-07-30; the "structural recall waits for target-route caps" invariant).
        // The persisted iso/exposureTimeNs are the handoff seed; SPEED is forced for the same
        // reason refreshProgramAppSide forces it — the loop's exposureTimeNs is what requests use.
        val wantAppSideProgram = programShouldRunAppSide(e.mode, c.exposureMode, c.flash)
        val cSynced = c.copy(
            fps = safeFrameRate.fps,
            zoomRatio = restoredOptics.zoomRatio,
            exposureTimeNs = restoredExposure.activeExposureTimeNs,
            programAppSide = wantAppSideProgram,
            shutterMode = if (wantAppSideProgram && c.exposureMode == ExposureMode.PROGRAM) {
                ShutterMode.SPEED
            } else {
                c.shutterMode
            },
        ).normalizedForCaptureMode(e.mode)
        val restoredVideoSize = parseVideoResolution(e.videoResolution)
        // Before setResolvedOptics: the engine's own TELE zoom ceiling and its HAL effective-zoom
        // hint both derive from the converter, so the recalled optic must be in force first — its
        // terminal commit re-normalizes the packet against it. Rolled back below on refusal, like
        // the hidden Photo shutter: a rejected recall must leave NEITHER bank behind.
        val previousMagnification = _state.value.teleconverterMagnification
        engine.setTeleconverterMagnification(restoredMagnification)
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
            engine.setTeleconverterMagnification(previousMagnification)
            return
        }
        // The recalled packet supersedes a delayed manual-control snapshot from the prior setup.
        // These callbacks share the main queue, so cancelling immediately after synchronous
        // admission still precedes any stale trailing apply without mutating a rejected recall.
        cancelPendingControls()
        cancelCountdown()
        // Mirror the engine transaction: setResolvedOptics resets ITS pre-TELE return snapshot, so
        // the VM's copy must drop too (same discipline as the front-camera door). Keeping the old
        // unified zoom here made a later TC-off restore a framing the engine had already forgotten —
        // OSD said one zoom while the wire streamed another, and nothing re-converged until the
        // next gesture.
        preTeleUnifiedZoom = Float.NaN
        // MR recall / settings restore can change mode/lens/TC — i.e. the zoom SCALE. Any glide still
        // easing toward a target computed in the old scale (or a throttled landing about to fire) would
        // visibly drag the just-recalled framing away from the preset (same invariant as every remap door).
        invalidateOpticsDerivedState()
        clearTapFocusUi()
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
        engine.setHiResStill(e.hiResStill)
        engine.setVideoCodec(safeCodec)
        engine.setBitrateLevel(e.bitrateLevel)
        engine.setOpenGate(e.openGate)
        engine.setAudioGain(e.audioGain)
        engine.setAudioScene(e.audioScene)
        engine.setAudioInputPreference(e.audioInputPreference)
        engine.setVideoFrameRate(safeFrameRate)
        // DNG is a ROUTE input, so a RESTORED selection has to reach the engine exactly like a live
        // one. Without this the persisted choice was silently inert on every launch: the sheet
        // showed DNG selected, the session came up on the logical route with raw=false, and the
        // shutter produced outputs=jpg with no DNG at all (found by the 2026-07-29 review pass).
        engine.setRawWanted(PhotoFormats(e.heif, e.jpeg, e.dngRaw).withDefaultIfEmpty().dngRaw)
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
                phoneModel = e.phoneModel,
                // Same re-derivation as the live picker: a restored phone that is not the one this
                // boot detected must not inherit the "Detected …" claim.
                phoneModelDetected = e.phoneModel == detectedPhone,
                teleconverterProfile = restoredConverter,
                teleconverterCustomMagnification = e.teleconverterCustomMagnification,
                // Recall/restore packets are rear-route optics; the engine's setResolvedOptics
                // exits FRONT in the same transaction, so the UI mirrors that here (MR recall
                // stays available while FRONT — it flips back as part of the recall).
                facing = CameraFacing.BACK,
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
                hiResStill = e.hiResStill,
                videoCodec = safeCodec,
                bitrateLevel = e.bitrateLevel,
                videoFrameRate = safeFrameRate,
                videoResolution = restoredVideoSize ?: it.videoResolution,
                openGate = e.openGate,
                recordAudio = e.recordAudio,
                audioGain = normalizeAudioGain(e.audioGain),
                audioScene = e.audioScene,
                gammaAssist = e.gammaAssist,
                frameLines = e.frameLines,
                audioInputPreference = e.audioInputPreference,
                audioRouteLabel = audioInputStatusLabel(e.audioInputPreference),
                photoFnSlots = normalizeFnSlots(e.photoFnSlots, FnSlot.PHOTO_DEFAULT),
                videoFnSlots = normalizeFnSlots(e.videoFnSlots, FnSlot.VIDEO_DEFAULT),
                myMenuSlots = normalizeFnSlots(e.myMenuSlots, FnSlot.MY_MENU_DEFAULT),
                volumeKeyAction = e.volumeKeyAction,
                halfPressAction = e.halfPressAction,
                quickButtonAction = e.quickButtonAction,
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
        // NOTE deliberately NO refreshProgramAppSide() here: the flag is already derived into
        // cSynced above, and calling the refresher would route the recalled packet back through
        // updateControls' whole-packet normalization against the outgoing route's caps (the
        // verification must-fix this replaced).
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
            phoneModel = s.phoneModel,
            teleconverterProfile = s.teleconverterProfile,
            teleconverterCustomMagnification = s.teleconverterCustomMagnification,
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
            hiResStill = s.hiResStill,
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
            quickButtonAction = s.quickButtonAction,
            gammaAssist = s.gammaAssist,
            frameLines = s.frameLines,
            preserveLensSelection = s.preserveLensSelection,
            preserveTeleconverter = s.preserveTeleconverter,
        )
    }

    private fun saveSettingsIfEnabled() {
        val s = _state.value
        if (!s.rememberSettings) return
        // Facing is session-only (never persisted; fresh launch is BACK), so a save landing while
        // FRONT — the background onStop save, or any debounced control change — must persist the
        // REAR optics the next launch will actually restore. The live front-session values (TC
        // forced off, front-local 1×) silently overwrote the retained TELE/zoom setup captured at
        // front entry (cycle-6 debugger F6).
        val substituteRear = s.facing == CameraFacing.FRONT && !preFrontRearZoom.isNaN()
        val controls = if (substituteRear) s.controls.copy(zoomRatio = preFrontRearZoom) else s.controls
        val extras = if (substituteRear) {
            currentExtras().copy(teleconverter = preFrontRearTeleconverter)
        } else {
            currentExtras()
        }
        settingsStore.save(controls, extras)
    }

    private fun readBatteryPct(): Int = runCatching {
        val bm = getApplication<Application>().getSystemService(android.content.Context.BATTERY_SERVICE)
            as android.os.BatteryManager
        bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }.getOrDefault(-1)

    private fun readFreeBytes(): Long = runCatching {
        android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path).availableBytes
    }.getOrDefault(-1L)

    /** Own AudioRecord only while its armed-Video level meter is genuinely visible. */
    private fun refreshStandbyAudioMeter(forceRestart: Boolean = false) {
        val s = _state.value
        val enabled = standbyAudioMeterShouldRun(
            lifecycleStarted = lifecycleStarted,
            visible = standbyMeterVisible,
            mode = s.mode,
            recordAudio = s.recordAudio,
            recording = s.isRecording,
        )
        if (!forceRestart && enabled == standbyMeterEnabled) return
        standbyMeterEnabled = enabled
        engine.setStandbyAudioMonitor(enabled)
    }

    override fun onStandbyAudioMeterVisibilityChanged(visible: Boolean) {
        if (standbyMeterVisible == visible) return
        standbyMeterVisible = visible
        refreshStandbyAudioMeter()
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

    /**
     * Refusal for the REAR-only optics doors (lens presets, TC toggle) — the shared, tested
     * [backOpticsDoorRefusal] decision mapped to the two standard status strings, so the ViewModel
     * and the engine's defensive twin can never disagree on when or why these doors refuse.
     */
    private fun rejectBackOnlyOpticsDoor(): Boolean {
        val message = when (
            backOpticsDoorRefusal(_state.value.isRecording, _state.value.facing == CameraFacing.FRONT)
        ) {
            BackOpticsRefusal.RECORDING -> "Stop REC first"
            BackOpticsRefusal.FRONT_ROUTE -> "Switch to rear camera first"
            BackOpticsRefusal.NONE -> return false
        }
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
        if (s.facing == CameraFacing.FRONT) {
            // [s.lens] retains the REAR band across a front trip; the front lens's own measured
            // equiv (from the accepted route's caps) is the honest 1/focal input, TC never applies.
            preferredProgramShutterNs(
                s.caps?.equivalentFocalMm?.takeIf { it > 0f } ?: LensChoice.MAIN.targetEquivMm,
                teleconverterMode = false,
                teleconverterMagnification = s.teleconverterMagnification,
            )
        } else {
            preferredProgramShutterNs(
                s.lens.targetEquivMm,
                s.teleconverterMode,
                s.teleconverterMagnification,
            )
        }

    private fun audioInputStatus(preference: AudioInputPreference = _state.value.audioInputPreference) =
        AudioInputInspector.status(getApplication(), preference)

    private fun audioInputStatusLabel(preference: AudioInputPreference = _state.value.audioInputPreference): String =
        audioInputStatus(preference).label

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

    // An MR summary is a READ-ONLY restatement of what the other surfaces already show, so every
    // fragment below goes through that surface's canonical formatter. The private re-implementations
    // that used to live here had already drifted: the local video-size bucket printed "1440p" for
    // the 2560×1920 Open Gate size the OSD and Encoder row call "2.5K 4:3", and the focal branch
    // truncated where formatFocalMm rounds.
    private fun presetNameFor(s: CameraUiState): String {
        val focal = focalSummary(s)
        return when (s.mode) {
            CaptureMode.PHOTO -> "Photo $focal"
            CaptureMode.VIDEO -> "Video ${transferLabel(s.transfer)}"
        }
    }

    private fun presetSummaryFor(s: CameraUiState): String = when (s.mode) {
        CaptureMode.PHOTO ->
            "${focalSummary(s)} · ${s.controls.exposureMode.letter} · ${photoFormatLabel(s.photoFormats)}"
        CaptureMode.VIDEO -> {
            "${focalSummary(s)} · ${videoResolutionLabel(s.videoResolution)} ${s.videoFrameRate.label}p · " +
                "${transferLabel(s.transfer)} · ${bitrateLevelLabel(s.bitrateLevel)}"
        }
    }

    private fun focalSummary(s: CameraUiState): String =
        if (s.teleconverterMode) formatFocalMm(s.teleconverterFocalMm)
        else formatFocalMm(s.lens.targetEquivMm)

    /** Parses a persisted "WxH" video-resolution string; null for "" or anything malformed. */
    private fun parseVideoResolution(raw: String): Size? {
        val parts = raw.split('x')
        if (parts.size != 2) return null
        val w = parts[0].toIntOrNull() ?: return null
        val h = parts[1].toIntOrNull() ?: return null
        return if (w > 0 && h > 0) Size(w, h) else null
    }

    private fun markChanged(slot: FnSlot) {
        _state.update { s ->
            val recent = (listOf(slot) + s.recentSettingSlots.filterNot { it == slot }).take(RECENT_SETTING_LIMIT)
            s.copy(recentSettingSlots = recent, activeMemorySlot = null)
        }
    }

    private fun normalizedSlots(slots: List<FnSlot>, fallback: List<FnSlot>): List<FnSlot> =
        normalizeFnSlots(slots, fallback, FN_SLOT_LIMIT)

    // ---- Preview surface ----
    override fun onPreviewSurfaceAvailable(surface: Surface, width: Int, height: Int) =
        engine.onPreviewSurfaceAvailable(surface, width, height)
    override fun onPreviewSurfaceChanged(width: Int, height: Int) = engine.onPreviewSurfaceChanged(width, height)
    override fun onPreviewSurfaceDestroyed() = engine.onPreviewSurfaceDestroyed()

    // ---- Focus ----
    override fun onFocusMode(mode: FocusMode) {
        val before = _state.value
        if (focusModeChangeClearsTapPoint(before.controls.focusMode, mode)) {
            // updateControls below already rebuilds the repeating request for the focus-mode delta.
            // Queue the tap-owned-key clear first, then let that ONE rebuild carry both changes;
            // two back-to-back swaps stall this HAL's preview for ~340-500 ms.
            clearTapFocus(rebuildPreview = false)
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
        // A mode flip changes the too-close candidate (MANUAL excludes it) without any AF event.
        mainHandler.post(focusConfidenceRefreshRunnable)
    }
    override fun onFocusSlider(slider: Float) {
        val before = _state.value
        if (focusModeChangeClearsTapPoint(before.controls.focusMode, FocusMode.MANUAL)) {
            clearTapFocus(rebuildPreview = false)
        }
        val min = _state.value.caps?.minFocusDistanceDiopters ?: 0f
        val d = FocusMapping.sliderToDiopters(slider, min)
        updateControls(FnSlot.FOCUS) { it.copy(focusDistanceDiopters = d, focusMode = FocusMode.MANUAL) }
        // Slider entry into MANUAL clears the too-close candidate without any AF event.
        mainHandler.post(focusConfidenceRefreshRunnable)
    }
    override fun onAfLock(locked: Boolean) = updateControls(FnSlot.FOCUS) { it.copy(afLock = locked) }
    override fun onTapFocus(nx: Float, ny: Float) {
        // Queue admission alone is not AF HOLD: the engine publishes the point through
        // onTapFocusChange only after startPreview actually submits it to Camera2.
        engine.setTapPoint(nx, ny)
    }

    private fun showTapFocusUi(point: Pair<Float, Float>) {
        synchronized(tapFocusUiTimerLock) {
            // Apply SCANNING immediately on the camera-thread publication boundary. Camera2 result
            // callbacks for this request run later on that same serial thread, so a terminal verdict
            // can no longer be overwritten by a delayed main-queue "start scanning" task.
            _state.update { submittedTapFocusUiState(it, point) }
            reticleHideRunnable?.let { mainHandler.removeCallbacks(it) }
            // The 2 s timer is VISUAL ONLY (keep the viewfinder quiet): it hides the reticle but must
            // NOT release the AF hold, the metering region, or the loupe center. The tapped focus
            // holds until a new tap, a focus-mode change, an explicit reset, or an optics remap —
            // the documented pro-camera lock semantics (AGG4-3; the old timer silently returned AF to
            // AF-C hunting 2 s after every tap and re-centered the loupe mid-composition).
            lateinit var hide: Runnable
            hide = Runnable {
                synchronized(tapFocusUiTimerLock) {
                    if (reticleHideRunnable !== hide) return@synchronized
                    _state.update { it.copy(tapPoint = null) }
                    reticleHideRunnable = null
                }
            }
            reticleHideRunnable = hide
            // Handler scheduling/removal is thread-safe; timer ownership above protects the fields
            // and StateFlow update that Android's queue cannot serialize for us.
            mainHandler.postDelayed(hide, 2000)
        }
    }

    override fun onResetFocusPoint() = clearTapFocus()

    /**
     * The FUNCTIONAL tap-AF release: drops the AF_MODE_AUTO hold + metering region and re-centers
     * the loupe, plus the visual reticle. Called for explicit reset and focus-mode changes. Optics
     * remap transactions retire the engine half themselves and call [clearTapFocusUi] for the UI
     * half, allowing the Camera2 reset to fold into the remap's next request.
     */
    private fun clearTapFocus(rebuildPreview: Boolean = true) {
        engine.clearTapPoint(rebuildPreview)
        clearTapFocusUi()
    }

    /** UI half of tap-point retirement; engine callbacks can invalidate it after controller loss. */
    private fun clearTapFocusUi() {
        synchronized(tapFocusUiTimerLock) {
            reticleHideRunnable?.let(mainHandler::removeCallbacks)
            reticleHideRunnable = null
            _state.update { it.copy(tapPoint = null, tapFocusHeld = false) }
        }
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
    // (onToggleAutoExposure was removed: dead API surface — every caller sets ExposureMode directly.)
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
                    showStatus("Custom WB measurement failed")
                    return@post
                }
                val applied = engine.consumeCustomWbSampleIfCurrent(sample) { gains ->
                    updateControls(FnSlot.WB) {
                        it.copy(wbMode = WbMode.CUSTOM, customWbGains = gains)
                    }
                }
                showStatus(if (applied) "Custom WB set" else "Custom WB measurement failed")
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
    // door invalidates it through the single invalidateOpticsDerivedState() owner (AGG3-51).


    // DEBUG shell hooks (called from MainActivity's intent-extra path — the exported launcher
    // activity is the one component adb `am start` can reach; API 36 blocks shell broadcasts to
    // the NOT_EXPORTED debug receivers above). Both no-op in release via the callers' DEBUG gate;
    // the spike additionally re-checks BuildConfig.DEBUG inside CameraController.setZslSpike.
    internal fun debugSetZslSpike(enabled: Boolean) {
        if (!me.hletrd.telecampro.BuildConfig.DEBUG) return
        engine.setZslSpike(enabled)
    }

    internal fun debugApplyZoom(ratio: Float) {
        if (!me.hletrd.telecampro.BuildConfig.DEBUG) return
        if (ratio > 0f) applyZoomRatio(ratio)
    }

    private fun applyZoomRatio(ratio: Float): Float {
        val s = _state.value
        val range = s.caps?.zoomRatioRange
        val bounds = effectiveZoomBounds(
            range?.lower,
            range?.upper,
            s.teleconverterMode,
            s.teleconverterMagnification,
        )
        val z = normalizeZoomRequest(
            requested = ratio,
            currentApplied = currentZoomBase(),
            bounds = bounds,
            teleconverter = s.teleconverterMode,
            teleconverterMagnification = s.teleconverterMagnification,
        )
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
        // ratio (engine controls + GL target + still-truth, no submit); the boost flip right below
        // is the edge's ONE submit and carries this z as its finalZoom (a rebuild on a cold edge, a
        // bare fast-path submit when the boost is already active). Mid-gesture ticks keep the
        // coalesced/throttled wide-aim path unchanged.
        val leadingWide = zoomGlide.isLeadingEdgeToWide(z, _state.value.controls.zoomRatio)
        // One flush spends the edge, whether or not it took it. Idempotent for every later tick of
        // the same gesture; re-armed only by a real pinch-end or the quiet-window landing.
        zoomGlide.leadingEdgeArmed = false
        if (leadingWide) engine.commitZoomForBoost(z)
        // `leadingWide ||` (AGG4-14): a re-pinch that begins inside the previous gesture's 700 ms
        // tail still has `interacting == true`, so the old `!interacting` form would have committed
        // the ratio here and then submitted NOTHING (the `!leadingWide` guard below skips the fast
        // path) — a leading edge strictly worse than no leading edge. Calling in with the boost
        // already active is cheap and deliberate: CameraController.setSmoothPreviewBoost sees
        // `smoothPreviewBoost == active` and takes its fast-path branch — ONE submitZoomFastPath
        // with the exact ratio, no startPreview rebuild. Because the edge is spent by this same
        // flush, a re-pinch costs exactly ONE extra submit however long the gesture runs, so the
        // sustained rate stays inside the ≥200 ms throttle's cost class (a ~260 ms pinch-release
        // cadence is ~4 submits/s against its 5/s ceiling). What it does NOT respect is the
        // throttle's local spacing: landing at Δ250 then re-pinching at Δ300 submits twice ~50 ms
        // apart. That is the trade taken knowingly — the leading edge exists precisely because
        // zoom-OUT has no GL fallback (zoomComp is clamped at 1), so one extra ~180 ms stall beats
        // a crop frozen the wrong way for ~330 ms.
        if (leadingWide || !zoomGlide.interacting) {
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
        // The chip band tracks the unified zoom only on the rear seamless camera; front zoom is
        // lens-local and must not remap the retained rear band (same guard as the engine's
        // reconcileControlsWithCaps).
        val lensBand = if (!s.teleconverterMode && s.mode == CaptureMode.PHOTO && s.facing == CameraFacing.BACK) {
            LensChoice.forZoom(z)
        } else {
            s.lens
        }
        _state.update { it.copy(controls = it.controls.copy(zoomRatio = z), lens = lensBand) }
        // A pending throttled full-apply captured OLDER controls — refresh its zoom so it can't
        // briefly snap the ratio back when it lands.
        pendingControls = pendingControls?.copy(zoomRatio = z)
        markChanged(FnSlot.ZOOM)
        scheduleSettingsSave()
    }

    /** One hardware zoom-key repeat: nudge the ease target and make sure the glide ticker runs. */
    fun onHardwareZoomStep(factor: Float) {
        val s = _state.value
        val range = s.caps?.zoomRatioRange ?: return
        val bounds = effectiveZoomBounds(
            range.lower,
            range.upper,
            s.teleconverterMode,
            s.teleconverterMagnification,
        ) ?: return
        val base = zoomGlide.easeTarget ?: currentZoomBase()
        val wasIdle = zoomGlide.easeTarget == null
        zoomGlide.easeTarget = (base * factor).coerceIn(bounds.lower, bounds.upper)
        if (wasIdle) mainHandler.post(zoomEaseTicker)
    }

    override fun onPinchEnd() {
        // The one signal that is a TRUE gesture boundary. Re-arm the zoom-OUT leading edge now
        // instead of waiting for the 250 ms quiet-window landing, so a re-pinch that begins inside
        // the previous gesture's 700 ms boost tail still gets its immediate outward submit — the
        // window where GL has zero outward headroom (zoomComp is clamped at 1) and the wide-aim
        // margin is already spent. Nothing else moves: the boost tail, the quiet landing, and the
        // 16 ms coalescer all keep their own timing, and the landing's own re-arm stays as the
        // fallback for the paths with no finger-up.
        zoomGlide.leadingEdgeArmed = true
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
     * One-shot teardown of every piece of ROUTE-SCOPED derived state, called from EVERY optics-SCALE
     * remap door (mode / lens / TC / MR-recall / rollback / camera-override) AND onStop.
     *
     * Two things live here because they share EXACTLY that door set, and splitting them would
     * recreate the hand-duplicated-door bug called out below:
     *
     * 1. The zoom glide. A scale remap makes
     * every in-flight glide value an ABSOLUTE ratio in the OLD scale: the coalesced pending ratio, the
     * hardware-key ease target, and the throttled quiet-landing / interaction-end / 16 ms-flush
     * Runnables would each submit an old-scale ratio (or run a wasted AE/AF rebuild) through whatever
     * controller is live — plausibly the OUTGOING one, since a full reopen outlasts these 16/250/700 ms
     * callbacks (AGG3-10/TRC-1). This is the single door prior cycles hand-duplicated at ~10 sites and
     * forgot at several (AGG3-25/26/51, VER-3, ARCH-4): it clears every plain field via the holder and
     * cancels every matching timer.
     *
     * 2. The focus-confidence evidence. Frame-detail verdicts and the AF/lens-position candidate both
     * belong to the ROUTE that produced them: a TELE analysis frame must never publish a verdict for
     * the 1× route the app just switched to, and a held tag must not survive the door that made its
     * evidence meaningless. The new route earns its own 700 ms hold from scratch.
     *
     * It deliberately does NOT call engine.setZoomInteraction(false): a synchronous boost-off would
     * fire setSmoothPreviewBoost(false) → a full startPreview() rebuild on a controller the remap may
     * discard. Resetting the ViewModel-side `interacting` flag is enough. A structural reopen gets a
     * fresh boost=false controller through `wireController`; a same-route commit goes through
     * `commitRetainedOpticsControls`, which folds exact controls and boost removal into its one
     * camera-thread request update. `resume()` covers an onStop-mid-gesture lifecycle return.
     */
    private fun invalidateOpticsDerivedState() {
        zoomGlide.invalidateForRemap()
        mainHandler.removeCallbacks(zoomEaseTicker)
        mainHandler.removeCallbacks(zoomTrailingFlush)
        mainHandler.removeCallbacks(zoomQuietLanding)
        mainHandler.removeCallbacks(zoomInteractionEnd)
        invalidateFocusConfidence()
    }

    override fun onJpegQuality(quality: Int) = updateControls(persist = true) { it.copy(jpegQuality = quality) }

    // ---- Modes ----
    override fun onModeChange(mode: CaptureMode) {
        cancelCountdown()
        // Tapping the already-active mode label is a no-op, not a remap door: the full transition
        // cancels glides, retires tap focus, and republishes optics — a visible hiccup for a tap
        // that asked for nothing (review L8).
        if (mode == _state.value.mode) return
        if (_state.value.isRecording) {
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
            frontFacing = before.facing == CameraFacing.FRONT,
        )
        _state.update {
            it.copy(mode = mode, lens = optics.lens, controls = optics.controls)
        }
        // The mode remap invalidated the zoom SCALE — the coalesced base, any hardware-key glide whose
        // absolute target was set in the old scale, and any throttled quiet-landing / interaction-end
        // that would otherwise submit an old-scale ratio through the outgoing controller (AGG3-10/25).
        invalidateOpticsDerivedState()
        clearTapFocusUi()
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
        // DNG is a ROUTE input: RAW cannot come off the logical photo camera, so wanting it moves
        // the session to a standalone lens. Pushed BEFORE the state write so the reopen it may
        // trigger is already in flight when the UI reflects the new selection.
        engine.setRawWanted(formats.dngRaw)
        _state.update {
            it.copy(
                // NOT normalizedFor(photoSessionOutputs) on the RAW axis any more: those outputs
                // describe the session being replaced, so normalising against them dropped the very
                // selection that asks for the new route.
                photoFormats = formats,
                activeMemorySlot = null,
            )
        }
        scheduleSettingsSave()
    }
    override fun onToggleHiResStill(enabled: Boolean) {
        // Flipping admission rebuilds the Camera2 session (the still reader size is fixed at
        // configureStreams) — the same mid-REC gate every session-reconfiguring control has.
        if (rejectIfRecording("Stop REC first")) return
        cancelCountdown()
        engine.setHiResStill(enabled)
        _state.update { it.copy(hiResStill = enabled, activeMemorySlot = null) }
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
        val normalized = normalizeAudioGain(gain)
        engine.setAudioGain(normalized)
        _state.update { it.copy(audioGain = normalized, activeMemorySlot = null) }
        // Debounced, not immediate: this rides a slider, and a synchronous full-prefs commit per
        // drag frame stuttered the main thread. The trailing save still lands within ~0.5 s.
        scheduleSettingsSave()
    }
    override fun onAudioScene(scene: me.hletrd.telecampro.camera.AudioScene) {
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
        // Explicit route intent owns a fresh bounded standby setup attempt when the meter is shown.
        refreshStandbyAudioMeter(forceRestart = true)
        saveSettingsIfEnabled()
    }
    override fun onToggleTeleconverter(enabled: Boolean) {
        if (rejectBackOnlyOpticsDoor()) return
        cancelCountdown()
        drainPendingControls()
        // TELE pins the STANDALONE 3× camera (the converter's host lens; digital-only zoom, afocal
        // flip). OFF restores the EXACT pre-TELE framing — lens band + ratio in whatever mode is
        // active (mirrors the engine's unified-zoom snapshot; user-required round-trip fidelity).
        engine.setLens(me.hletrd.telecampro.camera.LensChoice.TELE3X, enabled, restorePreTele = !enabled)
        // Captured inside the transform, ASSIGNED after it: MutableStateFlow.update retries on CAS
        // contention, and a field write inside the lambda makes the second run read the first run's
        // output (tracer T10 — idempotent today only by accident of the current math).
        var capturedPreTele = Float.NaN
        var enteredTele = false
        _state.update {
            if (enabled) {
                capturedPreTele = if (it.mode == CaptureMode.VIDEO) {
                    it.lens.zoomPreset * it.controls.zoomRatio.coerceAtLeast(1f)
                } else {
                    it.controls.zoomRatio
                }
                enteredTele = true
                it.copy(
                    teleconverterMode = true,
                    lens = me.hletrd.telecampro.camera.LensChoice.TELE3X,
                    controls = it.controls.copy(zoomRatio = 1f),
                )
            } else {
                val unified = preTeleUnifiedZoom.takeIf { z -> !z.isNaN() }
                    ?: me.hletrd.telecampro.camera.LensChoice.TELE3X.zoomPreset
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
        if (enteredTele) preTeleUnifiedZoom = capturedPreTele
        // The TC scale flip overwrote the coalesced base and invalidated any hardware-key glide /
        // throttled landing set in the pre-flip scale (same invariant as every optics-remap door).
        invalidateOpticsDerivedState()
        clearTapFocusUi()
        markChanged(FnSlot.TELECONVERTER)
        saveSettingsIfEnabled()
    }

    override fun onPhoneModel(model: PhoneModel) {
        if (rejectBackOnlyOpticsDoor()) return
        // The phone narrows the converter list, so a kit for the OUTGOING phone cannot stay selected
        // — and dropping it changes the magnification, which is why this rides the same seam as a
        // converter pick rather than being a plain state write.
        applyTeleconverterOptic(
            phone = model,
            profile = reconcileConverter(model, _state.value.teleconverterProfile),
            custom = _state.value.teleconverterCustomMagnification,
            persistImmediately = true,
        )
    }

    override fun onTeleconverterProfile(profile: TeleconverterProfile) {
        if (rejectBackOnlyOpticsDoor()) return
        // Discrete pick: commit synchronously like every other one-tap optics choice — the next
        // gesture can be a Recents swipe-kill, and apply()'s async write would die with the process.
        applyTeleconverterOptic(
            phone = _state.value.phoneModel,
            profile = profile,
            custom = _state.value.teleconverterCustomMagnification,
            persistImmediately = true,
        )
    }

    override fun onTeleconverterCustomMagnification(value: Float) {
        if (rejectBackOnlyOpticsDoor()) return
        // Ruler drag: bursts of values, so persistence rides the 500 ms trailing debounce.
        applyTeleconverterOptic(
            phone = _state.value.phoneModel,
            profile = _state.value.teleconverterProfile,
            custom = normalizeMagnification(value),
            persistImmediately = false,
        )
    }

    /**
     * The ONE seam that changes which converter the app believes is mounted — phone, converter, or
     * custom magnification, since all three resolve to the same single number.
     *
     * Order matters: the engine (and through it the controller's HAL zoom hint) must know the new
     * optic BEFORE anything re-clamps a zoom against it, because TELE's ceiling is a cap on TOTAL
     * magnification — the LOCAL ratio it permits moves inversely with the converter, so a framing
     * that was legal under the previous optic can be out of range under this one.
     */
    private fun applyTeleconverterOptic(
        phone: PhoneModel,
        profile: TeleconverterProfile,
        custom: Float,
        persistImmediately: Boolean,
    ) {
        val magnification = effectiveMagnification(profile, custom)
        engine.setTeleconverterMagnification(magnification)
        _state.update {
            it.copy(
                phoneModel = phone,
                // Re-derived, never carried: picking a different phone by hand un-claims the
                // detection, and picking the detected one back re-claims it.
                phoneModelDetected = phone == detectedPhone,
                teleconverterProfile = profile,
                teleconverterCustomMagnification = custom,
            )
        }
        // The converter IS the TELE zoom SCALE, so this is an optics-remap door like mode/lens/TC:
        // a hardware-key glide easing toward an ABSOLUTE target set in the old scale, or a throttled
        // landing about to fire, would drag the framing toward an un-commanded value in the new one.
        invalidateOpticsDerivedState()
        reconcileZoomToTeleconverterOptic()
        markChanged(FnSlot.TELECONVERTER)
        if (persistImmediately) saveSettingsIfEnabled() else scheduleSettingsSave()
    }

    /**
     * Re-clamps the live zoom after the converter changed. Only TELE has a converter-derived scale,
     * so every other route is untouched. Deliberately NOT routed through [applyZoomRatio]: that path
     * is the pinch/dial coalescer and would open a zoom INTERACTION (fps boost + a full preview
     * rebuild) for what is a settings pick. The engine fast path is the whole submit.
     */
    private fun reconcileZoomToTeleconverterOptic() {
        val s = _state.value
        if (!s.teleconverterMode) return
        val range = s.caps?.zoomRatioRange
        val bounds = effectiveZoomBounds(
            range?.lower,
            range?.upper,
            teleconverter = true,
            teleconverterMagnification = s.teleconverterMagnification,
        ) ?: return
        val z = s.controls.zoomRatio.coerceIn(bounds.lower, bounds.upper)
        if (z == s.controls.zoomRatio) return
        engine.setZoomRatio(z)
        _state.update { it.copy(controls = it.controls.copy(zoomRatio = z)) }
        // A delayed full-controls packet captured the pre-clamp ratio; refresh it so it cannot snap
        // the framing back out of range when it lands.
        pendingControls = pendingControls?.copy(zoomRatio = z)
    }

    /**
     * First-launch default for the converter PAIR, seeded from the phone.
     *
     * An afocal converter is passive glass on a clamp — no contacts, no ID — so the app can NEVER
     * detect one. What it CAN read is the PHONE, and this is the ONE place in the codebase that does
     * ([detectPhone] itself stays pure). A model match may only choose which entries start SELECTED
     * and license the "Detected …" caption; no capability, route, or request decision may ever
     * branch on a model string (every lens is still resolved by ENUMERATING Camera2 capabilities).
     *
     * Runs before [restoreSettingsIfEnabled], so a persisted pair always wins over this seed. On an
     * unrecognised phone nothing is seeded: the state defaults stand and [phoneModelDetected] stays
     * false, which is exactly what the caption must be able to say.
     */
    private fun seedPhoneModel() {
        val phone = detectedPhone ?: return
        _state.update {
            it.copy(
                phoneModel = phone,
                phoneModelDetected = true,
                teleconverterProfile = defaultConverterFor(phone),
            )
        }
        engine.setTeleconverterMagnification(_state.value.teleconverterMagnification)
    }

    // UI mirror of the engine's pre-TELE framing snapshot (unified main-relative zoom).
    private var preTeleUnifiedZoom = Float.NaN
    // Last REAR optics captured at FRONT entry, substituted into settings saves while FRONT (see
    // saveSettingsIfEnabled). NaN zoom = no snapshot; stale values while rear are simply unused
    // (substitution is gated on facing == FRONT) and re-entry overwrites them.
    private var preFrontRearTeleconverter = false
    private var preFrontRearZoom = Float.NaN

    // Which zoom SCALE preFrontRearZoom was captured in (video = lens-local, photo = unified).
    private var preFrontRearVideoMode = false

    override fun onLens(choice: LensChoice) {
        if (rejectBackOnlyOpticsDoor()) return
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
        invalidateOpticsDerivedState()
        clearTapFocusUi()
        markChanged(FnSlot.TELECONVERTER)
        saveSettingsIfEnabled()
    }

    /**
     * One TELE rail mark. [totalMagnification] arrives in the rail's TOTAL-magnification scale, so it
     * crosses back to the lens-local ratio here and clamps through the same [effectiveZoomBounds]
     * seam the marks were derived from — a drawn mark therefore always lands exactly on itself.
     *
     * Deliberately NOT routed through [applyZoomRatio], for the same reason as
     * [reconcileZoomToTeleconverterOptic]: that path is the pinch/dial coalescer and would open a
     * zoom INTERACTION (fps boost + a full preview rebuild, ~180 ms of repeating-request stall) for
     * what is one discrete pick. The engine fast path is the whole submit.
     */
    override fun onTeleZoomMark(totalMagnification: Float) {
        val s = _state.value
        if (!s.teleconverterMode) return
        val range = s.caps?.zoomRatioRange
        val bounds = effectiveZoomBounds(
            range?.lower,
            range?.upper,
            teleconverter = true,
            teleconverterMagnification = s.teleconverterMagnification,
        ) ?: return
        val base = teleDisplayBase(s.teleconverterMagnification)
        if (!base.isFinite() || base <= 0f || !totalMagnification.isFinite()) return
        val z = (totalMagnification / base).coerceIn(bounds.lower, bounds.upper)
        // A discrete pick OWNS the framing: a coalesced pending ratio or a hardware-key glide still
        // easing toward its own absolute target would otherwise drag the zoom straight back off the
        // mark the user just tapped. (The scale itself is unchanged, so this is not a remap door —
        // no route-scoped focus evidence to discard.)
        zoomGlide.pendingRatio = Float.NaN
        zoomGlide.easeTarget = null
        if (z == s.controls.zoomRatio) return
        engine.setZoomRatio(z)
        _state.update { it.copy(controls = it.controls.copy(zoomRatio = z)) }
        // A delayed full-controls packet captured the pre-pick ratio; refresh it so it cannot snap
        // the framing back when it lands.
        pendingControls = pendingControls?.copy(zoomRatio = z)
        markChanged(FnSlot.ZOOM)
        scheduleSettingsSave()
    }

    override fun onToggleFrontCamera() {
        // The flip itself is recording-gated only; FRONT is where it leads, not a refusal input.
        if (rejectIfRecording("Stop REC first")) return
        cancelCountdown()
        drainPendingControls()
        val entering = _state.value.facing == CameraFacing.BACK
        // Mirrors the engine transaction exactly (like onToggleTeleconverter mirrors setLens):
        // entering forces TC off and front-local 1×; leaving lands on the retained rear band's
        // mode home (unified preset in photo, lens-local 1× in video). Deliberately NO explicit
        // settings save: facing is session-only, so a kill while FRONT restores the last REAR
        // setup — the outcome the "fresh launch is BACK" rule wants. saveSettingsIfEnabled
        // substitutes this snapshot while FRONT so an incidental save (background, control
        // change) keeps that promise instead of persisting the front-session TC-off/1×.
        if (entering) {
            preFrontRearTeleconverter = _state.value.teleconverterMode
            preFrontRearZoom = _state.value.controls.zoomRatio
            // The snapshot's zoom SCALE is the entry mode's; a mode flip while FRONT invalidates it
            // for the live return zoom (falls back to the preset, mirroring the engine). The
            // settings-save substitution keeps using it regardless: a clamped wrong-scale rear zoom
            // on the next launch is strictly less wrong than persisting the front 1×/TC-off hybrid.
            preFrontRearVideoMode = _state.value.mode == CaptureMode.VIDEO
        }
        engine.setFrontCamera(entering)
        _state.update {
            if (entering) {
                it.copy(
                    facing = CameraFacing.FRONT,
                    teleconverterMode = false,
                    controls = it.controls.copy(zoomRatio = 1f),
                    activeMemorySlot = null,
                )
            } else {
                it.copy(
                    facing = CameraFacing.BACK,
                    controls = it.controls.copy(
                        // Mirrors the engine transaction: restore the framing held before the front
                        // trip, NOT the lens preset — once TELE has been used the preset is 3x for
                        // the rest of the session, so the preset fallback zoomed the operator in on
                        // every flip back (user-reported).
                        zoomRatio = rearReturnZoom(
                            videoMode = it.mode == CaptureMode.VIDEO,
                            preFrontZoom = if (preFrontRearVideoMode == (it.mode == CaptureMode.VIDEO)) {
                                preFrontRearZoom
                            } else {
                                Float.NaN
                            },
                            lensPreset = it.lens.zoomPreset,
                        ),
                    ),
                    activeMemorySlot = null,
                )
            }
        }
        // A front trip drops the pre-TELE return snapshot (engine does the same in its transaction).
        preTeleUnifiedZoom = Float.NaN
        // The facing flip rewrote the zoom scale — full remap-door hygiene, same as mode/lens/TC.
        invalidateOpticsDerivedState()
        clearTapFocusUi()
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
        // Carry the outgoing lens's exposure across the aperture change before normalizing, exactly
        // as the engine already did at its own caps-install seam (same pure seed, same inputs — the
        // engine's `controls` and this state are the same packet, so the two agree and this cannot
        // undo the engine's seed). Doing it here as well is what keeps the OSD honest AND stops the
        // re-normalization below from pushing the stale pre-switch exposure back down.
        val seededControls = seedExposureForRouteChange(
            requested = current.controls,
            outgoing = current.caps,
            incoming = caps,
            mode = current.mode,
        )
        val normalizedControls = normalizeControlsForRoute(
            requested = seededControls,
            capabilities = caps.controlCapabilities(),
            mode = current.mode,
            teleconverter = current.teleconverterMode,
            teleconverterMagnification = current.teleconverterMagnification,
            capsLower = range?.lower,
            capsUpper = range?.upper,
        )
        val lens = if (
            current.mode == CaptureMode.PHOTO && !current.teleconverterMode &&
            current.facing == CameraFacing.BACK
        ) {
            LensChoice.forZoom(normalizedControls.zoomRatio)
        } else {
            current.lens
        }
        _state.update {
            it.copy(caps = caps, lens = lens, controls = normalizedControls)
        }
        pendingControls = pendingControls?.let { pending ->
            normalizeControlsForRoute(
                // A throttled apply captured before the switch carries the OLD lens's exposure; it
                // lands after this reconcile and would otherwise walk the seed straight back.
                requested = seedExposureForRouteChange(
                    requested = pending,
                    outgoing = current.caps,
                    incoming = caps,
                    mode = current.mode,
                ),
                capabilities = caps.controlCapabilities(),
                mode = current.mode,
                teleconverter = current.teleconverterMode,
                teleconverterMagnification = current.teleconverterMagnification,
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
    override fun onVideoStabMode(mode: me.hletrd.telecampro.camera.VideoStabMode) {
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

    /** The OPPO quick/action button (injected keycode 781) — its own reassignable binding. */
    fun onHardwareQuickButton(active: Boolean) {
        performHardwareAction(_state.value.quickButtonAction, active)
    }

    /**
     * Fires the capture AND blinks the viewfinder immediately. The still takes pipeline-depth ×
     * frame-duration before it even starts exposing (~0.9 s measured in low light) — with no
     * instant acknowledgment every press reads as shutter lag or a dead button.
     */
    private fun fireShutterWithFeedback() {
        val state = _state.value
        if (engine.capturePhoto(state.photoFormats, singleShot = state.isRecording)) {
            _state.update { it.copy(shutterFlashTick = it.shutterFlashTick + 1) }
        }
    }

    // NOTE: no one-shot onHardwareShutter() alias here. [onHardwareFullKey] dispatches the
    // USER-REASSIGNED action, and AEL/PUNCH_IN are MOMENTARY — they need the matching
    // active = false. A press-only alias named "shutter" would latch AE lock or the loupe on with
    // no release path under those bindings. MainActivity always sends both edges.

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
        if (state.isRecordingStarting) return
        dispatchPhotoShutter(
            countdownSeconds = state.timerCountdownSec,
            stillCaptureReady = state.stillCaptureReady,
            configuredDelaySeconds = photoShutterDelaySeconds(
                configuredDelaySeconds = state.timer.seconds,
                recording = state.isRecording,
            ),
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
        val attemptGeneration = ++recordingAttemptGeneration
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
                    audioRouteLabel = if (s.recordAudio) "Starting…" else "Off",
                    isRecording = true,
                    isRecordingStarting = true,
                    recordElapsedMs = 0,
                )
            }
            if (s.recordAudio && !inputStatus.available) {
                // ";" is the clause joiner every sibling status uses ("Camera unavailable; mode
                // unchanged"). A comma cannot do it here: the left operand is a port label that can
                // itself read "Auto · No mic", so a comma would bind a clause inside a · list.
                showStatus("${inputStatus.label}; using default")
            }
            // THREAD CONTRACT: this callback runs on the RECORDER EXECUTOR for a queued admission,
            // or synchronously on MAIN for an immediate refusal. Reconcile a refusal on MAIN as one
            // ordered unit: refreshStandbyAudioMeter reads main-confined lifecycle/visibility fields,
            // and must observe any onStop/onStart work already queued ahead of this callback.
            engine.startRecording(s.recordAudio) { ok ->
                if (!ok) {
                    mainHandler.post {
                        val current = _state.value
                        if (!recordingAttemptOwnsGeneration(
                                currentGeneration = recordingAttemptGeneration,
                                expectedGeneration = attemptGeneration,
                                isRecording = current.isRecording,
                                isRecordingStarting = current.isRecordingStarting,
                            )
                        ) return@post
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
        }
        refreshStandbyAudioMeter()
    }

    override fun onCameraOverride(id: String?) {
        cancelCountdown()
        drainPendingControls()
        // A camera-id override reopens onto a different route (different zoom scale): abandon any
        // in-flight coalesced/gliding zoom the same way every other optics-remap door does.
        invalidateOpticsDerivedState()
        clearTapFocusUi()
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
        val live = _state.value
        // Same FRONT substitution as saveSettingsIfEnabled: recalled packets are REAR-route optics
        // by contract (setResolvedOptics exits FRONT), so a preset stored while FRONT must persist
        // the retained rear setup, not the front-session hybrid (TC forced off, front-local 1×).
        // Without this, saving M1 during a selfie trip silently replaced the operator's TELE 5×
        // preset with rear MAIN at 1× — the exact cycle-6 F6 defect class, fixed for the plain save
        // path but not here. The substituted view also feeds the name/summary so the label describes
        // what recall will actually restore.
        val substituteRear = live.facing == CameraFacing.FRONT && !preFrontRearZoom.isNaN()
        // The zoom HALF of the substitution additionally requires the snapshot's zoom SCALE to
        // match the live mode (same tag as the return-zoom path): a Photo/Video flip while FRONT
        // makes the number wrong-scale, and a preset stores it verbatim — TC substitution has no
        // scale and stays unconditional.
        val substituteZoom = substituteRear &&
            preFrontRearVideoMode == (live.mode == CaptureMode.VIDEO)
        val snapshot = if (substituteRear) {
            live.copy(
                teleconverterMode = preFrontRearTeleconverter,
                controls = if (substituteZoom) {
                    live.controls.copy(zoomRatio = preFrontRearZoom)
                } else {
                    live.controls
                },
            )
        } else {
            live
        }
        val extras = if (substituteRear) {
            currentExtras().copy(teleconverter = preFrontRearTeleconverter)
        } else {
            currentExtras()
        }
        settingsStore.savePreset(
            slot,
            snapshot.controls,
            extras,
            name = presetNameFor(snapshot),
            summary = presetSummaryFor(snapshot),
        )
        refreshMemorySlotInfo(activeSlot = slot)
        showStatus("${slot.label} saved · ${presetNameFor(snapshot)}")
    }

    override fun onRecallMemorySlot(slot: MemorySlot) {
        if (_state.value.isRecording) {
            // The canonical REC refusal, word for word: every other site in the VM and the engine
            // says exactly this, and StatusUrgencyTest pins it. One refusal, one voice.
            showStatus("Stop REC first")
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

    override fun onQuickButtonAction(action: HardwareKeyAction) {
        _state.update { it.copy(quickButtonAction = action, activeMemorySlot = null) }
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
            // BEFORE the known outputs go: sweep still-PENDING family siblings the tracker never
            // learned about (a publish-failed output keeps its bytes + a COMPLETE journal entry, and
            // launch recovery would ADOPT it later — resurrecting part of a deleted capture). Runs
            // only for a real capture family; a legacy URI parses to no family and sweeps nothing.
            // Reads the tapped URI's display name, so it must precede that row's own deletion.
            if (deletePlan.captureId != null) {
                MediaStoreWriter.deletePendingFamilySiblings(getApplication(), uri)
            }
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
                        restored != null -> "Some files could not be deleted. Open the capture and retry."
                        else -> "Some files could not be deleted. Retry in Gallery."
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
                mainHandler.post { showStatus("Could not delete file") }
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
        // ONE emission per publication: the recent-slot bookkeeping used to ride a SECOND
        // _state.update (markChanged) on every slider/ruler event, doubling StateFlow emissions
        // and full-tree recompositions during continuous drags (cycle-6 PR-2).
        _state.update {
            if (slot == null) {
                it.copy(controls = updated)
            } else {
                val recent = (listOf(slot) + it.recentSettingSlots.filterNot { s -> s == slot })
                    .take(RECENT_SETTING_LIMIT)
                it.copy(controls = updated, recentSettingSlots = recent, activeMemorySlot = null)
            }
        }
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
        // Once the preview's brightness SIMULATION saturates, the metered frame is permanently
        // darker than the exposure already intended (wire exposure pinned at the fluidity cap, ISO
        // at its ceiling, GL gain clamped at x16). The error then never shrinks and every tick asks
        // for more — a ratchet that walks the intent to the 4 s HAL-safe still ceiling and
        // overexposes the real capture. Freeze UPWARD motion there; a brightening scene is still
        // metered truthfully and may come down. Preserves the cycle-8 fluidity cap and the
        // ISO-headroom trade exactly — only the unmeterable direction is refused.
        val brightnessSimSaturated = previewBrightnessSimulationSaturated(c, caps.controlCapabilities())
        when (c.exposureMode) {
            ExposureMode.SHUTTER -> if (isoRange != null) {
                AutoExposure.driveIso(luma, c.iso, isoRange.lower, isoRange.upper, evStops)?.let { newIso ->
                    if (!brightnessSimSaturated || newIso <= c.iso) updateControls { it.copy(iso = newIso) }
                }
            }
            ExposureMode.ISO -> if (expRange != null && exposureUpperNs != null && exposureUpperNs >= expRange.lower) {
                AutoExposure.driveShutterNs(luma, c.effectiveExposureNs(), expRange.lower, exposureUpperNs, evStops)?.let { newNs ->
                    if (!brightnessSimSaturated || newNs <= c.effectiveExposureNs()) {
                        updateControls { it.copy(exposureTimeNs = newNs) }
                    }
                }
            }
            ExposureMode.PROGRAM -> if (c.programAppSide && isoRange != null && expRange != null &&
                exposureUpperNs != null && exposureUpperNs >= expRange.lower
            ) {
                AutoExposure.driveProgram(
                    luma, c.iso, c.effectiveExposureNs(), preferredProgramShutterNs(s),
                    isoRange.lower, isoRange.upper, expRange.lower, exposureUpperNs, evStops,
                )?.let { (newIso, newNs) ->
                    // Compare the BRIGHTNESS product: the program line trades one axis against the
                    // other, so either alone can rise while the exposure as a whole falls.
                    val brighter = newIso.toLong() * newNs > c.iso.toLong() * c.effectiveExposureNs()
                    if (!brightnessSimSaturated || !brighter) {
                        updateControls { it.copy(iso = newIso, exposureTimeNs = newNs) }
                    }
                }
            }
            else -> Unit
        }
    }

    // ---- Lifecycle ----
    fun onStart() {
        if (lifecycleStarted) return
        lifecycleStarted = true
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
        recordingAttemptGeneration++
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
        invalidateOpticsDerivedState()
        // The controller is about to be closed. Retire its ownership/UI now, queue the ROI clear,
        // and skip a preview rebuild that could only race the queued close.
        clearTapFocus(rebuildPreview = false)
        saveSettingsIfEnabled() // persist on background so the next launch restores them
        standbyMeterEnabled = false
        engine.setStandbyAudioMonitor(false) // release the mic while backgrounded
        engine.pause()
    }

    override fun onCleared() {
        recordingAttemptGeneration++
        mainHandler.removeCallbacksAndMessages(null)
        debugZoomReceiver?.let { receiver -> runCatching { getApplication<Application>().unregisterReceiver(receiver) } }
        debugZoomReceiver = null
        debugZslSpikeReceiver?.let { receiver -> runCatching { getApplication<Application>().unregisterReceiver(receiver) } }
        debugZslSpikeReceiver = null
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

internal fun standbyAudioMeterShouldRun(
    lifecycleStarted: Boolean,
    visible: Boolean,
    mode: CaptureMode,
    recordAudio: Boolean,
    recording: Boolean,
): Boolean = lifecycleStarted && visible && mode == CaptureMode.VIDEO && recordAudio && !recording

/** A delayed async REC admission result may mutate only the optimistic UI attempt that submitted it. */
internal fun recordingAttemptOwnsGeneration(
    currentGeneration: Long,
    expectedGeneration: Long,
    isRecording: Boolean,
    isRecordingStarting: Boolean,
): Boolean = currentGeneration == expectedGeneration && isRecording && isRecordingStarting

internal fun focusModeChangeClearsTapPoint(
    current: FocusMode,
    requested: FocusMode,
): Boolean = current != requested

/** A new accepted AF trigger starts yellow/searching; a prior point's verdict cannot carry over. */
internal fun submittedTapFocusUiState(
    current: CameraUiState,
    point: Pair<Float, Float>,
): CameraUiState = current.copy(
    tapPoint = point,
    tapFocusHeld = true,
    // AF Lock wins over tap AF: the tap may still move the AE region, but it must not claim a scan.
    afIndication = if (current.controls.afLock) AfIndication.IDLE else AfIndication.SCANNING,
)

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
 * Whether the frame-detail metric's per-pixel math should run at all.
 *
 * Only the STABLE, route-level refusals live here — the ones that hold for as long as a shooting
 * state lasts. The per-frame gates (mid-scan, zoom gesture, stale statistics, exposure) stay in
 * [me.hletrd.telecampro.focus.frameDefocusCandidate], because a frame that fails one of those is
 * still worth computing: the very next frame may pass.
 *
 * This never turns the GL readback ON (the metric rides the scope/AE one), so a false here costs
 * nothing but a little CPU on the analysis executor, and a true adds no GL work.
 */
internal fun focusDetailAnalysisRequired(
    focusMode: FocusMode,
    recording: Boolean,
    recordingStarting: Boolean,
): Boolean = focusMode != FocusMode.MANUAL && !recording && !recordingStarting

/**
 * Handheld-safe shutter target (ns) for app-side PROGRAM: the 1/(35mm-equivalent focal) rule at the
 * effective focal length (native × teleconverter magnification). Pure for unit tests.
 */
internal fun preferredProgramShutterNs(
    lensEquivMm: Float,
    teleconverterMode: Boolean,
    teleconverterMagnification: Float,
): Long {
    val eff = lensEquivMm * (if (teleconverterMode) teleconverterMagnification else 1f)
    return (1_000_000_000f / eff.coerceAtLeast(1f)).toLong()
}
