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
        val useRaw = attempt < 1 && caps.supportsRaw

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

    private fun startPreview() {
        val camera = device ?: return
        val preview = glSurface ?: return
        val s = session ?: return
        val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
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
        s.setRepeatingRequest(req.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult,
            ) {
                result.get(android.hardware.camera2.CaptureResult.LENS_FOCUS_DISTANCE)?.let { lastFocusDistance = it }
            }
        }, handler)
    }

    /**
     * Applies the metering pattern as AE (and, in an AF focus mode, AF) regions, sized to the
     * RAW/producer sensor active array:
     *   MATRIX → no region (default full-frame metering).
     *   CENTER → one center rectangle covering 40% of the active array at METERING_WEIGHT_MAX.
     *   SPOT   → one center rectangle covering 12%, at METERING_WEIGHT_MAX.
     * No-op when the active array is unavailable, so it degrades to full-frame metering.
     */
    private fun applyMetering(builder: CaptureRequest.Builder, controls: ManualControls) {
        val fraction = when (controls.meteringMode) {
            MeteringMode.MATRIX -> return
            MeteringMode.CENTER -> 0.40f
            MeteringMode.SPOT -> 0.12f
        }
        val active = rawChars?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val rw = (active.width() * fraction).toInt().coerceAtLeast(1)
        val rh = (active.height() * fraction).toInt().coerceAtLeast(1)
        val cx = active.left + active.width() / 2
        val cy = active.top + active.height() / 2
        val region = MeteringRectangle(cx - rw / 2, cy - rh / 2, rw, rh, MeteringRectangle.METERING_WEIGHT_MAX)
        val regions = arrayOf(region)
        builder.set(CaptureRequest.CONTROL_AE_REGIONS, regions)
        // AF regions are only meaningful when the AF engine is running (any non-MANUAL focus mode).
        if (controls.focusMode != FocusMode.MANUAL) builder.set(CaptureRequest.CONTROL_AF_REGIONS, regions)
    }

    fun updateControls(controls: ManualControls) {
        this.controls = controls
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
