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
    // Startup setup (camera-service IPC) and still-image encoding are kept off the main/camera/GL
    // threads via these single-thread executors.
    private val setupExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var controller: CameraController? = null
    @Volatile private var recorder: VideoRecorder? = null

    // These are written from the UI thread and read on the GL thread (via the onInputReady
    // continuation), so they are @Volatile to give the JMM a visibility edge across the seam.
    @Volatile private var selection: TeleSelection? = null
    @Volatile private var caps: CameraCaps? = null
    @Volatile private var videoSize = Size(1920, 1080)
    @Volatile private var controls = ManualControls()
    @Volatile private var transfer = ColorTransfer.HLG
    @Volatile private var overrideId: String? = null
    @Volatile private var started = false
    // Set true synchronously on the calling thread once startup setup is dispatched, so a second
    // onPreviewSurfaceAvailable arriving before setup completes doesn't launch a duplicate start.
    @Volatile private var starting = false
    @Volatile private var previewSurface: Surface? = null

    private val gyro = com.hletrd.findx9tele.stab.GyroEis(context)
    @Volatile private var teleconverterMode = true
    @Volatile private var eisEnabled = true

    var onStatus: ((String?) -> Unit)? = null
    var onCapsReady: ((CameraCaps) -> Unit)? = null

    // ---- Preview surface lifecycle ----

    fun onPreviewSurfaceAvailable(surface: Surface, width: Int, height: Int) {
        previewSurface = surface // published synchronously on the calling (main) thread
        if (started) { gl.setPreviewOutput(surface, width, height); return }
        if (starting) return // setup already dispatched; don't launch a duplicate start
        starting = true

        // CameraSelector2.select + CameraCaps.read + chooseVideoSize each issue several
        // getCameraCharacteristics IPCs (tens of ms). surfaceCreated is delivered on the MAIN
        // thread, so run that setup on a background thread to avoid startup jank / near-ANR.
        // gl.start / gl.setPreviewOutput just post to the GL handler, so they're thread-safe here.
        setupExecutor.execute {
            val sel = CameraSelector2.select(manager, overrideId)
            if (sel == null) {
                onStatus?.invoke("망원 카메라를 찾지 못했습니다")
                starting = false
                return@execute
            }
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
                maybeDumpVendorTags()
                openCamera(input)
            }
            gl.setPreviewOutput(surface, width, height)
            started = true
        }
    }

    /** Debug-only vendor-tag dump, run off the GL thread so it never delays openCamera / first frame. */
    private fun maybeDumpVendorTags() {
        if (!com.hletrd.findx9tele.BuildConfig.DEBUG) return
        setupExecutor.execute { runCatching { VendorTagInspector.dumpAll(manager) } }
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
        val input = gl.inputSurface ?: return // @Volatile in GlPipeline: safe cross-thread read
        controller?.close()
        val sel = CameraSelector2.select(manager, id) ?: run { onStatus?.invoke("카메라 ID를 찾지 못했습니다"); return }
        selection = sel
        val c = CameraCaps.read(manager, sel.logicalId, sel.physicalId)
        caps = c
        onCapsReady?.invoke(c)
        // The new lens can expose different output sizes; refresh videoSize + the GL camera size so
        // aspect (FlipRenderer "cover"), EIS focal scaling, and the encoder size match the new lens.
        videoSize = chooseVideoSize(sel)
        gl.setCameraPreviewSize(videoSize.width, videoSize.height)
        applyStabilization()
        openCamera(input)
    }

    // ---- Photo ----

    fun capturePhoto(formats: PhotoFormats) {
        val ctrl = controller ?: return
        ctrl.capturePhoto(formats.heif, formats.dngRaw, object : CameraController.PhotoCallback {
            // Runs on the camera thread; the Images are valid ONLY for the duration of this call.
            override fun onPhoto(jpeg: Image?, raw: Image?, result: TotalCaptureResult, rawChars: CameraCharacteristics) {
                if (formats.heif) {
                    if (jpeg != null) {
                        // Cheaply copy the compressed JPEG bytes out while the Image is alive, then
                        // decode/rotate/encode HEIF off the camera thread (avoids OOM + control stall).
                        val bytes = runCatching {
                            val buf = jpeg.planes[0].buffer
                            ByteArray(buf.remaining()).also { buf.get(it) }
                        }.getOrNull()
                        if (bytes != null) ioExecutor.execute { saveHeifAsync(bytes) }
                        else onStatus?.invoke("HEIF 저장 실패")
                    } else onStatus?.invoke("HEIF 저장 실패: JPEG 없음")
                }
                if (formats.dngRaw) {
                    if (raw != null) {
                        // DngCreator needs the live raw Image → must stay synchronous in this callback.
                        runCatching { saveDng(raw, rawChars, result) }
                            .onSuccess { onStatus?.invoke("DNG 저장됨") }
                            .onFailure { onStatus?.invoke("DNG 저장 실패: ${it.message}") }
                    } else onStatus?.invoke("DNG 저장 실패: RAW 없음")
                }
                if (!formats.heif && !formats.dngRaw) onStatus?.invoke("저장할 형식이 없습니다")
                // HEIF success/failure is reported from inside saveHeifAsync (it runs later).
            }
            override fun onError(t: Throwable) { onStatus?.invoke("촬영 실패: ${t.message}") }
        })
    }

    /** Decode → rotate 180° → write HEIF on [ioExecutor]. Publishes only on success; deletes on any failure. */
    private fun saveHeifAsync(bytes: ByteArray) {
        var decoded: Bitmap? = null
        var rotated: Bitmap? = null
        var uri: android.net.Uri? = null
        try {
            val d = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (d == null) { onStatus?.invoke("HEIF 저장 실패: 디코딩 실패"); return }
            decoded = d
            val r = rotate180(d)
            rotated = r
            val u = MediaStoreWriter.createPendingImage(context, fileName("IMG", "heic"), "image/heic")
            if (u == null) { onStatus?.invoke("HEIF 저장 실패"); return }
            uri = u
            val wrote = MediaStoreWriter.openParcelFd(context, u, "rw")?.use { pfd ->
                HeifCapture.writeHeif(pfd.fileDescriptor, r); true
            } ?: false
            if (!wrote) { MediaStoreWriter.delete(context, u); onStatus?.invoke("HEIF 저장 실패"); return }
            MediaStoreWriter.publish(context, u)
            onStatus?.invoke("저장됨")
        } catch (e: OutOfMemoryError) {
            uri?.let { MediaStoreWriter.delete(context, it) }
            onStatus?.invoke("HEIF 저장 실패: 메모리 부족")
        } catch (t: Throwable) {
            uri?.let { MediaStoreWriter.delete(context, it) }
            onStatus?.invoke("HEIF 저장 실패: ${t.message}")
        } finally {
            val rr = rotated
            val dd = decoded
            if (rr != null && rr !== dd) rr.recycle()
            dd?.recycle()
        }
    }

    /** Writes the RAW image as DNG synchronously. Publishes only on success; deletes then rethrows on failure. */
    private fun saveDng(raw: Image, chars: CameraCharacteristics, result: TotalCaptureResult) {
        val uri = MediaStoreWriter.createPendingImage(context, fileName("IMG", "dng"), "image/x-adobe-dng")
            ?: throw IllegalStateException("MediaStore 항목 생성 실패")
        try {
            val out = MediaStoreWriter.openOutputStream(context, uri)
                ?: throw IllegalStateException("출력 스트림 열기 실패")
            out.use { DngCapture.writeDng(it, raw, chars, result) }
            MediaStoreWriter.publish(context, uri)
        } catch (t: Throwable) {
            MediaStoreWriter.delete(context, uri)
            throw t
        }
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

    /** Releases the camera + gyro for backgrounding without tearing down the GL pipeline or start state. */
    fun pause() {
        runCatching { recorder?.stop() }
        recorder = null
        gyro.stop()
        controller?.close()
        controller = null
    }

    /** Reopens the camera after [pause], reusing the existing GL input surface and start state. */
    fun resume() {
        val input = gl.inputSurface ?: return
        if (controller != null) return
        gyro.start()
        openCamera(input)
    }

    fun currentRollDegrees(): Float = gyro.currentRollDegrees()

    fun setPunchIn(enabled: Boolean) = gl.setPunchIn(enabled)

    fun release() {
        runCatching { recorder?.stop() }
        recorder = null
        gyro.stop()
        controller?.close()
        controller = null
        gl.stop()
        started = false
        starting = false
        setupExecutor.shutdown()
        ioExecutor.shutdown()
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
