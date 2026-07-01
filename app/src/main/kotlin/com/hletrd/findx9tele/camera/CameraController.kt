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
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
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

    @Volatile private var pending: Pending? = null

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
                runCatching { configureSession(onReady) }.onFailure { onError.onError(it) }
            }
            override fun onDisconnected(camera: CameraDevice) { close() }
            override fun onError(camera: CameraDevice, error: Int) {
                onError.onError(IllegalStateException("Camera error $error"))
                close()
            }
        })
    }

    private fun configureSession(onReady: Ready) {
        val camera = device ?: return
        val preview = glSurface ?: return

        val configs = ArrayList<OutputConfiguration>()

        val previewCfg = OutputConfiguration(preview).apply {
            selection.physicalId?.let { setPhysicalCameraId(it) }
            if (tenBitHlg && caps.supportsHlg10()) setDynamicRangeProfile(DynamicRangeProfiles.HLG10)
        }
        configs.add(previewCfg)

        caps.largestJpegSize?.let { size ->
            val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
            reader.setOnImageAvailableListener({ onImage(it, isRaw = false) }, handler)
            jpegReader = reader
            configs.add(OutputConfiguration(reader.surface).apply {
                selection.physicalId?.let { setPhysicalCameraId(it) }
            })
        }

        if (caps.supportsRaw) caps.rawSize?.let { size ->
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
                    startPreview()
                    onReady.onReady()
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    // Fallback path (e.g. 10-bit + RAW combo unsupported) is handled by the caller.
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
        }
        s.setRepeatingRequest(req.build(), null, handler)
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
                pending?.cb?.onError(IllegalStateException("Capture failed: ${failure.reason}"))
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
    }

    private class Pending(val wantJpeg: Boolean, val wantRaw: Boolean, val cb: PhotoCallback) {
        var jpeg: Image? = null
        var raw: Image? = null
        var result: TotalCaptureResult? = null
        var done = false
    }
}
