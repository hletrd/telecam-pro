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
import com.hletrd.findx9tele.gl.GlPipeline
import com.hletrd.findx9tele.storage.MediaStoreWriter
import com.hletrd.findx9tele.video.VideoRecorder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Facade tying Camera2 + GL(180° flip) + capture encoders + video recorder + MediaStore together.
 * Called by the ViewModel; internal work runs on the components' own threads. All image encoding
 * happens off the UI thread (inside camera/GL callbacks).
 */
class CameraEngine(private val context: Context) {

    private val manager = context.getSystemService(CameraManager::class.java)
    private val gl = GlPipeline()
    private var controller: CameraController? = null
    private var recorder: VideoRecorder? = null

    private var selection: TeleSelection? = null
    private var caps: CameraCaps? = null
    private var videoSize = Size(1920, 1080)
    private var controls = ManualControls()
    private var transfer = ColorTransfer.HLG
    private var overrideId: String? = null
    private var started = false
    private var previewSurface: Surface? = null

    private val gyro = com.hletrd.findx9tele.stab.GyroEis(context)
    private var teleconverterMode = true
    private var eisEnabled = true

    var onStatus: ((String?) -> Unit)? = null
    var onCapsReady: ((CameraCaps) -> Unit)? = null

    // ---- Preview surface lifecycle ----

    fun onPreviewSurfaceAvailable(surface: Surface, width: Int, height: Int) {
        previewSurface = surface
        if (started) { gl.setPreviewOutput(surface, width, height); return }

        val sel = CameraSelector2.select(manager, overrideId)
        if (sel == null) { onStatus?.invoke("망원 카메라를 찾지 못했습니다"); return }
        selection = sel
        val c = CameraCaps.read(manager, sel.logicalId, sel.physicalId)
        caps = c
        onCapsReady?.invoke(c)
        videoSize = chooseVideoSize(sel)

        val tenBit = c.supportsHlg10()
        gl.start(tenBit) { input ->
            gl.setCameraPreviewSize(videoSize.width, videoSize.height)
            gl.setEisProvider { gyro.currentCorrection() }
            applyStabilization()
            gyro.start()
            VendorTagInspector.dumpAll(manager)
            openCamera(input)
        }
        gl.setPreviewOutput(surface, width, height)
        started = true
    }

    /** Rotation (afocal 180° only in teleconverter mode) + gyro-EIS focal scaled to the effective FL. */
    private fun applyStabilization() {
        val c = caps ?: return
        gl.setRotationDegrees(c.sensorOrientation + if (teleconverterMode) 180 else 0)
        val mag = if (teleconverterMode) TELECONVERTER_MAGNIFICATION else 1f
        gl.setEis(eisEnabled, c.nativeFocalInImageWidths * mag, EIS_CROP)
    }

    fun setTeleconverterMode(enabled: Boolean) { teleconverterMode = enabled; applyStabilization() }
    fun setEisEnabled(enabled: Boolean) { eisEnabled = enabled; applyStabilization() }
    fun setFalseColor(enabled: Boolean) = gl.setFalseColor(enabled)

    fun onPreviewSurfaceChanged(width: Int, height: Int) {
        val surface = previewSurface ?: return
        gl.setPreviewOutput(surface, width, height)
    }

    fun onPreviewSurfaceDestroyed() {
        // Portrait is locked, so this is app teardown; keep the GL context but drop the output.
        previewSurface = null
        gl.setPreviewOutput(null, 0, 0)
    }

    private fun openCamera(input: Surface) {
        val sel = selection ?: return
        val c = caps ?: return
        val ctrl = CameraController(context)
        controller = ctrl
        ctrl.open(
            selection = sel,
            caps = c,
            glInputSurface = input,
            controls = controls,
            tenBitHlg = c.supportsHlg10(),
            onReady = { onStatus?.invoke(null) },
            onError = { onStatus?.invoke("카메라 오류: ${it.message}") },
        )
    }

    // ---- Controls ----

    fun setControls(c: ManualControls) {
        controls = c
        controller?.updateControls(c)
    }

    fun setTransfer(t: ColorTransfer) { transfer = t; gl.setTransfer(t) }
    fun setPeaking(enabled: Boolean) = gl.setPeaking(enabled)
    fun setZebra(enabled: Boolean) = gl.setZebra(enabled)

    fun setCameraOverride(id: String?) {
        overrideId = id
        if (!started) return
        val input = gl.inputSurface ?: return
        controller?.close()
        val sel = CameraSelector2.select(manager, id) ?: run { onStatus?.invoke("카메라 ID를 찾지 못했습니다"); return }
        selection = sel
        val c = CameraCaps.read(manager, sel.logicalId, sel.physicalId)
        caps = c
        onCapsReady?.invoke(c)
        applyStabilization()
        openCamera(input)
    }

    // ---- Photo ----

    fun capturePhoto(formats: PhotoFormats) {
        val ctrl = controller ?: return
        ctrl.capturePhoto(formats.heif, formats.dngRaw, object : CameraController.PhotoCallback {
            override fun onPhoto(jpeg: Image?, raw: Image?, result: TotalCaptureResult, rawChars: CameraCharacteristics) {
                if (formats.heif && jpeg != null) runCatching { saveHeif(jpeg) }
                    .onFailure { onStatus?.invoke("HEIF 저장 실패: ${it.message}") }
                if (formats.dngRaw && raw != null) runCatching { saveDng(raw, rawChars, result) }
                    .onFailure { onStatus?.invoke("DNG 저장 실패: ${it.message}") }
                onStatus?.invoke("저장됨")
            }
            override fun onError(t: Throwable) { onStatus?.invoke("촬영 실패: ${t.message}") }
        })
    }

    private fun saveHeif(jpeg: Image) {
        val buf = jpeg.planes[0].buffer
        val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
        val rotated = rotate180(decoded)
        val name = fileName("IMG", "heic")
        val uri = MediaStoreWriter.createPendingImage(context, name, "image/heic") ?: return
        MediaStoreWriter.openParcelFd(context, uri, "rw")?.use { pfd ->
            HeifCapture.writeHeif(pfd.fileDescriptor, rotated)
        }
        MediaStoreWriter.publish(context, uri)
        if (rotated != decoded) decoded.recycle()
        rotated.recycle()
    }

    private fun saveDng(raw: Image, chars: CameraCharacteristics, result: TotalCaptureResult) {
        val name = fileName("IMG", "dng")
        val uri = MediaStoreWriter.createPendingImage(context, name, "image/x-adobe-dng") ?: return
        MediaStoreWriter.openOutputStream(context, uri)?.use { out ->
            DngCapture.writeDng(out, raw, chars, result)
        }
        MediaStoreWriter.publish(context, uri)
    }

    // ---- Video ----

    fun startRecording(recordAudio: Boolean): Boolean {
        if (recorder != null) return false
        val name = fileName("VID", "mp4")
        val uri = MediaStoreWriter.createPendingVideo(context, name, "video/mp4") ?: return false
        val rec = VideoRecorder(context)
        val surface = rec.start(uri, videoSize, FPS, bitRateFor(videoSize), transfer, recordAudio)
        if (surface == null) { onStatus?.invoke("녹화 시작 실패"); return false }
        gl.setTransfer(transfer)
        gl.setEncoderOutput(surface, videoSize.width, videoSize.height)
        recorder = rec
        return true
    }

    fun stopRecording() {
        val rec = recorder ?: return
        gl.setEncoderOutput(null, 0, 0)
        rec.stop()
        recorder = null
        onStatus?.invoke("동영상 저장됨")
    }

    fun release() {
        runCatching { recorder?.stop() }
        recorder = null
        gyro.stop()
        controller?.close()
        controller = null
        gl.stop()
        started = false
    }

    // ---- Helpers ----

    private fun rotate180(src: Bitmap): Bitmap {
        val m = Matrix().apply { postRotate(180f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun chooseVideoSize(sel: TeleSelection): Size {
        val chars = runCatching {
            manager.getCameraCharacteristics(sel.physicalId ?: sel.logicalId)
        }.getOrNull() ?: return Size(1920, 1080)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(1920, 1080)
        val sizes = map.getOutputSizes(SurfaceTexture::class.java) ?: return Size(1920, 1080)
        // Largest 16:9 up to 3840 wide; fall back to the largest available.
        return sizes.filter { it.width <= 3840 && it.height * 16 == it.width * 9 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.maxByOrNull { it.width.toLong() * it.height }
            ?: Size(1920, 1080)
    }

    private fun bitRateFor(size: Size): Int =
        (size.width.toLong() * size.height * FPS * 0.1).toInt().coerceIn(8_000_000, 120_000_000)

    private fun fileName(prefix: String, ext: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "${prefix}_X9TELE_$stamp.$ext"
    }

    private companion object {
        const val FPS = 30
        // 300mm / 70mm ≈ 4.286: the Explorer teleconverter's angular magnification.
        const val TELECONVERTER_MAGNIFICATION = 300f / 70f
        const val EIS_CROP = 0.10f
    }
}
