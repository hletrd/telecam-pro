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
import java.util.concurrent.Executor

/**
 * Camera2 session for the tele lens. The GL input surface receives the repeating preview stream
 * (optionally 10-bit HLG for video); still capture targets a JPEG and a RAW ImageReader. When the
 * tele is a physical sub-camera, every output is routed to it via setPhysicalCameraId().
 *
 * Camera callbacks run on a dedicated HandlerThread. Photo encoding happens inside [PhotoCallback]
 * (synchronously) before the Images are closed.
 */
class CameraController(context: Context) {

    fun interface Ready { fun onReady() }
    fun interface ErrorCb { fun onError(t: Throwable) }

    interface PhotoCallback {
        /** Images are valid only for the duration of this call. rawChars is the RAW producer's characteristics. */
        fun onPhoto(jpeg: Image?, raw: Image?, result: TotalCaptureResult, rawChars: CameraCharacteristics)
        fun onError(t: Throwable)
    }

    private val manager = context.getSystemService(CameraManager::class.java)
    private val bg = HandlerThread("camera").apply { start() }
    private val handler = Handler(bg.looper)
    // Camera2 posts its callbacks through this executor; guard the post so a framework callback that
    // lands AFTER close() quit the looper is dropped instead of thrown — OPPO's LegacyMessageQueue
    // raises IllegalStateException "sending message to a dead thread" rather than returning false.
    private val executor = Executor { cmd -> runCatching { handler.post(cmd) } }

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
    private var lastReportedIso: Int? = null
    private var lastReportedExpNs: Long? = null
    // Live lens focus distance (diopters, from CaptureResult.LENS_FOCUS_DISTANCE) surfaced to the UI:
    // shows where AF actually parked the lens, and seeds the manual-focus slider on the AF→MF
    // handoff so fine focus starts from AF's solution instead of a stale value. Reported on change,
    // throttled with the exposure readout.
    @Volatile var onFocusDistance: ((Float) -> Unit)? = null
    private var lastReportedFocus = Float.NaN

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
        onReady: Ready,
        onError: ErrorCb,
    ) {
        this.selection = selection
        this.caps = caps
        this.glSurface = glInputSurface
        this.controls = controls
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
                    runCatching { configureSession(onReady, onError) }.onFailure { onError.onError(it) }
                }
                override fun onDisconnected(camera: CameraDevice) {
                    onError.onError(IllegalStateException("Camera disconnected"))
                    close()
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    onError.onError(IllegalStateException("Camera error $error"))
                    close()
                }
            })
        }.onFailure { onError.onError(it) }
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
            .onSuccess { Log.i(TAG, "vendor log.video.mode=$vendorLogMode applied") }
            .onFailure { Log.w(TAG, "vendor log.video.mode=$vendorLogMode rejected: ${it.message}") }
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
            }.onFailure { Log.w(TAG, "Hasselblad camera mode hint rejected: ${it.message}") }
        }
        runCatching {
            set(oplusOriginalZoomRatioKey, effectiveZoom)
        }.onFailure { Log.w(TAG, "original zoom ratio hint rejected: ${it.message}") }
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
    private fun configureSession(onReady: Ready, onError: ErrorCb) {
        val camera = device ?: return
        val preview = glSurface ?: return

        // Discard any readers built by a previous (failed) attempt before rebuilding.
        runCatching { jpegReader?.close() }
        runCatching { rawReader?.close() }
        jpegReader = null
        rawReader = null

        // High-speed (slow-motion) uses a dedicated constrained session with ONLY the GL surface;
        // it cannot coexist with the JPEG/RAW still readers, so it is a separate path.
        if (highSpeedFps > 0) { configureHighSpeed(camera, preview, onReady, onError); return }

        val attempt = configAttempt
        val useHlg = tenBitHlg && caps.supportsHlg10() && attempt < 2
        val useJpeg = attempt < 3
        // RAW routed to a physical sub-camera of a logical multicamera crashes this QTI HAL
        // (configureStreams: 'DataSpace override not allowed for format 0x20' -> SIGSEGV in
        // ChiMulticameraBase::Initialize). Only enable RAW for a standalone (non-routed) camera.
        val useRaw = attempt < 1 && caps.supportsRaw && selection.physicalId == null

        val configs = ArrayList<OutputConfiguration>()

        val previewCfg = OutputConfiguration(preview).apply {
            selection.physicalId?.let { setPhysicalCameraId(it) }
            if (useHlg) setDynamicRangeProfile(DynamicRangeProfiles.HLG10)
        }
        configs.add(previewCfg)

        if (useJpeg) caps.largestJpegSize?.let { size ->
            val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
            reader.setOnImageAvailableListener({ onImage(it, isRaw = false) }, handler)
            jpegReader = reader
            configs.add(OutputConfiguration(reader.surface).apply {
                selection.physicalId?.let { setPhysicalCameraId(it) }
            })
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
            SessionConfiguration.SESSION_REGULAR, configs, executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (closed) { runCatching { s.close() }; return } // closed before config completed
                    session = s
                    Log.i(TAG, "Session configured (fallback=$attempt, hlg=$useHlg, jpeg=$useJpeg, raw=$useRaw, vendorLog=$vendorLogMode)")
                    startPreview()
                    onReady.onReady()
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    // Advance the fallback ladder and retry; give up once it is exhausted.
                    configAttempt = attempt + 1
                    if (configAttempt > MAX_CONFIG_ATTEMPT) {
                        Log.e(TAG, "Session configure failed; fallback ladder exhausted")
                        onError.onError(IllegalStateException("session configure failed"))
                    } else {
                        Log.w(TAG, "Session configure failed at fallback $attempt; retrying at $configAttempt")
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
        }.onFailure { Log.w(TAG, "session params with vendor stabilization/log failed: ${it.message}") }
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
                    Log.i(TAG, "High-speed session configured (${highSpeedFps}fps)")
                    startHighSpeedPreview()
                    onReady.onReady()
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    Log.w(TAG, "High-speed session config failed at ${highSpeedFps}fps; falling back to regular session")
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
        }.onFailure { Log.w(TAG, "high-speed session params with vendor stabilization/log failed: ${it.message}") }
        runCatching { camera.createCaptureSession(sessionConfig) }.onFailure {
            Log.w(TAG, "createCaptureSession(HIGH_SPEED) threw; falling back: ${it.message}")
            highSpeedFps = 0
            configAttempt = 0
            runCatching { configureSession(onReady, onError) }.onFailure { e -> onError.onError(e) }
        }
    }

    /** Issues the high-speed repeating burst (one request expanded to N by the HAL) at [highSpeedFps]. */
    private fun startHighSpeedPreview() {
        if (closed) return
        val camera = device ?: return
        val preview = glSurface ?: return
        val s = session as? CameraConstrainedHighSpeedCaptureSession ?: return
        runCatching {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(preview)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(highSpeedFps, highSpeedFps))
                // Our gyro EIS handles stabilization at the effective focal length; keep HAL video
                // stabilization off, matching the regular path. OIS per the user toggle.
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
        }.onFailure { Log.w(TAG, "startHighSpeedPreview skipped: ${it.message}") }
    }

    /**
     * (Re)issues the repeating preview request. When [afTriggerPending] is set (a fresh tap-to-focus
     * point) and AF is running (non-MANUAL focus), it first fires ONE triggered capture identical to
     * the repeating request but carrying CONTROL_AF_TRIGGER_START, so the AF engine converges on the
     * tapped region; the trigger is then cleared (IDLE) and the repeating request continues to hold
     * that result. All calls guard nulls so a torn-down session is a no-op.
     */
    private fun startPreview() {
        if (closed) return
        // In high-speed mode the session is a constrained high-speed one; its repeating request must
        // be a burst list, so route there instead of the regular single-request path below.
        if (highSpeedFps > 0) { startHighSpeedPreview(); return }
        val camera = device ?: return
        val preview = glSurface ?: return
        val s = session ?: return
        // The device can be disconnected asynchronously (app backgrounded, another client, HAL) between
        // session config and here; createCaptureRequest/setRepeatingRequest then throw CameraAccess/
        // IllegalState. Guard the whole build+submit so a torn-down session degrades to "no preview
        // this cycle" instead of crashing the camera thread.
        runCatching {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview)
                applyManualControls(controls, caps, pinAutoFps)
                applyVendorLog()
                applyVideoStab()
                applyTeleconverterHints()
                applyMetering(this, controls)
                // Tap-to-focus: force AF_MODE_AUTO for a one-shot region scan that LOCKS on the tapped
                // spot (CONTINUOUS + a bare trigger just holds the current, often-wrong, distance). The
                // region is set by applyMetering above; the trigger below drives the scan.
                if (touchAfActive && controls.focusMode != FocusMode.MANUAL) {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                }
                // AF lock: freeze focus at the last AF-resolved distance instead of leaving AF running.
                // Not applicable in MANUAL focus mode, where focus is already fixed by the user.
                if (controls.afLock && controls.focusMode != FocusMode.MANUAL && caps.supportsManualFocus) {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, lastFocusDistance)
                }
            }
            val callback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult,
                ) {
                    result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { lastFocusDistance = it }
                    // Stashed for custom-WB capture (grey-card measure) and JPEG EXIF: the AWB gains
                    // and exposure the HAL actually used on the latest frame.
                    result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { lastAwbGains = it }
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
                    }
                    // Diagnostic: log what 3A is actually doing (throttled ~1/sec) so we can tell
                    // whether AE/AF are converging on the standalone tele or effectively inert.
                    if (threeAFrame % 30 == 0) {
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
            if (afTriggerPending && controls.focusMode != FocusMode.MANUAL) {
                Log.i(TAG, "Touch AF: scanning region $meteringPoint")
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
                runCatching { s.capture(builder.build(), callback, handler) }
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                runCatching { s.capture(builder.build(), callback, handler) }
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            }
            afTriggerPending = false
            s.setRepeatingRequest(builder.build(), callback, handler)
        }.onFailure { Log.w(TAG, "startPreview skipped: ${it.message}") }
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
    /** Latest-frame AWB gains / exposure, for custom-WB capture and JPEG EXIF. */
    @Volatile var lastAwbGains: android.hardware.camera2.params.RggbChannelVector? = null
        private set
    @Volatile var lastIso: Int = 0
        private set
    @Volatile var lastExposureNs: Long = 0L
        private set

    private fun applyMetering(builder: CaptureRequest.Builder, controls: ManualControls) {
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

        val rw = (active.width() * fraction).toInt().coerceAtLeast(1)
        val rh = (active.height() * fraction).toInt().coerceAtLeast(1)
        // Clamp so the rectangle stays fully inside the active array even near an edge.
        val left = (cx - rw / 2).coerceIn(active.left, active.right - rw)
        val top = (cy - rh / 2).coerceIn(active.top, active.bottom - rh)
        val regions = arrayOf(MeteringRectangle(left, top, rw, rh, MeteringRectangle.METERING_WEIGHT_MAX))
        builder.set(CaptureRequest.CONTROL_AE_REGIONS, regions)
        // AF regions are only meaningful when the AF engine is running (any non-MANUAL focus mode).
        if (controls.focusMode != FocusMode.MANUAL) builder.set(CaptureRequest.CONTROL_AF_REGIONS, regions)
    }

    fun updateControls(controls: ManualControls) {
        // Confine the field write to the camera handler thread: updateControls is called from both the
        // main thread (ViewModel) and the camera thread (AEB/BURST chain), and the field is read on the
        // camera thread, so doing the mutation here keeps it single-threaded (no lost-update race).
        postToCamera {
            // An explicit focus-mode change ends the tap-to-focus AUTO hold and resumes the chosen mode.
            if (controls.focusMode != this.controls.focusMode) touchAfActive = false
            this.controls = controls
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
        touchAfActive = true // hold AF_MODE_AUTO on this spot until the focus mode changes
        postToCamera { startPreview() }
    }

    /** Clears the tap target, restoring the metering-mode regions on the next preview build. */
    fun clearMeteringPoint() {
        meteringPoint = null
        postToCamera { startPreview() }
    }

    fun capturePhoto(wantJpeg: Boolean, wantRaw: Boolean, cb: PhotoCallback) {
        val posted = postToCamera {
            // Always surface a result through the callback (even on the no-target/not-ready paths) so a
            // BURST/AEB chain's onDone still fires and the user gets feedback instead of a silent no-op.
            val camera = device ?: return@postToCamera cb.onError(IllegalStateException("Camera not ready"))
            val s = session ?: return@postToCamera cb.onError(IllegalStateException("Camera session not ready"))
            val jpeg = jpegReader?.surface?.takeIf { wantJpeg }
            val raw = rawReader?.surface?.takeIf { wantRaw && caps.supportsRaw }
            if (jpeg == null && raw == null) {
                return@postToCamera cb.onError(
                    IllegalStateException("No capture target — enable HEIF or DNG (the session may have fallen back to preview-only)"),
                )
            }
            if (pending != null) {
                return@postToCamera cb.onError(IllegalStateException("Capture already in progress"))
            }

            pending = Pending(jpeg != null, raw != null, cb)

            val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                jpeg?.let { addTarget(it) }
                raw?.let { addTarget(it) }
                applyManualControls(controls, caps, pinAutoFps)
                // Keep stills consistent with the session's pipeline: with the log session active the
                // HAL processes everything scene-referred, so an unset key mid-session is undefined.
                applyVendorLog()
                applyTeleconverterHints()
                applyMetering(this, controls)
                // We rotate pixels ourselves (HEIF) / tag DNG orientation; keep JPEG upright.
                set(CaptureRequest.JPEG_ORIENTATION, 0)
            }
            s.capture(req.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult,
                ) {
                    pending?.let { it.result = result; tryComplete(it) }
                }
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
                    val p = pending
                    if (p != null) {
                        // Close any already-acquired images so the ImageReader (maxImages=2) isn't
                        // starved, and mark done so a late-arriving partial can't re-enter tryComplete.
                        synchronized(p) {
                            p.done = true
                            runCatching { p.jpeg?.close() }
                            runCatching { p.raw?.close() }
                        }
                        p.cb.onError(IllegalStateException("Capture failed: ${failure.reason}"))
                    }
                    pending = null
                }
            }, handler)
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
            p.done = true
        }
        val chars = rawChars
        try {
            if (chars != null) p.cb.onPhoto(p.jpeg, p.raw, p.result!!, chars)
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
        // Idempotent: a second close() must not run — the looper is already quitting, so posting the
        // teardown again would throw on OPPO's LegacyMessageQueue. setCameraOverride() → openCamera()
        // both call close() on the same controller, so this path is real.
        if (closed) return
        closed = true
        handler.post {
            pending?.let { p ->
                synchronized(p) {
                    if (!p.done) {
                        p.done = true
                        runCatching { p.jpeg?.close() }
                        runCatching { p.raw?.close() }
                        p.cb.onError(IllegalStateException("Camera closed during capture"))
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
        var done = false
    }

    private companion object {
        const val TAG = "CameraController"
        // Highest fallback index (attempt 3 = preview-only). Beyond this the session can't be built.
        const val MAX_CONFIG_ATTEMPT = 3
        // Max time close() waits for the camera thread to release the HAL device before a reopen.
        // Device close is normally well under this; the cap keeps a wedged close from hanging the UI.
        const val CLOSE_JOIN_TIMEOUT_MS = 1500L
        const val TELECONVERTER_MAGNIFICATION = 300f / 70f
        const val OPLUS_CAMERA_MODE_TELEPHOTO_HASSELBLAD: Byte = 40
    }
}
