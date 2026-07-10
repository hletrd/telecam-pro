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
    // URI of the clip currently being recorded, so stop can surface it (thumbnail/review).
    @Volatile private var activeRecordingUri: android.net.Uri? = null

    // These are written from the UI thread and read on the GL thread (via the onInputReady
    // continuation), so they are @Volatile to give the JMM a visibility edge across the seam.
    @Volatile private var selection: TeleSelection? = null
    @Volatile private var caps: CameraCaps? = null
    @Volatile private var videoSize = Size(1920, 1080)
    @Volatile private var videoCodec: VideoCodec = VideoCodec.HEVC
    @Volatile private var bitrateLevel: BitrateLevel = BitrateLevel.ULTRA
    @Volatile private var videoFrameRate: VideoFrameRate = VideoFrameRate.DEFAULT
    @Volatile private var openGate = false
    @Volatile private var driveMode: DriveMode = DriveMode.SINGLE
    @Volatile private var intervalSec: Int = 5
    @Volatile private var controls = ManualControls()
    @Volatile private var transfer = ColorTransfer.HLG
    @Volatile private var lensChoice: LensChoice = LensChoice.MAIN
    @Volatile private var overrideId: String? = null
    @Volatile private var started = false
    // Set true synchronously on the calling thread once startup setup is dispatched, so a second
    // onPreviewSurfaceAvailable arriving before setup completes doesn't launch a duplicate start.
    @Volatile private var starting = false
    // True between pause() and resume(). openCamera() honors it so a camera open queued during
    // startup (the GL onInputReady continuation) doesn't fire while the app is backgrounded — e.g.
    // launched behind the keyguard, where onStop lands right as the session would configure.
    @Volatile private var paused = false
    // True only after the CURRENT controller has configured a working session. Session-key and lens
    // changes clear it before their asynchronous reopen is queued so REC cannot race the teardown.
    @Volatile private var cameraReady = false
    @Volatile private var previewSurface: Surface? = null

    private val gyro = com.hletrd.findx9tele.stab.GyroEis(context)
    @Volatile private var teleconverterMode = false
    // HAL-native log (vendor com.oplus.log.video.mode). Session key → changing it reopens the camera.
    @Volatile private var vendorLogMode = VendorLogMode.OFF
    // Video stabilization strategy. Default ENHANCED = HAL OIS+EIS (motion-blur reduction at 300 mm).
    @Volatile private var videoStabMode = VideoStabMode.ENHANCED
    // Bounded auto-recovery when the camera HAL disconnects/errors mid-session (its provider process can
    // crash). Reset on a successful open so the viewfinder self-heals instead of sitting black.
    @Volatile private var cameraRecoveryAttempts = 0

    // Software recording-audio gain (1f = passthrough) and the still-photo aspect-ratio crop;
    // read from the audio-encode / io-executor threads, so both are @Volatile.
    @Volatile private var audioGain = 1f
    @Volatile private var audioScene = AudioScene.STANDARD
    @Volatile private var audioInputPreference = AudioInputPreference.AUTO
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
    // Actual AudioRecord route once recording starts, e.g. "USB · DJI Mic Mini".
    var onAudioRoute: ((String) -> Unit)? = null
    // AE-resolved ISO/shutter (auto mode) from the controller, for the live dial readout. Fired from
    // the camera thread, only on change; the ViewModel hoists it into UI state.
    var onExposureInfo: ((iso: Int?, exposureNs: Long?) -> Unit)? = null
    // Live lens focus distance (diopters) from the controller: the AF-resolved position shown on the
    // Focus chip and used to seed the manual slider when the user switches into MF (AF→MF handoff).
    var onFocusDistance: ((Float) -> Unit)? = null
    // The most recently saved still (HEIF/JPEG) URI, for the gallery thumbnail + in-app review. Fired
    // from the io thread after the file publishes.
    var onMediaSaved: ((android.net.Uri) -> Unit)? = null

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
            val sel = selectCurrentLens()
            if (sel == null) {
                onStatus?.invoke("Camera unavailable")
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
                // Re-seed GL state that may have been set BEFORE the GL thread existed:
                // GlPipeline.post is handler?.post, so pre-start calls (e.g. a LOG transfer or a
                // priority-mode AE metering flag restored from settings) were silently dropped —
                // the LOG preview only turned flat after the first recording pushed the transfer.
                gl.setNativeLog(false)
                gl.setTransfer(transfer)
                gl.setAeMetering(aeMetering)
                gl.setGammaAssist(gammaAssist)
                applyStabilization()
                gyro.start()
                maybeLogCameraCapabilities()
                openCamera(input)
            }
            gl.setPreviewOutput(surface, width, height)
            started = true
        }
    }

    /** Debug-only Camera2 capability log, run off the GL thread so it never delays openCamera / first frame. */
    private fun maybeLogCameraCapabilities() {
        if (!com.hletrd.findx9tele.BuildConfig.DEBUG) return
        setupExecutor.execute { runCatching { VendorTagInspector.logAll(manager) } }
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
        // App-side gyro EIS was removed (unusable at 300 mm): the HAL video-stab modes own
        // stabilization now, so GL EIS stays permanently disabled — it can never double-warp. Push
        // the resolved HAL control mode to the live request.
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
        teleconverterMode = enabled
        applyStabilization()
        // The afocal 180° preview flip can update live, but OPPO's available stabilization hints
        // (`com.oplus.camera.mode` + effective `original.zoomRatio`) are session keys. Reopen while
        // idle so the HAL chooses the OIS/EIS profile with the 300 mm context, matching the
        // CameraUnit path as closely as Camera2 allows.
        reopenForSession()
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
        cameraReady = false
        controller?.close() // idempotent: closes any prior controller so two never race for the device
        val ctrl = CameraController(context)
        controller = ctrl
        ctrl.onExposure = { iso, exp -> onExposureInfo?.invoke(iso, exp) }
        ctrl.onFocusDistance = { d -> onFocusDistance?.invoke(d) }
        ctrl.open(
            selection = sel,
            caps = c,
            glInputSurface = input,
            controls = controls,
            tenBitHlg = false, // SDR session — HLG10+RAW+JPEG crashes the HAL
            // >0 → build a CameraConstrainedHighSpeedCaptureSession at this fps (no JPEG/RAW). 0 keeps
            // the regular tele session with full still capture. Falls back to regular on config failure.
            highSpeedFps = desiredHighSpeedFps(),
            vendorLogMode = vendorLogMode.halValue,
            videoStabHalMode = c.videoStabControlMode(videoStabMode),
            teleconverterMode = teleconverterMode,
            onReady = {
                // A superseded controller can report ready after a newer reopen already replaced it.
                if (controller === ctrl && !paused) {
                    cameraReady = true
                    onStatus?.invoke(null)
                    cameraRecoveryAttempts = 0
                }
            },
            onError = {
                if (controller === ctrl) {
                    cameraReady = false
                    onStatus?.invoke("Camera error: ${it.message}")
                    scheduleCameraRecovery()
                }
            },
        )
    }

    // ---- Controls ----

    fun setControls(c: ManualControls) {
        controls = c
        controller?.updateControls(c)
    }

    /**
     * Selects the color transfer — the single source of truth. LOG now drives the device's NATIVE
     * HAL log (the vendor `com.oplus.log.video.mode` session key): the ISP emits a genuinely flat,
     * scene-referred log stream, so the GL stage must PASS IT THROUGH rather than bake a second
     * curve. Because it is a SESSION parameter, engaging/dropping native log reopens the camera
     * ([reopenForSession] is guarded — a no-op mid-recording / before start, so the current clip's
     * pipeline is never reconfigured underneath it). HLG/SDR keep the GL OETF path and turn native
     * log off. [vendorLogMode] is a private field derived from [transfer]; there is no public setter.
     *
     * Changing the OETF mid-recording would tag the file with the start transfer but bake a different
     * curve into the second half, so the GL curve is only pushed when idle (the field still updates
     * for the next recording). See docs/reviews record-pipeline #4.
     */
    fun setTransfer(t: ColorTransfer) {
        transfer = t
        // LOG = the GL O-Log2 OETF (proven path). The native com.oplus.log.video.mode key was tried
        // twice and is effectively INERT for a third-party Camera2 session: the HAL accepts it
        // ("applied" logs) but neither the preview nor the RECORDED stream is scene-referred —
        // device-tested 2026-07-09 with TEMPLATE_PREVIEW and TEMPLATE_RECORD repeating requests (the
        // clip came out plain 709; the earlier "file looked log" was the BT.2020 full-range container
        // tag being misread by players as a washed look). So the GL path stays: encoder gets the
        // official O-Log2 curve, the preview shows it flat, and Gamma Display Assist shows the normal
        // display-referred image instead. vendorLogMode stays OFF (dormant, with the de-log shader,
        // for a future CameraUnit-authenticated scene-referred path).
        gl.setNativeLog(false)
        val wasNativeLog = vendorLogMode != VendorLogMode.OFF
        vendorLogMode = VendorLogMode.OFF
        if (wasNativeLog) reopenForSession()
        if (recorder == null) gl.setTransfer(t)
    }
    fun setPeaking(enabled: Boolean) = gl.setPeaking(enabled)
    fun setZebra(enabled: Boolean) = gl.setZebra(enabled)

    // Adjustable peaking (sensitivity + color) and zebra (%): threshold and color are combined into
    // one GL call, so either change re-applies both from the current level/color.
    @Volatile private var peakingLevel = PeakingLevel.MEDIUM
    @Volatile private var peakingColor = PeakingColor.MAGENTA
    private fun applyPeaking() = gl.setPeakingParams(peakingLevel.threshold, peakingColor.r, peakingColor.g, peakingColor.b)
    fun setPeakingLevel(l: PeakingLevel) { peakingLevel = l; applyPeaking() }
    fun setPeakingColor(c: PeakingColor) { peakingColor = c; applyPeaking() }
    fun setZebraLevel(z: ZebraLevel) = gl.setZebraThreshold(z.threshold)

    /** Enables/disables GL-thread histogram and/or waveform computation feeding [onAnalysis]. */
    fun setAnalysis(histogram: Boolean, waveform: Boolean) = gl.setAnalysisEnabled(histogram, waveform)

    /** Forces the luma readback for the app-side auto-exposure loop (SHUTTER/ISO priority). */
    fun setAeMetering(enabled: Boolean) {
        aeMetering = enabled
        gl.setAeMetering(enabled)
    }

    /** Gamma Display Assist: normal monitor image while recording O-Log (the file stays log). */
    fun setGammaAssist(enabled: Boolean) {
        gammaAssist = enabled
        gl.setGammaAssist(enabled)
    }

    @Volatile private var gammaAssist = false

    /** AWB gains of the latest frame, for custom (grey-card) WB capture. Null until 3A runs. */
    fun currentAwbGains(): WbGains? =
        controller?.lastAwbGains?.let { WbGains(it.red, it.greenEven, it.greenOdd, it.blue) }

    // Remembered so the GL-start callback can re-seed it: setAeMetering can arrive from settings
    // restore BEFORE the GL thread exists, where GlPipeline.post silently drops it.
    @Volatile private var aeMetering = false

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

        // Movable focus loupe: point the punch-in zoom at the tapped spot. The renderer rotates
        // texcoords by previewRotationDegrees (the afocal 180° only — sensor orientation lives in the
        // SurfaceTexture matrix, which the loupe center passes through unchanged). View space is
        // y-DOWN while the texcoord/NDC the renderer works in is y-UP, so the vertical tap offset is
        // flipped before applying the content rotation; then re-centered. (Verified on device: without
        // the y-flip a top tap sent the loupe to the bottom half.)
        val loupeRad = Math.toRadians(previewRotationDegrees().toDouble())
        val lcos = Math.cos(loupeRad).toFloat()
        val lsin = Math.sin(loupeRad).toFloat()
        val ax = px
        val ay = -py
        val lx = ax * lcos - ay * lsin
        val ly = ax * lsin + ay * lcos
        gl.setPunchInCenter((lx + 0.5f).coerceIn(0f, 1f), (ly + 0.5f).coerceIn(0f, 1f))
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
        // Open Gate changes the recorded aspect/size; refuse mid-recording (the encoder is fixed-size).
        if (recorder != null) { onStatus?.invoke("Stop REC first"); return }
        openGate = enabled
        val sel = selection ?: return
        applyVideoSize(chooseVideoSize(sel))
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
        // Never tear the camera down under an active recording — it strands the encoder input surface
        // and gaps/corrupts the clip. The rate/open-gate change applies on the next recording instead.
        if (recorder != null) return
        val input = gl.inputSurface ?: return
        cameraReady = false
        // Run the close+reopen OFF the main thread. close() blocks until the HAL device is released
        // (bounded join) and openCamera() issues several getCameraCharacteristics/openCamera Binder
        // calls; on this HAL those can take seconds under contention, so doing it on the UI thread
        // (vendor-feature toggles are main-thread ViewModel calls) exceeds the 5s ANR watchdog and the
        // OS kills the app. setupExecutor is single-threaded, so reopens also serialize cleanly.
        setupExecutor.execute {
            // REC may have started after this reopen was queued. Never close a live recording stream.
            if (paused || recorder != null) return@execute
            controller?.close()
            controller = null
            if (paused || recorder != null) return@execute
            openCamera(input)
        }
    }

    /**
     * The camera HAL reported an error/disconnect mid-session — its provider process can crash (e.g. a
     * vendor key destabilizes it), which otherwise leaves a black CAMERA_DISCONNECTED viewfinder until
     * the user backgrounds/foregrounds the app. Auto-reopen a bounded number of times, after a short
     * delay so the provider has time to restart. The attempt counter resets on the next successful open.
     */
    private fun scheduleCameraRecovery() {
        if (!started || paused || recorder != null) return
        if (cameraRecoveryAttempts >= MAX_CAMERA_RECOVERY_ATTEMPTS) return
        cameraRecoveryAttempts++
        runCatching {
            timelapseScheduler.schedule(
                { reopenForSession() }, CAMERA_RECOVERY_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS,
            )
        }
    }

    /** Software gain applied to recorded PCM audio (1f = passthrough); takes effect on the next [startRecording]. */
    fun setAudioGain(g: Float) { audioGain = g }
    /** Directional-audio scene (Sound Focus/Stage); applies on the next [startRecording]. */
    fun setAudioScene(s: AudioScene) { audioScene = s }
    /** Preferred recording input; resolved against connected AudioDeviceInfo entries at record start. */
    fun setAudioInputPreference(p: AudioInputPreference) { audioInputPreference = p }

    /** Still-photo center-crop aspect ratio; applies to HEIF only (see [saveHeifAsync]). FULL = no crop. */
    fun setAspectRatio(a: AspectRatio) { aspectRatio = a }

    /**
     * Selects the video capture resolution and recreates the Camera2 session so the producer stream,
     * SurfaceTexture buffer and encoder all agree on the same dimensions.
     */
    fun setVideoResolution(s: Size) {
        if (videoSize == s) return
        applyVideoSize(s)
    }

    private fun applyVideoSize(s: Size) {
        videoSize = s
        onVideoSizeChosen?.invoke(s)
        gl.setCameraPreviewSize(s.width, s.height)
        // Output dimensions are part of Camera2's configured stream contract. Updating only the
        // SurfaceTexture default size leaves the producer on the old 16:9/4:3 stream.
        reopenForSession()
    }

    /**
     * Switches to one of the four rear lenses — by default bundling teleconverter mode: the 3× lens
     * enables it (afocal 180° flip + gyro-EIS scaled to ~300 mm), any other lens disables it — one
     * action, one camera reopen. [teleconverterOverride] lets a caller that already knows the desired
     * final teleconverter state (the settings-restore path, where "preserve lens" and "preserve TELE"
     * are independent toggles — see CameraViewModel.applyLoaded) set it in the SAME reopen instead of
     * bundling then immediately correcting it via a second [setTeleconverterMode] call, which would
     * otherwise reopen the camera twice in a row on launch. Resolves the [LensChoice]'s target focal to
     * a concrete (standalone-preferred) camera id so nothing is hardcoded. No-op mid-recording
     * (reconfiguring under the encoder corrupts the clip); the UI also gates it.
     */
    fun setLens(choice: LensChoice, teleconverterOverride: Boolean = choice.isTeleconverterLens) {
        if (recorder != null) { onStatus?.invoke("Stop REC first"); return }
        lensChoice = choice
        val id = CameraSelector2.overrideIdForFocal(manager, choice.targetEquivMm)
        if (id == null) { onStatus?.invoke("${choice.label} unavailable"); return }
        // Set the teleconverter flag BEFORE the reopen so applyStabilization() inside setCameraOverride
        // picks the right rotation + EIS focal for the new lens in the same pass.
        teleconverterMode = teleconverterOverride
        setCameraOverride(id)
    }

    fun setCameraOverride(id: String?) {
        // Switching the physical lens mid-recording reconfigures the camera under the encoder and
        // gaps/corrupts the clip. Refuse until recording stops; the UI also gates this.
        if (recorder != null) { onStatus?.invoke("Stop REC first"); return }
        overrideId = id
        if (!started) return
        val input = gl.inputSurface ?: return // @Volatile in GlPipeline: safe cross-thread read
        cameraReady = false
        // Off the main thread (same reason as reopenForSession): close() blocks on the HAL device
        // release and select/read/openCamera are several Binder IPCs — on the UI thread this ANR-kills
        // the app under HAL contention. Runs on the single-thread setupExecutor so it serializes with
        // the initial open and other reopens.
        setupExecutor.execute {
            if (paused || recorder != null) return@execute
            controller?.close()
            controller = null
            if (paused || recorder != null) return@execute
            val sel = selectCurrentLens() ?: run { onStatus?.invoke("Camera ID unavailable"); return@execute }
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
    }

    private fun selectCurrentLens(): TeleSelection? {
        val id = overrideId ?: (CameraSelector2.overrideIdForFocal(manager, lensChoice.targetEquivMm) ?: return null)
        return CameraSelector2.select(manager, id)
    }

    // ---- Photo ----

    fun capturePhoto(formats: PhotoFormats) {
        val ctrl = controller ?: return
        when (driveMode) {
            DriveMode.SINGLE -> ctrl.capturePhoto(formats.wantsProcessedStill, formats.dngRaw, photoCallback(formats))
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
            ctrl.capturePhoto(formats.wantsProcessedStill, formats.dngRaw, photoCallback(formats) { fire(shot + 1) })
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
    private fun captureAeb(ctrl: CameraController, formats: PhotoFormats) {
        val c = caps
        val original = controls
        if (!original.autoExposure && c?.supportsManualSensor == true) {
            val base = original.effectiveExposureNs()
            val range = c.exposureTimeRange
            val steps = manualAebExposuresNs(base, range?.lower ?: base, range?.upper ?: base)
            fun fire(i: Int) {
                if (i >= steps.size) { ctrl.updateControls(original); return }
                // SPEED override so the bracketed time applies even when the user dials ANGLE.
                ctrl.updateControls(original.copy(shutterMode = ShutterMode.SPEED, exposureTimeNs = steps[i]))
                ctrl.capturePhoto(formats.wantsProcessedStill, formats.dngRaw, photoCallback(formats) { fire(i + 1) })
            }
            fire(0)
            return
        }
        val range = c?.evRange
        // distinct() so a narrow EV range that clamps -2/0/+2 to the same value doesn't fire duplicate
        // identical frames (a 1-shot "bracket").
        val steps = if (range != null) listOf(-2, 0, 2).map { it.coerceIn(range.lower, range.upper) }.distinct()
        else listOf(original.exposureCompensation)
        fun fire(i: Int) {
            if (i >= steps.size) { ctrl.updateControls(original); return }
            ctrl.updateControls(original.copy(exposureCompensation = steps[i]))
            ctrl.capturePhoto(formats.wantsProcessedStill, formats.dngRaw, photoCallback(formats) { fire(i + 1) })
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
            controller?.capturePhoto(formats.wantsProcessedStill, formats.dngRaw, photoCallback(formats))
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
                if (!formats.heif && !formats.jpeg && !formats.dngRaw) onStatus?.invoke("No output selected")
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
            if (!MediaStoreWriter.publish(context, u)) {
                MediaStoreWriter.delete(context, u)
                onStatus?.invoke("Failed to publish HEIF")
                return
            }
            onMediaSaved?.invoke(u)
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
            // Bitmap.compress strips all metadata, so stamp the exposure EXIF back before publishing
            // (best-effort — a failed EXIF write must never lose the image itself).
            runCatching { writeJpegExif(u) }
            if (!MediaStoreWriter.publish(context, u)) {
                MediaStoreWriter.delete(context, u)
                onStatus?.invoke("Failed to publish JPEG")
                return
            }
            onMediaSaved?.invoke(u)
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
            if (!MediaStoreWriter.publish(context, uri)) {
                throw IllegalStateException("Failed to publish DNG")
            }
        } catch (t: Throwable) {
            MediaStoreWriter.delete(context, uri)
            throw t
        }
    }

    // ---- Video ----

    fun startRecording(recordAudio: Boolean): Boolean {
        if (!cameraReady) {
            onStatus?.invoke("Camera reconfiguring")
            return false
        }
        // The recorder owns the mic — stop the standby level tap first (its loop also self-exits on
        // recorder != null, but be explicit and give it a beat to release the AudioRecord).
        standbyMeterWanted = false
        standbyMeterThread?.let { runCatching { it.join(150) } }
        standbyMeterThread = null
        if (recorder != null) return false
        val name = fileName("VID", "mp4")
        val uri = MediaStoreWriter.createPendingVideo(context, name, "video/mp4") ?: return false
        activeRecordingUri = uri
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
        // File color tags: when the HAL emits O-Log2 the container MUST be tagged as the LOG profile
        // (BT.2020 full-range, SDR-class transfer) regardless of the TF chip, so players don't
        // HDR-tone-map it and OPPO's O-Log2 LUTs round-trip. Otherwise use the selected transfer on
        // the 10-bit paths (HEVC/APV); AVC is always SDR.
        val fileTransfer = when {
            vendorLogMode != VendorLogMode.OFF -> ColorTransfer.LOG
            codec == VideoCodec.HEVC || codec == VideoCodec.APV -> transfer
            else -> ColorTransfer.SDR
        }
        val rec = VideoRecorder(context)
        // Physical device orientation at record start → muxer rotation hint so a landscape-held clip
        // plays upright (GL only bakes the afocal 180°; see VideoRecorder.start).
        val orientationHint = gyro.currentDeviceOrientation()
        val surface = rec.start(
            uri, size, rate.encoderRate, captureRate, bitRateFor(size, rate),
            fileTransfer, codec, recordAudio, audioGain, orientationHint,
            audioScene, controls.zoomRatio, audioInputPreference,
            onRoute = { route -> onAudioRoute?.invoke(route) },
            onLevel = { lvl -> onAudioLevel?.invoke(lvl) },
        )
        if (surface == null) {
            // Encoder/muxer failed to configure; drop the pending MediaStore row we created so it
            // doesn't linger as a 0-byte orphan (VideoRecorder.start already released its own half).
            MediaStoreWriter.delete(context, uri)
            onStatus?.invoke("REC failed"); return false
        }
        gl.setTransfer(glTransfer)
        gl.setEncoderOutput(surface, size.width, size.height)
        recorder = rec
        return true
    }

    fun stopRecording() {
        val rec = recorder ?: return
        recorder = null
        gl.setEncoderOutput(null, 0, 0)
        val uri = activeRecordingUri
        activeRecordingUri = null
        // rec.stop() joins the video/audio drain threads (up to several seconds); run it OFF the caller
        // (main) thread to avoid an ANR. The file finalizes and the status fires from the io thread.
        ioExecutor.execute {
            finishRecording(rec, uri)
        }
    }

    private fun finishRecording(rec: VideoRecorder, uri: android.net.Uri?) {
        val result = runCatching { rec.stop() }
            .getOrElse { VideoRecorder.StopResult(saved = false, error = it) }
        if (result.saved && uri != null) {
            // Surface only a fully finalized, published clip to the review UI.
            onMediaSaved?.invoke(uri)
            onStatus?.invoke("Video saved")
        } else {
            onStatus?.invoke("Video save failed")
        }
    }

    /** Releases the camera + gyro for backgrounding without tearing down the GL pipeline or start state. */
    fun pause() {
        paused = true
        cameraReady = false
        // Finalize an in-flight recording OFF the main thread: rec.stop() joins the drain threads (up
        // to a few seconds) and calling it inline on onStop risks an ANR. Clear the encoder EGL first
        // so GL stops drawing into the input surface before the codec releases it.
        val rec = recorder
        recorder = null
        val pausedClipUri = activeRecordingUri
        activeRecordingUri = null
        if (rec != null) {
            gl.setEncoderOutput(null, 0, 0)
            ioExecutor.execute { finishRecording(rec, pausedClipUri) }
        }
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

    /**
     * Deletes leftover pending MediaStore entries from a prior crash / force-kill (a recording whose
     * MediaMuxer was never stopped → corrupt, invisible file). Runs on the setup thread so it never
     * blocks the UI. Call once on launch.
     */
    fun cleanupOrphans() {
        setupExecutor.execute { runCatching { MediaStoreWriter.cleanupOrphanedPending(context) } }
    }

    fun currentRollDegrees(): Float = gyro.currentRollDegrees()

    /** Discrete physical device orientation (0/90/180/270) from gravity, for auto-rotating overlays. */
    fun currentDeviceOrientation(): Int = gyro.currentDeviceOrientation()

    fun setPunchIn(enabled: Boolean) = gl.setPunchIn(enabled)

    fun release() {
        cameraReady = false
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
    // effectiveBpp scales the level up for the all-intra APV codec.
    private fun bitRateFor(size: Size, rate: VideoFrameRate): Int =
        videoBitRate(size.width, size.height, rate.encoderRate, effectiveBpp(bitrateLevel, videoCodec), videoCodec)

    // Monotonic per-session counter so rapid captures (BURST/AEB/timelapse) that land within the same
    // second — or even millisecond — never collide on filename and overwrite/duplicate each other.
    private val fileSeq = java.util.concurrent.atomic.AtomicInteger(0)

    /** Stamps ISO / exposure / 35mm focal / device EXIF onto a just-written JPEG (pending, rw). */
    private fun writeJpegExif(uri: android.net.Uri) {
        MediaStoreWriter.openParcelFd(context, uri, "rw")?.use { pfd ->
            val exif = androidx.exifinterface.media.ExifInterface(pfd.fileDescriptor)
            val iso = controller?.lastIso ?: 0
            val expNs = controller?.lastExposureNs ?: 0L
            if (iso > 0) exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, iso.toString())
            if (expNs > 0) {
                exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_TIME, (expNs / 1_000_000_000.0).toString())
            }
            val focal = caps?.equivalentFocalMm ?: 0f
            val eff = if (teleconverterMode) focal * TELECONVERTER_MAGNIFICATION else focal
            if (eff > 0f) {
                exif.setAttribute(
                    androidx.exifinterface.media.ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
                    Math.round(eff).toString(),
                )
            }
            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_MAKE, android.os.Build.MANUFACTURER)
            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_MODEL, android.os.Build.MODEL)
            exif.saveAttributes()
        }
    }

    // ---- Standby audio meter (Sony-style pre-roll level check) --------------------------------
    // A levels-only mic tap that feeds [onAudioLevel] while video mode is ARMED but not recording,
    // so input levels are visible before rolling. Stops itself the moment a real recording starts
    // (the recorder owns the mic) or the flag drops.
    @Volatile private var standbyMeterWanted = false
    private var standbyMeterThread: Thread? = null

    fun setStandbyAudioMonitor(enabled: Boolean) {
        standbyMeterWanted = enabled
        if (!enabled) return
        if (standbyMeterThread?.isAlive == true) return
        if (recorder != null) return
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val t = Thread({
            val sampleRate = 48_000
            val minBuf = android.media.AudioRecord.getMinBufferSize(
                sampleRate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) return@Thread
            val rec = runCatching {
                android.media.AudioRecord(
                    android.media.MediaRecorder.AudioSource.CAMCORDER, sampleRate,
                    android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, minBuf * 2,
                )
            }.getOrNull() ?: return@Thread
            if (rec.state != android.media.AudioRecord.STATE_INITIALIZED) { rec.release(); return@Thread }
            runCatching { rec.startRecording() }.onFailure { rec.release(); return@Thread }
            val buf = ShortArray(2048)
            var lastEmit = 0L
            while (standbyMeterWanted && recorder == null) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                val now = System.nanoTime()
                if (now - lastEmit < 100_000_000L) continue // ~10 Hz is plenty for a meter
                lastEmit = now
                var sum = 0.0
                for (i in 0 until n) { val v = buf[i].toDouble(); sum += v * v }
                val rms = kotlin.math.sqrt(sum / n) / 32767.0
                onAudioLevel?.invoke((rms * audioGain).toFloat().coerceIn(0f, 1f))
            }
            runCatching { rec.stop() }
            rec.release()
            onAudioLevel?.invoke(0f)
        }, "StandbyAudioMeter")
        standbyMeterThread = t
        t.start()
    }

    private fun fileName(prefix: String, ext: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val seq = fileSeq.getAndIncrement() % 1000
        // TELECAM = the app name (TeleCam Pro); files read IMG_TELECAM_… / VID_TELECAM_… in galleries.
        return "%s_TELECAM_%s_%03d.%s".format(prefix, stamp, seq, ext)
    }

    private companion object {
        // Number of frames fired for a single BURST drive-mode shutter press.
        const val BURST_COUNT = 5
        // 300mm / 70mm ≈ 4.286: the Explorer teleconverter's angular magnification.
        const val TELECONVERTER_MAGNIFICATION = 300f / 70f
        // Auto-recovery from a mid-session camera HAL error/disconnect (see scheduleCameraRecovery).
        const val MAX_CAMERA_RECOVERY_ATTEMPTS = 3
        const val CAMERA_RECOVERY_DELAY_MS = 1000L
    }
}
