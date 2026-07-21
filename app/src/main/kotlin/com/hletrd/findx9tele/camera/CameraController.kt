package com.hletrd.findx9tele.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.hletrd.findx9tele.BuildConfig
import com.hletrd.findx9tele.video.UnsafeRecorderQuarantine
import java.util.concurrent.Executor

/**
 * Camera2 session for the selected rear camera. The GL input surface receives the repeating preview
 * stream; processed stills use logical-camera YUV or standalone JPEG, and RAW is standalone-only.
 *
 * Camera callbacks run on a dedicated HandlerThread. [PhotoCallback] snapshots processed JPEG/YUV
 * data into engine-owned memory while each Image is live, then encoding/saving can continue on the
 * I/O executor after it closes. RAW is the deliberate exception: DNG writing consumes the live RAW
 * Image synchronously inside the callback.
 */
class CameraController(context: Context) {

    internal val diagnosticId: Long = controllerSequence.incrementAndGet()

    fun interface Ready { fun onReady(outputs: PhotoSessionOutputs) }
    fun interface ErrorCb { fun onError(t: Throwable) }

    interface PhotoCallback {
        /** Images are valid only for the duration of this call. rawChars is the RAW producer's characteristics. */
        fun onPhoto(
            jpeg: Image?,
            raw: Image?,
            result: TotalCaptureResult,
            rawChars: CameraCharacteristics,
            takenAtMs: Long,
        )
        fun onError(t: Throwable)
    }

    private val manager = context.getSystemService(CameraManager::class.java)
    private val bg = HandlerThread("camera").apply { start() }
    private val handler = Handler(bg.looper)
    private val callbackDispatchGate = CameraCallbackDispatchGate()
    // Camera2 posts its callbacks through this executor; guard the post so a framework callback that
    // lands AFTER close() quit the looper is handled instead of thrown — OPPO's LegacyMessageQueue
    // raises IllegalStateException "sending message to a dead thread" rather than returning false.
    // A failed post must RUN the callback inline (binder thread), not drop it: a late onOpened is
    // the ONLY code that closes a device the OS delivered after close() — dropping it leaks the
    // CameraDevice handle until process death (repeated keyguard cold-opens can exhaust the
    // per-process camera budget). Every StateCallback path checks `closed` first, so the inline run
    // just closes the leaked handle and returns.
    private val executor = Executor { cmd ->
        val posted = callbackDispatchGate.dispatch { bg.isAlive && handler.post(cmd) }
        if (!posted) runCatching { cmd.run() }
    }

    /**
     * Posts [block] to the camera thread, or drops it if this controller is closing/closed. On a
     * session-key reopen (Auto HDR, in-sensor zoom, lens switch, high-speed fps) the engine closes THIS
     * controller and opens a new one; a control update or capture racing that swap must not post to the
     * quit looper — OPPO's LegacyMessageQueue throws "dead thread" instead of returning false, which
     * previously crashed capture. Returns whether the post landed.
     */
    private fun postToCamera(block: () -> Unit): Boolean {
        if (closed || !bg.isAlive) return false
        return callbackDispatchGate.dispatch { bg.isAlive && handler.post(block) }
    }

    /**
     * Delayed sibling of [postToCamera]. OPPO's legacy queue can throw after `quitSafely()` instead
     * of returning false; every timeout/trailing task must therefore use this single containment
     * seam and execute its owner-specific fallback when scheduling loses the close race.
     */
    private fun postDelayedToCamera(task: Runnable, delayMs: Long): Boolean {
        if (closed || !bg.isAlive) return false
        return callbackDispatchGate.dispatch { bg.isAlive && handler.postDelayed(task, delayMs) }
    }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    // Set once close() runs so late-arriving open/config callbacks (which can fire after the app
    // backgrounds mid-startup — e.g. onStop behind the keyguard closes us while the session is still
    // configuring) short-circuit instead of touching an already-disconnected device.
    @Volatile private var closed = false
    // CAS gate for close(): reachable from main (pause), setupExecutor (reopens), and the GL-start
    // continuation (openCamera → controller?.close()), so the closed check-then-act alone can race.
    private val closeStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    private lateinit var selection: TeleSelection
    private lateinit var caps: CameraCaps
    private var glSurface: Surface? = null
    // Read on the camera handler thread (startPreview/capturePhoto) and mutated via updateControls,
    // which is invoked from BOTH the main thread (ViewModel) and the camera thread (AEB/BURST chain).
    // @Volatile for visibility; updateControls confines the actual write to the handler thread.
    @Volatile private var controls = ManualControls()
    private var tenBitHlg = false
    private var rawChars: CameraCharacteristics? = null
    private var configAttempt = 0
    // HAL-native log (vendor com.oplus.log.video.mode; 0 = off). It is BOTH a request key and a
    // SESSION key on this device, so it goes into the session parameters (pipeline configuration)
    // AND every repeating/still request. Set once per open(); changing it requires a reopen.
    private var vendorLogMode = 0
    // HAL video stabilization mode for the repeating preview/video request (CONTROL_VIDEO_
    // STABILIZATION_MODE: 0 off / 1 on / 2 preview-stabilization). Drives the HAL's OIS+EIS —
    // the only thing that cuts per-frame motion blur at 300 mm. Updated live via [setVideoStabMode].
    @Volatile private var videoStabHalMode = CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
    // True when the external Hasselblad 300 mm afocal converter is mounted on the native 70 mm tele.
    // The public Camera2 OIS key only says "OIS on". For teleconverter mode, pass the public session
    // hints this device exposes through Camera2: Hasselblad telephoto mode + effective zoom ~= 300/70.
    // These are best-effort and fully guarded.
    private var teleconverterMode = false
    // Engine-RESOLVED hi-res admission ([hiResAdmitted]: photo + 4:3 + standalone + advertised),
    // set BEFORE configure like [teleconverterMode]. @Volatile because the engine writes it on
    // setupExecutor while the fallback ladder re-reads it on the camera thread. The plan drops
    // hi-res on the FIRST failed attempt; [hiResReaderActive] below carries the per-session truth.
    @Volatile var hiResStill = false
    // True only while the CURRENT configure attempt built its processed reader at the full-sensor
    // size — camera-thread-owned, reset every attempt, and the sole source for both the accepted
    // Ready outputs and the still request's SENSOR_PIXEL_MODE.
    private var hiResReaderActive = false
    // Video preview/recording must honor the selected fps even under auto exposure. Photo preview
    // leaves this false so AE can lower its frame rate for a brighter low-light view.
    private var pinAutoFps = false
    // Requested intent is the cross-thread supersession gate; applied mode/generation remain
    // camera-thread-owned and change together before the exact request carrying that identity.
    private val modeIntentGate = PreviewModeIntentGate(
        PreviewModeIntent(pinAutoFps = false, opticsGeneration = 0),
    )
    private var diagnosticRequestMode = CaptureMode.PHOTO
    private var diagnosticOpticsGeneration = 0L
    // >0 → configure a CameraConstrainedHighSpeedCaptureSession at this fps (slow-motion), feeding
    // ONLY the GL input surface (no JPEG/RAW — high-speed sessions forbid extra targets). 0 = the
    // regular tele session. Set once per open(); on a high-speed config failure it drops back to 0.
    private var highSpeedFps = 0

    @Volatile private var pending: Pending? = null
    // Last AF-resolved focus distance, tracked from repeating-preview results so afLock can freeze
    // the lens there (a manual LENS_FOCUS_DISTANCE hold) instead of an AF-mode "lock" call.
    @Volatile private var lastFocusDistance: Float = 0f
    // Tap-to-focus/meter target in SENSOR-normalized coords (0..1); the engine applies the
    // view→sensor rotation before setting it. When non-null it overrides the metering-mode regions
    // with a spot centered here (AE, plus AF in a non-MANUAL focus mode); null restores the mode.
    @Volatile private var meteringPoint: Pair<Float, Float>? = null
    // Set alongside a fresh meteringPoint to request a single AF_TRIGGER_START on the next preview
    // build so the AF engine re-converges on the tapped point; cleared once that one-shot fires.
    @Volatile private var afTriggerPending = false
    // True after a tap-to-focus until the user changes focus mode: the repeating request then uses
    // AF_MODE_AUTO (a one-shot region scan that LOCKS on convergence) instead of CONTINUOUS, so the
    // focus actually holds on the tapped point rather than drifting back.
    @Volatile private var touchAfActive = false
    // clearMeteringPoint(false) folds the tap-owned key reset into the next request that already has
    // to be submitted (focus-mode or retained-optics commit). Fast paths must promote that update to
    // one full rebuild so the cached builder cannot retain the old AE/AF regions invisibly.
    private var tapResetPending = false
    // Throttle counter for the 3A-state diagnostic log (AE/AF convergence on the standalone tele).
    private var threeAFrame = 0
    // Live AE-resolved (ISO, exposureNs) surfaced to the UI so the Shutter/ISO chips can show what AE
    // chose in auto mode. Reported only on change (see startPreview's repeating callback) so a steady
    // scene doesn't spam recomposition.
    @Volatile var onExposure: ((iso: Int?, exposureNs: Long?) -> Unit)? = null
    // HAL-applied zoom from each preview result — drives the GL live-zoom compensation.
    @Volatile var onZoomResult: ((Float) -> Unit)? = null
    private var lastReportedIso: Int? = null
    private var lastReportedExpNs: Long? = null
    // Live lens focus distance (diopters, from CaptureResult.LENS_FOCUS_DISTANCE) surfaced to the UI:
    // shows where AF actually parked the lens, and seeds the manual-focus slider on the AF→MF
    // handoff so fine focus starts from AF's solution instead of a stale value. Reported on change,
    // throttled with the exposure readout.
    @Volatile var onFocusDistance: ((Float) -> Unit)? = null
    // AF engine state for the reticle color (Sony green-on-lock / red-on-fail — at 300 mm the
    // lock/fail distinction is the point of tap-AF). Raw CONTROL_AF_STATE int, reported ~3 Hz,
    // change-gated like the exposure readout; the engine maps it to the UI enum.
    @Volatile var onAfState: ((Int) -> Unit)? = null
    private var lastReportedFocus = Float.NaN
    private var lastReportedAfState = -1

    @SuppressLint("MissingPermission") // caller guarantees CAMERA permission before open()
    fun open(
        selection: TeleSelection,
        caps: CameraCaps,
        glInputSurface: Surface,
        controls: ManualControls,
        tenBitHlg: Boolean,
        highSpeedFps: Int = 0,
        vendorLogMode: Int = 0,
        videoStabHalMode: Int = CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
        teleconverterMode: Boolean = false,
        pinAutoFps: Boolean = false,
        diagnosticOpticsGeneration: Long = 0,
        // Dual-open camera switching: open the DEVICE now (the outgoing camera keeps streaming
        // through its ~120 ms) but hold the session until [startDeferredSession] — the preview
        // surface belongs to the old session until it closes. onDeviceOpened fires from the camera
        // thread once the device handle is live.
        deferSession: Boolean = false,
        onDeviceOpened: (() -> Unit)? = null,
        runNativeAcquisition: ((() -> Unit) -> Boolean) =
            UnsafeRecorderQuarantine::runNativeAcquisition,
        onReady: Ready,
        onError: ErrorCb,
    ) {
        this.selection = selection
        this.caps = caps
        this.glSurface = glInputSurface
        val modeIntent = if (pinAutoFps && controls.exposureMode == ExposureMode.PROGRAM) {
            controls.copy(programAppSide = false)
        } else {
            controls
        }
        this.controls = modeIntent.normalizedFor(caps).normalizedForCaptureMode(
            if (pinAutoFps) CaptureMode.VIDEO else CaptureMode.PHOTO,
        )
        this.tenBitHlg = tenBitHlg
        this.highSpeedFps = highSpeedFps
        this.vendorLogMode = vendorLogMode
        this.videoStabHalMode = videoStabHalMode
        this.teleconverterMode = teleconverterMode
        this.pinAutoFps = pinAutoFps
        this.diagnosticRequestMode = if (pinAutoFps) CaptureMode.VIDEO else CaptureMode.PHOTO
        this.diagnosticOpticsGeneration = diagnosticOpticsGeneration
        modeIntentGate.reset(PreviewModeIntent(pinAutoFps, diagnosticOpticsGeneration))
        this.rawChars = runCatching {
            manager.getCameraCharacteristics(selection.physicalId ?: selection.logicalId)
        }.getOrNull()

        // openCamera can throw SYNCHRONOUSLY — CameraAccessException CAMERA_DISABLED when the app is
        // opening from a background proc state (e.g. relaunched behind the keyguard / while the screen
        // just woke), or SecurityException. Guard it so that lifecycle race surfaces as an onError
        // status instead of crashing the app; the next foreground resume() reopens cleanly.
        val openAdmitted = runNativeAcquisition {
            runCatching {
                manager.openCamera(selection.logicalId, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (closed) { runCatching { camera.close() }; return } // closed before open completed
                    device = camera
                    configAttempt = 0
                    if (deferSession) {
                        deferredReady = onReady
                        deferredError = onError
                        onDeviceOpened?.invoke()
                    } else {
                        val sessionAdmitted = runNativeAcquisition {
                            runCatching { configureSession(onReady, onError) }
                                .onFailure { onError.onError(it) }
                        }
                        if (!sessionAdmitted) {
                            runCatching { camera.close() }
                            onError.onError(IllegalStateException("native acquisition closed before session"))
                        }
                    }
                }
                override fun onDisconnected(camera: CameraDevice) {
                    if (closed) return // an intentional teardown's late disconnect is not a health event
                    onError.onError(IllegalStateException("Camera disconnected"))
                    close()
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    // Same closed gate as every other StateCallback path: tearing down the
                    // standalone camera during a photo↔video flip can make this HAL hiccup
                    // ("Error clearing streaming request: Function not implemented (-38)" →
                    // ERROR_CAMERA_DEVICE) — surfacing that as a user-visible camera error and
                    // kicking bounded recovery for a device we were closing anyway is pure
                    // noise. A LIVE session's error still reports (closed is set only by close()).
                    if (closed) {
                        if (BuildConfig.DEBUG) Log.w(TAG, "Ignoring camera error $error during intentional close")
                        return
                    }
                    // Always log the RAW trigger before the engine's recovery path re-frames it:
                    // during the long-exposure investigation the only visible signature was the
                    // framework's own stopRepeating log inside close() — the actual onError source
                    // was silent, which cost a device-bisect session to attribute.
                    Log.e(TAG, "CameraDevice.StateCallback.onError: code=$error (device=${camera.id})")
                    onError.onError(IllegalStateException("Camera error $error"))
                    close()
                }
                })
            }.onFailure { onError.onError(it) }
        }
        if (!openAdmitted) {
            onError.onError(IllegalStateException("native acquisition closed before camera open"))
        }
    }

    // Callbacks parked by a deferSession open until the old camera releases the preview surface.
    private var deferredReady: Ready? = null
    private var deferredError: ErrorCb? = null

    /** Second phase of a deferSession [open]: the old session is closed, the surface is free. */
    fun startDeferredSession(): Boolean {
        val ready = deferredReady ?: return false
        val err = deferredError ?: return false
        val accepted = postToCamera {
            if (deferredReady !== ready || deferredError !== err) return@postToCamera
            deferredReady = null
            deferredError = null
            val sessionAdmitted = UnsafeRecorderQuarantine.runNativeAcquisition {
                runCatching { configureSession(ready, err) }.onFailure { err.onError(it) }
            }
            if (!sessionAdmitted) {
                err.onError(IllegalStateException("native acquisition closed before deferred session"))
            }
        }
        if (!accepted) {
            deferredReady = null
            deferredError = null
            err.onError(IllegalStateException("camera thread unavailable before deferred session"))
        }
        return accepted
    }

    /**
     * Device log-pipeline selector. Advertised in this device's availableRequestKeys AND
     * availableSessionKeys for the tele, so constructing the Key and setting it is standard Camera2
     * vendor-tag usage — the framework resolves it against the device tag provider.
     */
    private val logVideoModeKey = CaptureRequest.Key("com.oplus.log.video.mode", Int::class.javaObjectType)

    /** Applies the HAL-native log mode (no-op at 0). Defensive: a rejected vendor tag must never kill the preview build. */
    private fun CaptureRequest.Builder.applyVendorLog() {
        if (vendorLogMode == 0) return
        runCatching { set(logVideoModeKey, vendorLogMode) }
            .onSuccess { if (BuildConfig.DEBUG) Log.i(TAG, "vendor log.video.mode=$vendorLogMode applied") }
            .onFailure { if (BuildConfig.DEBUG) Log.w(TAG, "vendor log.video.mode=$vendorLogMode rejected: ${it.message}") }
    }

    // The QTI vendor "extras" (Auto HDR, in-sensor zoom, ideal RAW) were all removed: Auto HDR SIGABRTs
    // the camera-provider HAL on reopen+capture, in-sensor zoom is redundant with the standard
    // CONTROL_ZOOM_RATIO API, and ideal RAW silently breaks DNG capture. Only the native log session key
    // remains (see applyVendorLog) — set through Camera2, HAL-stable, device-verified.

    /**
     * The HAL's stabilization vendor tag (`com.oplus.video.stabilization.mode`, int) — the vendor
     * mirror of `VIDEO_STABILIZATION_MODE`. Advertised in the tele's request+session keys, so we
     * set it alongside the standard CONTROL_VIDEO_STABILIZATION_MODE to nudge the HAL's OIS/EIS
     * profile toward the active lens. Best-effort.
     */
    private val vendorVideoStabKey = CaptureRequest.Key("com.oplus.video.stabilization.mode", Int::class.javaObjectType)

    // OPPO CameraUnit-related mode hints that are also exposed as Camera2 session/request keys on the
    // tele. The Camera2 ceiling is these public hints plus
    // CONTROL_VIDEO_STABILIZATION_MODE=PREVIEW_STABILIZATION.
    private val oplusModeKey = CaptureRequest.Key("com.oplus.camera.mode", Byte::class.javaObjectType)
    private val oplusOriginalZoomRatioKey = CaptureRequest.Key("com.oplus.original.zoomRatio", Float::class.javaObjectType)

    // Cached repeating request builder + callback for the zoom fast path: [setZoomRatio] mutates
    // ONLY the zoom keys on this builder and resubmits, instead of re-deriving the full request per
    // pinch tick. Camera-thread confined; refreshed by every full startPreview rebuild.
    private var previewBuilder: CaptureRequest.Builder? = null
    private var previewCallback: CameraCaptureSession.CaptureCallback? = null
    @Volatile internal var latestPreviewRequestGeneration: Long = 0
        private set
    private var customWbSampleSequence = 0L
    private var pendingCustomWbSample: PendingCustomWbSample? = null

    /** Sets the HAL video stabilization on the preview/video repeating request: the standard mode plus the vendor mirror. */
    private fun CaptureRequest.Builder.applyVideoStab() {
        set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, videoStabHalMode)
        runCatching { set(vendorVideoStabKey, videoStabHalMode) }
    }

    /**
     * Best-effort hints for the external 300 mm converter.
     *
     * The device does not publish a raw "OIS focal scale" Camera2 key. The exposed Camera2 overlap is:
     *  - `com.oplus.camera.mode` byte: Hasselblad telephoto mode.
     *  - `com.oplus.original.zoomRatio` float: logical zoom context for the session.
     *
     * Setting these cannot force an unavailable OIS profile by itself, but it gives the HAL the
     * effective 300 mm context when it chooses the public PREVIEW_STABILIZATION OIS+EIS profile.
     * Fully guarded because device-specific tags can disappear or reject values across ColorOS builds.
     */
    private fun CaptureRequest.Builder.applyTeleconverterHints() {
        val effectiveZoom = controls.zoomRatio.coerceAtLeast(1f) *
            if (teleconverterMode) TELECONVERTER_MAGNIFICATION else 1f
        if (teleconverterMode) {
            runCatching {
                set(oplusModeKey, OPLUS_CAMERA_MODE_TELEPHOTO_HASSELBLAD)
            }.onFailure { if (BuildConfig.DEBUG) Log.w(TAG, "Hasselblad camera mode hint rejected: ${it.message}") }
        }
        runCatching {
            set(oplusOriginalZoomRatioKey, effectiveZoom)
        }.onFailure { if (BuildConfig.DEBUG) Log.w(TAG, "original zoom ratio hint rejected: ${it.message}") }
    }

    /**
     * Zoom-only fast path (see CameraEngine.setZoomRatio): mutate the cached repeating builder's
     * zoom keys and resubmit — no full request re-derivation, no metering/AF churn. Falls back to a
     * full [startPreview] rebuild when no builder is cached yet (pre-first-preview) or in
     * constrained high-speed mode (whose repeating request is a burst list).
     */
    fun setZoomRatio(ratio: Float, requestRatio: Float = ratio) {
        postToCamera {
            // [controls] feeds STILL requests too (capturePhoto snapshots it): store the EXACT
            // user-framed ratio, not the mid-gesture wide-aimed HAL target — a still captured in
            // the interaction tail otherwise frames ~17% wider than the viewfinder (the repeating
            // stream's wide aim is GL-compensated in the preview; a still has no compensation).
            controls = controls.copy(zoomRatio = requestRatio)
            submitZoomFastPath(ratio)
        }
    }

    /**
     * Still-truth-only zoom update for THROTTLED (non-submitted) ticks: capturePhoto snapshots
     * [controls], so a shutter press inside the ~200 ms throttle window must see the ratio the
     * viewfinder already frames (GL zooms instantly), not the previous submitted tick's. No
     * repeating-request touch here — that is exactly what the throttle exists to avoid.
     */
    fun noteRequestZoom(requestRatio: Float) {
        postToCamera { controls = controls.copy(zoomRatio = requestRatio) }
    }

    /** Camera-thread body of the zoom fast path; submits [ratio] on the repeating stream only. */
    private fun submitZoomFastPath(ratio: Float) {
        if (tapResetPending) { startPreview(); return }
        val s = session ?: return
        val b = previewBuilder
        val cb = previewCallback
        if (b == null || cb == null || highSpeedFps > 0) { startPreview(); return }
        if (BuildConfig.DEBUG) Log.i(TAG, "ZoomTrace: submit=$ratio t=${android.os.SystemClock.uptimeMillis()}")
        runCatching {
            caps.zoomRatioRange?.let {
                b.set(CaptureRequest.CONTROL_ZOOM_RATIO, clampToOrderedBounds(ratio, it.lower, it.upper))
            }
            // Keep OPPO's logical-zoom session hint in step (it contextualizes OIS/EIS strength);
            // same guarded write as applyTeleconverterHints.
            val effectiveZoom = ratio.coerceAtLeast(1f) * if (teleconverterMode) TELECONVERTER_MAGNIFICATION else 1f
            runCatching { b.set(oplusOriginalZoomRatioKey, effectiveZoom) }
            s.setRepeatingRequest(b.build(), cb, handler)
        }.onFailure {
            if (BuildConfig.DEBUG) Log.w(TAG, "zoom fast path failed, rebuilding: ${it.message}")
            // The cached builder/session can become invalid during a configure transition.
            // Re-derive the full repeating request so the requested zoom is not silently lost.
            startPreview()
        }
    }

    /** Live-updates the HAL video stabilization mode and re-issues the repeating request. */
    fun setVideoStabMode(controlMode: Int) {
        videoStabHalMode = controlMode
        postToCamera { startPreview() }
    }

    /**
     * Builds the capture session, degrading via a fallback ladder when [onConfigureFailed] fires
     * (HLG10 preview + JPEG + RAW is a demanding combo many HALs reject):
     *   attempt 0 — full: preview (HLG10 if supported) + JPEG + RAW
     *   attempt 1 — drop RAW
     *   attempt 2 — also drop HLG10 (SDR preview)
     *   attempt 3 — preview only (no JPEG, no RAW)
     * Each [onConfigureFailed] advances [configAttempt] and reconfigures; once the ladder is
     * exhausted the failure is surfaced through [onError].
     */
    // WrongConstant suppressed for the session type only: TC mode passes the STOCK APP's vendor
    // operation_mode (0x80b4, captured from CamX configure_streams — sensor mode 48, the 300 mm TC
    // OIS profile), which lint cannot know. The HAL rejecting it lands in onConfigureFailed and the
    // existing fallback ladder proceeds with SESSION_REGULAR semantics.
    @android.annotation.SuppressLint("WrongConstant")
    private fun configureSession(onReady: Ready, onError: ErrorCb) {
        val camera = device ?: return onError.onError(IllegalStateException("camera device unavailable"))
        val preview = glSurface ?: return onError.onError(IllegalStateException("preview surface unavailable"))

        // Discard any readers built by a previous (failed) attempt before rebuilding.
        runCatching { jpegReader?.close() }
        runCatching { rawReader?.close() }
        jpegReader = null
        rawReader = null
        hiResReaderActive = false

        // High-speed (slow-motion) uses a dedicated constrained session with ONLY the GL surface;
        // it cannot coexist with the JPEG/RAW still readers, so it is a separate path.
        if (highSpeedFps > 0) { configureHighSpeed(camera, preview, onReady, onError); return }

        val attempt = configAttempt
        val plan = sessionAttemptPlan(
            attempt = attempt,
            wantHlg = tenBitHlg && caps.supportsHlg10(),
            supportsRaw = caps.supportsRaw,
            // RAW routed to a physical sub-camera of a logical multicamera crashes this QTI HAL
            // (configureStreams: 'DataSpace override not allowed for format 0x20' -> SIGSEGV in
            // ChiMulticameraBase::Initialize). Only enable RAW for a standalone (non-routed) camera.
            standalone = selection.physicalId == null,
            logicalMultiCamera = caps.isLogicalMultiCamera,
            teleconverterMode = teleconverterMode,
            wantHiRes = hiResStill,
        )
        val useHlg = plan.useHlg
        val useJpeg = plan.useJpeg
        val useRaw = plan.useRaw

        val configs = ArrayList<OutputConfiguration>()

        val previewCfg = OutputConfiguration(preview).apply {
            selection.physicalId?.let { setPhysicalCameraId(it) }
            if (useHlg) setDynamicRangeProfile(DynamicRangeProfiles.HLG10)
        }
        configs.add(previewCfg)

        if (useJpeg) {
            // The LOGICAL multicamera can't allocate the full-size JPEG blob (device-observed
            // gralloc "SnapAlloc: ValidateDescriptor invalid" — the image never arrives and the
            // shot dies), so stills there come as YUV and the app encodes them itself (the save
            // pipeline re-encodes through a Bitmap either way). Standalone cameras keep the proven
            // HAL-JPEG path.
            val useYuv = caps.isLogicalMultiCamera
            // Hi-res admission RE-CHECKED at the seam (defensive, same discipline as the RAW gate
            // above): standalone only — the plan already gates, but a big blob reaching a routed or
            // logical session is exactly the gralloc/HAL-crash class this file exists to prevent.
            val useHiRes = plan.useHiResStill && selection.physicalId == null &&
                !caps.isLogicalMultiCamera && caps.hiResJpegSize != null
            val size = when {
                useHiRes -> caps.hiResJpegSize
                useYuv -> caps.largestYuvSize
                else -> caps.largestJpegSize
            }
            size?.let {
                val reader = ImageReader.newInstance(
                    it.width, it.height,
                    if (useYuv) ImageFormat.YUV_420_888 else ImageFormat.JPEG,
                    2,
                )
                reader.setOnImageAvailableListener({ r -> onImage(r, isRaw = false) }, handler)
                jpegReader = reader
                hiResReaderActive = useHiRes
                configs.add(OutputConfiguration(reader.surface).apply {
                    selection.physicalId?.let { pid -> setPhysicalCameraId(pid) }
                })
            }
        }

        if (useRaw) caps.rawSize?.let { size ->
            val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 2)
            reader.setOnImageAvailableListener({ onImage(it, isRaw = true) }, handler)
            rawReader = reader
            configs.add(OutputConfiguration(reader.surface).apply {
                selection.physicalId?.let { setPhysicalCameraId(it) }
            })
        }

        val sessionConfig = SessionConfiguration(
            // TC mode: pass the stock app's TC operation_mode (0x80b4) as the sessionType — captured
            // via CamX `configure_streams() operation_mode: 0x80b4` on the stock app (→ sensor mode 48,
            // the 300mm TC OIS profile). Falls back via onConfigureFailed if the framework/HAL rejects it.
            if (plan.useVendorOperationMode) 0x80b4 else SessionConfiguration.SESSION_REGULAR,
            configs,
            executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (closed) { runCatching { s.close() }; return } // closed before config completed
                    session = s
                    if (BuildConfig.DEBUG) Log.i(TAG, "Session configured (fallback=$attempt, hlg=$useHlg, jpeg=$useJpeg, raw=$useRaw, hiRes=$hiResReaderActive, vendorLog=$vendorLogMode)")
                    when (sessionStartDelivery(startPreview())) {
                        SessionStartDelivery.READY -> onReady.onReady(
                            acceptedPhotoSessionOutputs(
                                processedReaderPresent = jpegReader != null,
                                rawReaderPresent = rawReader != null,
                                hiResReaderPresent = hiResReaderActive,
                            ),
                        )
                        SessionStartDelivery.ERROR ->
                            onError.onError(IllegalStateException("repeating preview request failed"))
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    // Advance the fallback ladder and retry; give up once it is exhausted.
                    configAttempt = attempt + 1
                    val maxAttempt = if (teleconverterMode) MAX_TELE_CONFIG_ATTEMPT else MAX_CONFIG_ATTEMPT
                    if (configAttempt > maxAttempt) {
                        Log.e(TAG, "Session configure failed; fallback ladder exhausted")
                        onError.onError(IllegalStateException("session configure failed"))
                    } else {
                        if (BuildConfig.DEBUG) Log.w(TAG, "Session configure failed at fallback $attempt; retrying at $configAttempt")
                        runCatching { configureSession(onReady, onError) }
                            .onFailure { onError.onError(it) }
                    }
                }
            },
        )
        // Session keys must ride in the session parameters, not only per-frame requests. This matters
        // for PREVIEW_STABILIZATION (documented as a session key on this HAL) and for the Oplus vendor
        // mirror/original-zoom hints: the HAL can pick its OIS/EIS profile at configure time, which is
        // where the CameraUnit path may also supply stabilization + Explorer context.
        // Fully defensive — a session-parameter failure falls back to a plain session, not a dead camera.
        runCatching {
            val sp = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            sp.applyVideoStab()
            sp.applyTeleconverterHints()
            sp.applyVendorLog()
            sessionConfig.setSessionParameters(sp.build())
        }.onFailure { if (BuildConfig.DEBUG) Log.w(TAG, "session params with vendor stabilization/log failed: ${it.message}") }
        camera.createCaptureSession(sessionConfig)
    }

    /**
     * Builds a [CameraConstrainedHighSpeedCaptureSession] targeting only the GL input surface at
     * [highSpeedFps]. The GL SurfaceTexture must already be sized to a supported high-speed size
     * (the engine sets it before opening). Still capture (JPEG/RAW) is unavailable in this mode.
     * If configuration fails, drop [highSpeedFps] to 0 and rebuild the regular session so the user
     * still gets a live preview instead of a dead viewfinder.
     */
    private fun configureHighSpeed(camera: CameraDevice, preview: Surface, onReady: Ready, onError: ErrorCb) {
        val cfg = OutputConfiguration(preview).apply {
            selection.physicalId?.let { setPhysicalCameraId(it) }
        }
        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_HIGH_SPEED, listOf(cfg), executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (closed) { runCatching { s.close() }; return }
                    session = s
                    if (BuildConfig.DEBUG) Log.i(TAG, "High-speed session configured (${highSpeedFps}fps)")
                    when (sessionStartDelivery(startHighSpeedPreview())) {
                        SessionStartDelivery.READY -> onReady.onReady(PhotoSessionOutputs())
                        SessionStartDelivery.ERROR ->
                            onError.onError(IllegalStateException("high-speed repeating request failed"))
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "High-speed session config failed at ${highSpeedFps}fps; falling back to regular session")
                    highSpeedFps = 0
                    configAttempt = 0
                    runCatching { configureSession(onReady, onError) }.onFailure { onError.onError(it) }
                }
            },
        )
        runCatching {
            val sp = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            sp.applyVideoStab()
            sp.applyTeleconverterHints()
            sp.applyVendorLog()
            sessionConfig.setSessionParameters(sp.build())
        }.onFailure { if (BuildConfig.DEBUG) Log.w(TAG, "high-speed session params with vendor stabilization/log failed: ${it.message}") }
        runCatching { camera.createCaptureSession(sessionConfig) }.onFailure {
            if (BuildConfig.DEBUG) Log.w(TAG, "createCaptureSession(HIGH_SPEED) threw; falling back: ${it.message}")
            highSpeedFps = 0
            configAttempt = 0
            runCatching { configureSession(onReady, onError) }.onFailure { e -> onError.onError(e) }
        }
    }

    /** Issues the high-speed repeating burst (one request expanded to N by the HAL) at [highSpeedFps]. */
    private fun startHighSpeedPreview(): Boolean {
        if (closed) return false
        val camera = device ?: return false
        val preview = glSurface ?: return false
        val s = session as? CameraConstrainedHighSpeedCaptureSession ?: return false
        return runCatching {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(preview)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(highSpeedFps, highSpeedFps))
                // High-speed sessions keep HAL video stabilization OFF: the constrained session type
                // predates the OIS+EIS profiles the regular path requests, and this path is dead in
                // shipping builds anyway (high-speed SIGABRTs this HAL; desiredHighSpeedFps()==0).
                // If high-speed is ever revived, wire videoStabHalMode here like startPreview does.
                // (App-side gyro EIS no longer exists — the HAL owns stabilization.)
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                if (caps.oisAvailable) {
                    set(
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        if (controls.oisEnabled) CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
                        else CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF,
                    )
                }
            }
            val list = s.createHighSpeedRequestList(builder.build())
            s.setRepeatingBurst(list, null, handler)
            true
        }.getOrElse {
            if (BuildConfig.DEBUG) Log.w(TAG, "startHighSpeedPreview skipped: ${it.message}")
            false
        }
    }

    private enum class TapTriggerSubmission {
        NOT_REQUESTED,
        ACCEPTED,
        REJECTED_UNCHANGED,
        FAILED_UNCERTAIN,
    }

    private data class PreviewSubmission(
        val repeatingAccepted: Boolean,
        val tapTrigger: TapTriggerSubmission,
    )

    /**
     * (Re)issues the repeating preview request. When [afTriggerPending] is set (a fresh tap-to-focus
     * point) and AF is running (non-MANUAL, unlocked focus), it first fires ONE triggered capture
     * identical to the repeating request but carrying CONTROL_AF_TRIGGER_START, so the AF engine
     * converges on the tapped region; the trigger is then cleared (IDLE) and the repeating request
     * continues to hold that result. AF Lock instead keeps lens ownership while the repeating request
     * applies only the tap-owned AE region. All calls guard nulls so a torn-down session is a no-op.
     */
    private fun startPreview(): Boolean = submitPreviewRequest().repeatingAccepted

    /** Detailed sibling used by tap AF so partial CANCEL/START submission is never called success. */
    private fun submitPreviewRequest(): PreviewSubmission {
        if (closed) return PreviewSubmission(false, TapTriggerSubmission.REJECTED_UNCHANGED)
        // In high-speed mode the session is a constrained high-speed one; its repeating request must
        // be a burst list, so route there instead of the regular single-request path below.
        if (highSpeedFps > 0) {
            val tapRequested = afTriggerPending
            return PreviewSubmission(
                repeatingAccepted = startHighSpeedPreview(),
                tapTrigger = if (tapRequested) {
                    TapTriggerSubmission.REJECTED_UNCHANGED
                } else {
                    TapTriggerSubmission.NOT_REQUESTED
                },
            )
        }
        val camera = device ?: return PreviewSubmission(false, TapTriggerSubmission.REJECTED_UNCHANGED)
        val preview = glSurface ?: return PreviewSubmission(false, TapTriggerSubmission.REJECTED_UNCHANGED)
        val s = session ?: return PreviewSubmission(false, TapTriggerSubmission.REJECTED_UNCHANGED)
        // Process-global and strictly increasing across controller replacements. Device tests use
        // this identity to reject late results from the outgoing Photo/Video request after a mode
        // switch; a mode label alone cannot distinguish an old Photo callback from the new one.
        val requestGeneration = previewRequestSequence.incrementAndGet()
        val requestMode = diagnosticRequestMode
        val requestOpticsGeneration = diagnosticOpticsGeneration
        val touchAfUsesAuto = touchAfMayTrigger(
            touchAfActive = touchAfActive,
            maxAfRegions = caps.maxAfRegions,
            focusMode = controls.focusMode,
            afModes = caps.afModes,
        )
        val tapPointRequested = afTriggerPending
        // AF Lock owns the lens while a tap may still replace the AE region. Do not send a bogus
        // AF trigger against AF_MODE_OFF; the repeating request alone accepts that metering point.
        val tapAfTriggerRequired = tapAfTriggerRequired(tapPointRequested, touchAfUsesAuto, controls.afLock)
        // The device can be disconnected asynchronously (app backgrounded, another client, HAL) between
        // session config and here; createCaptureRequest/setRepeatingRequest then throw CameraAccess/
        // IllegalState. Guard the whole build+submit so a torn-down session degrades to "no preview
        // this cycle" instead of crashing the camera thread.
        var tapRequestStarted = false
        return runCatching {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview)
                applyManualControls(
                    controls,
                    caps,
                    pinAutoFps = pinAutoFps || smoothPreviewBoost,
                    previewExposureCap = true,
                    enforceFrameRate = pinAutoFps,
                )
                applyVendorLog()
                applyVideoStab()
                applyTeleconverterHints()
                applyMetering(this, controls)
                pendingCustomWbSample?.let { setTag(it.tag) }
                // Tap-to-focus / AF-lock overrides: ONE shared applier with the sensor fast path
                // (ARCH4-5 — the two sites used to be hand-duplicated byte-for-byte, the exact
                // drift class that produced the c928eac/f61594a regression pair). The tap region
                // is set by applyMetering above; the trigger below drives the one-shot scan.
                applyAfOverrides(this)
            }
            // A fresh repeating request means the HAL may ramp its zoom again (session reopen ramps
            // from 1.0). Reset the change gate so the FIRST result after every rebuild forwards —
            // otherwise a final ramp value equal to the pre-rebuild steady value would be suppressed
            // and the GL compensation would hold a stale mid-ramp halZoom.
            lastForwardedResultZoom = Float.NaN
            val callback = object : CameraCaptureSession.CaptureCallback() {
                private var firstDiagnosticResultPending = true
                private var firstAfResultPending = true

                override fun onCaptureCompleted(
                    session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult,
                ) {
                    result.get(CaptureResult.CONTROL_ZOOM_RATIO)?.let { rz ->
                        // Change-gated: this callback runs 30-60 Hz and the GL compensation only
                        // needs FRESH values while the HAL is actually ramping — forwarding a
                        // steady value allocated a capturing lambda + GL handler post per frame
                        // for a no-op (the hottest callback in the app).
                        if (rz != lastForwardedResultZoom) {
                            lastForwardedResultZoom = rz
                            onZoomResult?.invoke(rz)
                        }
                        if (BuildConfig.DEBUG && rz != lastTracedResultZoom) {
                            lastTracedResultZoom = rz
                            Log.i(TAG, "ZoomTrace: result=$rz t=${android.os.SystemClock.uptimeMillis()}")
                        }
                    }
                    result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { lastFocusDistance = it }
                    // Custom WB deliberately does not retain or read a cached value: it owns a
                    // fresh tagged unlocked-AUTO request and accepts only its converged result.
                    // The gains .get lives INSIDE the sample gate (PERF4-3): result.get for an
                    // RggbChannelVector allocates a framework object per call, and this callback
                    // runs 30-60 Hz — fetching it unconditionally was steady-state GC garbage
                    // consumed only during a one-shot custom-WB sample.
                    pendingCustomWbSample?.let { sample ->
                        val awbGains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
                        if (customWbResultBelongsToRequest(
                                expectedTag = sample.tag,
                                resultTag = request.tag,
                                awbMode = request.get(CaptureRequest.CONTROL_AWB_MODE),
                                awbLocked = request.get(CaptureRequest.CONTROL_AWB_LOCK),
                                awbState = result.get(CaptureResult.CONTROL_AWB_STATE),
                                gainsAvailable = awbGains != null,
                            )
                        ) {
                            finishCustomWbSample(sample.id, awbGains)
                        }
                    }
                    result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastIso = it }
                    result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastExposureNs = it }
                    threeAFrame++
                    // Surface the AE-resolved ISO/shutter to the UI (throttled ~3 Hz, only on change)
                    // so the Shutter/ISO chips can show what AE actually chose in auto mode.
                    if (threeAFrame % 10 == 0) {
                        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                        val expNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                        if (iso != lastReportedIso || expNs != lastReportedExpNs) {
                            lastReportedIso = iso
                            lastReportedExpNs = expNs
                            onExposure?.invoke(iso, expNs)
                        }
                        if (lastReportedFocus.isNaN() ||
                            kotlin.math.abs(lastFocusDistance - lastReportedFocus) > 0.005f
                        ) {
                            lastReportedFocus = lastFocusDistance
                            onFocusDistance?.invoke(lastFocusDistance)
                        }
                        val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                        if (afState != null && shouldPublishAfState(
                                requestGeneration = requestGeneration,
                                latestRequestGeneration = latestPreviewRequestGeneration,
                                firstResultForRequest = firstAfResultPending,
                                requestAfTrigger = request.get(CaptureRequest.CONTROL_AF_TRIGGER),
                                afState = afState,
                                lastReportedAfState = lastReportedAfState,
                            )
                        ) {
                            firstAfResultPending = false
                            lastReportedAfState = afState
                            onAfState?.invoke(afState)
                        }
                    }
                    // Diagnostic: log what 3A is actually doing (throttled ~1/sec) so we can tell
                    // whether AE/AF are converging on the standalone tele or effectively inert.
                    // Debug builds only — release users don't need per-second camera telemetry
                    // in logcat (minor capability disclosure + log spam; security review).
                    if (BuildConfig.DEBUG && (firstDiagnosticResultPending || threeAFrame % 30 == 0)) {
                        firstDiagnosticResultPending = false
                        val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                        val af = result.get(CaptureResult.CONTROL_AF_STATE)
                        val afMode = result.get(CaptureResult.CONTROL_AF_MODE)
                        val ois = result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)
                        val vstab = result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)
                        val effectiveZoom = controls.zoomRatio.coerceAtLeast(1f) *
                            if (teleconverterMode) TELECONVERTER_MAGNIFICATION else 1f
                        Log.i(TAG, "3A: controllerId=$diagnosticId opticsGeneration=$requestOpticsGeneration requestGeneration=$requestGeneration mode=${requestMode.name} aeState=$ae afState=$af afMode=$afMode iso=${result.get(CaptureResult.SENSOR_SENSITIVITY)} expNs=${result.get(CaptureResult.SENSOR_EXPOSURE_TIME)} lens=$lastFocusDistance ois=$ois vstab=$vstab (req=$videoStabHalMode tele=$teleconverterMode effZoom=$effectiveZoom)")
                    }
                }
            }
            // Tap-to-focus one-shot: CANCEL any in-progress AF, then START a fresh scan on the new
            // region, then fall through to the repeating request (trigger IDLE) so the converged
            // focus is held. Cancel-then-start is more reliable than a bare START when the AF engine
            // is mid-scan (common in CONTINUOUS mode).
            if (tapAfTriggerRequired) {
                if (BuildConfig.DEBUG) Log.i(TAG, "Touch AF: scanning region $meteringPoint")
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
                s.capture(builder.build(), callback, handler)
                tapRequestStarted = true
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                s.capture(builder.build(), callback, handler)
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            }
            s.setRepeatingRequest(builder.build(), callback, handler)
            // Only an accepted repeating request owns the cache/generation. A failed candidate must
            // leave the prior builder/callback authoritative so its in-flight results stay gated in.
            latestPreviewRequestGeneration = requestGeneration
            previewBuilder = builder
            previewCallback = callback
            val tapTrigger = if (tapPointRequested) {
                TapTriggerSubmission.ACCEPTED
            } else {
                TapTriggerSubmission.NOT_REQUESTED
            }
            afTriggerPending = false
            tapResetPending = false
            PreviewSubmission(true, tapTrigger)
        }.getOrElse {
            if (BuildConfig.DEBUG) Log.w(TAG, "startPreview skipped: ${it.message}")
            PreviewSubmission(
                repeatingAccepted = false,
                tapTrigger = when {
                    !tapPointRequested -> TapTriggerSubmission.NOT_REQUESTED
                    tapRequestStarted -> TapTriggerSubmission.FAILED_UNCERTAIN
                    else -> TapTriggerSubmission.REJECTED_UNCHANGED
                },
            )
        }
    }

    /**
     * Applies the metering regions as AE (and, in an AF focus mode, AF) rectangles, sized to the
     * RAW/producer sensor active array.
     *
     * A tap-to-meter [meteringPoint] (SENSOR-normalized) takes priority and OVERRIDES the mode: a
     * ~10% spot centered on that point, clamped inside the active array. When no point is set the
     * metering mode drives it:
     *   MATRIX → no region (default full-frame metering).
     *   CENTER → one center rectangle covering 40% of the active array at METERING_WEIGHT_MAX.
     *   SPOT   → one center rectangle covering 12%, at METERING_WEIGHT_MAX.
     * No-op when the active array is unavailable, so it degrades to full-frame metering.
     */
    /** Latest-frame exposure, surfaced to the live UI and still-capture metadata. */
    @Volatile var lastIso: Int = 0
        private set
    @Volatile var lastExposureNs: Long = 0L
        private set

    /**
     * Owns one fresh grey-card measurement. Rebuilding the repeating request with [PendingCustomWbSample.tag]
     * makes callbacks from frames already in flight ineligible; only a later unlocked-AUTO,
     * AWB-converged result from this request can complete the sample.
     */
    fun requestCustomWbSample(
        onResult: (android.hardware.camera2.params.RggbChannelVector?) -> Unit,
    ) {
        val posted = postToCamera {
            pendingCustomWbSample?.let { finishCustomWbSample(it.id, null) }
            if (device == null || session == null || !::caps.isInitialized ||
                !controlAvailability(caps.controlCapabilities(), controls).customWbCaptureEnabled
            ) {
                runCatching { onResult(null) }
                return@postToCamera
            }
            val id = ++customWbSampleSequence
            val tag = CustomWbSampleTag(id)
            val timeout = Runnable { finishCustomWbSample(id, null) }
            pendingCustomWbSample = PendingCustomWbSample(id, tag, timeout, onResult)
            if (!startPreview()) {
                finishCustomWbSample(id, null)
                return@postToCamera
            }
            if (!postDelayedToCamera(timeout, CUSTOM_WB_SAMPLE_TIMEOUT_MS)) {
                finishCustomWbSample(id, null)
            }
        }
        if (!posted) runCatching { onResult(null) }
    }

    private fun finishCustomWbSample(
        expectedId: Long,
        gains: android.hardware.camera2.params.RggbChannelVector?,
    ): Boolean {
        val sample = pendingCustomWbSample?.takeIf { it.id == expectedId } ?: return false
        pendingCustomWbSample = null
        handler.removeCallbacks(sample.timeout)
        runCatching { sample.onResult(gains) }
        return true
    }

    private fun applyMetering(builder: CaptureRequest.Builder, controls: ManualControls) {
        val targets = meteringRegionTargets(caps.maxAeRegions, caps.maxAfRegions, controls.focusMode)
        if (!targets.ae && !targets.af) return
        val active = rawChars?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return

        val point = meteringPoint
        val (cx, cy, fraction) = if (point != null) {
            // Tapped spot: center on the sensor-normalized point (~10% of the active array).
            Triple(
                active.left + (point.first * active.width()).toInt(),
                active.top + (point.second * active.height()).toInt(),
                controls.afSpotSize.fraction, // Sony-style Spot S/M/L
            )
        } else {
            val f = when (controls.meteringMode) {
                MeteringMode.MATRIX -> return
                MeteringMode.CENTER -> 0.40f
                MeteringMode.SPOT -> 0.12f
            }
            Triple(active.left + active.width() / 2, active.top + active.height() / 2, f)
        }

        val box = meteringRect(active.left, active.top, active.right, active.bottom, cx, cy, fraction)
            ?: return
        val regions = arrayOf(
            MeteringRectangle(box[0], box[1], box[2], box[3], MeteringRectangle.METERING_WEIGHT_MAX),
        )
        if (targets.ae) builder.set(CaptureRequest.CONTROL_AE_REGIONS, regions)
        if (targets.af) builder.set(CaptureRequest.CONTROL_AF_REGIONS, regions)
    }

    fun updateControls(controls: ManualControls) {
        // Confine the field write to the camera handler thread: updateControls is called from both the
        // main thread (ViewModel) and the camera thread (AEB/BURST chain), and the field is read on the
        // camera thread, so doing the mutation here keeps it single-threaded (no lost-update race).
        postToCamera { applyControlsOnCamera(controls, retainedOpticsCommit = false) }
    }

    /**
     * Applies a same-route optics packet and ends any retained smooth-preview boost in the SAME
     * camera-thread request update. A controller replacement starts with boost=false and never calls
     * this seam; the retained session either pays one necessary rebuild to remove pinned FPS keys or
     * keeps the existing zoom/sensor fast path when no boost was active.
     */
    fun commitRetainedOpticsControls(controls: ManualControls) {
        postToCamera { applyControlsOnCamera(controls, retainedOpticsCommit = true) }
    }

    private fun applyControlsOnCamera(
        requested: ManualControls,
        retainedOpticsCommit: Boolean,
    ) {
        val modeIntent = if (pinAutoFps && requested.exposureMode == ExposureMode.PROGRAM) {
            requested.copy(programAppSide = false)
        } else {
            requested
        }
        val normalized = modeIntent.normalizedFor(caps).normalizedForCaptureMode(
            if (pinAutoFps) CaptureMode.VIDEO else CaptureMode.PHOTO,
        )
        if (normalized.wbMode != WbMode.AUTO || normalized.awbLock) {
            pendingCustomWbSample?.let { finishCustomWbSample(it.id, null) }
        }
        val previous = controls
        // An explicit focus-mode change ends the tap-to-focus AUTO hold and resumes the chosen mode.
        if (normalized.focusMode != previous.focusMode) touchAfActive = false
        // AF Lock temporarily wins by pinning AF_MODE_OFF, but it does not release the tap-owned
        // region. Re-arm the one-shot when the lock is lifted so AF_MODE_AUTO actually scans that
        // surviving point instead of remaining INACTIVE at the formerly pinned distance.
        if (tapAfShouldRearmAfterUnlock(
                previousAfLock = previous.afLock,
                nextAfLock = normalized.afLock,
                touchAfActive = touchAfActive,
                meteringPointPresent = meteringPoint != null,
            )
        ) {
            afTriggerPending = true
        }
        val plan = if (retainedOpticsCommit) {
            retainedOpticsApplyPlan(
                previous,
                normalized,
                smoothPreviewBoostActive = smoothPreviewBoost,
                tapResetPending = tapResetPending,
            )
        } else {
            null
        }
        controls = normalized

        if (retainedOpticsCommit) {
            // Clear before rebuilding/fast-submitting: startPreview and the sensor-key derivation
            // both read this field to decide whether the low-light FPS floor remains pinned.
            smoothPreviewBoost = false
            when (plan) {
                RetainedOpticsApplyPlan.NO_OP -> Unit
                RetainedOpticsApplyPlan.ZOOM_FAST_PATH -> submitZoomFastPath(normalized.zoomRatio)
                RetainedOpticsApplyPlan.SENSOR_FAST_PATH -> submitSensorFastPath()
                RetainedOpticsApplyPlan.FULL_REBUILD -> startPreview()
                null -> error("retained optics plan missing")
            }
            return
        }

        if (tapResetPending) {
            startPreview()
            return
        }

        // Sensor fast path (mirrors the zoom fast path): a focus/ISO/shutter drag and the app-side
        // AE loop feed this at up to 25 Hz, and EVERY full rebuild's repeating-request swap gaps
        // this HAL's stream 170-250 ms. Anything broader keeps the full rebuild.
        if (sensorFastPathAdmitted(previous, normalized)) {
            submitSensorFastPath()
        } else {
            startPreview()
        }
    }

    // Camera-thread pacing state for the sensor fast path (see updateControls).
    private var lastSensorSubmitMs = 0L
    private var sensorFastPathQueued = false

    /** Paces sensor-value submits; a queued trailing task always lands the NEWEST packet. */
    private fun submitSensorFastPath() {
        val now = android.os.SystemClock.uptimeMillis()
        val since = now - lastSensorSubmitMs
        if (since >= SENSOR_SUBMIT_MIN_INTERVAL_MS) {
            lastSensorSubmitMs = now
            applySensorFastPath()
        } else if (!sensorFastPathQueued) {
            sensorFastPathQueued = true
            val trailing = Runnable {
                sensorFastPathQueued = false
                lastSensorSubmitMs = android.os.SystemClock.uptimeMillis()
                applySensorFastPath()
            }
            if (!postDelayedToCamera(trailing, SENSOR_SUBMIT_MIN_INTERVAL_MS - since)) {
                sensorFastPathQueued = false
            }
        }
        // else: a trailing task is already queued; it reads the newest [controls] when it fires.
    }

    /** Camera-thread body: mutate only the sensor keys on the cached repeating builder. */
    private fun applySensorFastPath() {
        if (tapResetPending) { startPreview(); return }
        val s = session ?: return
        val b = previewBuilder
        val cb = previewCallback
        if (b == null || cb == null || highSpeedFps > 0) { startPreview(); return }
        runCatching {
            b.applySensorValueControls(
                controls,
                caps,
                pinAutoFps = pinAutoFps || smoothPreviewBoost,
                previewExposureCap = true,
                enforceFrameRate = pinAutoFps,
            )
            // applyFocus above rewrote CONTROL_AF_MODE per the base focus mode; a live tap-AF /
            // AF-lock override must be restored on top, exactly as the full rebuild applies it
            // AFTER applyManualControls — the SAME applier, so the two paths cannot drift
            // (ARCH4-5). This also covers the queued-trailing-task ordering (a tap can land
            // between queueing and firing): re-applying beats the old wholesale refusal, which
            // starved the preview to ~5 fps under app-side AE with a held tap-AF.
            applyAfOverrides(b)
            s.setRepeatingRequest(b.build(), cb, handler)
        }.onFailure {
            if (BuildConfig.DEBUG) Log.w(TAG, "sensor fast path failed, rebuilding: ${it.message}")
            // The cached builder/session can go invalid during a configure transition; re-derive
            // the full repeating request so the new sensor values are not silently lost.
            startPreview()
        }
    }

    /**
     * The ONE application site for the tap-to-focus / AF-lock override keys, shared by the full
     * rebuild (startPreview, after [applyManualControls]) and the sensor fast path (ARCH4-5:
     * both paths must produce identical AF key state, enforced by construction instead of two
     * hand-synced copies). Tap-AF holds AF_MODE_AUTO on the tapped region (regions persist on the
     * cached builder; NO trigger here — re-firing AF_TRIGGER_START would restart the scan the
     * hold is meant to freeze); AF lock pins AF_MODE_OFF at the last AF-resolved distance and
     * WINS when both are set (the pure [afOverrideFor] pins that precedence under test).
     */
    private fun applyAfOverrides(builder: CaptureRequest.Builder) {
        val override = afOverrideFor(
            touchAfUsesAuto = touchAfMayTrigger(
                touchAfActive = touchAfActive,
                maxAfRegions = caps.maxAfRegions,
                focusMode = controls.focusMode,
                afModes = caps.afModes,
            ),
            afLock = controls.afLock,
            focusMode = controls.focusMode,
            supportsManualFocus = caps.supportsManualFocus,
            afOffAdvertised = exactAdvertisedMode(CaptureRequest.CONTROL_AF_MODE_OFF, caps.afModes) != null,
            lastFocusDistance = lastFocusDistance,
        )
        when (override) {
            is AfOverride.TouchAuto ->
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            is AfOverride.LockAt -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, override.focusDistance)
            }
            null -> Unit
        }
    }

    // While a zoom gesture is live, pin the HAL-AE fps floor so the preview doesn't idle at its
    // low-light 10-15 fps rate — at 10 fps ANY zoom reads as jank regardless of how smoothly the
    // ratio is applied (the stock camera keeps ~30 fps and lets ISO carry the difference).
    private var lastTracedResultZoom = -1f
    // Change gate for onZoomResult forwarding (camera-thread confined; NaN = always forward next).
    private var lastForwardedResultZoom = Float.NaN
    private var smoothPreviewBoost = false

    fun setSmoothPreviewBoost(active: Boolean, finalZoom: Float? = null) {
        postToCamera {
            // Land the caller's exact zoom INSIDE the same rebuild that flips the boost. The old
            // rebuild-then-correct order at gesture end submitted the stale mid-gesture wide-aimed
            // ratio first (this field was last written by the throttled wide submit) and then paid
            // a second ~180 ms repeating-request stall for the correction.
            if (finalZoom != null) controls = controls.copy(zoomRatio = finalZoom)
            if (smoothPreviewBoost == active) {
                // Boost state already correct (duplicate gesture edge): still honor the exact zoom
                // without a full rebuild.
                if (finalZoom != null) submitZoomFastPath(finalZoom)
                return@postToCamera
            }
            smoothPreviewBoost = active
            startPreview()
        }
    }

    /** Pins AUTO exposure to the selected fps in video mode, and restores low-light fps in photo. */
    fun setPinAutoFps(enabled: Boolean, opticsGeneration: Long) {
        val intent = PreviewModeIntent(enabled, opticsGeneration)
        if (!modeIntentGate.request(intent)) return
        postToCamera {
            // Drop superseded toggles before changing either the real request key or its diagnostic
            // owner. Both are camera-thread-confined, so a built request can never mix two intents.
            if (!modeIntentGate.isCurrent(intent)) return@postToCamera
            pinAutoFps = enabled
            diagnosticRequestMode = if (enabled) CaptureMode.VIDEO else CaptureMode.PHOTO
            diagnosticOpticsGeneration = opticsGeneration
            val modeIntent = if (enabled && controls.exposureMode == ExposureMode.PROGRAM) {
                controls.copy(programAppSide = false)
            } else {
                controls
            }
            controls = modeIntent.normalizedFor(caps).normalizedForCaptureMode(
                if (enabled) CaptureMode.VIDEO else CaptureMode.PHOTO,
            )
            startPreview()
        }
    }

    /**
     * Sets the tap-to-focus/meter target. [sx],[sy] are SENSOR-normalized (0..1); the caller has
     * already applied the view→sensor rotation. Arms a one-shot AF trigger and rebuilds the preview
     * so the new AE/AF spot region (and AF convergence) takes effect immediately. With AF Lock, the
     * trigger is suppressed and the accepted repeating request updates only the tap-owned AE region.
     */
    internal fun setMeteringPoint(
        sx: Float,
        sy: Float,
        onPreviewSubmitted: (TapFocusSubmissionResult) -> Unit,
    ): Boolean {
        if (closed || !::caps.isInitialized) return false

        // Mutate the point on the same serial queue that builds the request. Besides giving the
        // engine the REAL startPreview result (not merely Handler.post success), this keeps rapid
        // tap/tap and clear/tap sequences ordered instead of letting one failed task roll back a
        // newer point that was written from the UI thread.
        return postToCamera {
            if (!touchAfMayTrigger(
                    touchAfActive = true,
                    maxAfRegions = caps.maxAfRegions,
                    focusMode = controls.focusMode,
                    afModes = caps.afModes,
                )
            ) {
                onPreviewSubmitted(TapFocusSubmissionResult.REJECTED_PREVIOUS_RESTORED)
                return@postToCamera
            }
            val previousPoint = meteringPoint
            val previousTriggerPending = afTriggerPending
            val previousTouchAfActive = touchAfActive
            val previousTapResetPending = tapResetPending
            meteringPoint = sx.coerceIn(0f, 1f) to sy.coerceIn(0f, 1f)
            afTriggerPending = true
            // Hold AF_MODE_AUTO on this spot until a focus-mode change, a replacing tap, or the
            // engine's explicit clearMeteringPoint (reset / optics remap). The UI's 2 s reticle
            // timer is visual-only and does NOT release this hold (AGG4-3).
            touchAfActive = true
            tapResetPending = false
            val submission = submitPreviewRequest()
            val result = if (submission.tapTrigger == TapTriggerSubmission.ACCEPTED &&
                submission.repeatingAccepted
            ) {
                TapFocusSubmissionResult.ACCEPTED
            } else {
                meteringPoint = previousPoint
                afTriggerPending = previousTriggerPending
                touchAfActive = previousTouchAfActive
                tapResetPending = previousTapResetPending
                if (submission.tapTrigger == TapTriggerSubmission.FAILED_UNCERTAIN) {
                    // CANCEL or START may already have changed the AF state. Re-scan the previous
                    // held point (or submit the default regions) once; only that accepted request
                    // makes KEEP_PREVIOUS truthful. A second failure is explicitly uncertain and
                    // the engine retires/clears the owner instead of claiming the old lock.
                    afTriggerPending = previousTriggerPending ||
                        (previousPoint != null && previousTouchAfActive)
                    val rollback = submitPreviewRequest()
                    val restored = rollback.repeatingAccepted &&
                        (previousPoint == null || !previousTouchAfActive ||
                            rollback.tapTrigger == TapTriggerSubmission.ACCEPTED)
                    if (restored) {
                        TapFocusSubmissionResult.REJECTED_PREVIOUS_RESTORED
                    } else {
                        TapFocusSubmissionResult.FAILED_UNCERTAIN
                    }
                } else {
                    TapFocusSubmissionResult.REJECTED_PREVIOUS_RESTORED
                }
            }
            // ACCEPTED proves Camera2 took the required AF transaction (unless AF Lock owns the
            // lens) and the repeating request. Convergence remains asynchronous AF telemetry.
            onPreviewSubmitted(result)
        }
    }

    /** Clears the tap target, restoring the metering-mode regions on the next preview build. */
    fun clearMeteringPoint(rebuildPreview: Boolean = true): Boolean {
        if (closed) return false
        return postToCamera {
            meteringPoint = null
            touchAfActive = false
            afTriggerPending = false
            tapResetPending = true
            if (rebuildPreview) startPreview()
        }
    }

    fun capturePhoto(wantJpeg: Boolean, wantRaw: Boolean, cb: PhotoCallback) {
        val posted = postToCamera {
            // Always surface a result through the callback (even on the no-target/not-ready paths) so a
            // BURST/AEB chain's onDone still fires and the user gets feedback instead of a silent no-op.
            val camera = device ?: return@postToCamera cb.onError(IllegalStateException("Camera not ready"))
            val s = session ?: return@postToCamera cb.onError(IllegalStateException("Camera session not ready"))
            val jpeg = jpegReader?.surface?.takeIf { wantJpeg }
            val raw = rawReader?.surface?.takeIf { wantRaw }
            if (jpeg == null && raw == null) {
                return@postToCamera cb.onError(
                    IllegalStateException("No capture target — enable HEIF, JPEG, or DNG (the session may have fallen back to preview-only)"),
                )
            }
            if (pending != null) {
                return@postToCamera cb.onError(IllegalStateException("Capture already in progress"))
            }

            // Snapshot once: the timeout and the request must describe the same AEB/manual step even
            // if the UI publishes the next immutable control set while this capture is in flight.
            val requestControls = controls
            val watchdogExposureNs = if (!requestControls.autoExposure && caps.supportsManualSensor) {
                requestControls.clampedEffectiveExposureNs(
                    minNs = caps.exposureTimeRange?.lower,
                    maxNs = caps.exposureTimeRange?.upper,
                )
            } else {
                null
            }
            val watchdogTimeoutMs = captureWatchdogTimeoutMs(watchdogExposureNs)
            val newPending = Pending(jpeg != null, raw != null, cb)
            pending = newPending
            // A dead HAL must not occupy the pending slot forever, but a legitimate multi-second
            // manual/AEB exposure needs its full requested duration before the delivery budget begins.
            val watchdog = Runnable {
                val p = pending
                if (p === newPending && !p.done) {
                    synchronized(p) {
                        if (p.done) return@Runnable
                        p.done = true
                        runCatching { p.jpeg?.close() }
                        runCatching { p.raw?.close() }
                    }
                    pending = null
                    p.cb.onError(IllegalStateException("Capture timed out — the camera delivered no image"))
                }
            }
            if (!postDelayedToCamera(watchdog, watchdogTimeoutMs)) {
                if (pending === newPending) pending = null
                synchronized(newPending) {
                    if (!newPending.done) {
                        newPending.done = true
                        runCatching { newPending.jpeg?.close() }
                        runCatching { newPending.raw?.close() }
                        runCatching {
                            newPending.cb.onError(IllegalStateException("Camera closed before capture watchdog could start"))
                        }
                    }
                }
                return@postToCamera
            }

            // The device can disconnect between the null-checks above and the capture below (the same
            // async-teardown window startPreview guards): createCaptureRequest/capture then throw
            // CameraAccess/IllegalState on the camera thread — uncaught that kills the process, and
            // the pending slot set above would wedge every later shutter press with "Capture already
            // in progress". Surface it through the callback and clear the slot instead.
            runCatching {
                val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    jpeg?.let { addTarget(it) }
                    raw?.let { addTarget(it) }
                    applyManualControls(
                        requestControls,
                        caps,
                        pinAutoFps = pinAutoFps,
                        enforceFrameRate = pinAutoFps,
                    )
                    // Keep stills consistent with the session's pipeline: with the log session active the
                    // HAL processes everything scene-referred, so an unset key mid-session is undefined.
                    applyVendorLog()
                    applyTeleconverterHints()
                    applyMetering(this, requestControls)
                    // We rotate pixels ourselves (HEIF) / tag DNG + hi-res-JPEG orientation; keep
                    // the HAL out of rotation entirely.
                    set(CaptureRequest.JPEG_ORIENTATION, 0)
                    // Standard ultra-high-res path only: the still must OPT IN to the full-sensor
                    // readout or the HAL serves the binned image into the hi-res buffer. The
                    // vendor path needs nothing (the reader size itself selects remosaic), and the
                    // REPEATING request stays default pixel mode — only this still crosses over.
                    if (hiResReaderActive && caps.hiResUsesMaxResolutionMode) {
                        set(
                            CaptureRequest.SENSOR_PIXEL_MODE,
                            CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION,
                        )
                    }
                    // Zero-shutter-lag: with the HAL AE owning exposure the HAL may serve the still
                    // from its ring buffer (capture start ≈ tap instead of a full pipeline drain —
                    // measured ~0.8 s in low light). Ignored by spec when AE is OFF (manual /
                    // app-side priority modes need the requested values on the actual frame).
                    if (requestControls.autoExposure) runCatching { set(CaptureRequest.CONTROL_ENABLE_ZSL, true) }
                }
                s.capture(req.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureStarted(
                        session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long,
                    ) {
                        // The callback belongs to the request that created [newPending], not whatever
                        // happens to occupy the reusable pending slot when a late callback arrives.
                        // Bind its sensor timestamp before accepting images; an old image can arrive
                        // after the watchdog has admitted a newer shot on the same ImageReader.
                        if (!captureTokenIsCurrent(pending, newPending)) return
                        synchronized(newPending) {
                            if (newPending.done) return
                            newPending.sensorTimestampNs = timestamp
                            newPending.takenAtMs = System.currentTimeMillis()
                            newPending.jpeg?.takeIf { !timestampBelongsToCapture(timestamp, it.timestamp) }?.let {
                                it.close()
                                newPending.jpeg = null
                            }
                            newPending.raw?.takeIf { !timestampBelongsToCapture(timestamp, it.timestamp) }?.let {
                                it.close()
                                newPending.raw = null
                            }
                        }
                        // TRUE shutter moment (sensor exposure start): queue→started is the user-felt
                        // shutter lag; started→completed→image is HAL processing + readout.
                        if (BuildConfig.DEBUG) {
                            Log.i(TAG, "ShutterLag: started +${(System.nanoTime() - newPending.queuedAtNs) / 1_000_000} ms")
                        }
                    }

                    override fun onCaptureCompleted(
                        session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult,
                    ) {
                        if (!captureTokenIsCurrent(pending, newPending)) return
                        val resultTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                        synchronized(newPending) {
                            if (newPending.done) return
                            val expected = newPending.sensorTimestampNs
                            if (expected != null && resultTimestamp != null && expected != resultTimestamp) return
                            if (expected == null) newPending.sensorTimestampNs = resultTimestamp
                            newPending.result = result
                        }
                        if (BuildConfig.DEBUG) {
                            Log.i(TAG, "ShutterLag: completed +${(System.nanoTime() - newPending.queuedAtNs) / 1_000_000} ms")
                        }
                        tryComplete(newPending)
                    }
                    override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
                        if (captureTokenIsCurrent(pending, newPending)) {
                            // Close any already-acquired images so the ImageReader (maxImages=2) isn't
                            // starved, and mark done so a late-arriving partial can't re-enter tryComplete.
                            synchronized(newPending) {
                                newPending.done = true
                                runCatching { newPending.jpeg?.close() }
                                runCatching { newPending.raw?.close() }
                            }
                            pending = null
                            newPending.cb.onError(IllegalStateException("Capture failed: ${failure.reason}"))
                        }
                    }
                }, handler)
            }.onFailure { t ->
                if (captureTokenIsCurrent(pending, newPending)) {
                    pending = null // nothing acquired yet — the readers only deliver after a queued capture
                    newPending.done = true
                    cb.onError(t)
                }
            }
        }
        // The controller is mid-teardown — a session-key reopen (Auto HDR / in-sensor zoom / lens / fps)
        // quit the camera thread. Route the failure through the callback so a BURST/AEB chain's onDone
        // still fires and the user gets feedback, instead of the post throwing a dead-thread exception.
        if (!posted) cb.onError(IllegalStateException("Camera is reconfiguring — try the shot again"))
    }

    private fun onImage(reader: ImageReader, isRaw: Boolean) {
        val image = runCatching { reader.acquireNextImage() }.getOrNull() ?: return
        val p = pending
        if (p == null) { image.close(); return }
        synchronized(p) {
            val expected = p.sensorTimestampNs
            if (p.done || (expected != null && !timestampBelongsToCapture(expected, image.timestamp))) {
                image.close()
                return
            }
            if (isRaw && p.wantRaw && p.raw == null) p.raw = image
            else if (!isRaw && p.wantJpeg && p.jpeg == null) p.jpeg = image
            else { image.close(); return }
        }
        tryComplete(p)
    }

    private fun tryComplete(p: Pending) {
        synchronized(p) {
            if (p.done) return
            val haveResult = p.result != null
            val haveJpeg = !p.wantJpeg || p.jpeg != null
            val haveRaw = !p.wantRaw || p.raw != null
            if (!(haveResult && haveJpeg && haveRaw)) return
            val expected = p.sensorTimestampNs
            if (expected != null) {
                if (p.jpeg?.let { !timestampBelongsToCapture(expected, it.timestamp) } == true) {
                    p.jpeg?.close()
                    p.jpeg = null
                    return
                }
                if (p.raw?.let { !timestampBelongsToCapture(expected, it.timestamp) } == true) {
                    p.raw?.close()
                    p.raw = null
                    return
                }
            }
            p.done = true
        }
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "ShutterLag: images+result +${(System.nanoTime() - p.queuedAtNs) / 1_000_000} ms")
        }
        val chars = rawChars
        try {
            if (chars != null) p.cb.onPhoto(p.jpeg, p.raw, p.result!!, chars, p.takenAtMs)
            else p.cb.onError(IllegalStateException("Missing camera characteristics"))
        } catch (t: Throwable) {
            p.cb.onError(t)
        } finally {
            p.jpeg?.close()
            p.raw?.close()
            pending = null
        }
    }

    fun close() {
        // Idempotent AND atomic: a second close() must not run — the looper is already quitting, so
        // posting the teardown again would throw on OPPO's LegacyMessageQueue. setCameraOverride() →
        // openCamera() both call close() on the same controller, AND close() is reachable from three
        // threads (pause() on main, reopens on setupExecutor, openCamera() from the GL continuation),
        // so a plain `if (closed) return; closed = true` check-then-act can double-run. CAS decides.
        if (!closeStarted.compareAndSet(false, true)) return
        closed = true
        val teardown = Runnable {
            pendingCustomWbSample?.let { finishCustomWbSample(it.id, null) }
            pending?.let { p ->
                synchronized(p) {
                    if (!p.done) {
                        p.done = true
                        runCatching { p.jpeg?.close() }
                        runCatching { p.raw?.close() }
                        runCatching { p.cb.onError(IllegalStateException("Camera closed during capture")) }
                    }
                }
            }
            pending = null
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { jpegReader?.close() }
            runCatching { rawReader?.close() }
            session = null
            device = null
            jpegReader = null
            rawReader = null
        }
        // Same dead-thread quirk postToCamera guards against: if this instance somehow closes after
        // its looper died (OS kill ordering), the raw post throws instead of returning false.
        var teardownPosted = false
        callbackDispatchGate.beginClose {
            teardownPosted = runCatching { handler.post(teardown) }.getOrDefault(false)
            // Quit inside the same gate that admits callback posts. A framework callback is either
            // already queued ahead of teardown or observes closing and uses the inline fallback;
            // it can never probe the dead Handler in between teardown admission and quitSafely.
            bg.quitSafely()
        }
        if (!teardownPosted) teardown.run()
        // Teardown was posted before quitSafely, so the queued cleanup runs first and then the
        // HandlerThread exits. (Previously the thread leaked once per controller / override switch.)
        // Block (bounded) until that teardown actually runs and the HAL device is released, so a
        // subsequent open of the SAME physical camera on a session-key reopen (Auto HDR / in-sensor
        // zoom / lens / high-speed fps) doesn't race a half-closed device — that race surfaces as
        // Camera3-Device "Broken pipe -32" → ERROR_CAMERA_DEVICE and a dead session that needs an app
        // relaunch to recover. Never join from the camera thread itself (would deadlock).
        if (Thread.currentThread() !== bg) runCatching { bg.join(CLOSE_JOIN_TIMEOUT_MS) }
    }

    private class Pending(val wantJpeg: Boolean, val wantRaw: Boolean, val cb: PhotoCallback) {
        var jpeg: Image? = null
        var raw: Image? = null
        var result: TotalCaptureResult? = null
        var sensorTimestampNs: Long? = null
        var takenAtMs: Long = System.currentTimeMillis()
        var done = false
        // Shutter-lag instrumentation (DEBUG logs only): capture-queue time on the camera thread.
        val queuedAtNs: Long = System.nanoTime()
    }

    private data class PendingCustomWbSample(
        val id: Long,
        val tag: CustomWbSampleTag,
        val timeout: Runnable,
        val onResult: (android.hardware.camera2.params.RggbChannelVector?) -> Unit,
    )

    private companion object {
        const val TAG = "CameraController"
        val controllerSequence = java.util.concurrent.atomic.AtomicLong(0)
        val previewRequestSequence = java.util.concurrent.atomic.AtomicLong(0)
        const val CUSTOM_WB_SAMPLE_TIMEOUT_MS = 2_000L
        // Sensor fast-path pacing: every repeating-request swap gaps this HAL ~180 ms (measured),
        // so high-churn sensor submits hold the same >=200 ms floor as the zoom fast path.
        const val SENSOR_SUBMIT_MIN_INTERVAL_MS = 200L
        // Highest fallback index (attempt 3 = preview-only). Beyond this the session can't be built.
        const val MAX_CONFIG_ATTEMPT = 3
        // TELE tries the same four stream plans once with vendor operation mode 0x80b4 and once
        // with SESSION_REGULAR, so an unsupported vendor mode cannot kill an otherwise-valid view.
        const val MAX_TELE_CONFIG_ATTEMPT = 7
        // Max time close() waits for the camera thread to release the HAL device before a reopen.
        // Device close is normally well under this; the cap keeps a wedged close from hanging the UI.
        const val CLOSE_JOIN_TIMEOUT_MS = 1500L
        const val OPLUS_CAMERA_MODE_TELEPHOTO_HASSELBLAD: Byte = 40
    }
}

internal data class PreviewModeIntent(
    val pinAutoFps: Boolean,
    val opticsGeneration: Long,
)

/** Atomic last-intent gate; equal duplicate requests retain identity so their queued owner survives. */
internal class PreviewModeIntentGate(initial: PreviewModeIntent) {
    private val requested = java.util.concurrent.atomic.AtomicReference(initial)

    fun reset(intent: PreviewModeIntent) {
        requested.set(intent)
    }

    fun request(intent: PreviewModeIntent): Boolean {
        while (true) {
            val previous = requested.get()
            if (previous == intent) return false
            if (requested.compareAndSet(previous, intent)) return true
        }
    }

    fun isCurrent(intent: PreviewModeIntent): Boolean = requested.get() === intent
}

/** Identity guard for callbacks that can outlive and race reuse of the single pending-shot slot. */
internal fun captureTokenIsCurrent(current: Any?, expected: Any): Boolean = current === expected

/** Camera2 images/results for one request must carry the sensor timestamp reported at shutter start. */
internal fun timestampBelongsToCapture(expectedTimestampNs: Long, actualTimestampNs: Long): Boolean =
    expectedTimestampNs == actualTimestampNs

internal enum class SessionStartDelivery { READY, ERROR }

/** Configured is not Ready until the initial repeating request was accepted. */
internal fun sessionStartDelivery(repeatingRequestAccepted: Boolean): SessionStartDelivery =
    if (repeatingRequestAccepted) SessionStartDelivery.READY else SessionStartDelivery.ERROR

internal data class CustomWbSampleTag(val id: Long)

/** A Custom-WB result must be fresh, unlocked AUTO, and converged for the exact tagged request. */
internal fun customWbResultBelongsToRequest(
    expectedTag: CustomWbSampleTag,
    resultTag: Any?,
    awbMode: Int?,
    awbLocked: Boolean?,
    awbState: Int?,
    gainsAvailable: Boolean,
): Boolean = resultTag === expectedTag &&
    awbMode == CameraMetadata.CONTROL_AWB_MODE_AUTO &&
    awbLocked != true &&
    awbState == CaptureResult.CONTROL_AWB_STATE_CONVERGED &&
    gainsAvailable

/** Accepted-output truth comes from created readers, not the fallback plan that requested them. */
internal fun acceptedPhotoSessionOutputs(
    processedReaderPresent: Boolean,
    rawReaderPresent: Boolean,
    hiResReaderPresent: Boolean = false,
): PhotoSessionOutputs = PhotoSessionOutputs(
    processed = processedReaderPresent,
    raw = rawReaderPresent,
    // Session truth, not intent: hiRes only when the processed reader that SURVIVED configure is
    // the full-sensor one (the ladder's later attempts rebuild it at the ordinary size).
    hiRes = processedReaderPresent && hiResReaderPresent,
)

/** Rejects AF telemetry from an outgoing repeating request and seeds each new request once. */
internal fun shouldPublishAfState(
    requestGeneration: Long,
    latestRequestGeneration: Long,
    firstResultForRequest: Boolean,
    requestAfTrigger: Int?,
    afState: Int,
    lastReportedAfState: Int,
): Boolean = afTelemetryBelongsToRepeatingRequest(requestAfTrigger) &&
    requestGeneration == latestRequestGeneration &&
    (firstResultForRequest || afState != lastReportedAfState)

/** CANCEL/START are transitional control captures; only the IDLE repeating stream owns UI AF state. */
internal fun afTelemetryBelongsToRepeatingRequest(requestAfTrigger: Int?): Boolean =
    requestAfTrigger == null || requestAfTrigger == CameraMetadata.CONTROL_AF_TRIGGER_IDLE

/** A held tap resumes its one-shot AUTO scan when AF Lock gives lens ownership back. */
internal fun tapAfShouldRearmAfterUnlock(
    previousAfLock: Boolean,
    nextAfLock: Boolean,
    touchAfActive: Boolean,
    meteringPointPresent: Boolean,
): Boolean = previousAfLock && !nextAfLock && touchAfActive && meteringPointPresent

/** AF Lock retains lens ownership while the tap-owned AE region still enters the repeating request. */
internal fun tapAfTriggerRequired(
    tapPointRequested: Boolean,
    touchAfUsesAuto: Boolean,
    afLock: Boolean,
): Boolean = tapPointRequested && touchAfUsesAuto && !afLock

/** What the session fallback ladder enables at a given attempt — see [CameraController]'s ladder doc. */
internal data class SessionAttemptPlan(
    val useHlg: Boolean,
    val useJpeg: Boolean,
    val useRaw: Boolean,
    val useVendorOperationMode: Boolean = false,
    val useHiResStill: Boolean = false,
)

/**
 * Pure core of the fallback ladder (full → drop RAW → drop HLG → preview-only) so the
 * HAL-crash-critical ordering is unit-testable off-device. TELE tries both operation modes with
 * capture streams before either preview-only last resort. [standalone] is the
 * `selection.physicalId == null` RAW gate (RAW via physical routing SIGSEGVs this QTI HAL).
 * [wantHiRes] rides ONLY attempt 0 (TELE: the first vendor-mode attempt) and is the FIRST thing
 * dropped: attempt 1 re-tries the ordinary streams before the ladder degrades anything else.
 */
internal fun sessionAttemptPlan(
    attempt: Int,
    wantHlg: Boolean,
    supportsRaw: Boolean,
    standalone: Boolean,
    logicalMultiCamera: Boolean = false,
    teleconverterMode: Boolean = false,
    wantHiRes: Boolean = false,
): SessionAttemptPlan {
    val (streamAttempt, vendorMode) = if (teleconverterMode) {
        when (attempt) {
            0 -> 0 to true
            1 -> 1 to true
            2 -> 2 to true
            3 -> 0 to false
            4 -> 1 to false
            5 -> 2 to false
            6 -> 3 to true
            else -> 3 to false
        }
    } else {
        attempt to false
    }
    val hiRes = wantHiRes && attempt == 0
    return SessionAttemptPlan(
    useHlg = wantHlg && streamAttempt < 2,
    useJpeg = streamAttempt < 3,
    // RAW is STANDALONE-camera-only on this HAL, in BOTH failure modes: routed through a physical
    // sub-camera it rejects the stream ("DataSpace override not allowed"), and on the plain
    // LOGICAL camera a still with the RAW target errors the whole camera device ~5 s after the
    // shot (device-observed CAMERA_ERROR(3), 2026-07-14) — no image ever arrives. DNG therefore
    // exists only in TELE mode (standalone 3×) and on any explicit standalone selection.
    // Hi-res additionally FORCES RAW off on its one attempt: a 200MP blob + RAW in one session is
    // exactly the over-demanding stream combo this HAL punishes, and the maximum-resolution map
    // need not carry RAW at all.
    useRaw = streamAttempt < 1 && supportsRaw && standalone && !logicalMultiCamera && !hiRes,
    useVendorOperationMode = vendorMode,
    useHiResStill = hiRes,
    )
}

/**
 * Pure ROI math behind tap/center/spot metering, extracted (like [sessionAttemptPlan]) so the
 * edge clamp and the fraction ceiling are unit-testable. Returns `[left, top, width, height]`
 * fully inside the active array, or null for a degenerate array. The fraction is defensively
 * capped: `coerceIn(min, max)` throws once the rect reaches the array size (min > max), so a
 * future spot-size/metering fraction at or beyond ~1.0 must clamp instead of crashing every
 * tap-to-focus.
 */
internal fun meteringRect(
    activeLeft: Int,
    activeTop: Int,
    activeRight: Int,
    activeBottom: Int,
    cx: Int,
    cy: Int,
    fraction: Float,
): IntArray? {
    val aw = activeRight - activeLeft
    val ah = activeBottom - activeTop
    if (aw <= 0 || ah <= 0) return null
    val f = fraction.coerceIn(0.01f, 0.9f)
    val rw = (aw * f).toInt().coerceAtLeast(1)
    val rh = (ah * f).toInt().coerceAtLeast(1)
    // Clamp so the rectangle stays fully inside the active array even near an edge.
    val left = (cx - rw / 2).coerceIn(activeLeft, activeRight - rw)
    val top = (cy - rh / 2).coerceIn(activeTop, activeBottom - rh)
    return intArrayOf(left, top, rw, rh)
}

/**
 * The AF-override key state both request-build paths must apply identically (ARCH4-5): the full
 * rebuild in startPreview and the sensor fast path share one applier driven by this pure decision,
 * so the tap-AF/AF-lock behavior cannot drift between them again (the c928eac/f61594a class).
 */
internal sealed interface AfOverride {
    /** Tap-AF hold: AF_MODE_AUTO on the tapped region; NO trigger (never restart the held scan). */
    data object TouchAuto : AfOverride

    /** AF lock: AF_MODE_OFF pinned at the last AF-resolved [focusDistance]. */
    data class LockAt(val focusDistance: Float) : AfOverride
}

/**
 * Resolves which AF override (if any) applies. PRECEDENCE: when a tap-AF hold and AF lock are BOTH
 * active, the LOCK wins — both write CONTROL_AF_MODE, and the historical sequential apply (touch
 * first, lock second) always left the lock's frozen distance as the final key state; this function
 * pins that outcome under host test instead of relying on statement order.
 */
internal fun afOverrideFor(
    touchAfUsesAuto: Boolean,
    afLock: Boolean,
    focusMode: FocusMode,
    supportsManualFocus: Boolean,
    afOffAdvertised: Boolean,
    lastFocusDistance: Float,
): AfOverride? = when {
    afLock && focusMode != FocusMode.MANUAL && supportsManualFocus && afOffAdvertised ->
        AfOverride.LockAt(lastFocusDistance)
    touchAfUsesAuto -> AfOverride.TouchAuto
    else -> null
}
