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
    // Camera2 posts its callbacks through this executor; guard the post so a framework callback that
    // lands AFTER close() quit the looper is handled instead of thrown — OPPO's LegacyMessageQueue
    // raises IllegalStateException "sending message to a dead thread" rather than returning false.
    // A failed post must RUN the callback inline (binder thread), not drop it: a late onOpened is
    // the ONLY code that closes a device the OS delivered after close() — dropping it leaks the
    // CameraDevice handle until process death (repeated keyguard cold-opens can exhaust the
    // per-process camera budget). Every StateCallback path checks `closed` first, so the inline run
    // just closes the leaked handle and returns.
    private val executor = Executor { cmd ->
        val posted = runCatching { handler.post(cmd) }.getOrDefault(false)
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
        return runCatching { handler.post(block) }.getOrDefault(false)
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
    // Video preview/recording must honor the selected fps even under auto exposure. Photo preview
    // leaves this false so AE can lower its frame rate for a brighter low-light view.
    @Volatile private var pinAutoFps = false
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
        // Dual-open camera switching: open the DEVICE now (the outgoing camera keeps streaming
        // through its ~120 ms) but hold the session until [startDeferredSession] — the preview
        // surface belongs to the old session until it closes. onDeviceOpened fires from the camera
        // thread once the device handle is live.
        deferSession: Boolean = false,
        onDeviceOpened: (() -> Unit)? = null,
        onReady: Ready,
        onError: ErrorCb,
    ) {
        this.selection = selection
        this.caps = caps
        this.glSurface = glInputSurface
        this.controls = controls.normalizedFor(caps)
        this.tenBitHlg = tenBitHlg
        this.highSpeedFps = highSpeedFps
        this.vendorLogMode = vendorLogMode
        this.videoStabHalMode = videoStabHalMode
        this.teleconverterMode = teleconverterMode
        this.pinAutoFps = pinAutoFps
        this.rawChars = runCatching {
            manager.getCameraCharacteristics(selection.physicalId ?: selection.logicalId)
        }.getOrNull()

        // openCamera can throw SYNCHRONOUSLY — CameraAccessException CAMERA_DISABLED when the app is
        // opening from a background proc state (e.g. relaunched behind the keyguard / while the screen
        // just woke), or SecurityException. Guard it so that lifecycle race surfaces as an onError
        // status instead of crashing the app; the next foreground resume() reopens cleanly.
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
                        runCatching { configureSession(onReady, onError) }.onFailure { onError.onError(it) }
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
            runCatching { configureSession(ready, err) }.onFailure { err.onError(it) }
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
        val s = session ?: return
        val b = previewBuilder
        val cb = previewCallback
        if (b == null || cb == null || highSpeedFps > 0) { startPreview(); return }
        if (BuildConfig.DEBUG) Log.i(TAG, "ZoomTrace: submit=$ratio t=${android.os.SystemClock.uptimeMillis()}")
        runCatching {
            caps.zoomRatioRange?.let { b.set(CaptureRequest.CONTROL_ZOOM_RATIO, ratio.coerceIn(it.lower, it.upper)) }
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
            val size = if (useYuv) caps.largestYuvSize else caps.largestJpegSize
            size?.let {
                val reader = ImageReader.newInstance(
                    it.width, it.height,
                    if (useYuv) ImageFormat.YUV_420_888 else ImageFormat.JPEG,
                    2,
                )
                reader.setOnImageAvailableListener({ r -> onImage(r, isRaw = false) }, handler)
                jpegReader = reader
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
                    if (BuildConfig.DEBUG) Log.i(TAG, "Session configured (fallback=$attempt, hlg=$useHlg, jpeg=$useJpeg, raw=$useRaw, vendorLog=$vendorLogMode)")
                    when (sessionStartDelivery(startPreview())) {
                        SessionStartDelivery.READY -> onReady.onReady(
                            acceptedPhotoSessionOutputs(
                                processedReaderPresent = jpegReader != null,
                                rawReaderPresent = rawReader != null,
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

    /**
     * (Re)issues the repeating preview request. When [afTriggerPending] is set (a fresh tap-to-focus
     * point) and AF is running (non-MANUAL focus), it first fires ONE triggered capture identical to
     * the repeating request but carrying CONTROL_AF_TRIGGER_START, so the AF engine converges on the
     * tapped region; the trigger is then cleared (IDLE) and the repeating request continues to hold
     * that result. All calls guard nulls so a torn-down session is a no-op.
     */
    private fun startPreview(): Boolean {
        if (closed) return false
        // In high-speed mode the session is a constrained high-speed one; its repeating request must
        // be a burst list, so route there instead of the regular single-request path below.
        if (highSpeedFps > 0) return startHighSpeedPreview()
        val camera = device ?: return false
        val preview = glSurface ?: return false
        val s = session ?: return false
        val touchAfUsesAuto = touchAfMayTrigger(
            touchAfActive = touchAfActive,
            maxAfRegions = caps.maxAfRegions,
            focusMode = controls.focusMode,
            afModes = caps.afModes,
        )
        // The device can be disconnected asynchronously (app backgrounded, another client, HAL) between
        // session config and here; createCaptureRequest/setRepeatingRequest then throw CameraAccess/
        // IllegalState. Guard the whole build+submit so a torn-down session degrades to "no preview
        // this cycle" instead of crashing the camera thread.
        return runCatching {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview)
                applyManualControls(controls, caps, pinAutoFps || smoothPreviewBoost, previewExposureCap = true)
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
                        if (afState != null && afState != lastReportedAfState) {
                            lastReportedAfState = afState
                            onAfState?.invoke(afState)
                        }
                    }
                    // Diagnostic: log what 3A is actually doing (throttled ~1/sec) so we can tell
                    // whether AE/AF are converging on the standalone tele or effectively inert.
                    // Debug builds only — release users don't need per-second camera telemetry
                    // in logcat (minor capability disclosure + log spam; security review).
                    if (BuildConfig.DEBUG && threeAFrame % 30 == 0) {
                        val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                        val af = result.get(CaptureResult.CONTROL_AF_STATE)
                        val afMode = result.get(CaptureResult.CONTROL_AF_MODE)
                        val ois = result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)
                        val vstab = result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)
                        val effectiveZoom = controls.zoomRatio.coerceAtLeast(1f) *
                            if (teleconverterMode) TELECONVERTER_MAGNIFICATION else 1f
                        Log.i(TAG, "3A: aeState=$ae afState=$af afMode=$afMode iso=${result.get(CaptureResult.SENSOR_SENSITIVITY)} expNs=${result.get(CaptureResult.SENSOR_EXPOSURE_TIME)} lens=$lastFocusDistance ois=$ois vstab=$vstab (req=$videoStabHalMode tele=$teleconverterMode effZoom=$effectiveZoom)")
                    }
                }
            }
            // Tap-to-focus one-shot: CANCEL any in-progress AF, then START a fresh scan on the new
            // region, then fall through to the repeating request (trigger IDLE) so the converged
            // focus is held. Cancel-then-start is more reliable than a bare START when the AF engine
            // is mid-scan (common in CONTINUOUS mode).
            if (afTriggerPending && touchAfUsesAuto) {
                if (BuildConfig.DEBUG) Log.i(TAG, "Touch AF: scanning region $meteringPoint")
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
                runCatching { s.capture(builder.build(), callback, handler) }
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                runCatching { s.capture(builder.build(), callback, handler) }
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            }
            afTriggerPending = false
            previewBuilder = builder
            previewCallback = callback
            s.setRepeatingRequest(builder.build(), callback, handler)
            true
        }.getOrElse {
            if (BuildConfig.DEBUG) Log.w(TAG, "startPreview skipped: ${it.message}")
            false
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
            if (!handler.postDelayed(timeout, CUSTOM_WB_SAMPLE_TIMEOUT_MS)) {
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
        postToCamera {
            val normalized = controls.normalizedFor(caps)
            if (normalized.wbMode != WbMode.AUTO || normalized.awbLock) {
                pendingCustomWbSample?.let { finishCustomWbSample(it.id, null) }
            }
            // An explicit focus-mode change ends the tap-to-focus AUTO hold and resumes the chosen mode.
            if (normalized.focusMode != this.controls.focusMode) touchAfActive = false
            val previous = this.controls
            this.controls = normalized
            // Sensor fast path (mirrors the zoom fast path): a focus/ISO/shutter drag and the
            // app-side AE loop feed this at up to 25 Hz, and EVERY full rebuild's repeating-request
            // swap gaps this HAL's stream 170-250 ms (measured) — a 2 s ruler drag was a ~5 fps
            // viewfinder exactly while judging critical focus at 300 mm. When the delta touches
            // ONLY the sensor scalars, mutate the cached builder via the SAME derivation the full
            // build uses, paced to >=200 ms with a trailing exact landing; a live tap-focus/AF-lock
            // override is RE-APPLIED onto the builder afterward (applyAfOverrides) so it can ride
            // the fast path too — a wholesale refusal starved the preview back to ~5 fps whenever
            // the app-side AE loop ran with a held tap-AF. Anything else keeps the full rebuild.
            if (sensorFastPathAdmitted(previous, normalized)) {
                submitSensorFastPath()
            } else {
                startPreview()
            }
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
            handler.postDelayed({
                sensorFastPathQueued = false
                lastSensorSubmitMs = android.os.SystemClock.uptimeMillis()
                applySensorFastPath()
            }, SENSOR_SUBMIT_MIN_INTERVAL_MS - since)
        }
        // else: a trailing task is already queued; it reads the newest [controls] when it fires.
    }

    /** Camera-thread body: mutate only the sensor keys on the cached repeating builder. */
    private fun applySensorFastPath() {
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
    fun setPinAutoFps(enabled: Boolean) {
        if (pinAutoFps == enabled) return
        pinAutoFps = enabled
        postToCamera { startPreview() }
    }

    /**
     * Sets the tap-to-focus/meter target. [sx],[sy] are SENSOR-normalized (0..1); the caller has
     * already applied the view→sensor rotation. Arms a one-shot AF trigger and rebuilds the preview
     * so the new AE/AF spot region (and AF convergence) takes effect immediately.
     */
    fun setMeteringPoint(sx: Float, sy: Float) {
        meteringPoint = sx.coerceIn(0f, 1f) to sy.coerceIn(0f, 1f)
        afTriggerPending = true
        // Hold AF_MODE_AUTO on this spot until a focus-mode change, a replacing tap, or the
        // engine's explicit clearMeteringPoint (reset / optics remap). The UI's 2 s reticle timer
        // is visual-only and does NOT release this hold (AGG4-3).
        touchAfActive = true
        postToCamera { startPreview() }
    }

    /** Clears the tap target, restoring the metering-mode regions on the next preview build. */
    fun clearMeteringPoint() {
        meteringPoint = null
        touchAfActive = false
        afTriggerPending = false
        postToCamera { startPreview() }
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
            handler.postDelayed({
                val p = pending
                if (p === newPending && !p.done) {
                    synchronized(p) {
                        if (p.done) return@postDelayed
                        p.done = true
                        runCatching { p.jpeg?.close() }
                        runCatching { p.raw?.close() }
                    }
                    pending = null
                    p.cb.onError(IllegalStateException("Capture timed out — the camera delivered no image"))
                }
            }, watchdogTimeoutMs)

            // The device can disconnect between the null-checks above and the capture below (the same
            // async-teardown window startPreview guards): createCaptureRequest/capture then throw
            // CameraAccess/IllegalState on the camera thread — uncaught that kills the process, and
            // the pending slot set above would wedge every later shutter press with "Capture already
            // in progress". Surface it through the callback and clear the slot instead.
            runCatching {
                val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    jpeg?.let { addTarget(it) }
                    raw?.let { addTarget(it) }
                    applyManualControls(requestControls, caps, pinAutoFps)
                    // Keep stills consistent with the session's pipeline: with the log session active the
                    // HAL processes everything scene-referred, so an unset key mid-session is undefined.
                    applyVendorLog()
                    applyTeleconverterHints()
                    applyMetering(this, requestControls)
                    // We rotate pixels ourselves (HEIF) / tag DNG orientation; keep JPEG upright.
                    set(CaptureRequest.JPEG_ORIENTATION, 0)
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
        val teardownPosted = runCatching { handler.post(teardown) }.getOrDefault(false)
        if (!teardownPosted) teardown.run()
        // Quit AFTER posting cleanup so the queued teardown runs first, then the thread exits.
        // (Previously the "camera" HandlerThread leaked once per controller / override switch.)
        bg.quitSafely()
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
): PhotoSessionOutputs = PhotoSessionOutputs(
    processed = processedReaderPresent,
    raw = rawReaderPresent,
)

/** What the session fallback ladder enables at a given attempt — see [CameraController]'s ladder doc. */
internal data class SessionAttemptPlan(
    val useHlg: Boolean,
    val useJpeg: Boolean,
    val useRaw: Boolean,
    val useVendorOperationMode: Boolean = false,
)

/**
 * Pure core of the fallback ladder (full → drop RAW → drop HLG → preview-only) so the
 * HAL-crash-critical ordering is unit-testable off-device. TELE tries both operation modes with
 * capture streams before either preview-only last resort. [standalone] is the
 * `selection.physicalId == null` RAW gate (RAW via physical routing SIGSEGVs this QTI HAL).
 */
internal fun sessionAttemptPlan(
    attempt: Int,
    wantHlg: Boolean,
    supportsRaw: Boolean,
    standalone: Boolean,
    logicalMultiCamera: Boolean = false,
    teleconverterMode: Boolean = false,
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
    return SessionAttemptPlan(
    useHlg = wantHlg && streamAttempt < 2,
    useJpeg = streamAttempt < 3,
    // RAW is STANDALONE-camera-only on this HAL, in BOTH failure modes: routed through a physical
    // sub-camera it rejects the stream ("DataSpace override not allowed"), and on the plain
    // LOGICAL camera a still with the RAW target errors the whole camera device ~5 s after the
    // shot (device-observed CAMERA_ERROR(3), 2026-07-14) — no image ever arrives. DNG therefore
    // exists only in TELE mode (standalone 3×) and on any explicit standalone selection.
    useRaw = streamAttempt < 1 && supportsRaw && standalone && !logicalMultiCamera,
    useVendorOperationMode = vendorMode,
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
