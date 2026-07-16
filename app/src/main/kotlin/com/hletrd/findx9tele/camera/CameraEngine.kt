package com.hletrd.findx9tele.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.util.Size
import android.view.Surface
import com.hletrd.findx9tele.capture.DngCapture
import com.hletrd.findx9tele.capture.HeifCapture
import com.hletrd.findx9tele.capture.StillSnapshot
import com.hletrd.findx9tele.gl.GlPipeline
import com.hletrd.findx9tele.storage.CaptureFamilyKey
import com.hletrd.findx9tele.storage.CaptureFamilyMedia
import com.hletrd.findx9tele.storage.MediaStoreWriter
import com.hletrd.findx9tele.video.VideoRecorder

/**
 * Facade tying Camera2 + GL(180° flip) + capture encoders + video recorder + MediaStore together.
 * Called by the ViewModel; internal work runs on the components' own threads. All image encoding
 * happens off the UI thread (inside camera/GL callbacks).
 */
class CameraEngine(private val context: Context) {

    private val manager = context.getSystemService(CameraManager::class.java)
    private val gl = GlPipeline()
    // Terminal owner for every operation that can acquire a fresh GL generation. release() closes
    // it before its one GL stop, so a queued cold start cannot resurrect resources afterward.
    private val terminalAcquisitionGate = TerminalAcquisitionGate()
    // Startup setup (camera-service IPC) and still-image encoding are kept off the main/camera/GL
    // threads via these single-thread executors.
    private val setupExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    // Recorder finalization can block for seconds joining codec/audio drains. It must never sit
    // behind a burst's full-resolution still encodes on ioExecutor (or vice versa).
    private val recorderExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    // Drives interval (timelapse) capture off the camera/UI threads.
    private val timelapseScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    @Volatile private var timelapseFuture: java.util.concurrent.ScheduledFuture<*>? = null
    private val timelapseRun = CaptureSequenceGeneration()
    @Volatile private var controller: CameraController? = null
    @Volatile private var recorder: VideoRecorder? = null
    private val recorderOwnershipLock = Any()
    @Volatile private var recorderFinalizationLatch: java.util.concurrent.CountDownLatch? = null
    // URI (+ capture-sequence id) of the clip currently being recorded, so stop can surface it
    // (thumbnail/review) tagged like any other capture.
    @Volatile private var activeRecordingUri: android.net.Uri? = null
    @Volatile private var activeRecordingCaptureId = 0

    // These are written from the UI thread and read on the GL thread (via the onInputReady
    // continuation), so they are @Volatile to give the JMM a visibility edge across the seam.
    @Volatile private var selection: TeleSelection? = null
    @Volatile private var caps: CameraCaps? = null
    @Volatile private var videoSize = Size(1920, 1080)
    // The user's explicit resolution pick (from the Video tab or a settings restore); null = never
    // chosen, auto-derive. Kept separate from videoSize so reopen paths can re-validate it against
    // each lens's caps instead of blindly re-deriving the largest size over the user's choice.
    @Volatile private var requestedVideoSize: Size? = null
    // What the camera preview stream (the GL SurfaceTexture) is actually sized to. In VIDEO mode it
    // must equal [videoSize] (the SurfaceTexture feeds the encoder, and the HAL fixes the stream size
    // at session config). In PHOTO mode it is the largest 4:3 stream instead, so the viewfinder shows
    // the SAME field the still capture gets — a 16:9 preview stream crops the sensor vertically and
    // silently mis-frames every photo.
    @Volatile private var previewStreamSize = Size(1920, 1080)
    @Volatile private var videoCodec: VideoCodec = VideoCodec.HEVC
    @Volatile private var bitrateLevel: BitrateLevel = BitrateLevel.ULTRA
    @Volatile private var videoFrameRate: VideoFrameRate = VideoFrameRate.DEFAULT
    @Volatile private var openGate = false
    @Volatile private var driveMode: DriveMode = DriveMode.SINGLE
    @Volatile private var intervalSec: Int = 5
    @Volatile private var controls = ManualControls()
    @Volatile private var transfer = ColorTransfer.HLG
    @Volatile private var lensChoice: LensChoice = LensChoice.MAIN
    @Volatile private var overrideId: String? = null
    @Volatile private var started = false
    // Set true synchronously on the calling thread once startup setup is dispatched, so a second
    // onPreviewSurfaceAvailable arriving before setup completes doesn't launch a duplicate start.
    @Volatile private var starting = false
    // True only while the GL generation exists but has not created its camera input Surface yet.
    // Optics intents during this window remain pending; the input callback converges on the latest.
    @Volatile private var glInputPending = false
    // True between pause() and resume(). openCamera() honors it so a camera open queued during
    // startup (the GL onInputReady continuation) doesn't fire while the app is backgrounded — e.g.
    // launched behind the keyguard, where onStop lands right as the session would configure.
    @Volatile private var paused = false
    // True only after the CURRENT controller has configured a working session. Session-key and lens
    // changes clear it before their asynchronous reopen is queued so REC cannot race the teardown.
    @Volatile private var cameraReady = false
    // Preview EGL health participates in the externally visible Ready contract. The accepted
    // Camera2 session remains owned across a preview-only failure so a bounded rebind can restore
    // admission without needlessly reopening the device or starving an active encoder.
    @Volatile private var previewReady = false
    @Volatile private var readyController: CameraController? = null
    @Volatile private var acceptedCameraSession: AcceptedCameraSession? = null
    private val cameraSessionGeneration = java.util.concurrent.atomic.AtomicLong(0)
    private val cameraReadyPublicationSequence = java.util.concurrent.atomic.AtomicLong(0)
    private val opticsIntentGeneration = java.util.concurrent.atomic.AtomicLong(0)
    private val opticsCommitGate = OpticsCommitGate(opticsIntentGeneration, this)
    // Camera-health signal for the UI (dim the shutter, show a persistent OSD tag while down):
    // fires on every cameraReady flip. A silent scheduleCameraRecovery exhaustion previously left a
    // black viewfinder behind a fully interactive-looking shutter with zero indication.
    var onCameraReadyChange: ((CameraReadyPublication) -> Unit)? = null
    /** Restores UI optics when a current-generation camera switch fails before closing the old one. */
    var onOpticsRollback: ((CaptureMode, LensChoice, Boolean, ManualControls, String?, generation: Long) -> Unit)? = null

    // AF engine state for the reticle color, mapped from the controller's raw CONTROL_AF_STATE.
    var onAfIndication: ((AfIndication) -> Unit)? = null

    /** Captures callback ordering while the caller still owns the engine monitor/state mutation. */
    private fun nextCameraReadyPublication(
        ready: Boolean,
        opticsGeneration: Long,
        sessionGeneration: Long,
        photoOutputs: PhotoSessionOutputs = PhotoSessionOutputs(),
    ): CameraReadyPublication = CameraReadyPublication(
        sequence = cameraReadyPublicationSequence.incrementAndGet(),
        ready = ready,
        opticsGeneration = opticsGeneration,
        sessionGeneration = sessionGeneration,
        photoOutputs = photoOutputs,
    )

    private fun invalidateCameraReady() {
        val publication = synchronized(this) {
            val sessionGeneration = cameraSessionGeneration.incrementAndGet()
            cameraReady = false
            readyController = null
            acceptedCameraSession = null
            nextCameraReadyPublication(
                ready = false,
                opticsGeneration = opticsIntentGeneration.get(),
                sessionGeneration = sessionGeneration,
            )
        }
        onCameraReadyChange?.invoke(publication)
    }

    private fun reconcileControlsWithCaps(cameraCaps: CameraCaps) {
        val range = cameraCaps.zoomRatioRange
        controls = normalizeControlsForRoute(
            requested = controls,
            capabilities = cameraCaps.controlCapabilities(),
            mode = if (videoMode) CaptureMode.VIDEO else CaptureMode.PHOTO,
            teleconverter = teleconverterMode,
            capsLower = range?.lower,
            capsUpper = range?.upper,
        )
        if (!videoMode && !teleconverterMode) lensChoice = LensChoice.forZoom(controls.zoomRatio)
        seedGlZoom()
    }

    private data class OpticsSnapshot(
        val videoMode: Boolean,
        val lens: LensChoice,
        val teleconverter: Boolean,
        val controls: ManualControls,
        val overrideId: String?,
        val selection: TeleSelection?,
        val caps: CameraCaps?,
        val videoSize: Size,
        val previewStreamSize: Size,
        val preTeleUnifiedZoom: Float,
        val ready: Boolean,
        val readyController: CameraController?,
        val sessionGeneration: Long,
        val photoSessionOutputs: PhotoSessionOutputs,
    )

    private data class AcceptedCameraSession(
        val controller: CameraController,
        val sessionGeneration: Long,
        val outputs: PhotoSessionOutputs,
    )

    private data class OwnedRecording(
        val recorder: VideoRecorder,
        val uri: android.net.Uri?,
        val captureId: Int,
    )

    private data class OpticsTransaction(val generation: Long, val before: OpticsSnapshot)
    private data class OpticsReconfiguration(val overrideId: String?, val transaction: OpticsTransaction)

    @Volatile private var opticsRollbackBaseline: OpticsSnapshot? = null

    private fun currentOpticsSnapshot(): OpticsSnapshot = OpticsSnapshot(
        videoMode = videoMode,
        lens = lensChoice,
        teleconverter = teleconverterMode,
        controls = controls,
        overrideId = overrideId,
        selection = selection,
        caps = caps,
        videoSize = videoSize,
        previewStreamSize = previewStreamSize,
        preTeleUnifiedZoom = preTeleUnifiedZoom,
        ready = cameraReady,
        readyController = readyController,
        sessionGeneration = cameraSessionGeneration.get(),
        photoSessionOutputs = acceptedCameraSession?.outputs ?: PhotoSessionOutputs(),
    )

    private fun <T> beginOpticsTransaction(publishDesiredOptics: () -> T): Pair<OpticsTransaction, T> {
        val (result, publication) = opticsCommitGate.begin { generation ->
            // A second tap can arrive while the first switch is still preflighting. Both must roll
            // back to the last Ready state, never to the first tap's unaccepted intermediate fields.
            val before = selectRollbackBaseline(cameraReady, currentOpticsSnapshot(), opticsRollbackBaseline)
            opticsRollbackBaseline = before
            val transaction = OpticsTransaction(generation, before)
            // Generation and its complete desired packet share the commit gate's monitor. Resume
            // cannot capture the new token between increment and desired-state publication.
            val desired = publishDesiredOptics()
            // Desired optics and Not-Ready are one publication. Capture/REC cannot observe g+1's
            // fields while the outgoing controller still presents itself as Ready.
            cameraReady = false
            readyController = null
            acceptedCameraSession = null
            (transaction to desired) to nextCameraReadyPublication(
                ready = false,
                opticsGeneration = generation,
                sessionGeneration = cameraSessionGeneration.get(),
            )
        }
        onCameraReadyChange?.invoke(publication)
        return result
    }

    /**
     * Accepts an optics result only if its generation and controller still own the engine at the
     * exact terminal mutation boundary. The callback payload is captured in that same commit; a
     * later intent therefore receives the old generation and can reject it instead of having an
     * obsolete controller mislabeled with the new global generation.
     */
    private fun commitOpticsReady(
        expectedGeneration: Long,
        expectedController: CameraController,
        photoOutputs: PhotoSessionOutputs,
        expectedSessionGeneration: Long,
        terminalMutation: () -> Unit = {},
        beforeReadyPublication: ((generation: Long) -> Unit)? = null,
    ): Boolean {
        // terminalMutation must only update engine fields or enqueue non-blocking GL/controller
        // handler work. Never perform Binder IPC, close resources, or invoke external callbacks
        // while the optics monitor is held.
        var publication: CameraReadyPublication? = null
        val publicationGeneration = opticsCommitGate.commit(
            expectedGeneration = expectedGeneration,
            ownsTerminal = {
                controller === expectedController && !paused &&
                    cameraSessionGeneration.get() == expectedSessionGeneration
            },
        ) {
            terminalMutation()
            opticsRollbackBaseline = null
            val sessionGeneration = cameraSessionGeneration.get()
            acceptedCameraSession = AcceptedCameraSession(
                controller = expectedController,
                sessionGeneration = sessionGeneration,
                outputs = photoOutputs,
            )
            val effectiveReady = previewReady
            cameraReady = effectiveReady
            readyController = expectedController.takeIf { effectiveReady }
            publication = nextCameraReadyPublication(
                ready = effectiveReady,
                opticsGeneration = expectedGeneration,
                sessionGeneration = sessionGeneration,
                photoOutputs = photoOutputs,
            )
            cameraRecoveryAttempts = 0
        } ?: return false
        coldStartRetryGate.success(publicationGeneration)
        // Reconciled caps/controls must enter the caller's main queue before Ready. Callback failure
        // is sealed so UI plumbing cannot strand an otherwise accepted Camera2 session Not-Ready.
        runCatching { beforeReadyPublication?.invoke(publicationGeneration) }
        onCameraReadyChange?.invoke(checkNotNull(publication))
        return true
    }

    /**
     * Same-route intents optimistically reuse the accepted session. A superseded dual-open attempt
     * can restore that controller with a newer session token, so a rejected terminal commit must
     * converge through the ordinary reconfigure path while this intent still owns the engine.
     */
    private fun commitFastPathOrReconfigure(
        transaction: OpticsTransaction,
        cameraId: String,
        expectedController: CameraController,
        beforeReadyPublication: ((generation: Long) -> Unit)? = null,
        terminalMutation: () -> Unit,
    ) {
        convergeFastPathCommit(
            commit = {
                commitOpticsReady(
                    transaction.generation,
                    expectedController,
                    transaction.before.photoSessionOutputs,
                    expectedSessionGeneration = transaction.before.sessionGeneration,
                    terminalMutation = terminalMutation,
                    beforeReadyPublication = beforeReadyPublication,
                )
            },
            ownsTransaction = { ownsOpticsTransaction(transaction) },
            canReconfigure = { !paused && recorder == null },
            reconfigure = { reconfigureCamera(cameraId, transaction) },
        )
    }

    private fun ownsOpticsTransaction(transaction: OpticsTransaction): Boolean =
        reconfigurationOwnsGeneration(opticsIntentGeneration.get(), transaction.generation)

    /** Desired route + ownership token captured atomically for resume. */
    @Synchronized
    private fun currentOpticsReconfiguration(): OpticsReconfiguration = OpticsReconfiguration(
        overrideId = overrideId,
        transaction = OpticsTransaction(
            generation = opticsIntentGeneration.get(),
            before = selectRollbackBaseline(cameraReady, currentOpticsSnapshot(), opticsRollbackBaseline),
        ),
    )

    @Synchronized
    private fun rollbackOptics(transaction: OpticsTransaction, message: String) {
        val before = transaction.before
        val restored = rollbackOpticsState(
            currentGeneration = opticsIntentGeneration.get(),
            expectedGeneration = transaction.generation,
            snapshot = OpticsIntentState(
                mode = if (before.videoMode) CaptureMode.VIDEO else CaptureMode.PHOTO,
                lens = before.lens,
                teleconverter = before.teleconverter,
                controls = before.controls,
                overrideId = before.overrideId,
            ),
        ) ?: return
        opticsRollbackBaseline = null
        videoMode = restored.mode == CaptureMode.VIDEO
        lensChoice = restored.lens
        teleconverterMode = restored.teleconverter
        controls = restored.controls
        overrideId = restored.overrideId
        selection = before.selection
        caps = before.caps
        videoSize = before.videoSize
        previewStreamSize = before.previewStreamSize
        preTeleUnifiedZoom = before.preTeleUnifiedZoom
        controller?.setPinAutoFps(before.videoMode)
        controller?.updateControls(before.controls)
        // Queue the exact UI optics first. Caps reconciliation follows it on main and is tagged with
        // this generation, so it cannot clamp the failed candidate or a newer user intent.
        onOpticsRollback?.invoke(
            restored.mode,
            restored.lens,
            restored.teleconverter,
            restored.controls,
            restored.overrideId,
            transaction.generation,
        )
        before.caps?.let { onCapsReady?.invoke(it, transaction.generation) }
        onVideoSizeChosen?.invoke(before.videoSize, transaction.generation)
        emitPreviewAspect(transaction.generation)
        gl.setCameraPreviewSize(before.previewStreamSize.width, before.previewStreamSize.height)
        seedGlZoom()
        applyStabilization()
        val restoreSession = before.ready && before.readyController === controller && !paused &&
            before.sessionGeneration == cameraSessionGeneration.get()
        val readyPublication = if (restoreSession) {
            val restoredController = checkNotNull(controller)
            acceptedCameraSession = AcceptedCameraSession(
                controller = restoredController,
                sessionGeneration = before.sessionGeneration,
                outputs = before.photoSessionOutputs,
            )
            val effectiveReady = previewReady
            cameraReady = effectiveReady
            readyController = restoredController.takeIf { effectiveReady }
            nextCameraReadyPublication(
                ready = effectiveReady,
                opticsGeneration = transaction.generation,
                sessionGeneration = before.sessionGeneration,
                photoOutputs = before.photoSessionOutputs,
            )
        } else {
            val sessionGeneration = cameraSessionGeneration.incrementAndGet()
            cameraReady = false
            readyController = null
            acceptedCameraSession = null
            nextCameraReadyPublication(
                ready = false,
                opticsGeneration = transaction.generation,
                sessionGeneration = sessionGeneration,
            )
        }
        onCameraReadyChange?.invoke(readyPublication)
        onStatus?.invoke(message)
    }
    @Volatile private var previewSurface: Surface? = null
    // Last-known preview surface dimensions, kept alongside previewSurface so the async start
    // continuation can bind the LIVE surface (not its captured, possibly-released parameter).
    @Volatile private var previewSurfaceW = 0
    @Volatile private var previewSurfaceH = 0
    private val previewSurfaceGeneration = java.util.concurrent.atomic.AtomicLong(0)
    private var previewRecoveryAttempts = 0

    // Static-per-device caches: camera characteristics and focal→id resolution never change at
    // runtime, but re-reading them cost dozens of Binder IPCs on EVERY lens/TC/mode switch — and
    // they used to run inside the close→open blackout window.
    private val capsCache = java.util.concurrent.ConcurrentHashMap<String, CameraCaps>()
    private val lensExifCache = java.util.concurrent.ConcurrentHashMap<String, LensExifMetadata>()
    private val idForFocalCache = java.util.concurrent.ConcurrentHashMap<Float, String>()
    @Volatile private var cachedLogicalBackId: String? = null

    private fun cachedCaps(logicalId: String, physicalId: String?): CameraCaps? =
        runCatching {
            capsCache.getOrPut("$logicalId:$physicalId") { CameraCaps.read(manager, logicalId, physicalId) }
        }.getOrNull()?.also { cameraCaps ->
            lensExifCache.putIfAbsent(physicalId ?: logicalId, cameraCaps.lensExifMetadata())
        }

    /** Populates logical-member optics on setupExecutor before Camera2 can deliver a still. */
    private fun prefetchLensExifMetadata(selection: TeleSelection, selectedCaps: CameraCaps) {
        val selectedId = selection.physicalId ?: selection.logicalId
        lensExifCache.putIfAbsent(selectedId, selectedCaps.lensExifMetadata())
        val memberIds = runCatching {
            manager.getCameraCharacteristics(selection.logicalId).physicalCameraIds
        }.getOrDefault(emptySet())
        for (cameraId in memberIds) {
            if (!lensExifCache.containsKey(cameraId)) {
                readLensExifMetadata(manager, cameraId)?.let {
                    lensExifCache.putIfAbsent(cameraId, it)
                }
            }
        }
    }

    private fun cachedIdForFocal(equivMm: Float): String? =
        idForFocalCache[equivMm]
            ?: CameraSelector2.overrideIdForFocal(manager, equivMm)?.also { idForFocalCache[equivMm] = it }

    private fun cachedLogicalBack(): String? =
        cachedLogicalBackId ?: CameraSelector2.logicalBackId(manager)?.also { cachedLogicalBackId = it }

    private val gyro = com.hletrd.findx9tele.stab.GyroEis(context)
    @Volatile private var teleconverterMode = false
    @Volatile private var videoMode = false
    private val modeIntentGeneration = java.util.concurrent.atomic.AtomicLong(0)
    private val lensIntentGeneration = java.util.concurrent.atomic.AtomicLong(0)
    // HAL-native log (vendor com.oplus.log.video.mode). Session key → changing it reopens the camera.
    @Volatile private var vendorLogMode = VendorLogMode.OFF
    // Video stabilization strategy. Default ENHANCED = HAL OIS+EIS (motion-blur reduction at 300 mm).
    @Volatile private var videoStabMode = VideoStabMode.ENHANCED
    // Bounded auto-recovery when the camera HAL disconnects/errors mid-session (its provider process can
    // crash). Reset on a successful open so the viewfinder self-heals instead of sitting black.
    @Volatile private var cameraRecoveryAttempts = 0
    private val coldStartRetryGate = ColdStartRetryGate(MAX_CAMERA_RECOVERY_ATTEMPTS)

    // Software recording-audio gain (1f = passthrough) and the still-photo aspect-ratio crop;
    // read from the audio-encode / io-executor threads, so both are @Volatile.
    @Volatile private var audioGain = 1f
    @Volatile private var audioScene = AudioScene.STANDARD
    @Volatile private var audioInputPreference = AudioInputPreference.AUTO
    @Volatile private var aspectRatio = AspectRatio.W4_3
    // Desired GL-only assists outlive the handler/EGL generation. Settings restore calls these
    // setters before gl.start(), where handler posts are intentionally unavailable, so every new
    // generation replays this complete snapshot before the first camera frame.
    private val rendererConfig = RendererConfigStore()

    var onStatus: ((String?) -> Unit)? = null
    var onCapsReady: ((CameraCaps, generation: Long) -> Unit)? = null
    // The auto-chosen video size for the selected lens (largest 16:9), so the UI's Video tab reflects
    // what the engine will actually encode instead of drifting from a hardcoded default.
    var onVideoSizeChosen: ((Size, generation: Long?) -> Unit)? = null
    // Displayed preview aspect (width/height AS SHOWN on the portrait screen — the ~90° sensor
    // orientation already swaps the stream's W/H). The UI sizes the TextureView to this so the
    // viewfinder letterboxes the FULL capture field instead of cover-cropping it: with a 16:9 stream
    // on this ~19.5:9 panel the old full-screen cover cut ~40% of the frame's width, and photo mode
    // additionally previewed a 16:9 field while capturing the full 4:3 sensor.
    var onPreviewAspect: ((Float, generation: Long) -> Unit)? = null
    // Viewfinder analysis (histogram/waveform) computed on the GL thread; delivered here so the
    // ViewModel can hoist it into UI state. Either arg is null when its analysis is disabled.
    var onAnalysis: ((HistogramData?, WaveformData?) -> Unit)? = null
    // Live recording-audio level (0..1 RMS, post-gain), throttled by VideoRecorder to ~10 Hz.
    var onAudioLevel: ((Float) -> Unit)? = null
    // Actual AudioRecord route once recording starts, e.g. "USB · DJI Mic Mini".
    var onAudioRoute: ((String) -> Unit)? = null
    /** Unexpected async codec/muxer failure; the recorder has already entered ordered teardown. */
    var onRecordingTerminated: ((Throwable) -> Unit)? = null
    /** Encoder input is attached to EGL and the recorder is now genuinely rolling. */
    var onRecordingStarted: (() -> Unit)? = null
    // AE-resolved ISO/shutter (auto mode) from the controller, for the live dial readout. Fired from
    // the camera thread, only on change; the ViewModel hoists it into UI state.
    var onExposureInfo: ((iso: Int?, exposureNs: Long?) -> Unit)? = null
    // Live lens focus distance (diopters) from the controller: the AF-resolved position shown on the
    // Focus chip and used to seed the manual slider when the user switches into MF (AF→MF handoff).
    var onFocusDistance: ((Float) -> Unit)? = null
    // The most recently saved displayable media (HEIF/JPEG/video) URI + its capture-sequence id, for
    // the gallery thumbnail + in-app review. Fired from the io thread after the file publishes. The
    // id groups every output of one shutter press so review-Delete can remove the whole shot.
    var onMediaSaved: ((android.net.Uri, Int) -> Unit)? = null
    // The DNG sibling of a capture (never displayed itself), with the same capture-sequence id as
    // the HEIF/JPEG from that shutter press. Fired from the camera thread after the DNG publishes.
    var onRawSaved: ((android.net.Uri, Int) -> Unit)? = null

    // ---- Preview surface lifecycle ----

    fun onPreviewSurfaceAvailable(surface: Surface, width: Int, height: Int) {
        val surfaceGeneration = previewSurfaceGeneration.incrementAndGet()
        previewSurface = surface // published synchronously on the calling (main) thread
        previewSurfaceW = width
        previewSurfaceH = height
        markPreviewPending(surface, surfaceGeneration)
        if (started) {
            bindPreviewSurface(surface, width, height, surfaceGeneration)
            return
        }
        val dispatchStart = synchronized(this) {
            if (started || starting || paused) {
                false
            } else {
                starting = true
                true
            }
        }
        if (!dispatchStart) return
        // Cold-start feedback: GL creation + Camera2 preflight/open happen before the first frame.
        onStatus?.invoke("Starting camera…")

        // Start GL before resolving a camera route. Any mode/lens/MR intent arriving before the GL
        // input Surface exists is retained, and the input callback snapshots that latest complete
        // desired transaction. This removes the old preflight route that could be opened under a
        // newer generation.
        setupExecutor.execute {
            val acquired = runCatching { terminalAcquisitionGate.runIfOpen {
                if (paused) {
                    synchronized(this) { starting = false }
                    return@runIfOpen
                }
                // HLG10 10-bit preview + full-res JPEG/RAW crashes this HAL (configureStreams Broken
                // pipe -32); use an SDR preview session. Video still tags HLG/Log in the encoder.
                val tenBit = false
                glInputPending = true
                gl.start(tenBit) { _ ->
                    terminalAcquisitionGate.runIfOpen inputReady@{
                        glInputPending = false
                        if (paused) return@inputReady
                        gl.setEisProvider { gyro.currentCorrection() }
                        gl.setAnalysisCallback { h, w -> onAnalysis?.invoke(h, w) }
                        // Re-seed desired GL state that may have been set before the handler existed.
                        gl.setNativeLog(false)
                        gl.setTransfer(transfer)
                        gl.setAeMetering(aeMetering)
                        gl.setGammaAssist(gammaAssist)
                        applyRendererConfig()
                        gyro.start()
                        // Capture route + token after GL input exists. A newer intent invalidates it.
                        val desired = currentOpticsReconfiguration()
                        reconfigureCamera(desired.overrideId, desired.transaction, startup = true)
                        maybeLogCameraCapabilities()
                    }
                }
                synchronized(this) {
                    // Publish start state BEFORE binding: a TextureView surface destroyed+recreated
                    // during GL startup then takes the started fast path and binds the fresh surface.
                    started = true
                    starting = false
                }
                // Bind the LIVE surface field, not the captured parameter: a destroyed-and-not-yet-
                // recreated surface simply skips this bind and the next callback supplies the owner.
                val liveSurface = previewSurface
                if (liveSurface != null) {
                    bindPreviewSurface(
                        surface = liveSurface,
                        width = previewSurfaceW,
                        height = previewSurfaceH,
                        surfaceGeneration = previewSurfaceGeneration.get(),
                    )
                }
            } }.getOrDefault(false)
            if (!acquired) synchronized(this) { starting = false }
        }
    }

    /** Debug-only Camera2 capability log, queued behind initial camera route/open work. */
    private fun maybeLogCameraCapabilities() {
        if (!com.hletrd.findx9tele.BuildConfig.DEBUG) return
        setupExecutor.execute { runCatching { VendorTagInspector.logAll(manager) } }
    }

    /** Apply afocal preview rotation and the selected HAL stabilization mode; keep GL EIS disabled. */
    private fun applyStabilization() {
        val c = caps ?: return
        // The camera SurfaceTexture transform already rotates the sampled image by the sensor
        // orientation, so the renderer only needs the afocal flip on top (see previewRotationDegrees).
        // It still needs the sensor orientation separately to pick the right preview ASPECT, because
        // that ~90° SurfaceTexture rotation swaps the displayed width/height.
        gl.setSensorOrientation(c.sensorOrientation)
        gl.setRotationDegrees(previewRotationDegrees())
        // App-side gyro EIS is disabled (unusable at 300 mm): the HAL video-stab modes own
        // stabilization, so GL EIS cannot double-warp. Push the resolved HAL mode to the live request.
        gl.setEis(false, 0f, 0f)
        controller?.setVideoStabMode(c.videoStabControlMode(videoStabMode))
    }

    /**
     * CW degrees the GL renderer adds ON TOP of the camera SurfaceTexture transform to show the
     * preview upright. That transform already applies the sensor orientation to the sampled image, so
     * the ONLY correction left is the afocal teleconverter's 180° inversion — no sensor term here
     * (unlike [captureRotationDegrees], which rotates the raw-sensor JPEG/RAW and therefore keeps it).
     * Empirically calibrated on this device: +sensorOrientation (270°) read 90° CCW and
     * −sensorOrientation (90°) read 90° CW, so the upright value is the afocal 180° alone. Portrait-
     * locked UI ⇒ no device-orientation term (tilting the phone tilts the world in the preview).
     */
    private fun previewRotationDegrees(): Int = RotationMath.previewRotationDegrees(teleconverterMode)

    fun setTeleconverterMode(enabled: Boolean) {
        if (teleconverterMode == enabled) return
        // TELE is a CAMERA SWITCH, not just the afocal flip: ON pins the standalone 3× (the
        // converter's host lens — digital-only zoom, session-key Hasselblad/zoom hints); OFF returns
        // to the logical seamless-zoom camera at the 3× preset so the framing carries over.
        setLens(LensChoice.TELE3X, enabled)
    }

    fun setVideoStabMode(m: VideoStabMode) {
        if (videoStabMode == m) return
        videoStabMode = m
        applyStabilization()
        // CONTROL_VIDEO_STABILIZATION_MODE is advertised as a session key on the Find X9 Ultra tele.
        // Request-only updates work, but the HAL can select a different OIS/EIS pipeline at configure
        // time, so recreate the session when the user changes the stabilization class.
        reopenForSession()
    }
    fun setFalseColor(enabled: Boolean) {
        rendererConfig.update { it.copy(falseColor = enabled) }
        gl.setFalseColor(enabled)
    }

    fun onPreviewSurfaceChanged(width: Int, height: Int) {
        previewSurfaceW = width
        previewSurfaceH = height
        val surface = previewSurface ?: return
        val surfaceGeneration = previewSurfaceGeneration.incrementAndGet()
        markPreviewPending(surface, surfaceGeneration)
        bindPreviewSurface(surface, width, height, surfaceGeneration)
    }

    fun onPreviewSurfaceDestroyed() {
        // Portrait is locked, so this is app teardown; keep the GL context but drop the output.
        val surfaceGeneration = previewSurfaceGeneration.incrementAndGet()
        val outgoing = previewSurface
        previewSurface = null
        if (outgoing != null) markPreviewPending(outgoing, surfaceGeneration, requireCurrentSurface = false)
        gl.setPreviewOutput(null, 0, 0)
    }

    private fun bindPreviewSurface(
        surface: Surface,
        width: Int,
        height: Int,
        surfaceGeneration: Long,
    ) {
        gl.setPreviewOutput(
            surface = surface,
            width = width,
            height = height,
            onReady = { handlePreviewReady(surface, surfaceGeneration) },
            onFailure = { failure -> handlePreviewFailure(surface, surfaceGeneration, failure) },
        )
    }

    /** Makes the UI/shutter truthful while a replacement TextureView output is not yet bound. */
    private fun markPreviewPending(
        surface: Surface,
        surfaceGeneration: Long,
        requireCurrentSurface: Boolean = true,
    ) {
        val publication = synchronized(this) {
            if (surfaceGeneration != previewSurfaceGeneration.get()) return@synchronized null
            if (requireCurrentSurface && previewSurface !== surface) return@synchronized null
            previewReady = false
            previewRecoveryAttempts = 0
            if (!cameraReady) return@synchronized null
            cameraReady = false
            readyController = null
            val accepted = acceptedCameraSession
            nextCameraReadyPublication(
                ready = false,
                opticsGeneration = opticsIntentGeneration.get(),
                sessionGeneration = accepted?.sessionGeneration ?: cameraSessionGeneration.get(),
                photoOutputs = accepted?.outputs ?: PhotoSessionOutputs(),
            )
        }
        publication?.let { onCameraReadyChange?.invoke(it) }
    }

    private fun handlePreviewReady(surface: Surface, surfaceGeneration: Long) {
        val publication = synchronized(this) {
            if (surfaceGeneration != previewSurfaceGeneration.get() || previewSurface !== surface) {
                return@synchronized null
            }
            val wasReady = previewReady && cameraReady
            previewReady = true
            previewRecoveryAttempts = 0
            val accepted = acceptedCameraSession?.takeIf {
                !paused && controller === it.controller &&
                    cameraSessionGeneration.get() == it.sessionGeneration
            } ?: return@synchronized null
            cameraReady = true
            readyController = accepted.controller
            if (wasReady) return@synchronized null
            nextCameraReadyPublication(
                ready = true,
                opticsGeneration = opticsIntentGeneration.get(),
                sessionGeneration = accepted.sessionGeneration,
                photoOutputs = accepted.outputs,
            )
        }
        publication?.let { onCameraReadyChange?.invoke(it) }
    }

    private fun handlePreviewFailure(
        surface: Surface,
        surfaceGeneration: Long,
        failure: Throwable,
    ) {
        val outcome = synchronized(this) {
            val ownerCurrent = surfaceGeneration == previewSurfaceGeneration.get() && previewSurface === surface
            val decision = previewRecoveryDecision(
                ownerCurrent = ownerCurrent,
                started = started,
                paused = paused,
                nextAttempt = previewRecoveryAttempts + 1,
                maxAttempts = MAX_PREVIEW_RECOVERY_ATTEMPTS,
            )
            if (decision == PreviewRecoveryDecision.IGNORE) return@synchronized null
            previewReady = false
            val wasCameraReady = cameraReady
            cameraReady = false
            readyController = null
            previewRecoveryAttempts++
            val accepted = acceptedCameraSession
            val publication = if (wasCameraReady) {
                nextCameraReadyPublication(
                    ready = false,
                    opticsGeneration = opticsIntentGeneration.get(),
                    sessionGeneration = accepted?.sessionGeneration ?: cameraSessionGeneration.get(),
                    photoOutputs = accepted?.outputs ?: PhotoSessionOutputs(),
                )
            } else {
                null
            }
            decision to publication
        } ?: return

        outcome.second?.let { onCameraReadyChange?.invoke(it) }
        when (outcome.first) {
            PreviewRecoveryDecision.IGNORE -> Unit
            PreviewRecoveryDecision.EXHAUSTED ->
                onStatus?.invoke("Preview failed — reopen the app")
            PreviewRecoveryDecision.RETRY -> {
                onStatus?.invoke("Preview interrupted — recovering")
                runCatching {
                    timelapseScheduler.schedule(
                        {
                            if (surfaceGeneration == previewSurfaceGeneration.get() &&
                                previewSurface === surface && started && !paused
                            ) {
                                bindPreviewSurface(surface, previewSurfaceW, previewSurfaceH, surfaceGeneration)
                            }
                        },
                        PREVIEW_RECOVERY_DELAY_MS,
                        java.util.concurrent.TimeUnit.MILLISECONDS,
                    )
                }.onFailure {
                    onStatus?.invoke("Preview failed — reopen the app")
                }
            }
        }
        if (com.hletrd.findx9tele.BuildConfig.DEBUG) {
            android.util.Log.w("CameraEngine", "Preview EGL failure", failure)
        }
    }

    private fun wireController(): CameraController {
        val ctrl = CameraController(context)
        ctrl.onExposure = { iso, exp -> onExposureInfo?.invoke(iso, exp) }
        ctrl.onZoomResult = { rz -> gl.setHalZoom(rz) }
        ctrl.onFocusDistance = { d -> onFocusDistance?.invoke(d) }
        ctrl.onAfState = { hal -> onAfIndication?.invoke(AfIndication.fromHal(hal)) }
        return ctrl
    }

    private fun openCamera(input: Surface, transaction: OpticsTransaction? = null) {
        if (paused) return // don't grab the camera while backgrounded (queued GL continuation, etc.)
        val expectedOpticsGeneration = transaction?.generation ?: opticsIntentGeneration.get()
        if (!reconfigurationOwnsGeneration(opticsIntentGeneration.get(), expectedOpticsGeneration)) return
        val sel = selection ?: return
        val c = caps ?: return
        seedGlZoom()
        invalidateCameraReady()
        controller?.close() // idempotent: closes any prior controller so two never race for the device
        val ctrl = wireController()
        val installedPublication = synchronized(this) {
            if (paused || !reconfigurationOwnsGeneration(
                    opticsIntentGeneration.get(),
                    expectedOpticsGeneration,
                )
            ) {
                null
            } else {
                // Controller replacement shares the terminal-commit monitor, so an obsolete
                // callback cannot pass identity and publish Ready across this assignment.
                controller = ctrl
                cameraReady = false
                readyController = null
                acceptedCameraSession = null
                val sessionGeneration = cameraSessionGeneration.incrementAndGet()
                nextCameraReadyPublication(
                    ready = false,
                    opticsGeneration = expectedOpticsGeneration,
                    sessionGeneration = sessionGeneration,
                )
            }
        }
        if (installedPublication == null) {
            ctrl.close()
            return
        }
        // Re-assert the atomic install state in case the outgoing controller reported Ready after
        // the earlier invalidation but before replacement acquired the monitor.
        onCameraReadyChange?.invoke(installedPublication)
        ctrl.open(
            selection = sel,
            caps = c,
            glInputSurface = input,
            controls = controls,
            tenBitHlg = false, // SDR session — HLG10+RAW+JPEG crashes the HAL
            // The shipping picker always resolves 0: constrained high-speed SIGABRTs this HAL.
            // Non-zero support remains dormant for diagnostics/schema-compatible internal callers.
            highSpeedFps = desiredHighSpeedFps(),
            vendorLogMode = vendorLogMode.halValue,
            videoStabHalMode = c.videoStabControlMode(videoStabMode),
            teleconverterMode = teleconverterMode,
            pinAutoFps = videoMode,
            onReady = { outputs ->
                // Generation, controller identity, rollback acceptance, and Ready publication are
                // one commit. A superseding intent cannot land between the check and publication.
                // Status messages auto-expire in the ViewModel; an untagged old success must not
                // clear a newer transaction's message after the owned commit.
                commitOpticsReady(
                    expectedGeneration = expectedOpticsGeneration,
                    expectedController = ctrl,
                    photoOutputs = outputs,
                    expectedSessionGeneration = installedPublication.sessionGeneration,
                )
            },
            onError = { failure ->
                handleActiveCameraFailure(ctrl, failure)
            },
        )
    }

    // ---- Controls ----

    fun setControls(c: ManualControls) {
        val normalized = caps?.let(c::normalizedFor) ?: c
        controls = normalized
        controller?.updateControls(normalized)
    }

    fun isOpticsGenerationCurrent(generation: Long): Boolean =
        reconfigurationOwnsGeneration(opticsIntentGeneration.get(), generation)

    /** Rechecks a queued UI Ready publication after it crosses onto the main thread. */
    fun isCameraReadyPublicationCurrent(publication: CameraReadyPublication): Boolean =
        cameraReadyPublicationIsCurrent(
            currentOpticsGeneration = opticsIntentGeneration.get(),
            expectedOpticsGeneration = publication.opticsGeneration,
            currentSessionGeneration = cameraSessionGeneration.get(),
            expectedSessionGeneration = publication.sessionGeneration,
            cameraReady = cameraReady,
        )

    fun setVideoMode(enabled: Boolean, resolvedLens: LensChoice, resolvedControls: ManualControls) {
        // Publish one already-resolved optics packet before queuing any reconfiguration. Keeping the
        // UI and engine remaps separate let a delayed full-controls apply race this method and made
        // Photo/Video transitions depend on executor timing.
        val changed = videoMode != enabled
        val transaction = if (changed) {
            beginOpticsTransaction {
                videoMode = enabled
                lensChoice = resolvedLens
                controls = resolvedControls
                // A mode intent owns automatic routing. Clear the resolved outgoing id atomically.
                overrideId = null
            }.first
        } else {
            synchronized(this) {
                videoMode = enabled
                lensChoice = resolvedLens
                controls = resolvedControls
            }
            null
        }
        controller?.updateControls(resolvedControls)
        if (!changed) return
        val intentGeneration = modeIntentGeneration.incrementAndGet()
        controller?.setPinAutoFps(enabled)
        if (!started) return
        // beginOpticsTransaction published Not-Ready atomically with the desired packet, so REC
        // cannot start against the outgoing session while this reopen is merely queued.
        // The mode flip can change the CAMERA (photo=logical seamless, video=standalone — see
        // resolveNonTeleId) as well as the stream size. The caller has already remapped zoom and
        // lens together; this task only applies that immutable intent.
        setupExecutor.execute {
            if (transaction == null || !ownsOpticsTransaction(transaction) ||
                videoMode != enabled || modeIntentGeneration.get() != intentGeneration
            ) return@execute
            if (paused) return@execute
            if (recorder != null) {
                rollbackOptics(transaction, "Stop REC first; mode unchanged")
                return@execute
            }
            val id = if (teleconverterMode) {
                cachedIdForFocal(LensChoice.TELE3X.targetEquivMm)
            } else {
                resolveNonTeleId(lensChoice)
            }
            if (id == null) {
                rollbackOptics(transaction, "Camera unavailable; mode unchanged")
                return@execute
            }
            val beforeCameraId = transaction.before.selection?.let { it.physicalId ?: it.logicalId }
            if (id != beforeCameraId || controller == null) {
                reconfigureCamera(id, transaction) // refreshes caps, stream sizes, aspect, stabilization, reopens
            } else {
                // Same camera (e.g. TELE mode) — still recreate when the STREAM SIZE flips between
                // the photo 4:3 field and the recording stream (dimensions fix at configureStreams).
                val sel = selection ?: return@execute
                val next = choosePreviewStreamSize(sel)
                if (next != previewStreamSize) {
                    previewStreamSize = next
                    emitPreviewAspect(transaction.generation)
                    gl.setCameraPreviewSize(next.width, next.height)
                    reopenForSession(transaction)
                } else if (controller != null && transaction.before.ready &&
                    transaction.before.readyController === controller && !paused
                ) {
                    // Same camera and same configured stream: the intent only changed request-side
                    // mode semantics, so the existing session is still the ready session.
                    val expectedController = controller ?: return@execute
                    commitFastPathOrReconfigure(transaction, id, expectedController) {
                        overrideId = id
                    }
                } else {
                    reconfigureCamera(id, transaction)
                }
            }
        }
    }

    /** Applies settings/MR optics as one transaction so mode and lens cannot supersede each other. */
    fun setResolvedOptics(
        enabledVideo: Boolean,
        resolvedLens: LensChoice,
        resolvedTeleconverter: Boolean,
        resolvedControls: ManualControls,
    ) {
        if (recorder != null) { onStatus?.invoke("Stop REC first"); return }
        val transaction = beginOpticsTransaction {
            videoMode = enabledVideo
            lensChoice = resolvedLens
            teleconverterMode = resolvedTeleconverter
            controls = resolvedControls
            preTeleUnifiedZoom = Float.NaN
            // Settings/MR replaces automatic routing; the snapshot retains an override for rollback.
            overrideId = null
        }.first
        val modeGeneration = modeIntentGeneration.incrementAndGet()
        val lensGeneration = lensIntentGeneration.incrementAndGet()
        controller?.setPinAutoFps(enabledVideo)
        if (!started) return
        setupExecutor.execute {
            if (!ownsOpticsTransaction(transaction) ||
                modeIntentGeneration.get() != modeGeneration ||
                lensIntentGeneration.get() != lensGeneration
            ) return@execute
            if (paused || recorder != null) return@execute
            val id = if (resolvedTeleconverter) {
                cachedIdForFocal(LensChoice.TELE3X.targetEquivMm)
            } else {
                resolveNonTeleId(resolvedLens)
            }
            if (id == null) {
                rollbackOptics(transaction, "Camera unavailable; recalled optics unchanged")
                return@execute
            }
            val before = transaction.before
            val beforeCameraId = before.selection?.let { it.physicalId ?: it.logicalId }
            val structuralChange = resolvedOpticsRequiresReconfigure(
                beforeVideo = before.videoMode,
                targetVideo = enabledVideo,
                beforeTeleconverter = before.teleconverter,
                targetTeleconverter = resolvedTeleconverter,
                beforeCameraId = beforeCameraId,
                targetCameraId = id,
                controllerAvailable = controller != null,
                beforeReady = before.ready,
                readyControllerMatches = before.readyController === controller,
            )
            if (structuralChange) {
                reconfigureCamera(id, transaction)
            } else {
                val expectedController = controller ?: return@execute
                val routeCaps = caps ?: run {
                    reconfigureCamera(id, transaction)
                    return@execute
                }
                val range = routeCaps.zoomRatioRange
                val normalizedControls = normalizeControlsForRoute(
                    requested = resolvedControls,
                    capabilities = routeCaps.controlCapabilities(),
                    mode = if (enabledVideo) CaptureMode.VIDEO else CaptureMode.PHOTO,
                    teleconverter = resolvedTeleconverter,
                    capsLower = range?.lower,
                    capsUpper = range?.upper,
                )
                commitFastPathOrReconfigure(
                    transaction = transaction,
                    cameraId = id,
                    expectedController = expectedController,
                    terminalMutation = {
                        // Enqueue the owned normalized packet while the commit monitor is held. A
                        // newer begin cannot enqueue its packet first or publish raw recalled state.
                        controls = normalizedControls
                        if (!enabledVideo && !resolvedTeleconverter) {
                            lensChoice = LensChoice.forZoom(normalizedControls.zoomRatio)
                        }
                        seedGlZoom()
                        expectedController.updateControls(normalizedControls)
                        overrideId = id
                    },
                    beforeReadyPublication = { generation -> onCapsReady?.invoke(routeCaps, generation) },
                )
            }
        }
    }

    /**
     * Selects the color transfer — the single source of truth. LOG = the GL-baked O-Log2 OETF (the
     * shipping path): the encoder receives the official curve and the preview renders it flat. The
     * device's native HAL log key (`com.oplus.log.video.mode`) is INERT for third-party Camera2
     * (device-settled 2026-07-09 — see the body comment), so [vendorLogMode] stays OFF; the dormant
     * native-log plumbing (pass-through + de-log shader) is kept only for a future
     * CameraUnit-authenticated scene-referred stream.
     *
     * Changing the OETF mid-recording would tag the file with the start transfer but bake a different
     * curve into the second half, so the GL curve is only pushed when idle (the field still updates
     * for the next recording; stopRecording/pause re-apply it). See docs/reviews record-pipeline #4.
     */
    fun setTransfer(t: ColorTransfer) {
        transfer = t
        // LOG = the GL O-Log2 OETF (proven path). The native com.oplus.log.video.mode key was tried
        // twice and is effectively INERT for a third-party Camera2 session: the HAL accepts it
        // ("applied" logs) but neither the preview nor the RECORDED stream is scene-referred —
        // device-tested 2026-07-09 with TEMPLATE_PREVIEW and TEMPLATE_RECORD repeating requests (the
        // clip came out plain 709; the earlier "file looked log" was the BT.2020 full-range container
        // tag being misread by players as a washed look). So the GL path stays: encoder gets the
        // official O-Log2 curve, the preview shows it flat, and Gamma Display Assist shows the normal
        // display-referred image instead. vendorLogMode stays OFF (dormant, with the de-log shader,
        // for a future CameraUnit-authenticated scene-referred path).
        gl.setNativeLog(false)
        val wasNativeLog = vendorLogMode != VendorLogMode.OFF
        vendorLogMode = VendorLogMode.OFF
        if (wasNativeLog) reopenForSession()
        if (recorder == null) gl.setTransfer(t)
    }
    fun setPeaking(enabled: Boolean) {
        rendererConfig.update { it.copy(peaking = enabled) }
        gl.setPeaking(enabled)
    }
    fun setZebra(enabled: Boolean) {
        rendererConfig.update { it.copy(zebra = enabled) }
        gl.setZebra(enabled)
    }

    // Adjustable peaking (sensitivity + color) and zebra (%): threshold and color are combined into
    // one GL call, so either change re-applies both from the current level/color.
    private fun applyPeaking(config: RendererConfig = rendererConfig.snapshot()) =
        gl.setPeakingParams(
            config.peakingLevel.threshold,
            config.peakingColor.r,
            config.peakingColor.g,
            config.peakingColor.b,
        )
    fun setPeakingLevel(l: PeakingLevel) {
        val config = rendererConfig.update { it.copy(peakingLevel = l) }
        applyPeaking(config)
    }
    fun setPeakingColor(c: PeakingColor) {
        val config = rendererConfig.update { it.copy(peakingColor = c) }
        applyPeaking(config)
    }
    fun setZebraLevel(z: ZebraLevel) {
        rendererConfig.update { it.copy(zebraLevel = z) }
        gl.setZebraThreshold(z.threshold)
    }

    /** Enables/disables GL-thread histogram and/or waveform computation feeding [onAnalysis]. */
    fun setAnalysis(histogram: Boolean, waveform: Boolean) {
        rendererConfig.update { it.copy(histogram = histogram, waveform = waveform) }
        gl.setAnalysisEnabled(histogram, waveform)
    }

    /** Replays all desired handler-backed assists into the current GL generation, in order. */
    private fun applyRendererConfig(config: RendererConfig = rendererConfig.snapshot()) {
        gl.setPeaking(config.peaking)
        applyPeaking(config)
        gl.setZebra(config.zebra)
        gl.setZebraThreshold(config.zebraLevel.threshold)
        gl.setFalseColor(config.falseColor)
        gl.setAnalysisEnabled(config.histogram, config.waveform)
        gl.setPunchIn(config.punchIn)
    }

    /** Forces the luma readback for the app-side auto-exposure loop (SHUTTER/ISO priority). */
    fun setAeMetering(enabled: Boolean) {
        // The ViewModel calls this on EVERY control mutation (updateControls); the value only
        // actually changes on a mode transition. Skip the redundant GL-handler post (60-120/s of
        // no-op messages during a sustained gesture).
        if (aeMetering == enabled) return
        aeMetering = enabled
        gl.setAeMetering(enabled)
    }

    /** Gamma Display Assist: normal monitor image while recording O-Log (the file stays log). */
    fun setGammaAssist(enabled: Boolean) {
        gammaAssist = enabled
        gl.setGammaAssist(enabled)
    }

    @Volatile private var gammaAssist = false

    /** AWB gains of the latest frame, for custom (grey-card) WB capture. Null until 3A runs. */
    fun currentAwbGains(): WbGains? =
        controller?.lastAwbGains?.let { WbGains(it.red, it.greenEven, it.greenOdd, it.blue) }

    // Remembered so the GL-start callback can re-seed it: setAeMetering can arrive from settings
    // restore BEFORE the GL thread exists, where GlPipeline.post silently drops it.
    @Volatile private var aeMetering = false

    /**
     * Tap-to-focus/meter. Maps a VIEW-normalized tap [(nx,ny), origin top-left] to a
     * SENSOR-normalized point by inverting the GL content rotation applied to the preview — the
     * sensor orientation plus the teleconverter's afocal 180° (normalized to 0/90/180/270). The
     * centered tap is rotated by -total degrees and re-centered, then forwarded to the controller.
     *
     * NOTE: this ignores the EIS/punch-in crop and offset, so the mapping is only APPROXIMATE and
     * needs on-device calibration — the axis signs (and possibly a horizontal mirror) may need
     * flipping once validated against the live preview.
     */
    fun setTapPoint(nx: Float, ny: Float) {
        val c = caps ?: return
        val sensor = viewTapToSensorPoint(nx, ny, c.sensorOrientation, teleconverterMode)
        controller?.setMeteringPoint(sensor.first, sensor.second)
        val loupe = viewTapToLoupeCenter(nx, ny, previewRotationDegrees())
        gl.setPunchInCenter(loupe.first, loupe.second)
    }

    /** Clears the tap-owned AE/AF region when its UI reticle expires. */
    fun clearTapPoint() {
        controller?.clearMeteringPoint()
    }

    // ---- Drive mode + video parameters ----

    fun setDriveMode(m: DriveMode) {
        driveMode = m
        // Selecting any non-timelapse drive mode cancels an in-progress interval sequence.
        if (m != DriveMode.TIMELAPSE) stopTimelapse()
    }
    fun setIntervalSec(s: Int) { intervalSec = s }
    fun setVideoCodec(c: VideoCodec) { videoCodec = c }
    fun setBitrateLevel(b: BitrateLevel) { bitrateLevel = b }

    /**
     * Selects the video frame rate. Shipping UI and settings restore exclude high-speed rates because
     * the constrained session SIGABRTs this HAL. The boundary/reopen path remains dormant for
     * diagnostics and persisted-schema compatibility; normal rates only update the next recording.
     */
    fun setVideoFrameRate(r: VideoFrameRate) {
        val before = desiredHighSpeedFps()
        videoFrameRate = r
        if (desiredHighSpeedFps() != before) reopenForSession()
    }

    /** Open Gate: record the full 4:3 sensor readout. Switches [videoSize] to a 4:3 size and re-picks it. */
    fun setOpenGate(enabled: Boolean) {
        if (openGate == enabled) return
        // Open Gate changes the recorded aspect/size; refuse mid-recording (the encoder is fixed-size).
        if (recorder != null) { onStatus?.invoke("Stop REC first"); return }
        openGate = enabled
        val sel = selection ?: return
        applyVideoSize(chooseVideoSize(sel))
    }

    /**
     * Dormant high-speed-session resolver. Shipping selection/restore guarantees 0 because this HAL
     * aborts constrained sessions; diagnostic internal callers still require a matching advertised
     * config before the compatibility path returns a non-zero rate.
     */
    private fun desiredHighSpeedFps(): Int {
        val c = caps ?: return 0
        val r = videoFrameRate
        return if (r.highSpeed && c.highSpeedFpsFor(videoSize) >= r.fps) r.fps else 0
    }

    /** Capture a generation-owned desired packet before invalidating the accepted session. */
    private fun reopenForSession(expectedController: CameraController? = null) {
        reopenForSession(expectedController, currentOpticsReconfiguration())
    }

    /** Reopen for an already-owned optics transaction (mode/stream-size transition). */
    private fun reopenForSession(transaction: OpticsTransaction) {
        val desired = synchronized(this) { OpticsReconfiguration(overrideId, transaction) }
        reopenForSession(expectedController = null, desired = desired)
    }

    /** Reopen the camera (if started) so the session type tracks [desiredHighSpeedFps]. */
    private fun reopenForSession(
        expectedController: CameraController?,
        desired: OpticsReconfiguration,
    ) {
        val transaction = desired.transaction
        if (!started || paused) return
        if (!ownsOpticsTransaction(transaction)) return
        // Never tear the camera down under an active recording — it strands the encoder input surface
        // and gaps/corrupts the clip. The rate/open-gate change applies on the next recording instead.
        if (recorder != null) return
        if (gl.inputSurface == null) return
        invalidateCameraReady()
        // Run the close+reopen OFF the main thread. close() blocks until the HAL device is released
        // (bounded join) and openCamera() issues several getCameraCharacteristics/openCamera Binder
        // calls; on this HAL those can take seconds under contention, so doing it on the UI thread
        // (vendor-feature toggles are main-thread ViewModel calls) exceeds the 5s ANR watchdog and the
        // OS kills the app. setupExecutor is single-threaded, so reopens also serialize cleanly.
        setupExecutor.execute {
            if (!sessionReopenMayProceed(
                    currentGeneration = opticsIntentGeneration.get(),
                    expectedGeneration = transaction.generation,
                    expectedControllerMatches = expectedController == null || controller === expectedController,
                    paused = paused,
                    recording = recorder != null,
                )
            ) return@execute
            // Every reopen resolves the immutable desired route under its generation. There is no
            // transaction-less close/open branch that can pair stale selection/caps with a newer
            // optics generation after this task was queued.
            reconfigureCamera(desired.overrideId, transaction)
        }
    }

    /**
     * Atomically invalidates an accepted Camera2 session and claims any recorder fed by it. The
     * shared engine monitor is also the recording-admission gate, so failure either wins before REC
     * publication or claims the published recorder; it cannot leave a phantom recorder between the
     * two transitions.
     */
    private fun handleActiveCameraFailure(
        failedController: CameraController,
        failure: Throwable,
    ) {
        val outcome = synchronized(this) {
            if (!activeCameraFailureBelongsToController(controller, failedController)) return
            val sessionGeneration = cameraSessionGeneration.incrementAndGet()
            cameraReady = false
            readyController = null
            acceptedCameraSession = null
            val publication = nextCameraReadyPublication(
                ready = false,
                opticsGeneration = opticsIntentGeneration.get(),
                sessionGeneration = sessionGeneration,
            )
            val ownedRecording = synchronized(recorderOwnershipLock) {
                recorder?.let { owned ->
                    recorder = null
                    val uri = activeRecordingUri
                    val captureId = activeRecordingCaptureId
                    activeRecordingUri = null
                    OwnedRecording(owned, uri, captureId)
                }
            }
            publication to ownedRecording
        }
        onCameraReadyChange?.invoke(outcome.first)
        onStatus?.invoke("Camera error: ${failure.message}")
        outcome.second?.let { owned ->
            detachAndFinalizeRecording(owned.recorder, owned.uri, owned.captureId)
            gl.setTransfer(transfer)
            onRecordingTerminated?.invoke(failure)
        }
        // The recorder has been claimed before recovery checks its invariant, so a failed Camera2
        // producer cannot strand UI/audio/encoder state and then suppress the bounded reopen.
        scheduleCameraRecovery(failedController)
    }

    /**
     * The camera HAL reported an error/disconnect mid-session — its provider process can crash (e.g. a
     * vendor key destabilizes it), which otherwise leaves a black CAMERA_DISCONNECTED viewfinder until
     * the user backgrounds/foregrounds the app. Auto-reopen a bounded number of times, after a short
     * delay so the provider has time to restart. The attempt counter resets on the next successful open.
     */
    private fun scheduleCameraRecovery(failedController: CameraController) {
        if (!started || paused || recorder != null) return
        if (controller !== failedController) return
        if (cameraRecoveryAttempts >= MAX_CAMERA_RECOVERY_ATTEMPTS) {
            // Recovery exhausted: say so instead of silently leaving a black viewfinder behind an
            // interactive-looking UI. cameraReady stays false, so the shutter is dimmed too; a
            // background/foreground cycle (resume()) retries with a fresh attempt budget.
            onStatus?.invoke("Camera failed — reopen the app")
            return
        }
        cameraRecoveryAttempts++
        runCatching {
            timelapseScheduler.schedule(
                {
                    if (controller === failedController) reopenForSession(failedController)
                },
                CAMERA_RECOVERY_DELAY_MS,
                java.util.concurrent.TimeUnit.MILLISECONDS,
            )
        }
    }

    /** Bounded retry for transient selection/capability failures before the first Ready session. */
    private fun scheduleColdStartRetry(transaction: OpticsTransaction, reason: String) {
        val canRun = started && !paused && recorder == null && gl.inputSurface != null
        when (val failure = coldStartRetryGate.failed(
            expectedGeneration = transaction.generation,
            currentGeneration = opticsIntentGeneration.get(),
            canRun = canRun,
        )) {
            ColdStartRetryGate.Failure.Ignore -> Unit
            ColdStartRetryGate.Failure.Exhausted ->
                onStatus?.invoke("Camera unavailable — retry the app")
            is ColdStartRetryGate.Failure.Retry -> {
                onStatus?.invoke("$reason — retrying")
                runCatching {
                    timelapseScheduler.schedule(
                        {
                            val retryable = started && !paused && recorder == null &&
                                gl.inputSurface != null
                            if (!coldStartRetryGate.claim(
                                    failure.token,
                                    opticsIntentGeneration.get(),
                                    retryable,
                                )
                            ) return@schedule
                            val desired = currentOpticsReconfiguration()
                            reconfigureCamera(
                                desired.overrideId,
                                desired.transaction,
                                startup = true,
                            )
                        },
                        CAMERA_RECOVERY_DELAY_MS,
                        java.util.concurrent.TimeUnit.MILLISECONDS,
                    )
                }.onFailure {
                    coldStartRetryGate.abandon(failure.token)
                    onStatus?.invoke("Camera unavailable")
                }
            }
        }
    }

    /** Software gain applied to recorded PCM audio (1f = passthrough); takes effect on the next [startRecording]. */
    fun setAudioGain(g: Float) { audioGain = g }
    /** Directional-audio scene (Sound Focus/Stage); applies on the next [startRecording]. */
    fun setAudioScene(s: AudioScene) { audioScene = s }
    /** Preferred recording input; resolved against connected AudioDeviceInfo entries at record start. */
    fun setAudioInputPreference(p: AudioInputPreference) { audioInputPreference = p }

    /** Still-photo center-crop aspect ratio; applies to HEIF and JPEG. W4_3 = no crop. */
    fun setAspectRatio(a: AspectRatio) { aspectRatio = a }

    /**
     * Selects the video capture resolution and recreates the Camera2 session so the producer stream,
     * SurfaceTexture buffer and encoder all agree on the same dimensions.
     */
    fun setRecalledVideoResolution(s: Size) {
        // A memory/settings recall can target a different camera whose caps have not arrived yet.
        // Store the request only; chooseVideoSize validates it against that target during reconfigure.
        applyVideoResolutionRequest(s, VideoSizeRequestSource.RECALL)
    }

    fun setVideoResolution(s: Size): Boolean =
        applyVideoResolutionRequest(s, VideoSizeRequestSource.INTERACTIVE)

    private fun applyVideoResolutionRequest(s: Size, source: VideoSizeRequestSource): Boolean {
        val offered = caps?.let { if (openGate) it.openGateVideoSizes else it.availableVideoSizes }
        if (validatesVideoSizeAgainstCurrentCaps(source) && offered != null && s !in offered) {
            onStatus?.invoke("Resolution unavailable on this camera")
            return false
        }
        // Remember the user's pick so lens switches and the initial open don't silently re-derive
        // the largest size over it — chooseVideoSize honors this request whenever the current lens
        // still offers it (and falls back to auto when it doesn't, e.g. after an openGate aspect
        // flip or a lens without that mode).
        requestedVideoSize = s
        if (source == VideoSizeRequestSource.RECALL) return true
        if (videoSize == s) return true
        applyVideoSize(s)
        return true
    }

    private fun applyVideoSize(s: Size) {
        videoSize = s
        onVideoSizeChosen?.invoke(s, null)
        // In PHOTO mode the preview stream stays on the 4:3 full-field size — the new recording
        // size takes effect on the stream when the user switches to VIDEO (setVideoMode reopens).
        if (videoMode) {
            previewStreamSize = s
            emitPreviewAspect()
            gl.setCameraPreviewSize(s.width, s.height)
            // Output dimensions are part of Camera2's configured stream contract. Updating only the
            // SurfaceTexture default size leaves the producer on the old 16:9/4:3 stream.
            reopenForSession()
        }
    }

    /**
     * Switches to one of the four rear lenses — by default bundling teleconverter mode: the 3× lens
     * enables it (the afocal 180° flip; stabilization at 300 mm is the HAL's OIS+EIS via
     * [VideoStabMode], not app-side gyro warping), any other lens disables it — one action, one
     * camera reopen. [teleconverterOverride] lets TELE toggles retain or detach the converter in the
     * same reopen. Resolves the [LensChoice]'s target focal to
     * a concrete (standalone-preferred) camera id so nothing is hardcoded. No-op mid-recording
     * (reconfiguring under the encoder corrupts the clip); the UI also gates it.
     */
    // Pre-TELE framing snapshot as a UNIFIED main-relative zoom (mode-independent), so turning
    // TELE off returns the user EXACTLY where they were — lens band, ratio, focal readout — in
    // photo AND video (user-reported: off used to pin the 3× preset, or 9.1× through a remap).
    @Volatile private var preTeleUnifiedZoom = Float.NaN

    fun setLens(
        choice: LensChoice,
        teleconverterOverride: Boolean = false,
        // TELE-toggle-off only: restore the pre-TELE framing instead of jumping to [choice]'s
        // preset. An explicit lens pick (onLens) must NOT restore — the user chose a new framing.
        restorePreTele: Boolean = false,
    ) {
        if (recorder != null) { onStatus?.invoke("Stop REC first"); return }
        val intentGeneration = lensIntentGeneration.incrementAndGet()
        val (transaction, resolved) = beginOpticsTransaction {
            val intent = resolveLensOpticsIntent(
                mode = if (videoMode) CaptureMode.VIDEO else CaptureMode.PHOTO,
                currentLens = lensChoice,
                currentTeleconverter = teleconverterMode,
                currentControls = controls,
                currentPreTeleUnifiedZoom = preTeleUnifiedZoom,
                requestedLens = choice,
                requestedTeleconverter = teleconverterOverride,
                restorePreTele = restorePreTele,
            )
            // Publish the COMPLETE desired packet under the same monitor as its generation.
            lensChoice = intent.lens
            teleconverterMode = intent.teleconverter
            controls = intent.controls
            preTeleUnifiedZoom = intent.preTeleUnifiedZoom
            overrideId = null
            intent
        }
        // Resolve the camera id ON setupExecutor (dozen-plus Binder IPCs; also orders resolve→reopen).
        setupExecutor.execute {
            if (!ownsOpticsTransaction(transaction) ||
                lensIntentGeneration.get() != intentGeneration || paused || recorder != null
            ) return@execute
            // The TC state is SESSION-scoped: the session TYPE (0x80b4, the stock TC operation mode
            // that engages the real 300 mm OIS profile), the Hasselblad hints, and the afocal flip
            // are all fixed at configureStreams. Flipping TELE on the SAME camera id (the video 3×
            // band and TELE both live on standalone 4) therefore MUST still reopen — device-observed
            // otherwise: the TC stabilization kept applying after TELE off, and turning TELE on from
            // the video 3× lens never engaged it.
            val teleChanged = transaction.before.teleconverter != resolved.teleconverter
            if (resolved.teleconverter) {
                // Physical-converter shooting: pin the STANDALONE 3× camera — the converter clamps
                // onto that lens, so the logical camera's seamless switching would zoom right off it.
                // Digital 1–10× only, afocal flip on, RAW available (standalone id).
                val id = cachedIdForFocal(LensChoice.TELE3X.targetEquivMm)
                if (id == null) {
                    rollbackOptics(transaction, "3× unavailable; lens unchanged")
                    return@execute
                }
                val beforeCameraId = transaction.before.selection?.let { it.physicalId ?: it.logicalId }
                if (beforeCameraId != id || controller == null || teleChanged) {
                    reconfigureCamera(id, transaction)
                } else if (transaction.before.ready &&
                    transaction.before.readyController === controller
                ) {
                    val expectedController = controller ?: return@execute
                    commitFastPathOrReconfigure(transaction, id, expectedController) {
                        overrideId = id
                        applyStabilization()
                    }
                } else {
                    reconfigureCamera(id, transaction)
                }
            } else {
                // Mode-split homes (see resolveNonTeleId): PHOTO picks are ZOOM PRESETS on the
                // logical seamless camera (no reopen); VIDEO picks reopen the matching STANDALONE
                // lens (lens-local zoom resets to 1×).
                val id = resolveNonTeleId(resolved.lens)
                if (id == null) {
                    rollbackOptics(transaction, "${resolved.lens.label} unavailable; lens unchanged")
                    return@execute
                }
                val beforeCameraId = transaction.before.selection?.let { it.physicalId ?: it.logicalId }
                if (beforeCameraId != id || controller == null || teleChanged) {
                    reconfigureCamera(id, transaction)
                } else if (transaction.before.ready &&
                    transaction.before.readyController === controller
                ) {
                    val expectedController = controller ?: return@execute
                    commitFastPathOrReconfigure(transaction, id, expectedController) {
                        setZoomRatio(resolved.controls.zoomRatio)
                        overrideId = id
                    }
                } else {
                    reconfigureCamera(id, transaction)
                }
            }
        }
    }

    /**
     * The non-teleconverter camera id for the CURRENT capture mode. PHOTO lives on the LOGICAL
     * multicamera — seamless 0.6–20× pinch, stills device-verified clean. VIDEO pins the matching
     * STANDALONE lens: the logical camera's EIS (Standard AND Active) leaks its uncorrected warp
     * margin into the stream's left edge — device-verified 2026-07-14 in the preview AND the
     * RECORDED file — while the standalone cameras have shipped clean stabilized video all along.
     * Falls back to the closest standalone id on devices without a logical back camera.
     */
    private fun resolveNonTeleId(choice: LensChoice): String? =
        if (videoMode) {
            cachedIdForFocal(choice.targetEquivMm)
        } else {
            cachedLogicalBack()
                ?: cachedIdForFocal(choice.targetEquivMm)
        }

    fun setCameraOverride(id: String?) {
        // Switching the physical lens mid-recording reconfigures the camera under the encoder and
        // gaps/corrupts the clip. Refuse until recording stops; the UI also gates this.
        if (recorder != null) { onStatus?.invoke("Stop REC first"); return }
        val transaction = beginOpticsTransaction { overrideId = id }.first
        reconfigureCamera(id, transaction)
    }

    private fun reconfigureCamera(
        id: String?,
        transaction: OpticsTransaction,
        startup: Boolean = false,
    ) {
        synchronized(this) {
            if (!ownsOpticsTransaction(transaction)) return
            overrideId = id
        }
        if (!started) return
        val input = gl.inputSurface ?: run {
            // An optics intent that lands between GL start and input-Surface creation is valid and
            // remains Not-Ready. The input callback snapshots the latest generation and converges it.
            if (glInputPending) return
            if (startup || controller == null) {
                scheduleColdStartRetry(transaction, "Preview unavailable")
            } else {
                rollbackOptics(transaction, "Preview unavailable; camera unchanged")
            }
            return
        } // @Volatile in GlPipeline: safe cross-thread read
        invalidateCameraReady()
        // Off the main thread (same reason as reopenForSession): close() blocks on the HAL device
        // release and select/read/openCamera are several Binder IPCs — on the UI thread this ANR-kills
        // the app under HAL contention. Runs on the single-thread setupExecutor so it serializes with
        // the initial open and other reopens.
        setupExecutor.execute {
            if (!ownsOpticsTransaction(transaction)) return@execute
            if (paused || recorder != null) return@execute
            val recoverColdPreflight = startup || controller == null
            // Resolve the id and read the characteristics BEFORE closing: these are dozens of
            // Binder IPCs (~100 ms uncached) that used to sit inside the close→open blackout —
            // the old camera keeps streaming while they run, shrinking the visible freeze.
            val sel = selectCurrentLens() ?: run {
                if (recoverColdPreflight) {
                    scheduleColdStartRetry(transaction, "Camera ID unavailable")
                } else {
                    rollbackOptics(transaction, "Camera ID unavailable; camera unchanged")
                }
                return@execute
            }
            val c = cachedCaps(sel.logicalId, sel.physicalId)
                ?: run {
                    if (recoverColdPreflight) {
                        scheduleColdStartRetry(transaction, "Camera unavailable")
                    } else {
                        rollbackOptics(transaction, "Camera unavailable; camera unchanged")
                    }
                    return@execute
                }
            if (!ownsOpticsTransaction(transaction)) return@execute
            if (paused || recorder != null) return@execute
            // This lightweight physical-member walk happens on setupExecutor while the old camera
            // is still streaming. The still callback remains cache-only even on the first lens hit.
            prefetchLensExifMetadata(sel, c)
            if (!ownsOpticsTransaction(transaction)) return@execute
            if (paused || recorder != null) return@execute

            // DUAL-OPEN: open the NEXT device while the old camera keeps streaming (~120 ms of the
            // blackout overlapped away). The preview surface still belongs to the old session, so
            // the new session is deferred until the old controller closes. If the HAL refuses a
            // second open (shared physical sensor / max-cameras), fall back to the sequential path.
            // A concurrent eviction of the OLD device is harmless: `controller` is already the new
            // one, so the old error handler's identity guard no-ops and its close() self-runs.
            val old = controller
            val next = wireController()
            val candidatePublication = synchronized(this) {
                if (!ownsOpticsTransaction(transaction)) {
                    null
                } else {
                    controller = next
                    cameraReady = false
                    readyController = null
                    acceptedCameraSession = null
                    val sessionGeneration = cameraSessionGeneration.incrementAndGet()
                    selection = sel
                    caps = c
                    reconcileControlsWithCaps(c)
                    videoSize = chooseVideoSize(sel)
                    previewStreamSize = choosePreviewStreamSize(sel)
                    nextCameraReadyPublication(
                        ready = false,
                        opticsGeneration = transaction.generation,
                        sessionGeneration = sessionGeneration,
                    )
                }
            }
            if (candidatePublication == null) {
                next.close()
                return@execute
            }
            onCameraReadyChange?.invoke(candidatePublication)

            seedGlZoom()
            val deviceUp = java.util.concurrent.CountDownLatch(1)
            val deviceOk = java.util.concurrent.atomic.AtomicBoolean(false)
            next.open(
                selection = sel,
                caps = c,
                glInputSurface = input,
                controls = controls,
                tenBitHlg = false, // SDR session — HLG10+RAW+JPEG crashes the HAL
                highSpeedFps = desiredHighSpeedFps(),
                vendorLogMode = vendorLogMode.halValue,
                videoStabHalMode = c.videoStabControlMode(videoStabMode),
                teleconverterMode = teleconverterMode,
                pinAutoFps = videoMode,
                deferSession = true,
                onDeviceOpened = { deviceOk.set(true); deviceUp.countDown() },
                onReady = { outputs ->
                    commitOpticsReady(
                        expectedGeneration = transaction.generation,
                        expectedController = next,
                        photoOutputs = outputs,
                        expectedSessionGeneration = candidatePublication.sessionGeneration,
                    )
                },
                onError = { failure ->
                    deviceUp.countDown() // an open-phase failure also releases the latch (fallback)
                    // A concurrent-open refusal is expected on devices that cannot dual-open a
                    // shared sensor; the setup task below owns that local sequential fallback.
                    // Only an error AFTER the next device opened is a real active-controller fault.
                    if (deviceOk.get()) {
                        handleActiveCameraFailure(next, failure)
                    }
                },
            )
            // The old camera streams through the new device's open. Bounded wait: a refusal or a
            // wedged open must degrade to the sequential path, not hang the setup thread.
            deviceUp.await(2, java.util.concurrent.TimeUnit.SECONDS)
            // The blocking wait is an ownership boundary: pause, REC, or a newer reopen may have
            // superseded this attempt while the setup thread was parked. Do not publish sizes or
            // start a deferred session from an obsolete device; release every local handle.
            if (!ownsOpticsTransaction(transaction)) {
                next.close()
                synchronized(this) {
                    if (controller === next) {
                        controller = old
                        selection = transaction.before.selection
                        caps = transaction.before.caps
                        videoSize = transaction.before.videoSize
                        previewStreamSize = transaction.before.previewStreamSize
                    }
                }
                return@execute
            }
            if (paused || recorder != null || controller !== next) {
                next.close()
                old?.close()
                synchronized(this) {
                    if (controller === next) controller = null
                }
                return@execute
            }
            // Candidate caps/sizes become externally visible only after the blocking dual-open
            // boundary confirms this generation still owns the user's intent.
            val deliveredGeneration = transaction.generation
            onCapsReady?.invoke(c, deliveredGeneration)
            onVideoSizeChosen?.invoke(videoSize, deliveredGeneration)
            applyStabilization()
            // Update the GL stream size only now — the old camera was still producing into the
            // shared SurfaceTexture; resizing it earlier would distort its final frames.
            emitPreviewAspect(deliveredGeneration)
            gl.setCameraPreviewSize(previewStreamSize.width, previewStreamSize.height)
            old?.close()
            if (deviceOk.get()) {
                next.startDeferredSession()
            } else {
                // Sequential fallback: the HAL refused the concurrent open.
                next.close()
                synchronized(this) {
                    if (controller === next) controller = null
                }
                if (paused || recorder != null) return@execute
                openCamera(input, transaction)
            }
        }
    }

    private fun selectCurrentLens(): TeleSelection? {
        val id = overrideId
            ?: (if (!teleconverterMode) resolveNonTeleId(lensChoice) else null)
            ?: cachedIdForFocal(lensChoice.targetEquivMm)
            ?: return null
        return CameraSelector2.select(manager, id)
    }

    /**
     * Zoom-gesture smoothness: in low light the preview legitimately idles at 10-15 fps — the
     * app-side P loop parks exposure at up to 1/10 s (its handheld ceiling) and HAL-AE photo AUTO
     * uses the lowest fps floor for a brighter view. At that rate ANY zoom reads as jank, no matter
     * how smoothly the ratio is applied. While a gesture is live: pin the HAL-AE fps floor
     * (controller boost), and for the app-side P loop trade exposure time for ISO
     * BRIGHTNESS-NEUTRALLY — only as far as ISO headroom allows, floored at 1/30 s, so the image
     * never dims (in this trade the frame rate simply rises as far as physics permits). On release
     * the loop re-converges to its preferred program point on its own (≤0.35 stop/tick).
     */
    fun setZoomInteraction(active: Boolean) {
        zoomInteractionActive = active
        controller?.setSmoothPreviewBoost(active)
        if (!active) {
            // Gesture over: land the HAL on the EXACT requested ratio (mid-gesture submits are
            // throttled and aimed slightly wide); the GL compensation converges to 1 as the
            // matching results arrive.
            controller?.setZoomRatio(controls.zoomRatio)
        }
        // (The low-light frame-rate help lives in applyExposure's ALWAYS-on preview exposure cap —
        // an earlier gesture-scoped trade here mutated the real program values, so a still captured
        // right after a zoom inherited the traded short-exposure/high-ISO pair.)
    }

    /**
     * Re-seeds the GL live-zoom pair at a camera (re)open. A fresh session's HAL RAMPS its zoom
     * from 1.0 to the requested ratio over several frames — with a stale GL target the compensation
     * can't mask it, which showed as a 1×→3× flicker the instant TELE turned off. Seeding target
     * AND hal to the requested ratio holds the last frame steady; the first real capture result
     * then reports the HAL's true (still-ramping) zoom and the crop masks the rest of the ramp.
     */
    private fun seedGlZoom() {
        gl.setZoomTarget(controls.zoomRatio)
        gl.setHalZoom(controls.zoomRatio)
    }

    /**
     * Zoom FAST PATH — pinch/dial ticks arrive at input rate, and routing them through the full
     * setControls → startPreview rebuild (request re-derivation, metering regions, AF-trigger arming,
     * new callback per tick) is what read as zoom lag/stutter. The controller instead mutates only
     * the zoom keys on its CACHED repeating builder and resubmits. [controls] stays the source of
     * truth so the next full rebuild carries the same value (no snap-back).
     */
    fun setZoomRatio(ratio: Float) {
        val r = caps?.zoomRatioRange
        // TELE's zoom is capped at the 60× display ceiling (local ≈4.6 on the 3× lens): past that
        // the digital crop is unusable at 1400 mm-equivalent anyway.
        val hi = if (teleconverterMode) {
            minOf(r?.upper ?: Float.MAX_VALUE, TELE_MAX_DISPLAY_ZOOM / TELE_DISPLAY_BASE)
        } else {
            r?.upper ?: Float.MAX_VALUE
        }
        val z = ratio.coerceIn(r?.lower ?: ratio, hi)
        controls = controls.copy(zoomRatio = z)
        // The PREVIEW zooms instantly: GL crops the last frame to the requested ratio and
        // self-redraws (every setRepeatingRequest stalls this HAL's stream ~180 ms — measured —
        // so per-tick HAL submits made zoom read as ~5 fps no matter how smooth the input was).
        gl.setZoomTarget(z)
        // The HAL follows at a throttled pace. Mid-gesture it is aimed slightly WIDE
        // (÷ZOOM_GESTURE_MARGIN) so the GL crop has field for instant zoom-out too; the exact
        // value lands at gesture end (setZoomInteraction(false)).
        val now = android.os.SystemClock.uptimeMillis()
        val halTarget = if (zoomInteractionActive) {
            val wide = z / ZOOM_GESTURE_MARGIN
            if (r != null) wide.coerceIn(r.lower, r.upper) else wide
        } else z
        if (!zoomInteractionActive || now - lastHalZoomSubmitMs >= ZOOM_HAL_THROTTLE_MS) {
            lastHalZoomSubmitMs = now
            controller?.setZoomRatio(halTarget)
        }
    }

    @Volatile private var zoomInteractionActive = false
    @Volatile private var lastHalZoomSubmitMs = 0L

    // ---- Photo ----

    private fun currentAcceptedCameraSession(): AcceptedCameraSession? {
        val accepted = acceptedCameraSession ?: return null
        return accepted.takeIf {
            cameraReady && !paused && controller === it.controller &&
                cameraSessionGeneration.get() == it.sessionGeneration
        }
    }

    private fun acceptedSessionIsCurrent(accepted: AcceptedCameraSession): Boolean =
        acceptedCameraSession === accepted && currentAcceptedCameraSession() === accepted

    /** Returns true only when this press was admitted to a real still target. */
    fun capturePhoto(formats: PhotoFormats): Boolean {
        // Same gate startRecording has: during a session-key reopen (cameraReady=false) the
        // controller guards make a capture attempt safe but silent — give the user the status
        // instead of a shutter press that does nothing.
        val accepted = currentAcceptedCameraSession()
        if (accepted == null) {
            onStatus?.invoke("Camera reconfiguring")
            return false
        }
        val effFormats = formats.normalizedFor(accepted.outputs)
        if (!effFormats.wantsProcessedStill && !effFormats.dngRaw) {
            onStatus?.invoke("Still capture unavailable in current session")
            return false
        }
        when {
            formats.wantsProcessedStill && !effFormats.wantsProcessedStill && effFormats.dngRaw ->
                onStatus?.invoke("Processed still unavailable; capturing DNG")
            formats.dngRaw && !effFormats.dngRaw ->
                onStatus?.invoke("RAW unavailable in current session")
        }
        val ctrl = accepted.controller
        when (driveMode) {
            DriveMode.SINGLE -> ctrl.capturePhoto(
                effFormats.wantsProcessedStill,
                effFormats.dngRaw,
                photoCallback(effFormats, controls),
            )
            DriveMode.BURST -> captureBurst(accepted, effFormats)
            DriveMode.AEB -> captureAeb(accepted, effFormats)
            DriveMode.TIMELAPSE -> startTimelapse(formats)
        }
        return true
    }

    /**
     * BURST: [BURST_COUNT] stills, each started only after the previous completes. The controller
     * tracks a single in-flight capture (one `pending` slot), so shots are chained rather than fired
     * in a tight loop, which would clobber that slot while a capture is still resolving its images.
     */
    private fun captureBurst(accepted: AcceptedCameraSession, formats: PhotoFormats) {
        fun fire(shot: Int) {
            if (shot >= BURST_COUNT || !acceptedSessionIsCurrent(accepted)) return
            accepted.controller.capturePhoto(
                formats.wantsProcessedStill,
                formats.dngRaw,
                photoCallback(formats, controls) { fire(shot + 1) },
            )
        }
        fire(0)
    }

    /**
     * AEB: three stills at -2 / 0 / +2 EV. In AUTO exposure the bracket drives
     * CONTROL_AE_EXPOSURE_COMPENSATION; in MANUAL exposure the HAL ignores that key entirely (AE
     * is OFF), which used to fire three identical frames — so the manual path brackets the
     * exposure TIME instead (×¼ / ×1 / ×4, clamped to the sensor range, ISO untouched; see
     * [manualAebExposuresNs]). Either way the original controls are restored when the bracket
     * finishes, and shots are chained for the same single-`pending` reason as BURST.
     */
    private fun captureAeb(accepted: AcceptedCameraSession, formats: PhotoFormats) {
        val ctrl = accepted.controller
        val c = caps
        val original = controls
        if (!original.autoExposure && c?.supportsManualSensor == true) {
            val base = original.effectiveExposureNs()
            val range = c.exposureTimeRange
            val steps = manualAebExposuresNs(base, range?.lower ?: base, range?.upper ?: base)
            fun fire(i: Int) {
                if (!acceptedSessionIsCurrent(accepted)) {
                    ctrl.updateControls(controls)
                    return
                }
                if (i >= steps.size) { ctrl.updateControls(controls); return }
                // SPEED override so the bracketed time applies even when the user dials ANGLE.
                val stepControls = manualAebStepControls(controls, steps[i])
                ctrl.updateControls(stepControls)
                ctrl.capturePhoto(
                    formats.wantsProcessedStill,
                    formats.dngRaw,
                    photoCallback(formats, stepControls) { fire(i + 1) },
                )
            }
            fire(0)
            return
        }
        val range = c?.evRange
        val evStep = c?.evStep?.let {
            if (it.denominator == 0) 1f / 3f else it.numerator.toFloat() / it.denominator.toFloat()
        } ?: (1f / 3f)
        val steps = if (range != null) {
            aeCompAebSteps(original.exposureCompensation, range.lower, range.upper, evStep)
        }
        else listOf(original.exposureCompensation)
        fun fire(i: Int) {
            if (!acceptedSessionIsCurrent(accepted)) {
                ctrl.updateControls(controls)
                return
            }
            if (i >= steps.size) { ctrl.updateControls(controls); return }
            val stepControls = autoAebStepControls(controls, steps[i])
            ctrl.updateControls(stepControls)
            ctrl.capturePhoto(
                formats.wantsProcessedStill,
                formats.dngRaw,
                photoCallback(formats, stepControls) { fire(i + 1) },
            )
        }
        fire(0)
    }

    /**
     * TIMELAPSE: one capture + processed save at a time. The next interval starts only after
     * [photoCallback]'s exactly-once completion, so full-resolution snapshots cannot accumulate on
     * [ioExecutor] when encoding/storage is slower than [intervalSec].
     */
    private fun startTimelapse(requestedFormats: PhotoFormats) {
        stopTimelapse()
        val period = intervalSec.coerceAtLeast(1).toLong()
        val generation = timelapseRun.restart()
        val sequence = object {
            fun schedule(delaySeconds: Long) {
                if (!timelapseRun.owns(generation)) return
                val future = runCatching {
                    timelapseScheduler.schedule(
                        {
                            if (!timelapseRun.owns(generation)) return@schedule
                            val accepted = currentAcceptedCameraSession()
                            if (accepted == null || driveMode != DriveMode.TIMELAPSE) {
                                if (!paused && driveMode == DriveMode.TIMELAPSE) schedule(period)
                                return@schedule
                            }
                            val formats = requestedFormats.normalizedFor(accepted.outputs)
                            if (!formats.wantsProcessedStill && !formats.dngRaw) {
                                onStatus?.invoke("Still capture unavailable in current session")
                                stopTimelapse()
                                return@schedule
                            }
                            accepted.controller.capturePhoto(
                                formats.wantsProcessedStill,
                                formats.dngRaw,
                                photoCallback(formats, controls) {
                                    if (timelapseRun.owns(generation)) schedule(period)
                                },
                            )
                        },
                        delaySeconds,
                        java.util.concurrent.TimeUnit.SECONDS,
                    )
                }.getOrNull() ?: return
                if (timelapseRun.owns(generation)) {
                    timelapseFuture = future
                } else {
                    future.cancel(false)
                }
            }
        }
        sequence.schedule(0)
    }

    fun stopTimelapse() {
        timelapseRun.stop()
        timelapseFuture?.cancel(false)
        timelapseFuture = null
    }

    /** Immutable request-time state consumed by every output belonging to one shutter press. */
    private data class ShotSpec(
        val controls: ManualControls,
        val caps: CameraCaps?,
        val selection: TeleSelection?,
        val teleconverter: Boolean,
        val aspectRatio: AspectRatio,
        val jpegQuality: Int,
        val rotationDegrees: Int,
        val captureId: Int,
        val familyKey: CaptureFamilyKey,
        val requestedAtMs: Long,
        val takenAtMs: Long,
    )

    private fun shotSpec(shotControls: ManualControls): ShotSpec {
        val shotCaps = caps
        val shotTeleconverter = teleconverterMode
        val requestedAtMs = System.currentTimeMillis()
        val captureId = captureSeq.incrementAndGet()
        val rotation = shotCaps?.let {
            RotationMath.captureRotationDegrees(
                it.sensorOrientation,
                shotTeleconverter,
                gyro.currentDeviceOrientation(),
            )
        } ?: 0
        return ShotSpec(
            controls = shotControls,
            caps = shotCaps,
            selection = selection,
            teleconverter = shotTeleconverter,
            aspectRatio = aspectRatio,
            jpegQuality = shotControls.jpegQuality.coerceIn(1, 100),
            rotationDegrees = rotation,
            captureId = captureId,
            familyKey = CaptureFamilyKey(
                media = CaptureFamilyMedia.STILL,
                capturedAtEpochMillis = requestedAtMs,
                sequence = captureId.toLong(),
            ),
            requestedAtMs = requestedAtMs,
            takenAtMs = requestedAtMs,
        )
    }

    /**
     * Per-shot save callback. Processed frame copying happens while the Image is live, then encoding
     * runs on [ioExecutor]. A BURST/AEB continuation is admitted only after that save job finishes,
     * keeping at most one full processed snapshot queued behind the active shot.
     */
    private fun photoCallback(
        formats: PhotoFormats,
        shotControls: ManualControls,
        onDone: (() -> Unit)? = null,
    ): CameraController.PhotoCallback {
        val requestSpec = shotSpec(shotControls)
        val completionDelivered = java.util.concurrent.atomic.AtomicBoolean(false)
        val finish = {
            if (completionDelivered.compareAndSet(false, true)) onDone?.invoke()
            Unit
        }
        return object : CameraController.PhotoCallback {
            override fun onPhoto(
                jpeg: Image?,
                raw: Image?,
                result: TotalCaptureResult,
                rawChars: CameraCharacteristics,
                takenAtMs: Long,
            ) {
                val spec = requestSpec.copy(takenAtMs = takenAtMs)
                // Copy the live Image first so the ImageReader slot and Camera2 handler are held for
                // the shortest possible interval; EXIF composition below performs cache-only reads.
                val processedSnapshot = if (formats.wantsProcessedStill && jpeg != null) {
                    runCatching { StillSnapshot.from(jpeg) }.getOrNull()
                } else {
                    null
                }
                val exifShot = exifShotOf(result, spec)
                var processedQueued = false

                if (formats.wantsProcessedStill) {
                    if (jpeg != null) {
                        if (processedSnapshot != null) {
                            val queued = runCatching {
                                ioExecutor.execute {
                                    try {
                                        val bytes = runCatching { processedSnapshot.jpegBytes() }.getOrNull()
                                        if (bytes == null) {
                                            onStatus?.invoke("Failed to save photo")
                                        } else {
                                            if (formats.heif) saveHeifAsync(bytes, spec)
                                            if (formats.jpeg) saveJpegAsync(bytes, spec, exifShot)
                                        }
                                    } finally {
                                        finish()
                                    }
                                }
                            }
                            processedQueued = queued.isSuccess
                            queued.onFailure { onStatus?.invoke("Failed to queue photo save: ${it.message}") }
                        } else {
                            onStatus?.invoke("Failed to save photo")
                        }
                    } else {
                        onStatus?.invoke("Failed to save photo: no still image")
                    }
                }

                if (formats.dngRaw) {
                    if (raw != null) {
                        // DngCreator needs the live raw Image → must stay synchronous in this callback.
                        runCatching { saveDng(raw, rawChars, result, spec) }
                            .onSuccess { onStatus?.invoke("DNG saved") }
                            .onFailure { onStatus?.invoke("Failed to save DNG: ${it.message}") }
                    } else {
                        onStatus?.invoke("Failed to save DNG: no RAW")
                    }
                }
                if (!formats.wantsProcessedStill && !formats.dngRaw) onStatus?.invoke("No output selected")
                if (!processedQueued) finish()
            }

            override fun onError(t: Throwable) {
                onStatus?.invoke("Capture failed: ${t.message}")
                finish()
            }
        }
    }

    /**
     * Decode → center-crop to [aspectRatio] (HEIF only; [saveDng]'s RAW output always stays
     * full-frame) → rotate 180° → write HEIF, on [ioExecutor]. Publishes only on success; deletes
     * on any failure.
     */
    private fun saveHeifAsync(bytes: ByteArray, spec: ShotSpec) {
        var decoded: Bitmap? = null
        var cropped: Bitmap? = null
        var rotated: Bitmap? = null
        var uri: android.net.Uri? = null
        try {
            val d = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (d == null) { onStatus?.invoke("Failed to save HEIF: decode failed"); return }
            decoded = d
            val ar = spec.aspectRatio
            val base = if (ar != AspectRatio.W4_3) { // W4_3 = full sensor, no crop needed
                val c = centerCrop(d, ar.w, ar.h)
                cropped = c
                c
            } else d
            val r = rotateBitmap(base, spec.rotationDegrees)
            rotated = r
            val u = MediaStoreWriter.createPendingImage(
                context,
                spec.familyKey.displayName("heic"),
                "image/heic",
            )
            if (u == null) { onStatus?.invoke("Failed to save HEIF"); return }
            uri = u
            // The Setup quality slider governs BOTH still containers: HEIF here and the JPEG
            // re-encode in saveJpegAsync (it used to silently apply only to JPEG, leaving the
            // DEFAULT photo format pinned at the encoder's 95).
            val quality = spec.jpegQuality
            val wrote = MediaStoreWriter.openParcelFd(context, u, "rw")?.use { pfd ->
                HeifCapture.writeHeif(pfd.fileDescriptor, r, quality); true
            } ?: false
            if (!wrote) { MediaStoreWriter.delete(context, u); onStatus?.invoke("Failed to save HEIF"); return }
            if (!MediaStoreWriter.publish(context, u)) {
                MediaStoreWriter.delete(context, u)
                onStatus?.invoke("Failed to publish HEIF")
                return
            }
            onMediaSaved?.invoke(u, spec.captureId)
            onStatus?.invoke("Saved")
        } catch (e: OutOfMemoryError) {
            uri?.let { MediaStoreWriter.delete(context, it) }
            onStatus?.invoke("Failed to save HEIF: out of memory")
        } catch (t: Throwable) {
            uri?.let { MediaStoreWriter.delete(context, it) }
            onStatus?.invoke("Failed to save HEIF: ${t.message}")
        } finally {
            val rr = rotated
            val cc = cropped
            val dd = decoded
            if (rr != null && rr !== cc && rr !== dd) rr.recycle()
            if (cc != null && cc !== dd) cc.recycle()
            dd?.recycle()
        }
    }

    /**
     * Decode → center-crop to [aspectRatio] → rotate 180°(+device) → re-encode JPEG (at
     * [ManualControls.jpegQuality]), on [ioExecutor]. Uses the same processed-pixel pipeline as
     * [saveHeifAsync] — NOT the HEIF encoder — so JPEG and HEIF frame identically. Publishes only on
     * success; deletes on any failure.
     */
    private fun saveJpegAsync(bytes: ByteArray, spec: ShotSpec, exifShot: ExifShot) {
        var decoded: Bitmap? = null
        var cropped: Bitmap? = null
        var rotated: Bitmap? = null
        var uri: android.net.Uri? = null
        try {
            val d = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (d == null) { onStatus?.invoke("Failed to save JPEG: decode failed"); return }
            decoded = d
            val ar = spec.aspectRatio
            val base = if (ar != AspectRatio.W4_3) { // W4_3 = full sensor, no crop needed
                val c = centerCrop(d, ar.w, ar.h)
                cropped = c
                c
            } else d
            val r = rotateBitmap(base, spec.rotationDegrees)
            rotated = r
            val u = MediaStoreWriter.createPendingImage(
                context,
                spec.familyKey.displayName("jpg"),
                "image/jpeg",
            )
            if (u == null) { onStatus?.invoke("Failed to save JPEG"); return }
            uri = u
            val quality = spec.jpegQuality
            val wrote = MediaStoreWriter.openOutputStream(context, u)?.use { out ->
                r.compress(Bitmap.CompressFormat.JPEG, quality, out)
            } ?: false
            if (!wrote) { MediaStoreWriter.delete(context, u); onStatus?.invoke("Failed to save JPEG"); return }
            // Bitmap.compress strips all metadata, so stamp the exposure EXIF back before publishing
            // (best-effort — a failed EXIF write must never lose the image itself).
            runCatching { writeJpegExif(u, exifShot) }
            if (!MediaStoreWriter.publish(context, u)) {
                MediaStoreWriter.delete(context, u)
                onStatus?.invoke("Failed to publish JPEG")
                return
            }
            onMediaSaved?.invoke(u, spec.captureId)
            onStatus?.invoke("Saved")
        } catch (e: OutOfMemoryError) {
            uri?.let { MediaStoreWriter.delete(context, it) }
            onStatus?.invoke("Failed to save JPEG: out of memory")
        } catch (t: Throwable) {
            uri?.let { MediaStoreWriter.delete(context, it) }
            onStatus?.invoke("Failed to save JPEG: ${t.message}")
        } finally {
            val rr = rotated
            val cc = cropped
            val dd = decoded
            if (rr != null && rr !== cc && rr !== dd) rr.recycle()
            if (cc != null && cc !== dd) cc.recycle()
            dd?.recycle()
        }
    }

    /** Writes the RAW image as DNG synchronously (always full-frame; no [aspectRatio] crop applied). Publishes only on success; deletes then rethrows on failure. */
    private fun saveDng(
        raw: Image,
        chars: CameraCharacteristics,
        result: TotalCaptureResult,
        spec: ShotSpec,
    ) {
        val uri = MediaStoreWriter.createPendingImage(
            context,
            spec.familyKey.displayName("dng"),
            "image/x-adobe-dng",
        )
            ?: throw IllegalStateException("Failed to create MediaStore entry")
        try {
            val out = MediaStoreWriter.openOutputStream(context, uri)
                ?: throw IllegalStateException("Failed to open output stream")
            out.use {
                DngCapture.writeDng(it, raw, chars, result, exifOrientationFor(spec.rotationDegrees))
            }
            if (!MediaStoreWriter.publish(context, uri)) {
                throw IllegalStateException("Failed to publish DNG")
            }
            onRawSaved?.invoke(uri, spec.captureId)
        } catch (t: Throwable) {
            MediaStoreWriter.delete(context, uri)
            throw t
        }
    }

    // ---- Video ----

    private fun currentAcceptedRecordingSession(): AcceptedCameraSession? = synchronized(this) {
        acceptedCameraSession?.takeIf { accepted ->
            acceptedCameraSessionIsCurrent(
                currentController = controller,
                acceptedController = accepted.controller,
                currentSessionGeneration = cameraSessionGeneration.get(),
                acceptedSessionGeneration = accepted.sessionGeneration,
                cameraReady = cameraReady,
                paused = paused,
            )
        }
    }

    private fun ownsAcceptedRecordingSession(expected: AcceptedCameraSession): Boolean =
        acceptedCameraSession === expected && acceptedCameraSessionIsCurrent(
            currentController = controller,
            acceptedController = expected.controller,
            currentSessionGeneration = cameraSessionGeneration.get(),
            acceptedSessionGeneration = expected.sessionGeneration,
            cameraReady = cameraReady,
            paused = paused,
        )

    fun startRecording(recordAudio: Boolean): Boolean {
        val acceptedSession = currentAcceptedRecordingSession()
        if (acceptedSession == null) {
            onStatus?.invoke("Camera reconfiguring")
            return false
        }
        if (videoFrameRate !in VideoFrameRate.availableFor(caps, videoSize, videoCodec)) {
            onStatus?.invoke("Selected FPS is unavailable")
            return false
        }
        // A just-stopped clip's async teardown still owns the mic (and the encoder pipeline is being
        // finalized) — refuse until finishRecording completes rather than starting a second recorder
        // whose AudioRecord init would fail (silent video-only clip).
        if (recorderTeardownInFlight) {
            onStatus?.invoke("Finalizing previous clip")
            return false
        }
        // The recorder owns the mic — stop the standby level tap first and wait (bounded) for its
        // release CONFIRMATION, not just a timed thread join: the latch counts down only after
        // AudioRecord.release() ran, so the single-mic invariant holds whenever the wait succeeds.
        // A timed-out owner remains the mic owner: fail this REC attempt rather than letting a late
        // meter thread acquire AudioRecord after recorder admission and recreating dual ownership.
        val audioClaim = standbyMeterOwnership.beginRecording()
        if (!audioClaim.admitted) return false
        val meterReleased = audioClaim.release?.let {
            runCatching { it.await(400, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrDefault(false)
        } ?: true
        if (!meterReleased) {
            abortRecordingStart()
            onStatus?.invoke("Audio meter is stopping")
            return false
        }
        if (recorder != null) {
            abortRecordingStart()
            return false
        }
        if (currentAcceptedRecordingSession() !== acceptedSession) {
            abortRecordingStart()
            onStatus?.invoke("Camera reconfiguring")
            return false
        }
        val recordingCaptureId = captureSeq.incrementAndGet()
        val familyKey = CaptureFamilyKey(
            media = CaptureFamilyMedia.VIDEO,
            capturedAtEpochMillis = System.currentTimeMillis(),
            sequence = recordingCaptureId.toLong(),
        )
        val name = familyKey.displayName("mp4")
        val uri = MediaStoreWriter.createPendingVideo(context, name, "video/mp4") ?: run {
            abortRecordingStart()
            return false
        }
        val size = videoSize
        val codec = videoCodec
        val rate = videoFrameRate
        // High-speed (≥120 fps via the constrained session) tells the encoder it is fed faster than
        // real-time via KEY_CAPTURE_RATE; a regular clip leaves it 0. (High-speed is disabled — it
        // SIGABRTs the HAL — so desiredHighSpeedFps() is always 0 in practice.)
        val captureRate = if (desiredHighSpeedFps() != 0) rate.encoderRate else 0.0
        // AVC is 8-bit SDR only: force the GL color curve to SDR (no HLG/Log). HEVC/APV keep the
        // 10-bit HLG/Log path. With HAL-native log active the stream is ALREADY log-encoded by the
        // ISP — GL must pass it through untouched or the curve would be applied twice.
        // (Resolution changes after this point stream the old size until reopen.)
        val glTransfer = when {
            vendorLogMode != VendorLogMode.OFF -> null
            codec == VideoCodec.HEVC || codec == VideoCodec.APV -> transfer
            else -> null
        }
        // File color tags: when the HAL emits O-Log2 the container MUST be tagged as the LOG profile
        // (BT.2020 full-range, SDR-class transfer) regardless of the TF chip, so players don't
        // HDR-tone-map it and OPPO's O-Log2 LUTs round-trip. Otherwise use the selected transfer on
        // the 10-bit paths (HEVC/APV); AVC is always SDR.
        val fileTransfer = when {
            vendorLogMode != VendorLogMode.OFF -> ColorTransfer.LOG
            codec == VideoCodec.HEVC || codec == VideoCodec.APV -> transfer
            else -> ColorTransfer.SDR
        }
        val rec = VideoRecorder(context)
        val earlyFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val encoderAttachResult = java.util.concurrent.atomic.AtomicReference<Result<Unit>?>()
        val encoderAttachDelivered = java.util.concurrent.atomic.AtomicBoolean(false)
        val reportRecorderFailure: (Throwable) -> Unit = { failure ->
            // Both codec-drain and GL-output failures can beat recorder publication. Retain the
            // first one, then replay through the same identity-owned claim after admission.
            earlyFailure.compareAndSet(null, failure)
            handleUnexpectedRecorderFailure(rec, uri, recordingCaptureId, failure)
        }
        fun deliverEncoderAttach(result: Result<Unit>) {
            val owned = synchronized(recorderOwnershipLock) { recorder === rec }
            if (!owned || !encoderAttachDelivered.compareAndSet(false, true)) return
            result.fold(
                onSuccess = { onRecordingStarted?.invoke() },
                onFailure = { failure ->
                    handleUnexpectedRecorderFailure(rec, uri, recordingCaptureId, failure)
                },
            )
        }
        // Physical device orientation at record start → muxer rotation hint so a landscape-held clip
        // plays upright (GL only bakes the afocal 180°; see VideoRecorder.start).
        val orientationHint = gyro.currentDeviceOrientation()
        val surface = rec.start(
            uri, size, rate.encoderRate, captureRate, bitRateFor(size, rate),
            fileTransfer, codec, recordAudio, audioGain, orientationHint,
            audioScene, controls.zoomRatio, audioInputPreference,
            onRoute = { route -> onAudioRoute?.invoke(route) },
            onLevel = { lvl -> onAudioLevel?.invoke(lvl) },
            onFailure = reportRecorderFailure,
        )
        if (surface == null) {
            // Encoder/muxer failed to configure; drop the pending MediaStore row we created so it
            // doesn't linger as a 0-byte orphan (VideoRecorder.start already released its own half).
            MediaStoreWriter.delete(context, uri)
            abortRecordingStart()
            onStatus?.invoke("REC failed"); return false
        }
        gl.setTransfer(glTransfer)
        // Queue EGL attach before publishing recorder ownership. Any failure is retained and replayed
        // after admission, while stop/failure detach is necessarily queued behind this attach.
        gl.setEncoderOutput(
            surface,
            size.width,
            size.height,
            onRuntimeFailure = reportRecorderFailure,
        ) { result ->
            // GlPipeline guarantees exactly-once result delivery, so a plain atomic publication is
            // sufficient and avoids identity-CAS semantics on Kotlin's boxed Result value class.
            encoderAttachResult.set(result)
            deliverEncoderAttach(result)
        }
        val admitted = synchronized(this) {
            if (!ownsAcceptedRecordingSession(acceptedSession)) {
                false
            } else {
                synchronized(recorderOwnershipLock) {
                    if (recorder != null) {
                        false
                    } else {
                        recorder = rec
                        activeRecordingUri = uri
                        activeRecordingCaptureId = recordingCaptureId
                        true
                    }
                }
            }
        }
        if (!admitted) {
            // Camera failure or a new session won admission. The prepared codec still needs the same
            // ordered EGL-detach/finalize path before its MediaStore row can converge.
            detachAndFinalizeRecording(rec, uri, recordingCaptureId)
            gl.setTransfer(transfer)
            onStatus?.invoke("Camera reconfiguring")
            return false
        }
        // A drain thread can fail before start() returns and before recorder ownership is published.
        // Re-run the same identity-guarded claim after publication so that early failure is not lost.
        earlyFailure.get()?.let { handleUnexpectedRecorderFailure(rec, uri, recordingCaptureId, it) }
        encoderAttachResult.get()?.let(::deliverEncoderAttach)
        if (recorder !== rec) return false
        return true
    }

    private fun abortRecordingStart() {
        standbyMeterOwnership.abortRecording()
        if (!paused) startStandbyAudioMonitor(updateIntent = false)
    }

    fun stopRecording() {
        val (rec, uri, captureId) = synchronized(recorderOwnershipLock) {
            val owned = recorder ?: return
            recorder = null
            val ownedUri = activeRecordingUri
            val ownedCaptureId = activeRecordingCaptureId
            activeRecordingUri = null
            Triple(owned, ownedUri, ownedCaptureId)
        }
        // ORDERED teardown: finishRecording (rec.stop() → codec release; joins the drain threads up
        // to seconds, so it stays OFF the main thread on ioExecutor) dispatches from the completion
        // callback, which runs on the GL thread only AFTER the encoder EGL surface is actually
        // cleared. The old fire-and-forget post had no happens-before edge with the independent
        // ioExecutor — a queued drawFrame could still makeCurrent() the encoder surface while the
        // codec that owns it was being released (uncaught EGL failure on the GL thread).
        detachAndFinalizeRecording(rec, uri, captureId)
        // Restore the preview curve startRecording overrode: an AVC recording pushes null (SDR) into
        // GL and nothing else re-applies the LOG/HLG preview render until the next transfer change.
        gl.setTransfer(transfer)
    }

    // True from stop/pause dispatching the async rec.stop() until its AudioRecord is actually
    // released: `recorder == null` alone says nothing about the mic, and the standby meter starting
    // in that window would violate the one-AudioRecord invariant (its init would fail, or worse,
    // steal the route from the finalizing clip).
    @Volatile private var recorderTeardownInFlight = false

    private fun handleUnexpectedRecorderFailure(
        rec: VideoRecorder,
        uri: android.net.Uri,
        captureId: Int,
        failure: Throwable,
    ) {
        val claimed = synchronized(recorderOwnershipLock) {
            if (recorder !== rec) {
                false
            } else {
                recorder = null
                if (activeRecordingUri == uri) activeRecordingUri = null
                true
            }
        }
        if (!claimed) return
        detachAndFinalizeRecording(rec, uri, captureId)
        onStatus?.invoke("Recording stopped: ${failure.message ?: "encoder error"}")
        onRecordingTerminated?.invoke(failure)
        gl.setTransfer(transfer)
    }

    private fun detachAndFinalizeRecording(rec: VideoRecorder, uri: android.net.Uri?, captureId: Int) {
        recorderTeardownInFlight = true
        val completed = java.util.concurrent.CountDownLatch(1)
        recorderFinalizationLatch = completed
        val dispatched = java.util.concurrent.atomic.AtomicBoolean(false)
        val dispatchFinalization: () -> Unit = dispatch@{
            if (!dispatched.compareAndSet(false, true)) return@dispatch
            val task = Runnable {
                try {
                    finishRecording(rec, uri, captureId)
                } finally {
                    completed.countDown()
                }
            }
            val accepted = runCatching { recorderExecutor.execute(task) }.isSuccess
            if (!accepted) {
                // Executor shutdown/rejection must not strand MediaStore or the teardown latch.
                runCatching { Thread(task, "record-finalize-fallback").start() }
                    .onFailure { task.run() }
            }
        }
        gl.setEncoderOutput(null, 0, 0) { result ->
            result.fold(
                onSuccess = { dispatchFinalization() },
                onFailure = { failure ->
                    // A failed unbind/destroy result is NOT permission to release MediaCodec. Reset
                    // the EGL owner first; only the strict checked resource-release signal (not the
                    // bounded stop notification) can become the fallback finalization boundary.
                    recoverFromEncoderDetachFailure(failure, dispatchFinalization)
                },
            )
        }
    }

    /** Terminal EGL reset used only when the checked encoder detach transition itself fails. */
    private fun recoverFromEncoderDetachFailure(failure: Throwable, onReleased: () -> Unit) {
        onStatus?.invoke("Renderer reset: ${failure.message ?: "encoder detach failed"}")
        invalidateCameraReady()
        val failedController = synchronized(this) {
            val current = controller
            controller = null
            current
        }
        val reset = Runnable {
            failedController?.close()
            gl.stop(onResourcesReleased = afterResourcesReleased(onReleased))
        }
        if (runCatching { setupExecutor.execute(reset) }.isFailure) {
            // Release may already own the setup executor. A fresh helper keeps CameraController.close
            // off the GL callback thread while preserving stop-before-codec-finalize ordering.
            runCatching { Thread(reset, "egl-reset-fallback").start() }
                .onFailure {
                    runCatching { failedController?.close() }
                    gl.stop(onResourcesReleased = afterResourcesReleased(onReleased))
                }
        }
    }

    private fun afterResourcesReleased(onReleased: () -> Unit): () -> Unit = {
        synchronized(this) {
            started = false
            starting = false
            glInputPending = false
        }
        onReleased()
        val liveSurface = previewSurface
        if (!paused && terminalAcquisitionGate.isOpen() && liveSurface != null) {
            onPreviewSurfaceAvailable(liveSurface, previewSurfaceW, previewSurfaceH)
        }
    }

    private fun finishRecording(rec: VideoRecorder, uri: android.net.Uri?, captureId: Int) {
        try {
            val result = runCatching { rec.stop() }
                .getOrElse { VideoRecorder.StopResult(saved = false, error = it) }
            if (result.saved && uri != null) {
                // Surface only a fully finalized, published clip to the review UI.
                onMediaSaved?.invoke(uri, captureId)
                onStatus?.invoke("Video saved")
            } else {
                onStatus?.invoke("Video save failed")
            }
        } finally {
            recorderTeardownInFlight = false
            standbyMeterOwnership.finishRecording()
            if (!paused) startStandbyAudioMonitor(updateIntent = false)
        }
    }

    /** Releases the camera + gyro for backgrounding without tearing down the GL pipeline or start state. */
    fun pause() {
        paused = true
        coldStartRetryGate.cancel()
        standbyMeterOwnership.disable()
        invalidateCameraReady()
        // A backgrounded timelapse can't capture anyway (controller is nulled below, so every tick
        // no-ops) — stop it outright rather than silently resuming mid-sequence with a gap.
        stopTimelapse()
        // Finalize an in-flight recording OFF the main thread: rec.stop() joins the drain threads (up
        // to a few seconds) and calling it inline on onStop risks an ANR. Clear the encoder EGL first
        // so GL stops drawing into the input surface before the codec releases it.
        val ownedRecording = synchronized(recorderOwnershipLock) {
            recorder?.let { owned ->
                recorder = null
                val ownedUri = activeRecordingUri
                val ownedCaptureId = activeRecordingCaptureId
                activeRecordingUri = null
                Triple(owned, ownedUri, ownedCaptureId)
            }
        }
        val rec = ownedRecording?.first
        val pausedClipUri = ownedRecording?.second
        val pausedClipCaptureId = ownedRecording?.third ?: 0
        if (rec != null) {
            // Same ordered teardown as stopRecording: release the codec only after the GL thread
            // confirmed the encoder EGL surface is cleared.
            detachAndFinalizeRecording(rec, pausedClipUri, pausedClipCaptureId)
            gl.setTransfer(transfer) // restore the preview curve startRecording overrode (AVC → null)
        }
        gyro.stop()
        // Close OFF the main thread: close() blocks until the HAL device releases (bounded 1.5 s
        // join — see CameraController.close), and onStop already competes with other teardown work
        // inside the ANR budget. setupExecutor serializes this close ahead of any queued/subsequent
        // reopen, so ordering is preserved.
        val ctrl = synchronized(this) {
            val current = controller
            controller = null
            current
        }
        if (ctrl != null) setupExecutor.execute { ctrl.close() }
    }

    /** Reopens the camera after [pause], reusing the existing GL input surface and start state. */
    fun resume() {
        paused = false
        coldStartRetryGate.cancel()
        gyro.start()
        if (!started) {
            val surface = previewSurface ?: return
            onPreviewSurfaceAvailable(surface, previewSurfaceW, previewSurfaceH)
            return
        }
        // Serialized on setupExecutor like every other open path (the GL-start continuation,
        // reopenForSession, setCameraOverride). resume() used to call openCamera directly on the
        // main thread — the one remaining unserialized open: it raced a queued reopen's
        // `controller = null` + open (both threads could observe null and double-open, contending
        // for the HAL device) and paid the open's Binder IPCs on the UI thread.
        setupExecutor.execute {
            if (paused) return@execute
            if (controller != null) return@execute
            // Re-resolve selection/caps/stream geometry from CURRENT desired fields. openCamera(input)
            // reused the pre-pause selection and could mark a new mode/lens generation Ready on the
            // outgoing camera when its queued intent had observed paused and returned.
            // Resume always carries an ownership token. Without one, a lens/TELE tap during the
            // dual-open wait could let this older attempt publish outgoing caps/Ready under the
            // newer generation even when there was no pre-pause rollback baseline.
            val desired = currentOpticsReconfiguration()
            reconfigureCamera(desired.overrideId, desired.transaction)
        }
    }

    /**
     * Deletes leftover pending MediaStore entries from a prior crash / force-kill (a recording whose
     * MediaMuxer was never stopped → corrupt, invisible file). Runs on the setup thread so it never
     * blocks the UI. Call once on launch.
     */
    fun cleanupOrphans() {
        setupExecutor.execute { runCatching { MediaStoreWriter.cleanupOrphanedPending(context) } }
    }

    fun currentRollDegrees(): Float = gyro.currentRollDegrees()

    /** Discrete physical device orientation (0/90/180/270) from gravity, for auto-rotating overlays. */
    fun currentDeviceOrientation(): Int = gyro.currentDeviceOrientation()

    fun setPunchIn(enabled: Boolean) {
        rendererConfig.update { it.copy(punchIn = enabled) }
        gl.setPunchIn(enabled)
    }

    /** Breaks the engine→ViewModel callback graph before asynchronous owner teardown begins. */
    fun detachCallbacks() {
        onCameraReadyChange = null
        onOpticsRollback = null
        onAfIndication = null
        onStatus = null
        onCapsReady = null
        onVideoSizeChosen = null
        onPreviewAspect = null
        onAnalysis = null
        onAudioLevel = null
        onAudioRoute = null
        onRecordingStarted = null
        onRecordingTerminated = null
        onExposureInfo = null
        onFocusDistance = null
        onMediaSaved = null
        onRawSaved = null
    }

    fun release() {
        // Terminal before state/executor teardown: either an in-flight acquisition completes before
        // this close (and the stop below owns it), or close wins and no later task can call gl.start.
        terminalAcquisitionGate.close()
        paused = true
        started = false
        glInputPending = false
        coldStartRetryGate.cancel()
        invalidateCameraReady()
        stopTimelapse()
        standbyMeterOwnership.disable()
        // Preserve the same GL-detach-before-codec-release order during ViewModel teardown. Await
        // the exactly-once finalization latch before dropping GL/executor ownership; pause() usually
        // started this already, while the direct branch covers unusual unbalanced lifecycle exits.
        synchronized(recorderOwnershipLock) {
            recorder?.let { rec ->
                recorder = null
                val uri = activeRecordingUri
                val captureId = activeRecordingCaptureId
                activeRecordingUri = null
                detachAndFinalizeRecording(rec, uri, captureId)
            }
        }
        recorderFinalizationLatch?.let {
            runCatching { it.await(RECORDER_FINALIZE_RELEASE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }
        }
        gyro.stop()
        val ctrl = synchronized(this) {
            val current = controller
            controller = null
            current
        }
        ctrl?.close()
        gl.stop()
        starting = false
        setupExecutor.shutdown()
        ioExecutor.shutdown()
        recorderExecutor.shutdown()
        timelapseScheduler.shutdown()
    }

    // ---- Helpers ----

    /**
     * Total rotation (deg, clockwise) to apply to a still so it saves upright: the tele lens's
     * sensor orientation + the afocal 180° (teleconverter only) + the phone's physical orientation
     * from gravity — so a shot framed while holding the phone in landscape saves landscape-correct,
     * even though the UI is portrait-locked. Matches the GL preview rotation at 0° device tilt.
     */
    private fun captureRotationDegrees(): Int {
        val c = caps ?: return 0
        return RotationMath.captureRotationDegrees(c.sensorOrientation, teleconverterMode, gyro.currentDeviceOrientation())
    }

    private fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return src
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** Maps a clockwise rotation (0/90/180/270) to the matching EXIF/TIFF orientation tag for DNG. */
    private fun exifOrientationFor(degrees: Int): Int = RotationMath.exifOrientationFor(degrees)

    /** Returns the largest [ratioW]:[ratioH] rect centered within [src], cropped out of it (HEIF + JPEG paths). */
    private fun centerCrop(src: Bitmap, ratioW: Int, ratioH: Int): Bitmap {
        val (x, y, cropW, cropH) = centerCropBox(src.width, src.height, ratioW, ratioH)
        return Bitmap.createBitmap(src, x, y, cropW, cropH)
    }

    /**
     * The default recording size for the current lens: the largest matching-aspect SurfaceTexture
     * size the camera reports — 4:3 when [openGate] (full sensor readout), else 16:9. Capped at 3840
     * wide (this device's cameras top out at 4K/4096 for the recording surface; no camera exposes 8K
     * video here). Falls back through the caps lists so it never returns an unsupported size.
     */
    private fun chooseVideoSize(sel: TeleSelection): Size {
        val auto = chooseStreamSize(sel, fourByThree = openGate)
        val req = requestedVideoSize ?: return auto
        // Honor a user/restored pick only while the current caps actually offer it for the active
        // aspect; anything else (stale persisted size, openGate flip) degrades to the auto choice.
        val list = caps?.let { if (openGate) it.openGateVideoSizes else it.availableVideoSizes }
        return if (list != null && req in list) req else auto
    }

    /**
     * The camera preview (SurfaceTexture) stream size. VIDEO mode: identical to [videoSize] — the
     * SurfaceTexture feeds the encoder, so the two must agree. PHOTO mode: the largest 4:3 stream,
     * so the viewfinder previews the SAME full-sensor field the still capture gets (a 16:9 stream
     * crops the sensor vertically and mis-frames every photo).
     */
    private fun choosePreviewStreamSize(sel: TeleSelection): Size =
        if (videoMode) chooseVideoSize(sel) else chooseStreamSize(sel, fourByThree = true)

    /** Pushes the DISPLAYED preview aspect (post sensor-orientation W/H swap) to the UI. */
    private fun emitPreviewAspect(generation: Long = opticsIntentGeneration.get()) {
        val s = previewStreamSize
        // The ~90° sensor orientation means the SurfaceTexture transform swaps the shown W/H (the
        // afocal 180° doesn't change the swap). Same rule FlipRenderer uses for its aspect choice.
        val swapped = ((caps?.sensorOrientation ?: 90) % 180) == 90
        val aspect = if (swapped) s.height.toFloat() / s.width else s.width.toFloat() / s.height
        onPreviewAspect?.invoke(aspect, generation)
    }

    private fun chooseStreamSize(sel: TeleSelection, fourByThree: Boolean): Size {
        val c = caps
        if (c != null) {
            val list = if (fourByThree) c.openGateVideoSizes else c.availableVideoSizes
            (list.firstOrNull { it.width <= 3840 } ?: list.firstOrNull())?.let { return it }
        }
        // Fallback: query directly (pre-caps or empty list).
        val chars = runCatching {
            manager.getCameraCharacteristics(sel.physicalId ?: sel.logicalId)
        }.getOrNull() ?: return Size(1920, 1080)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(1920, 1080)
        val sizes = map.getOutputSizes(SurfaceTexture::class.java) ?: return Size(1920, 1080)
        // One selection rule with CameraCaps.read's lists (pickStreamSize) — the fallback used to
        // re-implement the aspect/cap/largest rule inline, so a policy change had to touch both.
        return pickStreamSize(sizes.map { it.width to it.height }, capWidth = 3840, fourByThree = fourByThree)
            ?.let { (w, h) -> Size(w, h) }
            ?: Size(1920, 1080)
    }

    // Resolved encoder bitrate (bits/s) for the current level, size, true frame rate, and codec.
    // effectiveBpp scales the level up for the all-intra APV codec.
    private fun bitRateFor(size: Size, rate: VideoFrameRate): Int =
        videoBitRate(size.width, size.height, rate.encoderRate, effectiveBpp(bitrateLevel, videoCodec), videoCodec)

    // Per-SHUTTER-PRESS counter (one id shared by every container a capture produces), so the review
    // UI and durable filename can group the HEIF/JPEG with its DNG sibling.
    private val captureSeq = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Stamps ISO / exposure / 35mm focal / device EXIF onto a just-written JPEG (pending, rw).
     * [iso]/[expNs] come from the shot's own TotalCaptureResult, snapshotted in the capture
     * callback — the live controller.lastIso/lastExposureNs may already describe a LATER frame by
     * the time this runs on the io thread.
     */
    /**
     * Everything the JPEG EXIF stamp needs, snapshotted AT THE SHOT (capture result + the controls
     * and optics active for that frame). Field set mirrors the stock camera's 3× reference sample
     * (FNumber/FocalLength/35 mm/LensModel/APEX values/metering/flash/program/zoom).
     */
    data class ExifShot(
        val iso: Int,
        val expNs: Long,
        val lensFocalMm: Float,
        val lensApertureF: Float,
        val focal35mm: Int,
        val digitalZoom: Float,
        val evBiasStops: Float,
        val meteringMode: MeteringMode,
        val flashFired: Boolean,
        val exposureProgram: Int, // EXIF: 1=manual, 2=program, 4=shutter priority
        val manualExposure: Boolean,
        val manualWb: Boolean,
        val lensModel: String,
        val takenAtMs: Long,
    )

    /**
     * Builds the EXIF snapshot for THIS shot. On the logical camera the active PHYSICAL lens comes
     * from the capture result (LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) so FocalLength/FNumber/
     * LensModel describe the lens that actually took the frame, exactly like the stock camera.
     */
    private fun exifShotOf(result: android.hardware.camera2.TotalCaptureResult, spec: ShotSpec): ExifShot {
        val c = spec.controls
        val base = spec.caps
        // Active physical lens (logical camera) → its own optics; standalone cameras are themselves.
        val activeId = runCatching {
            result.get(android.hardware.camera2.CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
        }.getOrNull()
        // Deliberately cache-only: CameraManager/CameraCaps.read on this callback would hold the
        // live Image and serialize CameraService IPC with capture-result/image delivery.
        val optics = resolveLensExifMetadata(activeId, lensExifCache, base?.lensExifMetadata())
        val lensFocal = optics?.focalLengthMm ?: 0f
        val lensF = optics?.apertureF ?: 0f
        val lensEquiv = optics?.equivalentFocalMm ?: 0f
        // Effective 35 mm focal: what the FRAME shows — unified zoom on the logical camera already
        // lands on the active lens (equiv × leftover digital), TELE multiplies the converter.
        val baseEquiv = base?.equivalentFocalMm ?: 0f
        // TELE uses the NOMINAL 300 mm base so EXIF matches the OSD/pill marks exactly
        // (13×→300, 30×→690, 60×→1380); the caps-measured 69.4 mm equiv would read 680 at 30×.
        val appliedZoom = result.get(android.hardware.camera2.CaptureResult.CONTROL_ZOOM_RATIO)
            ?: c.zoomRatio
        val eff = if (spec.teleconverter) {
            300f * appliedZoom.coerceAtLeast(1f)
        } else {
            baseEquiv * appliedZoom.coerceAtLeast(0.01f)
        }
        // Digital portion of the zoom: in TELE the converter's 4.286× is OPTICAL (glass), so only
        // the user's 1-10× ratio is digital; otherwise it's effective ÷ the active lens's equiv.
        val digital = if (spec.teleconverter) {
            appliedZoom.coerceAtLeast(1f)
        } else if (lensEquiv > 0f) {
            (eff / lensEquiv).coerceAtLeast(1f)
        } else 1f
        val evStep = base?.evStep?.let {
            if (it.denominator == 0) 1f / 3f else it.numerator.toFloat() / it.denominator
        } ?: (1f / 3f)
        val activeLensId = activeId ?: spec.selection?.let { it.physicalId ?: it.logicalId }
        val lensName = when (activeLensId) {
            "3" -> "ultra-wide"
            "2" -> "wide"
            "4" -> "tele"
            "5" -> "periscope tele"
            else -> "tele"
        }
        // Marketing focal like the stock sample ("70mm", not the computed 69.4): the lens band's
        // nominal equiv. f-stop truncated to one decimal (stock: "f/2.2" for the 2.26 aperture).
        val marketingMm = when (activeLensId) {
            "3" -> LensChoice.ULTRAWIDE.targetEquivMm
            "2" -> LensChoice.MAIN.targetEquivMm
            "4" -> LensChoice.TELE3X.targetEquivMm
            "5" -> LensChoice.TELE10X.targetEquivMm
            else -> lensEquiv
        }
        val fTrunc = kotlin.math.floor(lensF * 10f) / 10f
        val modelLabel = "OPPO Find X9 Ultra $lensName camera " +
            "${Math.round(marketingMm)}mm f/${"%.1f".format(java.util.Locale.US, fTrunc)}"
        return ExifShot(
            iso = result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY) ?: 0,
            expNs = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
            lensFocalMm = lensFocal,
            lensApertureF = lensF,
            // TELE reads a clean "300", not 297 — same nearest-10 rounding the OSD applies.
            focal35mm = if (spec.teleconverter) (Math.round(eff / 10f) * 10) else Math.round(eff),
            digitalZoom = digital,
            evBiasStops = (result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION)
                ?: c.exposureCompensation) * evStep,
            meteringMode = c.meteringMode,
            flashFired = result.get(android.hardware.camera2.CaptureResult.FLASH_STATE) ==
                android.hardware.camera2.CaptureResult.FLASH_STATE_FIRED,
            exposureProgram = when (c.exposureMode) {
                ExposureMode.MANUAL -> 1
                ExposureMode.SHUTTER -> 4
                else -> 2
            },
            manualExposure = c.exposureMode == ExposureMode.MANUAL,
            manualWb = c.wbMode != WbMode.AUTO,
            lensModel = modelLabel,
            takenAtMs = spec.takenAtMs,
        )
    }

    private fun writeJpegExif(uri: android.net.Uri, shot: ExifShot) {
        MediaStoreWriter.openParcelFd(context, uri, "rw")?.use { pfd ->
            val exif = androidx.exifinterface.media.ExifInterface(pfd.fileDescriptor)
            fun set(tag: String, value: String) = exif.setAttribute(tag, value)

            if (shot.iso > 0) set(androidx.exifinterface.media.ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, shot.iso.toString())
            if (shot.expNs > 0) {
                val sec = shot.expNs / 1_000_000_000.0
                set(androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_TIME, sec.toString())
                // APEX shutter speed = -log2(t), rational, matching the stock sample (6.908 at 1/120).
                val apex = -Math.log(sec) / Math.log(2.0)
                set(
                    androidx.exifinterface.media.ExifInterface.TAG_SHUTTER_SPEED_VALUE,
                    "${Math.round(apex * 1000)}/1000",
                )
            }
            if (shot.lensApertureF > 0f) {
                set(androidx.exifinterface.media.ExifInterface.TAG_F_NUMBER, shot.lensApertureF.toString())
                // APEX aperture = 2·log2(F) (stock: 2.35 at f/2.2).
                val apexAv = 2.0 * Math.log(shot.lensApertureF.toDouble()) / Math.log(2.0)
                set(
                    androidx.exifinterface.media.ExifInterface.TAG_APERTURE_VALUE,
                    "${Math.round(apexAv * 100)}/100",
                )
                set(
                    androidx.exifinterface.media.ExifInterface.TAG_MAX_APERTURE_VALUE,
                    "${Math.round(apexAv * 100)}/100",
                )
            }
            if (shot.lensFocalMm > 0f) {
                // Real lens focal (20.1 mm on the 3×), rational millimeters like the stock sample.
                set(
                    androidx.exifinterface.media.ExifInterface.TAG_FOCAL_LENGTH,
                    "${Math.round(shot.lensFocalMm * 1000)}/1000",
                )
            }
            if (shot.focal35mm > 0) {
                set(
                    androidx.exifinterface.media.ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
                    shot.focal35mm.toString(),
                )
            }
            set(
                androidx.exifinterface.media.ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
                "${Math.round(shot.digitalZoom * 10000)}/10000",
            )
            // EV bias in sixths, the stock sample's denominator (0/6).
            set(
                androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
                "${Math.round(shot.evBiasStops * 6)}/6",
            )
            set(
                androidx.exifinterface.media.ExifInterface.TAG_METERING_MODE,
                when (shot.meteringMode) {
                    MeteringMode.MATRIX -> "5" // pattern
                    MeteringMode.CENTER -> "2" // center-weighted (the stock default)
                    MeteringMode.SPOT -> "3"
                },
            )
            // 0x1 = fired; 0x10 = "did not fire, compulsory off" (the stock sample's value).
            set(androidx.exifinterface.media.ExifInterface.TAG_FLASH, if (shot.flashFired) "1" else "16")
            set(androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_PROGRAM, shot.exposureProgram.toString())
            set(androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_MODE, if (shot.manualExposure) "1" else "0")
            set(androidx.exifinterface.media.ExifInterface.TAG_WHITE_BALANCE, if (shot.manualWb) "1" else "0")
            set(androidx.exifinterface.media.ExifInterface.TAG_LENS_MODEL, shot.lensModel)
            set(androidx.exifinterface.media.ExifInterface.TAG_COLOR_SPACE, "1") // sRGB

            val dt = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date(shot.takenAtMs))
            val offset = java.text.SimpleDateFormat("XXX", java.util.Locale.US)
                .format(java.util.Date(shot.takenAtMs))
            set(androidx.exifinterface.media.ExifInterface.TAG_DATETIME, dt)
            set(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL, dt)
            set(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_DIGITIZED, dt)
            set(androidx.exifinterface.media.ExifInterface.TAG_OFFSET_TIME, offset)
            set(androidx.exifinterface.media.ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset)
            // Pixels are rotated upright before encode — the orientation tag must say NORMAL,
            // not the invalid 0 exifinterface leaves when the tag was never present.
            set(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, "1")
            // The stock sample writes the MARKET name, not the ro.product.model code (PMA110).
            set(androidx.exifinterface.media.ExifInterface.TAG_MAKE, "OPPO")
            set(androidx.exifinterface.media.ExifInterface.TAG_MODEL, "OPPO Find X9 Ultra")
            exif.saveAttributes()
        }
    }

    // ---- Standby audio meter (Sony-style pre-roll level check) --------------------------------
    // A levels-only mic tap that feeds [onAudioLevel] while video mode is ARMED but not recording,
    // so input levels are visible before rolling. Stops itself the moment a real recording starts
    // (the recorder owns the mic) or the flag drops.
    private val standbyMeterOwnership =
        StandbyMeterOwnership<java.util.concurrent.CountDownLatch>()

    fun setStandbyAudioMonitor(enabled: Boolean) {
        if (!enabled) {
            standbyMeterOwnership.disable()
            return
        }
        startStandbyAudioMonitor(updateIntent = true)
    }

    /** Starts only if the latest intent still wants metering; internal retries never re-enable it. */
    private fun startStandbyAudioMonitor(updateIntent: Boolean) {
        val canStart = !paused && recorder == null && !recorderTeardownInFlight &&
            context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        // reserve publishes the immutable owner + release latch before Thread.start. Concurrent UI
        // refresh/finalizer calls therefore see one owner, and REC can await that exact generation.
        val createRelease = { java.util.concurrent.CountDownLatch(1) }
        val owner = if (updateIntent) {
            standbyMeterOwnership.reserve(
                enabled = true,
                canStart = canStart,
                createRelease = createRelease,
            )
        } else {
            standbyMeterOwnership.reserveCurrentWanted(
                canStart = canStart,
                createRelease = createRelease,
            )
        } ?: return
        val t = Thread({
            var audioRecord: android.media.AudioRecord? = null
            try {
                // Reservation does not imply start admission: REC may have claimed ownership while
                // this thread was waiting to run.
                if (!standbyMeterOwnership.ownsAndWants(owner)) return@Thread
                val sampleRate = 48_000
                val minBuf = android.media.AudioRecord.getMinBufferSize(
                    sampleRate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT,
                )
                if (minBuf <= 0) return@Thread
                val rec = runCatching {
                    android.media.AudioRecord(
                        android.media.MediaRecorder.AudioSource.CAMCORDER, sampleRate,
                        android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, minBuf * 2,
                    )
                }.getOrNull() ?: return@Thread
                audioRecord = rec
                if (rec.state != android.media.AudioRecord.STATE_INITIALIZED) return@Thread
                if (!standbyMeterOwnership.ownsAndWants(owner)) return@Thread
                if (runCatching { rec.startRecording() }.isFailure) return@Thread
                val buf = ShortArray(2048)
                var lastEmit = 0L
                while (standbyMeterOwnership.ownsAndWants(owner) && recorder == null) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    val now = System.nanoTime()
                    if (now - lastEmit < 100_000_000L) continue // ~10 Hz is plenty for a meter
                    lastEmit = now
                    var sum = 0.0
                    for (i in 0 until n) { val v = buf[i].toDouble(); sum += v * v }
                    // 32768 = signed 16-bit full scale, matching VideoRecorder's recording meter so
                    // the level bar doesn't step at the standby→recording handoff.
                    val rms = kotlin.math.sqrt(sum / n) / 32768.0
                    onAudioLevel?.invoke((rms * audioGain).toFloat().coerceIn(0f, 1f))
                }
            } finally {
                // Signal "mic fully released" on EVERY exit path (incl. the early return@Thread
                // bails and read failures) before publishing the release latch.
                audioRecord?.let { rec ->
                    runCatching { rec.stop() }
                    runCatching { rec.release() }
                }
                val completion = standbyMeterOwnership.complete(owner)
                owner.release.countDown()
                runCatching { onAudioLevel?.invoke(0f) }
                if (completion.retryPending && !paused) startStandbyAudioMonitor(updateIntent = false)
            }
        }, "StandbyAudioMeter")
        runCatching { t.start() }.onFailure {
            val completion = standbyMeterOwnership.complete(owner)
            owner.release.countDown()
            if (completion.retryPending && !paused) startStandbyAudioMonitor(updateIntent = false)
        }
    }

    private companion object {
        // Exposure floor while a zoom gesture is live (1/30 s → ≥30 fps preview when ISO headroom allows).
        private const val ZOOM_SMOOTH_EXPOSURE_NS = 33_333_333L

        // Live-zoom HAL pacing: every setRepeatingRequest swap stalls this HAL's preview ~180 ms
        // (measured 2026-07-14), so mid-gesture submits are spaced at least this far apart — the
        // GL compensation renders the requested zoom in between.
        private const val ZOOM_HAL_THROTTLE_MS = 200L

        // Mid-gesture the HAL is aimed this factor WIDER than requested so the GL crop has spare
        // field in BOTH directions (instant zoom-out within the margin). Converged at gesture end.
        private const val ZOOM_GESTURE_MARGIN = 1.2f

        // Number of frames fired for a single BURST drive-mode shutter press.
        const val BURST_COUNT = 5
        // Auto-recovery from a mid-session camera HAL error/disconnect (see scheduleCameraRecovery).
        const val MAX_CAMERA_RECOVERY_ATTEMPTS = 3
        const val CAMERA_RECOVERY_DELAY_MS = 1000L
        const val MAX_PREVIEW_RECOVERY_ATTEMPTS = 3
        const val PREVIEW_RECOVERY_DELAY_MS = 200L
        const val RECORDER_FINALIZE_RELEASE_TIMEOUT_MS = 7_000L
    }
}

/** Bounded preview-output recovery decision for one identity-owned TextureView generation. */
internal enum class PreviewRecoveryDecision { IGNORE, RETRY, EXHAUSTED }

internal fun previewRecoveryDecision(
    ownerCurrent: Boolean,
    started: Boolean,
    paused: Boolean,
    nextAttempt: Int,
    maxAttempts: Int,
): PreviewRecoveryDecision = when {
    !ownerCurrent || !started || paused -> PreviewRecoveryDecision.IGNORE
    nextAttempt <= maxAttempts.coerceAtLeast(0) -> PreviewRecoveryDecision.RETRY
    else -> PreviewRecoveryDecision.EXHAUSTED
}

/** Prevents an older asynchronous camera intent from undoing a newer user choice. */
internal fun reconfigurationOwnsGeneration(currentGeneration: Long, expectedGeneration: Long): Boolean =
    currentGeneration == expectedGeneration

/** Camera2 health callbacks remain owned for the complete installed-controller lifetime. */
internal fun activeCameraFailureBelongsToController(
    currentController: Any?,
    failedController: Any,
): Boolean = currentController === failedController

/**
 * Completes a same-route transaction or schedules exactly one structural retry when the optimistic
 * session owner was invalidated. Dependency lambdas keep the decision deterministic in host tests.
 */
internal fun convergeFastPathCommit(
    commit: () -> Boolean,
    ownsTransaction: () -> Boolean,
    canReconfigure: () -> Boolean,
    reconfigure: () -> Unit,
): Boolean {
    val committed = commit()
    if (!committed && ownsTransaction() && canReconfigure()) reconfigure()
    return committed
}

/**
 * Serializes optics intent publication and terminal acceptance on one monitor. Production supplies
 * the engine monitor so desired fields, controller identity, rollback acceptance, and Ready state
 * share the same boundary; host tests can supply an isolated monitor and force exact interleavings.
 */
internal class OpticsCommitGate(
    private val generation: java.util.concurrent.atomic.AtomicLong =
        java.util.concurrent.atomic.AtomicLong(0),
    private val monitor: Any = Any(),
) {
    fun <T> begin(publishDesired: (generation: Long) -> T): T = synchronized(monitor) {
        publishDesired(generation.incrementAndGet())
    }

    fun commit(
        expectedGeneration: Long,
        ownsTerminal: () -> Boolean = { true },
        terminalMutation: () -> Unit,
    ): Long? = synchronized(monitor) {
        if (!reconfigurationOwnsGeneration(generation.get(), expectedGeneration) || !ownsTerminal()) {
            null
        } else {
            terminalMutation()
            expectedGeneration
        }
    }
}

/** Identity-safe, bounded retry ownership for pre-Ready camera preflight failures. */
internal class ColdStartRetryGate(private val maxAttempts: Int) {
    init {
        require(maxAttempts > 0)
    }

    data class Token(val id: Long, val generation: Long)

    sealed interface Failure {
        data object Ignore : Failure
        data object Exhausted : Failure
        data class Retry(val token: Token) : Failure
    }

    private var nextId = 0L
    private var attemptGeneration: Long? = null
    private var attempts = 0
    private var scheduled: Token? = null

    @Synchronized
    fun failed(
        expectedGeneration: Long,
        currentGeneration: Long,
        canRun: Boolean,
    ): Failure {
        if (!canRun || expectedGeneration != currentGeneration) return Failure.Ignore
        if (attemptGeneration != expectedGeneration) {
            attemptGeneration = expectedGeneration
            attempts = 0
            scheduled = null
        }
        // The setup executor is serialized, but resume/input callbacks may observe the same failure
        // while its retry is already scheduled. Keep one timer and one attempt owner.
        if (scheduled != null) return Failure.Ignore
        if (attempts >= maxAttempts) return Failure.Exhausted
        attempts++
        return Failure.Retry(Token(++nextId, expectedGeneration).also { scheduled = it })
    }

    @Synchronized
    fun claim(token: Token, currentGeneration: Long, canRun: Boolean): Boolean {
        if (!canRun || token != scheduled || token.generation != currentGeneration) return false
        scheduled = null
        return true
    }

    @Synchronized
    fun abandon(token: Token) {
        if (scheduled == token) scheduled = null
    }

    @Synchronized
    fun success(expectedGeneration: Long) {
        if (attemptGeneration != expectedGeneration) return
        attempts = 0
        attemptGeneration = null
        scheduled = null
    }

    @Synchronized
    fun cancel() {
        attempts = 0
        attemptGeneration = null
        scheduled = null
    }
}

/**
 * Single-owner admission for the standby AudioRecord and the recording handoff. The release object
 * is generic so the JVM suite can prove ownership without Android audio classes.
 */
internal class StandbyMeterOwnership<R> {
    data class Owner<R>(val id: Long, val release: R)
    data class RecordingClaim<R>(val admitted: Boolean, val release: R?)
    data class Completion(val completed: Boolean, val retryPending: Boolean)

    private var nextId = 0L
    private var wanted = false
    private var active: Owner<R>? = null
    private var recordingClaimed = false
    private var restoreWantedOnAbort = false
    private var wantedChangedSinceClaim = false
    private var restartAfterActive = false

    @Synchronized
    fun reserve(enabled: Boolean, canStart: Boolean, createRelease: () -> R): Owner<R>? {
        if (recordingClaimed) wantedChangedSinceClaim = true
        wanted = enabled
        return reserveWantedLocked(canStart, createRelease)
    }

    /** Internal restart path: observes current intent without changing it. */
    @Synchronized
    fun reserveCurrentWanted(canStart: Boolean, createRelease: () -> R): Owner<R>? =
        reserveWantedLocked(canStart, createRelease)

    private fun reserveWantedLocked(canStart: Boolean, createRelease: () -> R): Owner<R>? {
        if (!wanted || !canStart || recordingClaimed) return null
        if (active != null) {
            restartAfterActive = true
            return null
        }
        restartAfterActive = false
        return Owner(++nextId, createRelease()).also { active = it }
    }

    @Synchronized
    fun disable(): R? {
        if (recordingClaimed) wantedChangedSinceClaim = true
        wanted = false
        restartAfterActive = false
        return active?.release
    }

    @Synchronized
    fun ownsAndWants(owner: Owner<R>): Boolean = wanted && active?.id == owner.id

    @Synchronized
    fun complete(owner: Owner<R>): Completion {
        if (active?.id != owner.id) return Completion(completed = false, retryPending = false)
        active = null
        val retryPending = restartAfterActive && wanted && !recordingClaimed
        restartAfterActive = false
        return Completion(completed = true, retryPending = retryPending)
    }

    /** Claims the recording transition before any recorder object exists, blocking new meters. */
    @Synchronized
    fun beginRecording(): RecordingClaim<R> {
        if (recordingClaimed) return RecordingClaim(admitted = false, release = null)
        recordingClaimed = true
        restoreWantedOnAbort = wanted
        wantedChangedSinceClaim = false
        wanted = false
        restartAfterActive = false
        return RecordingClaim(admitted = true, release = active?.release)
    }

    @Synchronized
    fun abortRecording() {
        if (!wantedChangedSinceClaim) wanted = restoreWantedOnAbort
        recordingClaimed = false
        restoreWantedOnAbort = false
        wantedChangedSinceClaim = false
    }

    /** Releases recorder admission after its AudioRecord teardown; intent is rechecked separately. */
    @Synchronized
    fun finishRecording() {
        recordingClaimed = false
        restoreWantedOnAbort = false
        wantedChangedSinceClaim = false
    }
}

/** A Ready callback may publish only while optics, session, and the engine-ready bit remain current. */
internal fun cameraReadyPublicationIsCurrent(
    currentOpticsGeneration: Long,
    expectedOpticsGeneration: Long,
    currentSessionGeneration: Long,
    expectedSessionGeneration: Long,
    cameraReady: Boolean,
): Boolean = cameraReady &&
    reconfigurationOwnsGeneration(currentOpticsGeneration, expectedOpticsGeneration) &&
    currentSessionGeneration == expectedSessionGeneration

/** Identity + generation gate used to admit a recorder against one accepted Camera2 session. */
internal fun acceptedCameraSessionIsCurrent(
    currentController: Any?,
    acceptedController: Any?,
    currentSessionGeneration: Long,
    acceptedSessionGeneration: Long,
    cameraReady: Boolean,
    paused: Boolean,
): Boolean = cameraReady && !paused && currentController === acceptedController &&
    currentSessionGeneration == acceptedSessionGeneration

/** A queued session reopen may act only while its complete desired packet still owns the engine. */
internal fun sessionReopenMayProceed(
    currentGeneration: Long,
    expectedGeneration: Long,
    expectedControllerMatches: Boolean,
    paused: Boolean,
    recording: Boolean,
): Boolean = reconfigurationOwnsGeneration(currentGeneration, expectedGeneration) &&
    expectedControllerMatches && !paused && !recording

internal data class OpticsIntentState(
    val mode: CaptureMode,
    val lens: LensChoice,
    val teleconverter: Boolean,
    val controls: ManualControls,
    val overrideId: String?,
)

/** Returns the exact pre-intent optics only while that intent still owns the current generation. */
internal fun rollbackOpticsState(
    currentGeneration: Long,
    expectedGeneration: Long,
    snapshot: OpticsIntentState,
): OpticsIntentState? = snapshot.takeIf {
    reconfigurationOwnsGeneration(currentGeneration, expectedGeneration)
}

/** Rapid intents share the last Ready baseline instead of snapshotting an in-flight candidate. */
internal fun <T> selectRollbackBaseline(cameraReady: Boolean, current: T, pendingBaseline: T?): T =
    if (cameraReady) current else pendingBaseline ?: current

/**
 * Whether a recalled optics packet changes the Camera2 route/session contract. A non-TELE Photo
 * lens band is a unified-zoom preset on one logical camera, so the lens enum itself is deliberately
 * absent: same-id recalls can commit controls through the request fast path without a blackout.
 */
internal fun resolvedOpticsRequiresReconfigure(
    beforeVideo: Boolean,
    targetVideo: Boolean,
    beforeTeleconverter: Boolean,
    targetTeleconverter: Boolean,
    beforeCameraId: String?,
    targetCameraId: String?,
    controllerAvailable: Boolean,
    beforeReady: Boolean,
    readyControllerMatches: Boolean,
): Boolean = beforeVideo != targetVideo ||
    beforeTeleconverter != targetTeleconverter ||
    beforeCameraId == null || beforeCameraId != targetCameraId ||
    !controllerAvailable || !beforeReady || !readyControllerMatches

/** Thread-safe ownership token for completion-paced capture sequences. */
internal class CaptureSequenceGeneration {
    private val current = java.util.concurrent.atomic.AtomicLong(0)

    fun restart(): Long = current.incrementAndGet()
    fun stop() { current.incrementAndGet() }
    fun owns(generation: Long): Boolean = current.get() == generation
}

/**
 * Linearizes terminal shutdown with operations that can acquire a fresh native owner. The block is
 * deliberately executed while holding the monitor: close either waits for the in-flight acquisition
 * and owns its teardown, or prevents it from beginning.
 */
internal class TerminalAcquisitionGate {
    private var open = true

    @Synchronized
    fun runIfOpen(block: () -> Unit): Boolean {
        if (!open) return false
        block()
        return true
    }

    @Synchronized
    fun close() {
        open = false
    }

    @Synchronized
    fun isOpen(): Boolean = open
}

/** Merge only the manual bracket field into the freshest live controls. */
internal fun manualAebStepControls(latest: ManualControls, exposureTimeNs: Long): ManualControls =
    latest.copy(shutterMode = ShutterMode.SPEED, exposureTimeNs = exposureTimeNs)

/** Merge only the auto bracket field into the freshest live controls. */
internal fun autoAebStepControls(latest: ManualControls, exposureCompensation: Int): ManualControls =
    latest.copy(exposureCompensation = exposureCompensation)

/** Complete synchronous lens/TELE intent retained even when its async camera preflight is paused. */
internal data class ResolvedLensOpticsIntent(
    val lens: LensChoice,
    val teleconverter: Boolean,
    val controls: ManualControls,
    val preTeleUnifiedZoom: Float,
)

internal fun resolveLensOpticsIntent(
    mode: CaptureMode,
    currentLens: LensChoice,
    currentTeleconverter: Boolean,
    currentControls: ManualControls,
    currentPreTeleUnifiedZoom: Float,
    requestedLens: LensChoice,
    requestedTeleconverter: Boolean,
    restorePreTele: Boolean,
): ResolvedLensOpticsIntent {
    if (requestedTeleconverter) {
        val baseline = if (currentTeleconverter) {
            currentPreTeleUnifiedZoom
        } else if (mode == CaptureMode.VIDEO) {
            currentLens.zoomPreset * currentControls.zoomRatio.coerceAtLeast(1f)
        } else {
            currentControls.zoomRatio
        }
        return ResolvedLensOpticsIntent(
            lens = LensChoice.TELE3X,
            teleconverter = true,
            controls = currentControls.copy(zoomRatio = 1f),
            preTeleUnifiedZoom = baseline,
        )
    }

    val unified = currentPreTeleUnifiedZoom
        .takeIf { restorePreTele && it.isFinite() && it > 0f }
        ?: requestedLens.zoomPreset
    val band = LensChoice.forZoom(unified)
    return ResolvedLensOpticsIntent(
        lens = band,
        teleconverter = false,
        controls = currentControls.copy(
            zoomRatio = if (mode == CaptureMode.VIDEO) {
                (unified / band.zoomPreset).coerceAtLeast(1f)
            } else {
                unified
            },
        ),
        preTeleUnifiedZoom = Float.NaN,
    )
}

/** The largest ratioW:ratioH rect centered within srcW×srcH. */
internal data class CropBox(val x: Int, val y: Int, val w: Int, val h: Int)

/**
 * Pure rect math behind the still-photo aspect crop, extracted so the 4:3/16:9 gating is
 * unit-testable without a Bitmap (an unmocked android.jar stub on the JVM).
 */
/**
 * AUTO-exposure AEB compensation steps: a ±2-stop bracket around [center] converted through the
 * camera's advertised [evStepStops], then clamped to its compensation-unit range.
 * distinct() so a narrow range that clamps two steps to the same value doesn't fire duplicate
 * identical frames (a 1-shot "bracket"). Pure and top-level like [centerCropBox] so the clamp and
 * dedupe are unit-testable (the MANUAL branch's sibling, manualAebExposuresNs, already is).
 */
internal fun aeCompAebSteps(center: Int, lower: Int, upper: Int, evStepStops: Float): List<Int> {
    val safeStep = evStepStops.takeIf { it.isFinite() && it > 0f } ?: (1f / 3f)
    val twoStopsInUnits = kotlin.math.round(2f / safeStep).toInt().coerceAtLeast(1)
    return listOf(center - twoStopsInUnits, center, center + twoStopsInUnits)
        .map { it.coerceIn(lower, upper) }
        .distinct()
}

internal fun centerCropBox(srcW: Int, srcH: Int, ratioW: Int, ratioH: Int): CropBox {
    val heightForFullWidth = srcW * ratioH / ratioW
    val (cropW, cropH) = if (heightForFullWidth <= srcH) {
        srcW to heightForFullWidth
    } else {
        (srcH * ratioW / ratioH) to srcH
    }
    return CropBox((srcW - cropW) / 2, (srcH - cropH) / 2, cropW, cropH)
}

/**
 * View-normalized tap [(nx,ny), origin top-left] → RAW-SENSOR-normalized metering point. The tapped
 * point is in VIEW space; metering regions are in RAW-SENSOR space, so the centered tap is rotated
 * by -(sensor orientation + the teleconverter's afocal 180°) and re-centered — NOT by
 * previewRotationDegrees (which is only the afocal part the renderer adds). NOTE: this ignores the
 * EIS/punch-in crop and offset, so the mapping is APPROXIMATE and needs on-device calibration — the
 * axis signs (and possibly a horizontal mirror) may need flipping once validated. Pure and
 * top-level so the CURRENT behavior is pinned by tests: any future calibration "fix" must land as
 * an intentional test change, not a silent sign flip.
 */
internal fun viewTapToSensorPoint(
    nx: Float,
    ny: Float,
    sensorOrientationDeg: Int,
    afocal180: Boolean,
): Pair<Float, Float> {
    val total = ((sensorOrientationDeg + if (afocal180) 180 else 0) % 360 + 360) % 360
    val px = nx - 0.5f
    val py = ny - 0.5f
    val rad = Math.toRadians(-total.toDouble())
    val cos = Math.cos(rad).toFloat()
    val sin = Math.sin(rad).toFloat()
    val rx = px * cos - py * sin
    val ry = px * sin + py * cos
    return (rx + 0.5f).coerceIn(0f, 1f) to (ry + 0.5f).coerceIn(0f, 1f)
}

/**
 * View tap → punch-in loupe center. The renderer rotates texcoords by previewRotationDegrees (the
 * afocal 180° only — sensor orientation lives in the SurfaceTexture matrix, which the loupe center
 * passes through unchanged). View space is y-DOWN while the texcoord/NDC space is y-UP, so the
 * vertical tap offset is flipped before the content rotation, then re-centered. (Verified on
 * device: without the y-flip a top tap sent the loupe to the bottom half.)
 */
internal fun viewTapToLoupeCenter(nx: Float, ny: Float, previewRotationDegrees: Int): Pair<Float, Float> {
    val rad = Math.toRadians(previewRotationDegrees.toDouble())
    val cos = Math.cos(rad).toFloat()
    val sin = Math.sin(rad).toFloat()
    val ax = nx - 0.5f
    val ay = -(ny - 0.5f)
    val lx = ax * cos - ay * sin
    val ly = ax * sin + ay * cos
    return (lx + 0.5f).coerceIn(0f, 1f) to (ly + 0.5f).coerceIn(0f, 1f)
}
