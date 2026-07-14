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
import com.hletrd.findx9tele.capture.StillSnapshot
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
    // URI (+ capture-sequence id) of the clip currently being recorded, so stop can surface it
    // (thumbnail/review) tagged like any other capture.
    @Volatile private var activeRecordingUri: android.net.Uri? = null
    @Volatile private var activeRecordingCaptureId = 0

    // These are written from the UI thread and read on the GL thread (via the onInputReady
    // continuation), so they are @Volatile to give the JMM a visibility edge across the seam.
    @Volatile private var selection: TeleSelection? = null
    @Volatile private var caps: CameraCaps? = null
    @Volatile private var videoSize = Size(1920, 1080)
    // The user's explicit resolution pick (from the Video tab or a settings restore); null = never
    // chosen, auto-derive. Kept separate from videoSize so reopen paths can re-validate it against
    // each lens's caps instead of blindly re-deriving the largest size over the user's choice.
    @Volatile private var requestedVideoSize: Size? = null
    // What the camera preview stream (the GL SurfaceTexture) is actually sized to. In VIDEO mode it
    // must equal [videoSize] (the SurfaceTexture feeds the encoder, and the HAL fixes the stream size
    // at session config). In PHOTO mode it is the largest 4:3 stream instead, so the viewfinder shows
    // the SAME field the still capture gets — a 16:9 preview stream crops the sensor vertically and
    // silently mis-frames every photo.
    @Volatile private var previewStreamSize = Size(1920, 1080)
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
    // Camera-health signal for the UI (dim the shutter, show a persistent OSD tag while down):
    // fires on every cameraReady flip. A silent scheduleCameraRecovery exhaustion previously left a
    // black viewfinder behind a fully interactive-looking shutter with zero indication.
    var onCameraReadyChange: ((Boolean) -> Unit)? = null

    // AF engine state for the reticle color, mapped from the controller's raw CONTROL_AF_STATE.
    var onAfIndication: ((AfIndication) -> Unit)? = null

    private fun updateCameraReady(ready: Boolean) {
        cameraReady = ready
        onCameraReadyChange?.invoke(ready)
    }
    @Volatile private var previewSurface: Surface? = null
    // Last-known preview surface dimensions, kept alongside previewSurface so the async start
    // continuation can bind the LIVE surface (not its captured, possibly-released parameter).
    @Volatile private var previewSurfaceW = 0
    @Volatile private var previewSurfaceH = 0

    // Static-per-device caches: camera characteristics and focal→id resolution never change at
    // runtime, but re-reading them cost dozens of Binder IPCs on EVERY lens/TC/mode switch — and
    // they used to run inside the close→open blackout window.
    private val capsCache = java.util.concurrent.ConcurrentHashMap<String, CameraCaps>()
    private val idForFocalCache = java.util.concurrent.ConcurrentHashMap<Float, String>()
    @Volatile private var cachedLogicalBackId: String? = null

    private fun cachedCaps(logicalId: String, physicalId: String?): CameraCaps? =
        runCatching {
            capsCache.getOrPut("$logicalId:$physicalId") { CameraCaps.read(manager, logicalId, physicalId) }
        }.getOrNull()

    private fun cachedIdForFocal(equivMm: Float): String? =
        idForFocalCache[equivMm]
            ?: CameraSelector2.overrideIdForFocal(manager, equivMm)?.also { idForFocalCache[equivMm] = it }

    private fun cachedLogicalBack(): String? =
        cachedLogicalBackId ?: CameraSelector2.logicalBackId(manager)?.also { cachedLogicalBackId = it }

    private val gyro = com.hletrd.findx9tele.stab.GyroEis(context)
    @Volatile private var teleconverterMode = false
    @Volatile private var videoMode = false
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
    // Displayed preview aspect (width/height AS SHOWN on the portrait screen — the ~90° sensor
    // orientation already swaps the stream's W/H). The UI sizes the TextureView to this so the
    // viewfinder letterboxes the FULL capture field instead of cover-cropping it: with a 16:9 stream
    // on this ~19.5:9 panel the old full-screen cover cut ~40% of the frame's width, and photo mode
    // additionally previewed a 16:9 field while capturing the full 4:3 sensor.
    var onPreviewAspect: ((Float) -> Unit)? = null
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
    // The most recently saved displayable media (HEIF/JPEG/video) URI + its capture-sequence id, for
    // the gallery thumbnail + in-app review. Fired from the io thread after the file publishes. The
    // id groups every output of one shutter press so review-Delete can remove the whole shot.
    var onMediaSaved: ((android.net.Uri, Int) -> Unit)? = null
    // The DNG sibling of a capture (never displayed itself), with the same capture-sequence id as
    // the HEIF/JPEG from that shutter press. Fired from the camera thread after the DNG publishes.
    var onRawSaved: ((android.net.Uri, Int) -> Unit)? = null

    // ---- Preview surface lifecycle ----

    fun onPreviewSurfaceAvailable(surface: Surface, width: Int, height: Int) {
        previewSurface = surface // published synchronously on the calling (main) thread
        previewSurfaceW = width
        previewSurfaceH = height
        if (started) { gl.setPreviewOutput(surface, width, height); return }
        if (starting) return // setup already dispatched; don't launch a duplicate start
        starting = true
        // Cold-start feedback: several Binder IPCs + openCamera run before the first frame — say
        // "working" so the black window is distinguishable from a hang (cleared by onReady).
        onStatus?.invoke("Starting camera…")

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
            // read() throws when the camera service is down for both id reads; without the guard the
            // uncaught throw on this executor thread kills the process on every cold-start hiccup.
            val c = runCatching { CameraCaps.read(manager, sel.logicalId, sel.physicalId) }.getOrNull()
            if (c == null) {
                onStatus?.invoke("Camera unavailable")
                starting = false
                return@execute
            }
            caps = c
            onCapsReady?.invoke(c)
            videoSize = chooseVideoSize(sel)
            onVideoSizeChosen?.invoke(videoSize)
            previewStreamSize = choosePreviewStreamSize(sel)
            emitPreviewAspect()

            // HLG10 10-bit preview + full-res JPEG/RAW crashes this HAL (configureStreams Broken pipe -32);
            // SDR preview session. 10-bit HDR preview deferred; video still tags HLG/Log in the encoder.
            val tenBit = false
            gl.start(tenBit) { input ->
                gl.setCameraPreviewSize(previewStreamSize.width, previewStreamSize.height)
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
            // Publish start state BEFORE binding: a TextureView surface destroyed+recreated during
            // the multi-IPC window above then takes the `started` fast path (which binds the fresh
            // surface itself) instead of being dropped by the `starting` guard.
            started = true
            // Clear the dispatch guard now that started gates re-entry. Leaving it true was harmless
            // only while nothing ever reset started; don't couple those invariants.
            starting = false
            // Bind the LIVE surface field, not the captured parameter: if the surface was destroyed
            // during setup, the captured Surface is already released and EGL-binding it would crash
            // the fresh GL thread (this app installs no UncaughtExceptionHandler). A destroyed-and-
            // not-yet-recreated surface simply skips the bind — the next surface callback binds.
            val liveSurface = previewSurface
            if (liveSurface != null) gl.setPreviewOutput(liveSurface, previewSurfaceW, previewSurfaceH)
        }
    }

    /** Debug-only Camera2 capability log, run off the GL thread so it never delays openCamera / first frame. */
    private fun maybeLogCameraCapabilities() {
        if (!com.hletrd.findx9tele.BuildConfig.DEBUG) return
        setupExecutor.execute { runCatching { VendorTagInspector.logAll(manager) } }
    }

    /** Apply afocal preview rotation and the selected HAL stabilization mode; keep GL EIS disabled. */
    private fun applyStabilization() {
        val c = caps ?: return
        // The camera SurfaceTexture transform already rotates the sampled image by the sensor
        // orientation, so the renderer only needs the afocal flip on top (see previewRotationDegrees).
        // It still needs the sensor orientation separately to pick the right preview ASPECT, because
        // that ~90° SurfaceTexture rotation swaps the displayed width/height.
        gl.setSensorOrientation(c.sensorOrientation)
        gl.setRotationDegrees(previewRotationDegrees())
        // App-side gyro EIS is disabled (unusable at 300 mm): the HAL video-stab modes own
        // stabilization, so GL EIS cannot double-warp. Push the resolved HAL mode to the live request.
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
        // TELE is a CAMERA SWITCH, not just the afocal flip: ON pins the standalone 3× (the
        // converter's host lens — digital-only zoom, session-key Hasselblad/zoom hints); OFF returns
        // to the logical seamless-zoom camera at the 3× preset so the framing carries over.
        setLens(LensChoice.TELE3X, enabled)
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
        previewSurfaceW = width
        previewSurfaceH = height
        val surface = previewSurface ?: return
        gl.setPreviewOutput(surface, width, height)
    }

    fun onPreviewSurfaceDestroyed() {
        // Portrait is locked, so this is app teardown; keep the GL context but drop the output.
        previewSurface = null
        gl.setPreviewOutput(null, 0, 0)
    }

    private fun wireController(): CameraController {
        val ctrl = CameraController(context)
        ctrl.onExposure = { iso, exp -> onExposureInfo?.invoke(iso, exp) }
        ctrl.onZoomResult = { rz -> gl.setHalZoom(rz) }
        ctrl.onFocusDistance = { d -> onFocusDistance?.invoke(d) }
        ctrl.onAfState = { hal -> onAfIndication?.invoke(AfIndication.fromHal(hal)) }
        return ctrl
    }

    private fun openCamera(input: Surface) {
        if (paused) return // don't grab the camera while backgrounded (queued GL continuation, etc.)
        val sel = selection ?: return
        val c = caps ?: return
        updateCameraReady(false)
        controller?.close() // idempotent: closes any prior controller so two never race for the device
        val ctrl = wireController()
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
            vendorLogMode = vendorLogMode.halValue,
            videoStabHalMode = c.videoStabControlMode(videoStabMode),
            teleconverterMode = teleconverterMode,
            pinAutoFps = videoMode,
            onReady = {
                // A superseded controller can report ready after a newer reopen already replaced it.
                if (controller === ctrl && !paused) {
                    updateCameraReady(true)
                    onStatus?.invoke(null)
                    cameraRecoveryAttempts = 0
                }
            },
            onError = {
                if (controller === ctrl) {
                    updateCameraReady(false)
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

    fun setVideoMode(enabled: Boolean) {
        if (videoMode == enabled) return
        videoMode = enabled
        controller?.setPinAutoFps(enabled)
        if (!started) return
        // The mode flip can change the CAMERA (photo=logical seamless, video=standalone — see
        // resolveNonTeleId) as well as the stream size. Remap the zoom between the unified
        // main-relative scale and the lens-local scale so the framing carries across, then re-home.
        setupExecutor.execute {
            if (!teleconverterMode) {
                val z = controls.zoomRatio
                if (enabled) {
                    val band = LensChoice.forZoom(z)
                    lensChoice = band
                    controls = controls.copy(zoomRatio = (z / band.zoomPreset).coerceAtLeast(1f))
                } else {
                    controls = controls.copy(
                        zoomRatio = (lensChoice.zoomPreset * controls.zoomRatio.coerceAtLeast(1f)).coerceIn(0.6f, 20f),
                    )
                }
            }
            val id = if (teleconverterMode) {
                cachedIdForFocal(LensChoice.TELE3X.targetEquivMm)
            } else {
                resolveNonTeleId(lensChoice)
            }
            if (id != null && (id != overrideId || controller == null)) {
                setCameraOverride(id) // refreshes caps, stream sizes, aspect, stabilization, reopens
            } else {
                // Same camera (e.g. TELE mode) — still recreate when the STREAM SIZE flips between
                // the photo 4:3 field and the recording stream (dimensions fix at configureStreams).
                val sel = selection ?: return@execute
                val next = choosePreviewStreamSize(sel)
                if (next != previewStreamSize) {
                    previewStreamSize = next
                    emitPreviewAspect()
                    gl.setCameraPreviewSize(next.width, next.height)
                    reopenForSession()
                }
            }
        }
    }

    /**
     * Selects the color transfer — the single source of truth. LOG = the GL-baked O-Log2 OETF (the
     * shipping path): the encoder receives the official curve and the preview renders it flat. The
     * device's native HAL log key (`com.oplus.log.video.mode`) is INERT for third-party Camera2
     * (device-settled 2026-07-09 — see the body comment), so [vendorLogMode] stays OFF; the dormant
     * native-log plumbing (pass-through + de-log shader) is kept only for a future
     * CameraUnit-authenticated scene-referred stream.
     *
     * Changing the OETF mid-recording would tag the file with the start transfer but bake a different
     * curve into the second half, so the GL curve is only pushed when idle (the field still updates
     * for the next recording; stopRecording/pause re-apply it). See docs/reviews record-pipeline #4.
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
        // The ViewModel calls this on EVERY control mutation (updateControls); the value only
        // actually changes on a mode transition. Skip the redundant GL-handler post (60-120/s of
        // no-op messages during a sustained gesture).
        if (aeMetering == enabled) return
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
        val sensor = viewTapToSensorPoint(nx, ny, c.sensorOrientation, teleconverterMode)
        controller?.setMeteringPoint(sensor.first, sensor.second)
        val loupe = viewTapToLoupeCenter(nx, ny, previewRotationDegrees())
        gl.setPunchInCenter(loupe.first, loupe.second)
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
        updateCameraReady(false)
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
        if (cameraRecoveryAttempts >= MAX_CAMERA_RECOVERY_ATTEMPTS) {
            // Recovery exhausted: say so instead of silently leaving a black viewfinder behind an
            // interactive-looking UI. cameraReady stays false, so the shutter is dimmed too; a
            // background/foreground cycle (resume()) retries with a fresh attempt budget.
            onStatus?.invoke("Camera failed — reopen the app")
            return
        }
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

    /** Still-photo center-crop aspect ratio; applies to HEIF only (see [saveHeifAsync]). W4_3 = no crop. */
    fun setAspectRatio(a: AspectRatio) { aspectRatio = a }

    /**
     * Selects the video capture resolution and recreates the Camera2 session so the producer stream,
     * SurfaceTexture buffer and encoder all agree on the same dimensions.
     */
    fun setVideoResolution(s: Size) {
        // Remember the user's pick so lens switches and the initial open don't silently re-derive
        // the largest size over it — chooseVideoSize honors this request whenever the current lens
        // still offers it (and falls back to auto when it doesn't, e.g. after an openGate aspect
        // flip or a lens without that mode).
        requestedVideoSize = s
        if (videoSize == s) return
        applyVideoSize(s)
    }

    private fun applyVideoSize(s: Size) {
        videoSize = s
        onVideoSizeChosen?.invoke(s)
        // In PHOTO mode the preview stream stays on the 4:3 full-field size — the new recording
        // size takes effect on the stream when the user switches to VIDEO (setVideoMode reopens).
        if (videoMode) {
            previewStreamSize = s
            emitPreviewAspect()
            gl.setCameraPreviewSize(s.width, s.height)
            // Output dimensions are part of Camera2's configured stream contract. Updating only the
            // SurfaceTexture default size leaves the producer on the old 16:9/4:3 stream.
            reopenForSession()
        }
    }

    /**
     * Switches to one of the four rear lenses — by default bundling teleconverter mode: the 3× lens
     * enables it (the afocal 180° flip; stabilization at 300 mm is the HAL's OIS+EIS via
     * [VideoStabMode], not app-side gyro warping), any other lens disables it — one action, one
     * camera reopen. [teleconverterOverride] lets a caller that already knows the desired
     * final teleconverter state (the settings-restore path, where "preserve lens" and "preserve TELE"
     * are independent toggles — see CameraViewModel.applyLoaded) set it in the SAME reopen instead of
     * bundling then immediately correcting it via a second [setTeleconverterMode] call, which would
     * otherwise reopen the camera twice in a row on launch. Resolves the [LensChoice]'s target focal to
     * a concrete (standalone-preferred) camera id so nothing is hardcoded. No-op mid-recording
     * (reconfiguring under the encoder corrupts the clip); the UI also gates it.
     */
    fun setLens(choice: LensChoice, teleconverterOverride: Boolean = false) {
        if (recorder != null) { onStatus?.invoke("Stop REC first"); return }
        lensChoice = choice
        // Resolve the camera id ON setupExecutor (dozen-plus Binder IPCs; also orders resolve→reopen).
        setupExecutor.execute {
            if (teleconverterOverride) {
                // Physical-converter shooting: pin the STANDALONE 3× camera — the converter clamps
                // onto that lens, so the logical camera's seamless switching would zoom right off it.
                // Digital 1–10× only, afocal flip on, RAW available (standalone id).
                val id = cachedIdForFocal(LensChoice.TELE3X.targetEquivMm)
                if (id == null) { onStatus?.invoke("3× unavailable"); return@execute }
                teleconverterMode = true
                controls = controls.copy(zoomRatio = 1f)
                if (overrideId != id || controller == null) setCameraOverride(id) else applyStabilization()
            } else {
                // Mode-split homes (see resolveNonTeleId): PHOTO picks are ZOOM PRESETS on the
                // logical seamless camera (no reopen); VIDEO picks reopen the matching STANDALONE
                // lens (lens-local zoom resets to 1×).
                val id = resolveNonTeleId(choice)
                if (id == null) { onStatus?.invoke("${choice.label} unavailable"); return@execute }
                teleconverterMode = false
                val targetZoom = if (videoMode) 1f else choice.zoomPreset
                if (overrideId != id || controller == null) {
                    controls = controls.copy(zoomRatio = targetZoom)
                    setCameraOverride(id)
                } else {
                    setZoomRatio(targetZoom)
                }
            }
        }
    }

    /**
     * The non-teleconverter camera id for the CURRENT capture mode. PHOTO lives on the LOGICAL
     * multicamera — seamless 0.6–20× pinch, stills device-verified clean. VIDEO pins the matching
     * STANDALONE lens: the logical camera's EIS (Standard AND Active) leaks its uncorrected warp
     * margin into the stream's left edge — device-verified 2026-07-14 in the preview AND the
     * RECORDED file — while the standalone cameras have shipped clean stabilized video all along.
     * Falls back to the closest standalone id on devices without a logical back camera.
     */
    private fun resolveNonTeleId(choice: LensChoice): String? =
        if (videoMode) {
            cachedIdForFocal(choice.targetEquivMm)
        } else {
            cachedLogicalBack()
                ?: cachedIdForFocal(choice.targetEquivMm)
        }

    // One-time "DNG: TELE mode only" notifier; re-armed whenever the camera changes.
    @Volatile private var dngUnavailableNotified = false

    fun setCameraOverride(id: String?) {
        // Switching the physical lens mid-recording reconfigures the camera under the encoder and
        // gaps/corrupts the clip. Refuse until recording stops; the UI also gates this.
        if (recorder != null) { onStatus?.invoke("Stop REC first"); return }
        overrideId = id
        dngUnavailableNotified = false
        if (!started) return
        val input = gl.inputSurface ?: return // @Volatile in GlPipeline: safe cross-thread read
        updateCameraReady(false)
        // Off the main thread (same reason as reopenForSession): close() blocks on the HAL device
        // release and select/read/openCamera are several Binder IPCs — on the UI thread this ANR-kills
        // the app under HAL contention. Runs on the single-thread setupExecutor so it serializes with
        // the initial open and other reopens.
        setupExecutor.execute {
            if (paused || recorder != null) return@execute
            // Resolve the id and read the characteristics BEFORE closing: these are dozens of
            // Binder IPCs (~100 ms uncached) that used to sit inside the close→open blackout —
            // the old camera keeps streaming while they run, shrinking the visible freeze.
            val sel = selectCurrentLens() ?: run { onStatus?.invoke("Camera ID unavailable"); return@execute }
            val c = cachedCaps(sel.logicalId, sel.physicalId)
                ?: run { onStatus?.invoke("Camera unavailable"); return@execute }
            if (paused || recorder != null) return@execute

            // DUAL-OPEN: open the NEXT device while the old camera keeps streaming (~120 ms of the
            // blackout overlapped away). The preview surface still belongs to the old session, so
            // the new session is deferred until the old controller closes. If the HAL refuses a
            // second open (shared physical sensor / max-cameras), fall back to the sequential path.
            // A concurrent eviction of the OLD device is harmless: `controller` is already the new
            // one, so the old error handler's identity guard no-ops and its close() self-runs.
            val old = controller
            val next = wireController()
            controller = next
            updateCameraReady(false)
            selection = sel
            caps = c
            onCapsReady?.invoke(c)
            videoSize = chooseVideoSize(sel)
            onVideoSizeChosen?.invoke(videoSize)
            previewStreamSize = choosePreviewStreamSize(sel)
            applyStabilization()

            val deviceUp = java.util.concurrent.CountDownLatch(1)
            val deviceOk = java.util.concurrent.atomic.AtomicBoolean(false)
            next.open(
                selection = sel,
                caps = c,
                glInputSurface = input,
                controls = controls,
                tenBitHlg = false, // SDR session — HLG10+RAW+JPEG crashes the HAL
                highSpeedFps = desiredHighSpeedFps(),
                vendorLogMode = vendorLogMode.halValue,
                videoStabHalMode = c.videoStabControlMode(videoStabMode),
                teleconverterMode = teleconverterMode,
                pinAutoFps = videoMode,
                deferSession = true,
                onDeviceOpened = { deviceOk.set(true); deviceUp.countDown() },
                onReady = {
                    if (controller === next && !paused) {
                        updateCameraReady(true)
                        onStatus?.invoke(null)
                        cameraRecoveryAttempts = 0
                    }
                },
                onError = {
                    deviceUp.countDown() // an open-phase failure also releases the latch (fallback)
                    if (controller === next) {
                        updateCameraReady(false)
                        onStatus?.invoke("Camera error: ${it.message}")
                        scheduleCameraRecovery()
                    }
                },
            )
            // The old camera streams through the new device's open. Bounded wait: a refusal or a
            // wedged open must degrade to the sequential path, not hang the setup thread.
            deviceUp.await(2, java.util.concurrent.TimeUnit.SECONDS)
            // Update the GL stream size only now — the old camera was still producing into the
            // shared SurfaceTexture; resizing it earlier would distort its final frames.
            emitPreviewAspect()
            gl.setCameraPreviewSize(previewStreamSize.width, previewStreamSize.height)
            old?.close()
            if (deviceOk.get()) {
                next.startDeferredSession()
            } else {
                // Sequential fallback: the HAL refused the concurrent open.
                next.close()
                if (controller === next) controller = null
                if (paused || recorder != null) return@execute
                openCamera(input)
            }
        }
    }

    private fun selectCurrentLens(): TeleSelection? {
        val id = overrideId
            ?: (if (!teleconverterMode) resolveNonTeleId(lensChoice) else null)
            ?: cachedIdForFocal(lensChoice.targetEquivMm)
            ?: return null
        return CameraSelector2.select(manager, id)
    }

    /**
     * Zoom-gesture smoothness: in low light the preview legitimately idles at 10-15 fps — the
     * app-side P loop parks exposure at up to 1/10 s (its handheld ceiling) and HAL-AE photo AUTO
     * uses the lowest fps floor for a brighter view. At that rate ANY zoom reads as jank, no matter
     * how smoothly the ratio is applied. While a gesture is live: pin the HAL-AE fps floor
     * (controller boost), and for the app-side P loop trade exposure time for ISO
     * BRIGHTNESS-NEUTRALLY — only as far as ISO headroom allows, floored at 1/30 s, so the image
     * never dims (in this trade the frame rate simply rises as far as physics permits). On release
     * the loop re-converges to its preferred program point on its own (≤0.35 stop/tick).
     */
    fun setZoomInteraction(active: Boolean) {
        zoomInteractionActive = active
        controller?.setSmoothPreviewBoost(active)
        if (!active) {
            // Gesture over: land the HAL on the EXACT requested ratio (mid-gesture submits are
            // throttled and aimed slightly wide); the GL compensation converges to 1 as the
            // matching results arrive.
            controller?.setZoomRatio(controls.zoomRatio)
        }
        // (The low-light frame-rate help lives in applyExposure's ALWAYS-on preview exposure cap —
        // an earlier gesture-scoped trade here mutated the real program values, so a still captured
        // right after a zoom inherited the traded short-exposure/high-ISO pair.)
    }

    /**
     * Zoom FAST PATH — pinch/dial ticks arrive at input rate, and routing them through the full
     * setControls → startPreview rebuild (request re-derivation, metering regions, AF-trigger arming,
     * new callback per tick) is what read as zoom lag/stutter. The controller instead mutates only
     * the zoom keys on its CACHED repeating builder and resubmits. [controls] stays the source of
     * truth so the next full rebuild carries the same value (no snap-back).
     */
    fun setZoomRatio(ratio: Float) {
        val r = caps?.zoomRatioRange
        val z = if (r != null) ratio.coerceIn(r.lower, r.upper) else ratio
        controls = controls.copy(zoomRatio = z)
        // The PREVIEW zooms instantly: GL crops the last frame to the requested ratio and
        // self-redraws (every setRepeatingRequest stalls this HAL's stream ~180 ms — measured —
        // so per-tick HAL submits made zoom read as ~5 fps no matter how smooth the input was).
        gl.setZoomTarget(z)
        // The HAL follows at a throttled pace. Mid-gesture it is aimed slightly WIDE
        // (÷ZOOM_GESTURE_MARGIN) so the GL crop has field for instant zoom-out too; the exact
        // value lands at gesture end (setZoomInteraction(false)).
        val now = android.os.SystemClock.uptimeMillis()
        val halTarget = if (zoomInteractionActive) {
            val wide = z / ZOOM_GESTURE_MARGIN
            if (r != null) wide.coerceIn(r.lower, r.upper) else wide
        } else z
        if (!zoomInteractionActive || now - lastHalZoomSubmitMs >= ZOOM_HAL_THROTTLE_MS) {
            lastHalZoomSubmitMs = now
            controller?.setZoomRatio(halTarget)
        }
    }

    @Volatile private var zoomInteractionActive = false
    @Volatile private var lastHalZoomSubmitMs = 0L

    // ---- Photo ----

    fun capturePhoto(formats: PhotoFormats) {
        // Same gate startRecording has: during a session-key reopen (cameraReady=false) the
        // controller guards make a capture attempt safe but silent — give the user the status
        // instead of a shutter press that does nothing.
        if (!cameraReady) {
            onStatus?.invoke("Camera reconfiguring")
            return
        }
        val ctrl = controller ?: return
        // RAW is standalone-only on this HAL (both routed AND plain-logical stills kill the camera
        // device — see sessionAttemptPlan). On the seamless-zoom logical camera the session has no
        // RAW stream, so a DNG-enabled format quietly degrades to the processed still, with a
        // one-time status so the user knows where their DNGs went.
        val effFormats = if (formats.dngRaw && !ctrl.hasRawStream) {
            if (!dngUnavailableNotified) {
                dngUnavailableNotified = true
                onStatus?.invoke("DNG: TELE mode only")
            }
            formats.copy(dngRaw = false)
        } else formats
        when (driveMode) {
            DriveMode.SINGLE -> ctrl.capturePhoto(effFormats.wantsProcessedStill, effFormats.dngRaw, photoCallback(effFormats))
            DriveMode.BURST -> captureBurst(ctrl, effFormats)
            DriveMode.AEB -> captureAeb(ctrl, effFormats)
            DriveMode.TIMELAPSE -> startTimelapse(effFormats)
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
        val steps = if (range != null) aeCompAebSteps(range.lower, range.upper)
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
                // One id per shutter press, shared by every container it produces (HEIF/JPEG/DNG), so
                // the review UI can treat them as one shot (delete removes the DNG sibling too).
                val captureId = captureSeq.incrementAndGet()
                // Snapshot THIS shot's exposure from its own TotalCaptureResult: the deferred JPEG
                // EXIF stamp used to read controller.lastIso/lastExposureNs at write time, which a
                // settings change (or the next AEB step) can overwrite before the io thread runs.
                val shotIso = result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY) ?: 0
                val shotExpNs = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
                // HEIF and JPEG both come from the single still ImageReader (HAL JPEG on standalone
                // cameras, YUV on the logical one — see StillSnapshot). Snapshot the frame ONCE while
                // the Image is alive (cheap copy on the camera thread), then compress + fan out to
                // whichever containers are enabled on the io thread.
                if (formats.heif || formats.jpeg) {
                    if (jpeg != null) {
                        val snap = runCatching { StillSnapshot.from(jpeg) }.getOrNull()
                        if (snap != null) {
                            ioExecutor.execute {
                                val bytes = runCatching { snap.jpegBytes() }.getOrNull()
                                if (bytes == null) {
                                    onStatus?.invoke("Failed to save photo")
                                    return@execute
                                }
                                if (formats.heif) saveHeifAsync(bytes, rotation, captureId)
                                if (formats.jpeg) saveJpegAsync(bytes, rotation, shotIso, shotExpNs, captureId)
                            }
                        } else onStatus?.invoke("Failed to save photo")
                    } else onStatus?.invoke("Failed to save photo: no still image")
                }
                if (formats.dngRaw) {
                    if (raw != null) {
                        // DngCreator needs the live raw Image → must stay synchronous in this callback.
                        runCatching { saveDng(raw, rawChars, result, rotation, captureId) }
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
    private fun saveHeifAsync(bytes: ByteArray, rotationDegrees: Int, captureId: Int) {
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
            // The Setup quality slider governs BOTH still containers: HEIF here and the JPEG
            // re-encode in saveJpegAsync (it used to silently apply only to JPEG, leaving the
            // DEFAULT photo format pinned at the encoder's 95).
            val quality = controls.jpegQuality.coerceIn(1, 100)
            val wrote = MediaStoreWriter.openParcelFd(context, u, "rw")?.use { pfd ->
                HeifCapture.writeHeif(pfd.fileDescriptor, r, quality); true
            } ?: false
            if (!wrote) { MediaStoreWriter.delete(context, u); onStatus?.invoke("Failed to save HEIF"); return }
            if (!MediaStoreWriter.publish(context, u)) {
                MediaStoreWriter.delete(context, u)
                onStatus?.invoke("Failed to publish HEIF")
                return
            }
            onMediaSaved?.invoke(u, captureId)
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
    private fun saveJpegAsync(bytes: ByteArray, rotationDegrees: Int, shotIso: Int, shotExpNs: Long, captureId: Int) {
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
            runCatching { writeJpegExif(u, shotIso, shotExpNs) }
            if (!MediaStoreWriter.publish(context, u)) {
                MediaStoreWriter.delete(context, u)
                onStatus?.invoke("Failed to publish JPEG")
                return
            }
            onMediaSaved?.invoke(u, captureId)
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
    private fun saveDng(raw: Image, chars: CameraCharacteristics, result: TotalCaptureResult, rotationDegrees: Int, captureId: Int) {
        val uri = MediaStoreWriter.createPendingImage(context, fileName("IMG", "dng"), "image/x-adobe-dng")
            ?: throw IllegalStateException("Failed to create MediaStore entry")
        try {
            val out = MediaStoreWriter.openOutputStream(context, uri)
                ?: throw IllegalStateException("Failed to open output stream")
            out.use { DngCapture.writeDng(it, raw, chars, result, exifOrientationFor(rotationDegrees)) }
            if (!MediaStoreWriter.publish(context, uri)) {
                throw IllegalStateException("Failed to publish DNG")
            }
            onRawSaved?.invoke(uri, captureId)
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
        // A just-stopped clip's async teardown still owns the mic (and the encoder pipeline is being
        // finalized) — refuse until finishRecording completes rather than starting a second recorder
        // whose AudioRecord init would fail (silent video-only clip).
        if (recorderTeardownInFlight) {
            onStatus?.invoke("Finalizing previous clip")
            return false
        }
        // The recorder owns the mic — stop the standby level tap first and wait (bounded) for its
        // release CONFIRMATION, not just a timed thread join: the latch counts down only after
        // AudioRecord.release() ran, so the single-mic invariant holds whenever the wait succeeds.
        // Proceed-on-timeout stays (a wedged read must not make REC unresponsive); VideoRecorder's
        // STATE_UNINITIALIZED guard then degrades that rare overlap to a video-only clip.
        standbyMeterWanted = false
        standbyMeterReleased?.let { runCatching { it.await(400, java.util.concurrent.TimeUnit.MILLISECONDS) } }
        standbyMeterThread = null
        if (recorder != null) return false
        val name = fileName("VID", "mp4")
        val uri = MediaStoreWriter.createPendingVideo(context, name, "video/mp4") ?: return false
        activeRecordingUri = uri
        activeRecordingCaptureId = captureSeq.incrementAndGet()
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
        val uri = activeRecordingUri
        val captureId = activeRecordingCaptureId
        activeRecordingUri = null
        recorderTeardownInFlight = true
        // ORDERED teardown: finishRecording (rec.stop() → codec release; joins the drain threads up
        // to seconds, so it stays OFF the main thread on ioExecutor) dispatches from the completion
        // callback, which runs on the GL thread only AFTER the encoder EGL surface is actually
        // cleared. The old fire-and-forget post had no happens-before edge with the independent
        // ioExecutor — a queued drawFrame could still makeCurrent() the encoder surface while the
        // codec that owns it was being released (uncaught EGL failure on the GL thread).
        gl.setEncoderOutput(null, 0, 0) {
            ioExecutor.execute { finishRecording(rec, uri, captureId) }
        }
        // Restore the preview curve startRecording overrode: an AVC recording pushes null (SDR) into
        // GL and nothing else re-applies the LOG/HLG preview render until the next transfer change.
        gl.setTransfer(transfer)
    }

    // True from stop/pause dispatching the async rec.stop() until its AudioRecord is actually
    // released: `recorder == null` alone says nothing about the mic, and the standby meter starting
    // in that window would violate the one-AudioRecord invariant (its init would fail, or worse,
    // steal the route from the finalizing clip).
    @Volatile private var recorderTeardownInFlight = false

    private fun finishRecording(rec: VideoRecorder, uri: android.net.Uri?, captureId: Int) {
        try {
            val result = runCatching { rec.stop() }
                .getOrElse { VideoRecorder.StopResult(saved = false, error = it) }
            if (result.saved && uri != null) {
                // Surface only a fully finalized, published clip to the review UI.
                onMediaSaved?.invoke(uri, captureId)
                onStatus?.invoke("Video saved")
            } else {
                onStatus?.invoke("Video save failed")
            }
        } finally {
            recorderTeardownInFlight = false
        }
    }

    /** Releases the camera + gyro for backgrounding without tearing down the GL pipeline or start state. */
    fun pause() {
        paused = true
        updateCameraReady(false)
        // A backgrounded timelapse can't capture anyway (controller is nulled below, so every tick
        // no-ops) — stop it outright rather than silently resuming mid-sequence with a gap.
        stopTimelapse()
        // Finalize an in-flight recording OFF the main thread: rec.stop() joins the drain threads (up
        // to a few seconds) and calling it inline on onStop risks an ANR. Clear the encoder EGL first
        // so GL stops drawing into the input surface before the codec releases it.
        val rec = recorder
        recorder = null
        val pausedClipUri = activeRecordingUri
        val pausedClipCaptureId = activeRecordingCaptureId
        activeRecordingUri = null
        if (rec != null) {
            recorderTeardownInFlight = true
            // Same ordered teardown as stopRecording: release the codec only after the GL thread
            // confirmed the encoder EGL surface is cleared.
            gl.setEncoderOutput(null, 0, 0) {
                ioExecutor.execute { finishRecording(rec, pausedClipUri, pausedClipCaptureId) }
            }
            gl.setTransfer(transfer) // restore the preview curve startRecording overrode (AVC → null)
        }
        gyro.stop()
        // Close OFF the main thread: close() blocks until the HAL device releases (bounded 1.5 s
        // join — see CameraController.close), and onStop already competes with other teardown work
        // inside the ANR budget. setupExecutor serializes this close ahead of any queued/subsequent
        // reopen, so ordering is preserved.
        val ctrl = controller
        controller = null
        if (ctrl != null) setupExecutor.execute { ctrl.close() }
    }

    /** Reopens the camera after [pause], reusing the existing GL input surface and start state. */
    fun resume() {
        paused = false
        gyro.start()
        // Serialized on setupExecutor like every other open path (the GL-start continuation,
        // reopenForSession, setCameraOverride). resume() used to call openCamera directly on the
        // main thread — the one remaining unserialized open: it raced a queued reopen's
        // `controller = null` + open (both threads could observe null and double-open, contending
        // for the HAL device) and paid the open's Binder IPCs on the UI thread.
        setupExecutor.execute {
            if (paused) return@execute
            val input = gl.inputSurface ?: return@execute
            if (controller != null) return@execute
            openCamera(input)
        }
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
        updateCameraReady(false)
        stopTimelapse()
        // ACCEPTED main-thread teardown: onCleared() means the process is going away — the
        // rec.stop() drain joins (up to ~3 s each, wedge-guarded) are tolerated here because there
        // is no user-visible session left to jank, and dispatching to ioExecutor would race the
        // executor shutdown below. pause() normally ran first, so recorder is almost always null.
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

    /** Returns the largest [ratioW]:[ratioH] rect centered within [src], cropped out of it (HEIF + JPEG paths). */
    private fun centerCrop(src: Bitmap, ratioW: Int, ratioH: Int): Bitmap {
        val (x, y, cropW, cropH) = centerCropBox(src.width, src.height, ratioW, ratioH)
        return Bitmap.createBitmap(src, x, y, cropW, cropH)
    }

    /**
     * The default recording size for the current lens: the largest matching-aspect SurfaceTexture
     * size the camera reports — 4:3 when [openGate] (full sensor readout), else 16:9. Capped at 3840
     * wide (this device's cameras top out at 4K/4096 for the recording surface; no camera exposes 8K
     * video here). Falls back through the caps lists so it never returns an unsupported size.
     */
    private fun chooseVideoSize(sel: TeleSelection): Size {
        val auto = chooseStreamSize(sel, fourByThree = openGate)
        val req = requestedVideoSize ?: return auto
        // Honor a user/restored pick only while the current caps actually offer it for the active
        // aspect; anything else (stale persisted size, openGate flip) degrades to the auto choice.
        val list = caps?.let { if (openGate) it.openGateVideoSizes else it.availableVideoSizes }
        return if (list != null && req in list) req else auto
    }

    /**
     * The camera preview (SurfaceTexture) stream size. VIDEO mode: identical to [videoSize] — the
     * SurfaceTexture feeds the encoder, so the two must agree. PHOTO mode: the largest 4:3 stream,
     * so the viewfinder previews the SAME full-sensor field the still capture gets (a 16:9 stream
     * crops the sensor vertically and mis-frames every photo).
     */
    private fun choosePreviewStreamSize(sel: TeleSelection): Size =
        if (videoMode) chooseVideoSize(sel) else chooseStreamSize(sel, fourByThree = true)

    /** Pushes the DISPLAYED preview aspect (post sensor-orientation W/H swap) to the UI. */
    private fun emitPreviewAspect() {
        val s = previewStreamSize
        // The ~90° sensor orientation means the SurfaceTexture transform swaps the shown W/H (the
        // afocal 180° doesn't change the swap). Same rule FlipRenderer uses for its aspect choice.
        val swapped = ((caps?.sensorOrientation ?: 90) % 180) == 90
        val aspect = if (swapped) s.height.toFloat() / s.width else s.width.toFloat() / s.height
        onPreviewAspect?.invoke(aspect)
    }

    private fun chooseStreamSize(sel: TeleSelection, fourByThree: Boolean): Size {
        val c = caps
        if (c != null) {
            val list = if (fourByThree) c.openGateVideoSizes else c.availableVideoSizes
            (list.firstOrNull { it.width <= 3840 } ?: list.firstOrNull())?.let { return it }
        }
        // Fallback: query directly (pre-caps or empty list).
        val chars = runCatching {
            manager.getCameraCharacteristics(sel.physicalId ?: sel.logicalId)
        }.getOrNull() ?: return Size(1920, 1080)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(1920, 1080)
        val sizes = map.getOutputSizes(SurfaceTexture::class.java) ?: return Size(1920, 1080)
        // One selection rule with CameraCaps.read's lists (pickStreamSize) — the fallback used to
        // re-implement the aspect/cap/largest rule inline, so a policy change had to touch both.
        return pickStreamSize(sizes.map { it.width to it.height }, capWidth = 3840, fourByThree = fourByThree)
            ?.let { (w, h) -> Size(w, h) }
            ?: Size(1920, 1080)
    }

    // Resolved encoder bitrate (bits/s) for the current level, size, true frame rate, and codec.
    // effectiveBpp scales the level up for the all-intra APV codec.
    private fun bitRateFor(size: Size, rate: VideoFrameRate): Int =
        videoBitRate(size.width, size.height, rate.encoderRate, effectiveBpp(bitrateLevel, videoCodec), videoCodec)

    // Monotonic per-session counter so rapid captures (BURST/AEB/timelapse) that land within the same
    // second — or even millisecond — never collide on filename and overwrite/duplicate each other.
    private val fileSeq = java.util.concurrent.atomic.AtomicInteger(0)

    // Per-SHUTTER-PRESS counter (one id shared by every container a capture produces), so the review
    // UI can group the HEIF/JPEG with its DNG sibling. Distinct from fileSeq, which is per FILE.
    private val captureSeq = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Stamps ISO / exposure / 35mm focal / device EXIF onto a just-written JPEG (pending, rw).
     * [iso]/[expNs] come from the shot's own TotalCaptureResult, snapshotted in the capture
     * callback — the live controller.lastIso/lastExposureNs may already describe a LATER frame by
     * the time this runs on the io thread.
     */
    private fun writeJpegExif(uri: android.net.Uri, iso: Int, expNs: Long) {
        MediaStoreWriter.openParcelFd(context, uri, "rw")?.use { pfd ->
            val exif = androidx.exifinterface.media.ExifInterface(pfd.fileDescriptor)
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
    // Counts down only after the meter thread's AudioRecord is actually RELEASED (every exit path).
    // startRecording awaits this instead of a bare Thread.join: a timed join is a wait bound, not
    // an exit guarantee — on timeout it proceeded into VideoRecorder's own AudioRecord init while
    // the standby tap could still hold the mic (a brief two-AudioRecord overlap).
    @Volatile private var standbyMeterReleased: java.util.concurrent.CountDownLatch? = null

    fun setStandbyAudioMonitor(enabled: Boolean) {
        standbyMeterWanted = enabled
        if (!enabled) return
        if (standbyMeterThread?.isAlive == true) return
        // Also refuse while a stopped recording's async teardown still owns the mic — recorder is
        // already null then, but its AudioRecord releases only when finishRecording completes.
        if (recorder != null || recorderTeardownInFlight) return
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val released = java.util.concurrent.CountDownLatch(1)
        standbyMeterReleased = released
        val t = Thread({
            try {
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
                    // 32768 = signed 16-bit full scale, matching VideoRecorder's recording meter so
                    // the level bar doesn't step at the standby→recording handoff.
                    val rms = kotlin.math.sqrt(sum / n) / 32768.0
                    onAudioLevel?.invoke((rms * audioGain).toFloat().coerceIn(0f, 1f))
                }
                runCatching { rec.stop() }
                rec.release()
                onAudioLevel?.invoke(0f)
            } finally {
                // Signal "mic fully released" on EVERY exit path (incl. the early return@Thread
                // bails, where no AudioRecord was ever held).
                released.countDown()
            }
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
        // Exposure floor while a zoom gesture is live (1/30 s → ≥30 fps preview when ISO headroom allows).
        private const val ZOOM_SMOOTH_EXPOSURE_NS = 33_333_333L

        // Live-zoom HAL pacing: every setRepeatingRequest swap stalls this HAL's preview ~180 ms
        // (measured 2026-07-14), so mid-gesture submits are spaced at least this far apart — the
        // GL compensation renders the requested zoom in between.
        private const val ZOOM_HAL_THROTTLE_MS = 200L

        // Mid-gesture the HAL is aimed this factor WIDER than requested so the GL crop has spare
        // field in BOTH directions (instant zoom-out within the margin). Converged at gesture end.
        private const val ZOOM_GESTURE_MARGIN = 1.2f

        // Number of frames fired for a single BURST drive-mode shutter press.
        const val BURST_COUNT = 5
        // Auto-recovery from a mid-session camera HAL error/disconnect (see scheduleCameraRecovery).
        const val MAX_CAMERA_RECOVERY_ATTEMPTS = 3
        const val CAMERA_RECOVERY_DELAY_MS = 1000L
    }
}

/** The largest ratioW:ratioH rect centered within srcW×srcH. */
internal data class CropBox(val x: Int, val y: Int, val w: Int, val h: Int)

/**
 * Pure rect math behind the still-photo aspect crop, extracted so the 4:3/16:9 gating is
 * unit-testable without a Bitmap (an unmocked android.jar stub on the JVM).
 */
/**
 * AUTO-exposure AEB compensation steps: a -2/0/+2 EV bracket clamped to the camera's EV range.
 * distinct() so a narrow range that clamps two steps to the same value doesn't fire duplicate
 * identical frames (a 1-shot "bracket"). Pure and top-level like [centerCropBox] so the clamp and
 * dedupe are unit-testable (the MANUAL branch's sibling, manualAebExposuresNs, already is).
 */
internal fun aeCompAebSteps(lower: Int, upper: Int): List<Int> =
    listOf(-2, 0, 2).map { it.coerceIn(lower, upper) }.distinct()

internal fun centerCropBox(srcW: Int, srcH: Int, ratioW: Int, ratioH: Int): CropBox {
    val heightForFullWidth = srcW * ratioH / ratioW
    val (cropW, cropH) = if (heightForFullWidth <= srcH) {
        srcW to heightForFullWidth
    } else {
        (srcH * ratioW / ratioH) to srcH
    }
    return CropBox((srcW - cropW) / 2, (srcH - cropH) / 2, cropW, cropH)
}

/**
 * View-normalized tap [(nx,ny), origin top-left] → RAW-SENSOR-normalized metering point. The tapped
 * point is in VIEW space; metering regions are in RAW-SENSOR space, so the centered tap is rotated
 * by -(sensor orientation + the teleconverter's afocal 180°) and re-centered — NOT by
 * previewRotationDegrees (which is only the afocal part the renderer adds). NOTE: this ignores the
 * EIS/punch-in crop and offset, so the mapping is APPROXIMATE and needs on-device calibration — the
 * axis signs (and possibly a horizontal mirror) may need flipping once validated. Pure and
 * top-level so the CURRENT behavior is pinned by tests: any future calibration "fix" must land as
 * an intentional test change, not a silent sign flip.
 */
internal fun viewTapToSensorPoint(
    nx: Float,
    ny: Float,
    sensorOrientationDeg: Int,
    afocal180: Boolean,
): Pair<Float, Float> {
    val total = ((sensorOrientationDeg + if (afocal180) 180 else 0) % 360 + 360) % 360
    val px = nx - 0.5f
    val py = ny - 0.5f
    val rad = Math.toRadians(-total.toDouble())
    val cos = Math.cos(rad).toFloat()
    val sin = Math.sin(rad).toFloat()
    val rx = px * cos - py * sin
    val ry = px * sin + py * cos
    return (rx + 0.5f).coerceIn(0f, 1f) to (ry + 0.5f).coerceIn(0f, 1f)
}

/**
 * View tap → punch-in loupe center. The renderer rotates texcoords by previewRotationDegrees (the
 * afocal 180° only — sensor orientation lives in the SurfaceTexture matrix, which the loupe center
 * passes through unchanged). View space is y-DOWN while the texcoord/NDC space is y-UP, so the
 * vertical tap offset is flipped before the content rotation, then re-centered. (Verified on
 * device: without the y-flip a top tap sent the loupe to the bottom half.)
 */
internal fun viewTapToLoupeCenter(nx: Float, ny: Float, previewRotationDegrees: Int): Pair<Float, Float> {
    val rad = Math.toRadians(previewRotationDegrees.toDouble())
    val cos = Math.cos(rad).toFloat()
    val sin = Math.sin(rad).toFloat()
    val ax = nx - 0.5f
    val ay = -(ny - 0.5f)
    val lx = ax * cos - ay * sin
    val ly = ax * sin + ay * cos
    return (lx + 0.5f).coerceIn(0f, 1f) to (ly + 0.5f).coerceIn(0f, 1f)
}
