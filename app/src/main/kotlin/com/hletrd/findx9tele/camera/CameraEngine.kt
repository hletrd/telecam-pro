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
    // Drives interval (timelapse) capture off the camera/UI threads.
    private val timelapseScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    @Volatile private var timelapseFuture: java.util.concurrent.ScheduledFuture<*>? = null
    @Volatile private var controller: CameraController? = null
    @Volatile private var recorder: VideoRecorder? = null

    // These are written from the UI thread and read on the GL thread (via the onInputReady
    // continuation), so they are @Volatile to give the JMM a visibility edge across the seam.
    @Volatile private var selection: TeleSelection? = null
    @Volatile private var caps: CameraCaps? = null
    @Volatile private var videoSize = Size(1920, 1080)
    @Volatile private var videoCodec: VideoCodec = VideoCodec.HEVC
    @Volatile private var bitrateLevel: BitrateLevel = BitrateLevel.MEDIUM
    @Volatile private var videoFrameRate: VideoFrameRate = VideoFrameRate.DEFAULT
    @Volatile private var openGate = false
    @Volatile private var driveMode: DriveMode = DriveMode.SINGLE
    @Volatile private var intervalSec: Int = 5
    @Volatile private var controls = ManualControls()
    @Volatile private var transfer = ColorTransfer.HLG
    @Volatile private var overrideId: String? = null
    @Volatile private var started = false
    // Set true synchronously on the calling thread once startup setup is dispatched, so a second
    // onPreviewSurfaceAvailable arriving before setup completes doesn't launch a duplicate start.
    @Volatile private var starting = false
    // True between pause() and resume(). openCamera() honors it so a camera open queued during
    // startup (the GL onInputReady continuation) doesn't fire while the app is backgrounded — e.g.
    // launched behind the keyguard, where onStop lands right as the session would configure.
    @Volatile private var paused = false
    @Volatile private var previewSurface: Surface? = null

    private val gyro = com.hletrd.findx9tele.stab.GyroEis(context)
    @Volatile private var teleconverterMode = true
    @Volatile private var eisEnabled = true
    @Volatile private var eisCrop: Float = EisStrength.MEDIUM.crop

    // Software recording-audio gain (1f = passthrough) and the still-photo aspect-ratio crop;
    // read from the audio-encode / io-executor threads, so both are @Volatile.
    @Volatile private var audioGain = 1f
    @Volatile private var aspectRatio = AspectRatio.W4_3

    var onStatus: ((String?) -> Unit)? = null
    var onCapsReady: ((CameraCaps) -> Unit)? = null
    // The auto-chosen video size for the selected lens (largest 16:9), so the UI's Video tab reflects
    // what the engine will actually encode instead of drifting from a hardcoded default.
    var onVideoSizeChosen: ((Size) -> Unit)? = null
    // Viewfinder analysis (histogram/waveform) computed on the GL thread; delivered here so the
    // ViewModel can hoist it into UI state. Either arg is null when its analysis is disabled.
    var onAnalysis: ((HistogramData?, WaveformData?) -> Unit)? = null
    // Live recording-audio level (0..1 RMS, post-gain), throttled by VideoRecorder to ~10 Hz.
    var onAudioLevel: ((Float) -> Unit)? = null

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
                onStatus?.invoke("Could not find the telephoto camera")
                starting = false
                return@execute
            }
            selection = sel
            val c = CameraCaps.read(manager, sel.logicalId, sel.physicalId)
            caps = c
            onCapsReady?.invoke(c)
            videoSize = chooseVideoSize(sel)
            onVideoSizeChosen?.invoke(videoSize)

            // HLG10 10-bit preview + full-res JPEG/RAW crashes this HAL (configureStreams Broken pipe -32);
            // SDR preview session. 10-bit HDR preview deferred; video still tags HLG/Log in the encoder.
            val tenBit = false
            gl.start(tenBit) { input ->
                gl.setCameraPreviewSize(videoSize.width, videoSize.height)
                gl.setEisProvider { gyro.currentCorrection() }
                gl.setAnalysisCallback { h, w -> onAnalysis?.invoke(h, w) }
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
        // The camera SurfaceTexture transform already rotates the sampled image by the sensor
        // orientation, so the renderer only needs the afocal flip on top (see previewRotationDegrees).
        // It still needs the sensor orientation separately to pick the right preview ASPECT, because
        // that ~90° SurfaceTexture rotation swaps the displayed width/height.
        gl.setSensorOrientation(c.sensorOrientation)
        gl.setRotationDegrees(previewRotationDegrees())
        val mag = if (teleconverterMode) TELECONVERTER_MAGNIFICATION else 1f
        gl.setEis(eisEnabled, c.nativeFocalInImageWidths * mag, eisCrop)
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

    fun setTeleconverterMode(enabled: Boolean) { teleconverterMode = enabled; applyStabilization() }
    fun setEisEnabled(enabled: Boolean) { eisEnabled = enabled; applyStabilization() }
    fun setEisStrength(s: EisStrength) { eisCrop = s.crop; applyStabilization() }
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
        if (paused) return // don't grab the camera while backgrounded (queued GL continuation, etc.)
        val sel = selection ?: return
        val c = caps ?: return
        controller?.close() // idempotent: closes any prior controller so two never race for the device
        val ctrl = CameraController(context)
        controller = ctrl
        ctrl.open(
            selection = sel,
            caps = c,
            glInputSurface = input,
            controls = controls,
            tenBitHlg = false, // SDR session — HLG10+RAW+JPEG crashes the HAL
            // >0 → build a CameraConstrainedHighSpeedCaptureSession at this fps (no JPEG/RAW). 0 keeps
            // the regular tele session with full still capture. Falls back to regular on config failure.
            highSpeedFps = desiredHighSpeedFps(),
            onReady = { onStatus?.invoke(null) },
            onError = { onStatus?.invoke("Camera error: ${it.message}") },
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

    /** Enables/disables GL-thread histogram and/or waveform computation feeding [onAnalysis]. */
    fun setAnalysis(histogram: Boolean, waveform: Boolean) = gl.setAnalysisEnabled(histogram, waveform)

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
        // The tapped point is in VIEW space; metering regions are in RAW-SENSOR space. Invert the full
        // sensor→view rotation = sensor orientation (from the SurfaceTexture transform) + the afocal
        // 180° — NOT previewRotationDegrees (which is only the afocal part the renderer adds).
        val total = ((c.sensorOrientation + if (teleconverterMode) 180 else 0) % 360 + 360) % 360
        val px = nx - 0.5f
        val py = ny - 0.5f
        val rad = Math.toRadians(-total.toDouble())
        val cos = Math.cos(rad).toFloat()
        val sin = Math.sin(rad).toFloat()
        val rx = px * cos - py * sin
        val ry = px * sin + py * cos
        controller?.setMeteringPoint((rx + 0.5f).coerceIn(0f, 1f), (ry + 0.5f).coerceIn(0f, 1f))
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
     * Selects the video frame rate. When this crosses the high-speed boundary (into or out of a
     * ≥120fps rate the current camera advertises for [videoSize]), the camera is reopened so the
     * session type — regular vs CameraConstrainedHighSpeedCaptureSession — matches. Purely changing
     * a normal rate just updates the encoder rate for the next recording, no reopen.
     */
    fun setVideoFrameRate(r: VideoFrameRate) {
        val before = desiredHighSpeedFps()
        videoFrameRate = r
        if (desiredHighSpeedFps() != before) reopenForSession()
    }

    /** Open Gate: record the full 4:3 sensor readout. Switches [videoSize] to a 4:3 size and re-picks it. */
    fun setOpenGate(enabled: Boolean) {
        if (openGate == enabled) return
        openGate = enabled
        val sel = selection ?: return
        videoSize = chooseVideoSize(sel)
        onVideoSizeChosen?.invoke(videoSize)
        gl.setCameraPreviewSize(videoSize.width, videoSize.height)
        if (desiredHighSpeedFps() != 0) reopenForSession()
    }

    /**
     * The high-speed fps the camera should run at right now (0 = regular session): non-zero only when
     * the selected rate is a high-speed one AND the current camera advertises a high-speed config for
     * [videoSize] at ≥ that fps. Keeps the tele's normal recording path untouched when nothing needs it.
     */
    private fun desiredHighSpeedFps(): Int {
        val c = caps ?: return 0
        val r = videoFrameRate
        return if (r.highSpeed && c.highSpeedFpsFor(videoSize) >= r.fps) r.fps else 0
    }

    /** Reopen the camera (if started) so the session type tracks [desiredHighSpeedFps]. */
    private fun reopenForSession() {
        if (!started || paused) return
        val input = gl.inputSurface ?: return
        controller?.close()
        controller = null
        openCamera(input)
    }

    /** Software gain applied to recorded PCM audio (1f = passthrough); takes effect on the next [startRecording]. */
    fun setAudioGain(g: Float) { audioGain = g }

    /** Still-photo center-crop aspect ratio; applies to HEIF only (see [saveHeifAsync]). FULL = no crop. */
    fun setAspectRatio(a: AspectRatio) { aspectRatio = a }

    /**
     * Selects the video capture resolution: updates [videoSize] and the GL camera-input size so the
     * encoder frame and preview aspect track the new size. NOTE: the live camera session keeps
     * streaming the old size; the change fully applies on the next camera (re)open or recording start.
     */
    fun setVideoResolution(s: Size) {
        videoSize = s
        gl.setCameraPreviewSize(s.width, s.height)
    }

    fun setCameraOverride(id: String?) {
        overrideId = id
        if (!started) return
        val input = gl.inputSurface ?: return // @Volatile in GlPipeline: safe cross-thread read
        controller?.close()
        val sel = CameraSelector2.select(manager, id) ?: run { onStatus?.invoke("Could not find that camera ID"); return }
        selection = sel
        val c = CameraCaps.read(manager, sel.logicalId, sel.physicalId)
        caps = c
        onCapsReady?.invoke(c)
        // The new lens can expose different output sizes; refresh videoSize + the GL camera size so
        // aspect (FlipRenderer "cover"), EIS focal scaling, and the encoder size match the new lens.
        videoSize = chooseVideoSize(sel)
        onVideoSizeChosen?.invoke(videoSize)
        gl.setCameraPreviewSize(videoSize.width, videoSize.height)
        applyStabilization()
        openCamera(input)
    }

    // ---- Photo ----

    fun capturePhoto(formats: PhotoFormats) {
        val ctrl = controller ?: return
        when (driveMode) {
            DriveMode.SINGLE -> ctrl.capturePhoto(formats.heif, formats.dngRaw, photoCallback(formats))
            DriveMode.BURST -> captureBurst(ctrl, formats)
            DriveMode.AEB -> captureAeb(ctrl, formats)
            DriveMode.TIMELAPSE -> startTimelapse(formats)
        }
    }

    /**
     * BURST: [BURST_COUNT] stills, each started only after the previous completes. The controller
     * tracks a single in-flight capture (one `pending` slot), so shots are chained rather than fired
     * in a tight loop, which would clobber that slot while a capture is still resolving its images.
     */
    private fun captureBurst(ctrl: CameraController, formats: PhotoFormats) {
        fun fire(shot: Int) {
            if (shot >= BURST_COUNT) return
            ctrl.capturePhoto(formats.heif, formats.dngRaw, photoCallback(formats) { fire(shot + 1) })
        }
        fire(0)
    }

    /**
     * AEB: three stills at exposure-compensation -2 / 0 / +2 steps (clamped to the AE-comp range),
     * applied by re-issuing the controls with a different [ManualControls.exposureCompensation] before
     * each shot; the original controls are restored when the bracket finishes. Chained for the same
     * single-`pending` reason as BURST.
     */
    private fun captureAeb(ctrl: CameraController, formats: PhotoFormats) {
        val range = caps?.evRange
        // distinct() so a narrow EV range that clamps -2/0/+2 to the same value doesn't fire duplicate
        // identical frames (a 1-shot "bracket").
        val steps = if (range != null) listOf(-2, 0, 2).map { it.coerceIn(range.lower, range.upper) }.distinct()
        else listOf(controls.exposureCompensation)
        fun fire(i: Int) {
            if (i >= steps.size) { ctrl.updateControls(controls); return }
            ctrl.updateControls(controls.copy(exposureCompensation = steps[i]))
            ctrl.capturePhoto(formats.heif, formats.dngRaw, photoCallback(formats) { fire(i + 1) })
        }
        fire(0)
    }

    /**
     * TIMELAPSE: repeats a single capture every [intervalSec] seconds on [timelapseScheduler] until
     * [stopTimelapse] (called on a drive-mode change away from TIMELAPSE, and in [release]).
     */
    private fun startTimelapse(formats: PhotoFormats) {
        stopTimelapse()
        val period = intervalSec.coerceAtLeast(1).toLong()
        timelapseFuture = timelapseScheduler.scheduleWithFixedDelay({
            controller?.capturePhoto(formats.heif, formats.dngRaw, photoCallback(formats))
        }, 0, period, java.util.concurrent.TimeUnit.SECONDS)
    }

    fun stopTimelapse() {
        timelapseFuture?.cancel(false)
        timelapseFuture = null
    }

    /**
     * Per-shot save callback (HEIF copied out then encoded async; DNG written synchronously while the
     * raw Image is alive). Runs on the camera thread; Images are valid ONLY for this call. [onDone]
     * chains the next shot in a BURST/AEB sequence (null for a single capture).
     */
    private fun photoCallback(formats: PhotoFormats, onDone: (() -> Unit)? = null) =
        object : CameraController.PhotoCallback {
            override fun onPhoto(jpeg: Image?, raw: Image?, result: TotalCaptureResult, rawChars: CameraCharacteristics) {
                // Snapshot the orientation once, at the moment of capture, so the deferred HEIF encode
                // and the synchronous DNG write agree on how the phone was held for this shot.
                val rotation = captureRotationDegrees()
                // HEIF and JPEG both come from the single JPEG ImageReader; copy the compressed bytes
                // out ONCE while the Image is alive, then fan out to whichever containers are enabled
                // off the camera thread (HEIF re-encodes; JPEG is written straight through).
                if (formats.heif || formats.jpeg) {
                    if (jpeg != null) {
                        val bytes = runCatching {
                            val buf = jpeg.planes[0].buffer
                            ByteArray(buf.remaining()).also { buf.get(it) }
                        }.getOrNull()
                        if (bytes != null) {
                            if (formats.heif) ioExecutor.execute { saveHeifAsync(bytes, rotation) }
                            if (formats.jpeg) ioExecutor.execute { saveJpegAsync(bytes, rotation) }
                        } else onStatus?.invoke("Failed to save photo")
                    } else onStatus?.invoke("Failed to save photo: no JPEG")
                }
                if (formats.dngRaw) {
                    if (raw != null) {
                        // DngCreator needs the live raw Image → must stay synchronous in this callback.
                        runCatching { saveDng(raw, rawChars, result, rotation) }
                            .onSuccess { onStatus?.invoke("DNG saved") }
                            .onFailure { onStatus?.invoke("Failed to save DNG: ${it.message}") }
                    } else onStatus?.invoke("Failed to save DNG: no RAW")
                }
                if (!formats.heif && !formats.jpeg && !formats.dngRaw) onStatus?.invoke("No output format selected")
                // HEIF success/failure is reported from inside saveHeifAsync (it runs later).
                onDone?.invoke()
            }
            override fun onError(t: Throwable) { onStatus?.invoke("Capture failed: ${t.message}"); onDone?.invoke() }
        }

    /**
     * Decode → center-crop to [aspectRatio] (HEIF only; [saveDng]'s RAW output always stays
     * full-frame) → rotate 180° → write HEIF, on [ioExecutor]. Publishes only on success; deletes
     * on any failure.
     */
    private fun saveHeifAsync(bytes: ByteArray, rotationDegrees: Int) {
        var decoded: Bitmap? = null
        var cropped: Bitmap? = null
        var rotated: Bitmap? = null
        var uri: android.net.Uri? = null
        try {
            val d = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (d == null) { onStatus?.invoke("Failed to save HEIF: decode failed"); return }
            decoded = d
            val ar = aspectRatio
            val base = if (ar != AspectRatio.W4_3) { // W4_3 = full sensor, no crop needed
                val c = centerCrop(d, ar.w, ar.h)
                cropped = c
                c
            } else d
            val r = rotateBitmap(base, rotationDegrees)
            rotated = r
            val u = MediaStoreWriter.createPendingImage(context, fileName("IMG", "heic"), "image/heic")
            if (u == null) { onStatus?.invoke("Failed to save HEIF"); return }
            uri = u
            val wrote = MediaStoreWriter.openParcelFd(context, u, "rw")?.use { pfd ->
                HeifCapture.writeHeif(pfd.fileDescriptor, r); true
            } ?: false
            if (!wrote) { MediaStoreWriter.delete(context, u); onStatus?.invoke("Failed to save HEIF"); return }
            MediaStoreWriter.publish(context, u)
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
    private fun saveJpegAsync(bytes: ByteArray, rotationDegrees: Int) {
        var decoded: Bitmap? = null
        var cropped: Bitmap? = null
        var rotated: Bitmap? = null
        var uri: android.net.Uri? = null
        try {
            val d = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (d == null) { onStatus?.invoke("Failed to save JPEG: decode failed"); return }
            decoded = d
            val ar = aspectRatio
            val base = if (ar != AspectRatio.W4_3) { // W4_3 = full sensor, no crop needed
                val c = centerCrop(d, ar.w, ar.h)
                cropped = c
                c
            } else d
            val r = rotateBitmap(base, rotationDegrees)
            rotated = r
            val u = MediaStoreWriter.createPendingImage(context, fileName("IMG", "jpg"), "image/jpeg")
            if (u == null) { onStatus?.invoke("Failed to save JPEG"); return }
            uri = u
            val quality = controls.jpegQuality.coerceIn(1, 100)
            val wrote = MediaStoreWriter.openOutputStream(context, u)?.use { out ->
                r.compress(Bitmap.CompressFormat.JPEG, quality, out); true
            } ?: false
            if (!wrote) { MediaStoreWriter.delete(context, u); onStatus?.invoke("Failed to save JPEG"); return }
            MediaStoreWriter.publish(context, u)
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
    private fun saveDng(raw: Image, chars: CameraCharacteristics, result: TotalCaptureResult, rotationDegrees: Int) {
        val uri = MediaStoreWriter.createPendingImage(context, fileName("IMG", "dng"), "image/x-adobe-dng")
            ?: throw IllegalStateException("Failed to create MediaStore entry")
        try {
            val out = MediaStoreWriter.openOutputStream(context, uri)
                ?: throw IllegalStateException("Failed to open output stream")
            out.use { DngCapture.writeDng(it, raw, chars, result, exifOrientationFor(rotationDegrees)) }
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
        var size = videoSize
        val codec = videoCodec
        var rate = videoFrameRate
        // AV1 here is the software encoder (no HW AV1 on this SoC): clamp to what it can sustain —
        // ≤1080p and ≤30 fps — so a mis-set higher size/rate degrades instead of failing to configure.
        if (codec == VideoCodec.AV1) {
            if (size.width > 1920) size = pickSizeUpTo(1920)
            if (rate.fps > 30) rate = VideoFrameRate.FPS_30
        }
        // High-speed (≥120 fps via the constrained session) tells the encoder it is fed faster than
        // real-time via KEY_CAPTURE_RATE; a regular clip leaves it 0.
        val captureRate = if (desiredHighSpeedFps() != 0) rate.encoderRate else 0.0
        // AVC and AV1 are 8-bit SDR only: force the GL color curve to SDR (no HLG/Log). HEVC keeps the
        // 10-bit HLG/Log path. (Resolution changes after this point stream the old size until reopen.)
        val glTransfer = if (codec == VideoCodec.HEVC) transfer else null
        val rec = VideoRecorder(context)
        // Physical device orientation at record start → muxer rotation hint so a landscape-held clip
        // plays upright (GL only bakes the afocal 180°; see VideoRecorder.start).
        val orientationHint = gyro.currentDeviceOrientation()
        val surface = rec.start(
            uri, size, rate.encoderRate, captureRate, bitRateFor(size, rate),
            transfer, codec, recordAudio, audioGain, orientationHint,
        ) { lvl -> onAudioLevel?.invoke(lvl) }
        if (surface == null) { onStatus?.invoke("Failed to start recording"); return false }
        gl.setTransfer(glTransfer)
        gl.setEncoderOutput(surface, size.width, size.height)
        recorder = rec
        return true
    }

    /** Largest reported 16:9 SurfaceTexture size no wider than [maxWidth]; used to clamp AV1 to ≤1080p. */
    private fun pickSizeUpTo(maxWidth: Int): Size =
        caps?.availableVideoSizes?.firstOrNull { it.width <= maxWidth } ?: Size(1920, 1080)

    fun stopRecording() {
        val rec = recorder ?: return
        recorder = null
        gl.setEncoderOutput(null, 0, 0)
        // rec.stop() joins the video/audio drain threads (up to several seconds); run it OFF the caller
        // (main) thread to avoid an ANR. The file finalizes and the status fires from the io thread.
        ioExecutor.execute {
            runCatching { rec.stop() }
            onStatus?.invoke("Video saved")
        }
    }

    /** Releases the camera + gyro for backgrounding without tearing down the GL pipeline or start state. */
    fun pause() {
        paused = true
        runCatching { recorder?.stop() }
        recorder = null
        gyro.stop()
        controller?.close()
        controller = null
    }

    /** Reopens the camera after [pause], reusing the existing GL input surface and start state. */
    fun resume() {
        paused = false
        val input = gl.inputSurface ?: return
        if (controller != null) return
        gyro.start()
        openCamera(input)
    }

    fun currentRollDegrees(): Float = gyro.currentRollDegrees()

    /** Discrete physical device orientation (0/90/180/270) from gravity, for auto-rotating overlays. */
    fun currentDeviceOrientation(): Int = gyro.currentDeviceOrientation()

    fun setPunchIn(enabled: Boolean) = gl.setPunchIn(enabled)

    fun release() {
        stopTimelapse()
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

    /** Returns the largest [ratioW]:[ratioH] rect centered within [src], cropped out of it. HEIF-only (see [saveHeifAsync]). */
    private fun centerCrop(src: Bitmap, ratioW: Int, ratioH: Int): Bitmap {
        val srcW = src.width
        val srcH = src.height
        val heightForFullWidth = srcW * ratioH / ratioW
        val (cropW, cropH) = if (heightForFullWidth <= srcH) {
            srcW to heightForFullWidth
        } else {
            (srcH * ratioW / ratioH) to srcH
        }
        val x = (srcW - cropW) / 2
        val y = (srcH - cropH) / 2
        return Bitmap.createBitmap(src, x, y, cropW, cropH)
    }

    /**
     * The default recording size for the current lens: the largest matching-aspect SurfaceTexture
     * size the camera reports — 4:3 when [openGate] (full sensor readout), else 16:9. Capped at 3840
     * wide (this device's cameras top out at 4K/4096 for the recording surface; no camera exposes 8K
     * video here). Falls back through the caps lists so it never returns an unsupported size.
     */
    private fun chooseVideoSize(sel: TeleSelection): Size {
        val c = caps
        if (c != null) {
            val list = if (openGate) c.openGateVideoSizes else c.availableVideoSizes
            (list.firstOrNull { it.width <= 3840 } ?: list.firstOrNull())?.let { return it }
        }
        // Fallback: query directly (pre-caps or empty list).
        val chars = runCatching {
            manager.getCameraCharacteristics(sel.physicalId ?: sel.logicalId)
        }.getOrNull() ?: return Size(1920, 1080)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(1920, 1080)
        val sizes = map.getOutputSizes(SurfaceTexture::class.java) ?: return Size(1920, 1080)
        val wantW = if (openGate) 4 else 16
        val wantH = if (openGate) 3 else 9
        return sizes.filter { it.width <= 3840 && it.height * wantW == it.width * wantH }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.maxByOrNull { it.width.toLong() * it.height }
            ?: Size(1920, 1080)
    }

    // Resolved encoder bitrate (bits/s) for the current level, size, true frame rate, and codec.
    private fun bitRateFor(size: Size, rate: VideoFrameRate): Int =
        videoBitRate(size.width, size.height, rate.encoderRate, bitrateLevel.bpp, videoCodec)

    // Monotonic per-session counter so rapid captures (BURST/AEB/timelapse) that land within the same
    // second — or even millisecond — never collide on filename and overwrite/duplicate each other.
    private val fileSeq = java.util.concurrent.atomic.AtomicInteger(0)

    private fun fileName(prefix: String, ext: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val seq = fileSeq.getAndIncrement() % 1000
        return "%s_X9TELE_%s_%03d.%s".format(prefix, stamp, seq, ext)
    }

    private companion object {
        // Number of frames fired for a single BURST drive-mode shutter press.
        const val BURST_COUNT = 5
        // 300mm / 70mm ≈ 4.286: the Explorer teleconverter's angular magnification.
        const val TELECONVERTER_MAGNIFICATION = 300f / 70f
    }
}
