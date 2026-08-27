package me.hletrd.telecampro.ui

import android.app.Application
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
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
import me.hletrd.telecampro.camera.CaptureFamilyDeleteDurability
import me.hletrd.telecampro.camera.CaptureFamilyDeleteIntent
import me.hletrd.telecampro.camera.CameraReadyPublication
import me.hletrd.telecampro.camera.CameraReadyPublicationGate
import me.hletrd.telecampro.camera.CameraPolicyPublicationGate
import me.hletrd.telecampro.camera.CameraRouteInventory
import me.hletrd.telecampro.camera.CameraRoute
import me.hletrd.telecampro.camera.recalledCameraRoute
import me.hletrd.telecampro.camera.CameraStatus
import me.hletrd.telecampro.camera.CameraStatusArgument
import me.hletrd.telecampro.camera.CameraStatusLifecycle
import me.hletrd.telecampro.camera.CameraStatusMessage
import me.hletrd.telecampro.camera.normalizeTimelapseIntervalSeconds
import me.hletrd.telecampro.hardwareActionAdmitted
import me.hletrd.telecampro.camera.backOpticsDoorRefusal
import me.hletrd.telecampro.camera.cameraPolicyPublishedState
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.CaptureMode
import me.hletrd.telecampro.camera.ColorEffect
import me.hletrd.telecampro.camera.ColorTransfer
import me.hletrd.telecampro.camera.DriveMode
import me.hletrd.telecampro.camera.DiagnosticChangeLogGate
import me.hletrd.telecampro.camera.AfSpotSize
import me.hletrd.telecampro.camera.AutoExposure
import me.hletrd.telecampro.camera.ExposureMode
import me.hletrd.telecampro.gl.MotionInversionConfidence
import me.hletrd.telecampro.gl.MOTION_SIGNS_VERIFIED
import me.hletrd.telecampro.camera.MotionInversionData
import me.hletrd.telecampro.camera.MotionAgreement
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
import me.hletrd.telecampro.camera.OpenReviewPresentation
import me.hletrd.telecampro.camera.MeteringMode
import me.hletrd.telecampro.camera.MemorySlot
import me.hletrd.telecampro.camera.MemoryPresetPresentation
import me.hletrd.telecampro.camera.PeakingColor
import me.hletrd.telecampro.camera.PeakingLevel
import me.hletrd.telecampro.camera.PhotoFormats
import me.hletrd.telecampro.camera.opticalBaseFor
import me.hletrd.telecampro.camera.localZoomOf
import me.hletrd.telecampro.camera.resolveTeleZoomTransition
import me.hletrd.telecampro.camera.unifiedZoomOf
import me.hletrd.telecampro.camera.standaloneRouteWanted
import me.hletrd.telecampro.camera.normalizedForEncoder
import me.hletrd.telecampro.camera.normalizedForAvailableModes
import me.hletrd.telecampro.camera.availableVideoStabModes
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
import me.hletrd.telecampro.camera.status
import me.hletrd.telecampro.camera.ProcessingLevel
import me.hletrd.telecampro.camera.ShutterMode
import me.hletrd.telecampro.camera.ShutterTimer
import me.hletrd.telecampro.camera.TapFocusPublicationGate
import me.hletrd.telecampro.camera.TeleconverterProfile
import me.hletrd.telecampro.camera.PhoneModel
import me.hletrd.telecampro.camera.defaultConverterFor
import me.hletrd.telecampro.camera.detectPhone
import me.hletrd.telecampro.camera.effectiveFocalMm
import me.hletrd.telecampro.camera.normalizeMagnification
import me.hletrd.telecampro.camera.reconcileConverter
import me.hletrd.telecampro.camera.teleDisplayBase
import me.hletrd.telecampro.camera.teleconverterDeclaration
import me.hletrd.telecampro.camera.VideoCodec
import me.hletrd.telecampro.camera.VideoFrameRate
import me.hletrd.telecampro.camera.WbMode
import me.hletrd.telecampro.camera.ZebraLevel
import me.hletrd.telecampro.camera.processDiagnosticLogBudget
import me.hletrd.telecampro.camera.rearReturnZoom
import me.hletrd.telecampro.focus.FocusMapping
import me.hletrd.telecampro.focus.MACRO_HOLD_MS
import me.hletrd.telecampro.focus.FocusConfidenceHold
import me.hletrd.telecampro.focus.focusConfidenceCandidate
import me.hletrd.telecampro.focus.frameDefocusCandidate
import me.hletrd.telecampro.focus.macroTooCloseCandidate
import me.hletrd.telecampro.storage.ExtraSettings
import me.hletrd.telecampro.storage.DeletedFamilySweepResult
import me.hletrd.telecampro.storage.DiscardMarkerCleanupDisposition
import me.hletrd.telecampro.storage.KnownOutputDeletionResult
import me.hletrd.telecampro.storage.KnownOutputProviderDisposition
import me.hletrd.telecampro.storage.MediaProvenance
import me.hletrd.telecampro.storage.MediaStoreWriter
import me.hletrd.telecampro.storage.RestoredCapture
import me.hletrd.telecampro.storage.SettingsStore
import me.hletrd.telecampro.video.AudioInputInspector
import me.hletrd.telecampro.video.AudioRouteAvailability
import me.hletrd.telecampro.video.AudioRouteStatus
import me.hletrd.telecampro.video.CodecInventory
import me.hletrd.telecampro.video.EncoderCaps
import me.hletrd.telecampro.video.audioUnavailableLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.lang.ref.WeakReference

internal data class OwnerlessMediaDeleteOverrides(
    val createDeleteRequest: (ContentResolver, Uri) -> PendingIntent = { resolver, uri ->
        MediaStore.createDeleteRequest(resolver, listOf(uri))
    },
    val queryPresence: (Context, Uri) -> KnownOutputProviderDisposition = { context, uri ->
        MediaStoreWriter.knownOutputPresence(context, uri)
    },
    val dispatcher: ViewModelMediaDeleteDispatcher = ViewModelMediaDeleteDispatcher(
        VIEW_MODEL_MEDIA_DELETE_WORKER_COUNT,
        VIEW_MODEL_MEDIA_DELETE_BACKLOG_CAPACITY,
    ),
)

/** Constructor-time provider/queue seams for deterministic latest-capture restore tests. */
internal data class LatestCaptureRestoreOverrides(
    val submit: (Runnable) -> Boolean,
    val postCompletion: (Runnable) -> Boolean,
    val query: () -> RestoredCapture<Uri>?,
)

/** The potentially wedged provider call retains application Context, never its ViewModel owner. */
private fun latestCaptureRestoreQuery(context: Context): () -> RestoredCapture<Uri>? {
    val applicationContext = context.applicationContext
    return { MediaStoreWriter.latestOwnCapture(applicationContext) }
}

/** Holds [CameraUiState] and turns [CameraActions] into [CameraEngine] calls. UI-thread only. */
// The engine is a defaulted constructor parameter (the ONE test seam this class exposes): host
// tests inject or observe it while production behavior is unchanged. The public @JvmOverloads
// constructor emits the plain (Application) overload that AndroidViewModelFactory requires; the
// private primary additionally admits constructor-time provider seams without exposing them to it.
class CameraViewModel private constructor(
    app: Application,
    private val engine: CameraEngine,
    private val latestCaptureRestoreOverrides: LatestCaptureRestoreOverrides?,
    @Suppress("UNUSED_PARAMETER") privateConstructorMarker: Unit,
) : AndroidViewModel(app), CameraActions {

    @JvmOverloads
    constructor(
        app: Application,
        engine: CameraEngine = CameraEngine(app),
    ) : this(app, engine, null, Unit)

    internal constructor(
        app: Application,
        engine: CameraEngine,
        ownerlessMediaDeleteOverrides: OwnerlessMediaDeleteOverrides,
    ) : this(app, engine, null, Unit) {
        // Construction/init performs no review deletion. Retire the unused default facade, then
        // install the deterministic provider seams before a test can freeze its first review.
        mediaDeleteDispatcher.shutdown()
        this.ownerlessMediaDeleteOverrides = ownerlessMediaDeleteOverrides
        mediaDeleteDispatcher = ownerlessMediaDeleteOverrides.dispatcher
    }

    internal constructor(
        app: Application,
        engine: CameraEngine,
        latestCaptureRestoreOverrides: LatestCaptureRestoreOverrides,
    ) : this(app, engine, latestCaptureRestoreOverrides, Unit)

    private val cameraReadyPublicationGate = CameraReadyPublicationGate()
    private val cameraPolicyPublicationGate = CameraPolicyPublicationGate()

    // The focus-ruler loupe assist owns `punchIn` transiently; these keep the operator's own value

    // available so a save during the assist persists intent rather than the assist's side effect.

    private var autoPunchInActive = false

    private var punchInBeforeAuto = false
    private val tapFocusPublicationGate = TapFocusPublicationGate()
    private val settingsStore = SettingsStore(app)
    private val _state = MutableStateFlow(CameraUiState())
    val state: StateFlow<CameraUiState> = _state.asStateFlow()
    private val _ownerlessMediaDeleteLaunch = MutableStateFlow<OwnerlessMediaDeleteLaunch?>(null)
    internal val ownerlessMediaDeleteLaunch: StateFlow<OwnerlessMediaDeleteLaunch?> =
        _ownerlessMediaDeleteLaunch.asStateFlow()
    // Video must clamp its live/request shutter to one frame, but that derived value must not erase
    // the photographer's Photo shutter (including ANGLE's dormant SPEED value). Persisted through
    // ExtraSettings so a process death while Video is selected still restores Photo faithfully.
    private var photoExposureTimeNs = ManualControls().exposureTimeNs

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recordStartMs = 0L
    private val recordTicker = object : Runnable {
        override fun run() {
            // 200 ms tick bounds display lag, but the mm:ss readout changes at 1 Hz — publish only
            // when the DISPLAYED second advances, or 4 of 5 ticks were whole-state copies that
            // recomposed the root for an unchanged string (perf review #5).
            val elapsed = SystemClock.elapsedRealtime() - recordStartMs
            if (elapsed / 1000L != _state.value.recordElapsedMs / 1000L) {
                _state.update { it.copy(recordElapsedMs = elapsed) }
            }
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
    // Written on main (Compose DISP/modal effect), read by onAnalysis on the GL thread — volatile,
    // not synchronized: a one-tick-stale read only delays a scope publication by ~166 ms.
    @Volatile private var scopesVisible = false
    // True while NO full-screen modal covers the viewfinder — the MANUAL exposure meter's own
    // composed-consumer truth (it renders without expanded DISP, unlike the scopes).
    @Volatile private var exposureMeterVisible = true
    // Main-confined identity for optimistic REC UI. A queued refusal may arrive after stop/new-start
    // or lifecycle teardown; only the exact attempt that submitted it may reconcile the state.
    private var recordingAttemptGeneration = 0L
    private var debugZoomReceiver: android.content.BroadcastReceiver? = null
    private var debugZslSpikeReceiver: android.content.BroadcastReceiver? = null
    // Main-thread token for the one-shot Custom-WB sample. Any newer WB action makes an older
    // controller callback inert before it can publish gains or a stale status message.
    private var customWbSampleGeneration = 0L

    // Status timer ownership is sequence-based: removing a Runnable cannot stop one already running,
    // so an old event may clear only the exact publication it armed for.
    private var statusSequence = 0L
    private var clearStatusRunnable: Runnable? = null

    private var reticleHideRunnable: Runnable? = null
    // Tap publications can originate on camera/setup/main threads while the visual timeout runs on
    // main. Keep timer ownership and its StateFlow mutation atomic so an already-running old timeout
    // cannot erase a newer accepted point after removeCallbacks loses that race.
    private val tapFocusUiTimerLock = Any()

    // Owns every processed/raw URI for a capture and tombstones deleted ids so a late save callback
    // cannot resurrect a sibling after the user deleted the frozen review shot.
    private val captureOutputs = CaptureOutputTracker<Uri>(CAPTURE_OUTPUT_HISTORY)
    // Main-thread owner for the exact file frozen while Android's system delete-consent surface is
    // active. The provider result may arrive after a newer capture, so completion always rechecks
    // this object identity before restoring or publishing UI state.
    private var pendingOwnerlessMediaDelete: PendingOwnerlessMediaDelete? = null
    private var ownerlessMediaDeleteGeneration = 0L

    private data class PendingOwnerlessMediaDelete(
        val request: OwnerlessMediaDeleteRequest,
        val plan: CaptureDeletePlan<Uri>,
        var reconciliationStarted: Boolean = false,
    )

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

    // Quiet-window landing: 250 ms after the LAST flush, the exact (non-wide-aimed)
    // ratio lands on the HAL even though the 700 ms boost tail is still running — otherwise a clip
    // keeps the ~1.2×-wide framing after finger-up and a tail still frames wider than the finder.
    // It also RE-ARMS the zoom-OUT leading edge (AGG4-14): reaching here means the pipeline went
    // quiet for the full landing window, which is the only re-arm signal available to the input paths
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
            // Quantized to 0.2° BEFORE the compare (perf review #4): raw smoothed-gravity floats
            // virtually never repeat, so the unquantized publish defeated StateFlow dedup and
            // recomposed the whole tree at 10 Hz even on a tripod. 0.2° is 2.5× finer than the
            // 0.5° is-level color threshold and moves the drawn 230 px half-span under 1 px —
            // the same change-gating discipline orientationTicker below already applies.
            val quantized = kotlin.math.round(engine.currentRollDegrees() * 5f) / 5f
            if (_state.value.levelRoll != quantized) {
                _state.update { it.copy(levelRoll = quantized) }
            }
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
    // The reads stay on the shared serial I/O lane so restore/delete ordering does not change, but
    // [LifecycleInfoRefresh] admits only one submitted sample plus one coalesced intent. A blocked
    // provider task can therefore delay telemetry without letting ten-second ticks or lifecycle
    // churn build an unbounded queue in front of later user work.
    private val infoRefresh: LifecycleInfoRefresh<LifecycleInfoSample> by lazy(LazyThreadSafetyMode.NONE) {
        LifecycleInfoRefresh(
            submit = { task -> runCatching { ioExecutor.execute(task) }.isSuccess },
            sample = { LifecycleInfoSample(readBatteryPct(), readFreeBytes()) },
            deliver = { generation, sample ->
                mainHandler.post {
                    // Completion admission and main-thread publication are two different races:
                    // Stop may land after the worker finishes but before this post executes.
                    if (lifecycleStarted && infoRefresh.isActive(generation)) {
                        _state.update {
                            it.copy(batteryPct = sample.batteryPct, freeBytes = sample.freeBytes)
                        }
                    }
                }
            },
        )
    }
    private val infoTicker = object : Runnable {
        override fun run() {
            if (!lifecycleStarted) return
            infoRefresh.request()
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
    // Verdict changes are paced and stable input truth gets a slow heartbeat. Every recurring DEBUG
    // producer additionally shares the process budget so this trace cannot consume the ColorOS log
    // quota independently of 3A, motion, ZSL, or hardware-input evidence.
    private val focusConfidenceDiagnosticGate = DiagnosticChangeLogGate<Any?>()
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
            focusConfidenceDiagnosticGate.shouldEmit(now, candidate) &&
            processDiagnosticLogBudget.tryAcquire()
        ) {
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
        // An optics door is the ONLY thing that can change the inversion answer (the operator
        // toggling TELE, or changing lens/mode), so it is both where a settled verdict stops being
        // valid and where it is worth paying for the gyro again.
        restartMotionInversion()
    }

    // Confidence accumulated across analysis frames. Main-thread confined: written only from the
    // onAnalysis main post and the optics-door reset, read only here.
    private var motionConfidence = MotionInversionConfidence()
    private var motionArmed = false
    private var lastMotionHeartbeatMs = 0L
    private var lastMotionShape = -1

    /**
     * Re-arms the inversion detector for a fresh question. Called at every optics door.
     *
     * Deliberately gated on [MOTION_SIGNS_VERIFIED]: until the gyro→image signs are bisected on
     * hardware, a verdict would be as likely inverted as correct, and arming the gyro to compute an
     * answer nobody may trust is pure battery cost. This is the single switch that makes the whole
     * feature dark — see the bisection procedure in gl/MotionInversion.kt.
     */
    private fun restartMotionInversion() {
        motionConfidence = MotionInversionConfidence()
        val want = MOTION_SIGNS_VERIFIED
        motionArmed = want
        // This is an evidence-epoch boundary, not merely an armed-state setter. Re-publish even on
        // true -> true so neither gyro samples nor GL's retained predecessor can span the optics
        // door. The renderer epoch also rejects a result already computing on its old executor.
        engine.setMotionInversionArmed(want, resetEvidence = true)
    }

    /**
     * Folds one frame verdict into the accumulator and disarms once it settles.
     *
     * Disarming on confidence is the whole power story: the gyro runs for the few seconds it takes
     * to answer, then stops until the next optics door. A phone left pointing at a static scene
     * simply never settles and never publishes — which is correct, and costs only the gyro until
     * the operator moves on.
     */
    private fun observeMotionInversion(data: MotionInversionData) {
        if (!motionArmed) return

        // LIVENESS HEARTBEAT, at most once a second. An all-UNJUDGEABLE run is otherwise completely
        // silent, which during a device sign bisection is indistinguishable from "the rider never
        // ran" — the operator is left panning at a detector that may or may not exist. This says
        // which gate refused: totalBlocks=0 means the frame never reached the block grid (no
        // rotation cleared the angular gate), votingBlocks=0 means the scene carries no texture
        // along the pan axis, and a near-even agree/oppose split means periodic content.
        // Throttled because ColorOS drops app logs past 300 rows per process.
        // CHANGE-GATED, with a slow floor. A 1 Hz heartbeat burns the ColorOS 300-row-per-process
        // quota in ~5 minutes and then silences the very trace the bisection is reading — the exact
        // trap CLAUDE.md documents, walked into by this line's first draft. What matters is the
        // SHAPE change: whether rotation is reaching the block grid at all (totalBlocks 0 <-> n) and
        // whether blocks are voting (votingBlocks 0 <-> n). Those move a handful of times per pan.
        val now = SystemClock.uptimeMillis()
        val shape = (if (data.totalBlocks > 0) 2 else 0) + (if (data.votingBlocks > 0) 1 else 0)
        // While the phone is actually MOVING, log at 2 Hz so the operator gets a live meter to aim
        // at — a gesture lasts a couple of seconds and a 15 s heartbeat would report it once, after
        // it ended. At rest the 15 s floor still applies, so an idle phone cannot drain the quota.
        val moving = data.predictedMrad >= 1f
        val floorMs = if (moving) 500L else 15_000L
        if ((shape != lastMotionShape || now - lastMotionHeartbeatMs >= floorMs) &&
            processDiagnosticLogBudget.tryAcquire()
        ) {
            lastMotionShape = shape
            lastMotionHeartbeatMs = now
            Log.i(
                "MotionInversion",
                "tick frame=${data.verdict} blocks=${data.votingBlocks}/${data.totalBlocks} " +
                    "agree=${data.agreeVotes} oppose=${data.opposeVotes} " +
                    "rot=${"%.1f".format(data.predictedMrad)} dir=${"%+.0f".format(data.predictedX)},${"%+.0f".format(data.predictedY)} " +
                    "settled=${motionConfidence.settled} streak=${motionConfidence.streak}",
            )
        }
        if (data.verdict == MotionAgreement.UNJUDGEABLE) return

        val beforeSettled = motionConfidence.settled
        val beforePending = motionConfidence.pending
        motionConfidence = motionConfidence.observe(data.verdict)
        val settled = motionConfidence.settled
        val pending = motionConfidence.pending

        // Logs on CHANGE only — of either the settled answer or the pending candidate. ColorOS drops
        // app logs past a 300-row-per-process quota, and a ~6 Hz per-frame line would spend it in
        // under a minute, taking the startup and focus traces with it. Including PENDING costs
        // almost nothing (a deliberate pan changes it once or twice) and is what makes a
        // non-settling result diagnosable: without it, "the detector said nothing" cannot be told
        // apart from "the detector never ran". This is the readout the device sign bisection reads.
        if ((settled != beforeSettled || pending != beforePending) &&
            processDiagnosticLogBudget.tryAcquire()
        ) {
            Log.i(
                "MotionInversion",
                "settled=$settled pending=$pending streak=${motionConfidence.streak} " +
                    "frame=${data.verdict} blocks=${data.votingBlocks}/${data.totalBlocks} " +
                    "agree=${data.agreeVotes} oppose=${data.opposeVotes} " +
                    "rot=${"%.1f".format(data.predictedMrad)} dir=${"%+.0f".format(data.predictedX)},${"%+.0f".format(data.predictedY)}",
            )
        }
        if (motionConfidence.confident) {
            motionArmed = false
            engine.setMotionInversionArmed(false)
        }
    }

    // One ViewModel-local background lane for bounded/coalesced telemetry plus one-shot restore and
    // codec inventory work. Provider deletion has its own process-finite ordered lane below, so a
    // wedged Binder call cannot grow this executor through repeated capture/delete callbacks.
    private val ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "vm-io").apply { isDaemon = true }
    }
    private val latestCaptureRestoreOwner = LatestCaptureRestoreOwner(
        submit = latestCaptureRestoreOverrides?.submit
            ?: { task -> runCatching { ioExecutor.execute(task) }.isSuccess },
        postCompletion = latestCaptureRestoreOverrides?.postCompletion ?: mainHandler::post,
        query = latestCaptureRestoreOverrides?.query
            ?: latestCaptureRestoreQuery(app),
        publish = ::publishLatestCapture,
    )
    private var ownerlessMediaDeleteOverrides = OwnerlessMediaDeleteOverrides()
    private var mediaDeleteDispatcher = ownerlessMediaDeleteOverrides.dispatcher
    @Volatile private var cleared = false
    private var encoderInventory: CodecInventory = CodecInventory.EMPTY
    private var pendingCodecUntilInventory: VideoCodec? = null
    private var pendingTransferUntilInventory: ColorTransfer? = null
    private var pendingPhotoFormatsUntilInventory: PhotoFormats? = null

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
        // Route inventory is attempted before the first Camera2 open. A partial/failed
        // classification may coexist with a safe current/default open, while the Engine's bounded
        // independent retry later converges complete truth. Mirror each publication plus the exact
        // active route in one fold; never infer EXTERNAL from the two-value facing axis.
        engine.onCameraRouteInventory = { routes, activeRoute ->
            _state.update { current ->
                cameraRoutePublishedState(
                    current = current,
                    routes = routes,
                    activeRoute = activeRoute,
                    rawForcesStandalone = engine.rawForcesStandalone,
                )
            }
        }
        // Caps arrive on the setup thread. Reconcile restored/schema-normalized zoom against the
        // selected camera's authoritative range on main before any delayed input can reuse it.
        engine.onLensInventory = { inventory ->
            _state.update { it.copy(lensInventory = inventory) }
            // The inventory carries the MEASURED converter host lens and lands after the phone
            // seed, so re-push it — but on MAIN, like every other engine setter in this class
            // (this callback arrives on the setup thread; the seed path calls the same setter from
            // main, and one setter must not be entered from two threads).
            mainHandler.post {
                val current = _state.value
                engine.setTeleconverterDeclaration(
                    teleconverterDeclaration(
                        phone = current.phoneModel,
                        profile = current.teleconverterProfile,
                        customMagnification = current.teleconverterCustomMagnification,
                        measuredOtherHostEquivMm = current.teleconverterHostEquivMm,
                    ),
                )
                refreshMemorySlotInfo()
            }
        }
        engine.onCapsReady = { caps, generation ->
            mainHandler.post {
                if (!engine.isOpticsGenerationCurrent(generation)) return@post
                reconcileZoomToCaps(caps)
                reconcileFrameRate()
                // Macro hint is static per route: resolve the closer-focusing lens label once per
                // caps delivery, and re-evaluate the tag against the new route's min focus.
                val hintLens = engine.closerFocusingLens(caps)
                _state.update { it.copy(macroCloserLens = hintLens) }
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
        engine.onEncoderSizeAccepted = { size ->
            mainHandler.post {
                if (_state.value.isRecording) {
                    _state.update { it.copy(activeEncoderResolution = size) }
                }
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
                // Ready ends only the progress condition it still owns. Passing observe() above is
                // not a lifetime lease: a newer Not-Ready + progress publication can land before
                // this callback reaches retirement. runIfOwned shares the gate monitor with status
                // publication, so the only two orders are old Ready clears first/new progress wins,
                // or Not-Ready wins first/old Ready is inert.
                clearProgressStatus(publication)
                mainHandler.post {
                    // A newer optics intent or pause/session reopen can land while this camera-thread
                    // callback is queued for main. Both generations bind its output snapshot.
                    if (!engine.isCameraReadyPublicationCurrent(publication)) return@post
                    var formatStatus: CameraStatus? = null
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
                                CameraStatusMessage.STILL_CAPTURE_UNAVAILABLE.status()
                            current.photoFormats.wantsProcessedStill &&
                                !accepted.photoFormats.wantsProcessedStill && accepted.photoFormats.dngRaw ->
                                // Word for word the engine's capture-time refusal and the
                                // PhotoFormatToggles caption: this fires on Ready publication and
                                // those fire at the shutter, so one user sees all three for one
                                // output mask.
                                CameraStatusMessage.PROCESSED_STILL_UNAVAILABLE_DNG_ONLY.status()
                            // NO route-switch "RAW unavailable" toast (user-removed 2026-07-31:
                            // "too noisy" — every front flip with DNG selected announced a state
                            // the vanished chrome already shows). The sheet caption under the
                            // format chips remains the one home for that truth, and the
                            // CAPTURE-time status in CameraEngine still tells the user when an
                            // actual shot dropped its DNG.
                            else -> null
                        }
                        current.copy(
                            cameraReady = true,
                            photoSessionOutputs = publication.photoOutputs,
                            photoFormats = accepted.photoFormats,
                        )
                    }
                    if (acceptedApplied) preTeleUnifiedZoom = acceptedPreTele
                    formatStatus?.let { status ->
                        cameraReadyPublicationGate.runIfOwned(publication) { showStatus(status) }
                    }
                }
            }
        }
        engine.onOpticsRollback = { rollback ->
            mainHandler.post {
                if (!engine.isOpticsGenerationCurrent(rollback.generation)) return@post
                val pipelineOwned = engine.isVideoPipelinePublicationCurrent(
                    rollback.videoPipelineGeneration,
                )
                // "Camera unchanged": the failed door never closed the outgoing session, so it is
                // still streaming. Drop the dip now rather than blacking out live picture until the
                // deadline — and remember this generation, because the rollback's OWN trailing
                // Not-Ready (posted right behind this one, from the same thread) carries it.
                applySwitchCover(switchCover.onOpticsRollback(rollback.generation))
                cancelPendingControls()
                cancelCountdown()
                // The rollback restored a different optics scale: every in-flight glide value is an
                // ABSOLUTE ratio in the failed attempt's scale, so ease target / coalesced base /
                // scheduled quiet landing all invalidate together (same invariant as every optics-remap door).
                invalidateOpticsDerivedState()
                clearTapFocusUi()
                // Engine snapshots this hidden bank inside the same generation-owned transaction as
                // visible optics, so even Ready-callback overlap restores the exact accepted value.
                photoExposureTimeNs = rollback.photoExposureTimeNs
                // Mirror the engine's restored pre-TELE snapshot: recall resets this mirror eagerly,
                // and without the rollback leg a FAILED recall left NaN here while the engine
                // restored its value — the next TC-off then showed the preset while the wire
                // restored the retained framing (verification S4).
                preTeleUnifiedZoom = rollback.preTeleUnifiedZoom
                _state.update {
                    it.copy(
                        mode = rollback.mode,
                        transfer = if (pipelineOwned) rollback.transfer else it.transfer,
                        videoCodec = if (pipelineOwned) rollback.videoCodec else it.videoCodec,
                        lens = rollback.lens,
                        teleconverterMode = rollback.teleconverter,
                        facing = rollback.facing,
                        activeCameraRoute = rollback.route,
                        controls = rollback.controls,
                        phoneModel = rollback.declaration.phone,
                        phoneModelDetected = rollback.declaration.phone == detectedPhone,
                        teleconverterProfile = rollback.declaration.profile,
                        teleconverterCustomMagnification = rollback.declaration.customMagnification,
                        // The engine publishes only a GENUINE diagnostic pin here (its routed-target
                        // pin stays internal) — so a routine failed door can no longer surface the
                        // Setup Camera ID row or poison the same-route recall fast path.
                        cameraOverrideId = rollback.userPin,
                    )
                }
                // Mode-derived owners were applied optimistically with the rejected packet. The
                // Engine transaction restores its exact accepted transfer/GL snapshot; replay the
                // UI-owned visible standby AudioRecord after this generation restores the mode.
                refreshStandbyAudioMeter()
                refreshProgramAppSide()
                scheduleSettingsSave()
            }
        }
        // DEBUG-only app-local zoom injection hook. Keep a receiver reference so ViewModel teardown
        // unregisters it; NOT_EXPORTED prevents arbitrary apps/shell broadcasts from controlling
        // camera framing while the process is alive. NOTE (device-confirmed 2026-07-25): on API 36
        // NOT_EXPORTED also rejects adb-shell broadcasts (result=0, enqueued, never delivered), so
        // Shell-driven debugging goes through the debug-only DUMP-protected activity and its
        // process-local mailbox; these non-exported receivers remain for app-internal senders.
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
        engine.onAnalysis = { h, w, f, m ->
            // Publish scope data into UI state only when something actually renders it: the
            // histogram/waveform overlays while ACTUALLY COMPOSED (scope settings alone are not
            // enough — [scopesVisible] carries the Compose-local expanded-DISP/no-modal truth the
            // VM cannot otherwise see, perf review #6), or the MANUAL-mode exposure meter, which
            // reads histogram data with the overlays hidden and stays unconditional. App-side AE
            // reads the callback arg directly, so with nothing rendering the ~6 Hz analysis tick
            // no longer forces a whole-CameraUiState emission (root-recomposition churn).
            val s = _state.value
            // The MANUAL clause feeds the exposure meter, which is ALSO not composed under a modal
            // (the Fn overlay suppresses it outright, the sheet and review cover it), so it takes
            // the same consumer-exists gate rather than publishing ~6 Hz whole-state copies with
            // nothing to draw them (2026-08-02 review). meterVisible stays true whenever no modal
            // is up, so MANUAL keeps working with the scopes switched off.
            if (((s.histogram || s.waveform) && scopesVisible) ||
                (s.controls.exposureMode == ExposureMode.MANUAL && exposureMeterVisible)
            ) {
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
            // Motion-inversion verdict. Rides the same epoch as the focus evidence: an optics door
            // is exactly when the answer can change, so a verdict computed before one must never be
            // folded into the accumulator after it.
            if (m != null) {
                val epoch = focusEvidenceEpoch
                mainHandler.post {
                    if (focusEvidenceEpoch != epoch) return@post
                    observeMotionInversion(m)
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
        engine.onAudioLevel = { frame ->
            // Quantize BEFORE the compare, exactly as levelRoll does (perf review #4): the raw RMS
            // float never repeats — even a silent room's noise floor jitters — so StateFlow's
            // equality dedup never fired and all ~10 emissions/s were whole-CameraUiState copies
            // that recomposed the tree for an unchanged 120x8 dp bar. 1/256 is finer than one
            // On the densest panel here that is ~1.6 px of a 120 dp bar — below the eye's
            // threshold for a smoothly-moving meter, and the emission it saves is a whole-tree
            // recomposition. Per CHANNEL, and before the list compare: N channels are N chances
            // for a jittering low bit to defeat the dedup.
            val display = me.hletrd.telecampro.video.audioDisplayFrame(frame)
            if (display.rms.size != lastLoggedLevelChannels &&
                me.hletrd.telecampro.camera.recurringDiagnosticAllowed(
                    me.hletrd.telecampro.BuildConfig.DEBUG,
                )
            ) {
                lastLoggedLevelChannels = display.rms.size
                // Change-gated on the CHANNEL COUNT only — a per-emission line at ~10 Hz would
                // spend the ColorOS 300-row process quota in half a minute.
                android.util.Log.i("AudioLevels", "meter channels=${display.rms.size}")
            }
            _state.update {
                if (it.audioLevels == display.rms && it.audioOverloadStates == display.overloads) it
                else it.copy(audioLevels = display.rms, audioOverloadStates = display.overloads)
            }
        }
        // Run-state edges only (engine is edge-gated); may arrive from the timelapse scheduler
        // thread — StateFlow.update is thread-safe.
        engine.onTimelapseRun = { running -> _state.update { it.copy(timelapseRunning = running) } }
        engine.onCameraPolicyBlocked = { publication ->
            cameraPolicyPublicationGate.publish(publication) { blocked ->
                _state.update { cameraPolicyPublishedState(it, blocked) }
            }
        }
        engine.onAudioRoute = { route -> _state.update { it.copy(audioRoute = route) } }
        engine.onStandbyAudioAvailable = {
            mainHandler.post {
                val current = _state.value
                if (!current.isRecording && standbyMeterVisible) {
                    _state.update {
                        it.copy(audioRoute = audioInputStatus(it.audioInputPreference).route)
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
                        it.copy(
                            audioRoute = AudioRouteStatus(
                                it.audioInputPreference,
                                AudioRouteAvailability.UNAVAILABLE,
                            ),
                        )
                    }
                    publishStatus(CameraStatusMessage.STANDBY_MICROPHONE_UNAVAILABLE.status())
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
                        activeEncoderResolution = null,
                        recordElapsedMs = 0,
                        audioRoute = audioInputStatus(it.audioInputPreference).route,
                    )
                }
                refreshStandbyAudioMeter()
            }
        }
        engine.onRecordingFinalizing = { finalizing ->
            // The callback is owned by the native-release terminal, not the REC intent. Keeping it
            // separate leaves Stop visually complete while preventing review playback from racing
            // the still-live AudioRecord/container tail. StateFlow is thread-safe, so publish this
            // exact ownership edge synchronously: Stop cannot expose review for even one frame
            // between clearing visible REC and a posted finalization update.
            _state.update { it.copy(isRecordingFinalizing = finalizing) }
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
        engine.onCaptureFamilyRegistered = { captureId, familyKey, lateStillOutputs ->
            captureOutputs.registerFamily(captureId, familyKey, lateStillOutputs)
        }
        engine.onStillCaptureAdmissionChanged = { available ->
            _state.update {
                if (it.stillCaptureAdmissionAvailable == available) it
                else it.copy(stillCaptureAdmissionAvailable = available)
            }
        }
        engine.onStillCaptureAdmissionChanged?.invoke(engine.stillOutputAdmissionAvailable())
        seedPhoneModel()
        restoreSettingsIfEnabled()
        loadEncoderInventoryAsync()
        refreshProgramAppSide()
        // Sweep prior-process pending rows first, then restore the newest published family after
        // EITHER typed terminal outcome. Success includes rows just adopted by recovery; failure
        // must not hide already-published media. CaptureOutputTracker prevents either late result
        // from displacing live output, and provider probes never delay Camera2 startup.
        engine.cleanupOrphans {
            restoreLatestPublishedCapture()
        }
        refreshMemorySlotInfo()
        refreshStandbyAudioMeter()
    }

    private fun restoreLatestPublishedCapture() {
        // A live review owner already makes another provider query useless. The owner below closes
        // the check-to-submit race with one active request plus one conflated latest intent.
        if (_state.value.lastMediaUri != null) return
        latestCaptureRestoreOwner.request()
    }

    /** Main-thread publication for one exact owner completion; true drops its pending duplicate. */
    private fun publishLatestCapture(restored: RestoredCapture<Uri>): Boolean {
        if (cleared) return true
        val preferred = restored.preferred.output
        if (captureOutputs.seedRestoredCapture(restored)) {
            val deleteScope = captureOutputs.deleteScopeFor(preferred)
            _state.update {
                if (it.lastMediaUri == null && captureOutputs.isCurrentReviewOutput(preferred)) {
                    it.copy(
                        lastMediaUri = preferred,
                        lastMediaProvenance = restored.preferred.provenance,
                        lastMediaDeleteScope = deleteScope,
                    )
                } else {
                    it
                }
            }
        }
        // A live capture may have won while the provider query ran. Either owner satisfies restore;
        // only a still-empty state needs the one request conflated behind this completion.
        return _state.value.lastMediaUri != null
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
        status: CameraStatus? = null,
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
        // The fallback must be a codec this DEVICE has, not the ExtraSettings default (HEVC) — on a
        // handset with no HEVC encoder that default IS the unavailable one, so the "safe" value was
        // the broken value (2026-08-02 review). HEVC encode is not CDD-mandatory at API 33.
        val inventoryLoaded = _state.value.encoderInventoryLoaded
        val deviceCodecs = _state.value.availableVideoCodecs
        val safeCodec = when {
            !inventoryLoaded -> e.videoCodec
            deviceCodecs.contains(e.videoCodec) -> e.videoCodec
            deviceCodecs.contains(ExtraSettings().videoCodec) -> ExtraSettings().videoCodec
            else -> deviceCodecs.firstOrNull() ?: ExtraSettings().videoCodec
        }
        val requestedFormats = PhotoFormats(e.heif, e.jpeg, e.dngRaw).withDefaultIfEmpty()
        val safeFormats = requestedFormats.normalizedForEncoder(
            inventoryLoaded && _state.value.heifAvailable,
        )
        val safeTransfer = e.transfer.normalizedForEncoder(
            safeCodec,
            inventoryLoaded && _state.value.tenBitEncodeAvailable,
        )
        if (!inventoryLoaded) {
            pendingCodecUntilInventory = e.videoCodec
            pendingTransferUntilInventory = e.transfer
            pendingPhotoFormatsUntilInventory = requestedFormats
        }
        // Keep the exposure fps in lockstep with the restored video rate (mirrors onVideoFrameRate;
        // restoring them independently let the AE/shutter-angle math run at a stale fps).
        // If a launch-time preserve option deliberately changed the saved optics, reset framing to
        // that resolved home. Otherwise (including every MR recall) restore the exact saved zoom.
        val preserveChangedOptics = honorPreserveOptions && (
            (!e.preserveTeleconverter && e.teleconverter) ||
                (!e.preserveLensSelection && !requestedTeleconverter)
            )
        val requestedZoom = if (preserveChangedOptics) {
            if (requestedTeleconverter) {
                1f
            } else if (standaloneRouteWanted(
                    e.mode == CaptureMode.VIDEO,
                    PhotoFormats(e.heif, e.jpeg, e.dngRaw).withDefaultIfEmpty().dngRaw,
                    engine.rawForcesStandalone,
                )
            ) {
                (requestedLens.zoomPreset / opticalBaseFor(requestedLens.zoomPreset, _state.value.lensInventory.optical).zoomPreset)
                    .coerceAtLeast(1f)
            } else {
                requestedLens.zoomPreset
            }
        } else {
            c.zoomRatio
        }
        // The recalled packet carries its OWN converter, and the TELE zoom ceiling is derived from
        // it — resolve the magnification before anything clamps a zoom against it.
        // SettingsStore already reconciled the persisted pair; re-running it here costs nothing and
        // keeps this path correct for any caller that hands over a hand-built ExtraSettings.
        val restoredDeclaration = teleconverterDeclaration(
            phone = e.phoneModel,
            profile = e.teleconverterProfile,
            customMagnification = e.teleconverterCustomMagnification,
            measuredOtherHostEquivMm = hostTeleEquivMmFor(e.phoneModel),
        )
        val restoredConverter = restoredDeclaration.profile
        val restoredMagnification = restoredDeclaration.magnification
        val restoredOptics = restoredOptics(
            mode = e.mode,
            requestedLens = requestedLens,
            teleconverter = requestedTeleconverter,
            teleconverterMagnification = restoredMagnification,
            savedZoomRatio = requestedZoom,
        )
        val currentState = _state.value
        val restoredRoute = recalledCameraRoute(currentState.cameraRoutes, currentState.activeCameraRoute)
            ?: return
        val restoredLens = if (restoredRoute == CameraRoute.BACK) restoredOptics.lens else currentState.lens
        val restoredTeleconverter = restoredOptics.teleconverter && restoredRoute == CameraRoute.BACK
        // Clamp only when the currently accepted session is the same route as the restored target.
        // Outgoing caps are not authoritative across mode/lens recalls: applying a 0.5 s Video-lens
        // ceiling to a 4 s Photo bank would permanently destroy the photographer's saved shutter.
        // Target-route normalization still runs before that route publishes Ready.
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
        val restoredVideoCandidates = if (inventoryLoaded) {
            encoderInventory.candidatesFor(safeCodec, safeTransfer)
        } else {
            emptyList()
        }
        // Converter declaration, resolution, and hidden Photo exposure join ONE optics transaction.
        // A synchronous REC refusal mutates none of them; an asynchronous failure restores the
        // complete phone/profile/custom/host packet from the generation-owned baseline.
        val opticsAccepted = engine.setResolvedOptics(
            enabledVideo = e.mode == CaptureMode.VIDEO,
            resolvedLens = restoredLens,
            resolvedTeleconverter = restoredTeleconverter,
            resolvedDeclaration = restoredDeclaration,
            resolvedControls = cSynced,
            resolvedPhotoExposureTimeNs = photoExposureTimeNs,
            recalledVideoSize = restoredVideoSize,
            resolvedTransfer = safeTransfer,
            resolvedVideoCodec = safeCodec,
            resolvedVideoEncoderCandidates = restoredVideoCandidates,
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
        // Mirror the engine transaction: setResolvedOptics resets ITS pre-TELE return snapshot, so
        // the VM's copy must drop too (same discipline as the front-camera door). Keeping the old
        // unified zoom here made a later TC-off restore a framing the engine had already forgotten —
        // OSD said one zoom while the wire streamed another, and nothing re-converged until the
        // next gesture.
        preTeleUnifiedZoom = Float.NaN
        // MR recall / settings restore can change mode/lens/TC — i.e. the zoom SCALE. Any glide still
        // easing toward a target computed in the old scale (or a scheduled quiet landing about to fire) would
        // visibly drag the just-recalled framing away from the preset (same invariant as every remap door).
        invalidateOpticsDerivedState()
        clearTapFocusUi()
        // Manual/priority modes need luma analysis even when scopes are hidden: priority AE drives
        // from it, and full manual uses it for the live exposure meter.
        engine.setAeMetering(exposureAnalysisRequired(cSynced))
        engine.setGammaAssist(e.gammaAssist)
        engine.setVideoStabMode(e.videoStabMode)
        engine.setAspectRatio(e.aspectRatio)
        engine.setDriveMode(e.driveMode)
        val restoredIntervalSec = normalizeTimelapseIntervalSeconds(e.intervalSec)
        engine.setIntervalSec(restoredIntervalSec)
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
        engine.setRawWanted(safeFormats.dngRaw)
        // Restore the user-selected recording resolution ("Remember Settings" previously dropped it
        // silently — the engine re-picked the largest size on every launch). The engine re-validates
        // the request against the live caps once the camera opens and falls back to auto if the
        // size is no longer offered (lens change, aspect mismatch with openGate).
        cameraReadyPublicationGate.serializedStatus { statusOwner ->
            _state.update {
                it.copy(
                rememberSettings = rememberSettings ?: it.rememberSettings,
                controls = cSynced,
                // Normalized for the SAME reason photoFormats is on the next line: the seed at
                // launch cannot be the only gate, because THIS path writes the persisted value back
                // over it. A persisted HLG/log gamma restored onto an 8-bit-only encoder produced a
                // clip tagged bt2020/arib-std-b67 over a Main yuv420p stream — the UI offered SDR
                // alone while the wire still carried HLG (caught on an Android 13 emulator after
                // the seed-only fix looked correct in the menu).
                transfer = safeTransfer,
                photoFormats = safeFormats,
                mode = e.mode,
                lens = restoredLens,
                teleconverterMode = restoredTeleconverter,
                phoneModel = restoredDeclaration.phone,
                // Same re-derivation as the live picker: a restored phone that is not the one this
                // boot detected must not inherit the "Detected …" claim.
                phoneModelDetected = restoredDeclaration.phone == detectedPhone,
                teleconverterProfile = restoredConverter,
                teleconverterCustomMagnification = restoredDeclaration.customMagnification,
                // Recall/restore packets are rear-route optics; the engine's setResolvedOptics
                // exits FRONT in the same transaction, so the UI mirrors that here (MR recall
                // stays available while FRONT — it flips back as part of the recall).
                facing = restoredRoute.facing,
                activeCameraRoute = restoredRoute,
                videoStabMode = e.videoStabMode,
                aspectRatio = e.aspectRatio,
                timer = e.timer,
                driveMode = e.driveMode,
                intervalSec = restoredIntervalSec,
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
                audioRoute = audioInputStatus(e.audioInputPreference).route,
                photoFnSlots = normalizeFnSlots(e.photoFnSlots, FnSlot.PHOTO_DEFAULT),
                videoFnSlots = normalizeFnSlots(e.videoFnSlots, FnSlot.VIDEO_DEFAULT),
                myMenuSlots = normalizeFnSlots(e.myMenuSlots, FnSlot.MY_MENU_DEFAULT),
                volumeKeyAction = e.volumeKeyAction,
                halfPressAction = e.halfPressAction,
                quickButtonAction = e.quickButtonAction,
                preserveLensSelection = if (honorPreserveOptions) e.preserveLensSelection else it.preserveLensSelection,
                preserveTeleconverter = if (honorPreserveOptions) e.preserveTeleconverter else it.preserveTeleconverter,
                activeMemorySlot = activeSlot,
                status = status,
                )
            }
            armStatusTimer(status, statusOwner)
        }
        mainHandler.removeCallbacks(levelTicker)
        if (e.level && lifecycleStarted) mainHandler.post(levelTicker)
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
            // The OPERATOR's value, not the live one. While the focus-ruler assist owns the loupe
            // the two differ, and every save path funnels through here — including the background
            // save, which is exactly when the ruler can still be open.
            punchIn = if (autoPunchInActive) punchInBeforeAuto else s.punchIn,
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
        val substituteRear = s.facing == CameraFacing.FRONT && preFrontRearUnifiedZoom.isFinite()
        val controls = if (substituteRear) {
            s.controls.copy(zoomRatio = retainedRearZoomRatio(s, preFrontRearTeleconverter))
        } else {
            s.controls
        }
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
            unobscured = !s.cameraInputBlocked,
        )
        if (!forceRestart && enabled == standbyMeterEnabled) return
        standbyMeterEnabled = enabled
        engine.setStandbyAudioMonitor(enabled)
    }

    private var lastLoggedLevelChannels = -1

    override fun onStandbyAudioMeterVisibilityChanged(visible: Boolean) {
        if (standbyMeterVisible == visible) return
        standbyMeterVisible = visible
        refreshStandbyAudioMeter()
    }

    override fun onScopesVisibilityChanged(visible: Boolean) {
        scopesVisible = visible
    }

    override fun onExposureMeterVisibilityChanged(visible: Boolean) {
        exposureMeterVisible = visible
    }

    override fun onGalleryAccessRequested() {
        // Idempotent by construction: the restore only fills lastMediaUri while it is null and
        // seedPriorCapture refuses to displace live captures, so a repeat tap (or the permission
        // decorator calling through after a grant) cannot regress review state. Without media
        // access the query still returns this install's own rows — same behavior as launch.
        restoreLatestPublishedCapture()
    }

    private fun publishStatus(status: CameraStatus?) {
        cameraReadyPublicationGate.serializedStatus { statusOwner ->
            _state.update { it.copy(status = status) }
            armStatusTimer(status, statusOwner)
        }
    }

    private fun showStatus(message: CameraStatusMessage, vararg arguments: CameraStatusArgument) =
        publishStatus(message.status(*arguments))

    private fun showStatus(status: CameraStatus) = publishStatus(status)

    private fun armStatusTimer(
        status: CameraStatus?,
        owner: CameraReadyPublicationGate.StatusOwner,
    ) {
        owner.requireHeld()
        clearStatusRunnable?.let(mainHandler::removeCallbacks)
        clearStatusRunnable = null
        val sequence = ++statusSequence
        status?.durationMs?.let { durationMs ->
            val runnable = Runnable {
                cameraReadyPublicationGate.serialized {
                    if (statusSequence == sequence) _state.update { current ->
                        if (current.status == status) current.copy(status = null) else current
                    }
                }
            }
            clearStatusRunnable = runnable
            mainHandler.postDelayed(runnable, durationMs)
        }
    }

    /**
     * Retires a timer-less progress status once the condition it reports has ended. Guarded on the
     * message still being that progress status: anything published since owns the pill and must not
     * be swallowed by a late arrival of the event this clears on.
     */
    private fun clearProgressStatus(readyPublication: CameraReadyPublication? = null) {
        val clear: () -> Unit = clear@{
            if (_state.value.status?.lifecycle != CameraStatusLifecycle.PROGRESS) return@clear
            _state.update { current ->
                val status = current.status
                if (status?.lifecycle == CameraStatusLifecycle.PROGRESS) current.copy(status = null)
                else current
            }
            statusSequence++
            clearStatusRunnable?.let(mainHandler::removeCallbacks)
            clearStatusRunnable = null
        }
        if (readyPublication == null) {
            cameraReadyPublicationGate.serialized(clear)
        } else {
            cameraReadyPublicationGate.runIfOwned(readyPublication, clear)
        }
    }

    private fun rejectIfRecording(message: CameraStatusMessage = CameraStatusMessage.STOP_RECORDING_FIRST): Boolean {
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
            backOpticsDoorRefusal(
                _state.value.isRecording,
                _state.value.facing == CameraFacing.FRONT || !_state.value.cameraRoutes.back,
            )
        ) {
            BackOpticsRefusal.RECORDING -> CameraStatusMessage.STOP_RECORDING_FIRST
            BackOpticsRefusal.FRONT_ROUTE -> CameraStatusMessage.SWITCH_TO_REAR_FIRST
            BackOpticsRefusal.NONE -> return false
        }
        showStatus(message)
        return true
    }

    fun onAppStatus(message: CameraStatusMessage) = showStatus(message)

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
        if (s.activeCameraRoute.lensLocalZoom) {
            // FRONT/EXTERNAL do not use the retained rear band. Their opened lens's measured equiv
            // is the honest 1/focal input, and rear-only TELE can never apply.
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

    private fun refreshMemorySlotInfo(activeSlot: MemorySlot? = _state.value.activeMemorySlot) {
        val info = settingsStore.savedPresetInfo()
        val measuredOtherHostEquivMm = _state.value.lensInventory.teleHostEquivMm
        _state.update {
            it.copy(
                savedMemorySlots = info.keys,
                memorySlotPresentations = info.mapValues { entry ->
                    val preset = entry.value
                    val extras = preset.loaded.extras
                    val videoSize = parseVideoResolution(extras.videoResolution)
                    MemoryPresetPresentation(
                        customName = preset.customName,
                        customSummary = preset.customSummary,
                        mode = extras.mode,
                        focalMm = memoryPresetFocalMm(extras, measuredOtherHostEquivMm),
                        exposureMode = preset.loaded.controls.exposureMode,
                        photoFormats = PhotoFormats(extras.heif, extras.jpeg, extras.dngRaw),
                        videoWidth = videoSize?.width ?: 0,
                        videoHeight = videoSize?.height ?: 0,
                        videoFrameRate = extras.videoFrameRate,
                        transfer = extras.transfer,
                        bitrateLevel = extras.bitrateLevel,
                    )
                },
                activeMemorySlot = activeSlot,
            )
        }
    }

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

    override fun onWindowRotationChanged(degrees: Int) {
        engine.setWindowRotation(degrees)
    }

    override fun onFinderBottomClearanceChanged(fraction: Float) {
        engine.setFinderBottomClearance(fraction)
    }

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
            showStatus(CameraStatusMessage.CAMERA_RECONFIGURING)
            return
        }
        if (!availability.customWbCaptureEnabled) {
            showStatus(CameraStatusMessage.USE_AUTO_WB)
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
                    showStatus(CameraStatusMessage.CUSTOM_WB_MEASUREMENT_FAILED)
                    return@post
                }
                val applied = engine.consumeCustomWbSampleIfCurrent(sample) { gains ->
                    updateControls(FnSlot.WB) {
                        it.copy(wbMode = WbMode.CUSTOM, customWbGains = gains)
                    }
                }
                showStatus(
                    if (applied) CameraStatusMessage.CUSTOM_WB_SET
                    else CameraStatusMessage.CUSTOM_WB_MEASUREMENT_FAILED,
                )
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


    // DEBUG shell hooks. Only the debug manifest's DUMP-protected control Activity can publish the
    // process-local commands MainActivity consumes; ordinary launcher extras are inert. Both no-op
    // in release via this DEBUG gate, and the spike re-checks inside CameraController too.
    internal fun debugSetZslSpike(enabled: Boolean) {
        if (!me.hletrd.telecampro.BuildConfig.DEBUG) return
        engine.setZslSpike(enabled)
    }

    internal fun debugApplyZoom(ratio: Float) {
        if (!me.hletrd.telecampro.BuildConfig.DEBUG) return
        if (ratio > 0f) applyZoomRatio(ratio)
    }

    /** Read-only on-device lifecycle evidence; production state still flows through [state]. */
    internal fun previewReadinessDiagnostic(): CameraEngine.PreviewReadinessDiagnostic =
        engine.previewReadinessDiagnostic()

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
        mainHandler.postDelayed(zoomTrailingFlush, 16) // ~60 Hz: GL follows; moving ticks never submit to HAL
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
        // bare fast-path submit when the boost is already active). Every later moving tick is
        // coalesced for GL/still truth and suppressed at the HAL until the quiet landing or end.
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
        // with the new bounded edge target, no startPreview rebuild. Because the edge is spent by this same
        // flush, a re-pinch costs exactly ONE extra submit however long the gesture runs. A landing
        // at Δ250 and a new outward edge at Δ300 can still submit twice ~50 ms apart; that is the
        // trade taken knowingly — the leading edge exists precisely because
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
        // Straight to the engine zoom path — updateControls would re-apply the FULL control set.
        // The start edge already submitted above; every later moving tick updates GL/still truth
        // without submitting to Camera2. Chip highlight follows the logical seamless route only;
        // every standalone route is lens-local, including RAW/DNG Photo. Persistence rides the
        // debounced settings save.
        if (!leadingWide) engine.setZoomRatio(z)
        val s = _state.value
        // The chip band tracks the unified zoom only on the rear seamless camera; front zoom is
        // lens-local and must not remap the retained rear band (same guard as the engine's
        // reconcileControlsWithCaps).
        // Only the LOGICAL seamless camera speaks the unified scale forZoom() reads; every
        // standalone route (video, TC, and DNG) carries a lens-local ratio.
        val lensBand = if (
            !s.teleconverterMode && s.activeCameraRoute == CameraRoute.BACK &&
            !standaloneRouteWanted(s.mode == CaptureMode.VIDEO, s.photoFormats.dngRaw, s.rawForcesStandalone)
        ) {
            LensChoice.forZoom(z)
        } else {
            s.lens
        }
        _state.update { it.copy(controls = it.controls.copy(zoomRatio = z), lens = lensBand) }
        // A pending coalesced full-control apply captured OLDER controls — refresh its zoom so it can't
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
     * hardware-key ease target, and the scheduled quiet-landing / interaction-end / 16 ms-flush
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
            showStatus(CameraStatusMessage.STOP_RECORDING_FIRST)
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
            lensLocalRoute = before.activeCameraRoute.lensLocalZoom,
            // DNG keeps PHOTO on a standalone lens too, so there is no unified↔local gap to bridge.
            photoIsStandalone = standaloneRouteWanted(
                videoMode = false, rawWanted = before.photoFormats.dngRaw,
                rawForcesStandalone = before.rawForcesStandalone,
            ),
            optical = before.lensInventory.optical,
        )
        _state.update {
            it.copy(mode = mode, lens = optics.lens, controls = optics.controls)
        }
        // The mode remap invalidated the zoom SCALE — the coalesced base, any hardware-key glide whose
        // absolute target was set in the old scale, and any scheduled quiet-landing / interaction-end
        // that would otherwise submit an old-scale ratio through the outgoing controller (AGG3-10/25).
        invalidateOpticsDerivedState()
        clearTapFocusUi()
        engine.setVideoMode(
            enabled = mode == CaptureMode.VIDEO,
            resolvedLens = optics.lens,
            resolvedControls = optics.controls,
            resolvedPhotoExposureTimeNs = photoExposureTimeNs,
            resolvedTransfer = if (mode == CaptureMode.VIDEO) {
                _state.value.transfer.normalizedForEncoder(
                    _state.value.videoCodec,
                    _state.value.tenBitEncodeAvailable,
                )
            } else {
                ColorTransfer.SDR
            },
        )
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
        if (rejectIfRecording()) return
        val current = _state.value
        val safeTransfer = transfer.normalizedForEncoder(
            current.videoCodec,
            current.tenBitEncodeAvailable,
        )
        if (!current.encoderInventoryLoaded) pendingTransferUntilInventory = transfer
        if (current.encoderInventoryLoaded) {
            engine.setVideoPipeline(
                encoderInventory.candidatesFor(current.videoCodec, safeTransfer),
                safeTransfer,
                current.videoCodec,
            )
        } else {
            engine.setTransfer(safeTransfer)
        }
        _state.update { it.copy(transfer = safeTransfer) }
        markChanged(FnSlot.TRANSFER)
        scheduleSettingsSave()
    }
    override fun onSetPhotoFormats(formats: PhotoFormats) {
        cancelCountdown()
        val s = _state.value
        // A device with no HEVC encoder cannot write HEIF at all — promote JPEG instead of letting
        // the shutter produce nothing (2026-08-02 review).
        val formats = formats.normalizedForEncoder(s.heifAvailable)
        if (!s.encoderInventoryLoaded) pendingPhotoFormatsUntilInventory = formats
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
        if (rejectIfRecording()) return
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
        if (rejectIfRecording()) return
        _state.update { it.copy(recordAudio = enabled, activeMemorySlot = null) }
        refreshStandbyAudioMeter()
        saveSettingsIfEnabled()
    }
    override fun onAudioGain(gain: Float) {
        if (rejectIfRecording()) return
        val normalized = normalizeAudioGain(gain)
        engine.setAudioGain(normalized)
        _state.update { it.copy(audioGain = normalized, activeMemorySlot = null) }
        // Debounced, not immediate: this rides a slider, and a synchronous full-prefs commit per
        // drag frame stuttered the main thread. The trailing save still lands within ~0.5 s.
        scheduleSettingsSave()
    }
    override fun onAudioScene(scene: me.hletrd.telecampro.camera.AudioScene) {
        if (rejectIfRecording()) return
        engine.setAudioScene(scene)
        _state.update { it.copy(audioScene = scene) }
        markChanged(FnSlot.AUDIO_SCENE)
        scheduleSettingsSave()
    }
    override fun onAudioInputPreference(preference: AudioInputPreference) {
        if (rejectIfRecording()) return
        engine.setAudioInputPreference(preference)
        _state.update {
            it.copy(
                audioInputPreference = preference,
                audioRoute = if (it.isRecording) it.audioRoute else audioInputStatus(preference).route,
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
        var acceptedTransition: me.hletrd.telecampro.camera.TeleZoomTransition? = null
        _state.update {
            val transition = resolveTeleZoomTransition(
                nonTeleStandaloneRoute = standaloneRouteWanted(
                    it.mode == CaptureMode.VIDEO, it.photoFormats.dngRaw, it.rawForcesStandalone,
                ),
                opticalPresets = it.lensInventory.optical,
                currentLens = it.lens,
                currentTeleconverter = it.teleconverterMode,
                currentZoomRatio = it.controls.zoomRatio,
                currentPreTeleUnifiedZoom = preTeleUnifiedZoom,
                requestedLens = LensChoice.TELE3X,
                requestedTeleconverter = enabled,
                restorePreTele = !enabled,
            )
            acceptedTransition = transition
            it.copy(
                teleconverterMode = transition.teleconverter,
                lens = transition.lens,
                controls = it.controls.copy(zoomRatio = transition.zoomRatio),
            )
        }
        // Exit clears only after accepted Ready, preserving rollback ownership as before.
        if (enabled) preTeleUnifiedZoom = checkNotNull(acceptedTransition).preTeleUnifiedZoom
        // The TC scale flip overwrote the coalesced base and invalidated any hardware-key glide /
        // scheduled quiet landing set in the pre-flip scale (same invariant as every optics-remap door).
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
        val declaration = teleconverterDeclaration(
            phone = phone,
            profile = profile,
            customMagnification = custom,
            measuredOtherHostEquivMm = hostTeleEquivMmFor(phone),
        )
        engine.setTeleconverterDeclaration(declaration)
        _state.update {
            it.copy(
                phoneModel = declaration.phone,
                // Re-derived, never carried: picking a different phone by hand un-claims the
                // detection, and picking the detected one back re-claims it.
                phoneModelDetected = declaration.phone == detectedPhone,
                teleconverterProfile = declaration.profile,
                teleconverterCustomMagnification = declaration.customMagnification,
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

    /** Loads the one immutable platform codec inventory off main, then reconciles retained intent. */
    private fun loadEncoderInventoryAsync() {
        if (EncoderCaps.isLoaded()) {
            applyEncoderInventory(EncoderCaps.currentInventory())
            return
        }
        runCatching {
            ioExecutor.execute {
                val inventory = EncoderCaps.load()
                mainHandler.post {
                    if (!cleared) applyEncoderInventory(inventory)
                }
            }
        }
    }

    private fun applyEncoderInventory(inventory: CodecInventory) {
        encoderInventory = inventory
        val before = _state.value
        val requestedCodec = pendingCodecUntilInventory ?: before.videoCodec
        val requestedTransfer = pendingTransferUntilInventory ?: before.transfer
        val requestedFormats = pendingPhotoFormatsUntilInventory ?: before.photoFormats
        pendingCodecUntilInventory = null
        pendingTransferUntilInventory = null
        pendingPhotoFormatsUntilInventory = null
        val codecs = inventory.availableVideoCodecs
        val safeCodec = when {
            requestedCodec in codecs -> requestedCodec
            ExtraSettings().videoCodec in codecs -> ExtraSettings().videoCodec
            else -> codecs.firstOrNull() ?: requestedCodec
        }
        val safeTransfer = requestedTransfer.normalizedForEncoder(
            safeCodec,
            inventory.tenBitEncodeAvailable,
        )
        val safeFormats = requestedFormats.normalizedForEncoder(inventory.heifEncodeAvailable)
        engine.setVideoPipeline(
            inventory.candidatesFor(safeCodec, safeTransfer),
            safeTransfer,
            safeCodec,
        )
        engine.setRawWanted(safeFormats.dngRaw)
        _state.update {
            it.copy(
                encoderInventoryLoaded = true,
                availableVideoCodecs = codecs,
                videoCodec = safeCodec,
                heifAvailable = inventory.heifEncodeAvailable,
                photoFormats = safeFormats,
                tenBitEncodeAvailable = inventory.tenBitEncodeAvailable,
                transfer = safeTransfer,
                rawForcesStandalone = engine.rawForcesStandalone,
            )
        }
        reconcileFrameRate()
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
        // An UNRECOGNISED phone seeds PhoneModel.OTHER, not the state default: DEFAULT_PHONE_MODEL is
        // the Find X9 Ultra (this app's reason for existing), which was correct while the app only
        // installed on one handset. Since multi-device (2026-08-01) leaving it standing meant a
        // Samsung/Lenovo/vivo owner opened the Lens tab to "Phone: OPPO Find X9 Ultra", a Hasselblad
        // 300 mm converter their phone cannot mount, and a "300 mm" readout derived from a 70 mm
        // periscope they do not have (device-seen on a Lenovo TB336ZU, 2026-08-02). OTHER offers the
        // generic clip-ons, which is exactly what fits an unknown phone. Detection honesty is
        // unchanged: phoneModelDetected stays false, so the caption still claims nothing.
        val phone = detectedPhone ?: PhoneModel.OTHER
        _state.update {
            it.copy(
                phoneModel = phone,
                phoneModelDetected = detectedPhone != null,
                teleconverterProfile = defaultConverterFor(phone),
            )
        }
        val current = _state.value
        engine.setTeleconverterDeclaration(
            teleconverterDeclaration(
                phone = current.phoneModel,
                profile = current.teleconverterProfile,
                customMagnification = current.teleconverterCustomMagnification,
                measuredOtherHostEquivMm = current.teleconverterHostEquivMm,
            ),
        )
    }

    /** Host focal for a phone about to be selected: declared for a kit phone, measured for OTHER. */
    private fun hostTeleEquivMmFor(phone: PhoneModel): Float =
        if (phone == PhoneModel.OTHER && _state.value.lensInventory.teleHostEquivMm > 0f) {
            _state.value.lensInventory.teleHostEquivMm
        } else {
            phone.teleEquivMm
        }

    // UI mirror of the engine's pre-TELE framing snapshot (unified main-relative zoom).
    private var preTeleUnifiedZoom = Float.NaN
    // Last REAR optics captured at FRONT entry, substituted into settings saves while FRONT (see
    // saveSettingsIfEnabled). NaN zoom = no snapshot; stale values while rear are simply unused
    // (substitution is gated on facing == FRONT) and re-entry overwrites them.
    private var preFrontRearTeleconverter = false
    private var preFrontRearUnifiedZoom = Float.NaN

    /** Converts the canonical rear snapshot into the route a save/recall will actually restore. */
    private fun retainedRearZoomRatio(state: CameraUiState, teleconverter: Boolean): Float {
        val targetStandalone = teleconverter || standaloneRouteWanted(
            state.mode == CaptureMode.VIDEO,
            state.photoFormats.dngRaw,
            state.rawForcesStandalone,
        )
        return if (targetStandalone) {
            localZoomOf(preFrontRearUnifiedZoom, state.lensInventory.optical)
        } else {
            preFrontRearUnifiedZoom
        }
    }

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
                // Mirrors resolveLensOpticsIntent, and on the same axis it uses: the ROUTE, not the
                // mode. zoomRatio is main-relative on the logical seamless camera and LENS-LOCAL on
                // a standalone one — and wanting DNG is a standalone door just as video is. Asking
                // "is this video?" here put the main-relative 3.0 into a lens-local slot whenever
                // DNG was on, so tapping 3× gave 3× digital zoom on the 70 mm lens: OSD "208 mm",
                // readout 9.1× (3 × 70/23), wire zoom correctly 3.0 (device-reported 2026-08-03).
                controls = it.controls.copy(
                    zoomRatio = if (keepTc) {
                        1f
                    } else if (standaloneRouteWanted(
                            it.mode == CaptureMode.VIDEO, it.photoFormats.dngRaw, it.rawForcesStandalone,
                        )
                    ) {
                        // Divide by the OPTICAL lens the standalone route lands on, not by the
                        // preset: on a one-camera device "3×" is a crop of the main lens, and a flat
                        // 1× would throw that framing away (device-seen: 3× read 27 mm, not 81 mm).
                        (choice.zoomPreset / opticalBaseFor(choice.zoomPreset, it.lensInventory.optical).zoomPreset)
                            .coerceAtLeast(1f)
                    } else {
                        choice.zoomPreset
                    },
                ),
            )
        }
        // The lens-preset rewrite overwrote the coalesced base and invalidated any hardware-key glide /
        // scheduled quiet landing set in the pre-pick scale (same invariant as every optics-remap door).
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
        if (rejectIfRecording()) return
        cancelCountdown()
        drainPendingControls()
        val entering = _state.value.activeCameraRoute != CameraRoute.FRONT
        // Mirrors the engine transaction exactly (like onToggleTeleconverter mirrors setLens):
        // entering forces TC off and front-local 1×; leaving converts the canonical unified rear
        // snapshot into the target route's wire coordinate. Deliberately NO explicit settings save:
        // facing is session-only, so a kill while FRONT restores the last REAR setup — the outcome
        // the "fresh launch is BACK" rule wants. saveSettingsIfEnabled substitutes this snapshot
        // while FRONT so an incidental save keeps that promise instead of persisting front 1×.
        if (entering) {
            val before = _state.value
            preFrontRearTeleconverter = before.teleconverterMode
            preFrontRearUnifiedZoom = unifiedZoomOf(
                lens = before.lens,
                zoomRatio = before.controls.zoomRatio,
                standaloneRoute = before.teleconverterMode || standaloneRouteWanted(
                    before.mode == CaptureMode.VIDEO,
                    before.photoFormats.dngRaw,
                    before.rawForcesStandalone,
                ),
                optical = before.lensInventory.optical,
            )
        }
        engine.setFrontCamera(entering)
        _state.update {
            if (entering) {
                it.copy(
                    facing = CameraFacing.FRONT,
                    activeCameraRoute = CameraRoute.FRONT,
                    teleconverterMode = false,
                    controls = it.controls.copy(zoomRatio = 1f),
                    activeMemorySlot = null,
                )
            } else {
                it.copy(
                    facing = CameraFacing.BACK,
                    activeCameraRoute = if (it.cameraRoutes.back) CameraRoute.BACK else CameraRoute.EXTERNAL,
                    controls = it.controls.copy(
                        // Mirrors the engine transaction: restore the framing held before the front
                        // trip, NOT the lens preset — once TELE has been used the preset is 3x for
                        // the rest of the session, so the preset fallback zoomed the operator in on
                        // every flip back (user-reported).
                        zoomRatio = if (it.cameraRoutes.back) {
                            rearReturnZoom(
                                targetStandaloneRoute = standaloneRouteWanted(
                                    it.mode == CaptureMode.VIDEO,
                                    it.photoFormats.dngRaw,
                                    it.rawForcesStandalone,
                                ),
                                preFrontUnifiedZoom = preFrontRearUnifiedZoom,
                                lensPreset = it.lens.zoomPreset,
                                opticalPresets = it.lensInventory.optical,
                            )
                        } else {
                            1f
                        },
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
        if (rejectIfRecording()) return
        val current = _state.value
        val safeTransfer = current.transfer.normalizedForEncoder(
            codec,
            current.tenBitEncodeAvailable,
        )
        val candidates = encoderInventory.candidatesFor(codec, safeTransfer)
        if (candidates.isEmpty()) return
        engine.setVideoPipeline(candidates, safeTransfer, codec)
        _state.update {
            it.copy(
                videoCodec = codec,
                transfer = safeTransfer,
                activeMemorySlot = null,
            )
        }
        reconcileFrameRate()
        scheduleSettingsSave()
    }
    override fun onBitrateLevel(level: BitrateLevel) {
        if (rejectIfRecording()) return
        engine.setBitrateLevel(level)
        _state.update { it.copy(bitrateLevel = level, activeMemorySlot = null) }
        scheduleSettingsSave()
    }
    override fun onVideoResolution(size: Size) {
        if (rejectIfRecording()) return
        if (!engine.setVideoResolution(size)) return
        _state.update { it.copy(videoResolution = size, activeMemorySlot = null) }
        reconcileFrameRate()
        // The one pro setting "Remember Settings" used to drop: a user's 1080p pick silently
        // reverted to 4K on relaunch. Persisted like every sibling video setting.
        scheduleSettingsSave()
    }
    override fun onVideoFrameRate(rate: VideoFrameRate) {
        if (rejectIfRecording()) return
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
        if (rejectIfRecording()) return
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
        val videoStabChoices = availableVideoStabModes(caps.videoStabModes)
        val normalizedVideoStabMode =
            current.videoStabMode.normalizedForAvailableModes(caps.videoStabModes)
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
            !current.teleconverterMode && current.activeCameraRoute == CameraRoute.BACK &&
            !standaloneRouteWanted(
                current.mode == CaptureMode.VIDEO, current.photoFormats.dngRaw, current.rawForcesStandalone,
            )
        ) {
            LensChoice.forZoom(normalizedControls.zoomRatio)
        } else {
            current.lens
        }
        _state.update {
            it.copy(
                caps = caps,
                lens = lens,
                controls = normalizedControls,
                videoStabMode = normalizedVideoStabMode,
                videoStabChoices = videoStabChoices,
            )
        }
        if (normalizedVideoStabMode != current.videoStabMode) {
            engine.setVideoStabMode(normalizedVideoStabMode)
            scheduleSettingsSave()
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
        if (rejectIfRecording()) return
        val current = _state.value
        val normalized = current.caps?.let {
            mode.normalizedForAvailableModes(it.videoStabModes)
        } ?: return
        if (normalized == current.videoStabMode) return
        engine.setVideoStabMode(normalized)
        _state.update { it.copy(videoStabMode = normalized) }
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
        // An operator toggle ENDS the assist's ownership even if the ruler is still open: they have
        // stated an intent, so it is theirs to persist. (The assist's own close branch already
        // defers to this — "manual sheet toggles mid-drag win".)
        autoPunchInActive = false
        engine.setPunchIn(enabled)
        _state.update { it.copy(punchIn = enabled) }
        markChanged(FnSlot.PUNCH_IN)
        scheduleSettingsSave()
    }

    override fun onAutoPunchIn(enabled: Boolean) {
        if (enabled) {
            // Snapshot what the operator had, so a save landing mid-assist writes THAT.
            if (!autoPunchInActive) punchInBeforeAuto = _state.value.punchIn
            autoPunchInActive = true
        } else {
            autoPunchInActive = false
        }
        engine.setPunchIn(enabled)
        _state.update { it.copy(punchIn = enabled) }
        // Deliberately no markChanged and no scheduleSettingsSave: the assist is not a setting.
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
        val normalized = normalizeTimelapseIntervalSeconds(sec)
        engine.setIntervalSec(normalized)
        _state.update { it.copy(intervalSec = normalized) }
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
        if (!hardwareActionAdmitted(action, _state.value.primaryShutterEnabled)) return
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
            timelapseRunning = state.timelapseRunning,
            countdownSeconds = state.timerCountdownSec,
            stillCaptureReady = state.stillCaptureReady,
            configuredDelaySeconds = photoShutterDelaySeconds(
                configuredDelaySeconds = state.timer.seconds,
                recording = state.isRecording,
            ),
            stopTimelapse = engine::stopTimelapse,
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
                    activeEncoderResolution = null,
                    recordElapsedMs = 0,
                    audioRoute = audioInputStatus(it.audioInputPreference).route,
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
                    audioRoute = AudioRouteStatus(
                        s.audioInputPreference,
                        if (s.recordAudio) AudioRouteAvailability.STARTING else AudioRouteAvailability.OFF,
                    ),
                    isRecording = true,
                    isRecordingStarting = true,
                    activeEncoderResolution = null,
                    recordElapsedMs = 0,
                )
            }
            if (s.recordAudio && !inputStatus.available) {
                // ";" is the clause joiner every sibling status uses ("Camera unavailable; mode
                // unchanged"). A comma cannot do it here: the left operand is a port label that can
                // itself read "Auto · No mic", so a comma would bind a clause inside a · list.
                showStatus(
                    CameraStatusMessage.AUDIO_INPUT_USING_DEFAULT,
                    CameraStatusArgument.AudioInput(inputStatus.route.preference),
                )
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
                                activeEncoderResolution = null,
                                recordElapsedMs = 0,
                                audioRoute = audioInputStatus(it.audioInputPreference).route,
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
        if (rejectIfRecording()) return
        val live = _state.value
        // Same FRONT substitution as saveSettingsIfEnabled: recalled packets are REAR-route optics
        // by contract (setResolvedOptics exits FRONT), so a preset stored while FRONT must persist
        // the retained rear setup, not the front-session hybrid (TC forced off, front-local 1×).
        // Without this, saving M1 during a selfie trip silently replaced the operator's TELE 5×
        // preset with rear MAIN at 1× — the exact cycle-6 F6 defect class, fixed for the plain save
        // path but not here. The substituted view also feeds the name/summary so the label describes
        // what recall will actually restore.
        val substituteRear = live.facing == CameraFacing.FRONT && preFrontRearUnifiedZoom.isFinite()
        val snapshot = if (substituteRear) {
            live.copy(
                teleconverterMode = preFrontRearTeleconverter,
                controls = live.controls.copy(
                    zoomRatio = retainedRearZoomRatio(live, preFrontRearTeleconverter),
                ),
            )
        } else {
            live
        }
        val extras = if (substituteRear) {
            currentExtras().copy(teleconverter = preFrontRearTeleconverter)
        } else {
            currentExtras()
        }
        settingsStore.saveGeneratedPreset(
            slot,
            snapshot.controls,
            extras,
        )
        refreshMemorySlotInfo(activeSlot = slot)
        showStatus(
            CameraStatusMessage.MEMORY_SLOT_SAVED,
            CameraStatusArgument.Text(slot.name),
        )
    }

    override fun onRecallMemorySlot(slot: MemorySlot) {
        if (_state.value.isRecording) {
            // The canonical REC refusal, word for word: every other site in the VM and the engine
            // says exactly this, and StatusUrgencyTest pins it. One refusal, one voice.
            showStatus(CameraStatusMessage.STOP_RECORDING_FIRST)
            return
        }
        val loaded = settingsStore.loadPreset(slot)
        if (loaded == null) {
            showStatus(CameraStatusMessage.MEMORY_SLOT_EMPTY, CameraStatusArgument.Text(slot.name))
            return
        }
        applyLoaded(
            loaded,
            activeSlot = slot,
            status = CameraStatusMessage.MEMORY_SLOT_LOADED.status(CameraStatusArgument.Text(slot.name)),
        )
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
        if (open && !reviewTargetEnabled(
                recordingStarting = _state.value.isRecordingStarting,
                recording = _state.value.isRecording,
                recordingFinalizing = _state.value.isRecordingFinalizing,
            )
        ) {
            // Defense in depth for non-Compose callers. A review would remove the visible Stop
            // control, block its hardware-key twin through modal ownership, and may autoplay an
            // older video's speaker audio into the still-live recording microphone.
            showStatus(CameraStatusMessage.STOP_RECORDING_FIRST)
            return false
        }
        // A one-shot timer must never finish behind a full-screen review. Cancel before pinning or
        // publishing modal ownership so no scheduler tick can race the visible transition.
        if (open) cancelCountdown()
        // Pin before publishing the modal state: a concurrent capture callback may trim ordinary
        // history, but it cannot evict the exact family the confirmation copy now describes.
        if (!open) {
            captureOutputs.releaseReviewPin(uri)
            // A stale overlay callback may retire only the exact review it rendered. Releasing its
            // URI from the tracker is harmless when a newer pin exists; releasing the shared REVIEW
            // input owner or clearing the newer presentation would not be.
            if (_state.value.openReview?.uri != uri) return false
            _state.update { current ->
                if (current.openReview?.uri == uri) current.copy(openReview = null) else current
            }
            onCameraInputBlockOwnerChange(CameraInputBlockOwner.REVIEW, false)
            return false
        }

        val before = _state.value
        val familyPinned = captureOutputs.pinForReview(uri)
        val frozen = OpenReviewPresentation(
            uri = uri,
            provenance = if (before.lastMediaUri == uri) {
                before.lastMediaProvenance
            } else {
                captureOutputs.provenanceFor(uri) ?: MediaProvenance.APP_OWNED
            },
            deleteScope = if (familyPinned) {
                if (before.lastMediaUri == uri) before.lastMediaDeleteScope
                else captureOutputs.deleteScopeFor(uri)
            } else {
                MediaDeleteScope.FILE_ONLY
            },
        )
        onCameraInputBlockOwnerChange(CameraInputBlockOwner.REVIEW, true)
        _state.update { it.copy(openReview = frozen) }
        return familyPinned
    }

    override fun onCameraInputBlockedChange(blocked: Boolean) {
        onCameraInputBlockOwnerChange(CameraInputBlockOwner.COMPOSE_MODAL, blocked)
    }

    private val cameraInputBlockOwnerLock = Any()
    private var cameraInputBlockOwners: Set<CameraInputBlockOwner> = emptySet()

    internal fun onCameraInputBlockOwnerChange(owner: CameraInputBlockOwner, blocked: Boolean) {
        // Acquisition cancels synchronously; release never changes timer state. Owner identity is
        // the important part: a newly composed CameraScreen may release COMPOSE_MODAL without
        // releasing an Activity-owned permission surface restored across recreation.
        if (blocked) cancelCountdown()
        val changed = synchronized(cameraInputBlockOwnerLock) {
            val updated = cameraInputBlockOwnersAfter(cameraInputBlockOwners, owner, blocked)
            if (updated == cameraInputBlockOwners) {
                false
            } else {
                cameraInputBlockOwners = updated
                _state.update { it.copy(cameraInputBlocked = updated.isNotEmpty()) }
                true
            }
        }
        if (changed) refreshStandbyAudioMeter()
    }

    /**
     * Freezes one owner-unverified row for an Activity-owned MediaStore delete request.
     *
     * The frozen review provenance is supplied by Compose, while the tracker is the stronger live
     * authority when it still owns the URI. Either one saying owner-unverified selects consent; an
     * ownerless row can therefore never fall through to the direct app-owned delete path merely
     * because its bounded tracker entry disappeared.
     */
    internal fun prepareOwnerlessMediaDelete(
        uri: Uri,
        presentedProvenance: MediaProvenance,
    ): OwnerlessMediaDeletePreparation {
        val route = mediaDeleteAuthorizationRoute(
            trackedProvenance = captureOutputs.provenanceFor(uri),
            presentedProvenance = presentedProvenance,
        )
        if (route == MediaDeleteAuthorizationRoute.DIRECT_APP_OWNED) {
            return OwnerlessMediaDeletePreparation.DirectAppOwned
        }
        if (pendingOwnerlessMediaDelete != null) {
            return OwnerlessMediaDeletePreparation.Rejected
        }

        val rawPlan = captureOutputs.beginDelete(uri)
        val plan = rawPlan.copy(
            // A pin can fail only after bounded-history loss. Preserve the frozen review's
            // unverified authorship instead of defaulting a canceled request back to APP_OWNED.
            provenanceByOutput = rawPlan.provenanceByOutput +
                (uri to MediaProvenance.LEGACY_FORMAT_UNVERIFIED),
            deleteScope = MediaDeleteScope.FILE_ONLY,
            outputs = setOf(uri),
        )
        val request = OwnerlessMediaDeleteRequest(
            uri = uri,
            generation = ++ownerlessMediaDeleteGeneration,
        )
        pendingOwnerlessMediaDelete = PendingOwnerlessMediaDelete(request, plan)
        onCameraInputBlockOwnerChange(CameraInputBlockOwner.OWNERLESS_DELETE, true)
        _state.update { current ->
            current.copy(
                lastMediaUri = current.lastMediaUri.takeUnless { it == uri },
                lastMediaDeleteScope = MediaDeleteScope.FILE_ONLY,
                ownerlessDeleteConsentPending = true,
            )
        }
        return OwnerlessMediaDeletePreparation.ConsentRequired(request)
    }

    /** Starts finite, deadlined request construction without retaining an Activity in provider work. */
    internal fun beginOwnerlessMediaDeleteRequestCreation(request: OwnerlessMediaDeleteRequest) {
        val pending = pendingOwnerlessMediaDelete?.takeIf { it.request == request } ?: return
        val terminal = FirstWinsTerminal<OwnerlessMediaDeleteRequestCreation> { outcome ->
            mainHandler.post {
                if (cleared || pendingOwnerlessMediaDelete !== pending) return@post
                when (outcome) {
                    is OwnerlessMediaDeleteRequestCreation.Ready -> {
                        _ownerlessMediaDeleteLaunch.value = OwnerlessMediaDeleteLaunch(
                            request = request,
                            pendingIntent = outcome.pendingIntent,
                        )
                    }
                    OwnerlessMediaDeleteRequestCreation.Failed,
                    OwnerlessMediaDeleteRequestCreation.Rejected,
                    OwnerlessMediaDeleteRequestCreation.TimedOut,
                    -> completeOwnerlessMediaDelete(
                        pending,
                        ownerlessMediaDeleteResolution(
                            OwnerlessMediaDeleteConsentResult.LAUNCH_FAILED,
                            KnownOutputProviderDisposition.UNKNOWN,
                        ),
                    )
                }
            }
        }
        armFirstWinsTimeout(
            terminal = terminal,
            timeoutValue = OwnerlessMediaDeleteRequestCreation.TimedOut,
            timeoutMs = OWNERLESS_MEDIA_DELETE_PROVIDER_TIMEOUT_MS,
            postDelayed = mainHandler::postDelayed,
        )
        if (!terminal.isPending()) return

        // Capture only process-safe values. If Binder wedges past the timeout, FirstWinsTerminal has
        // already dropped the callback that owns this ViewModel and the replacement Activity.
        val resolver = getApplication<Application>().contentResolver
        val createRequest = ownerlessMediaDeleteOverrides.createDeleteRequest
        val dispatch = mediaDeleteDispatcher.dispatch(
            Runnable {
                val result = runCatching { createRequest(resolver, request.uri) }
                terminal.complete(
                    result.fold(
                        onSuccess = OwnerlessMediaDeleteRequestCreation::Ready,
                        onFailure = { OwnerlessMediaDeleteRequestCreation.Failed },
                    ),
                )
            },
        )
        if (dispatch != ViewModelMediaDeleteDispatch.ACCEPTED) {
            terminal.complete(OwnerlessMediaDeleteRequestCreation.Rejected)
        }
    }

    /** Claims a launch event only while it still belongs to the exact frozen review generation. */
    internal fun claimOwnerlessMediaDeleteLaunch(launch: OwnerlessMediaDeleteLaunch): Boolean {
        if (_ownerlessMediaDeleteLaunch.value != launch) return false
        if (pendingOwnerlessMediaDelete?.request != launch.request) return false
        _ownerlessMediaDeleteLaunch.value = null
        return true
    }

    /** Activity result edge. Approval is already terminal provider work; never delete a second time. */
    internal fun onOwnerlessMediaDeleteConsentResult(result: OwnerlessMediaDeleteConsentResult) {
        val request = pendingOwnerlessMediaDelete?.request ?: return
        onOwnerlessMediaDeleteConsentResult(request, result)
    }

    /** Exact request form used when request launch itself fails after a newer generation could exist. */
    internal fun onOwnerlessMediaDeleteConsentResult(
        request: OwnerlessMediaDeleteRequest,
        result: OwnerlessMediaDeleteConsentResult,
    ) {
        val pending = pendingOwnerlessMediaDelete?.takeIf { it.request == request } ?: return
        if (result == OwnerlessMediaDeleteConsentResult.APPROVED) {
            completeOwnerlessMediaDelete(
                pending,
                ownerlessMediaDeleteResolution(
                    result,
                    KnownOutputProviderDisposition.ALREADY_ABSENT,
                ),
            )
            return
        }
        if (pending.reconciliationStarted) return
        pending.reconciliationStarted = true

        val application = getApplication<Application>()
        val queryPresence = ownerlessMediaDeleteOverrides.queryPresence
        val terminal = FirstWinsTerminal<KnownOutputProviderDisposition> { presence ->
            mainHandler.post {
                if (!cleared && pendingOwnerlessMediaDelete === pending) {
                    completeOwnerlessMediaDelete(
                        pending,
                        ownerlessMediaDeleteResolution(result, presence),
                    )
                }
            }
        }
        armFirstWinsTimeout(
            terminal = terminal,
            timeoutValue = KnownOutputProviderDisposition.UNKNOWN,
            timeoutMs = OWNERLESS_MEDIA_DELETE_PROVIDER_TIMEOUT_MS,
            postDelayed = mainHandler::postDelayed,
        )
        if (!terminal.isPending()) return
        val dispatch = mediaDeleteDispatcher.dispatch(
            Runnable {
                terminal.complete(queryPresence(application, request.uri))
            },
        )
        if (dispatch != ViewModelMediaDeleteDispatch.ACCEPTED) {
            // No provider worker was available. UNKNOWN preserves the exact review handle for a
            // later attempt; it never guesses that cancellation/failure deleted the row.
            terminal.complete(KnownOutputProviderDisposition.UNKNOWN)
        }
    }

    private fun completeOwnerlessMediaDelete(
        pending: PendingOwnerlessMediaDelete,
        resolution: OwnerlessMediaDeleteResolution,
    ) {
        if (pendingOwnerlessMediaDelete !== pending) return
        pendingOwnerlessMediaDelete = null
        _ownerlessMediaDeleteLaunch.value = null
        val restored = captureOutputs.restoreDeleteSurvivors(
            pending.plan,
            if (resolution.restoreExactFile) setOf(pending.request.uri) else emptySet(),
        )
        _state.update { current ->
            val terminal = current.copy(
                ownerlessDeleteConsentPending = false,
            )
            if (restored != null && captureOutputs.isCurrentReviewOutput(restored.output)) {
                terminal.withDeleteSurvivor(restored)
            } else {
                terminal
            }
        }
        onCameraInputBlockOwnerChange(CameraInputBlockOwner.OWNERLESS_DELETE, false)
        showStatus(resolution.status)
    }

    override fun onDeleteLastMedia(uri: Uri, provenance: MediaProvenance) {
        if (mediaDeleteAuthorizationRoute(captureOutputs.provenanceFor(uri), provenance) ==
            MediaDeleteAuthorizationRoute.SYSTEM_CONSENT
        ) {
            // Defense in depth for direct/non-Activity callers: only MainActivity can own the
            // platform IntentSender result, so never attempt an unauthorized resolver delete here.
            showStatus(CameraStatusMessage.DELETE_AUTHORIZATION_UNAVAILABLE)
            return
        }
        // Freeze ownership and tombstone the id BEFORE the Binder calls. Any slower HEIF/JPEG/DNG
        // callback for the shot is then rejected and deleted instead of replacing the thumbnail.
        val deletePlan = captureOutputs.beginDelete(uri)
        // Retained still completion belongs to the Engine's I/O lane and can outlive this ViewModel.
        // Publish the same tombstone there synchronously so a late private row takes the durable
        // DISCARD path without calling back into this UI owner or its soon-to-shut-down executor.
        engine.markCaptureDeleted(
            CaptureFamilyDeleteIntent(
                familyKey = deletePlan.familyKey,
                scope = deletePlan.deleteScope,
                liveStillCaptureId = deletePlan.liveStillCaptureId,
            ),
        ) deleteReady@{ durability ->
            if (durability == CaptureFamilyDeleteDurability.FAILED) {
                val restored = captureOutputs.restoreDeleteSurvivors(deletePlan, deletePlan.outputs)
                mainHandler.post {
                    if (restored != null && captureOutputs.isCurrentReviewOutput(restored.output)) {
                        _state.update {
                            it.withDeleteSurvivor(restored)
                        }
                    }
                    showStatus(CameraStatusMessage.COULD_NOT_DELETE_FILE)
                }
                return@deleteReady
            }
            val outputs = deletePlan.outputs
            // The open overlay can still hold the RAW URI after a processed sibling upgraded the
            // thumbnail. Clear only after whole-family intent is durably owned.
            _state.update {
                if (it.lastMediaUri in outputs) {
                    it.copy(lastMediaUri = null, lastMediaDeleteScope = MediaDeleteScope.FILE_ONLY)
                } else {
                    it
                }
            }
            val application = getApplication<Application>()
            val dispatch = mediaDeleteDispatcher.dispatch(
                Runnable {
                    // BEFORE the known outputs go, sweep exact-family siblings the tracker never
                    // learned about. This includes a publish-failed pending row and the cross-Engine
                    // ordering where an old publication completed immediately before Delete won.
                    // The frozen tracker rows stay excluded so survivor accounting remains exact.
                    var sweep = DeletedFamilySweepResult()
                    if (deletePlan.deleteScope == MediaDeleteScope.CAPTURE_FAMILY) {
                        sweep = deletePlan.familyKey?.let { family ->
                            MediaStoreWriter.deleteUntrackedFamilySiblings(
                                context = application,
                                family = family,
                                excluded = deletePlan.outputs,
                            )
                        } ?: DeletedFamilySweepResult.QUERY_FAILED
                    }
                    val knownOutputs = knownOutputDeleteComposition(
                        outputs.associateWith { output ->
                            runCatching {
                                MediaStoreWriter.deleteKnownOutput(application, output)
                            }.getOrDefault(
                                KnownOutputDeletionResult(
                                    provider = KnownOutputProviderDisposition.UNKNOWN,
                                    markerCleanup = DiscardMarkerCleanupDisposition.NOT_ATTEMPTED,
                                ),
                            )
                        },
                    )
                    // Restore only rows the provider authoritatively confirmed still exist. An
                    // absent row with a retained DISCARD marker needs cleanup retry, not a phantom
                    // review owner; an unknown row likewise has no safe handle to republish.
                    val restored = captureOutputs.restoreDeleteSurvivors(
                        deletePlan,
                        knownOutputs.survivors,
                    )
                    deletePlan.familyKey?.let { family ->
                        engine.reconcileDeletedFamilyAfterProviderMutation(
                            family = family,
                            liveStillCaptureId = deletePlan.liveStillCaptureId,
                        )
                    }
                    mainHandler.post {
                        if (cleared) return@post
                        if (restored != null) {
                            _state.update { current ->
                                resolveDeleteSurvivorState(
                                    current = current,
                                    survivor = restored,
                                    captureOutputs = captureOutputs,
                                )
                            }
                        }
                        // A later capture can replace review ownership immediately after the survivor
                        // decision. Gallery is the one retry route that stays truthful across that
                        // race and across an uncertain exact-family sweep.
                        showStatus(
                            deleteResultStatus(
                                knownOutputs.providerDeletionComplete,
                                sweep,
                            ).status(),
                        )
                    }
                },
            )
            if (dispatch != ViewModelMediaDeleteDispatch.ACCEPTED) {
                val restored = if (deletePlan.deleteScope == MediaDeleteScope.FILE_ONLY) {
                    // No durable family marker exists for a legacy/file-only row. Keep that exact
                    // file reviewable so the operator can retry instead of losing the in-app handle.
                    captureOutputs.restoreDeleteSurvivors(deletePlan, outputs)
                } else {
                    null
                }
                // A family delete retains its durable marker; a file-only delete restores the exact
                // review handle above. Provider work was not started or run inline, so terminal
                // success would be false in either case. Gallery is the race-safe retry route.
                mainHandler.post {
                    if (!cleared) {
                        if (restored != null) {
                            _state.update { current ->
                                resolveDeleteSurvivorState(current, restored, captureOutputs)
                            }
                        }
                        showStatus(CameraStatusMessage.SOME_FILES_NOT_DELETED_RETRY_GALLERY)
                    }
                }
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
                            lastMediaProvenance = MediaProvenance.APP_OWNED,
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
        val application = getApplication<Application>()
        val dispatch = mediaDeleteDispatcher.dispatch(
            Runnable {
                // This is not a newly rejected output: the durable whole-family marker already owns
                // restart recovery, so it must not consume the process rejected-output headroom.
                if (MediaStoreWriter.discardPendingOutput(application, uri) ==
                    me.hletrd.telecampro.storage.PendingOutputDiscardResult.UNRESOLVED
                ) {
                    mainHandler.post {
                        if (cleared) return@post
                        _state.update { it.copy(stillCaptureAdmissionAvailable = false) }
                        showStatus(CameraStatusMessage.COULD_NOT_DELETE_FILE)
                    }
                }
            },
        )
        if (dispatch != ViewModelMediaDeleteDispatch.ACCEPTED) {
            // No inline fallback. The capture-family marker remains the durable restart owner.
            mainHandler.post {
                if (!cleared) showStatus(CameraStatusMessage.COULD_NOT_DELETE_FILE)
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
        infoRefresh.start()
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
        // Invalidates both a worker result and a result already posted to main. A later start owns a
        // fresh generation and, if the old worker is still blocked, exactly one pending refresh.
        infoRefresh.stop()
        recordingAttemptGeneration++
        customWbSampleGeneration++
        // A backgrounded Activity owns no visible camera condition. Exact terminal/new events will
        // publish again after resume; leaving untimed recovery copy behind would resurrect a stale
        // pill over the next generation.
        clearProgressStatus()
        cancelCountdown()
        // engine.pause() finalizes any in-flight recording; keep the UI in sync so we don't return
        // to a phantom "recording" state with the timer still ticking.
        if (_state.value.isRecording) {
            mainHandler.removeCallbacks(recordTicker)
            _state.update {
                it.copy(
                    isRecording = false,
                    isRecordingStarting = false,
                    activeEncoderResolution = null,
                    recordElapsedMs = 0,
                )
            }
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
        cleared = true
        infoRefresh.stop()
        // Completion posts and teardown share main. Close first so a queued provider result cannot
        // publish, then remove its already-posted Runnable with every other stale ViewModel task.
        latestCaptureRestoreOwner.close()
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
        // Refuse new work from this stale ViewModel facade. Accepted provider tasks keep their exact
        // identity on the finite process owner; neither teardown nor replacement multiplies lanes.
        mediaDeleteDispatcher.shutdown()
        // Let the remaining one-shot/coalesced ViewModel work finish, then retire its daemon lane.
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

/** Publishes the exact survivor identity atomically after file-only or partial deletion. */
internal fun CameraUiState.withDeleteSurvivor(
    survivor: CaptureDeleteSurvivor<Uri>,
): CameraUiState = copy(
    lastMediaUri = survivor.output,
    lastMediaProvenance = survivor.provenance,
    lastMediaDeleteScope = survivor.deleteScope,
)

/** Restores a survivor only while it still owns the review entry at StateFlow publication time. */
internal fun resolveDeleteSurvivorState(
    current: CameraUiState,
    survivor: CaptureDeleteSurvivor<Uri>,
    captureOutputs: CaptureOutputTracker<Uri>,
): CameraUiState = if (captureOutputs.isCurrentReviewOutput(survivor.output)) {
    current.withDeleteSurvivor(survivor)
} else current

/** ViewModel-facing reduction of provider truth; retry metadata never becomes review presence. */
internal data class KnownOutputDeleteComposition<T>(
    val survivors: Set<T>,
    val cleanupRetry: Set<T>,
    val providerUnknown: Set<T>,
) {
    val providerDeletionComplete: Boolean
        get() = survivors.isEmpty() && providerUnknown.isEmpty()
}

internal fun <T> knownOutputDeleteComposition(
    results: Map<T, KnownOutputDeletionResult>,
): KnownOutputDeleteComposition<T> = KnownOutputDeleteComposition(
    survivors = results.filterValues(KnownOutputDeletionResult::restoreAsSurvivor).keys,
    cleanupRetry = results.filterValues(KnownOutputDeletionResult::cleanupRetryRequired).keys,
    providerUnknown = results.filterValues(KnownOutputDeletionResult::providerUnknown).keys,
)

/** Gallery remains valid even if a newer capture replaces the in-app review owner. */
internal fun deleteResultStatus(
    knownProviderDeletionComplete: Boolean,
    untrackedSweep: DeletedFamilySweepResult,
): CameraStatusMessage =
    if (knownProviderDeletionComplete && untrackedSweep.complete) CameraStatusMessage.DELETED
    else CameraStatusMessage.SOME_FILES_NOT_DELETED_RETRY_GALLERY

/** Which owner is authorized to perform the exact frozen review-file deletion. */
internal enum class MediaDeleteAuthorizationRoute {
    DIRECT_APP_OWNED,
    SYSTEM_CONSENT,
}

internal fun mediaDeleteAuthorizationRoute(
    trackedProvenance: MediaProvenance?,
    presentedProvenance: MediaProvenance,
): MediaDeleteAuthorizationRoute = if (
    trackedProvenance == MediaProvenance.LEGACY_FORMAT_UNVERIFIED ||
    presentedProvenance == MediaProvenance.LEGACY_FORMAT_UNVERIFIED
) {
    MediaDeleteAuthorizationRoute.SYSTEM_CONSENT
} else {
    MediaDeleteAuthorizationRoute.DIRECT_APP_OWNED
}

internal sealed interface OwnerlessMediaDeletePreparation {
    data object DirectAppOwned : OwnerlessMediaDeletePreparation
    data class ConsentRequired(
        val request: OwnerlessMediaDeleteRequest,
    ) : OwnerlessMediaDeletePreparation
    data object Rejected : OwnerlessMediaDeletePreparation
}

internal enum class OwnerlessMediaDeleteConsentResult {
    APPROVED,
    CANCELED,
    LAUNCH_FAILED,
}

internal data class OwnerlessMediaDeleteResolution(
    val restoreExactFile: Boolean,
    val status: CameraStatusMessage,
)

/**
 * Reduces the system result with fresh provider truth. RESULT_OK means createDeleteRequest already
 * completed the deletion; every other outcome restores unless absence is authoritative.
 */
internal fun ownerlessMediaDeleteResolution(
    result: OwnerlessMediaDeleteConsentResult,
    presence: KnownOutputProviderDisposition,
): OwnerlessMediaDeleteResolution = when {
    result == OwnerlessMediaDeleteConsentResult.APPROVED -> OwnerlessMediaDeleteResolution(
        restoreExactFile = false,
        status = CameraStatusMessage.DELETED,
    )
    presence == KnownOutputProviderDisposition.ALREADY_ABSENT ||
        presence == KnownOutputProviderDisposition.DELETED -> OwnerlessMediaDeleteResolution(
        restoreExactFile = false,
        status = CameraStatusMessage.FILE_ALREADY_REMOVED,
    )
    result == OwnerlessMediaDeleteConsentResult.CANCELED -> OwnerlessMediaDeleteResolution(
        restoreExactFile = true,
        status = CameraStatusMessage.DELETE_CANCELED,
    )
    else -> OwnerlessMediaDeleteResolution(
        restoreExactFile = true,
        status = CameraStatusMessage.DELETE_AUTHORIZATION_UNAVAILABLE,
    )
}

/** Mirrors the pre-open route decision into UI truth without inventing a second selection policy. */
internal fun cameraRoutePublishedState(
    current: CameraUiState,
    routes: CameraRouteInventory,
    activeRoute: CameraRoute,
    rawForcesStandalone: Boolean,
): CameraUiState = current.copy(
    cameraRoutes = routes,
    facing = activeRoute.facing,
    activeCameraRoute = activeRoute,
    teleconverterMode = current.teleconverterMode && activeRoute == CameraRoute.BACK,
    rawForcesStandalone = rawForcesStandalone,
    controls = if (activeRoute.lensLocalZoom) {
        current.controls.copy(zoomRatio = 1f)
    } else {
        current.controls
    },
)

internal fun standbyAudioMeterShouldRun(
    lifecycleStarted: Boolean,
    visible: Boolean,
    mode: CaptureMode,
    recordAudio: Boolean,
    recording: Boolean,
    unobscured: Boolean = true,
): Boolean = lifecycleStarted && visible && unobscured &&
    mode == CaptureMode.VIDEO && recordAudio && !recording

/** A delayed async REC admission result may mutate only the optimistic UI attempt that submitted it. */
internal fun recordingAttemptOwnsGeneration(
    currentGeneration: Long,
    expectedGeneration: Long,
    isRecording: Boolean,
    isRecordingStarting: Boolean,
): Boolean = currentGeneration == expectedGeneration && isRecording && isRecordingStarting

internal data class LifecycleInfoSample(
    val batteryPct: Int,
    val freeBytes: Long,
)

internal data class LifecycleInfoRefreshSnapshot(
    val activeGeneration: Long?,
    val inFlightRequests: Int,
    val pendingRequests: Int,
)

/**
 * Bounded owner for the lifecycle OSD's slow battery/storage sample.
 *
 * [submit] deliberately targets the ViewModel's existing serial I/O executor: capture restore and
 * delete operations keep their established FIFO order. This owner changes only telemetry
 * cardinality. While one sample is submitted/running, every later tick collapses into one pending
 * request for the latest active lifecycle generation. Stop invalidates publication and clears an
 * old pending intent; a subsequent Start may install one new-generation pending request behind the
 * still-running old sample.
 */
internal class LifecycleInfoRefresh<T : Any>(
    private val submit: (Runnable) -> Boolean,
    private val sample: () -> T,
    private val deliver: (generation: Long, T) -> Unit,
) {
    private data class Request(val generation: Long)
    private data class Completion(val publish: Boolean, val next: Request?)

    private val lock = Any()
    private var generation = 0L
    private var activeGeneration: Long? = null
    private var inFlightGeneration: Long? = null
    private var pendingGeneration: Long? = null

    /** Opens a fresh publication generation; the caller separately requests its immediate sample. */
    fun start(): Long = synchronized(lock) {
        generation += 1
        activeGeneration = generation
        generation
    }

    /** Invalidates accepted/posted results and any intent that has not reached the executor. */
    fun stop() = synchronized(lock) {
        generation += 1
        activeGeneration = null
        pendingGeneration = null
    }

    fun request() {
        val request = synchronized(lock) {
            val active = activeGeneration ?: return
            if (inFlightGeneration == null) {
                inFlightGeneration = active
                Request(active)
            } else {
                pendingGeneration = active
                null
            }
        }
        request?.let(::dispatch)
    }

    /** Rechecked by the main-thread delivery because Stop may race a worker-to-main post. */
    fun isActive(expectedGeneration: Long): Boolean = synchronized(lock) {
        activeGeneration == expectedGeneration
    }

    internal fun snapshot(): LifecycleInfoRefreshSnapshot = synchronized(lock) {
        LifecycleInfoRefreshSnapshot(
            activeGeneration = activeGeneration,
            inFlightRequests = if (inFlightGeneration == null) 0 else 1,
            pendingRequests = if (pendingGeneration == null) 0 else 1,
        )
    }

    private fun dispatch(request: Request) {
        val accepted = runCatching {
            submit(
                Runnable {
                    val value = runCatching(sample).getOrNull()
                    val completion = finish(request, publishCandidate = value != null)
                    try {
                        if (completion.publish && value != null) deliver(request.generation, value)
                    } finally {
                        // Enqueue only after this worker has classified itself. On the shared
                        // single-thread executor, user work accepted while it ran remains ahead of
                        // this coalesced tail.
                        completion.next?.let(::dispatch)
                    }
                },
            )
        }.getOrDefault(false)
        if (!accepted) {
            finish(request, publishCandidate = false).next?.let(::dispatch)
        }
    }

    private fun finish(request: Request, publishCandidate: Boolean): Completion = synchronized(lock) {
        if (inFlightGeneration != request.generation) return@synchronized Completion(false, null)
        val publish = publishCandidate && activeGeneration == request.generation
        val nextGeneration = pendingGeneration?.takeIf { it == activeGeneration }
        pendingGeneration = null
        inFlightGeneration = nextGeneration
        Completion(publish, nextGeneration?.let(::Request))
    }
}

internal data class LatestCaptureRestoreSnapshot(
    val inFlightRequests: Int,
    val pendingRequests: Int,
    val closed: Boolean,
)

/**
 * VM-lifetime latest-capture restore admission: one provider query plus one conflated intent.
 *
 * [submit] targets the existing serial `vm-io` lane. [postCompletion] and [close] must target the
 * same serialized owner thread (main in production), which makes clear-first completion inert and
 * publish-first completion precede teardown without holding this short state lock across UI work.
 * Repeated requests retain no caller Runnable or callback graph: one Boolean means "query current
 * provider truth once more after this query." A successful [publish] satisfies and drops that bit;
 * null/failure or an ownership-rejected result runs exactly one latest follow-up.
 */
internal class LatestCaptureRestoreOwner<T : Any>(
    private val submit: (Runnable) -> Boolean,
    private val postCompletion: (Runnable) -> Boolean,
    private val query: () -> T?,
    private val publish: (T) -> Boolean,
) {
    private data class Request(val generation: Long)

    private val lock = Any()
    private var generation = 0L
    private var inFlight: Request? = null
    private var pending = false
    private var closed = false

    fun request() {
        val request = synchronized(lock) {
            if (closed) return
            if (inFlight != null) {
                pending = true
                null
            } else {
                Request(++generation).also { inFlight = it }
            }
        }
        request?.let(::dispatch)
    }

    /** Invalidates active/posted completion identity and drops the one not-yet-submitted intent. */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            generation += 1
            inFlight = null
            pending = false
        }
    }

    internal fun snapshot(): LatestCaptureRestoreSnapshot = synchronized(lock) {
        LatestCaptureRestoreSnapshot(
            inFlightRequests = if (inFlight == null) 0 else 1,
            pendingRequests = if (pending) 1 else 0,
            closed = closed,
        )
    }

    private fun dispatch(request: Request) {
        // The executor queue / Binder stack owns only the application-safe query plus a WEAK path
        // back to this VM-local coordinator. Once the ViewModel is retired, a wedged provider call
        // cannot keep its state/callback graph alive merely to report a result no one may publish.
        val owner = WeakReference(this)
        val ownedQuery = query
        val ownedPostCompletion = postCompletion
        val accepted = runCatching {
            submit(
                Runnable {
                    val value = runCatching(ownedQuery).getOrNull()
                    val liveOwner = owner.get() ?: return@Runnable
                    val posted = runCatching {
                        ownedPostCompletion(Runnable { liveOwner.complete(request, value) })
                    }.getOrDefault(false)
                    if (!posted) liveOwner.retireWithoutPublication(request)
                },
            )
        }.getOrDefault(false)
        if (!accepted) retireWithoutPublication(request)
    }

    /** Runs on the serialized completion/close owner thread (main in production). */
    private fun complete(request: Request, value: T?) {
        val current = synchronized(lock) { !closed && inFlight == request }
        if (!current) return
        val satisfied = value?.let { runCatching { publish(it) }.getOrDefault(false) } == true
        finish(request, allowPending = !satisfied)?.let(::dispatch)
    }

    /** Submit/post refusal never runs provider or publication work inline. */
    private fun retireWithoutPublication(request: Request) {
        finish(request, allowPending = true)?.let(::dispatch)
    }

    private fun finish(request: Request, allowPending: Boolean): Request? = synchronized(lock) {
        if (closed || inFlight != request) return@synchronized null
        val runNext = allowPending && pending
        pending = false
        if (runNext) {
            Request(++generation).also { inFlight = it }
        } else {
            inFlight = null
            null
        }
    }
}

internal fun focusModeChangeClearsTapPoint(
    current: FocusMode,
    requested: FocusMode,
): Boolean = current != requested

/** Reconstructs an MR row from that bank's own phone declaration (measured only for OTHER). */
internal fun memoryPresetFocalMm(extras: ExtraSettings, hostTeleEquivMm: Float): Float =
    if (extras.teleconverter) {
        val declaration = teleconverterDeclaration(
            phone = extras.phoneModel,
            profile = extras.teleconverterProfile,
            customMagnification = extras.teleconverterCustomMagnification,
            measuredOtherHostEquivMm = hostTeleEquivMm,
        )
        effectiveFocalMm(
            declaration.magnification,
            declaration.hostTeleEquivMm,
        )
    } else {
        extras.lens.targetEquivMm
    }

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
