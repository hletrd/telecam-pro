package me.hletrd.telecampro.gl

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import me.hletrd.telecampro.camera.ColorTransfer
import me.hletrd.telecampro.camera.PUNCH_IN_CROP
import me.hletrd.telecampro.camera.finderRect
import me.hletrd.telecampro.camera.loupeHintRect
import me.hletrd.telecampro.camera.FocusDetailData
import me.hletrd.telecampro.camera.MotionInversionData
import me.hletrd.telecampro.camera.RotationMath
import me.hletrd.telecampro.camera.HistogramData
import me.hletrd.telecampro.camera.WaveformData
import me.hletrd.telecampro.video.UnsafeRecorderQuarantine
import me.hletrd.telecampro.video.NativeAcquisitionRefusedException
import me.hletrd.telecampro.video.NativeAcquisitionRefusalPhase
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.hypot

/** A process-owned lease that can atomically reject an encoder EGL ownership commit. */
class EncoderOutputAdmission(
    private val validity: () -> Boolean,
    private val commitBlock: ((() -> Unit) -> Boolean),
) {
    fun isValid(): Boolean = validity()
    fun commit(block: () -> Unit): Boolean = commitBlock(block)
}

/** Installs a prepared native output only while its external admission lease still owns the commit. */
internal fun <T> installPreparedEncoderOutput(
    candidate: T,
    admission: EncoderOutputAdmission?,
    install: (T) -> Unit,
    discard: (T) -> Unit,
): Boolean {
    val installed = if (admission == null) {
        install(candidate)
        true
    } else {
        admission.commit { install(candidate) }
    }
    if (!installed) discard(candidate)
    return installed
}

/**
 * Owns the GL render thread. The camera renders into [inputSurface] (an external SurfaceTexture);
 * each frame is drawn once to the on-screen preview and, while recording, once more to the video
 * encoder's input surface — both 180°-flipped by [FlipRenderer]. Single EGL context, single thread.
 *
 * Lifecycle: [start] creates the context; the GL objects (texture + SurfaceTexture) are created when
 * the preview [setPreviewOutput] surface first arrives, at which point [onInputReady] fires so the
 * caller can open the camera against [inputSurface].
 */
class GlPipeline(
    /** Engine identity used to exclude a replacement graph while another Engine owns active REC. */
    private val nativeAcquisitionOwner: Any? = null,
    /** Deterministic test barrier between Engine advisory and the atomic native-entry gate. */
    private val beforeNativeAcquisition: (() -> Unit)? = null,
    /** Narrow test barrier for a healthy-context preview-window bind. */
    private val beforePreviewOutputNativeAcquisition: ((Any?) -> Unit)? = null,
) {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var resourceReleaseHub: ResourceReleaseHub? = null
    // Once a bounded stop abandons a still-live native thread, this object is terminal. A fresh
    // GlPipeline instance may replace it, but start() must never overwrite the fields that the old
    // SurfaceTexture listener and queued tasks still close over.
    @Volatile
    private var terminallyAbandoned = false
    private var egl: EglCore? = null
    private var eglStartRefusal: NativeAcquisitionRefusedException? = null
    private var unsafeOutputAbandoned = false
    private val renderer = FlipRenderer()

    private var surfaceTexture: SurfaceTexture? = null
    // SurfaceTexture itself posts one Handler message per producer notification. Keep those
    // callbacks cheap and collapse their expensive preview/encoder/analysis work to the newest
    // real frame. Zoom self-redraw remains a separate preview-only path.
    private var frameNotifications: FrameNotificationCoalescer? = null

    @Volatile
    var inputSurface: Surface? = null
        private set

    private var previewEgl: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderEgl: EGLSurface = EGL14.EGL_NO_SURFACE
    // Failed provisional-output cleanup retains its only EGL handle here. These handles are never
    // drawn; every checked detach/reset drains them before authorizing native producer teardown.
    private val orphanedEglOutputs = RetainedOutputs<EGLSurface>()
    private var encoderSignal: EncoderOutputSignal? = null
    private var previewSignal: PreviewOutputSignal? = null
    private var previewSurface: Surface? = null
    // Surface lifecycle callbacks can invalidate a native window before an older GL task runs.
    // Bump this synchronously on the caller thread so queued binds can reject stale ownership.
    private val previewOutputGeneration = AtomicLong(0L)

    private var previewW = 0
    private var previewH = 0
    private var encoderW = 0
    private var encoderH = 0
    private var cameraW = 1
    private var cameraH = 1

    // Video frames are timestamped with the camera/sensor clock (SurfaceTexture.getTimestamp), a large
    // boot-based value; audio is 0-based (sample counter). Muxing the two as-is offsets the video track
    // by ~boot-time from audio → broken A/V sync. Rebase the encoder presentation time to the first
    // recorded frame so both tracks start near 0 (see docs/reviews record-pipeline #9).
    private var encoderBaseNs = 0L
    private var encoderBaseSet = false

    private var transfer: ColorTransfer? = null
    // Front-route stream-mirror fact. ROUTE state like the rotation/sensor-orientation pair (NOT a
    // RendererAssists entry): CameraEngine.applyStabilization re-pushes it on every session
    // (re)config and rollback, which is also what re-seeds a replacement GL generation — the same
    // replay path setRotationDegrees rides, so the documented "posted before start() is dropped"
    // trap is covered without a config-store field.
    // DEVICE FACT (PMA110): the front HAL PRE-mirrors its SurfaceTexture stream. The preview
    // therefore draws WITHOUT any mirror of its own (the stream already shows the selfie-mirror
    // view), and the encoder/analysis draws apply the x-inversion instead so files and metering
    // keep the TRUE scene. Per-draw roles derive from FrontMirrorConvention (never restate them
    // as literals); see CameraEngine.applyStabilization for the diagnosis trail.
    private var frontRoute = false
    private var frontStreamPreMirrored = false
    private var gammaAssist = false
    private var peaking = false
    // Adjustable focus-peaking edge threshold + highlight color, and the zebra clipping threshold.
    private var peakThreshold = 0.06f
    private var peakR = 1f
    private var peakG = 0.1f
    private var peakB = 0.7f
    private var zebra = false
    private var zebraThreshold = 0.95f
    private var falseColor = false
    private var tenBit = false
    // Renderer texcoord rotation, mirrored for the loupe framing hint (see setRotationDegrees).
    private var previewRotationDeg = 0
    private var punchIn = false
    // Movable focus loupe: texcoord point the punch-in zoom magnifies (0.5,0.5 = frame center), set
    // from the tapped point so the loupe follows an off-center subject. Preview-only.
    private var punchInX = 0.5f
    private var finderFieldScale = 1f

    // Window rotation away from natural (0/90/180/270). Preview/finder draws only — see
    // setWindowRotation. 0 on every portrait-locked phone, which is what keeps PMA110 unchanged.
    private var windowRotationDeg = 0

    // Change gate for the finder-draw failure log; see its onFailure arm.
    private var lastFinderFailureSig: String? = null
    private var punchInY = 0.5f
    // TELE finder PIP: the RESOLVED enable flag (user toggle && TELE && 4:3, resolved by
    // CameraEngine.pushTeleFinder). The finder actually draws only when this is set AND the
    // punch-in loupe is active (checked in drawFrame — the same gate axis as the Compose border's
    // teleFinderVisible). It re-draws the FULL current camera frame — the widest field the single
    // stream carries, NOT an unzoomed view — which is only wider than the main view while the
    // loupe magnifies past it.
    private var teleFinder = false

    // Gyro EIS: provider returns [yaw, pitch, roll] shake radians; eisFocal scales to the effective
    // (teleconverter) focal length in image widths; eisCrop is the headroom (e.g. 0.10).
    private var eisEnabled = false
    private var eisFocal = 0f
    private var eisCrop = 0f
    private var eisProvider: (() -> FloatArray)? = null

    // Real-time scope analysis (histogram + waveform) via GL readback. The glReadPixels call runs on
    // the GL thread and is throttled; the per-pixel compute is dispatched to its generation-owned
    // executor so it never stalls rendering. Perf note: reading back at full preview resolution
    // every ~12th frame is a tradeoff (GPU->CPU stall + copy) that should be profiled on device.
    private var analysisHistogram = false
    private var analysisWaveform = false
    // Force the luma readback on (independent of the user's scope toggles) so the app-side
    // auto-exposure loop in SHUTTER/ISO-priority always has fresh luma to meter from.
    private var analysisAe = false
    // Frame-detail (focus-confidence) metric. Deliberately a RIDER, not a trigger: it is NOT in the
    // readback gate below, so it only ever computes over a snapshot some other consumer already
    // paid for. Enabling it must not add a single glReadPixels — the documented consequence is that
    // it is silent in the modes where no scope/AE readback runs at all (video-P, flash-metered P),
    // which is a MISS we accept rather than new per-frame GL work.
    private var analysisFocus = false
    // Motion-inversion rider. Like [analysisFocus] this NEVER forces a readback — it only decides
    // whether to compute over a snapshot some other consumer already paid for. Unlike it, this one
    // needs the PREVIOUS frame's luma too, so the generation carries a one-frame history.
    private var analysisMotion = false
    // Supplies the gyro rotation for the interval since the last analysis frame, drained by the GL
    // thread at snapshot time so the interval lines up with the frame pair rather than with whenever
    // the executor happens to run. Returns (yawRadians, pitchRadians).
    private var motionRotationProvider: ((Long, Long) -> FloatArray?)? = null
    // Published synchronously by setMotionInversionEnabled before its GL command is queued. An old
    // analysis task therefore sees an optics-door reset immediately and cannot publish or seed
    // history while the GL thread is still draining commands from the previous evidence epoch.
    private val motionEvidenceEpoch = AtomicLong(0L)
    private var analysisCallback: (
        (HistogramData?, WaveformData?, FocusDetailData?, MotionInversionData?) -> Unit
    )? = null
    // One immutable owner per GL generation. Buffer/FBO storage and the single-in-flight guard never
    // cross a restart, so a retired analysis task cannot read replacement pixels, publish stale AE,
    // or clear the replacement generation's guard from its finally block.
    private class AnalysisGeneration {
        val owner = AnalysisGenerationOwner()
        val executor = Executors.newSingleThreadExecutor()
        var frameCounter = 0
        var fbo = 0
        var texture = 0
        var buffer: ByteBuffer? = null
        var bytes: ByteArray? = null
        var bufferW = 0
        var bufferH = 0
        var textureW = 0
        var textureH = 0

        // One-frame luma history for the motion rider, GENERATION-owned so a GL restart cannot pair
        // a frame with one from before the discontinuity (different surface, different framing —
        // the displacement between them is meaningless). Two buffers swapped rather than copied.
        var motionPrev: IntArray? = null
        var motionCur: IntArray? = null
        @Volatile var motionW = 0
        @Volatile var motionH = 0
        // Sensor-clock timestamp of the retained frame, so the gyro can be integrated over exactly
        // the interval those two frames span rather than over whenever the readback happened to run.
        var motionPrevTsNs = 0L
        // False until a SECOND frame at the current size arrives; the first has no predecessor.
        //
        // @Volatile because this one flag genuinely crosses threads: the executor SETS it, the GL
        // thread READS it before dispatch and CLEARS it from [clearMotionHistory]. The busy gate
        // already orders the set/read pair (the executor's release and the GL thread's acquire are
        // a CAS pair, so the write is visible), but a clear racing an in-flight computation has no
        // such edge — and losing a clear is the one failure that matters, because it pairs frames
        // across the discontinuity the clear existed to mark.
        @Volatile var motionHasHistory = false
        @Volatile var motionHistoryEpoch = Long.MIN_VALUE

        fun retire() {
            owner.retire()
            executor.shutdown()
        }

        fun clearSnapshots() {
            buffer = null
            bytes = null
            bufferW = 0
            bufferH = 0
            clearMotionHistory()
        }

        /** Drops the retained frame; the next pair starts fresh. Cheap and always safe. */
        fun clearMotionHistory(evidenceEpoch: Long = Long.MIN_VALUE) {
            motionHasHistory = false
            motionPrevTsNs = 0L
            motionHistoryEpoch = evidenceEpoch
        }
    }

    @Volatile
    private var analysisGeneration: AnalysisGeneration? = null

    private var inited = false
    private val stMatrix = FloatArray(16)
    private var onInputReady: ((Surface) -> Unit)? = null

    fun start(tenBit: Boolean, onInputReady: (Surface) -> Unit) {
        // Double-start guard (CR-7). One GL generation owns the HandlerThread + EGL context +
        // analysis executor/FBO/gates, and stop() is the ONLY thing that retires that owner (it
        // nulls `thread` once its GL thread has quit+joined). Re-entering start() while a generation
        // is live used to overwrite thread/handler/resourceReleaseHub and post a fresh EglCore,
        // orphaning the previous thread, EglCore (context/display/surfaces), surfaceTexture/
        // inputSurface, and analysis executor with no teardown — a leaked live GL context per
        // re-entry. Contract: a second start() before a completed stop() is a safe no-op; the live
        // generation (and its original tenBit/onInputReady) keeps running. The sole caller
        // (CameraEngine.onPreviewSurfaceAvailable) already serializes this behind started/starting/
        // paused, and the one legitimate restart door (stop → afterResourcesReleased →
        // onPreviewSurfaceAvailable) re-dispatches on the single-threaded setupExecutor only AFTER
        // stop() has blocked-joined the GL thread and nulled `thread`, so this guard never refuses a
        // real restart — it only rejects an unbalanced re-entry that would leak the prior generation.
        if (thread != null || terminallyAbandoned) return
        this.tenBit = tenBit
        this.onInputReady = onInputReady
        analysisGeneration?.retire()
        analysisGeneration = AnalysisGeneration()
        val t = HandlerThread("gl-pipeline").also { it.start() }
        thread = t
        resourceReleaseHub = ResourceReleaseHub()
        unsafeOutputAbandoned = false
        eglStartRefusal = null
        handler = Handler(t.looper)
        post {
            // EGL init containment (CR-3). EglCore's constructor throws on any eglInitialize/
            // eglChooseConfig/eglCreateContext failure; an uncaught throw inside this Handler
            // Runnable dies on the GL HandlerThread → default uncaught handler → process death, and
            // the initialized EGLDisplay leaks. Contain it like every other GL failure in this file:
            // leave `egl` null so the very next applyPreviewOutput takes its core==null branch and
            // throws "GL context is unavailable for preview output", which dispatchWithResult routes
            // through the preview-output signal → CameraEngine.handlePreviewFailure (Not-Ready +
            // bounded retry — the one Surface/generation-owned preview-health path). No separate
            // failure surface is needed here: no preview output is bound yet at start() time (the
            // pending setPreviewOutput carries the failure), so there is nothing to signal directly.
            // On failure the half-initialized display handle is deliberately LEAKED (DBG4-4): the
            // default EGLDisplay is PROCESS-WIDE shared state — the Compose/HWUI renderer holds the
            // same handle — and eglTerminate on a non-refcounting EGL implementation would tear
            // down the framework's own EGL resources from app code. One leaked handle on an
            // already-broken-EGL device is strictly safer than a possible process-wide display
            // teardown; EglCore's constructor is atomic, so there is no core to run the checked
            // release on and nothing of ours is current on this thread.
            beforeNativeAcquisition?.invoke()
            val admitted = UnsafeRecorderQuarantine.runNativeAcquisition(nativeAcquisitionOwner) {
                egl = runCatching { EglCore(tenBit = tenBit) }.getOrElse { failure ->
                    if (me.hletrd.telecampro.BuildConfig.DEBUG) {
                        android.util.Log.e("GlPipeline", "EGL init failed; preview stays Not-Ready", failure)
                    }
                    null
                }
            }
            if (!admitted) {
                egl = null
                eglStartRefusal = NativeAcquisitionRefusedException(
                    NativeAcquisitionRefusalPhase.EGL_CONTEXT,
                    "Native acquisition refused before EGL context creation",
                )
            }
        }
    }

    fun setPreviewOutput(
        surface: Surface?,
        width: Int,
        height: Int,
        onReady: (() -> Unit)? = null,
        onFailure: ((Throwable) -> Unit)? = null,
    ) {
        val generation = previewOutputGeneration.incrementAndGet()
        val signal = PreviewOutputSignal(
            onReady = { onReady?.invoke() },
            onFailure = { failure -> onFailure?.invoke(failure) },
        )
        val h = handler
        if (h == null) {
            if (surface != null) signal.fail(IllegalStateException("GL pipeline is not running"))
            return
        }
        var applied = false
        dispatchWithResult(
            post = { task -> h.post(task) },
            block = {
                if (surface == null) {
                    applied = applyPreviewOutput(generation, null, width, height, null)
                } else {
                    beforeNativeAcquisition?.invoke()
                    beforePreviewOutputNativeAcquisition?.invoke(nativeAcquisitionOwner)
                    val admitted = UnsafeRecorderQuarantine.runNativeAcquisition(nativeAcquisitionOwner) {
                        applied = applyPreviewOutput(
                            generation = generation,
                            surface = surface,
                            width = width,
                            height = height,
                            signal = signal,
                        )
                    }
                    if (!admitted) {
                        throw NativeAcquisitionRefusedException(
                            NativeAcquisitionRefusalPhase.PREVIEW_OUTPUT,
                            "Native acquisition refused before preview EGL attachment",
                        )
                    }
                }
            },
            // EGL/native-window failures are contained on the GL thread. A successful attachment
            // stays pending until drawFrame presents its first real preview frame; bind-only
            // readiness would erase CameraEngine's recovery budget before swap health is known.
            onComplete = { result ->
                result.fold(
                    onSuccess = { if (!applied && surface != null) signal.cancel() },
                    onFailure = { failure -> if (surface != null) signal.fail(failure) },
                )
            },
        )
    }

    private fun applyPreviewOutput(
        generation: Long,
        surface: Surface?,
        width: Int,
        height: Int,
        signal: PreviewOutputSignal?,
    ): Boolean {
        if (generation != previewOutputGeneration.get()) return false
        val core = egl
        if (core == null) {
            if (surface == null) return false
            eglStartRefusal?.let { throw it }
            throw IllegalStateException("GL context is unavailable for preview output")
        }
        if (surface == null) {
            clearPreviewOutput(core)
            return true
        }
        // The TextureView host can deliver available-then-size-changed back-to-back on the same
        // native window; if it's the same surface already bound at the same size, there is nothing
        // to do — recreating the EGLSurface on a still-live native window throws EGL_BAD_ALLOC.
        if (surface === previewSurface && previewEgl != EGL14.EGL_NO_SURFACE &&
            width == previewW && height == previewH
        ) {
            previewSignal?.cancel()
            previewSignal = signal
            return true
        }

        clearPreviewOutput(core)
        if (generation != previewOutputGeneration.get()) return false
        var candidate = EGL14.EGL_NO_SURFACE
        var primaryFailure: Throwable? = null
        try {
            candidate = core.createWindowSurface(surface)
            if (generation != previewOutputGeneration.get()) return false
            core.makeCurrent(candidate)
            if (generation != previewOutputGeneration.get()) return false

            previewW = width
            previewH = height
            previewSurface = surface
            previewEgl = candidate
            candidate = EGL14.EGL_NO_SURFACE
            if (!inited) {
                val texId = renderer.init()
                val st = SurfaceTexture(texId)
                st.setDefaultBufferSize(cameraW, cameraH)
                val ownedHandler = checkNotNull(handler)
                val notifications = FrameNotificationCoalescer(
                    post = ownedHandler::post,
                    drawLatestFrame = { drawFrame(updateTex = true) },
                )
                frameNotifications = notifications
                // The framework listener message does only atomic bookkeeping. Its one named draw
                // Runnable lands behind any already-queued notifications, so a burst catches up by
                // latching/drawing the newest frame once instead of redrawing every stale callback.
                st.setOnFrameAvailableListener({ notifications.onFrameAvailable() }, ownedHandler)
                surfaceTexture = st
                val input = Surface(st)
                inputSurface = input
                inited = true
                runCatching { onInputReady?.invoke(input) }
            }
            // Do not install the health owner until renderer/input initialization has succeeded.
            // Otherwise catch/clear would cancel this pending signal before onComplete can report
            // the initialization failure to CameraEngine.
            previewSignal = signal
            return true
        } catch (failure: Throwable) {
            primaryFailure = failure
            runCatching { clearPreviewOutput(core) }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
            throw failure
        } finally {
            if (candidate != EGL14.EGL_NO_SURFACE) {
                // A stale generation can be discovered after candidate makeCurrent(). Explicitly
                // unbind before destroy: EGL defers destruction of a current surface, so destroy
                // alone would leave the released TextureView window natively owned.
                try {
                    detachEglOutput(
                        hasFallback = false,
                        makeFallbackCurrent = {},
                        makeNothingCurrent = core::releaseCurrentOwnership,
                        destroy = { core.releaseSurface(candidate) },
                    )
                } catch (cleanupFailure: Throwable) {
                    orphanedEglOutputs.retain(candidate)
                    primaryFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
                }
            }
        }
    }

    private fun clearPreviewOutput(core: EglCore) {
        clearOrphanedOutputs(core)
        val owned = previewEgl
        if (owned != EGL14.EGL_NO_SURFACE) {
            detachEglOutput(
                hasFallback = false,
                makeFallbackCurrent = {},
                makeNothingCurrent = core::releaseCurrentOwnership,
                destroy = { core.releaseSurface(owned) },
            )
        }
        previewEgl = EGL14.EGL_NO_SURFACE
        previewSurface = null
        previewSignal?.cancel()
        previewSignal = null
    }

    private fun clearOrphanedOutputs(core: EglCore) {
        orphanedEglOutputs.releaseAll { orphan ->
            detachEglOutput(
                hasFallback = previewEgl != EGL14.EGL_NO_SURFACE,
                makeFallbackCurrent = { core.makeCurrent(previewEgl) },
                makeNothingCurrent = core::releaseCurrentOwnership,
                destroy = { core.releaseSurface(orphan) },
            )
        }
    }

    /** Camera output resolution feeding the SurfaceTexture; controls aspect + peaking texel size. */
    fun setCameraPreviewSize(width: Int, height: Int) = post {
        cameraW = width.coerceAtLeast(1)
        cameraH = height.coerceAtLeast(1)
        surfaceTexture?.setDefaultBufferSize(cameraW, cameraH)
        renderer.setPreviewSize(cameraW, cameraH)
    }

    fun setRotationDegrees(deg: Int) = post {
        // Mirrored on this side too: the loupe framing hint has to rotate a texcoord point the same
        // way the renderer rotates the image, and reading it back off the renderer would make the
        // hint depend on that class's internals.
        previewRotationDeg = ((deg % 360) + 360) % 360
        renderer.setRotationDegrees(deg)
    }
    fun setSensorOrientation(deg: Int) = post { renderer.setSensorOrientation(deg) }

    /**
     * Front-route pre-mirrored-stream fact (PMA110 device diagnosis): when set, the PREVIEW draws
     * the stream as-is (it already IS the selfie-mirror view) and the ENCODER/ANALYSIS draws apply
     * the un-mirror so files and metering keep the TRUE scene. Per-draw roles derive from
     * [FrontMirrorConvention].
     */
    fun setFrontMirrorConvention(front: Boolean, streamPreMirrored: Boolean) = post {
        frontRoute = front
        frontStreamPreMirrored = streamPreMirrored
        // Kept as the diagnosis trail for the inverted mirror roles: this trace proved the flag
        // reaches the GL thread while the selfie still read unmirrored — the stream itself is
        // pre-mirrored, so the preview draw needs no mirror and the encoder must un-mirror.
        if (me.hletrd.telecampro.BuildConfig.DEBUG) {
            android.util.Log.i("GlPipeline", "frontMirror: front=$front preMirrored=$streamPreMirrored")
        }
    }
    fun setTransfer(t: ColorTransfer?) = post { transfer = t }

    /** Gamma Display Assist: monitor shows the normal 709-ish image while the FILE stays log. */
    fun setGammaAssist(enabled: Boolean) = post { gammaAssist = enabled }

    fun setPeaking(enabled: Boolean) = post { peaking = enabled }
    fun setZebra(enabled: Boolean) = post { zebra = enabled }

    /** Focus-peaking edge threshold (lower = more sensitive) + highlight color (RGB 0..1). */
    fun setPeakingParams(threshold: Float, r: Float, g: Float, b: Float) = post {
        peakThreshold = threshold; peakR = r; peakG = g; peakB = b
    }

    /** Zebra clipping threshold (luma 0..1 above which stripes draw). */
    fun setZebraThreshold(t: Float) = post { zebraThreshold = t }
    fun setFalseColor(enabled: Boolean) = post { falseColor = enabled }

    fun setEis(enabled: Boolean, focalInImageWidths: Float, crop: Float) = post {
        eisEnabled = enabled
        eisFocal = focalInImageWidths
        eisCrop = crop
    }

    fun setEisProvider(provider: (() -> FloatArray)?) = post { eisProvider = provider }

    /** Preview-only center crop-zoom (focus punch-in); does not affect the recorded/encoder frame. */
    fun setPunchIn(enabled: Boolean) = post { punchIn = enabled }

    /** Loupe Overview pretend-field scale (converter magnification while TELE, else 1). */
    fun setFinderFieldScale(scale: Float) = post { finderFieldScale = scale.coerceAtLeast(1f) }

    /**
     * How far the preview runs behind the bottom chrome, as a fraction of the preview HEIGHT. The
     * layout measures it (only Compose knows where the chrome sits) and it arrives here so the
     * scissor box and the Compose border keep resolving one rect. Cache is invalidated on change.
     */
    fun setFinderBottomClearanceFraction(fraction: Float) = post {
        val f = fraction.coerceIn(0f, 0.5f)
        if (finderBottomClearanceFraction != f) {
            finderBottomClearanceFraction = f
            finderRectCache = null
        }
    }

    /**
     * The app WINDOW's rotation away from the device's natural orientation, in degrees. Applied to
     * the PREVIEW and FINDER draws only — never to the encoder or analysis draws, which must keep
     * framing the sensor field by GRAVITY (see [RotationMath.windowPreviewRotationDegrees]).
     * Always 0 on a portrait-locked phone, so this whole path is inert there.
     */
    fun setWindowRotation(degrees: Int) = post { windowRotationDeg = RotationMath.normalize(degrees) }

    /** TELE finder PIP: with the resolved flag on and the punch-in loupe active, draw a small
     *  corner viewport re-drawing the FULL current camera frame (single-stream: the HAL zoom crop
     *  is baked in, so this is the widest available field, not an unzoomed one). Preview-only; the
     *  actual gating also requires the loupe ([punchIn], checked at draw). */
    /**
     * How the accepted camera session encoded its buffers (HLG10/DV vs 8-bit SDR).
     *
     * Goes through RendererAssists so it lands in the REPLAYED snapshot: a bare post is dropped
     * before start() and lost on a GL generation replacement — which is exactly how the first
     * attempt at this silently did nothing (2026-07-29).
     */
    fun setSourceHlg(enabled: Boolean) = post { renderer.setSourceHlg(enabled) }

    fun setTeleFinder(enabled: Boolean) = post { teleFinder = enabled }

    /** Sets the loupe magnification center (texcoord 0..1); the punch-in zoom follows this point. */
    fun setPunchInCenter(x: Float, y: Float) = post {
        punchInX = x.coerceIn(0f, 1f)
        punchInY = y.coerceIn(0f, 1f)
    }

    /** Toggle live histogram/waveform readback. Both off (and AE metering off) → readback is skipped. */
    fun setAnalysisEnabled(histogram: Boolean, waveform: Boolean) = post {
        analysisHistogram = histogram
        analysisWaveform = waveform
    }

    /** Force the luma readback for app-side AE (SHUTTER/ISO priority), independent of scope toggles. */
    fun setAeMetering(enabled: Boolean) = post { analysisAe = enabled }

    /**
     * Arm the frame-detail metric. Never forces a readback (see [analysisFocus]); it only decides
     * whether the per-pixel curvature math runs over a snapshot that was going to be taken anyway,
     * so a MANUAL-focus shooter pays exactly nothing.
     */
    fun setFocusDetailEnabled(enabled: Boolean) = post { analysisFocus = enabled }

    /**
     * Arms the motion-inversion rider. Never forces a readback (see [analysisMotion]); it only
     * decides whether to compute over a snapshot the scopes/AE readback already produced.
     *
     * [rotationProvider] must return the gyro rotation accumulated since ITS OWN previous call —
     * `GyroEis.rotationBetween`'s window contract — as (yawRadians, pitchRadians) over exactly the
     * two frames' own sensor timestamps, or null when it cannot answer that window. Passing null
     * disarms and drops the retained frame history.
     */
    fun setMotionInversionEnabled(
        enabled: Boolean,
        rotationProvider: ((Long, Long) -> FloatArray?)?,
        evidenceEpoch: Long = motionEvidenceEpoch.get(),
    ) {
        // This write is intentionally outside post(): it is the cancellation publication for an
        // already-running analysis task. The queued GL half resets pairing state before the next
        // dispatch, while the atomic epoch prevents an old executor result from sneaking through.
        motionEvidenceEpoch.accumulateAndGet(evidenceEpoch, ::maxOf)
        post {
            val currentEpoch = motionEvidenceEpoch.get()
            // A command from an older concurrently-published replay must not disarm the newer one.
            if (evidenceEpoch != currentEpoch) return@post
            analysisMotion = enabled && rotationProvider != null
            motionRotationProvider = if (analysisMotion) rotationProvider else null
            analysisGeneration?.let { generation ->
                if (generation.motionHistoryEpoch != currentEpoch) {
                    generation.clearMotionHistory(currentEpoch)
                }
            }
        }
    }

    /** Sink for computed scopes; invoked on the current analysis generation's executor, not GL. */
    fun setAnalysisCallback(
        cb: ((HistogramData?, WaveformData?, FocusDetailData?, MotionInversionData?) -> Unit)?,
    ) = post {
        analysisCallback = cb
    }

    /**
     * Swap (or clear) the encoder EGL surface. [onApplied] receives the applied result ON THE GL
     * THREAD. A rejected/dead GL thread delivers failure inline so callers cannot mistake a queued
     * attach failure for a ready recorder or strand an ordered teardown waiter.
     */
    fun setEncoderOutput(
        surface: Surface?,
        width: Int,
        height: Int,
        admission: EncoderOutputAdmission? = null,
        onRuntimeFailure: ((Throwable) -> Unit)? = null,
        onApplied: ((Result<Unit>) -> Unit)? = null,
    ) {
        val h = handler
        if (h == null) {
            val result = if (surface == null) Result.success(Unit) else Result.failure(
                IllegalStateException("GL pipeline is not running"),
            )
            runCatching { onApplied?.invoke(result) }
            return
        }
        if (surface == null) {
            dispatchWithResult(
                post = { task -> h.post(task) },
                block = {
                    val core = egl
                    if (core != null) {
                        clearEncoderOutput(
                            core,
                            CancellationException("Encoder output detached before its first frame"),
                        )
                    } else {
                        encoderSignal?.cancel(
                            CancellationException("EGL context stopped before encoder detach"),
                        )
                        encoderSignal = null
                        encoderEgl = EGL14.EGL_NO_SURFACE
                    }
                    resetEncoderTimestampBase()
                },
                onComplete = { result -> onApplied?.invoke(result) },
            )
            return
        }

        val signal = EncoderOutputSignal(
            onAttached = { result -> onApplied?.invoke(result) },
            onRuntimeFailure = { failure -> onRuntimeFailure?.invoke(failure) },
        )
        dispatchWithResult(
            post = { task -> h.post(task) },
            block = {
                if (admission?.isValid() == false) {
                    throw CancellationException("Recorder admission was quarantined before EGL attach")
                }
                val core = checkNotNull(egl) { "EGL context is not ready" }
                clearEncoderOutput(
                    core,
                    CancellationException("Encoder output replaced before its first frame"),
                )
                resetEncoderTimestampBase()
                // eglCreateWindowSurface proves allocation only. Bind the candidate now so a dead
                // codec/native window fails inside this result boundary, then restore preview (or
                // no current output) before publishing ownership.
                lateinit var candidate: EGLSurface
                val nativePrepared = UnsafeRecorderQuarantine.runNativeAcquisition(nativeAcquisitionOwner) {
                    candidate = prepareEglOutput(
                        create = { core.createWindowSurface(surface) },
                        makeCandidateCurrent = core::makeCurrent,
                        restoreCurrent = { restorePreviewOrNothing(core) },
                        discardCandidate = { failedCandidate ->
                            // Candidate may be current when bind succeeded but preview restoration
                            // failed. Unbind before destroying it while preserving the primary failure.
                            try {
                                detachEglOutput(
                                    hasFallback = false,
                                    makeFallbackCurrent = {},
                                    makeNothingCurrent = core::releaseCurrentOwnership,
                                    destroy = { core.releaseSurface(failedCandidate) },
                                )
                            } catch (cleanupFailure: Throwable) {
                                orphanedEglOutputs.retain(failedCandidate)
                                throw cleanupFailure
                            }
                        },
                    )
                }
                if (!nativePrepared) {
                    throw CancellationException("Recorder quarantine refused encoder EGL acquisition")
                }
                val installed = installPreparedEncoderOutput(
                    candidate = candidate,
                    admission = admission,
                    install = { accepted ->
                        encoderW = width
                        encoderH = height
                        encoderEgl = accepted
                        encoderSignal = signal
                    },
                    discard = { revoked ->
                        try {
                            core.releaseSurface(revoked)
                        } catch (cleanupFailure: Throwable) {
                            orphanedEglOutputs.retain(revoked)
                            throw cleanupFailure
                        }
                    },
                )
                if (!installed) {
                    throw CancellationException("Recorder admission was quarantined during EGL attach")
                }
                scheduleCheckedDelay(
                    postDelayed = { task, delayMs -> h.postDelayed(task, delayMs) },
                    delayMs = ENCODER_FIRST_FRAME_TIMEOUT_MS,
                    action = {
                        if (encoderSignal === signal && signal.isPending()) {
                            failEncoderOutput(
                                core,
                                signal,
                                IllegalStateException("Encoder produced no frame before timeout"),
                            )
                        }
                    },
                )
            },
            // Setup success remains pending until drawFrame presents the first real camera frame.
            // Rejection or any candidate-stage exception is still delivered exactly once inline.
            onComplete = { result -> result.exceptionOrNull()?.let(signal::fail) },
        )
    }

    private fun resetEncoderTimestampBase() {
        // Each output owns an independent sensor-clock rebase.
        encoderBaseSet = false
        encoderBaseNs = 0L
    }

    /** Releases the current encoder EGLSurface before resolving its pending attachment. */
    private fun clearEncoderOutput(
        core: EglCore,
        pendingCause: Throwable,
        cancelSignal: Boolean = true,
    ) {
        clearOrphanedOutputs(core)
        val owned = encoderEgl
        val signal = encoderSignal
        if (owned != EGL14.EGL_NO_SURFACE) {
            detachEglOutput(
                hasFallback = previewEgl != EGL14.EGL_NO_SURFACE,
                makeFallbackCurrent = { core.makeCurrent(previewEgl) },
                makeNothingCurrent = core::releaseCurrentOwnership,
                destroy = { core.releaseSurface(owned) },
            )
        }
        encoderEgl = EGL14.EGL_NO_SURFACE
        encoderSignal = null
        if (cancelSignal) signal?.cancel(pendingCause)
    }

    /** Contains a real-frame encoder failure, then reports it through the matching recorder owner. */
    private fun failEncoderOutput(core: EglCore, signal: EncoderOutputSignal, failure: Throwable) {
        if (encoderSignal !== signal) return
        val detachFailure = runCatching {
            clearEncoderOutput(core, failure, cancelSignal = false)
            resetEncoderTimestampBase()
        }.exceptionOrNull()
        signal.fail(detachFailure ?: failure)
    }

    // Cached finder box: pure in (previewW, previewH), recomputed only when the size changes — a
    // per-frame data-object allocation in the PIP draw loop sat below the PERF4-4 bar (cycle-6
    // PR-3). GL-thread confined.
    private var finderRectForW = -1f
    private var finderRectForH = -1f
    private var finderRectCache: me.hletrd.telecampro.camera.FinderRect? = null
    private var finderBottomClearanceFraction = 0f

    private fun finderRectFor(w: Float, h: Float): me.hletrd.telecampro.camera.FinderRect {
        val cached = finderRectCache
        if (cached != null && finderRectForW == w && finderRectForH == h) return cached
        return finderRect(w, h, bottomClearance = h * finderBottomClearanceFraction).also {
            finderRectCache = it
            finderRectForW = w
            finderRectForH = h
        }
    }

    /** Restores the viewfinder target; a failed restore still makes nothing current before escaping. */
    private fun restorePreviewOrNothing(core: EglCore) {
        if (previewEgl == EGL14.EGL_NO_SURFACE) {
            core.makeNothingCurrent()
            return
        }
        try {
            core.makeCurrent(previewEgl)
        } catch (failure: Throwable) {
            runCatching { core.releaseCurrentOwnership() }
            throw failure
        }
    }

    private var lastDrawMs = 0L

    // Live-zoom compensation (see FlipRenderer.draw zoomComp): the UI's requested zoom vs the zoom
    // the HAL last REPORTED applying. The preview crops the difference immediately; camera frames
    // catch up at the HAL's own (stall-prone) pace. GL-thread confined.
    private var zoomTarget = 1f
    private var halZoom = 1f
    private var lastSelfRedrawMs = 0L

    /** The UI's requested zoom — redraws the LAST frame immediately so pinch follows the finger
     *  even while the HAL stalls (~180 ms per repeating-request swap on this device). */
    fun setZoomTarget(z: Float) = post {
        if (zoomTarget == z) return@post
        zoomTarget = z
        val now = android.os.SystemClock.uptimeMillis()
        // Self-redraw throttle: frame-available draws already repaint at camera rate; only inject
        // extra draws when the camera is quiet, at most ~60 Hz.
        if (now - lastDrawMs > 16 && now - lastSelfRedrawMs > 16) {
            lastSelfRedrawMs = now
            drawFrame(updateTex = false)
        }
    }

    /** The zoom the HAL reported in the latest capture result (rides the matching frames). */
    fun setHalZoom(z: Float) = post { halZoom = z }

    // Preview brightness simulation (cycle 8): linear gain for the exposure shortfall the
    // fluidity-capped AE-OFF repeating request cannot carry (CameraController publishes the value
    // its previewExposureTrade just put on the wire; the engine re-seeds it per GL generation).
    // GL-thread confined. The preview/finder draws render it; the analysis readback stays
    // UNBOOSTED and its histogram/waveform apply the matching display LUT CPU-side instead, so
    // scopes, the MANUAL meter, and the app-side AE loop all read the SIMULATED still exposure
    // (the loop then drives the intended still values — metering the dimmed wire preview would
    // ratchet the intended exposure against a brightness the wire can never show). The encoder
    // draw never sees any of this.
    private var previewDigitalGain = 1f

    /** The GL brightness-simulation gain matching the current repeating request (≥1). */
    fun setPreviewDigitalGain(g: Float) = post { previewDigitalGain = g.coerceAtLeast(1f) }

    private fun drawFrame(updateTex: Boolean = true) {
        val now = android.os.SystemClock.uptimeMillis()
        if (me.hletrd.telecampro.BuildConfig.DEBUG) {
            // Threshold is 200 ms, not 50: since the cycle-8 fluidity cap a dark preview runs at a
            // DESIGNED 66.7 ms cadence, so a >50 ms rule logged EVERY frame (~15 rows/s) and spent
            // ColorOS's 300-row per-process quota in ~20 s — after which the device silently drops
            // every other diagnostic this app emits (LOG_FLOWCTRL, device-observed 2026-07-25:
            // it ate the startup trace and the focus-verdict trace outright). 200 ms still catches
            // what this line exists for: the ~180 ms setRepeatingRequest stalls and real stream
            // wedges. Normal cadence is NOT news.
            if (lastDrawMs != 0L && now - lastDrawMs > 200) {
                android.util.Log.i("GlPipeline", "FrameGap: ${now - lastDrawMs} ms")
            }
        }
        // Release builds use this timestamp too: setZoomTarget() consults it to decide whether the
        // camera is quiet enough for a self-redraw. Keeping the write inside DEBUG made every zoom
        // update look idle in production and injected redundant preview draws between real frames.
        lastDrawMs = now
        val core = egl ?: return
        val st = surfaceTexture ?: return
        // A real camera frame feeds two sibling outputs. Preview loss must not prevent texture
        // acquisition or starve an otherwise healthy encoder surface.
        if (!updateTex && previewEgl == EGL14.EGL_NO_SURFACE) return

        if (updateTex) {
            val framePreview = previewEgl
            val frameEncoder = encoderEgl
            val frameEncoderSignal = encoderSignal
            val acquisitionOwner = frameAcquisitionOwner(
                previewAvailable = framePreview != EGL14.EGL_NO_SURFACE,
                encoderActive = frameEncoder != EGL14.EGL_NO_SURFACE &&
                    frameEncoderSignal?.isActive() == true,
            )
            val acquisitionFailure = runCatching {
                when (acquisitionOwner) {
                    FrameAcquisitionOwner.NONE -> return
                    FrameAcquisitionOwner.PREVIEW -> core.makeCurrent(framePreview)
                    FrameAcquisitionOwner.ENCODER -> core.makeCurrent(frameEncoder)
                }
                st.updateTexImage()
                st.getTransformMatrix(stMatrix)
            }.exceptionOrNull()
            if (acquisitionFailure != null) {
                when (acquisitionOwner) {
                    FrameAcquisitionOwner.NONE -> Unit
                    FrameAcquisitionOwner.PREVIEW -> {
                        previewSignal?.fail(acquisitionFailure)
                        val detachFailure = runCatching { clearPreviewOutput(core) }.exceptionOrNull()
                        // If the broken preview cannot relinquish native-window ownership, encoder
                        // continuation is no longer safe. Terminate it explicitly instead of letting
                        // the next frame escape the GL looper or silently freeze REC.
                        if (detachFailure != null && frameEncoderSignal?.isActive() == true) {
                            failEncoderOutput(core, frameEncoderSignal, detachFailure)
                        }
                    }
                    FrameAcquisitionOwner.ENCODER -> {
                        if (frameEncoderSignal != null) {
                            failEncoderOutput(core, frameEncoderSignal, acquisitionFailure)
                        }
                    }
                }
                return
            }
        }

        var sx = 0f
        var sy = 0f
        var roll = 0f
        var crop = 0f
        if (eisEnabled) {
            crop = eisCrop
            // Note: the provider lambda (set via setEisProvider) returns a new FloatArray per call;
            // its 3 values are copied into local floats immediately below and not retained here.
            // Removing that allocation would require changing the provider contract, which is owned
            // by the caller (CameraEngine/GyroEis), outside this file's scope.
            val c = eisProvider?.invoke()
            if (c != null && c.size >= 3) {
                val half = eisCrop / 2f
                sx = (c[0] * eisFocal).coerceIn(-half, half)
                sy = (c[1] * eisFocal).coerceIn(-half, half)
                roll = Math.toDegrees(c[2].toDouble()).toFloat().coerceIn(-6f, 6f)
            }
        }

        // Punch-in is preview-only: the encoder draw below always uses the original `crop`.
        val previewCrop = if (punchIn) maxOf(crop, PUNCH_IN_CROP) else crop

        // The loupe (movable punch-in) recenters the preview zoom on the tapped point; the encoder
        // draw below stays centered so recordings are unaffected.
        //
        // Clamped against the LIVE crop and zoom compensation so the sampled window cannot leave the
        // texture — setPunchInCenter can only bound the centre to 0..1, which still lets the window
        // run off the edge and return edge-clamped garbage instead of scene. Computed once here and
        // fed to BOTH the draw and the framing hint, or the hint would mark a position the preview
        // no longer shows.
        val previewZoomComp = (zoomTarget / halZoom.coerceAtLeast(0.01f)).coerceAtLeast(1f)
        val loupeX = if (punchIn) clampPunchInCenter(punchInX, previewCrop, previewZoomComp) else 0.5f
        val loupeY = if (punchIn) clampPunchInCenter(punchInY, previewCrop, previewZoomComp) else 0.5f
        // Show the selected log curve (S-Log3/S-Log3.Cine/LogC3) FLAT in the live preview so the user
        // can monitor that they're on a log profile — previously the preview was hardcoded to SDR
        // (null) and only the encoder got the curve, so log never looked flat on screen. The preview
        // renders the same flat curve the encoder bakes, or — with Gamma Display Assist ON — skips it
        // and shows the normal display-referred image (the FILE always gets the curve). HLG/SDR keep
        // the natural SDR preview (an HLG curve on this SDR preview surface would just look washed;
        // HDR is monitored on an HDR display, not here).
        val previewTransfer = transfer?.takeIf { it.isLog && !gammaAssist }
        val ownedPreview = previewEgl
        val ownedPreviewSignal = previewSignal
        if (ownedPreview != EGL14.EGL_NO_SURFACE) {
            val previewFailure = runCatching {
                core.makeCurrent(ownedPreview)
                renderer.draw(
                    stMatrix, previewW, previewH, previewTransfer, peaking, zebra, falseColor, sx, sy, roll, previewCrop, loupeX, loupeY,
                    peakThreshold = peakThreshold, peakR = peakR, peakG = peakG, peakB = peakB, zebraThreshold = zebraThreshold,
                    zoomComp = zoomTarget / halZoom.coerceAtLeast(0.01f),
                    digitalGain = previewDigitalGain,
                    // Profile-resolved selfie-preview role; encoder/analysis derive their matching
                    // true-scene roles from the same FrontMirrorConvention.
                    mirrorX = FrontMirrorConvention.previewDrawMirrorX(frontRoute, frontStreamPreMirrored),
                    // Undo the WINDOW's rotation so the field stays upright when a large screen
                    // hands this portrait-designed activity a landscape window (Android 16+ ignores
                    // screenOrientation at sw600dp+). Added on top of the SHARED rotation state
                    // rather than written into it: the encoder and analysis draws below must keep
                    // framing by gravity, or the same device held the same way would record a
                    // differently-framed clip in a landscape window and coverScale would overscan
                    // (the cycle-4 bug). null when the window is unrotated, so a phone takes the
                    // byte-identical pre-existing path.
                    rotationOverrideDeg = windowRotationDeg.takeIf { it != 0 }?.let {
                        RotationMath.normalize(
                            renderer.contentRotationDegrees() + RotationMath.windowPreviewRotationDegrees(it),
                        )
                    },
                )
                // TELE finder PIP (opt-in, resolved by CameraEngine.pushTeleFinder): a corner
                // viewport re-drawing the FULL current camera frame while the main view is
                // magnified. Single-stream honesty: the HAL's zoom crop is baked into the texture,
                // so this box is only wider than the main view while zoomComp/punch-in magnify past
                // the delivered field (mid-gesture); it converges to the same framing at rest — a
                // true wide 3× finder needs a second stream (BACKLOG). The main draw above already
                // filled the surface and renderer.draw's internal glClear is framebuffer-wide, so
                // the finder box is scissored to keep its clear from wiping the main preview.
                // ISOLATION: a finder-only failure must degrade to "no PIP this frame" — it must
                // never feed preview health or burn the bounded recovery budget — and scissor is
                // CONTEXT state shared with the encoder/analysis draws, so the finally rearms it
                // off even when the draw throws (a leak would clip every later draw to this box).
                // Same gate axis as the Compose border's teleFinderVisible: resolved && punch-in
                // (AGG4-29/P3.4) — the PIP shows the FULL delivered frame while the loupe magnifies,
                // the one case the single stream makes it genuinely wider than the main view.
                if (teleFinder && punchIn && previewW > 0 && previewH > 0) {
                    val rect = finderRectFor(previewW.toFloat(), previewH.toFloat())
                    // Bottom-left corner in GL's bottom-left-origin pixel space (the Compose border
                    // mirrors the same rect from its top-left-origin space via the shared seam).
                    val fx = rect.x.toInt()
                    val fy = rect.y.toInt()
                    val fw = rect.width.toInt().coerceAtLeast(1)
                    val fh = rect.height.toInt().coerceAtLeast(1)
                    runCatching {
                        try {
                            GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                            GLES20.glScissor(fx, fy, fw, fh)
                            renderer.draw(
                                stMatrix, fw, fh, previewTransfer, false, false, false,
                                // Full delivered frame: no extra GL crop on top of the HAL field
                                // (crop/center/EIS deliberately left at defaults — the finder shows
                                // the whole frame, not the loupe/stab framing).
                                zoomComp = 1f,
                                // Preview-role sibling: matches the main view's simulated
                                // brightness, or the PIP would read as a mysteriously dark inset.
                                digitalGain = previewDigitalGain,
                                viewportX = fx, viewportY = fy,
                                // Preview-space sibling draw: like the main preview, no mirror
                                // of our own (moot today — the finder requires TC, which the front
                                // route forces off).
                                mirrorX = false,
                                // UPRIGHT, deliberately NOT carrying the afocal 180° the main view
                                // gets (user-specified 2026-07-28, restated three times). The
                                // overview is an orientation reference: the operator wants the
                                // world the right way up in the corner while the magnified main
                                // view is the converter-corrected image.
                                //
                                // HONESTY NOTE — this is only fully right once the overview is a
                                // real WIDE stream. Today it re-draws the SAME converter-fed frame
                                // (single-stream), so with the converter physically mounted this
                                // box shows the raw, inverted field. The genuinely correct version
                                // is the second-stream wide finder already on the BACKLOG; that
                                // stream comes off a lens the converter is NOT clamped to, and is
                                // upright for real rather than by declining a rotation.
                                // The overview deliberately declines the afocal 180° so the world sits
                                // the right way up in the corner (user-specified 2026-07-28). "Upright"
                                // is relative to the WINDOW, not the device, so a rotated window still
                                // owes its own term — this stays 0 on any phone.
                                rotationOverrideDeg = RotationMath.windowPreviewRotationDegrees(windowRotationDeg),
                            )
                            // iPhone-style framing hint: a thin rectangle inside the overview
                            // marking WHERE THE MAGNIFIED MAIN VIEW IS LOOKING. Drawn with
                            // scissored clears rather than geometry — four 1-ish px edges need no
                            // shader, no VBO and no texture unit, so the hint cannot perturb the
                            // renderer state the main preview and the encoder share. Scissor is
                            // already enabled and already restored by the finally below.
                            //
                            // The fraction is derived from the SAME values the main draw above
                            // received, so the hint cannot claim a framing the view does not have.
                            val hint = loupeHintRect(
                                finder = rect,
                                visibleFraction = (1f - previewCrop) /
                                    (zoomTarget / halZoom.coerceAtLeast(0.01f)).coerceAtLeast(0.01f),
                                centerTexX = loupeX,
                                centerTexY = loupeY,
                                // TELE: the upright overview stands in for the PRE-CONVERTER world,
                                // so the hint marks the main view's field on that scale — a 4.3×
                                // converter shrinks it 4.3× beyond the loupe fraction
                                // (operator-specified 2026-07-31; see loupeHintRect).
                                fieldScale = finderFieldScale,
                                // MUST match the overview draw's own rotation above — not
                                // previewRotationDeg. The hint marks a position INSIDE that box, so
                                // if the box rotates and the hint does not, the mark lands
                                // point-mirrored from the field it claims to describe (the same
                                // class of bug the y-sign bisect chased). This was a literal 0 while
                                // the box was pinned upright; once the box took the WINDOW term the
                                // two silently diverged in a landscape window, so it reads the same
                                // expression. Still 0 on any phone, where the window never rotates.
                                rotationDegrees = RotationMath.windowPreviewRotationDegrees(windowRotationDeg),
                            )
                            val hx = hint.x.toInt()
                            val hy = hint.y.toInt()
                            val hw = hint.width.toInt().coerceAtLeast(1)
                            val hh = hint.height.toInt().coerceAtLeast(1)
                            val t = (minOf(fw, fh) / 90).coerceIn(1, 3)
                            // Signature yellow (CameraColors.ManualActive 0xFFFFD60A): the hint
                            // is an ACTIVE-state mark like the OSD tags, not neutral chrome, and
                            // white lost itself against a bright frame.
                            GLES20.glClearColor(1f, 0.839f, 0.039f, 1f)
                            // Bottom, top, left, right. Each is clamped into the finder box so an
                            // edge-clamped hint cannot paint over the border or the main preview.
                            // Four inline scissor+clear pairs, no per-frame arrays: the previous
                            // array-of-arrays shape allocated ~6 objects per drawn PIP frame at up
                            // to ~90 draws/s on the GL thread (perf review #14).
                            GLES20.glScissor(hx, hy, hw.coerceAtLeast(1), t)
                            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                            GLES20.glScissor(hx, hy + hh - t, hw.coerceAtLeast(1), t)
                            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                            GLES20.glScissor(hx, hy, t, hh.coerceAtLeast(1))
                            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                            GLES20.glScissor(hx + hw - t, hy, t, hh.coerceAtLeast(1))
                            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                        } finally {
                            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
                        }
                    }.onFailure { e ->
                        // The isolation above is deliberate — a finder fault must never reach
                        // preview health — but it was also SILENT, so "the overview is an empty
                        // rectangle" was undiagnosable: the main draw has already filled this box,
                        // so a failed finder draw looks exactly like a transparent one. Change-gated
                        // because the ColorOS 300-row process quota would eat the rest of logcat if
                        // this logged per frame (see CLAUDE.md).
                        if (me.hletrd.telecampro.BuildConfig.DEBUG) {
                            val sig = "${e.javaClass.simpleName}:${e.message}"
                            if (sig != lastFinderFailureSig) {
                                lastFinderFailureSig = sig
                                android.util.Log.w("GlPipeline", "FinderDrawFailed rect=$fx,$fy,${fw}x$fh of ${previewW}x$previewH: $sig")
                            }
                        }
                    }
                }
                core.swapBuffers(ownedPreview)
                // Attachment alone is not renderer health. The first identity-owned swap is the
                // only event that may publish Preview Ready and reset CameraEngine's retry budget.
                // A zoom self-redraw swaps cached/uninitialized texture state and proves no camera
                // producer progress, so it must leave a pending preview signal untouched.
                ownedPreviewSignal?.readyAfterSwap(realCameraFrame = updateTex)
            }.exceptionOrNull()
            if (previewFailure != null) {
                // Publish the identity-owned failure once, then detach the broken preview. Keep
                // processing a real frame below so active recording remains truthful and live.
                ownedPreviewSignal?.fail(previewFailure)
                val detachFailure = runCatching { clearPreviewOutput(core) }.exceptionOrNull()
                if (detachFailure != null) {
                    // Same containment policy as the texture-acquisition branch above — the old
                    // bare runCatching DISCARDED this failure and retained the poisoned
                    // previewEgl/native-window owner for every same-surface recovery retry, while
                    // the same frame kept flowing into the encoder. A preview that cannot
                    // relinquish native-window ownership makes encoder continuation unsafe:
                    // terminate the identity-owned recorder explicitly, then orphan the poisoned
                    // EGL surface (destroyed later under clearOrphanedOutputs' checked detach) so
                    // recovery restarts from a clean create instead of retrying the unresolved
                    // clear transaction. The original preview failure stays primary (already
                    // published above); this frame is abandoned (no encoder/analysis work).
                    val activeEncoderSignal = encoderSignal
                    if (activeEncoderSignal?.isActive() == true) {
                        failEncoderOutput(core, activeEncoderSignal, detachFailure)
                    }
                    val poisoned = previewEgl
                    if (poisoned != EGL14.EGL_NO_SURFACE) orphanedEglOutputs.retain(poisoned)
                    previewEgl = EGL14.EGL_NO_SURFACE
                    previewSurface = null
                    previewSignal?.cancel()
                    previewSignal = null
                    return
                }
            }
        }

        // Self-redraws only refresh the PREVIEW with a new zoom crop from the last frame — the
        // analysis meter and (critically) the encoder must only ever see REAL camera frames.
        if (!updateTex) return

        val ownedEncoder = encoderEgl
        val ownedSignal = encoderSignal
        if (ownedEncoder != EGL14.EGL_NO_SURFACE && ownedSignal?.isActive() == true) {
            var encoderFrameSwapped = false
            try {
                core.makeCurrent(ownedEncoder)
                // Un-mirror the pre-mirrored front stream so the FILE keeps the true scene
                // (rear routes pass false and are untouched).
                renderer.draw(stMatrix, encoderW, encoderH, transfer, false, false, false, sx, sy, roll, crop, mirrorX = FrontMirrorConvention.encoderDrawMirrorX(frontRoute, frontStreamPreMirrored))
                // Rebase to the first recorded frame so video PTS starts near 0 like the audio track.
                val ts = st.timestamp
                if (!encoderBaseSet && ts > 0L) { encoderBaseNs = ts; encoderBaseSet = true }
                core.setPresentationTime(ownedEncoder, if (encoderBaseSet) ts - encoderBaseNs else 0L)
                core.swapBuffers(ownedEncoder)
                encoderFrameSwapped = true
                // Never leave the codec window current between frames. Apart from making detach
                // truthful, this ensures a stop triggered by the ready callback is queued only
                // after EGL has relinquished the native producer.
                restorePreviewOrNothing(core)
                ownedSignal.ready()
            } catch (failure: Throwable) {
                if (!encoderFrameSwapped) {
                    // Encoder output is optional to preview. Contain every runtime EGL/renderer
                    // error, detach this exact output, and converge the identity-owned recorder
                    // instead of letting an uncaught HandlerThread exception kill the process.
                    failEncoderOutput(core, ownedSignal, failure)
                } else {
                    // The encoder frame swapped cleanly — this failure is the PREVIEW surface
                    // refusing to come back current, not a recorder fault. Attributing it to the
                    // recorder claimed and ended a healthy recording (cycle-6 code-review F8).
                    // Route it through preview health exactly like the preview-draw branch above:
                    // publish the identity-owned failure, detach the broken preview, and only a
                    // failed detach (unresolvable native-window ownership) makes encoder
                    // continuation unsafe.
                    ownedSignal.ready()
                    previewSignal?.fail(failure)
                    val detachFailure = runCatching { clearPreviewOutput(core) }.exceptionOrNull()
                    if (detachFailure != null) {
                        val activeEncoderSignal = encoderSignal
                        if (activeEncoderSignal?.isActive() == true) {
                            failEncoderOutput(core, activeEncoderSignal, detachFailure)
                        }
                        val poisoned = previewEgl
                        if (poisoned != EGL14.EGL_NO_SURFACE) orphanedEglOutputs.retain(poisoned)
                        previewEgl = EGL14.EGL_NO_SURFACE
                        previewSurface = null
                        previewSignal?.cancel()
                        previewSignal = null
                    }
                }
            }
        }

        // Additive scope analysis: throttled GL readback of the just-drawn preview, computed off-thread.
        // Kept entirely defensive so it can never block or crash the preview/encoder draws above.
        // Ordered AFTER the encoder draw+swap on purpose: the full-surface glReadPixels stalls the GL
        // thread, so running it before the encoder draw would push the readback in front of every
        // recorded frame — encoder frame pacing beats scope latency (scopes only refresh ~6×/s anyway).
        val analysis = analysisGeneration
        if (analysis != null && (analysisHistogram || analysisWaveform || analysisAe) && analysisCallback != null) {
            // Refresh the scopes / AE meter ~6×/s (every 5th frame at 30 fps) — snappy without stalling
            // the 4K preview on the readback. (Was every 12th ≈ 2.5×/s, which felt laggy.)
            if (++analysis.frameCounter >= 5) {
                analysis.frameCounter = 0
                // Scopes and app-side AE observe capture framing, never the preview-only focus loupe.
                val frame = analysisFrame(crop)
                runAnalysisReadback(
                    generation = analysis,
                    core = core,
                    // ALWAYS display-referred (null), never previewTransfer (AGG4-9/P3.4): with a
                    // log profile active the preview renders the flat curve, and metering THAT put
                    // 18% grey at the log grey anchor (~0.41 S-Log3 / ~0.39 LogC3; ~0.4868 on the
                    // removed O-Log2, where the bug was found) instead of 0.18 — the app-side AE
                    // loop settled ~1.5 stops off and the histogram/waveform/zebra read the encode
                    // curve rather than the scene. The meter must not move with the log toggle.
                    transfer = analysisReadbackTransfer(previewTransfer),
                    sx = sx,
                    sy = sy,
                    roll = roll,
                    crop = frame.crop,
                    centerX = frame.centerX,
                    centerY = frame.centerY,
                    frameTimestampNs = st.timestamp,
                )
            }
        }
    }

    /**
     * Extracts this frame's luma, compares it with the retained previous frame, and rotates the
     * history. Runs on the ANALYSIS EXECUTOR, which is single-flight by construction (the
     * generation's busy gate), so the generation's motion buffers are effectively thread-confined
     * here — the GL thread only reads [AnalysisGeneration.motionHasHistory] and the dimensions,
     * both of which it wrote itself before dispatching.
     *
     * Always updates the history, even when it returns UNJUDGED: a frame that could not be judged is
     * still a perfectly good PREDECESSOR for the next one, and dropping it would halve the sample
     * rate for no benefit.
     */
    private fun computeMotionInversionFrame(
        generation: AnalysisGeneration,
        bytes: ByteArray,
        w: Int,
        h: Int,
        paired: Boolean,
        rotation: FloatArray?,
        rotationDegrees: Int,
        frontFacing: Boolean,
        frameTsNs: Long,
        evidenceEpoch: Long,
    ): MotionInversionData {
        if (motionEvidenceEpoch.get() != evidenceEpoch) return MotionInversionData.UNJUDGED
        val size = w * h
        var cur = generation.motionCur
        if (cur == null || cur.size < size) {
            cur = IntArray(size)
            generation.motionCur = cur
        }
        motionLuma(bytes, w, h, cur)

        var result = MotionInversionData.UNJUDGED
        val prev = generation.motionPrev
        if (paired && generation.motionHistoryEpoch == evidenceEpoch && prev != null &&
            prev.size >= size && rotation != null && rotation.size >= 2
        ) {
            val predicted = predictedSceneMotion(
                yawRadians = rotation[0],
                pitchRadians = rotation[1],
                rotationDegrees = rotationDegrees,
                frontFacing = frontFacing,
            )
            result = if (predicted != null) {
                computeMotionInversion(prev, cur, w, h, predicted[0], predicted[1])
            } else {
                // BELOW the angular gate: no verdict, but report how far below. Without this the
                // trace prints 0.0 for everything from a phone on a desk to a movement just shy of
                // judgeable, which gives a person trying to produce a valid gesture nothing to aim
                // at — they cannot tell "not moving" from "nearly there". Diagnostic only; the
                // verdict is still UNJUDGEABLE and no caller reads the magnitude.
                MotionInversionData.UNJUDGED.copy(
                    predictedMrad = hypot(
                        rotation[0].toDouble() * 1000.0,
                        rotation[1].toDouble() * 1000.0,
                    ).toFloat(),
                )
            }
        }

        // A reset can race the CPU work above. Never let a late old-epoch task reseed the new
        // history; its callback is suppressed by the matching publication guard below as well.
        if (motionEvidenceEpoch.get() != evidenceEpoch) return MotionInversionData.UNJUDGED
        // Swap rather than copy: this frame becomes the next call's predecessor.
        generation.motionCur = prev
        generation.motionPrev = cur
        generation.motionW = w
        generation.motionH = h
        generation.motionPrevTsNs = frameTsNs
        generation.motionHistoryEpoch = evidenceEpoch
        generation.motionHasHistory = true
        return result
    }

    /**
     * Redraws capture framing into a bounded offscreen framebuffer and dispatches its pixels to
     * the generation-owned executor. Runs on the GL thread; the generation owner ensures only one
     * readback is in flight so its byte snapshot is never overwritten while the executor reads it.
     * Fully wrapped so any failure degrades to "no scopes this frame" rather than breaking rendering.
     */
    private fun runAnalysisReadback(
        generation: AnalysisGeneration,
        core: EglCore,
        transfer: ColorTransfer?,
        sx: Float,
        sy: Float,
        roll: Float,
        crop: Float,
        centerX: Float,
        centerY: Float,
        /**
         * `SurfaceTexture.timestamp` for the frame being read back — the camera/sensor clock, the
         * same one the encoder uses for PTS. Load-bearing for the motion rider only; 0 means
         * unavailable, which makes the rider refuse rather than approximate.
         */
        frameTimestampNs: Long,
    ) {
        if (previewEgl == EGL14.EGL_NO_SURFACE || previewW <= 0 || previewH <= 0) return
        val cb = analysisCallback ?: return
        // AE metering needs the luma histogram; compute it whenever AE is active even if the user's
        // histogram overlay is off (the callback consumer picks luma out and ignores the rest).
        val doHist = analysisHistogram || analysisAe
        val doWave = analysisWaveform
        if (!doHist && !doWave) return
        // Rider (see [analysisFocus]): computed only when the readback is already happening.
        val doFocus = analysisFocus
        val doMotion = analysisMotion && motionRotationProvider != null
        val evidenceEpoch = motionEvidenceEpoch.get()
        if (!generation.owner.tryAcquire()) return
        try {
            val target = analysisTargetSize(previewW, previewH)
            val w = target.width
            val h = target.height
            val size = w * h * 4
            if (generation.buffer == null || generation.bufferW != w || generation.bufferH != h) {
                generation.buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
                generation.bytes = ByteArray(size)
                generation.bufferW = w
                generation.bufferH = h
            }
            val buf = checkNotNull(generation.buffer)
            val bytes = checkNotNull(generation.bytes)
            core.makeCurrent(previewEgl)
            if (generation.fbo == 0) {
                val ids = IntArray(1)
                GLES20.glGenTextures(1, ids, 0)
                generation.texture = ids[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, generation.texture)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glGenFramebuffers(1, ids, 0)
                generation.fbo = ids[0]
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, generation.fbo)
                GLES20.glFramebufferTexture2D(
                    GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, generation.texture, 0,
                )
            } else {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, generation.fbo)
            }
            if (generation.textureW != w || generation.textureH != h) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, generation.texture)
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
                )
                generation.textureW = w
                generation.textureH = h
            }
            // Re-draw the scene (same transform/EIS/crop as the preview, minus peaking/zebra/false-
            // color so assist overlays never pollute the meter) into the small target and read THAT.
            // Same un-mirror as the encoder: luma stats are mirror-invariant, but keeping the
            // analysis geometry file-true costs nothing and avoids a surprise if a spatial
            // consumer (zone metering) ever lands here.
            renderer.draw(stMatrix, w, h, transfer, false, false, false, sx, sy, roll, crop, centerX, centerY, mirrorX = FrontMirrorConvention.encoderDrawMirrorX(frontRoute, frontStreamPreMirrored))
            buf.rewind()
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            buf.rewind()
            buf.get(bytes, 0, size)
            // GL-thread snapshot: the gain that rendered THIS frame's preview. The readback pixels
            // are unboosted (analysis draw passes digitalGain=1), so the executor applies the
            // matching display LUT to the histogram/waveform values — scopes, the MANUAL meter,
            // and the app-side AE loop then read the same simulated still exposure the finder
            // shows, and the boost is counted exactly once.
            val analysisGain = previewDigitalGain
            // Drained on the GL THREAD, at snapshot time, not on the executor: this must measure the
            // interval between the two FRAMES being compared. Draining on the executor would fold in
            // however long the previous computation and its queue wait took, which varies with load —
            // a rotation attributed to the wrong interval is a fabricated pan.
            // The window is the two FRAMES' own sensor timestamps, not "since I last asked".
            // Those frames left the sensor before this readback ran, so any interval keyed on
            // readback time is offset by the camera pipeline latency — which is invisible during a
            // steady pan and inverts the verdict during a reversing one (device-diagnosed
            // 2026-08-11, see gl/MotionInversion.kt). Keying on the frames cancels the lag.
            val curTsNs = frameTimestampNs
            val rotation = if (doMotion && generation.motionPrevTsNs > 0L && curTsNs > 0L) {
                motionRotationProvider?.invoke(generation.motionPrevTsNs, curTsNs)
            } else {
                null
            }
            // Whether the RETAINED frame can be paired with this one. Read on the GL thread because
            // the flag is GL-thread state; the executor only consumes the answer.
            val motionPaired = doMotion && generation.motionHasHistory &&
                generation.motionHistoryEpoch == evidenceEpoch &&
                generation.motionW == w && generation.motionH == h
            // The rotation the ANALYSIS draw applied — i.e. the app's own afocal correction as it
            // currently stands. That is what makes the verdict mean "is the frame 180 out relative
            // to the CURRENT setting" rather than "is a converter present".
            val motionRotationDeg = previewRotationDeg
            val motionFront = frontRoute
            generation.executor.execute {
                try {
                    val lut = digitalGainDisplayLut(analysisGain)
                    val hist = if (doHist) computeHistogram(bytes, w, h, lut) else null
                    val wave = if (doWave) computeWaveform(bytes, w, h, lut) else null
                    // NOTE the missing `lut` argument: the frame-detail metric reads the RAW,
                    // unboosted snapshot on purpose. Its verdict is about the optics, and must not
                    // move when a display-only brightness simulation moves (gl/FocusDetail.kt).
                    val focus = if (doFocus) computeFocusDetail(bytes, w, h) else null
                    // Same reasoning for the motion rider, and the same missing `lut`: an inverted
                    // image is an optical fact, and a brightness simulation must not reach it.
                    val motion = if (doMotion) {
                        computeMotionInversionFrame(
                            generation,
                            bytes,
                            w,
                            h,
                            motionPaired,
                            rotation,
                            motionRotationDeg,
                            motionFront,
                            curTsNs,
                            evidenceEpoch,
                        )
                    } else {
                        null
                    }
                    if (generation.owner.mayPublish() && analysisGeneration === generation &&
                        motionEvidenceEpoch.get() == evidenceEpoch
                    ) {
                        cb.invoke(hist, wave, focus, motion)
                    }
                } catch (_: Throwable) {
                    // Analysis is best-effort; swallow so a bad frame never surfaces to the UI.
                } finally {
                    generation.owner.release()
                }
            }
        } catch (_: Throwable) {
            // Readback/dispatch failed (e.g. rejected after shutdown); reset the guard and move on.
            runCatching { GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0) }
            generation.owner.release()
        }
    }

    internal fun stop(
        onStopped: (() -> Unit)? = null,
        onResourcesReleased: (() -> Unit)? = null,
    ): GlStopOutcome {
        val ownedAnalysis = analysisGeneration
        ownedAnalysis?.retire()
        val ownedThread = thread
        val ownedHandler = handler
        val ownedReleaseHub = resourceReleaseHub
        val completed = CountDownLatch(1)
        val completion = OnceAction {
            try {
                onStopped?.invoke()
            } finally {
                completed.countDown()
            }
        }
        // Every stop caller for one GL generation shares this strict signal. Unlike the bounded stop
        // notification, it fires only after checked EGL release, including callers whose cleanup
        // Runnable loses a race to another stop for the same thread.
        val localResourceRelease = OnceAction { onResourcesReleased?.invoke() }
        ownedReleaseHub?.subscribe { localResourceRelease.run() }
        fun releaseGenerationResources(): Boolean {
            if (ownedReleaseHub != null) {
                return ownedReleaseHub.runCleanup { releaseGlResources(ownedAnalysis) }
            }
            val released = runCatching { releaseGlResources(ownedAnalysis) }.getOrDefault(false)
            if (released) localResourceRelease.run()
            return released
        }

        if (ownedThread == null || ownedHandler == null) {
            if (terminallyAbandoned) {
                completion.run()
                return GlStopOutcome.ABANDONED
            }
            val safeToCleanHere = ownedThread == null || !ownedThread.isAlive
            if (!safeToCleanHere) {
                completion.run()
                terminallyAbandoned = true
                return GlStopOutcome.ABANDONED
            }
            val resourcesReleased = releaseGenerationResources()
            completion.run()
            if (thread === ownedThread) thread = null
            if (handler === ownedHandler) handler = null
            if (resourceReleaseHub === ownedReleaseHub) resourceReleaseHub = null
            return glStopOutcome(threadExited = true, resourcesReleased = resourcesReleased).also {
                if (it == GlStopOutcome.ABANDONED) terminallyAbandoned = true
            }
        }

        // stop() can be called from a GL callback. Running cleanup directly avoids posting behind
        // ourselves and then deadlocking while waiting for that queued task on the same thread.
        if (Thread.currentThread() === ownedThread) {
            var resourcesReleased = false
            try {
                resourcesReleased = releaseGenerationResources()
            } finally {
                completion.run()
                runCatching { ownedThread.quitSafely() }
                if (thread === ownedThread) thread = null
                if (handler === ownedHandler) handler = null
                if (resourceReleaseHub === ownedReleaseHub) resourceReleaseHub = null
            }
            // We cannot join the current GL thread, and quitSafely may still drain queued work.
            // Retire the object even after a successful release so that work cannot cross into a
            // restarted generation on the same mutable facade.
            return glStopOutcome(threadExited = false, resourcesReleased = resourcesReleased).also {
                if (it == GlStopOutcome.ABANDONED) terminallyAbandoned = true
            }
        }

        val resourceReleaseResult = AtomicReference<Boolean?>(null)
        val cleanup = Runnable {
            try {
                // If a bounded stop timed out and a new generation has since started, this old
                // Runnable must not tear down the replacement generation's EGL state.
                if (thread === ownedThread && !terminallyAbandoned) {
                    resourceReleaseResult.set(releaseGenerationResources())
                }
            } finally {
                completion.run()
                // Clear ownership only from the generation that actually performed cleanup. A
                // bounded caller-side timeout keeps these references intact so a late accepted
                // cleanup can still recognize and release its own generation.
                if (thread === ownedThread) thread = null
                if (handler === ownedHandler) handler = null
                if (resourceReleaseHub === ownedReleaseHub) resourceReleaseHub = null
            }
        }
        val accepted = runCatching { ownedHandler.post(cleanup) }.getOrDefault(false)
        runCatching { ownedThread.quitSafely() }

        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STOP_TIMEOUT_MS)
        if (accepted) {
            runCatching { completed.await(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        }
        val remainingNs = deadlineNs - System.nanoTime()
        if (ownedThread.isAlive && remainingNs > 0L) {
            val joinMs = TimeUnit.NANOSECONDS.toMillis(remainingNs).coerceAtLeast(1L)
            runCatching { ownedThread.join(joinMs) }
        }

        val threadExited = !ownedThread.isAlive
        val cleanupCompleted = completed.count == 0L
        if (threadExited && !cleanupCompleted) {
            // The task was rejected, or the looper died after accepting it. With the owned thread
            // now gone no GL work can race this caller-side fallback cleanup.
            resourceReleaseResult.set(releaseGenerationResources())
        }
        // A wedged GL thread may outlive the bounded wait. Deliver completion once so release cannot
        // hang indefinitely; the late cleanup Runnable (if accepted) will observe the same one-shot.
        completion.run()
        val outcome = glStopOutcome(
            threadExited = threadExited,
            resourcesReleased = resourceReleaseResult.get() == true || ownedReleaseHub?.isReleased() == true,
        )
        if (outcome == GlStopOutcome.STOPPED) {
            if (thread === ownedThread) thread = null
            if (handler === ownedHandler) handler = null
            if (resourceReleaseHub === ownedReleaseHub) resourceReleaseHub = null
        } else {
            // DELIBERATE GENERATION ABANDON (DBG4-3): the GL thread is wedged inside native code
            // past the bounded stop, OR checked EGL ownership/output release failed even though the
            // thread exited. Either state is unsafe for reuse. Drop the ownership references (the
            // VideoRecorder drain-wedge pattern) and make this object permanently terminal. CameraEngine
            // installs a separate GlPipeline, so late work stays confined to the retired instance.
            terminallyAbandoned = true
            if (thread === ownedThread) thread = null
            if (handler === ownedHandler) handler = null
            if (resourceReleaseHub === ownedReleaseHub) resourceReleaseHub = null
        }
        return outcome
    }

    /** Releases all GL-owned resources. Runs on the GL thread, or after that thread has exited. */
    private fun releaseGlResources(ownedAnalysis: AnalysisGeneration?): Boolean {
        val core = egl
        var outputsReleased = core == null && encoderEgl == EGL14.EGL_NO_SURFACE &&
            !unsafeOutputAbandoned
        if (core != null) {
            // Caller-thread fallback needs to make the context current before deleting renderer/FBO
            // objects. The normal GL-thread path also benefits from not relying on whichever output
            // happened to receive the last draw.
            val current = when {
                previewEgl != EGL14.EGL_NO_SURFACE -> previewEgl
                encoderEgl != EGL14.EGL_NO_SURFACE -> encoderEgl
                else -> EGL14.EGL_NO_SURFACE
            }
            if (current != EGL14.EGL_NO_SURFACE) runCatching { core.makeCurrent(current) }
            frameNotifications?.cancel()
            frameNotifications = null
            runCatching { surfaceTexture?.setOnFrameAvailableListener(null) }
            runCatching { surfaceTexture?.release() }
            runCatching { inputSurface?.release() }
            runCatching {
                ownedAnalysis?.let { analysis ->
                    if (analysis.fbo != 0) {
                        GLES20.glDeleteFramebuffers(1, intArrayOf(analysis.fbo), 0)
                    }
                    if (analysis.texture != 0) {
                        GLES20.glDeleteTextures(1, intArrayOf(analysis.texture), 0)
                    }
                }
                renderer.release()
            }
            // Surface destruction is not an unbind in EGL. Relinquish every native window before
            // destroying either handle so stop completion is a real ownership boundary.
            val currentOwnershipReleased = runCatching { core.releaseCurrentOwnership() }.isSuccess
            if (currentOwnershipReleased) {
                val encoderReleased = encoderEgl == EGL14.EGL_NO_SURFACE ||
                    runCatching { core.releaseSurface(encoderEgl) }.isSuccess
                val previewReleased = previewEgl == EGL14.EGL_NO_SURFACE ||
                    runCatching { core.releaseSurface(previewEgl) }.isSuccess
                val orphansReleased = orphanedEglOutputs.releaseAllBestEffort(core::releaseSurface)
                val displayTerminated = core.releaseAfterCurrentOwnership()
                val surfacesDestroyed = encoderReleased && previewReleased && orphansReleased
                outputsReleased = outputReleaseProven(
                    currentOwnershipReleased = true,
                    surfacesDestroyed = surfacesDestroyed,
                    displayTerminated = displayTerminated,
                )
            }
        } else {
            frameNotifications?.cancel()
            frameNotifications = null
            runCatching { surfaceTexture?.setOnFrameAvailableListener(null) }
            runCatching { surfaceTexture?.release() }
            runCatching { inputSurface?.release() }
        }
        surfaceTexture = null
        inputSurface = null
        previewSurface = null
        previewEgl = EGL14.EGL_NO_SURFACE
        encoderEgl = EGL14.EGL_NO_SURFACE
        orphanedEglOutputs.abandon()
        previewSignal?.cancel()
        previewSignal = null
        encoderSignal?.cancel(CancellationException("GL pipeline stopped before encoder ready"))
        encoderSignal = null
        ownedAnalysis?.apply {
            fbo = 0
            texture = 0
            textureW = 0
            textureH = 0
            clearSnapshots()
        }
        if (analysisGeneration === ownedAnalysis) analysisGeneration = null
        resetEncoderTimestampBase()
        lastDrawMs = 0L
        egl = null
        inited = false
        if (!outputsReleased) unsafeOutputAbandoned = true
        return outputsReleased
    }

    private inline fun post(crossinline block: () -> Unit) {
        handler?.post { block() }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 1_500L
        const val ENCODER_FIRST_FRAME_TIMEOUT_MS = 2_000L
    }
}

internal enum class GlStopOutcome { STOPPED, ABANDONED }

/** Pure stop classification: both thread exit and checked native-output release are required. */
internal fun glStopOutcome(
    threadExited: Boolean,
    resourcesReleased: Boolean,
): GlStopOutcome =
    if (threadExited && resourcesReleased) GlStopOutcome.STOPPED else GlStopOutcome.ABANDONED

internal data class AnalysisTargetSize(val width: Int, val height: Int)
internal data class AnalysisFrame(val crop: Float, val centerX: Float, val centerY: Float)

/** Analysis deliberately excludes the preview-only punch-in crop and movable loupe center. */
internal fun analysisFrame(captureCrop: Float): AnalysisFrame = AnalysisFrame(captureCrop, 0.5f, 0.5f)

/**
 * The transfer for the scopes/AE analysis re-draw: ALWAYS display-referred (null), whatever the
 * PREVIEW currently renders (AGG4-9/P3.4). With a log profile selected the preview shows the flat
 * curve for monitoring, but the METER must read scene brightness in the same domain the AE loop's
 * targets are defined in — metering the log curve put 18% grey at the curve's grey anchor (~0.41
 * S-Log3 / ~0.39 LogC3) instead of 0.18 and settled the app-side AE ~1.5 stops off. Pure seam so
 * a host test pins that toggling a log profile cannot move the metered domain.
 */
internal fun analysisReadbackTransfer(previewTransfer: ColorTransfer?): ColorTransfer? = null

/** Preserves the rendered frame's orientation/aspect while bounding synchronous RGBA readback. */
internal fun analysisTargetSize(width: Int, height: Int, maxLongEdge: Int = 256): AnalysisTargetSize {
    if (width <= 0 || height <= 0 || maxLongEdge <= 0) return AnalysisTargetSize(1, 1)
    return if (width >= height) {
        AnalysisTargetSize(maxLongEdge, (height.toDouble() * maxLongEdge / width).toInt().coerceAtLeast(1))
    } else {
        AnalysisTargetSize((width.toDouble() * maxLongEdge / height).toInt().coerceAtLeast(1), maxLongEdge)
    }
}

/**
 * Moves EGL current ownership away from an outgoing surface before destroying it. If the preferred
 * fallback is stale, making nothing current is still a valid verified unbind; destruction never
 * runs when both transitions fail. Kept Android-free so the native lifetime order is unit-testable.
 */
internal fun detachEglOutput(
    hasFallback: Boolean,
    makeFallbackCurrent: () -> Unit,
    makeNothingCurrent: () -> Unit,
    destroy: () -> Unit,
) {
    if (hasFallback) {
        try {
            makeFallbackCurrent()
        } catch (_: Throwable) {
            makeNothingCurrent()
        }
    } else {
        makeNothingCurrent()
    }
    destroy()
}

/** Checked create/bind/restore transaction for a candidate output, with primary-error retention. */
internal fun <T> prepareEglOutput(
    create: () -> T,
    makeCandidateCurrent: (T) -> Unit,
    restoreCurrent: () -> Unit,
    discardCandidate: (T) -> Unit,
): T {
    val candidate = create()
    try {
        makeCandidateCurrent(candidate)
        restoreCurrent()
        return candidate
    } catch (failure: Throwable) {
        runCatching { discardCandidate(candidate) }
            .exceptionOrNull()
            ?.let(failure::addSuppressed)
        throw failure
    }
}

/** A missing timeout task would leave recorder admission pending forever, so rejection is fatal. */
internal fun scheduleCheckedDelay(
    postDelayed: (Runnable, Long) -> Boolean,
    delayMs: Long,
    action: () -> Unit,
) {
    check(postDelayed(Runnable(action), delayMs)) { "Delayed GL task rejected" }
}

/** Codec teardown needs a verified unbind plus either destroyed outputs or terminal EGL display. */
internal fun outputReleaseProven(
    currentOwnershipReleased: Boolean,
    surfacesDestroyed: Boolean,
    displayTerminated: Boolean,
): Boolean = currentOwnershipReleased && (surfacesDestroyed || displayTerminated)

/**
 * Exactly-once bridge from one encoder output generation to recorder ownership. Allocation/bind is
 * still [State.PENDING]; the first real swap publishes [State.READY]. A failure before READY is an
 * attachment result, while a later failure is a runtime recorder failure. Normal detach cancels a
 * pending result but never manufactures a runtime failure for a recorder the caller already claimed.
 */
internal class EncoderOutputSignal(
    private val onAttached: (Result<Unit>) -> Unit,
    private val onRuntimeFailure: (Throwable) -> Unit,
) {
    private enum class State { PENDING, READY, TERMINAL }

    private var state = State.PENDING

    @Synchronized
    fun isPending(): Boolean = state == State.PENDING

    @Synchronized
    fun isActive(): Boolean = state != State.TERMINAL

    fun ready(): Boolean {
        val deliver = synchronized(this) {
            if (state != State.PENDING) false else {
                state = State.READY
                true
            }
        }
        if (deliver) runCatching { onAttached(Result.success(Unit)) }
        return deliver
    }

    fun fail(failure: Throwable): Boolean {
        val wasPending = synchronized(this) {
            when (state) {
                State.PENDING -> true.also { state = State.TERMINAL }
                State.READY -> false.also { state = State.TERMINAL }
                State.TERMINAL -> return false
            }
        }
        if (wasPending) {
            runCatching { onAttached(Result.failure(failure)) }
        } else {
            runCatching { onRuntimeFailure(failure) }
        }
        return true
    }

    fun cancel(cause: Throwable): Boolean {
        val wasPending = synchronized(this) {
            when (state) {
                State.PENDING -> true.also { state = State.TERMINAL }
                State.READY -> false.also { state = State.TERMINAL }
                State.TERMINAL -> return false
            }
        }
        if (wasPending) runCatching { onAttached(Result.failure(cause)) }
        return true
    }
}

/** Output whose EGL context owns acquisition of the next real camera texture frame. */
internal enum class FrameAcquisitionOwner { NONE, PREVIEW, ENCODER }

internal fun frameAcquisitionOwner(
    previewAvailable: Boolean,
    encoderActive: Boolean,
): FrameAcquisitionOwner = when {
    previewAvailable -> FrameAcquisitionOwner.PREVIEW
    encoderActive -> FrameAcquisitionOwner.ENCODER
    else -> FrameAcquisitionOwner.NONE
}

/** Exactly-once health bridge for one TextureView/EGL preview-output generation. */
internal class PreviewOutputSignal(
    private val onReady: () -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private enum class State { PENDING, READY, TERMINAL }

    private var state = State.PENDING

    fun ready(): Boolean {
        val deliver = synchronized(this) {
            if (state != State.PENDING) false else {
                state = State.READY
                true
            }
        }
        if (deliver) runCatching(onReady)
        return deliver
    }

    fun fail(failure: Throwable): Boolean {
        val deliver = synchronized(this) {
            if (state == State.TERMINAL) false else {
                state = State.TERMINAL
                true
            }
        }
        if (deliver) runCatching { onFailure(failure) }
        return deliver
    }

    fun cancel(): Boolean = synchronized(this) {
        if (state == State.TERMINAL) false else {
            state = State.TERMINAL
            true
        }
    }
}

/** Cached-frame self-redraws can present pixels, but only a producer-fed swap proves preview health. */
internal fun PreviewOutputSignal.readyAfterSwap(realCameraFrame: Boolean): Boolean =
    realCameraFrame && ready()

/**
 * Single-in-flight gate owned by one immutable analysis generation. Retirement is synchronous;
 * work that lost the race cannot publish, and its release can only mutate this owner's guard.
 */
internal class AnalysisGenerationOwner(
    /** Deterministic interleaving seam for the otherwise nanosecond-wide retire/acquire race. */
    private val afterBusyAcquired: (() -> Unit)? = null,
) {
    private val retired = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)

    fun tryAcquire(): Boolean {
        if (retired.get() || !busy.compareAndSet(false, true)) return false
        afterBusyAcquired?.invoke()
        if (!retired.get()) return true
        busy.compareAndSet(true, false)
        return false
    }

    fun retire() {
        retired.set(true)
        busy.set(false)
    }

    fun mayPublish(): Boolean = !retired.get()

    fun release() {
        busy.compareAndSet(true, false)
    }

    fun isBusy(): Boolean = busy.get()
}

/** Executes [callback] at most once across racing GL/caller threads. Callback failures are sealed. */
internal class OnceAction(private val callback: () -> Unit) {
    private val delivered = AtomicBoolean(false)

    fun run(): Boolean {
        if (!delivered.compareAndSet(false, true)) return false
        runCatching(callback)
        return true
    }
}

/** Owns provisional native outputs until their checked destruction succeeds or shutdown abandons. */
internal class RetainedOutputs<T> {
    private val values = mutableListOf<T>()

    fun retain(value: T) {
        values += value
    }

    fun releaseAll(release: (T) -> Unit) {
        while (values.isNotEmpty()) {
            release(values.first())
            values.removeAt(0)
        }
    }

    fun releaseAllBestEffort(release: (T) -> Unit): Boolean {
        val results = values.map { value -> runCatching { release(value) }.isSuccess }
        values.clear()
        return results.all { it }
    }

    fun abandon() {
        values.clear()
    }
}

/** Shared, exactly-once native-resource boundary for every stop caller in one GL generation. */
internal class ResourceReleaseHub {
    private var released = false
    private var cleanupClaimed = false
    private val listeners = mutableListOf<() -> Unit>()

    fun subscribe(listener: () -> Unit) {
        val deliverNow = synchronized(this) {
            if (released) {
                true
            } else {
                listeners += listener
                false
            }
        }
        if (deliverNow) runCatching(listener)
    }

    fun release(): Boolean {
        val pending = synchronized(this) {
            if (released) return false
            released = true
            listeners.toList().also { listeners.clear() }
        }
        pending.forEach { runCatching(it) }
        return true
    }

    fun isReleased(): Boolean = synchronized(this) { released }

    /** At most one stop caller may mutate a generation's EGL state, including fallback cleanup. */
    fun runCleanup(cleanup: () -> Boolean): Boolean {
        val claimed = synchronized(this) {
            when {
                released -> return true
                cleanupClaimed -> false
                else -> true.also { cleanupClaimed = true }
            }
        }
        if (!claimed) return false
        val success = runCatching(cleanup).getOrDefault(false)
        if (success) release()
        return success
    }
}

/**
 * Posts [block] and delivers its [Result] exactly once after it runs. If [post] rejects or throws,
 * failure runs inline so native-teardown waiters cannot be stranded on a dead looper. Work failures
 * are contained in the result and never escape through the target looper.
 */
internal fun dispatchWithResult(
    post: (Runnable) -> Boolean,
    block: () -> Unit,
    onComplete: (Result<Unit>) -> Unit,
): Boolean {
    val delivered = AtomicBoolean(false)
    fun deliver(result: Result<Unit>) {
        if (delivered.compareAndSet(false, true)) runCatching { onComplete(result) }
    }
    val task = Runnable {
        deliver(runCatching(block))
    }
    return try {
        val accepted = post(task)
        if (!accepted) deliver(Result.failure(RejectedExecutionException("Task rejected")))
        accepted
    } catch (failure: Throwable) {
        deliver(Result.failure(failure))
        false
    }
}

// Pure scope-analysis math, hoisted out of GlPipeline (they hold no instance state — just the RGBA
// snapshot + dimensions) so they are unit-testable off-GL-thread, matching the codebase's pure-seam
// pattern (e.g. camera/meteringRect, camera/centerCropBox).

/** RGBA snapshot -> luma + per-channel 256-bin histograms, subsampled for speed (Rec.2020 luma). */
/**
 * Display-referred byte LUT mirroring the shader's `dgain`: BT.1886 decode → linear ×[gain] →
 * clamp → re-encode. Applied to the UNBOOSTED analysis readback's per-pixel values before binning
 * so the scope/meter story matches the simulated preview while the readback stays sensor-true.
 * Null at ~unity (the common case) so the hot loops keep their LUT-free shape; the 256-entry
 * allocation only happens on the ~6 Hz analysis executor while a boost is active.
 */
internal fun digitalGainDisplayLut(gain: Float): IntArray? {
    if (gain <= 1.001f) return null
    val lut = IntArray(256)
    val g = gain.toDouble()
    for (v in 0..255) {
        val lin = Math.pow(v / 255.0, SdrToHlgMapping.SDR_EOTF_GAMMA) * g
        val enc = Math.pow(minOf(lin, 1.0), 1.0 / SdrToHlgMapping.SDR_EOTF_GAMMA)
        lut[v] = (enc * 255.0).roundToInt().coerceIn(0, 255)
    }
    return lut
}

internal fun computeHistogram(bytes: ByteArray, w: Int, h: Int, lut: IntArray? = null): HistogramData {
    val luma = IntArray(256)
    val red = IntArray(256)
    val green = IntArray(256)
    val blue = IntArray(256)
    // ADAPTIVE stride: the fixed step 6 predates the 2026-07-14 FBO downsample — it was sized for
    // the full-resolution readback. Against today's ≤256-long-edge analysis buffer it left only
    // ~1.4k samples for 256 bins (~5/bin — a spiky, starved histogram). A ≤256 buffer is ~49k
    // pixels, trivially full-scannable at the ~6 Hz analysis cadence; larger (hypothetical) inputs
    // keep a stride that lands ~256 samples per axis.
    val step = maxOf(1, maxOf(w, h) / 256)
    var y = 0
    while (y < h) {
        val rowBase = y * w * 4
        var x = 0
        while (x < w) {
            val i = rowBase + x * 4
            var r = bytes[i].toInt() and 0xFF
            var g = bytes[i + 1].toInt() and 0xFF
            var b = bytes[i + 2].toInt() and 0xFF
            // Boost BEFORE the luma weighting, exactly like the shader gains rgb before `base`.
            if (lut != null) {
                r = lut[r]
                g = lut[g]
                b = lut[b]
            }
            val l = (0.2627f * r + 0.678f * g + 0.0593f * b).toInt().coerceIn(0, 255)
            luma[l]++
            red[r]++
            green[g]++
            blue[b]++
            x += step
        }
        y += step
    }
    return HistogramData(luma, red, green, blue)
}

/** RGBA snapshot -> 128x64 luma waveform, subsampled; row 0 = brightest (top). */
internal fun computeWaveform(bytes: ByteArray, w: Int, h: Int, lut: IntArray? = null): WaveformData {
    val columns = 128
    val rows = 64
    val bins = IntArray(columns * rows)
    // ADAPTIVE stride (see computeHistogram): with the fixed step 6 against the ≤256 analysis
    // buffer, x visited only every 6th source pixel of ~192, so col = x*128/192 filled every 4TH
    // waveform column — 96 of 128 columns were PERMANENTLY empty and the monitor rendered as
    // spaced dots. The stride must never exceed the source-pixels-per-column ratio (w/columns),
    // and a full scan of a ≤256 buffer is cheap; only oversized inputs stride.
    val step = maxOf(1, minOf(w / columns, h / rows))
    var y = 0
    while (y < h) {
        val rowBase = y * w * 4
        var x = 0
        while (x < w) {
            val i = rowBase + x * 4
            var r = bytes[i].toInt() and 0xFF
            var g = bytes[i + 1].toInt() and 0xFF
            var b = bytes[i + 2].toInt() and 0xFF
            if (lut != null) {
                r = lut[r]
                g = lut[g]
                b = lut[b]
            }
            val l = (0.2627f * r + 0.678f * g + 0.0593f * b).toInt().coerceIn(0, 255)
            val col = (x * columns / w).coerceIn(0, columns - 1)
            val row = ((255 - l) * rows / 256).coerceIn(0, rows - 1)
            bins[col * rows + row]++
            x += step
        }
        y += step
    }
    return WaveformData(columns, rows, bins)
}
