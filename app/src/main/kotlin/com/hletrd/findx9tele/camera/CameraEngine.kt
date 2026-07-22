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
import android.util.Log
import android.util.Size
import android.view.Surface
import com.hletrd.findx9tele.BuildConfig
import com.hletrd.findx9tele.capture.DngCapture
import com.hletrd.findx9tele.capture.ExifShot
import com.hletrd.findx9tele.capture.ShotSpec
import com.hletrd.findx9tele.capture.StillCapturePipeline
import com.hletrd.findx9tele.capture.HeifCapture
import com.hletrd.findx9tele.capture.StillSnapshot
import com.hletrd.findx9tele.gl.AtomicOwnerSlot
import com.hletrd.findx9tele.gl.EncoderOutputAdmission
import com.hletrd.findx9tele.gl.GlPipeline
import com.hletrd.findx9tele.gl.GlStopOutcome
import com.hletrd.findx9tele.gl.glInputTransactionMayProceed
import com.hletrd.findx9tele.gl.glReplacementMayRestartPreview
import com.hletrd.findx9tele.storage.CaptureFamilyKey
import com.hletrd.findx9tele.storage.CaptureFamilyMedia
import com.hletrd.findx9tele.storage.MediaStoreWriter
import com.hletrd.findx9tele.storage.RecoveryReport
import com.hletrd.findx9tele.storage.RecoveryRetryDecision
import com.hletrd.findx9tele.storage.recoveryRetryDecision
import com.hletrd.findx9tele.video.NativeGraphDisposition
import com.hletrd.findx9tele.video.UnsafeRecorderQuarantine
import com.hletrd.findx9tele.video.VideoRecorder

/**
 * Facade tying Camera2 + GL(180° flip) + capture encoders + video recorder + MediaStore together.
 * Called by the ViewModel; internal work runs on the components' own threads. All image encoding
 * happens off the UI thread (inside camera/GL callbacks).
 */
class CameraEngine(private val context: Context) {

    private val manager = context.getSystemService(CameraManager::class.java)
    private val processNativeOwner = Any()
    private val glOwners = AtomicOwnerSlot(GlPipeline()) { GlPipeline() }
    private val gl: GlPipeline
        get() = glOwners.current()
    private val rendererAssists = RendererAssists(glOwners::current)
    // Terminal owner for every operation that can acquire a fresh GL generation. release() closes
    // it before its one GL stop, so a queued cold start cannot resurrect resources afterward.
    private val terminalAcquisitionGate = TerminalAcquisitionGate()
    // Startup setup (camera-service IPC) and still-image encoding are kept off the main/camera/GL
    // threads via these single-thread executors.
    private val setupExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    // Launch MediaStore reconciliation may perform several provider scans/probes and bounded
    // backoffs. Keep it independent from camera setup so recovery can never delay first preview.
    private val mediaRecoveryExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "media-recovery").apply { isDaemon = true }
    }
    // Recorder finalization can block for seconds joining codec/audio drains. It must never sit
    // behind a burst's full-resolution still encodes on ioExecutor (or vice versa).
    private val recorderExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    // Independent of GL/setup/recorder lanes: an accepted task on any of those lanes may itself be
    // the wedge we are timing out. This daemon owns only bounded teardown deadlines.
    private val recorderWatchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "recording-teardown-watchdog").apply { isDaemon = true }
    }
    // Drives interval (timelapse) capture off the camera/UI threads.
    private val timelapseScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    @Volatile private var timelapseFuture: java.util.concurrent.ScheduledFuture<*>? = null
    private val timelapseRun = CaptureSequenceGeneration()
    @Volatile private var controller: CameraController? = null
    @Volatile private var recorder: VideoRecorder? = null
    private val recorderOwnershipLock = Any()
    // The encoder EGL surface must detach from the exact pipeline instance that attached it. A
    // replacement pipeline is deliberately unreachable from an old recorder teardown callback.
    @Volatile private var activeRecordingGl: GlPipeline? = null
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
    // Hidden while Video is active, but transaction-owned like every visible optics control: a
    // failed/overlapping MR recall must restore the exact Photo shutter of the last Ready session.
    @Volatile private var photoExposureTimeNs = controls.exposureTimeNs
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
    private val tapFocusPublicationSequence = java.util.concurrent.atomic.AtomicLong(0)
    private val opticsIntentGeneration = java.util.concurrent.atomic.AtomicLong(0)
    private val opticsCommitGate = OpticsCommitGate(opticsIntentGeneration, this)
    // Camera-health signal for the UI (dim the shutter, show a persistent OSD tag while down):
    // fires on every cameraReady flip. A silent scheduleCameraRecovery exhaustion previously left a
    // black viewfinder behind a fully interactive-looking shutter with zero indication.
    var onCameraReadyChange: ((CameraReadyPublication) -> Unit)? = null
    /** Restores UI optics when a current-generation camera switch fails before closing the old one. */
    var onOpticsRollback: ((CaptureMode, LensChoice, Boolean, CameraFacing, ManualControls, Long, String?, generation: Long) -> Unit)? = null

    // AF engine state for the reticle color, mapped from the controller's raw CONTROL_AF_STATE.
    var onAfIndication: ((AfIndication) -> Unit)? = null
    // A tap point is owned by one exact accepted Camera2 session. Controller/session invalidation
    // publishes a newer false event so a delayed UI task can never leave a stale AF HOLD visible.
    var onTapFocusChange: ((TapFocusPublication) -> Unit)? = null
    private var tapFocusOwner: AcceptedCameraSession? = null

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

    private fun nextTapFocusPublication(
        held: Boolean,
        point: Pair<Float, Float>? = null,
    ): TapFocusPublication = TapFocusPublication(
        sequence = tapFocusPublicationSequence.incrementAndGet(),
        held = held,
        point = point,
    )

    /** Engine-monitor-only: retires the exact session that owned the functional tap point. */
    private fun retireTapFocusLocked(rebuildPreview: Boolean): TapFocusPublication? {
        val owner = tapFocusOwner
        val pending = pendingTapFocus
        if (owner == null && pending == null && deferredTapFocus == null) return null
        tapFocusOwner = null
        pendingTapFocus = null
        deferredTapFocus = null
        owner?.controller?.clearMeteringPoint(rebuildPreview)
        pending?.session?.controller
            ?.takeIf { it !== owner?.controller }
            ?.clearMeteringPoint(rebuildPreview)
        resetTapGeometryLocked()
        return nextTapFocusPublication(held = false)
    }

    /** Engine-monitor-only sibling of the controller ROI reset. */
    private fun resetTapGeometryLocked() {
        loupeCenterTexX = 0.5f
        loupeCenterTexY = 0.5f
        loupeCenterSensorX = 0.5f
        loupeCenterSensorY = 0.5f
        gl.setPunchInCenter(0.5f, 0.5f)
    }

    private fun invalidateCameraReady() {
        val (publication, tapPublication) = synchronized(this) {
            val sessionGeneration = cameraSessionGeneration.incrementAndGet()
            val retiredTap = retireTapFocusLocked(rebuildPreview = false)
            cameraReady = false
            readyController = null
            acceptedCameraSession = null
            nextCameraReadyPublication(
                ready = false,
                opticsGeneration = opticsIntentGeneration.get(),
                sessionGeneration = sessionGeneration,
            ) to retiredTap
        }
        tapPublication?.let { onTapFocusChange?.invoke(it) }
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
        if (!videoMode) photoExposureTimeNs = controls.exposureTimeNs
        // The lens band is a REAR unified-zoom concept; front zoom is lens-local and must not
        // remap the retained rear band (it is what "leave FRONT" returns to).
        if (!videoMode && !teleconverterMode && facing == CameraFacing.BACK) {
            lensChoice = LensChoice.forZoom(controls.zoomRatio)
        }
        seedGlZoom()
    }

    private data class OpticsSnapshot(
        val videoMode: Boolean,
        val lens: LensChoice,
        val teleconverter: Boolean,
        val facing: CameraFacing,
        val controls: ManualControls,
        val photoExposureTimeNs: Long,
        val overrideId: String?,
        val selection: TeleSelection?,
        val caps: CameraCaps?,
        val videoSize: Size,
        val requestedVideoSize: Size?,
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

    /** One queued tap awaiting Camera2 request submission on its accepted controller. */
    private data class PendingTapFocus(
        val session: AcceptedCameraSession,
        val viewPoint: Pair<Float, Float>,
        val sensorPoint: Pair<Float, Float>,
        val loupePoint: Pair<Float, Float>,
    )

    private var pendingTapFocus: PendingTapFocus? = null
    /** Latest already-mapped point while setRepeatingRequest is submitting the preceding tap. */
    private var deferredTapFocus: PendingTapFocus? = null

    private data class OwnedRecording(
        val recorder: VideoRecorder,
        val uri: android.net.Uri?,
        val captureId: Int,
        val gl: GlPipeline,
    )

    private data class OpticsTransaction(val generation: Long, val before: OpticsSnapshot)
    private data class OpticsReconfiguration(val overrideId: String?, val transaction: OpticsTransaction)

    @Volatile private var opticsRollbackBaseline: OpticsSnapshot? = null

    private fun currentOpticsSnapshot(): OpticsSnapshot = OpticsSnapshot(
        videoMode = videoMode,
        lens = lensChoice,
        teleconverter = teleconverterMode,
        facing = facing,
        controls = controls,
        photoExposureTimeNs = photoExposureTimeNs,
        overrideId = overrideId,
        selection = selection,
        caps = caps,
        videoSize = videoSize,
        requestedVideoSize = requestedVideoSize,
        previewStreamSize = previewStreamSize,
        preTeleUnifiedZoom = preTeleUnifiedZoom,
        ready = cameraReady,
        readyController = readyController,
        sessionGeneration = cameraSessionGeneration.get(),
        photoSessionOutputs = acceptedCameraSession?.outputs ?: PhotoSessionOutputs(),
    )

    private fun <T> beginOpticsTransaction(publishDesiredOptics: () -> T): Pair<OpticsTransaction, T> {
        val (result, publications) = opticsCommitGate.begin { generation ->
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
            val tapPublication = retireTapFocusLocked(rebuildPreview = false)
            cameraReady = false
            readyController = null
            acceptedCameraSession = null
            (transaction to desired) to (
                nextCameraReadyPublication(
                    ready = false,
                    opticsGeneration = generation,
                    sessionGeneration = cameraSessionGeneration.get(),
                ) to tapPublication
            )
        }
        publications.second?.let { onTapFocusChange?.invoke(it) }
        onCameraReadyChange?.invoke(publications.first)
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
        var acceptedDiagnostic: String? = null
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
            val acceptedMode = if (videoMode) CaptureMode.VIDEO else CaptureMode.PHOTO
            val acceptedCameraId = selection?.let { it.physicalId ?: it.logicalId } ?: "none"
            acceptedDiagnostic = "CameraSessionAccepted: controllerId=${expectedController.diagnosticId} " +
                "opticsGeneration=$expectedGeneration sessionGeneration=$sessionGeneration " +
                "requestGeneration=${expectedController.latestPreviewRequestGeneration} " +
                "mode=${acceptedMode.name} cameraId=$acceptedCameraId ready=$effectiveReady"
            cameraRecoveryAttempts = 0
        } ?: return false
        coldStartRetryGate.success(publicationGeneration)
        if (BuildConfig.DEBUG) Log.i("CameraEngine", checkNotNull(acceptedDiagnostic))
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
        // A fast commit retains the accepted session's still readers VERBATIM, but the hi-res
        // admission axis (photo-only) can flip on exactly these same-route doors — e.g. a
        // photo↔video mode flip on the standalone 3× with an unchanged stream size. Re-publishing
        // the old reader truth under the new intent would either strand a 200MP reader in a video
        // session or deny an admitted hi-res still, so when the freshly resolved admission no
        // longer matches the session being retained, converge through the full reconfigure (the
        // desired-state fields were already published under this transaction's monitor).
        if (resolvedHiResStill() != transaction.before.photoSessionOutputs.hiRes &&
            ownsOpticsTransaction(transaction) && !paused && recorder == null
        ) {
            reconfigureCamera(cameraId, transaction)
            return
        }
        convergeFastPathCommit(
            commit = {
                commitOpticsReady(
                    transaction.generation,
                    expectedController,
                    transaction.before.photoSessionOutputs,
                    expectedSessionGeneration = transaction.before.sessionGeneration,
                    terminalMutation = {
                        terminalMutation()
                        finishRetainedControllerOpticsRemap(expectedController)
                    },
                    beforeReadyPublication = beforeReadyPublication,
                )
            },
            ownsTransaction = { ownsOpticsTransaction(transaction) },
            canReconfigure = { !paused && recorder == null },
            reconfigure = { reconfigureCamera(cameraId, transaction) },
        )
    }

    /**
     * Completes the zoom lifecycle only for a fast optics commit that RETAINS its CameraController.
     * Reconfiguration paths skip this entirely because [wireController] installs a fresh boost=false
     * owner. The controller folds exact controls + boost-off into one camera-thread request update,
     * avoiding both the stale low-light FPS pin and a second corrective rebuild.
     */
    private fun finishRetainedControllerOpticsRemap(expectedController: CameraController) {
        zoomInteractionActive = false
        lastHalZoomSubmitMs = android.os.SystemClock.uptimeMillis()
        gl.setZoomTarget(controls.zoomRatio)
        expectedController.commitRetainedOpticsControls(controls)
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
                facing = before.facing,
                controls = before.controls,
                photoExposureTimeNs = before.photoExposureTimeNs,
                overrideId = before.overrideId,
                requestedVideoSize = before.requestedVideoSize,
            ),
        ) ?: return
        opticsRollbackBaseline = null
        videoMode = restored.mode == CaptureMode.VIDEO
        lensChoice = restored.lens
        teleconverterMode = restored.teleconverter
        // A failed FRONT open (or a failed exit) restores the exact prior facing with the rest of
        // the packet; applyStabilization below then re-pushes the matching preview mirror.
        facing = restored.facing
        controls = restored.controls
        photoExposureTimeNs = restored.photoExposureTimeNs
        overrideId = restored.overrideId
        selection = before.selection
        caps = before.caps
        videoSize = before.videoSize
        requestedVideoSize = restored.requestedVideoSize
        previewStreamSize = before.previewStreamSize
        preTeleUnifiedZoom = before.preTeleUnifiedZoom
        controller?.setPinAutoFps(before.videoMode, transaction.generation)
        // Queue the exact UI optics first. Caps reconciliation follows it on main and is tagged with
        // this generation, so it cannot clamp the failed candidate or a newer user intent.
        onOpticsRollback?.invoke(
            restored.mode,
            restored.lens,
            restored.teleconverter,
            restored.facing,
            restored.controls,
            restored.photoExposureTimeNs,
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
        if (restoreSession) {
            finishRetainedControllerOpticsRemap(checkNotNull(controller))
        } else {
            controller?.updateControls(before.controls)
        }
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

    // Static-per-device like the focal→id cache: the front enumeration never changes at runtime and
    // costs several Binder IPCs, so resolve it once on setupExecutor and reuse.
    @Volatile private var cachedFrontSelection: TeleSelection? = null

    private fun cachedFront(): TeleSelection? =
        cachedFrontSelection ?: CameraSelector2.pickFront(manager)?.also { cachedFrontSelection = it }

    private val gyro = com.hletrd.findx9tele.stab.GyroEis(context)
    @Volatile private var teleconverterMode = false
    @Volatile private var videoMode = false
    // FRONT is a first-class optics door (setFrontCamera). Never persisted — fresh launch is BACK
    // (see CameraFacing) — and never combined with TELE: entering FRONT forces the converter off.
    @Volatile private var facing = CameraFacing.BACK
    private val facingIntentGeneration = java.util.concurrent.atomic.AtomicLong(0)
    // Hi-res still INTENT (user toggle, persisted). Resolution to an actual session demand happens
    // in exactly one place ([resolvedHiResStill], the shared hiResAdmitted predicate) and is pushed
    // to the controller before every configure; the doors that can flip the resolved value without
    // a reconfigure of their own (the toggle itself, aspect, and same-route fast commits) each
    // re-resolve and converge through the session reconfigure paths below.
    @Volatile private var hiResStillIntent = false
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
    /** First successful standby PCM after enable/recovery; safe point to clear unavailable UI. */
    internal var onStandbyAudioAvailable: (() -> Unit)? = null
    /** Bounded standby setup/read budget exhausted while the visible meter is still requested. */
    internal var onStandbyAudioUnavailable: ((StandbyAudioUnavailable) -> Unit)? = null
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
    // A DNG output with the same capture-sequence id as its HEIF/JPEG siblings. A RAW-only capture
    // may own the review metadata tile until a processed sibling upgrades it. Fired after publish.
    var onRawSaved: ((android.net.Uri, Int) -> Unit)? = null

    // ---- Preview surface lifecycle ----

    fun onPreviewSurfaceAvailable(surface: Surface, width: Int, height: Int) {
        if (!nativeAcquisitionMayProceed()) {
            if (UnsafeRecorderQuarantine.isActive()) {
                onStatus?.invoke(UNSAFE_RECORDER_RESTART_STATUS)
            }
            invalidateCameraReady()
            return
        }
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
                if (paused || UnsafeRecorderQuarantine.isActive()) {
                    synchronized(this) { starting = false }
                    return@runIfOpen
                }
                // HLG10 10-bit preview + full-res JPEG/RAW crashes this HAL (configureStreams Broken
                // pipe -32); use an SDR preview session. Video still tags HLG/Log in the encoder.
                val tenBit = false
                val ownedGl = glOwners.current()
                glInputPending = true
                ownedGl.start(tenBit) { _ ->
                    // A bounded native wedge can retire this whole GlPipeline object and install a
                    // replacement. Its late input callback must not open a camera onto the new
                    // generation's Engine state.
                    if (!glOwners.owns(ownedGl)) return@start
                    terminalAcquisitionGate.runIfOpen inputReady@{
                        if (!glOwners.owns(ownedGl)) return@inputReady
                        glInputPending = false
                        if (paused || UnsafeRecorderQuarantine.isActive()) return@inputReady
                        ownedGl.setEisProvider { gyro.currentCorrection() }
                        ownedGl.setAnalysisCallback { h, w ->
                            if (glOwners.owns(ownedGl)) onAnalysis?.invoke(h, w)
                        }
                        // Re-seed desired GL state that may have been set before the handler existed.
                        ownedGl.setNativeLog(false)
                        ownedGl.setTransfer(transfer)
                        rendererAssists.replayAll(ownedGl)
                        gyro.start()
                        // Capture route + token after GL input exists. A newer intent invalidates it.
                        val desired = currentOpticsReconfiguration()
                        reconfigureCamera(desired.overrideId, desired.transaction, startup = true)
                        maybeLogCameraCapabilities()
                    }
                }
                if (!glOwners.owns(ownedGl)) return@runIfOpen
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

    /**
     * Debug-only Camera2 capability log, queued behind initial camera work and the OEM SDK's startup
     * log storm. ColorOS rate-limits each process during that burst, which otherwise drops the
     * concurrent-camera evidence before adb can read it.
     */
    private fun maybeLogCameraCapabilities() {
        if (!com.hletrd.findx9tele.BuildConfig.DEBUG) return
        timelapseScheduler.schedule(
            {
                runCatching {
                    setupExecutor.execute { runCatching { VendorTagInspector.logAll(manager) } }
                }
            },
            5,
            java.util.concurrent.TimeUnit.SECONDS,
        )
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
        // Selfie mirror is ROUTE state plumbed exactly like the rotation pair above: re-pushed on
        // every session (re)config/rollback, which also covers a replacement GL generation (this
        // method runs after each reconfigure, so a fresh pipeline can never start with a stale
        // mirror — the documented "posted before start() is dropped" trap). Preview-only; the
        // encoder/analysis draws stay unmirrored (saved files show the true scene — the framework
        // front-camera convention; a "save mirrored" toggle is a possible future option).
        gl.setPreviewMirror(facing == CameraFacing.FRONT)
        // TELE finder PIP: re-resolve on every session (re)config and tele change, like rotation
        // (the user toggle, TELE state, or aspect may all have changed since the last push).
        pushTeleFinder()
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
        rendererAssists.setFalseColor(enabled)
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
        val ownedGl = glOwners.current()
        terminalAcquisitionGate.runIfOpen {
            if (UnsafeRecorderQuarantine.isActive()) return@runIfOpen
            ownedGl.setPreviewOutput(
                surface = surface,
                width = width,
                height = height,
                onReady = {
                    handlePreviewReady(ownedGl, surface, surfaceGeneration)
                },
                onFailure = { failure ->
                    handlePreviewFailure(ownedGl, surface, surfaceGeneration, failure)
                },
            )
        }
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

    private fun handlePreviewReady(
        ownedGl: GlPipeline,
        surface: Surface,
        surfaceGeneration: Long,
    ) {
        if (!nativeAcquisitionMayProceed()) return
        val publication = synchronized(this) {
            if (!glOwners.owns(ownedGl)) return@synchronized null
            if (surfaceGeneration != previewSurfaceGeneration.get() || previewSurface !== surface) {
                return@synchronized null
            }
            val wasReady = previewReady && cameraReady
            previewReady = true
            // GlPipeline invokes this only after the generation's first successful real-frame
            // swap. Bind success alone stays Pending, so repeated first-swap failures retain and
            // eventually exhaust this surface generation's bounded recovery budget.
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
        ownedGl: GlPipeline,
        surface: Surface,
        surfaceGeneration: Long,
        failure: Throwable,
    ) {
        if (!nativeAcquisitionMayProceed()) return
        val outcome = synchronized(this) {
            val ownerCurrent = glOwners.owns(ownedGl) &&
                surfaceGeneration == previewSurfaceGeneration.get() && previewSurface === surface
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
                onStatus?.invoke("Preview unavailable. Reopen the app.")
            PreviewRecoveryDecision.RETRY -> {
                onStatus?.invoke("Preview interrupted. Recovering.")
                runCatching {
                    timelapseScheduler.schedule(
                        {
                            if (nativeAcquisitionMayProceed() && glOwners.owns(ownedGl) &&
                                surfaceGeneration == previewSurfaceGeneration.get() &&
                                previewSurface === surface && started && !paused
                            ) {
                                bindPreviewSurface(surface, previewSurfaceW, previewSurfaceH, surfaceGeneration)
                            }
                        },
                        PREVIEW_RECOVERY_DELAY_MS,
                        java.util.concurrent.TimeUnit.MILLISECONDS,
                    )
                }.onFailure {
                    onStatus?.invoke("Preview unavailable. Reopen the app.")
                }
            }
        }
        if (com.hletrd.findx9tele.BuildConfig.DEBUG) {
            android.util.Log.w("CameraEngine", "Preview EGL failure", failure)
        }
    }

    private fun wireController(ownedGl: GlPipeline): CameraController {
        // A fresh controller means a fresh gesture world: clear the interaction flag so a glide
        // that was cut short by a remap door (whose invalidate deliberately skips the boost-off
        // rebuild) cannot leave the NEXT gesture's leading edge seeing a stale mid-gesture state
        // (AGG4-2 — the flag used to survive every mode/lens/TC remap and defeat the leading-edge
        // exact submit for one gesture).
        zoomInteractionActive = false
        val ctrl = CameraController(context)
        // Every result callback is identity-gated: a CLOSING controller's capture results keep
        // arriving on its camera thread for a beat after the replacement is wired, and an ungated
        // setHalZoom could clobber the new generation's GL zoom compensation for a frame during a
        // reopen (the same replaced-controller-is-inert rule the error path already follows).
        ctrl.onExposure = { iso, exp -> if (controller === ctrl) onExposureInfo?.invoke(iso, exp) }
        ctrl.onZoomResult = { rz ->
            if (controller === ctrl && glOwners.owns(ownedGl)) ownedGl.setHalZoom(rz)
        }
        ctrl.onFocusDistance = { d -> if (controller === ctrl) onFocusDistance?.invoke(d) }
        ctrl.onAfState = { hal -> if (controller === ctrl) onAfIndication?.invoke(AfIndication.fromHal(hal)) }
        return ctrl
    }

    private fun openCamera(
        ownedGl: GlPipeline,
        input: Surface,
        transaction: OpticsTransaction? = null,
    ) {
        if (!nativeAcquisitionMayProceed()) return
        // The SurfaceTexture belongs to exactly one GL object. A queued open from a retired object
        // must never configure Camera2 against that old native window.
        if (paused || !glInputTransactionMayProceed(
                ownerCurrent = glOwners.owns(ownedGl),
                engineStarted = started,
                inputCurrent = ownedGl.inputSurface === input,
            )
        ) return
        val expectedOpticsGeneration = transaction?.generation ?: opticsIntentGeneration.get()
        if (!reconfigurationOwnsGeneration(opticsIntentGeneration.get(), expectedOpticsGeneration)) return
        val sel = selection ?: return
        val c = caps ?: return
        seedGlZoom(ownedGl)
        invalidateCameraReady()
        controller?.close() // idempotent: closes any prior controller so two never race for the device
        val ctrl = wireController(ownedGl)
        val installedPublication = synchronized(this) {
            if (UnsafeRecorderQuarantine.isActive() || paused || !glInputTransactionMayProceed(
                    ownerCurrent = glOwners.owns(ownedGl),
                    engineStarted = started,
                    inputCurrent = ownedGl.inputSurface === input,
                ) || !reconfigurationOwnsGeneration(
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
        var openStarted = false
        terminalAcquisitionGate.runIfOpen cameraOpen@{
            if (UnsafeRecorderQuarantine.isActive()) return@cameraOpen
            openStarted = true
            // Same pre-configure resolve as the dual-open path: this sequential fallback must not
            // silently drop an admitted hi-res session (or keep one video mode denies).
            ctrl.hiResStill = resolvedHiResStill(sel, c)
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
                diagnosticOpticsGeneration = expectedOpticsGeneration,
                onReady = { outputs ->
                    if (!nativeAcquisitionMayProceed() || !glInputTransactionMayProceed(
                            ownerCurrent = glOwners.owns(ownedGl),
                            engineStarted = started,
                            inputCurrent = ownedGl.inputSurface === input,
                        )
                    ) {
                        ctrl.close()
                        return@open
                    }
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
                    if (nativeAcquisitionMayProceed() && glInputTransactionMayProceed(
                            ownerCurrent = glOwners.owns(ownedGl),
                            engineStarted = started,
                            inputCurrent = ownedGl.inputSurface === input,
                        )
                    ) {
                        handleActiveCameraFailure(ctrl, failure)
                    } else {
                        ctrl.close()
                    }
                },
            )
        }
        if (!openStarted) {
            synchronized(this) {
                if (controller === ctrl) controller = null
            }
            ctrl.close()
        }
    }

    // ---- Controls ----

    fun setControls(c: ManualControls) {
        val modeIntent = if (videoMode && c.exposureMode == ExposureMode.PROGRAM) {
            c.copy(programAppSide = false)
        } else {
            c
        }
        val normalized = (caps?.let(modeIntent::normalizedFor) ?: modeIntent).normalizedForCaptureMode(
            if (videoMode) CaptureMode.VIDEO else CaptureMode.PHOTO,
        )
        // Packet-write invariant: EVERY wholesale [controls] writer holds the engine monitor
        // (rollbackOptics is @Synchronized, the commit-gate/caps-reconcile writers run inside
        // synchronized(this), the zoom RMW takes it). This main-thread apply loop was the one
        // exception — its write landing between a setupExecutor writer's read and write-back
        // silently clobbered the route-normalized packet with one normalized against the
        // OUTGOING caps (the VM's caps snapshot lags onCapsReady by a main-queue hop).
        val tapPublication = synchronized(this) {
            val retiredTap = if (controls.focusMode != normalized.focusMode) {
                // setControls is also used by MR restore/caps reconciliation, not only the Focus UI.
                // Retire under the same monitor as the packet swap so a pending submission cannot
                // publish AF HOLD between the focus-mode comparison and ownership invalidation.
                retireTapFocusLocked(rebuildPreview = false)
            } else {
                null
            }
            controls = normalized
            if (!videoMode) photoExposureTimeNs = normalized.exposureTimeNs
            retiredTap
        }
        tapPublication?.let { onTapFocusChange?.invoke(it) }
        // clearMeteringPoint(false), when needed, was queued first on this same controller. This
        // update performs the single request rebuild carrying both the focus-mode and ROI reset.
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

    fun setVideoMode(
        enabled: Boolean,
        resolvedLens: LensChoice,
        resolvedControls: ManualControls,
        resolvedPhotoExposureTimeNs: Long,
    ) {
        val wasVideoMode = videoMode
        if (captureModeTransitionStopsTimelapse(wasVideoMode, enabled)) {
            // Interval shooting is photo-owned. Revoke its generation before publishing any VIDEO
            // optics/session intent so a queued tick or late still-save completion cannot follow the
            // new accepted session into Video. Keep [driveMode] untouched: returning to Photo still
            // shows TIMELAPSE, but the photographer must press the shutter to start a fresh run.
            stopTimelapse()
        }
        // Publish one already-resolved optics packet before queuing any reconfiguration. Keeping the
        // UI and engine remaps separate let a delayed full-controls apply race this method and made
        // Photo/Video transitions depend on executor timing.
        val enteringVideo = !videoMode && enabled
        val modeIntent = if (enteringVideo && resolvedControls.exposureMode == ExposureMode.PROGRAM) {
            resolvedControls.copy(programAppSide = false)
        } else {
            resolvedControls
        }
        val modeControls = modeIntent.normalizedForCaptureMode(
            if (enabled) CaptureMode.VIDEO else CaptureMode.PHOTO,
        )
        val changed = wasVideoMode != enabled
        val transaction = if (changed) {
            beginOpticsTransaction {
                videoMode = enabled
                lensChoice = resolvedLens
                controls = modeControls
                photoExposureTimeNs = resolvedPhotoExposureTimeNs.coerceAtLeast(1L)
                // A mode intent owns automatic routing. Clear the resolved outgoing id atomically.
                overrideId = null
            }.first
        } else {
            synchronized(this) {
                videoMode = enabled
                lensChoice = resolvedLens
                controls = modeControls
                photoExposureTimeNs = resolvedPhotoExposureTimeNs.coerceAtLeast(1L)
            }
            null
        }
        // Mode is a finder-gate input (photo-only): re-resolve synchronously so a photo→video flip
        // can never leave the GL PIP drawing until the async reconfigure lands.
        pushTeleFinder()
        transaction?.let { controller?.setPinAutoFps(enabled, it.generation) }
        controller?.updateControls(modeControls)
        if (!changed) return
        val intentGeneration = modeIntentGeneration.incrementAndGet()
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
            // FRONT keeps its one camera across the mode flip (photo/video are stream-size, not
            // camera, changes there); the rear split keeps its documented tele/mode-home routing.
            val id = when {
                facing == CameraFacing.FRONT -> cachedFront()?.logicalId
                teleconverterMode -> cachedIdForFocal(LensChoice.TELE3X.targetEquivMm)
                else -> resolveNonTeleId(lensChoice)
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
                    // mode semantics, so the existing session is still the ready session. Reconcile
                    // against this retained route before publishing Ready: an AE_OFF-only camera
                    // must re-arm app-owned Program + luma analysis in the ViewModel, even though
                    // Video entry intentionally cleared the Photo-derived ownership flag.
                    val expectedController = controller ?: return@execute
                    val routeCaps = caps ?: run {
                        reconfigureCamera(id, transaction)
                        return@execute
                    }
                    val range = routeCaps.zoomRatioRange
                    commitFastPathOrReconfigure(
                        transaction = transaction,
                        cameraId = id,
                        expectedController = expectedController,
                        terminalMutation = {
                            // This terminal mutation holds the engine monitor, the same monitor used
                            // by setControls. Normalize the LIVE target-mode packet so a P-ownership
                            // refresh or dial update that landed while setup was queued cannot be
                            // overwritten by the older transition snapshot.
                            controls = normalizeRetainedControlsAtCommit(
                                liveControls = controls,
                                capabilities = routeCaps.controlCapabilities(),
                                mode = if (enabled) CaptureMode.VIDEO else CaptureMode.PHOTO,
                                teleconverter = teleconverterMode,
                                capsLower = range?.lower,
                                capsUpper = range?.upper,
                            )
                            if (!enabled) photoExposureTimeNs = controls.exposureTimeNs
                            if (!enabled && !teleconverterMode && facing == CameraFacing.BACK) {
                                lensChoice = LensChoice.forZoom(controls.zoomRatio)
                            }
                            seedGlZoom()
                            overrideId = id
                        },
                        beforeReadyPublication = { generation ->
                            onCapsReady?.invoke(routeCaps, generation)
                        },
                    )
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
        resolvedPhotoExposureTimeNs: Long,
        recalledVideoSize: Size?,
    ): Boolean {
        if (recorder != null) { onStatus?.invoke("Stop REC first"); return false }
        if (captureModeTransitionStopsTimelapse(videoMode, enabledVideo)) {
            // MR/settings recall is a second route into VIDEO and must own the same interval boundary
            // as the direct Photo/Video carousel transition.
            stopTimelapse()
        }
        val modeIntent = if (enabledVideo && resolvedControls.exposureMode == ExposureMode.PROGRAM) {
            resolvedControls.copy(programAppSide = false)
        } else {
            resolvedControls
        }
        val modeControls = modeIntent.normalizedForCaptureMode(
            if (enabledVideo) CaptureMode.VIDEO else CaptureMode.PHOTO,
        )
        val transaction = beginOpticsTransaction {
            videoMode = enabledVideo
            lensChoice = resolvedLens
            teleconverterMode = resolvedTeleconverter
            // MR recall / settings restore EXITS the front camera in this same atomic publication:
            // recalled packets are rear-route optics (lens band, TC state, unified/lens-local zoom
            // semantics — facing itself is deliberately never persisted), so applying one while
            // FRONT would pair front hardware with rear-scale values. One transaction, no
            // intermediate front-with-recalled-optics state.
            facing = CameraFacing.BACK
            controls = modeControls
            photoExposureTimeNs = resolvedPhotoExposureTimeNs.coerceAtLeast(1L)
            recalledVideoSize?.let { requestedVideoSize = it }
            preTeleUnifiedZoom = Float.NaN
            // Settings/MR replaces automatic routing; the snapshot retains an override for rollback.
            overrideId = null
        }.first
        // Self-contained finder resolve: TC/mode just changed above, and relying on the restore
        // block's TRAILING setAspectRatio/setTeleFinder calls made correctness depend on adjacent
        // setter call order (a reorder would resolve against stale inputs).
        pushTeleFinder()
        val modeGeneration = modeIntentGeneration.incrementAndGet()
        val lensGeneration = lensIntentGeneration.incrementAndGet()
        controller?.setPinAutoFps(enabledVideo, transaction.generation)
        if (!started) return true
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
                commitFastPathOrReconfigure(
                    transaction = transaction,
                    cameraId = id,
                    expectedController = expectedController,
                    terminalMutation = {
                        // Preserve any target-owned update that landed after the recalled packet was
                        // published; terminal commit and setControls serialize on this monitor.
                        controls = normalizeRetainedControlsAtCommit(
                            liveControls = controls,
                            capabilities = routeCaps.controlCapabilities(),
                            mode = if (enabledVideo) CaptureMode.VIDEO else CaptureMode.PHOTO,
                            teleconverter = resolvedTeleconverter,
                            capsLower = range?.lower,
                            capsUpper = range?.upper,
                        )
                        if (!enabledVideo) photoExposureTimeNs = controls.exposureTimeNs
                        if (!enabledVideo && !resolvedTeleconverter) {
                            lensChoice = LensChoice.forZoom(controls.zoomRatio)
                        }
                        seedGlZoom()
                        overrideId = id
                    },
                    beforeReadyPublication = { generation -> onCapsReady?.invoke(routeCaps, generation) },
                )
            }
        }
        return true
    }

    /**
     * Selects the color transfer — the single source of truth. The log profiles (S-Log3 /
     * S-Log3.Cine / LogC3) are GL-baked curves (the shipping path): the encoder receives the curve
     * and the preview renders it flat. The device's native HAL log key (`com.oplus.log.video.mode`)
     * is INERT for third-party Camera2 (device-settled 2026-07-09 — see the body comment), so
     * [vendorLogMode] stays OFF; the dormant native-log plumbing (pass-through + de-log shader) is
     * kept only for a future CameraUnit-authenticated scene-referred stream.
     *
     * Changing the OETF mid-recording would tag the file with the start transfer but bake a different
     * curve into the second half, so the GL curve is only pushed when idle (the field still updates
     * for the next recording; stopRecording/pause re-apply it). See docs/reviews record-pipeline #4.
     */
    fun setTransfer(t: ColorTransfer) {
        transfer = t
        // Log = a GL-baked standard curve (proven architecture, inherited from the removed O-Log2
        // option). The native com.oplus.log.video.mode key was tried twice and is effectively INERT
        // for a third-party Camera2 session: the HAL accepts it ("applied" logs) but neither the
        // preview nor the RECORDED stream is scene-referred — device-tested 2026-07-09 with
        // TEMPLATE_PREVIEW and TEMPLATE_RECORD repeating requests (the clip came out plain 709; the
        // earlier "file looked log" was the BT.2020 full-range container tag being misread by
        // players as a washed look). So the GL path stays: encoder gets the selected curve, the
        // preview shows it flat, and Gamma Display Assist shows the normal display-referred image
        // instead. vendorLogMode stays OFF (dormant, with the de-log shader, for a future
        // CameraUnit-authenticated scene-referred path).
        gl.setNativeLog(false)
        val wasNativeLog = vendorLogMode != VendorLogMode.OFF
        vendorLogMode = VendorLogMode.OFF
        if (wasNativeLog) reopenForSession()
        if (recorder == null) gl.setTransfer(t)
    }
    fun setPeaking(enabled: Boolean) {
        rendererAssists.setPeaking(enabled)
    }
    fun setZebra(enabled: Boolean) {
        rendererAssists.setZebra(enabled)
    }
    fun setPeakingLevel(l: PeakingLevel) {
        rendererAssists.setPeakingLevel(l)
    }
    fun setPeakingColor(c: PeakingColor) {
        rendererAssists.setPeakingColor(c)
    }
    fun setZebraLevel(z: ZebraLevel) {
        rendererAssists.setZebraLevel(z)
    }

    /** Enables/disables GL-thread histogram and/or waveform computation feeding [onAnalysis]. */
    fun setAnalysis(histogram: Boolean, waveform: Boolean) {
        rendererAssists.setAnalysis(histogram, waveform)
    }

    /** Forces the luma readback for the app-side auto-exposure loop (SHUTTER/ISO priority). */
    fun setAeMetering(enabled: Boolean) {
        rendererAssists.setAeMetering(enabled)
    }

    /** Gamma Display Assist: normal monitor image while recording O-Log (the file stays log). */
    fun setGammaAssist(enabled: Boolean) {
        rendererAssists.setGammaAssist(enabled)
    }

    /**
     * Requests a fresh, converged grey-card sample from the accepted unlocked-AUTO session.
     * The returned sample retains that exact session as an opaque owner; callers must consume it
     * through [consumeCustomWbSampleIfCurrent] after crossing thread boundaries.
     */
    internal fun requestCustomWbSample(onResult: (CustomWbSample?) -> Unit) {
        val accepted = synchronized(this) {
            currentAcceptedCameraSession()?.takeIf {
                controls.wbMode == WbMode.AUTO && !controls.awbLock
            }
        }
        if (accepted == null) {
            runCatching { onResult(null) }
            return
        }
        accepted.controller.requestCustomWbSample { gains ->
            val sample = synchronized(this) {
                gains?.takeIf { ownsCustomWbSample(accepted) }?.let {
                    CustomWbSample(
                        gains = WbGains(it.red, it.greenEven, it.greenOdd, it.blue),
                        ownerToken = accepted,
                    )
                }
            }
            runCatching { onResult(sample) }
        }
    }

    /** Atomically rechecks the sample owner and lets the UI publish it before ownership can change. */
    internal fun consumeCustomWbSampleIfCurrent(
        sample: CustomWbSample,
        onCurrent: (WbGains) -> Unit,
    ): Boolean = synchronized(this) {
        val accepted = sample.ownerToken as? AcceptedCameraSession ?: return@synchronized false
        if (!ownsCustomWbSample(accepted)) return@synchronized false
        onCurrent(sample.gains)
        true
    }

    private fun ownsCustomWbSample(expected: AcceptedCameraSession): Boolean =
        customWbSampleOwnerIsCurrent(
            currentAcceptedSession = acceptedCameraSession,
            expectedAcceptedSession = expected,
            currentController = controller,
            expectedController = expected.controller,
            currentSessionGeneration = cameraSessionGeneration.get(),
            expectedSessionGeneration = expected.sessionGeneration,
            cameraReady = cameraReady,
            paused = paused,
            wbMode = controls.wbMode,
            awbLocked = controls.awbLock,
        )

    /**
     * Tap-to-focus/meter. Maps a VIEW-normalized tap [(nx,ny), origin top-left] to a
     * SENSOR-normalized point by inverting the GL content rotation applied to the preview — the
     * sensor orientation plus the teleconverter's afocal 180° (normalized to 0/90/180/270). The
     * centered tap is rotated by -total degrees and re-centered, then forwarded to the controller.
     *
     * A tap made while the punch-in loupe is ACTIVE is additionally composed through the loupe's
     * crop+center (loupeAdjustedTap, AGG4-11) so AF/metering land on the scene point actually
     * under the finger. EIS shift is not composed (app-side EIS is disabled; shift is 0). Axis
     * signs remain an on-device calibration item.
     *
     * Returns true only when one current Ready session accepted the camera-thread task. AF HOLD is
     * published separately after [CameraController] confirms that Camera2 accepted the required
     * trigger (unless AF Lock owns the lens) and repeating request; AF convergence remains
     * asynchronous result telemetry.
     */
    fun setTapPoint(nx: Float, ny: Float): Boolean = synchronized(this) {
        val c = caps ?: return@synchronized false
        val accepted = acceptedCameraSession ?: return@synchronized false
        val canHoldFocus = touchAfMayTrigger(
            touchAfActive = true,
            maxAfRegions = c.maxAfRegions,
            focusMode = controls.focusMode,
            afModes = c.afModes,
        )
        if (!tapPointAdmissionAllowed(
                currentController = controller,
                acceptedController = accepted.controller,
                currentSessionGeneration = cameraSessionGeneration.get(),
                acceptedSessionGeneration = accepted.sessionGeneration,
                cameraReady = cameraReady,
                paused = paused,
                canHoldFocus = canHoldFocus,
            )
        ) {
            return@synchronized false
        }
        // Map NOW, against the loupe center the user actually sees. If another submission is in
        // flight, keeping only raw view coordinates and mapping after it completes would compose the
        // latest tap through a future center the user had not seen when tapping.
        val geometry = mapTapFocusGeometry(
            nx = nx,
            ny = ny,
            sensorOrientation = c.sensorOrientation,
            teleconverter = teleconverterMode,
            punchActive = rendererAssists.isPunchInEnabled(),
            sensorCenter = loupeCenterSensorX to loupeCenterSensorY,
            loupeCenter = loupeCenterTexX to loupeCenterTexY,
            previewRotationDegrees = previewRotationDegrees(),
            mirrorX = facing == CameraFacing.FRONT,
        )
        val attempt = PendingTapFocus(
            session = accepted,
            viewPoint = geometry.viewPoint,
            sensorPoint = geometry.sensorPoint,
            loupePoint = geometry.loupePoint,
        )
        // This HAL can spend ~170-250 ms replacing its repeating request. Preserve rapid user taps
        // without stacking rebuilds: retain one mapped latest-wins snapshot.
        if (pendingTapFocus != null) {
            deferredTapFocus = attempt
            return@synchronized true
        }
        submitTapFocusLocked(attempt)
    }

    /** Engine-monitor-only: submits a mapped point after exact accepted-session revalidation. */
    private fun submitTapFocusLocked(attempt: PendingTapFocus): Boolean {
        val accepted = attempt.session
        val c = caps ?: return false
        if (!tapFocusSessionOwnerIsCurrent(
                currentAcceptedSession = acceptedCameraSession,
                expectedAcceptedSession = accepted,
                currentController = controller,
                expectedController = accepted.controller,
                currentSessionGeneration = cameraSessionGeneration.get(),
                expectedSessionGeneration = accepted.sessionGeneration,
                paused = paused,
            ) || !touchAfMayTrigger(
                touchAfActive = true,
                maxAfRegions = c.maxAfRegions,
                focusMode = controls.focusMode,
                afModes = c.afModes,
            )
        ) {
            return false
        }
        val queued = accepted.controller.setMeteringPoint(
            attempt.sensorPoint.first,
            attempt.sensorPoint.second,
        ) { result -> completeTapFocus(attempt, result) }
        if (queued) pendingTapFocus = attempt
        return queued
    }

    /** Camera-thread completion of one queued point; publishes only an actually submitted request. */
    private fun completeTapFocus(
        attempt: PendingTapFocus,
        submission: TapFocusSubmissionResult,
    ) {
        val publication = synchronized(this) {
            val accepted = attempt.session
            val sessionCurrent = submission == TapFocusSubmissionResult.ACCEPTED &&
                tapFocusSessionOwnerIsCurrent(
                    currentAcceptedSession = acceptedCameraSession,
                    expectedAcceptedSession = accepted,
                    currentController = controller,
                    expectedController = accepted.controller,
                    currentSessionGeneration = cameraSessionGeneration.get(),
                    expectedSessionGeneration = accepted.sessionGeneration,
                    paused = paused,
                )
            when (tapFocusCompletionDecision(pendingTapFocus === attempt, submission, sessionCurrent)) {
                TapFocusCompletionDecision.IGNORE -> return@synchronized null
                TapFocusCompletionDecision.KEEP_PREVIOUS -> {
                    pendingTapFocus = null
                    if (tapFocusOwner == null) resetTapGeometryLocked()
                    val deferred = deferredTapFocus
                    deferredTapFocus = null
                    deferred?.let(::submitTapFocusLocked)
                    return@synchronized null
                }
                TapFocusCompletionDecision.RETIRE -> {
                    val liveRequest = controller === accepted.controller &&
                        cameraSessionGeneration.get() == accepted.sessionGeneration && !paused
                    return@synchronized retireTapFocusLocked(rebuildPreview = liveRequest)
                }
                TapFocusCompletionDecision.PUBLISH_HELD -> Unit
            }

            pendingTapFocus = null
            tapFocusOwner = accepted
            loupeCenterTexX = attempt.loupePoint.first
            loupeCenterTexY = attempt.loupePoint.second
            loupeCenterSensorX = attempt.sensorPoint.first
            loupeCenterSensorY = attempt.sensorPoint.second
            // Lifecycle invalidation takes this same monitor, so its later center reset always wins.
            gl.setPunchInCenter(attempt.loupePoint.first, attempt.loupePoint.second)
            val heldPublication = nextTapFocusPublication(held = true, point = attempt.viewPoint)
            val deferred = deferredTapFocus
            deferredTapFocus = null
            deferred?.let(::submitTapFocusLocked)
            heldPublication
        }
        publication?.let { onTapFocusChange?.invoke(it) }
    }

    // Tap-owned loupe center mirrors in BOTH spaces (AGG4-11): texcoord (what GL magnifies about)
    // and sensor (what a magnified tap's metering composes against). Reset with clearTapPoint.
    @Volatile private var loupeCenterTexX = 0.5f
    @Volatile private var loupeCenterTexY = 0.5f
    @Volatile private var loupeCenterSensorX = 0.5f
    @Volatile private var loupeCenterSensorY = 0.5f

    /**
     * The FUNCTIONAL tap release: drops the tap-owned AE/AF region and re-centers the loupe.
     * Called on explicit reset and by the engine's optics-remap transaction — NOT from the 2 s
     * reticle timer, which is visual-only (AGG4-3: the tapped focus must HOLD until a new tap,
     * a focus-mode change, an explicit reset, or a remap; the timer used to silently return AF
     * to continuous hunting and re-center the loupe mid-composition).
     */
    fun clearTapPoint(rebuildPreview: Boolean = true) {
        val tapPublication = synchronized(this) {
            val hadTapState = tapFocusOwner != null || pendingTapFocus != null || deferredTapFocus != null
            val publication = retireTapFocusLocked(rebuildPreview)
            // No tracked point means there is no Camera2 key state to clear. Rebuilding here made
            // every optics tap pay an extra setRepeatingRequest even when tap AF had never been used.
            if (!hadTapState) {
                resetTapGeometryLocked()
            }
            publication
        }
        if (BuildConfig.DEBUG) Log.i("CameraEngine", "TapFocus: cleared")
        tapPublication?.let { onTapFocusChange?.invoke(it) }
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
        if (!nativeAcquisitionMayProceed() || !started || paused) return
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
            if (!nativeAcquisitionMayProceed() || !sessionReopenMayProceed(
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
            val tapPublication = retireTapFocusLocked(rebuildPreview = false)
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
                    recorderTeardownInFlight = true
                    recorder = null
                    val ownedGl = checkNotNull(activeRecordingGl) { "Recorder has no GL owner" }
                    activeRecordingGl = null
                    val uri = activeRecordingUri
                    val captureId = activeRecordingCaptureId
                    activeRecordingUri = null
                    activeRecordingCaptureId = 0 // stale-until-overwritten otherwise (ARCH-8)
                    OwnedRecording(owned, uri, captureId, ownedGl)
                }
            }
            Triple(publication, ownedRecording, tapPublication)
        }
        onCameraReadyChange?.invoke(outcome.first)
        outcome.third?.let { onTapFocusChange?.invoke(it) }
        android.util.Log.e("CameraEngine", "Active camera failure", failure)
        onStatus?.invoke("Camera error. Recovering.")
        outcome.second?.let { owned ->
            detachAndFinalizeRecording(owned.gl, owned.recorder, owned.uri, owned.captureId)
            owned.gl.setTransfer(transfer)
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
        if (!nativeAcquisitionMayProceed() || !started || paused || recorder != null) return
        if (controller !== failedController) return
        if (cameraRecoveryAttempts >= MAX_CAMERA_RECOVERY_ATTEMPTS) {
            // Recovery exhausted: say so instead of silently leaving a black viewfinder behind an
            // interactive-looking UI. cameraReady stays false, so the shutter is dimmed too; a
            // background/foreground cycle (resume()) retries with a fresh attempt budget.
            onStatus?.invoke("Camera unavailable. Reopen the app.")
            return
        }
        cameraRecoveryAttempts++
        runCatching {
            timelapseScheduler.schedule(
                {
                    if (nativeAcquisitionMayProceed() && controller === failedController) {
                        reopenForSession(failedController)
                    }
                },
                CAMERA_RECOVERY_DELAY_MS,
                java.util.concurrent.TimeUnit.MILLISECONDS,
            )
        }
    }

    /** Bounded retry for transient selection/capability failures before the first Ready session. */
    private fun scheduleColdStartRetry(transaction: OpticsTransaction, reason: String) {
        val canRun = nativeAcquisitionMayProceed() && started && !paused &&
            recorder == null && gl.inputSurface != null
        when (val failure = coldStartRetryGate.failed(
            expectedGeneration = transaction.generation,
            currentGeneration = opticsIntentGeneration.get(),
            canRun = canRun,
        )) {
            ColdStartRetryGate.Failure.Ignore -> Unit
            ColdStartRetryGate.Failure.Exhausted ->
                onStatus?.invoke("Camera unavailable. Reopen the app.")
            is ColdStartRetryGate.Failure.Retry -> {
                onStatus?.invoke("$reason. Retrying…")
                runCatching {
                    timelapseScheduler.schedule(
                        {
                            val retryable = nativeAcquisitionMayProceed() && started && !paused &&
                                recorder == null && gl.inputSurface != null
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
    fun setAudioGain(g: Float) { audioGain = normalizeAudioGain(g) }
    /** Directional-audio scene (Sound Focus/Stage); applies on the next [startRecording]. */
    fun setAudioScene(s: AudioScene) { audioScene = s }
    /** Preferred recording input; resolved against connected AudioDeviceInfo entries at record start. */
    fun setAudioInputPreference(p: AudioInputPreference) { audioInputPreference = p }

    /** Still-photo center-crop aspect ratio; applies to HEIF and JPEG. W4_3 = no crop. */
    fun setAspectRatio(a: AspectRatio) {
        val hiResBefore = resolvedHiResStill()
        aspectRatio = a
        // The finder PIP is 4:3-only (see pushTeleFinder); keep its resolved flag in step.
        pushTeleFinder()
        // Hi-res is 4:3-only too, but unlike the finder flag it is SESSION state (the still reader
        // size fixes at configureStreams): a 4:3↔16:9 flip that changes the resolved admission
        // must rebuild the session, or a 16:9 selection would keep silently shooting uncropped
        // 4:3 hi-res passthrough frames.
        if (resolvedHiResStill() != hiResBefore) reopenForSession()
    }

    /**
     * The ONE engine-side hi-res resolution ([hiResAdmitted]): defaults describe the CURRENT route;
     * reconfigure passes its freshly selected route explicitly. Standalone here means the full
     * standalone-camera truth — an unrouted selection of a non-logical camera (both halves of the
     * documented blob-allocation/RAW crash classes).
     */
    private fun resolvedHiResStill(
        sel: TeleSelection? = selection,
        c: CameraCaps? = caps,
    ): Boolean = hiResAdmitted(
        requested = hiResStillIntent,
        videoMode = videoMode,
        aspect = aspectRatio,
        standalone = sel != null && sel.physicalId == null && c?.isLogicalMultiCamera == false,
        advertised = c?.hiResJpegSize != null,
    )

    /**
     * Hi-res still intent. When the RESOLVED admission for the current route changes, the session
     * must rebuild (the still reader size is fixed at configureStreams) — through the same
     * generation-owned reopen door every other session-affecting toggle uses, never a bespoke
     * close/open shortcut. A toggle that does not change the resolved value (video mode, 16:9, a
     * camera that advertises nothing) costs no reopen; mode/lens/TC/aspect doors re-resolve on
     * their own paths.
     */
    fun setHiResStill(enabled: Boolean) {
        if (hiResStillIntent == enabled) return
        val before = resolvedHiResStill()
        hiResStillIntent = enabled
        if (resolvedHiResStill() != before) reopenForSession()
    }

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
        // Lens presets and the TC toggle (which routes through here) are REAR-only doors: while
        // FRONT they refuse instead of silently flipping the route out from under the operator —
        // the flip button is the one exit. Defensive twin of the ViewModel's gate, through the same
        // shared decision so their answers cannot drift.
        when (backOpticsDoorRefusal(recorder != null, facing == CameraFacing.FRONT)) {
            BackOpticsRefusal.RECORDING -> { onStatus?.invoke("Stop REC first"); return }
            BackOpticsRefusal.FRONT_ROUTE -> { onStatus?.invoke("Switch to rear camera first"); return }
            BackOpticsRefusal.NONE -> Unit
        }
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
        // The optics packet above already updated [teleconverterMode]; resolve the finder PIP now
        // so a TC-off (or a lens pick that drops TC) can never leave the GL PIP drawing after the
        // Compose border is gone — applyStabilization re-pushes, but only once the async session
        // reconfigure completes.
        pushTeleFinder()
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
                        overrideId = id
                    }
                } else {
                    reconfigureCamera(id, transaction)
                }
            }
        }
    }

    /**
     * The FRONT (selfie) camera door — a first-class optics transaction like setLens/setVideoMode,
     * NOT a transaction-less close/open. Order inside the desired packet matters and is deliberate:
     * entering FRONT forces the teleconverter OFF in the SAME publication (the converter is a
     * rear-3× accessory; a front session with the afocal 180° or the TC session type would be
     * nonsense), so no intermediate front+TC state is ever observable. Zoom becomes front-lens-local
     * 1× on entry; leaving returns to the retained rear band's home framing (unified preset in
     * photo, lens-local 1× in video — resolveNonTeleId's mode-split homes). Remap-door hygiene:
     * beginOpticsTransaction retires tap focus, pushTeleFinder re-resolves (false — TC just went
     * off), the ViewModel invalidates its zoom glide at the same door, and hi-res is re-resolved by
     * reconfigureCamera's route-explicit resolvedHiResStill(sel, c) — the facing door ALWAYS
     * reconfigures, so that resolve IS the explicit re-resolve the other doors perform inline.
     * A failed open rolls back facing with the rest of the packet through rollbackOptics.
     */
    fun setFrontCamera(enabled: Boolean) {
        if (recorder != null) { onStatus?.invoke("Stop REC first"); return }
        if ((facing == CameraFacing.FRONT) == enabled) return
        val intentGeneration = facingIntentGeneration.incrementAndGet()
        val transaction = beginOpticsTransaction {
            facing = if (enabled) CameraFacing.FRONT else CameraFacing.BACK
            if (enabled) teleconverterMode = false
            controls = controls.copy(
                zoomRatio = if (enabled || videoMode) 1f else lensChoice.zoomPreset,
            )
            // A front trip drops the pre-TELE return framing: the snapshot is an absolute ratio in
            // a rear scale, and TELE does not survive the trip (leaving FRONT lands on the plain
            // rear home, TC off), so a stale restore target must not linger.
            preTeleUnifiedZoom = Float.NaN
            overrideId = null
        }.first
        // TC state changed above (entering) or the finder inputs must re-resolve anyway (leaving):
        // resolve synchronously so the GL PIP cannot outlive the facing flip while the async
        // reconfigure is still queued — the same door discipline as setLens/setVideoMode.
        pushTeleFinder()
        setupExecutor.execute {
            if (!ownsOpticsTransaction(transaction) ||
                facingIntentGeneration.get() != intentGeneration || paused || recorder != null
            ) return@execute
            val id = if (enabled) {
                cachedFront()?.logicalId
            } else {
                resolveNonTeleId(lensChoice)
            }
            if (id == null) {
                rollbackOptics(
                    transaction,
                    if (enabled) "Front camera unavailable" else "Camera unavailable; facing unchanged",
                )
                return@execute
            }
            // Facing always changes the physical camera — no same-route fast path exists here.
            reconfigureCamera(id, transaction)
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
        // A debug override pins an explicit rear-route id; it is not the facing door, so it also
        // drops FRONT (selectCurrentLens would otherwise ignore the pinned id while facing FRONT).
        val transaction = beginOpticsTransaction {
            facing = CameraFacing.BACK
            overrideId = id
        }.first
        reconfigureCamera(id, transaction)
    }

    private fun reconfigureCamera(
        id: String?,
        transaction: OpticsTransaction,
        startup: Boolean = false,
    ) {
        if (!nativeAcquisitionMayProceed()) return
        synchronized(this) {
            if (!ownsOpticsTransaction(transaction)) return
            overrideId = id
        }
        if (!started) return
        val ownedGl = glOwners.current()
        val input = ownedGl.inputSurface ?: run {
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
            if (!nativeAcquisitionMayProceed() || !glInputTransactionMayProceed(
                    ownerCurrent = glOwners.owns(ownedGl),
                    engineStarted = started,
                    inputCurrent = ownedGl.inputSurface === input,
                )
            ) return@execute
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
            if (!nativeAcquisitionMayProceed() || paused || recorder != null) return@execute
            // This lightweight physical-member walk happens on setupExecutor while the old camera
            // is still streaming. The still callback remains cache-only even on the first lens hit.
            prefetchLensExifMetadata(sel, c)
            if (!ownsOpticsTransaction(transaction)) return@execute
            if (!nativeAcquisitionMayProceed() || paused || recorder != null) return@execute

            // DUAL-OPEN: open the NEXT device while the old camera keeps streaming (~120 ms of the
            // blackout overlapped away). The preview surface still belongs to the old session, so
            // the new session is deferred until the old controller closes. If the HAL refuses a
            // second open (shared physical sensor / max-cameras), fall back to the sequential path.
            // A concurrent eviction of the OLD device is harmless: `controller` is already the new
            // one, so the old error handler's identity guard no-ops and its close() self-runs.
            val old = controller
            val next = wireController(ownedGl)
            val candidatePublication = synchronized(this) {
                if (UnsafeRecorderQuarantine.isActive() || !glInputTransactionMayProceed(
                        ownerCurrent = glOwners.owns(ownedGl),
                        engineStarted = started,
                        inputCurrent = ownedGl.inputSurface === input,
                    ) || !ownsOpticsTransaction(transaction)
                ) {
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
            var openStarted = false
            terminalAcquisitionGate.runIfOpen cameraOpen@{
                if (UnsafeRecorderQuarantine.isActive()) return@cameraOpen
                openStarted = true
                // Resolved against the freshly selected route, BEFORE configure (the deferred
                // session included) — the controller only re-checks the standalone/advertised
                // halves defensively at its reader seam.
                next.hiResStill = resolvedHiResStill(sel, c)
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
                    diagnosticOpticsGeneration = transaction.generation,
                    deferSession = true,
                    onDeviceOpened = { deviceOk.set(true); deviceUp.countDown() },
                    onReady = { outputs ->
                        if (nativeAcquisitionMayProceed() && glInputTransactionMayProceed(
                                ownerCurrent = glOwners.owns(ownedGl),
                                engineStarted = started,
                                inputCurrent = ownedGl.inputSurface === input,
                            )
                        ) {
                            commitOpticsReady(
                                expectedGeneration = transaction.generation,
                                expectedController = next,
                                photoOutputs = outputs,
                                expectedSessionGeneration = candidatePublication.sessionGeneration,
                            )
                        } else {
                            next.close()
                        }
                    },
                    onError = { failure ->
                        deviceUp.countDown() // an open-phase failure also releases the latch (fallback)
                        // A concurrent-open refusal is expected on devices that cannot dual-open a
                        // shared sensor; the setup task below owns that local sequential fallback.
                        // Only an error AFTER the next device opened is a real active-controller fault.
                        if (nativeAcquisitionMayProceed() && deviceOk.get() && glInputTransactionMayProceed(
                                ownerCurrent = glOwners.owns(ownedGl),
                                engineStarted = started,
                                inputCurrent = ownedGl.inputSurface === input,
                            )
                        ) {
                            handleActiveCameraFailure(next, failure)
                        } else if (!nativeAcquisitionMayProceed()) {
                            next.close()
                        }
                    },
                )
            }
            if (!openStarted) {
                next.close()
                synchronized(this) {
                    if (controller === next) controller = null
                }
                return@execute
            }
            // The old camera streams through the new device's open. Bounded wait: a refusal or a
            // wedged open must degrade to the sequential path, not hang the setup thread.
            deviceUp.await(2, java.util.concurrent.TimeUnit.SECONDS)
            // The blocking wait is an ownership boundary: pause, REC, or a newer reopen may have
            // superseded this attempt while the setup thread was parked. Do not publish sizes or
            // start a deferred session from an obsolete device; release every local handle.
            if (!nativeAcquisitionMayProceed()) {
                next.close()
                synchronized(this) {
                    if (controller === next) controller = null
                }
                return@execute
            }
            if (!glInputTransactionMayProceed(
                    ownerCurrent = glOwners.owns(ownedGl),
                    engineStarted = started,
                    inputCurrent = ownedGl.inputSurface === input,
                )
            ) {
                next.close()
                old?.close()
                synchronized(this) {
                    if (controller === next) controller = null
                }
                return@execute
            }
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
            if (!nativeAcquisitionMayProceed() || paused || recorder != null || controller !== next) {
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
            ownedGl.setCameraPreviewSize(previewStreamSize.width, previewStreamSize.height)
            old?.close()
            if (deviceOk.get()) {
                var sessionStarted = false
                terminalAcquisitionGate.runIfOpen sessionStart@{
                    if (UnsafeRecorderQuarantine.isActive()) return@sessionStart
                    sessionStarted = true
                    next.startDeferredSession()
                }
                if (!sessionStarted) {
                    next.close()
                    synchronized(this) {
                        if (controller === next) controller = null
                    }
                }
            } else {
                // Sequential fallback: the HAL refused the concurrent open.
                next.close()
                synchronized(this) {
                    if (controller === next) controller = null
                }
                if (!nativeAcquisitionMayProceed() || paused || recorder != null) return@execute
                openCamera(ownedGl, input, transaction)
            }
        }
    }

    private fun selectCurrentLens(): TeleSelection? {
        // FRONT owns the whole selection: the rear override/focal resolvers below are meaningless
        // for it, and the facing door already cleared overrideId in its transaction. Standalone-
        // shaped (physicalId == null), so every downstream session/capture axis behaves like the
        // proven rear standalone path.
        if (facing == CameraFacing.FRONT) return cachedFront()
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
        // ONE submit per boost flip: the boost's own preview rebuild carries the current exact
        // ratio, so no separate corrective submit runs. The old rebuild-then-correct order at
        // gesture end paid two ~180 ms repeating-request stalls back to back AND transiently
        // re-submitted the stale mid-gesture wide-aimed ratio (the rebuild read the controller's
        // last stored zoom, which the throttled wide submit had written) — real frames and the
        // video encoder saw the wrong framing for a beat. Gesture start also swallows the first
        // fast-path tick (throttle stamp below): GL zoomComp covers instantly and the HAL follows
        // at the next ≥200 ms window. The GL compensation converges to 1 as matching results land.
        lastHalZoomSubmitMs = android.os.SystemClock.uptimeMillis()
        controller?.setSmoothPreviewBoost(active, finalZoom = controls.zoomRatio)
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
    private fun seedGlZoom(ownedGl: GlPipeline = glOwners.current()) {
        ownedGl.setZoomTarget(controls.zoomRatio)
        ownedGl.setHalZoom(controls.zoomRatio)
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
        val z = clampToOrderedBounds(ratio, r?.lower ?: ratio, hi)
        // Packet writers (rollback / caps-normalize on setupExecutor) replace [controls] wholesale
        // under this monitor. Take it for the zoom read-modify-write too: @Volatile alone gives
        // visibility, not atomicity, so a 60 Hz pinch flush could overwrite (lose) an entire
        // normalized packet published between its read and write-back — exactly around the
        // lens/TELE/mode churn where rollbacks happen.
        synchronized(this) { controls = controls.copy(zoomRatio = z) }
        // The PREVIEW zooms instantly: GL crops the last frame to the requested ratio and
        // self-redraws (every setRepeatingRequest stalls this HAL's stream ~180 ms — measured —
        // so per-tick HAL submits made zoom read as ~5 fps no matter how smooth the input was).
        gl.setZoomTarget(z)
        // The HAL follows at a throttled pace. Mid-gesture it is aimed slightly WIDE
        // (÷ZOOM_GESTURE_MARGIN) so the GL crop has field for instant zoom-out too; the exact
        // value lands at the quiet-window landing (landExactZoom) or gesture end
        // (setZoomInteraction(false)). The throttle/wide-aim decision is the pure, unit-tested
        // resolveHalZoomSubmit. requestRatio carries the EXACT z so stills never inherit the aim.
        val now = android.os.SystemClock.uptimeMillis()
        val plan = resolveHalZoomSubmit(
            requestedZoom = z,
            interactionActive = zoomInteractionActive,
            nowMs = now,
            lastSubmitMs = lastHalZoomSubmitMs,
            gestureMargin = ZOOM_GESTURE_MARGIN,
            throttleMs = ZOOM_HAL_THROTTLE_MS,
            rangeLower = r?.lower,
            rangeUpper = r?.upper,
        )
        if (plan.submitNow) {
            lastHalZoomSubmitMs = now
            controller?.setZoomRatio(plan.halTarget, requestRatio = plan.controlsZoomRatio)
        } else {
            // A swallowed tick must STILL update the controller's still-request truth: the
            // viewfinder (GL target) already frames z, and a shutter press inside the throttle
            // window would otherwise capture the PREVIOUS tick's ratio (aggregate AGG3-27).
            controller?.noteRequestZoom(plan.controlsZoomRatio)
        }
    }

    /**
     * Lands the EXACT (non-wide-aimed) ratio while a gesture is still formally active but has gone
     * quiet for one throttle window. Without this, the last throttled submit's ~1.2×-wide framing
     * held for the full 700 ms interaction tail — a recorded clip carried it after finger-up (the
     * encoder only sees real frames; GL zoomComp masks it in the preview only). One spaced
     * fast-path swap, NOT a boost flip — the fps-boost window and its single gesture-end rebuild
     * are unchanged, so this does not recreate the back-to-back double-stall c92eada removed.
     */
    fun landExactZoom() {
        if (!zoomInteractionActive) return
        lastHalZoomSubmitMs = android.os.SystemClock.uptimeMillis()
        controller?.setZoomRatio(controls.zoomRatio)
    }

    /**
     * Commits [ratio] as the engine/GL/still-request zoom truth WITHOUT a HAL submit (AGG4-1).
     * The zoom-OUT leading edge calls this immediately before setZoomInteraction(true), whose
     * boost rebuild then carries `controls.zoomRatio` as its finalZoom — the gesture edge's ONE
     * submit. The old leading-edge fast-path submit PLUS that rebuild paid two back-to-back
     * ~180 ms repeating-request stalls at every fresh pinch-out (the exact anti-pattern the
     * gesture-END path was rewritten to remove); the encoder saw the longer real-frame gap even
     * though GL zoomComp masked it in the preview.
     */
    fun commitZoomForBoost(ratio: Float) {
        val r = caps?.zoomRatioRange
        val hi = if (teleconverterMode) {
            minOf(r?.upper ?: Float.MAX_VALUE, TELE_MAX_DISPLAY_ZOOM / TELE_DISPLAY_BASE)
        } else {
            r?.upper ?: Float.MAX_VALUE
        }
        val z = clampToOrderedBounds(ratio, r?.lower ?: ratio, hi)
        // Same monitor rule as setZoomRatio: packet writers replace [controls] wholesale under it.
        synchronized(this) { controls = controls.copy(zoomRatio = z) }
        gl.setZoomTarget(z)
        // Still-request truth must follow even though no repeating submit happens here.
        controller?.noteRequestZoom(z)
    }

    @Volatile private var zoomInteractionActive = false
    @Volatile private var lastHalZoomSubmitMs = 0L

    // ---- Photo ----

    private fun currentAcceptedCameraSession(): AcceptedCameraSession? {
        if (UnsafeRecorderQuarantine.isActive()) return null
        val accepted = acceptedCameraSession ?: return null
        return accepted.takeIf {
            cameraReady && !paused && controller === it.controller &&
                cameraSessionGeneration.get() == it.sessionGeneration
        }
    }

    private fun acceptedSessionIsCurrent(accepted: AcceptedCameraSession): Boolean =
        acceptedCameraSession === accepted && currentAcceptedCameraSession() === accepted

    /** Returns true only when this press was admitted to a real still target. */
    fun capturePhoto(formats: PhotoFormats, singleShot: Boolean = false): Boolean {
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
            onStatus?.invoke("Still capture unavailable")
            return false
        }
        when {
            formats.wantsProcessedStill && !effFormats.wantsProcessedStill && effFormats.dngRaw ->
                onStatus?.invoke("HEIF/JPEG unavailable; using DNG")
            formats.dngRaw && !effFormats.dngRaw ->
                onStatus?.invoke("RAW unavailable")
        }
        val ctrl = accepted.controller
        when (captureDriveMode(driveMode, singleShot)) {
            DriveMode.SINGLE -> {
                val snapshotLease = if (effFormats.wantsProcessedStill) {
                    singleProcessedSnapshotBudget.tryAcquire()
                } else {
                    null
                }
                if (effFormats.wantsProcessedStill && snapshotLease == null) {
                    onStatus?.invoke("Finishing previous photo")
                    return false
                }
                val callback = runCatching {
                    photoCallback(
                        effFormats,
                        controls,
                        hiRes = accepted.outputs.hiRes,
                        retainedSnapshotLease = snapshotLease,
                    )
                }.getOrElse { failure ->
                    snapshotLease?.release()
                    android.util.Log.e("CameraEngine", "Photo callback creation failed", failure)
                    onStatus?.invoke("Photo capture failed")
                    return false
                }
                val dispatched = runCatching {
                    ctrl.capturePhoto(
                        effFormats.wantsProcessedStill,
                        effFormats.dngRaw,
                        callback,
                    )
                }
                if (dispatched.isFailure) {
                    callback.onError(dispatched.exceptionOrNull()!!)
                    return false
                }
            }
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
                photoCallback(formats, controls, hiRes = accepted.outputs.hiRes) { fire(shot + 1) },
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
                    photoCallback(formats, stepControls, hiRes = accepted.outputs.hiRes) { fire(i + 1) },
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
                photoCallback(formats, stepControls, hiRes = accepted.outputs.hiRes) { fire(i + 1) },
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
                                onStatus?.invoke("Still capture unavailable")
                                stopTimelapse()
                                return@schedule
                            }
                            accepted.controller.capturePhoto(
                                formats.wantsProcessedStill,
                                formats.dngRaw,
                                photoCallback(formats, controls, hiRes = accepted.outputs.hiRes) {
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


    // Save lanes live in the extracted StillCapturePipeline (ARCH4-3 step 1); the lambdas read
    // the live listener fields at invoke time so late wiring behaves exactly as before.
    private val stillPipeline = StillCapturePipeline(
        context,
        emitStatus = { msg -> onStatus?.invoke(msg) },
        emitMediaSaved = { uri, id -> onMediaSaved?.invoke(uri, id) },
        emitRawSaved = { uri, id -> onRawSaved?.invoke(uri, id) },
    )
    private val singleProcessedSnapshotBudget = ProcessedSnapshotBudget()

    private fun shotSpec(shotControls: ManualControls, hiRes: Boolean): ShotSpec {
        val shotCaps = caps
        val shotTeleconverter = teleconverterMode
        val shotFrontFacing = facing == CameraFacing.FRONT
        val requestedAtMs = System.currentTimeMillis()
        val captureId = captureSeq.incrementAndGet()
        val rotation = shotCaps?.let {
            RotationMath.captureRotationDegrees(
                it.sensorOrientation,
                shotTeleconverter,
                gyro.currentDeviceOrientation(),
                frontFacing = shotFrontFacing,
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
            hiRes = hiRes,
            frontFacing = shotFrontFacing,
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
        // Accepted-session truth ([PhotoSessionOutputs.hiRes]) snapshotted at dispatch: the save
        // lane must pick passthrough-vs-decode from the session the shot actually fired against,
        // not whatever session is accepted when the image lands.
        hiRes: Boolean,
        retainedSnapshotLease: ProcessedSnapshotBudget.Lease? = null,
        onDone: (() -> Unit)? = null,
    ): CameraController.PhotoCallback {
        require(retainedSnapshotLease == null || formats.wantsProcessedStill)
        val requestSpec = shotSpec(shotControls, hiRes)
        val expectedOutputExtensions = buildList {
            if (formats.heif) add("heic")
            if (formats.jpeg) add("jpg")
            if (formats.dngRaw) add("dng")
        }
        val familyStem = requestSpec.familyKey.displayName("complete").substringBeforeLast('.')
        val remainingSaveLanes = java.util.concurrent.atomic.AtomicInteger(
            (if (formats.wantsProcessedStill) 1 else 0) + (if (formats.dngRaw) 1 else 0),
        )
        val completionDelivered = java.util.concurrent.atomic.AtomicBoolean(false)
        val finishSequence = {
            if (remainingSaveLanes.get() == 0 && completionDelivered.compareAndSet(false, true)) {
                if (com.hletrd.findx9tele.BuildConfig.DEBUG) {
                    android.util.Log.i(
                        "CameraEngine",
                        "CaptureFamily: settled stem=$familyStem " +
                            "outputs=${expectedOutputExtensions.joinToString(",")}",
                    )
                }
                onDone?.invoke()
            }
            Unit
        }
        val processedFinished = java.util.concurrent.atomic.AtomicBoolean(!formats.wantsProcessedStill)
        val finishProcessed = {
            if (processedFinished.compareAndSet(false, true)) {
                retainedSnapshotLease?.release()
                if (remainingSaveLanes.decrementAndGet() == 0) finishSequence()
            }
            Unit
        }
        val dngFinished = java.util.concurrent.atomic.AtomicBoolean(!formats.dngRaw)
        val finishDng = {
            if (dngFinished.compareAndSet(false, true) && remainingSaveLanes.decrementAndGet() == 0) {
                finishSequence()
            }
            Unit
        }
        // Status observers are UI-owned and must never strand a retained-snapshot lease or a
        // BURST/AEB/timelapse continuation if one is detached or throws during teardown.
        val reportStatus: (String) -> Unit = { message ->
            runCatching { onStatus?.invoke(message) }
        }
        return object : CameraController.PhotoCallback {
            override fun onPhoto(
                jpeg: Image?,
                raw: Image?,
                result: TotalCaptureResult,
                rawChars: CameraCharacteristics,
                takenAtMs: Long,
            ) {
                var processedQueued = false
                var dngPublishQueued = false
                try {
                    val spec = requestSpec.copy(takenAtMs = takenAtMs)
                    // Copy the live Image first so the ImageReader slot and Camera2 handler are held
                    // for the shortest possible interval; EXIF composition is cache-only.
                    val processedSnapshot = if (formats.wantsProcessedStill && jpeg != null) {
                        runCatching { StillSnapshot.from(jpeg) }.getOrNull()
                    } else {
                        null
                    }
                    val exifShot = exifShotOf(result, spec)

                    if (formats.wantsProcessedStill) {
                        if (jpeg != null) {
                            if (processedSnapshot != null) {
                                val queued = runCatching {
                                    ioExecutor.execute {
                                        try {
                                            val bytes = runCatching { processedSnapshot.jpegBytes() }.getOrNull()
                                            if (bytes == null) {
                                                reportStatus("Photo save failed")
                                            } else {
                                                stillPipeline.saveProcessedStills(
                                                    bytes,
                                                    spec,
                                                    exifShot,
                                                    wantHeif = formats.heif,
                                                    wantJpeg = formats.jpeg,
                                                )
                                            }
                                        } finally {
                                            finishProcessed()
                                        }
                                    }
                                }
                                processedQueued = queued.isSuccess
                                queued.onFailure { failure ->
                                    android.util.Log.e("CameraEngine", "Photo save dispatch failed", failure)
                                    reportStatus("Photo save failed")
                                }
                            } else {
                                reportStatus("Photo save failed")
                            }
                        } else {
                            reportStatus("Photo capture failed")
                        }
                    }

                    if (formats.dngRaw) {
                        if (raw != null) {
                            // DngCreator needs the live Image, so write + COMPLETE marking remain on
                            // this camera callback. Only retrying publication crosses to ioExecutor.
                            val write = runCatching {
                                stillPipeline.saveDng(raw, rawChars, result, spec)
                            }
                            write.onFailure { failure ->
                                android.util.Log.e("CameraEngine", "DNG write failed", failure)
                                reportStatus("DNG save failed")
                            }
                            val pending = write.getOrNull()
                            if (pending != null) {
                                val queued = runCatching {
                                    ioExecutor.execute {
                                        try {
                                            stillPipeline.publishDng(pending)
                                        } finally {
                                            finishDng()
                                        }
                                    }
                                }
                                dngPublishQueued = queued.isSuccess
                                queued.onFailure {
                                    // The bytes are COMPLETE and remain pending for launch recovery.
                                    reportStatus("DNG save delayed. Will retry.")
                                }
                            }
                        } else {
                            reportStatus("DNG capture failed")
                        }
                    }
                    if (!formats.wantsProcessedStill && !formats.dngRaw) {
                        reportStatus("No output selected")
                    }
                } finally {
                    if (!processedQueued) finishProcessed()
                    if (!dngPublishQueued) finishDng()
                    finishSequence()
                }
            }

            override fun onError(t: Throwable) {
                try {
                    android.util.Log.e("CameraEngine", "Photo capture failed", t)
                    reportStatus("Photo capture failed")
                } finally {
                    finishProcessed()
                    finishDng()
                    finishSequence()
                }
            }
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

    // REC admission runs on [recorderExecutor], never the caller's (main) thread: the bounded
    // standby-mic release wait (≤400 ms), the MediaStore pending insert (Binder IPC), and the
    // MediaCodec/MediaMuxer/AudioRecord construction below cost ~100-700 ms — guaranteed dropped
    // frames at every REC press when run synchronously on main. Only the in-flight gate stays
    // synchronous. Stop-during-start ordering (latched, exactly-once, executed on the same serial
    // executor the moment admission publishes — the documented "stoppable while starting" UI
    // contract) is owned by the unit-tested [RecordingAdmissionLatch].
    private val recAdmission = RecordingAdmissionLatch()

    fun startRecording(recordAudio: Boolean, onResult: (Boolean) -> Unit) {
        // Defensive boundary for any future REC entry point that bypasses setVideoMode (or for a
        // Video-mode snapshot that accidentally started an interval run). This invalidates only the
        // active sequence; the selected TIMELAPSE drive mode remains available on return to Photo.
        stopTimelapse()
        if (!recAdmission.tryBeginAdmission()) {
            onResult(false)
            return
        }
        val accepted = runCatching {
            recorderExecutor.execute {
                val ok = runCatching { startRecordingBlocking(recordAudio) }.getOrDefault(false)
                val stopNow = recAdmission.completeAdmission(ok)
                onResult(ok)
                if (stopNow) stopRecording()
            }
        }.isSuccess
        if (!accepted) {
            recAdmission.completeAdmission(succeeded = false)
            onResult(false)
        }
    }

    private fun startRecordingBlocking(recordAudio: Boolean): Boolean {
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
        val processAdmission = UnsafeRecorderQuarantine.snapshotAdmission(processNativeOwner) ?: run {
            onStatus?.invoke(
                if (UnsafeRecorderQuarantine.isActive()) {
                    UNSAFE_RECORDER_RESTART_STATUS
                } else {
                    "Recording is already active"
                },
            )
            return false
        }
        return try {
            // The recorder owns the mic — stop the standby level tap first and wait (bounded) for
            // release confirmation. The process lease prevents another Engine from overlapping REC.
            val audioClaim = standbyAudioController.beginRecording()
            if (!audioClaim.admitted) {
                false
            } else {
                // Unexpected failures between mic claim and publication must release both claims.
                try {
                    startRecordingClaimed(recordAudio, acceptedSession, audioClaim, processAdmission)
                } catch (t: Throwable) {
                    android.util.Log.w("CameraEngine", "REC admission threw; releasing mic claim", t)
                    abortRecordingStart()
                    onStatus?.invoke("REC failed")
                    false
                }
            }
        } finally {
            // publishAdmission moves a successful setup to the active slot, so this is a no-op then.
            UnsafeRecorderQuarantine.abandonPendingAdmission(processAdmission)
        }
    }

    /** The post-mic-claim half of REC admission; every return path releases or converts the claim. */
    private fun startRecordingClaimed(
        recordAudio: Boolean,
        acceptedSession: AcceptedCameraSession,
        audioClaim: StandbyMeterOwnership.RecordingClaim<java.util.concurrent.CountDownLatch>,
        processAdmission: com.hletrd.findx9tele.video.UnsafeRecorderAdmissionToken,
    ): Boolean {
        val meterReleased = audioClaim.release?.let {
            runCatching { it.await(400, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrDefault(false)
        } ?: true
        if (!meterReleased) {
            abortRecordingStart()
            onStatus?.invoke("Audio is busy. Try again.")
            return false
        }
        if (!UnsafeRecorderQuarantine.isAdmissionCurrent(processAdmission)) {
            abortRecordingStart()
            onStatus?.invoke(UNSAFE_RECORDER_RESTART_STATUS)
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
        val ownedGl = glOwners.current()
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
        if (!UnsafeRecorderQuarantine.isAdmissionCurrent(processAdmission)) {
            MediaStoreWriter.delete(context, uri)
            abortRecordingStart()
            onStatus?.invoke(UNSAFE_RECORDER_RESTART_STATUS)
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
        // File color tags: when the (DORMANT) native path emits O-Log2 the container MUST carry the
        // log-class policy (BT.2020 full-range + explicit SDR-class transfer, never unset — the QTI
        // PQ-mistag trap) regardless of the TF chip, so players don't HDR-tone-map it. All three
        // log-class ColorTransfer members map to that identical tag set, so SLOG3_CINE serves as
        // the internal marker since the user-facing O-Log2 entry was removed; a future CameraUnit
        // activation owns its own curve/tagging decision (native O-Log2 is none of the GL curves).
        // Otherwise use the selected transfer on the 10-bit paths (HEVC/APV); AVC is always SDR.
        val fileTransfer = when {
            vendorLogMode != VendorLogMode.OFF -> ColorTransfer.SLOG3_CINE
            codec == VideoCodec.HEVC || codec == VideoCodec.APV -> transfer
            else -> ColorTransfer.SDR
        }
        val rec = VideoRecorder(context).also { it.processAdmissionToken = processAdmission }
        val earlyFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val encoderAttachResult = java.util.concurrent.atomic.AtomicReference<Result<Unit>?>()
        val encoderAttachDelivered = java.util.concurrent.atomic.AtomicBoolean(false)
        val reportRecorderFailure: (Throwable) -> Unit = { failure ->
            // Both codec-drain and GL-output failures can beat recorder publication. Retain the
            // first one, then replay through the same identity-owned claim after admission.
            earlyFailure.compareAndSet(null, failure)
            handleUnexpectedRecorderFailure(ownedGl, rec, uri, recordingCaptureId, failure)
        }
        fun deliverEncoderAttach(result: Result<Unit>) {
            val effectiveResult = if (UnsafeRecorderQuarantine.isAdmissionCurrent(processAdmission)) {
                result
            } else {
                Result.failure(
                    java.util.concurrent.CancellationException("Recorder admission was quarantined"),
                )
            }
            val owned = synchronized(recorderOwnershipLock) {
                recorder === rec && activeRecordingGl === ownedGl && glOwners.owns(ownedGl)
            }
            if (!owned || !encoderAttachDelivered.compareAndSet(false, true)) return
            effectiveResult.fold(
                onSuccess = {
                    if (UnsafeRecorderQuarantine.isAdmissionCurrent(processAdmission) &&
                        glOwners.owns(ownedGl)
                    ) onRecordingStarted?.invoke()
                },
                onFailure = { failure ->
                    handleUnexpectedRecorderFailure(ownedGl, rec, uri, recordingCaptureId, failure)
                },
            )
        }
        // Physical device orientation at record start → muxer rotation hint so a landscape-held clip
        // plays upright (GL only bakes the afocal 180°; see VideoRecorder.start).
        val orientationHint = gyro.currentDeviceOrientation()
        // FRAMING CONTRACT (ARCH4-1): the encoder buffer must match the DISPLAYED aspect, not the
        // camera stream's. The SurfaceTexture transform rotates GL sampling by the sensor's 90°,
        // so the displayed content is portrait; drawing it into the stream-shaped LANDSCAPE buffer
        // made coverScale overscan ~3.16× and the file recorded only a center band of the
        // viewfinder field (device-measured via luminance-gradient A/B, 2026-07-18). The camera
        // stream itself stays [videoSize] (the HAL fixes that); only the MediaCodec buffer swaps.
        val (encW, encH) = RotationMath.encoderSurfaceSize(
            size.width,
            size.height,
            caps?.sensorOrientation ?: 90,
            previewRotationDegrees(),
        )
        val encoderSize = Size(encW, encH)
        val requestedBitRate = bitRateFor(size, rate)
        if (!UnsafeRecorderQuarantine.isAdmissionCurrent(processAdmission)) {
            MediaStoreWriter.delete(context, uri)
            abortRecordingStart()
            onStatus?.invoke(UNSAFE_RECORDER_RESTART_STATUS)
            return false
        }
        var configuredSurface: Surface? = null
        val nativeSetupAdmitted = UnsafeRecorderQuarantine.commitAdmission(processAdmission) {
            configuredSurface = rec.start(
                uri, encoderSize, rate.encoderRate, captureRate, requestedBitRate,
                fileTransfer, codec, recordAudio, audioGain, orientationHint,
                audioScene, controls.zoomRatio, audioInputPreference,
                onRoute = { route -> onAudioRoute?.invoke(route) },
                onLevel = { lvl -> onAudioLevel?.invoke(lvl) },
                onFailure = reportRecorderFailure,
            )
        }
        if (!nativeSetupAdmitted) {
            MediaStoreWriter.delete(context, uri)
            abortRecordingStart()
            onStatus?.invoke(UNSAFE_RECORDER_RESTART_STATUS)
            return false
        }
        val surface = configuredSurface
        if (surface == null) {
            // Encoder/muxer failed to configure; drop the pending MediaStore row we created so it
            // doesn't linger as a 0-byte orphan (VideoRecorder.start already released its own half).
            MediaStoreWriter.delete(context, uri)
            abortRecordingStart()
            onStatus?.invoke("REC failed"); return false
        }
        if (!UnsafeRecorderQuarantine.isAdmissionCurrent(processAdmission)) {
            stopUnattachedRecording(rec, recordingCaptureId)
            onStatus?.invoke(UNSAFE_RECORDER_RESTART_STATUS)
            return false
        }
        var admitted = false
        val processCommitted = UnsafeRecorderQuarantine.publishAdmission(processAdmission) {
            admitted = synchronized(this) {
                synchronized(recorderOwnershipLock) {
                    if (recordingCandidateMayPublish(
                            acquisitionOpen = terminalAcquisitionGate.isOpen(),
                            teardownInFlight = recorderTeardownInFlight,
                            glOwnerCurrent = glOwners.owns(ownedGl),
                            sessionOwned = ownsAcceptedRecordingSession(acceptedSession),
                            recorderAbsent = recorder == null,
                        )
                    ) {
                        recorder = rec
                        activeRecordingGl = ownedGl
                        activeRecordingUri = uri
                        activeRecordingCaptureId = recordingCaptureId
                        true
                    } else {
                        false
                    }
                }
            }
            admitted
        }
        if (!processCommitted || !admitted) {
            // No EGL handoff occurred: direct recorder stop is safe and deletes the pending row.
            stopUnattachedRecording(rec, recordingCaptureId)
            onStatus?.invoke(
                if (!processCommitted) UNSAFE_RECORDER_RESTART_STATUS else "Camera reconfiguring",
            )
            return false
        }
        ownedGl.setTransfer(glTransfer)
        fun ownsEncoderAttach(): Boolean {
            val acquisitionOpen = terminalAcquisitionGate.isOpen()
            return synchronized(recorderOwnershipLock) {
                recordingAttachMayCommit(
                    acquisitionOpen = acquisitionOpen,
                    recorderOwned = recorder === rec && activeRecordingGl === ownedGl,
                    glOwnerCurrent = glOwners.owns(ownedGl),
                )
            }
        }
        val encoderAdmission = EncoderOutputAdmission(
            validity = {
                UnsafeRecorderQuarantine.isAdmissionCurrent(processAdmission) &&
                    ownsEncoderAttach()
            },
            commitBlock = { block ->
                var installed = false
                val processOwned = UnsafeRecorderQuarantine.commitAdmission(processAdmission) {
                    // Lock order is process lease -> terminal gate -> recorder owner. release()
                    // closes the terminal gate before claiming recorder ownership, while pause and
                    // failure claim recorder ownership directly. Either teardown observes a fully
                    // installed EGL owner and queues detach after this task, or this commit rejects
                    // the private candidate before it can outlive the recorder it targets.
                    terminalAcquisitionGate.runIfOpen {
                        synchronized(recorderOwnershipLock) {
                            if (recordingAttachMayCommit(
                                    acquisitionOpen = true,
                                    recorderOwned = recorder === rec && activeRecordingGl === ownedGl,
                                    glOwnerCurrent = glOwners.owns(ownedGl),
                                )
                            ) {
                                block()
                                installed = true
                            }
                        }
                    }
                }
                processOwned && installed
            },
        )
        // Ownership is published before the asynchronous EGL handoff. A lease revocation therefore
        // reports through the identity-owned failure path and receives checked detach-before-stop;
        // before this point the Surface was never handed to EGL and direct stop remained safe.
        ownedGl.setEncoderOutput(
            surface,
            encoderSize.width,
            encoderSize.height,
            admission = encoderAdmission,
            onRuntimeFailure = reportRecorderFailure,
        ) { result ->
            // GlPipeline guarantees exactly-once result delivery, so a plain atomic publication is
            // sufficient and avoids identity-CAS semantics on Kotlin's boxed Result value class.
            encoderAttachResult.set(result)
            deliverEncoderAttach(result)
        }
        // A drain thread can fail before start() returns and before recorder ownership is published.
        // Re-run the same identity-guarded claim after publication so that early failure is not lost.
        earlyFailure.get()?.let {
            handleUnexpectedRecorderFailure(ownedGl, rec, uri, recordingCaptureId, it)
        }
        encoderAttachResult.get()?.let(::deliverEncoderAttach)
        if (recorder !== rec) return false
        if (!UnsafeRecorderQuarantine.isAdmissionCurrent(processAdmission)) {
            handleUnexpectedRecorderFailure(
                ownedGl,
                rec,
                uri,
                recordingCaptureId,
                java.util.concurrent.CancellationException("Recorder admission was quarantined"),
            )
            return false
        }
        if (com.hletrd.findx9tele.BuildConfig.DEBUG) {
            android.util.Log.i(
                "CameraEngine",
                "RecordingSpec: admitted stem=${name.substringBeforeLast('.')} " +
                    "codec=${codec.name} source=${size.width}x${size.height} " +
                    "encoder=${encoderSize.width}x${encoderSize.height} bitrate=$requestedBitRate " +
                    "fps=${String.format(java.util.Locale.US, "%.9f", rate.encoderRate)} " +
                    "transfer=${fileTransfer.name} audio=$recordAudio",
            )
        }
        return true
    }

    private fun abortRecordingStart() {
        standbyAudioController.abortRecording()
    }

    /** A configured recorder whose Surface never crossed into EGL can be stopped directly. */
    private fun stopUnattachedRecording(
        rec: VideoRecorder,
        captureId: Int,
    ) {
        val result = runCatching { rec.stop() }.getOrElse { failure ->
            VideoRecorder.StopResult(
                saved = false,
                error = failure,
                nativeGraphDisposition = NativeGraphDisposition.QUARANTINE_REQUIRED,
            )
        }
        if (result.nativeGraphDisposition == NativeGraphDisposition.QUARANTINE_REQUIRED) {
            enterUnsafeRecorderQuarantine(
                rec = rec,
                captureId = captureId,
                failure = result.error ?: IllegalStateException("Recorder setup could not stop"),
            )
        }
        abortRecordingStart()
    }

    fun stopRecording() {
        // Admission still queued/in flight on the recorder executor → the latch takes the stop
        // and the admission completion runs it there (exactly-once, ordered behind the start).
        if (recAdmission.requestStop()) return
        val ownedRecording = synchronized(recorderOwnershipLock) {
            val owned = recorder ?: return
            recorderTeardownInFlight = true
            recorder = null
            val ownedGl = checkNotNull(activeRecordingGl) { "Recorder has no GL owner" }
            activeRecordingGl = null
            val ownedUri = activeRecordingUri
            val ownedCaptureId = activeRecordingCaptureId
            activeRecordingUri = null
            activeRecordingCaptureId = 0 // stale-until-overwritten otherwise (ARCH-8)
            OwnedRecording(owned, ownedUri, ownedCaptureId, ownedGl)
        }
        // ORDERED teardown: finishRecording (rec.stop() → codec release; joins the drain threads up
        // to seconds, so it stays OFF the main thread on ioExecutor) dispatches from the completion
        // callback, which runs on the GL thread only AFTER the encoder EGL surface is actually
        // cleared. The old fire-and-forget post had no happens-before edge with the independent
        // ioExecutor — a queued drawFrame could still makeCurrent() the encoder surface while the
        // codec that owns it was being released (uncaught EGL failure on the GL thread).
        detachAndFinalizeRecording(
            ownedRecording.gl,
            ownedRecording.recorder,
            ownedRecording.uri,
            ownedRecording.captureId,
        )
        // Restore the preview curve startRecording overrode: an AVC recording pushes null (SDR) into
        // GL and nothing else re-applies the LOG/HLG preview render until the next transfer change.
        ownedRecording.gl.setTransfer(transfer)
    }

    // True from stop/pause dispatching the async rec.stop() until its AudioRecord is actually
    // released: `recorder == null` alone says nothing about the mic, and the standby meter starting
    // in that window would violate the one-AudioRecord invariant (its init would fail, or worse,
    // steal the route from the finalizing clip).
    @Volatile private var recorderTeardownInFlight = false

    private fun handleUnexpectedRecorderFailure(
        ownedGl: GlPipeline,
        rec: VideoRecorder,
        uri: android.net.Uri,
        captureId: Int,
        failure: Throwable,
    ) {
        val claimed = synchronized(recorderOwnershipLock) {
            if (recorder !== rec || activeRecordingGl !== ownedGl) {
                false
            } else {
                recorderTeardownInFlight = true
                recorder = null
                activeRecordingGl = null
                if (activeRecordingUri == uri) {
                    activeRecordingUri = null
                    activeRecordingCaptureId = 0 // stale-until-overwritten otherwise (ARCH-8)
                }
                true
            }
        }
        if (!claimed) return
        detachAndFinalizeRecording(ownedGl, rec, uri, captureId)
        android.util.Log.e("CameraEngine", "Recording failed", failure)
        onStatus?.invoke("Recording failed")
        onRecordingTerminated?.invoke(failure)
        ownedGl.setTransfer(transfer)
    }

    private fun detachAndFinalizeRecording(
        ownedGl: GlPipeline,
        rec: VideoRecorder,
        uri: android.net.Uri?,
        captureId: Int,
    ) {
        recorderTeardownInFlight = true
        val completed = java.util.concurrent.CountDownLatch(1)
        recorderFinalizationLatch = completed

        fun dispatchTask(task: Runnable, fallbackName: String) {
            val accepted = runCatching { recorderExecutor.execute(task) }.isSuccess
            if (!accepted) {
                runCatching { Thread(task, fallbackName).apply { isDaemon = true }.start() }
                    .onFailure { task.run() }
            }
        }

        val scheduler = RecordingTeardownScheduler { delayMs, action ->
            runCatching {
                recorderWatchdog.schedule(
                    action,
                    delayMs,
                    java.util.concurrent.TimeUnit.MILLISECONDS,
                )
            }.getOrNull()?.let { future ->
                RecordingTeardownCancellation {
                    future.cancel(false)
                }
            }
        }
        lateinit var teardown: RecordingTeardownCoordinator
        teardown = RecordingTeardownCoordinator(
            scheduler = scheduler,
            detachTimeoutMs = RECORDER_DETACH_TIMEOUT_MS,
            hardTimeoutMs = RECORDER_QUARANTINE_TIMEOUT_MS,
            onRecoveryRequired = { failure ->
                // A failed or missing unbind/destroy result is NOT permission to release MediaCodec.
                // Reset the EGL owner first; only its strict resource-release signal may finalize.
                recoverFromEncoderDetachFailure(
                    ownedGl = ownedGl,
                    failure = failure,
                    onReleased = teardown::resourcesReleased,
                    onAbandoned = { teardown.recoveryAbandoned(failure) },
                )
            },
            onTerminal = { terminal, failure ->
                when (terminal) {
                    RecordingTeardownTerminal.FINALIZE -> {
                        val task = Runnable {
                            var releaseAdmission = false
                            try {
                                releaseAdmission = finishRecording(rec, uri, captureId)
                                    .nativeGraphDisposition == NativeGraphDisposition.RELEASED
                            } finally {
                                if (releaseAdmission) {
                                    UnsafeRecorderQuarantine.finishAdmission(rec.processAdmissionToken)
                                }
                                completed.countDown()
                            }
                        }
                        dispatchTask(task, "record-finalize-fallback")
                    }

                    RecordingTeardownTerminal.QUARANTINE -> try {
                        enterUnsafeRecorderQuarantine(
                            rec = rec,
                            captureId = captureId,
                            failure = failure
                                ?: IllegalStateException("Recording graph release was not proven"),
                        )
                    } finally {
                        completed.countDown()
                    }
                }
            },
        )
        teardown.start { completion ->
            ownedGl.setEncoderOutput(null, 0, 0, onApplied = completion)
        }
    }

    /** Terminal EGL reset used only when the checked encoder detach transition itself fails. */
    private fun recoverFromEncoderDetachFailure(
        ownedGl: GlPipeline,
        failure: Throwable,
        onReleased: () -> Unit,
        onAbandoned: () -> Unit,
    ) {
        android.util.Log.e("CameraEngine", "Renderer reset after encoder detach failure", failure)
        if (glOwners.owns(ownedGl)) {
            onStatus?.invoke("Camera error. Recovering.")
            invalidateCameraReady()
        }
        val failedController = synchronized(this) {
            if (!glOwners.owns(ownedGl)) return@synchronized null
            val current = controller
            controller = null
            current
        }
        val reset = Runnable {
            failedController?.close()
            val resourcesReleased = java.util.concurrent.atomic.AtomicBoolean(false)
            val outcome = stopGlOwner(
                ownedGl,
                afterResourcesReleased(ownedGl) {
                    resourcesReleased.set(true)
                    onReleased()
                },
                restartPreviewOnAbandon = false,
            )
            if (outcome == GlStopOutcome.ABANDONED && !resourcesReleased.get()) {
                onAbandoned()
            } else if (resourcesReleased.get() && !UnsafeRecorderQuarantine.isActive()) {
                restartPreviewAfterGlReset()
            }
        }
        if (runCatching { setupExecutor.execute(reset) }.isFailure) {
            // Release may already own the setup executor. A fresh helper keeps CameraController.close
            // off the GL callback thread while preserving stop-before-codec-finalize ordering.
            runCatching { Thread(reset, "egl-reset-fallback").start() }
                .onFailure {
                    runCatching { failedController?.close() }
                    val resourcesReleased = java.util.concurrent.atomic.AtomicBoolean(false)
                    val outcome = stopGlOwner(
                        ownedGl,
                        afterResourcesReleased(ownedGl) {
                            resourcesReleased.set(true)
                            onReleased()
                        },
                        restartPreviewOnAbandon = false,
                    )
                    if (outcome == GlStopOutcome.ABANDONED && !resourcesReleased.get()) {
                        onAbandoned()
                    } else if (resourcesReleased.get() && !UnsafeRecorderQuarantine.isActive()) {
                        restartPreviewAfterGlReset()
                    }
                }
        }
    }

    /** Replaces only the exact native owner that timed out; a stale stop cannot displace a newer GL. */
    private fun stopGlOwner(
        ownedGl: GlPipeline,
        onResourcesReleased: (() -> Unit)? = null,
        restartPreviewOnAbandon: Boolean = true,
    ): GlStopOutcome {
        val outcome = ownedGl.stop(onResourcesReleased = onResourcesReleased)
        var replaced = false
        if (outcome == GlStopOutcome.ABANDONED) {
            synchronized(this) {
                if (glOwners.replaceIfOwned(ownedGl) == null) return@synchronized
                started = false
                starting = false
                glInputPending = false
                replaced = true
            }
        }
        if (replaced) {
            android.util.Log.e(
                "CameraEngine",
                "Abandoned retired GL owner; installed isolated replacement",
            )
            // ABANDONED deliberately never emits the strict native-release callback. Start the
            // isolated replacement from the still-live TextureView now; waiting for that callback
            // or a lifecycle resume would leave an otherwise usable foreground preview black.
            val liveSurface = previewSurface
            if (restartPreviewOnAbandon && glReplacementMayRestartPreview(
                    replaced = true,
                    paused = paused,
                    acquisitionOpen = terminalAcquisitionGate.isOpen(),
                    hasPreviewSurface = liveSurface != null,
                )
            ) {
                onPreviewSurfaceAvailable(
                    checkNotNull(liveSurface),
                    previewSurfaceW,
                    previewSurfaceH,
                )
            }
        }
        return outcome
    }

    private fun afterResourcesReleased(ownedGl: GlPipeline, onReleased: () -> Unit): () -> Unit = released@{
        // Recorder finalization belongs to this old EGL owner and remains valid. Engine start state,
        // however, may only be mutated by the currently installed pipeline instance.
        if (!glOwners.owns(ownedGl)) {
            onReleased()
            return@released
        }
        synchronized(this) {
            started = false
            starting = false
            glInputPending = false
        }
        onReleased()
    }

    private fun restartPreviewAfterGlReset() {
        val liveSurface = previewSurface
        if (nativeAcquisitionMayProceed() && !paused && liveSurface != null) {
            onPreviewSurfaceAvailable(liveSurface, previewSurfaceW, previewSurfaceH)
        }
    }

    private fun nativeAcquisitionMayProceed(): Boolean = nativeAcquisitionAllowed(
        acquisitionOpen = terminalAcquisitionGate.isOpen(),
        recorderQuarantined = UnsafeRecorderQuarantine.isActive(),
    )

    private fun finishRecording(
        rec: VideoRecorder,
        uri: android.net.Uri?,
        captureId: Int,
    ): VideoRecorder.StopResult {
        var terminalResult: VideoRecorder.StopResult? = null
        try {
            val result = runCatching { rec.stop() }
                .getOrElse {
                    VideoRecorder.StopResult(
                        saved = false,
                        error = it,
                        nativeGraphDisposition = NativeGraphDisposition.QUARANTINE_REQUIRED,
                    )
                }
            terminalResult = result
            if (result.nativeGraphDisposition == NativeGraphDisposition.QUARANTINE_REQUIRED) {
                enterUnsafeRecorderQuarantine(
                    rec = rec,
                    captureId = captureId,
                    failure = result.error
                        ?: IllegalStateException("Recorder drains did not terminate"),
                )
            } else if (result.saved && uri != null) {
                // Surface only a fully finalized, published clip to the review UI.
                onMediaSaved?.invoke(uri, captureId)
                onStatus?.invoke("Video saved")
            } else {
                onStatus?.invoke("Video save failed")
            }
            return result
        } finally {
            if (terminalResult?.nativeGraphDisposition != NativeGraphDisposition.QUARANTINE_REQUIRED) {
                recorderTeardownInFlight = false
                standbyAudioController.finishRecording()
            }
            if (com.hletrd.findx9tele.BuildConfig.DEBUG) {
                val result = terminalResult
                android.util.Log.i(
                    "CameraEngine",
                    "RecordingFinalized: captureId=$captureId saved=${result?.saved == true} " +
                        "error=${result?.error?.javaClass?.simpleName ?: if (result == null) "unknown" else "none"}",
                )
            }
        }
    }

    /** Fail closed without releasing any owner that may still be executing in native code. */
    private fun enterUnsafeRecorderQuarantine(
        rec: VideoRecorder,
        captureId: Int,
        failure: Throwable,
    ) {
        // Close process admission before recorder callbacks or AudioRecord.stop can re-enter app
        // work. A fresh Engine observes the same irreversible gate.
        UnsafeRecorderQuarantine.retain(rec)
        terminalAcquisitionGate.close()
        synchronized(this) {
            started = false
            starting = false
            glInputPending = false
        }
        recorderTeardownInFlight = false
        // Clear visible intent before yielding the recording mic claim, otherwise standby could
        // transiently open another AudioRecord during terminal convergence.
        standbyAudioController.disable()
        standbyAudioController.finishRecording()
        invalidateCameraReady()
        runCatching { onAudioLevel?.invoke(0f) }
        runCatching { onStatus?.invoke(UNSAFE_RECORDER_RESTART_STATUS) }
        runCatching { onRecordingTerminated?.invoke(failure) }
        android.util.Log.e(
            "CameraEngine",
            "Recording graph quarantined; process restart required (captureId=$captureId)",
            failure,
        )
    }

    /** Releases the camera + gyro for backgrounding without tearing down the GL pipeline or start state. */
    fun pause() {
        paused = true
        coldStartRetryGate.cancel()
        standbyAudioController.disable()
        invalidateCameraReady()
        // A backgrounded timelapse can't capture anyway (controller is nulled below, so every tick
        // no-ops) — stop it outright rather than silently resuming mid-sequence with a gap.
        stopTimelapse()
        // Finalize an in-flight recording OFF the main thread: rec.stop() joins the drain threads (up
        // to a few seconds) and calling it inline on onStop risks an ANR. Clear the encoder EGL first
        // so GL stops drawing into the input surface before the codec releases it.
        val ownedRecording = synchronized(recorderOwnershipLock) {
            recorder?.let { owned ->
                recorderTeardownInFlight = true
                recorder = null
                val ownedGl = checkNotNull(activeRecordingGl) { "Recorder has no GL owner" }
                activeRecordingGl = null
                val ownedUri = activeRecordingUri
                val ownedCaptureId = activeRecordingCaptureId
                activeRecordingUri = null
                activeRecordingCaptureId = 0 // stale-until-overwritten otherwise (ARCH-8)
                OwnedRecording(owned, ownedUri, ownedCaptureId, ownedGl)
            }
        }
        if (ownedRecording != null) {
            // Same ordered teardown as stopRecording: release the codec only after the GL thread
            // confirmed the encoder EGL surface is cleared.
            detachAndFinalizeRecording(
                ownedRecording.gl,
                ownedRecording.recorder,
                ownedRecording.uri,
                ownedRecording.captureId,
            )
            // Restore the preview curve startRecording overrode (AVC → null) on the exact renderer.
            ownedRecording.gl.setTransfer(transfer)
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
        if (!nativeAcquisitionMayProceed()) {
            if (UnsafeRecorderQuarantine.isActive()) {
                onStatus?.invoke(UNSAFE_RECORDER_RESTART_STATUS)
            }
            invalidateCameraReady()
            return
        }
        // onStop's invalidateZoomGlide cancels the interaction-end runnable (the only ordinary
        // clearer of this flag), so a background mid-gesture left it stale-true across the whole
        // next foreground session's first gesture (AGG4-2). Resume covers the no-reopen path
        // (controller still installed); wireController covers every reopen.
        zoomInteractionActive = false
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
            if (!nativeAcquisitionMayProceed() || paused) return@execute
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
     * Reconciles prior-process pending media: adopt COMPLETE/structurally proven rows, delete only
     * proven-incomplete rows, and retain indeterminate rows. Provider failures retry boundedly and
     * finish with one typed completion; Images and Video remain independently failure-isolated.
     * The dedicated recovery lane keeps every provider probe/backoff off camera startup.
     */
    internal fun cleanupOrphans(onComplete: (MediaRecoveryCompletion) -> Unit = {}) {
        mediaRecoveryExecutor.execute {
            var completedAttempts = 0
            var cumulativeReport = RecoveryReport()
            var latestReport = RecoveryReport()
            var finalDecision = RecoveryRetryDecision.COMPLETE
            do {
                completedAttempts += 1
                latestReport = MediaStoreWriter.cleanupOrphanedPending(context)
                cumulativeReport = cumulativeReport.foldRecoveryAttempt(latestReport)
                finalDecision = recoveryRetryDecision(
                    report = latestReport,
                    completedAttempts = completedAttempts,
                    maxAttempts = MAX_MEDIA_RECOVERY_ATTEMPTS,
                )
                if (finalDecision == RecoveryRetryDecision.RETRY) {
                    runCatching { Thread.sleep(MEDIA_RECOVERY_RETRY_BACKOFF_MS * completedAttempts) }
                }
            } while (finalDecision == RecoveryRetryDecision.RETRY)

            if (BuildConfig.DEBUG || finalDecision == RecoveryRetryDecision.EXHAUSTED) {
                val summary = "attempts=$completedAttempts scanned=${cumulativeReport.scanned} " +
                    "adopted=${cumulativeReport.adopted} deleted=${cumulativeReport.deleted} " +
                    "retained=${cumulativeReport.retained} errors=${cumulativeReport.errors} " +
                    "failures=${latestReport.failureClasses.sortedBy { it.name }.joinToString { it.name }}"
                if (finalDecision == RecoveryRetryDecision.EXHAUSTED) {
                    Log.w("MediaRecovery", "exhausted $summary")
                } else {
                    Log.d("MediaRecovery", "complete $summary")
                }
            }
            onComplete(MediaRecoveryCompletion(cumulativeReport, completedAttempts, finalDecision))
        }
    }

    fun currentRollDegrees(): Float = gyro.currentRollDegrees()

    /** Discrete physical device orientation (0/90/180/270) from gravity, for auto-rotating overlays. */
    fun currentDeviceOrientation(): Int = gyro.currentDeviceOrientation()

    fun setPunchIn(enabled: Boolean) {
        rendererAssists.setPunchIn(enabled)
    }

    // TELE finder PIP: the user's persisted Assist toggle (default OFF). Only the RESOLVED flag —
    // teleFinderResolved: toggle && TELE && PHOTO && 4:3 — is pushed to GL and stored in
    // RendererAssists (so a fresh GL generation replays the resolved value via replayAll).
    // Photo-only: it is a still-composition aid and 4:3 is the STILL aspect. 16:9 is excluded
    // because the AspectMask pillarboxes would dim/misframe the corner box, and the finder
    // deliberately shows the FULL delivered frame (see FINDER_* in CameraState for the honest
    // single-stream contract).
    fun setTeleFinder(enabled: Boolean) {
        rendererAssists.setTeleFinderIntent(enabled)
        pushTeleFinder()
    }

    /** Recomputes and pushes the resolved finder flag; called on toggle, aspect, lens/TC, mode,
     *  and session (re)config so the GL PIP can never outlive a TC-off, aspect, or mode change.
     *  The predicate itself is the shared, unit-tested [teleFinderResolved]. */
    private fun pushTeleFinder() {
        val resolved = teleFinderResolved(
            rendererAssists.isTeleFinderEnabled(),
            teleconverterMode,
            videoMode,
            aspectRatio,
        )
        rendererAssists.setTeleFinderResolved(resolved)
    }

    /** Breaks the engine→ViewModel callback graph before asynchronous owner teardown begins. */
    fun detachCallbacks() {
        onCameraReadyChange = null
        onOpticsRollback = null
        onAfIndication = null
        onTapFocusChange = null
        onStatus = null
        onCapsReady = null
        onVideoSizeChosen = null
        onPreviewAspect = null
        onAnalysis = null
        onAudioLevel = null
        onAudioRoute = null
        onStandbyAudioAvailable = null
        onStandbyAudioUnavailable = null
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
        standbyAudioController.disable()
        // Preserve the same GL-detach-before-codec-release order during ViewModel teardown. Await
        // the exactly-once finalization latch before dropping GL/executor ownership; pause() usually
        // started this already, while the direct branch covers unusual unbalanced lifecycle exits.
        val ownedRecording = synchronized(recorderOwnershipLock) {
            recorder?.let { rec ->
                recorderTeardownInFlight = true
                recorder = null
                val ownedGl = checkNotNull(activeRecordingGl) { "Recorder has no GL owner" }
                activeRecordingGl = null
                val uri = activeRecordingUri
                val captureId = activeRecordingCaptureId
                activeRecordingUri = null
                activeRecordingCaptureId = 0 // stale-until-overwritten otherwise (ARCH-8)
                OwnedRecording(rec, uri, captureId, ownedGl)
            }
        }
        ownedRecording?.let {
            detachAndFinalizeRecording(it.gl, it.recorder, it.uri, it.captureId)
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
        stopGlOwner(glOwners.current())
        starting = false
        setupExecutor.shutdown()
        ioExecutor.shutdown()
        mediaRecoveryExecutor.shutdown()
        recorderExecutor.shutdown()
        recorderWatchdog.shutdown()
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
        return RotationMath.captureRotationDegrees(
            c.sensorOrientation,
            teleconverterMode,
            gyro.currentDeviceOrientation(),
            frontFacing = facing == CameraFacing.FRONT,
        )
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
        // The id map below names REAR lenses; a front shot must not fall into its "tele" default
        // (the front id is enumerated, never assumed, so facing — not an id table — decides).
        val lensName = when {
            spec.frontFacing -> "front"
            activeLensId == "3" -> "ultra-wide"
            activeLensId == "2" -> "wide"
            activeLensId == "4" -> "tele"
            activeLensId == "5" -> "periscope tele"
            else -> "tele"
        }
        // Marketing focal like the stock sample ("70mm", not the computed 69.4): the lens band's
        // nominal equiv. f-stop truncated to one decimal (stock: "f/2.2" for the 2.26 aperture).
        // The front has no rear marketing band — its own measured equiv is the honest value.
        val marketingMm = when {
            spec.frontFacing -> lensEquiv
            activeLensId == "3" -> LensChoice.ULTRAWIDE.targetEquivMm
            activeLensId == "2" -> LensChoice.MAIN.targetEquivMm
            activeLensId == "4" -> LensChoice.TELE3X.targetEquivMm
            activeLensId == "5" -> LensChoice.TELE10X.targetEquivMm
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


    private val standbyAudioController = StandbyAudioController(
        context = context,
        audioGain = { audioGain },
        onLevel = { level -> onAudioLevel?.invoke(level) },
        canStart = {
            nativeAcquisitionMayProceed() && !paused && recorder == null && !recorderTeardownInFlight
        },
        recorderAbsent = { recorder == null },
        isPaused = { paused },
        processOwner = processNativeOwner,
        onAvailable = { onStandbyAudioAvailable?.invoke() },
        onUnavailable = { unavailable -> onStandbyAudioUnavailable?.invoke(unavailable) },
    )

    fun setStandbyAudioMonitor(enabled: Boolean) {
        standbyAudioController.setEnabled(enabled)
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
        const val RECORDER_DETACH_TIMEOUT_MS = 2_000L
        const val RECORDER_QUARANTINE_TIMEOUT_MS = 4_500L
        const val MAX_MEDIA_RECOVERY_ATTEMPTS = 3
        const val MEDIA_RECOVERY_RETRY_BACKOFF_MS = 75L
        const val UNSAFE_RECORDER_RESTART_STATUS = "Recording failed. Force stop and reopen the app."
    }
}

/** One predicate shared by GL, preview, Camera2, and standby-microphone acquisition boundaries. */
internal fun nativeAcquisitionAllowed(
    acquisitionOpen: Boolean,
    recorderQuarantined: Boolean,
): Boolean = acquisitionOpen && !recorderQuarantined

/** Exact local half of process-linearized REC publication. */
internal fun recordingCandidateMayPublish(
    acquisitionOpen: Boolean,
    teardownInFlight: Boolean,
    glOwnerCurrent: Boolean,
    sessionOwned: Boolean,
    recorderAbsent: Boolean,
): Boolean = acquisitionOpen && !teardownInFlight && glOwnerCurrent && sessionOwned && recorderAbsent

/** Exact local owner required when a privately prepared encoder EGL surface becomes live. */
internal fun recordingAttachMayCommit(
    acquisitionOpen: Boolean,
    recorderOwned: Boolean,
    glOwnerCurrent: Boolean,
): Boolean = acquisitionOpen && recorderOwned && glOwnerCurrent

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

internal data class MediaRecoveryCompletion(
    val report: RecoveryReport,
    val attempts: Int,
    val decision: RecoveryRetryDecision,
)

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

/** A viewfinder tap may publish AF HOLD only against a live session that can run region AUTO AF. */
internal fun tapPointAdmissionAllowed(
    currentController: Any?,
    acceptedController: Any?,
    currentSessionGeneration: Long,
    acceptedSessionGeneration: Long,
    cameraReady: Boolean,
    paused: Boolean,
    canHoldFocus: Boolean,
): Boolean = canHoldFocus && acceptedCameraSessionIsCurrent(
    currentController = currentController,
    acceptedController = acceptedController,
    currentSessionGeneration = currentSessionGeneration,
    acceptedSessionGeneration = acceptedSessionGeneration,
    cameraReady = cameraReady,
    paused = paused,
)

/** Completion/deferred submission owner: preview-output readiness is deliberately not an input. */
internal fun tapFocusSessionOwnerIsCurrent(
    currentAcceptedSession: Any?,
    expectedAcceptedSession: Any,
    currentController: Any?,
    expectedController: Any,
    currentSessionGeneration: Long,
    expectedSessionGeneration: Long,
    paused: Boolean,
): Boolean = currentAcceptedSession === expectedAcceptedSession && !paused &&
    currentController === expectedController && currentSessionGeneration == expectedSessionGeneration

internal enum class TapFocusCompletionDecision {
    IGNORE,
    KEEP_PREVIOUS,
    PUBLISH_HELD,
    RETIRE,
}

/** Queue admission is deliberately absent: only the camera-thread submission result can hold. */
internal fun tapFocusCompletionDecision(
    attemptCurrent: Boolean,
    submission: TapFocusSubmissionResult,
    sessionCurrent: Boolean,
): TapFocusCompletionDecision = when {
    !attemptCurrent -> TapFocusCompletionDecision.IGNORE
    submission == TapFocusSubmissionResult.REJECTED_PREVIOUS_RESTORED ->
        TapFocusCompletionDecision.KEEP_PREVIOUS
    submission == TapFocusSubmissionResult.FAILED_UNCERTAIN -> TapFocusCompletionDecision.RETIRE
    !sessionCurrent -> TapFocusCompletionDecision.RETIRE
    else -> TapFocusCompletionDecision.PUBLISH_HELD
}

/** Fresh Custom-WB gains plus the opaque accepted-session owner that produced them. */
internal class CustomWbSample internal constructor(
    val gains: WbGains,
    internal val ownerToken: Any,
)

/** Accepted-session and WB-state ownership gate shared by callback and main-thread consumption. */
internal fun customWbSampleOwnerIsCurrent(
    currentAcceptedSession: Any?,
    expectedAcceptedSession: Any?,
    currentController: Any?,
    expectedController: Any?,
    currentSessionGeneration: Long,
    expectedSessionGeneration: Long,
    cameraReady: Boolean,
    paused: Boolean,
    wbMode: WbMode,
    awbLocked: Boolean,
): Boolean = expectedAcceptedSession != null &&
    currentAcceptedSession === expectedAcceptedSession &&
    acceptedCameraSessionIsCurrent(
        currentController = currentController,
        acceptedController = expectedController,
        currentSessionGeneration = currentSessionGeneration,
        acceptedSessionGeneration = expectedSessionGeneration,
        cameraReady = cameraReady,
        paused = paused,
    ) && wbMode == WbMode.AUTO && !awbLocked

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
    val photoExposureTimeNs: Long = controls.exposureTimeNs,
    val requestedVideoSize: Size? = null,
    // Facing rolls back with the packet like every other optics axis: a refused FRONT entry must
    // restore BACK (and vice versa) under the exact owning generation, never optimistically.
    val facing: CameraFacing = CameraFacing.BACK,
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

/** Interval shooting is photo-owned and is cancelled only on the Photo -> Video edge. */
internal fun captureModeTransitionStopsTimelapse(currentVideo: Boolean, targetVideo: Boolean): Boolean =
    !currentVideo && targetVideo

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

/** Immutable event-time mapping stored by the rapid-tap latest-wins coalescer. */
internal data class TapFocusGeometry(
    val viewPoint: Pair<Float, Float>,
    val sensorPoint: Pair<Float, Float>,
    val loupePoint: Pair<Float, Float>,
)

internal fun mapTapFocusGeometry(
    nx: Float,
    ny: Float,
    sensorOrientation: Int,
    teleconverter: Boolean,
    punchActive: Boolean,
    sensorCenter: Pair<Float, Float>,
    loupeCenter: Pair<Float, Float>,
    previewRotationDegrees: Int,
    // Selfie preview mirror: the DISPLAYED image is x-flipped, so the tapped view x must un-flip
    // (nx → 1−nx) before the sensor/loupe content mappings — otherwise front tap-AF meters the
    // horizontally opposite scene point. The reticle viewPoint stays the raw tap (UI space).
    // Same on-device sign caveat as the rest of this mapping.
    mirrorX: Boolean = false,
): TapFocusGeometry {
    val cx = if (mirrorX) 1f - nx else nx
    val sensorRaw = viewTapToSensorPoint(cx, ny, sensorOrientation, teleconverter)
    val loupeRaw = viewTapToLoupeCenter(cx, ny, previewRotationDegrees)
    if (!punchActive) return TapFocusGeometry(nx to ny, sensorRaw, loupeRaw)
    val span = 1f - PUNCH_IN_CROP
    return TapFocusGeometry(
        viewPoint = nx to ny,
        sensorPoint = loupeAdjustedTap(
            sensorRaw.first,
            sensorRaw.second,
            sensorCenter.first,
            sensorCenter.second,
            span,
        ),
        loupePoint = loupeAdjustedTap(
            loupeRaw.first,
            loupeRaw.second,
            loupeCenter.first,
            loupeCenter.second,
            span,
        ),
    )
}

/**
 * Composes a rotated tap point through the ACTIVE punch-in loupe crop (AGG4-11/P2.8). While the
 * loupe magnifies, the renderer samples `center + span·(p − 0.5)` — an affine map that commutes
 * with the (orthonormal, center-pivoted) rotation stages, so the same composition is exact in
 * BOTH the sensor space (metering) and the texcoord space (loupe recenter), each against its own
 * current center. [span] = 1 − PUNCH_IN_CROP (the sampled fraction of the frame; 0.4 → 2.5×).
 * Callers bypass this entirely when the loupe is off (the identity would require center 0.5).
 */
internal fun loupeAdjustedTap(
    px: Float,
    py: Float,
    centerX: Float,
    centerY: Float,
    span: Float,
): Pair<Float, Float> =
    (centerX + span * (px - 0.5f)).coerceIn(0f, 1f) to (centerY + span * (py - 0.5f)).coerceIn(0f, 1f)
