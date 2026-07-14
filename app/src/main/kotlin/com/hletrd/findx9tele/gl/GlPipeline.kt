package com.hletrd.findx9tele.gl

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.hletrd.findx9tele.camera.ColorTransfer
import com.hletrd.findx9tele.camera.HistogramData
import com.hletrd.findx9tele.camera.WaveformData
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * Owns the GL render thread. The camera renders into [inputSurface] (an external SurfaceTexture);
 * each frame is drawn once to the on-screen preview and, while recording, once more to the video
 * encoder's input surface — both 180°-flipped by [FlipRenderer]. Single EGL context, single thread.
 *
 * Lifecycle: [start] creates the context; the GL objects (texture + SurfaceTexture) are created when
 * the preview [setPreviewOutput] surface first arrives, at which point [onInputReady] fires so the
 * caller can open the camera against [inputSurface].
 */
class GlPipeline {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var egl: EglCore? = null
    private val renderer = FlipRenderer()

    private var surfaceTexture: SurfaceTexture? = null

    @Volatile
    var inputSurface: Surface? = null
        private set

    private var previewEgl: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderEgl: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewSurface: Surface? = null

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
    private var gammaAssist = false
    // True while the HAL-native O-Log2 stream is engaged: the frames arriving here are ALREADY log
    // (scene-referred), so the preview passes through (flat) or de-logs for Gamma Display Assist.
    private var nativeLog = false
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
    private var punchIn = false
    // Movable focus loupe: texcoord point the punch-in zoom magnifies (0.5,0.5 = frame center), set
    // from the tapped point so the loupe follows an off-center subject. Preview-only.
    private var punchInX = 0.5f
    private var punchInY = 0.5f

    // Gyro EIS: provider returns [yaw, pitch, roll] shake radians; eisFocal scales to the effective
    // (teleconverter) focal length in image widths; eisCrop is the headroom (e.g. 0.10).
    private var eisEnabled = false
    private var eisFocal = 0f
    private var eisCrop = 0f
    private var eisProvider: (() -> FloatArray)? = null

    // Real-time scope analysis (histogram + waveform) via GL readback. The glReadPixels call runs on
    // the GL thread and is throttled; the per-pixel compute is dispatched to [analysisExecutor] so it
    // never stalls rendering. Perf note: reading back at full preview resolution every ~12th frame is
    // a tradeoff (GPU->CPU stall + copy) that should be profiled/tuned on device.
    private var analysisHistogram = false
    private var analysisWaveform = false
    // Force the luma readback on (independent of the user's scope toggles) so the app-side
    // auto-exposure loop in SHUTTER/ISO-priority always has fresh luma to meter from.
    private var analysisAe = false
    private var analysisCallback: ((HistogramData?, WaveformData?) -> Unit)? = null
    private var analysisFrameCounter = 0
    // Small offscreen target for the analysis readback: the scene is re-drawn (no assist overlays)
    // at 256×192 and read back at ~190 KB, instead of glReadPixels on the FULL preview framebuffer
    // (a 4K preview = ~33 MB copy that stalled the GL thread every 5th frame — visible as periodic
    // preview/zoom stutter, and it polluted the meter with peaking/zebra pixels).
    private var analysisFbo = 0
    private var analysisTex = 0
    private var analysisBuffer: ByteBuffer? = null
    private var analysisBytes: ByteArray? = null
    private var analysisBufferW = 0
    private var analysisBufferH = 0

    // Guards single-in-flight analysis: only the GL thread sets it true (before dispatch), only the
    // executor sets it false (when done). This lets [analysisBytes] be reused across readbacks without
    // the GL thread overwriting a snapshot the executor is still reading.
    @Volatile
    private var analysisBusy = false
    // var, not val: stop() shuts it down, and a val made the whole pipeline silently single-use —
    // a future restart would have dead scopes and app-side AE. start() recreates it when needed.
    private var analysisExecutor = Executors.newSingleThreadExecutor()

    private var inited = false
    private val stMatrix = FloatArray(16)
    private var onInputReady: ((Surface) -> Unit)? = null

    fun start(tenBit: Boolean, onInputReady: (Surface) -> Unit) {
        this.tenBit = tenBit
        this.onInputReady = onInputReady
        // A prior stop() shut the analysis executor down; a restarted pipeline needs a live one or
        // scopes + the app-side AE loop silently never run again.
        if (analysisExecutor.isShutdown) analysisExecutor = Executors.newSingleThreadExecutor()
        val t = HandlerThread("gl-pipeline").also { it.start() }
        thread = t
        handler = Handler(t.looper)
        post { egl = EglCore(tenBit = tenBit) }
    }

    fun setPreviewOutput(surface: Surface?, width: Int, height: Int) = post {
        val core = egl ?: return@post
        if (surface == null) {
            if (previewEgl != EGL14.EGL_NO_SURFACE) {
                core.releaseSurface(previewEgl)
                previewEgl = EGL14.EGL_NO_SURFACE
            }
            previewSurface = null
            return@post
        }
        // The TextureView host can deliver available-then-size-changed back-to-back on the same
        // native window; if it's the same surface already bound at the same size, there is nothing
        // to do — recreating the EGLSurface on a still-live native window throws EGL_BAD_ALLOC.
        if (surface === previewSurface && previewEgl != EGL14.EGL_NO_SURFACE &&
            width == previewW && height == previewH
        ) {
            previewW = width
            previewH = height
            return@post
        }
        if (previewEgl != EGL14.EGL_NO_SURFACE) {
            core.releaseSurface(previewEgl)
            previewEgl = EGL14.EGL_NO_SURFACE
        }
        previewW = width
        previewH = height
        previewSurface = surface
        previewEgl = core.createWindowSurface(surface)
        core.makeCurrent(previewEgl)
        if (!inited) {
            val texId = renderer.init()
            val st = SurfaceTexture(texId)
            st.setDefaultBufferSize(cameraW, cameraH)
            // The listener already runs on the GL handler thread; call drawFrame() directly instead
            // of re-posting a fresh Runnable every frame.
            st.setOnFrameAvailableListener({ drawFrame() }, handler)
            surfaceTexture = st
            val input = Surface(st)
            inputSurface = input
            inited = true
            onInputReady?.invoke(input)
        }
    }

    /** Camera output resolution feeding the SurfaceTexture; controls aspect + peaking texel size. */
    fun setCameraPreviewSize(width: Int, height: Int) = post {
        cameraW = width.coerceAtLeast(1)
        cameraH = height.coerceAtLeast(1)
        surfaceTexture?.setDefaultBufferSize(cameraW, cameraH)
        renderer.setPreviewSize(cameraW, cameraH)
    }

    fun setRotationDegrees(deg: Int) = post { renderer.setRotationDegrees(deg) }
    fun setSensorOrientation(deg: Int) = post { renderer.setSensorOrientation(deg) }
    fun setTransfer(t: ColorTransfer?) = post { transfer = t }

    /** Gamma Display Assist: monitor shows the normal 709-ish image while the FILE stays log. */
    fun setGammaAssist(enabled: Boolean) = post { gammaAssist = enabled }

    /** HAL-native log engaged: frames are already scene-referred O-Log2 (see [nativeLog]). */
    fun setNativeLog(enabled: Boolean) = post { nativeLog = enabled }
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

    /** Sink for computed scopes; invoked on the [analysisExecutor] thread, not the GL thread. */
    fun setAnalysisCallback(cb: ((HistogramData?, WaveformData?) -> Unit)?) = post {
        analysisCallback = cb
    }

    /**
     * Swap (or clear) the encoder EGL surface. [onApplied] is invoked ON THE GL THREAD once the
     * change has actually landed, so an ordered encoder teardown (dispatch `finishRecording` only
     * after the encoder surface is truly released) can chain off it. If the GL thread does not exist
     * (pipeline never started / already stopped), [onApplied] runs inline right here so a caller
     * awaiting the completion never hangs.
     */
    fun setEncoderOutput(surface: Surface?, width: Int, height: Int, onApplied: (() -> Unit)? = null) {
        val h = handler
        if (h == null) {
            onApplied?.invoke()
            return
        }
        h.post {
            val core = egl
            if (core != null) {
                if (encoderEgl != EGL14.EGL_NO_SURFACE) {
                    core.releaseSurface(encoderEgl)
                    encoderEgl = EGL14.EGL_NO_SURFACE
                }
                // Reset the presentation-time base whenever the encoder surface changes (record
                // start/stop) so the next recording rebases from its own first frame.
                encoderBaseSet = false
                encoderBaseNs = 0L
                if (surface != null) {
                    encoderW = width
                    encoderH = height
                    encoderEgl = core.createWindowSurface(surface)
                }
            }
            // Signal completion AFTER the surface change is applied, on the GL thread; fires even when
            // egl is null (nothing to apply) so an ordered-teardown caller is never left waiting.
            onApplied?.invoke()
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

    private fun drawFrame(updateTex: Boolean = true) {
        if (com.hletrd.findx9tele.BuildConfig.DEBUG) {
            val now = android.os.SystemClock.uptimeMillis()
            if (lastDrawMs != 0L && now - lastDrawMs > 50) {
                android.util.Log.i("GlPipeline", "FrameGap: ${now - lastDrawMs} ms")
            }
            lastDrawMs = now
        }
        val core = egl ?: return
        val st = surfaceTexture ?: return
        if (previewEgl == EGL14.EGL_NO_SURFACE) return

        if (updateTex) {
            st.updateTexImage()
            st.getTransformMatrix(stMatrix)
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
        val previewCrop = if (punchIn) maxOf(crop, 0.6f) else crop

        // The loupe (movable punch-in) recenters the preview zoom on the tapped point; the encoder
        // draw below stays centered so recordings are unaffected.
        val loupeX = if (punchIn) punchInX else 0.5f
        val loupeY = if (punchIn) punchInY else 0.5f
        // Show the LOG (O-Log2) curve FLAT in the live preview so the user can monitor that they're on a
        // log profile — previously the preview was hardcoded to SDR (null) and only the encoder got the
        // curve, so LOG never looked flat on screen. HLG/SDR keep a natural SDR preview (an HLG curve on
        // this SDR preview surface would just look washed; HDR is monitored on an HDR display, not here).
        // LOG = GL O-Log2: the preview renders the same flat curve the encoder bakes, or — with Gamma
        // Display Assist ON — skips it and shows the normal display-referred image (the FILE always
        // gets the curve). HLG/SDR keep the natural SDR preview. delogAssist stays dormant: it is only
        // for a true scene-referred (native/CameraUnit) stream, which third-party Camera2 cannot get.
        val previewTransfer = if (transfer == ColorTransfer.LOG && !gammaAssist) ColorTransfer.LOG else null
        core.makeCurrent(previewEgl)
        renderer.draw(
            stMatrix, previewW, previewH, previewTransfer, peaking, zebra, falseColor, sx, sy, roll, previewCrop, loupeX, loupeY,
            peakThreshold = peakThreshold, peakR = peakR, peakG = peakG, peakB = peakB, zebraThreshold = zebraThreshold,
            delogAssist = nativeLog && gammaAssist,
            zoomComp = zoomTarget / halZoom.coerceAtLeast(0.01f),
        )
        core.swapBuffers(previewEgl)

        // Self-redraws only refresh the PREVIEW with a new zoom crop from the last frame — the
        // analysis meter and (critically) the encoder must only ever see REAL camera frames.
        if (!updateTex) return

        if (encoderEgl != EGL14.EGL_NO_SURFACE) {
            core.makeCurrent(encoderEgl)
            renderer.draw(stMatrix, encoderW, encoderH, transfer, false, false, false, sx, sy, roll, crop)
            // Rebase to the first recorded frame so video PTS starts near 0 like the audio track.
            val ts = st.timestamp
            if (!encoderBaseSet && ts > 0L) { encoderBaseNs = ts; encoderBaseSet = true }
            core.setPresentationTime(encoderEgl, if (encoderBaseSet) ts - encoderBaseNs else 0L)
            core.swapBuffers(encoderEgl)
        }

        // Additive scope analysis: throttled GL readback of the just-drawn preview, computed off-thread.
        // Kept entirely defensive so it can never block or crash the preview/encoder draws above.
        // Ordered AFTER the encoder draw+swap on purpose: the full-surface glReadPixels stalls the GL
        // thread, so running it before the encoder draw would push the readback in front of every
        // recorded frame — encoder frame pacing beats scope latency (scopes only refresh ~6×/s anyway).
        if ((analysisHistogram || analysisWaveform || analysisAe) && analysisCallback != null) {
            // Refresh the scopes / AE meter ~6×/s (every 5th frame at 30 fps) — snappy without stalling
            // the 4K preview on the readback. (Was every 12th ≈ 2.5×/s, which felt laggy.)
            if (++analysisFrameCounter >= 5) {
                analysisFrameCounter = 0
                runAnalysisReadback(core, previewTransfer, sx, sy, roll, previewCrop, loupeX, loupeY)
            }
        }
    }

    /**
     * Reads back the current preview framebuffer and dispatches per-pixel scope computation to
     * [analysisExecutor]. Runs on the GL thread; [analysisBusy] ensures only one readback is in flight
     * so the reused [analysisBytes] snapshot is never overwritten while the executor reads it. Fully
     * wrapped so any failure degrades to "no scopes this frame" rather than breaking rendering.
     */
    private fun runAnalysisReadback(
        core: EglCore,
        transfer: ColorTransfer?,
        sx: Float,
        sy: Float,
        roll: Float,
        crop: Float,
        centerX: Float,
        centerY: Float,
    ) {
        if (previewEgl == EGL14.EGL_NO_SURFACE || previewW <= 0 || previewH <= 0) return
        if (analysisBusy) return
        val cb = analysisCallback ?: return
        // AE metering needs the luma histogram; compute it whenever AE is active even if the user's
        // histogram overlay is off (the callback consumer picks luma out and ignores the rest).
        val doHist = analysisHistogram || analysisAe
        val doWave = analysisWaveform
        if (!doHist && !doWave) return
        try {
            val w = ANALYSIS_W
            val h = ANALYSIS_H
            val size = w * h * 4
            if (analysisBuffer == null || analysisBufferW != w || analysisBufferH != h) {
                analysisBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
                analysisBytes = ByteArray(size)
                analysisBufferW = w
                analysisBufferH = h
            }
            val buf = analysisBuffer ?: return
            val bytes = analysisBytes ?: return
            core.makeCurrent(previewEgl)
            if (analysisFbo == 0) {
                val ids = IntArray(1)
                GLES20.glGenTextures(1, ids, 0)
                analysisTex = ids[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, analysisTex)
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
                )
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glGenFramebuffers(1, ids, 0)
                analysisFbo = ids[0]
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, analysisFbo)
                GLES20.glFramebufferTexture2D(
                    GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, analysisTex, 0,
                )
            } else {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, analysisFbo)
            }
            // Re-draw the scene (same transform/EIS/crop as the preview, minus peaking/zebra/false-
            // color so assist overlays never pollute the meter) into the small target and read THAT.
            renderer.draw(stMatrix, w, h, transfer, false, false, false, sx, sy, roll, crop, centerX, centerY)
            buf.rewind()
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            buf.rewind()
            buf.get(bytes, 0, size)
            analysisBusy = true
            analysisExecutor.execute {
                try {
                    val hist = if (doHist) computeHistogram(bytes, w, h) else null
                    val wave = if (doWave) computeWaveform(bytes, w, h) else null
                    cb.invoke(hist, wave)
                } catch (_: Throwable) {
                    // Analysis is best-effort; swallow so a bad frame never surfaces to the UI.
                } finally {
                    analysisBusy = false
                }
            }
        } catch (_: Throwable) {
            // Readback/dispatch failed (e.g. rejected after shutdown); reset the guard and move on.
            analysisBusy = false
        }
    }

    fun stop() {
        analysisExecutor.shutdown()
        val h = handler ?: return
        h.post {
            val core = egl
            if (core != null) {
                if (encoderEgl != EGL14.EGL_NO_SURFACE) core.releaseSurface(encoderEgl)
                if (previewEgl != EGL14.EGL_NO_SURFACE) core.releaseSurface(previewEgl)
                renderer.release()
                core.release()
            }
            surfaceTexture?.release()
            inputSurface?.release()
            surfaceTexture = null
            inputSurface = null
            egl = null
            inited = false
        }
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private inline fun post(crossinline block: () -> Unit) {
        handler?.post { block() }
    }
}

// Pure scope-analysis math, hoisted out of GlPipeline (they hold no instance state — just the RGBA
// snapshot + dimensions) so they are unit-testable off-GL-thread, matching the codebase's pure-seam
// pattern (e.g. camera/meteringRect, camera/centerCropBox).

/** RGBA snapshot -> luma + per-channel 256-bin histograms, subsampled for speed (Rec.2020 luma). */
// Analysis target size: plenty for a 256-bin histogram / on-screen waveform, tiny to read back.
private const val ANALYSIS_W = 256
private const val ANALYSIS_H = 192

internal fun computeHistogram(bytes: ByteArray, w: Int, h: Int): HistogramData {
    val luma = IntArray(256)
    val red = IntArray(256)
    val green = IntArray(256)
    val blue = IntArray(256)
    val step = 6
    var y = 0
    while (y < h) {
        val rowBase = y * w * 4
        var x = 0
        while (x < w) {
            val i = rowBase + x * 4
            val r = bytes[i].toInt() and 0xFF
            val g = bytes[i + 1].toInt() and 0xFF
            val b = bytes[i + 2].toInt() and 0xFF
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
internal fun computeWaveform(bytes: ByteArray, w: Int, h: Int): WaveformData {
    val columns = 128
    val rows = 64
    val bins = IntArray(columns * rows)
    val step = 6
    var y = 0
    while (y < h) {
        val rowBase = y * w * 4
        var x = 0
        while (x < w) {
            val i = rowBase + x * 4
            val r = bytes[i].toInt() and 0xFF
            val g = bytes[i + 1].toInt() and 0xFF
            val b = bytes[i + 2].toInt() and 0xFF
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
