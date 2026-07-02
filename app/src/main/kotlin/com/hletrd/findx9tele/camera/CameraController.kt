package com.hletrd.findx9tele.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
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
    private val executor = Executor { handler.post(it) }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null

    private lateinit var selection: TeleSelection
    private lateinit var caps: CameraCaps
    private var glSurface: Surface? = null
    private var controls = ManualControls()
    private var tenBitHlg = false
    private var rawChars: CameraCharacteristics? = null
    private var configAttempt = 0

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

    @SuppressLint("MissingPermission") // caller guarantees CAMERA permission before open()
    fun open(
        selection: TeleSelection,
        caps: CameraCaps,
        glInputSurface: Surface,
        controls: ManualControls,
        tenBitHlg: Boolean,
        onReady: Ready,
        onError: ErrorCb,
    ) {
        this.selection = selection
        this.caps = caps
        this.glSurface = glInputSurface
        this.controls = controls
        this.tenBitHlg = tenBitHlg
        this.rawChars = runCatching {
            manager.getCameraCharacteristics(selection.physicalId ?: selection.logicalId)
        }.getOrNull()

        manager.openCamera(selection.logicalId, executor, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                configAttempt = 0
                runCatching { configureSession(onReady, onError) }.onFailure { onError.onError(it) }
            }
            override fun onDisconnected(camera: CameraDevice) { close() }
            override fun onError(camera: CameraDevice, error: Int) {
                onError.onError(IllegalStateException("Camera error $error"))
                close()
            }
        })
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
                    session = s
                    Log.i(TAG, "Session configured (fallback=$attempt, hlg=$useHlg, jpeg=$useJpeg, raw=$useRaw)")
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
        camera.createCaptureSession(sessionConfig)
    }

    /**
     * (Re)issues the repeating preview request. When [afTriggerPending] is set (a fresh tap-to-focus
     * point) and AF is running (non-MANUAL focus), it first fires ONE triggered capture identical to
     * the repeating request but carrying CONTROL_AF_TRIGGER_START, so the AF engine converges on the
     * tapped region; the trigger is then cleared (IDLE) and the repeating request continues to hold
     * that result. All calls guard nulls so a torn-down session is a no-op.
     */
    private fun startPreview() {
        val camera = device ?: return
        val preview = glSurface ?: return
        val s = session ?: return
        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(preview)
            applyManualControls(controls, caps)
            applyMetering(this, controls)
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
                result.get(android.hardware.camera2.CaptureResult.LENS_FOCUS_DISTANCE)?.let { lastFocusDistance = it }
            }
        }
        // Tap-to-focus one-shot: START the AF engine at the new point, then fall through to the
        // repeating request with the trigger cleared so the converged focus is held.
        if (afTriggerPending && controls.focusMode != FocusMode.MANUAL) {
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            runCatching { s.capture(builder.build(), callback, handler) }
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        }
        afTriggerPending = false
        s.setRepeatingRequest(builder.build(), callback, handler)
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
    private fun applyMetering(builder: CaptureRequest.Builder, controls: ManualControls) {
        val active = rawChars?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return

        val point = meteringPoint
        val (cx, cy, fraction) = if (point != null) {
            // Tapped spot: center on the sensor-normalized point (~10% of the active array).
            Triple(
                active.left + (point.first * active.width()).toInt(),
                active.top + (point.second * active.height()).toInt(),
                0.10f,
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
        this.controls = controls
        handler.post { startPreview() }
    }

    /**
     * Sets the tap-to-focus/meter target. [sx],[sy] are SENSOR-normalized (0..1); the caller has
     * already applied the view→sensor rotation. Arms a one-shot AF trigger and rebuilds the preview
     * so the new AE/AF spot region (and AF convergence) takes effect immediately.
     */
    fun setMeteringPoint(sx: Float, sy: Float) {
        meteringPoint = sx.coerceIn(0f, 1f) to sy.coerceIn(0f, 1f)
        afTriggerPending = true
        handler.post { startPreview() }
    }

    /** Clears the tap target, restoring the metering-mode regions on the next preview build. */
    fun clearMeteringPoint() {
        meteringPoint = null
        handler.post { startPreview() }
    }

    fun capturePhoto(wantJpeg: Boolean, wantRaw: Boolean, cb: PhotoCallback) = handler.post {
        val camera = device ?: return@post
        val s = session ?: return@post
        val jpeg = jpegReader?.surface?.takeIf { wantJpeg }
        val raw = rawReader?.surface?.takeIf { wantRaw && caps.supportsRaw }
        if (jpeg == null && raw == null) return@post

        pending = Pending(jpeg != null, raw != null, cb)

        val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            jpeg?.let { addTarget(it) }
            raw?.let { addTarget(it) }
            applyManualControls(controls, caps)
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
        handler.post {
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
    }
}
