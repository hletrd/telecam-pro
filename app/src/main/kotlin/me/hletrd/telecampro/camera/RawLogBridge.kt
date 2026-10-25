package me.hletrd.telecampro.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.media.Image
import android.media.ImageReader
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import me.hletrd.telecampro.BuildConfig
import me.hletrd.telecampro.camera.DiagnosticLog as Log
import me.hletrd.telecampro.gl.LogProfiles
import me.hletrd.telecampro.stab.RawEisGyro
import me.hletrd.telecampro.stab.RawEisMath
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * EXPERIMENTAL RAW log: develops the camera's RAW_SENSOR stream on the GPU into S-Log3 over linear
 * S-Gamut3.Cine and hands each frame to the EXISTING GL input SurfaceTexture, exactly where a camera
 * stream (or [P010InputBridge]) would land — so preview, encoder, scopes, rotation and REC keep
 * running unchanged, and only the source decode learns that these primaries are S-Gamut3.Cine
 * (`uSourceWide`, Shaders.kt).
 *
 * What it buys over the ISP's own log (native ISP log, NativeLogCurve.kt): no ISP colour matrix
 * clipping the gamut to BT.709 (the only STANDARD colour space this camera advertises is sRGB), no
 * QTI adaptive gain shaping the input (the reason the Log EI offset is hidden on PMA110), no ISP
 * noise reduction or sharpening, and a demosaic/colour pipeline this app owns end to end.
 * What it costs (the honesty limits, also in the UI caption): the RAW stream is 10-bit on PMA110
 * (white level 1023), so ~10 stops of sensor range; NO noise reduction at all; NO EIS (the HAL's
 * EIS works on ISP output — OIS still runs in the lens); NO stills in the session; at most the RAW
 * stream's full-size cadence (device-measured 2026-10-25, camera 2: 29.8 fps at 4080×3064).
 *
 * Pipeline per frame, on this bridge's own thread and EGL context (never the camera thread, never
 * the main GL thread):
 *  1. upload the RAW16 plane into an R16UI texture (the plane's row stride honoured);
 *  2. develop pass at the UNZOOMED aspect crop's resolution into an RGBA16F target: Malvar-He-Cutler
 *     5×5 demosaic on black-subtracted, white-normalized samples → lens-shading gains from the
 *     frame's own map → white-balance gains → a neutral highlight clamp at the lowest channel's clip
 *     (blown highlights go white, never magenta) → ONE camera→S-Gamut3.Cine matrix (RawDevelopMath);
 *  3. output pass at the stream size: bilinear resample of the zoomed sub-rectangle in LINEAR light,
 *     the app's defensive S-Log3 floor, the S-Log3 OETF, into an RGBA1010102 EGL window surface on
 *     the GL input Surface, stamped with the sensor timestamp (SurfaceTexture.timestamp keeps the
 *     sensor clock, so encoder PTS and the gyro log stay synced).
 *
 * The frame carries buffer transform 0, unlike a camera buffer (the camera service stamps the sensor
 * orientation as ROT_90 etc.); the GL pipeline re-applies that one factor while this source is the
 * accepted one (gl/SourceTransformEmulation.kt → GlPipeline.setSourceTransformEmulation).
 *
 * Ownership mirrors [P010InputBridge]: the EGL window surface is a PRODUCER on the GL input Surface
 * and a BufferQueue takes one producer at a time, so [close] runs after the camera stopped feeding
 * the RAW reader (device closed) and before any next session connects to the GL Surface directly.
 */
internal class RawLogBridge private constructor(
    private val appContext: android.content.Context,
    private val reader: ImageReader,
    private val thread: HandlerThread,
    private val handler: Handler,
    private val profile: RawDevelopProfile,
    private val outWidth: Int,
    private val outHeight: Int,
) {
    /** The camera output to configure in place of the GL input Surface. */
    val cameraSurface: Surface get() = reader.surface

    // Latest per-frame development inputs from the capture results (camera thread writes, bridge
    // thread reads). One frame of white-balance lag is invisible; the LSC map is near-static.
    @Volatile private var params: FrameParams? = null

    // Bridge-thread confined after construction.
    private var gl: Gl? = null
    private var closed = false
    // Set on the CALLER's thread the moment close() begins, so no develop starts behind it.
    @Volatile private var closing = false
    // Set by create() when it stops waiting for GL init: the init task then refuses to connect.
    @Volatile private var cancelled = false

    // Gyro EIS (stab/RawEisMath.kt): the configuration any thread may replace, and the gyro history
    // the bridge thread owns while a configuration is set (started/stopped by [ensureGyro]).
    @Volatile private var eisConfig: RawEisConfig? = null
    private var gyro: RawEisGyro? = null
    private var gyroOrientation = Int.MIN_VALUE

    /** Replaces the EIS configuration (null = off); the bridge thread picks it up on the next frame. */
    fun setEis(config: RawEisConfig?) {
        eisConfig = config
    }

    // Bridge thread: run the gyro exactly while EIS is configured, for this route's sensor orientation.
    private fun ensureGyro(config: RawEisConfig?) {
        val wanted = config?.sensorOrientation
        if (wanted == null) {
            gyro?.stop()
            gyro = null
            gyroOrientation = Int.MIN_VALUE
            return
        }
        if (gyro != null && gyroOrientation == wanted) return
        gyro?.stop()
        gyro = RawEisGyro(appContext, wanted).takeIf { it.available && it.start() }
        gyroOrientation = wanted
    }

    /** Camera thread: stash what the developer needs from a completed result. */
    fun onResult(result: CaptureResult) {
        val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS) ?: return
        val g = doubleArrayOf(
            gains.red.toDouble(),
            ((gains.greenEven + gains.greenOdd) / 2f).toDouble(),
            gains.blue.toDouble(),
        )
        val previous = params
        val matrix = if (previous != null && previous.gains.contentEquals(g)) {
            previous.matrix
        } else {
            profile.cameraToSg3c(g)
        }
        val black = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.average()?.toFloat()
            ?: profile.staticBlack
        val white = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)?.toFloat() ?: profile.whiteLevel
        val shading = result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
        val lsc = shading?.let { map ->
            // The map is near-static: compare into a reused scratch array and keep the previous
            // object when nothing moved, so the bridge thread re-uploads only a CHANGED map (it tests
            // identity) and the camera thread allocates only on change.
            val size = map.columnCount * map.rowCount * 4
            if (shadingScratch.size != size) shadingScratch = FloatArray(size)
            map.copyGainFactors(shadingScratch, 0)
            val old = previous?.shading
            if (old != null && old.columns == map.columnCount && old.rows == map.rowCount &&
                old.gains.contentEquals(shadingScratch)
            ) {
                old
            } else {
                Shading(map.columnCount, map.rowCount, shadingScratch.copyOf())
            }
        } ?: previous?.shading
        val zoom = result.get(CaptureResult.CONTROL_ZOOM_RATIO) ?: 1f
        // EIS row timing: each row is exposed from timestamp + skew·(row / height) for the exposure.
        val exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
        val skewNs = result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW) ?: 0L
        val frameDurationNs = result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: 0L
        params = FrameParams(g, matrix, black, white, lsc, zoom, exposureNs, skewNs, frameDurationNs)
        if (BuildConfig.DEBUG) {
            resultsSeen++
            val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
            resultsMissing += missingFrames(lastResultTimestampNs, ts, frameDurationNs)
            lastResultTimestampNs = ts
        }
    }

    // Camera-thread scratch for the shading-map comparison in [onResult].
    private var shadingScratch = FloatArray(0)

    // DEBUG-only: camera results seen (camera thread) vs frames developed (bridge thread), so the
    // profile can say how many frames the developer dropped behind acquireLatestImage.
    @Volatile private var resultsSeen = 0

    // DEBUG-only: sensor frames the CAMERA skipped (result timestamp gaps), camera thread. With the
    // developed-frame gaps in [DevelopProfiler] this attributes a frame missing from a clip: camera,
    // developer (acquireLatestImage), or downstream of the GL input Surface.
    @Volatile private var resultsMissing = 0
    private var lastResultTimestampNs = 0L

    private fun onImage(r: ImageReader) {
        val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return
        try {
            if (closed || closing) return
            val p = params ?: return
            val renderer = gl ?: return
            val eis = eisConfig
            ensureGyro(eis)
            renderer.develop(image, p, eis, gyro)
        } catch (t: Throwable) {
            // Bounded twice: three rows per bridge, through the quota-classified warning door.
            if (failures++ < 3) Log.w(TAG, "RAW develop failed: ${t.message}")
        } finally {
            image.close()
        }
    }
    private var failures = 0

    /**
     * Idempotent. Detaches the reader, then destroys the EGL window surface (disconnecting this
     * producer from the GL input Surface) and the context on the bridge thread, and waits a
     * bounded time.
     */
    fun close() {
        closing = true
        val done = CountDownLatch(1)
        val posted = runCatching {
            handler.post {
                if (!closed) {
                    closed = true
                    runCatching { reader.setOnImageAvailableListener(null, null) }
                    runCatching { reader.close() }
                    runCatching { gl?.release() }
                    gl = null
                    runCatching { gyro?.stop() }
                    gyro = null
                }
                done.countDown()
            }
        }.getOrDefault(false)
        if (posted && !done.await(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS) && BuildConfig.DEBUG) {
            // The window surface may still be connected to the GL input Surface: the next session's
            // producer would then be refused and its rung fall back (P010 → 8-bit).
            Log.w(TAG, "RAW bridge close timed out; GL input producer may linger")
        }
        thread.quitSafely()
    }

    private class Shading(val columns: Int, val rows: Int, val gains: FloatArray)

    private class FrameParams(
        val gains: DoubleArray,
        val matrix: DoubleArray,
        val black: Float,
        val white: Float,
        val shading: Shading?,
        val zoom: Float,
        val exposureNs: Long,
        val skewNs: Long,
        val frameDurationNs: Long,
    )

    /**
     * DEBUG-only stage timing for the resource budget: CPU wall time of the RAW upload and of the
     * whole develop call, and GPU time of each pass through GL_EXT_disjoint_timer_query — read
     * [QUERY_RING] frames late so a result is never waited for (a stall here would cost the very
     * cadence being measured). One summary row per [PROFILE_WINDOW_NS] and one terminal row, through
     * the bounded recurring door (ColorOS log quota: per-frame rows would eat everything else).
     * Created and used on the bridge thread, with the context current.
     */
    private inner class DevelopProfiler {
        private val timerQueries =
            GLES30.glGetString(GLES30.GL_EXTENSIONS)?.contains("GL_EXT_disjoint_timer_query") == true
        private val queries = IntArray(QUERY_RING * 2)
        private val pending = BooleanArray(QUERY_RING)
        private var slot = 0
        private var frames = 0
        private var uploadNs = 0L
        private var developNs = 0L
        private var developMaxNs = 0L
        private val gpuNs = LongArray(2)
        private var gpuFrames = 0
        private var windowStart = System.nanoTime()
        private var resultsAtWindowStart = resultsSeen
        private var cameraMissingAtWindowStart = resultsMissing
        private var developedMissing = 0
        private var lastDevelopedTimestampNs = 0L
        private var latencyNs = 0L
        private var latencyMaxNs = 0L

        init {
            if (timerQueries) GLES30.glGenQueries(queries.size, queries, 0)
        }

        fun begin(pass: Int) {
            if (timerQueries) GLES30.glBeginQuery(GL_TIME_ELAPSED_EXT, queries[slot * 2 + pass])
        }

        fun end() {
            if (timerQueries) GLES30.glEndQuery(GL_TIME_ELAPSED_EXT)
        }

        private var copyNs = 0L

        fun frameDone(upload: Long, develop: Long, copy: Long, timestampNs: Long, frameDurationNs: Long, latency: Long) {
            frames++
            developedMissing += missingFrames(lastDevelopedTimestampNs, timestampNs, frameDurationNs)
            lastDevelopedTimestampNs = timestampNs
            latencyNs += latency
            latencyMaxNs = maxOf(latencyMaxNs, latency)
            uploadNs += upload
            copyNs += copy
            developNs += develop
            developMaxNs = maxOf(developMaxNs, develop)
            if (timerQueries) {
                pending[slot] = true
                slot = (slot + 1) % QUERY_RING
                collect(slot)
            }
            if (System.nanoTime() - windowStart >= PROFILE_WINDOW_NS) emit(terminal = false)
        }

        private fun collect(s: Int) {
            if (!pending[s]) return
            val value = IntArray(1)
            GLES30.glGetQueryObjectuiv(queries[s * 2 + 1], GLES30.GL_QUERY_RESULT_AVAILABLE, value, 0)
            if (value[0] == 0) return
            val disjoint = IntArray(1)
            GLES30.glGetIntegerv(GL_GPU_DISJOINT_EXT, disjoint, 0)
            if (disjoint[0] == 0) {
                for (pass in 0..1) {
                    GLES30.glGetQueryObjectuiv(queries[s * 2 + pass], GLES30.GL_QUERY_RESULT, value, 0)
                    gpuNs[pass] += value[0].toLong() and 0xffffffffL
                }
                gpuFrames++
            }
            pending[s] = false
        }

        fun emit(terminal: Boolean) {
            val seconds = (System.nanoTime() - windowStart) / 1e9
            if (frames > 0) {
                val results = resultsSeen - resultsAtWindowStart
                val cameraMissing = resultsMissing - cameraMissingAtWindowStart
                val g = gpuFrames.coerceAtLeast(1)
                // To a cache file, not logcat: this process routinely sits over the ColorOS 300-row
                // quota during a session, which silently ate these rows (measured 2026-10-25).
                // Debug-only; pulled with `run-as … cat cache/rawdevelop_profile.txt`.
                appendProfile(
                    "RawDevelop${if (terminal) " terminal" else ""}: frames=$frames/${results} results " +
                        "fps=${"%.1f".format(frames / seconds)} upload=${"%.2f".format(uploadNs / 1e6 / frames)}ms (copy ${"%.2f".format(copyNs / 1e6 / frames)}) " +
                        "develop(cpu)=${"%.2f".format(developNs / 1e6 / frames)}ms max=${"%.1f".format(developMaxNs / 1e6)}ms " +
                        if (gpuFrames > 0) {
                            "pass1(gpu)=${"%.2f".format(gpuNs[0] / 1e6 / g)}ms pass2(gpu)=${"%.2f".format(gpuNs[1] / 1e6 / g)}ms"
                        } else {
                            "gpu=n/a"
                        } +
                        " missing(camera=$cameraMissing developed=$developedMissing) " +
                        "latency=${"%.1f".format(latencyNs / 1e6 / frames)}/${"%.1f".format(latencyMaxNs / 1e6)}ms",
                )
            }
            frames = 0
            uploadNs = 0
            copyNs = 0
            developNs = 0
            developMaxNs = 0
            gpuNs.fill(0)
            gpuFrames = 0
            windowStart = System.nanoTime()
            resultsAtWindowStart = resultsSeen
            cameraMissingAtWindowStart = resultsMissing
            developedMissing = 0
            latencyNs = 0
            latencyMaxNs = 0
        }
    }

    private fun appendProfile(line: String) {
        runCatching {
            java.io.File(System.getProperty("java.io.tmpdir") ?: return, "rawdevelop_profile.txt")
                .appendText("${System.currentTimeMillis()} $line\n")
        }
    }

    /** All GL state, created and used on the bridge thread only. */
    private inner class Gl(glInput: Surface) {
        private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val context: EGLContext
        // Assigned inside init's guarded block (Kotlin cannot see a val definitely assigned there).
        private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var rawTex = 0
        private var devTex = 0
        private var devFbo = 0
        private var lscTex = 0
        private var lscShape = 0L
        // Identity of the map last uploaded: onResult hands over the SAME object while it is unchanged.
        private var lscUploaded: Shading? = null
        private var developProgram = 0
        private var outputProgram = 0
        private val base = RawDevelopMath.cropRect(
            profile.arrayWidth, profile.arrayHeight, outWidth, outHeight, 1f,
        )
        // Only the rows the demosaic reads are uploaded: the crop band plus the 5×5 MHC apron
        // (2 rows each side). For 16:9 from the 4:3 array that is 2,308 of 3,072 rows.
        private val rowStart = (base.y - MHC_APRON).coerceAtLeast(0)
        private val rowCount = (base.y + base.height + MHC_APRON).coerceAtMost(profile.arrayHeight) - rowStart
        // RAW10 packed row: 5 bytes per 4 pixels.
        private val raw10BytesPerRow = profile.arrayWidth * 5 / 4
        // Cached uniform locations (glGetUniformLocation is a string lookup — ~20 per frame before).
        private var uBlack = -1
        private var uInvRange = -1
        private var uGains = -1
        private var uCamToSg3c = -1
        private var uUseShading = -1
        private var uShadingScale = -1
        private var uShadingOffset = -1
        private var uHomographies = -1
        private val homographyScratch = FloatArray(9 * EIS_BANDS)
        // EIS state (bridge thread): the smoothed VIRTUAL orientation, and the gyro it was integrated
        // in (a new gyro starts a new reference frame, so the virtual path restarts with it).
        private var virtual: RawEisMath.Quat? = null
        private var virtualGyro: RawEisGyro? = null
        private val matrixScratch = FloatArray(9)

        init {
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 10, EGL14.EGL_GREEN_SIZE, 10, EGL14.EGL_BLUE_SIZE, 10,
                EGL14.EGL_ALPHA_SIZE, 2,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) {
                "no RGBA1010102 ES3 config"
            }
            context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
            )
            check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
            var connected: EGLSurface = EGL14.EGL_NO_SURFACE
            try {
                // Surfaceless first (EGL_KHR_surfaceless_context; the probe ran this exact shape on
                // PMA110): every slow step — allocations, the RGBA16F target, shader compile/link —
                // happens BEFORE this bridge connects to the GL input Surface. Connecting is the last
                // act, behind the abandon check, so a create() that gave up on us (timeout) never has
                // a producer appear on the Surface its caller is about to hand to the next producer.
                check(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, context)) {
                    "surfaceless eglMakeCurrent"
                }
                allocate()
                check(!cancelled) { "RAW GL init abandoned" }
                connected = EGL14.eglCreateWindowSurface(display, configs[0], glInput, intArrayOf(EGL14.EGL_NONE), 0)
                check(connected != EGL14.EGL_NO_SURFACE) {
                    "eglCreateWindowSurface 0x${Integer.toHexString(EGL14.eglGetError())}"
                }
                check(EGL14.eglMakeCurrent(display, connected, connected, context)) { "eglMakeCurrent" }
                // Async producer: a consumer that stops latching (a reopen, a paused GL generation)
                // must never park this thread inside eglSwapBuffers. close() needs the thread free
                // to destroy the window surface — i.e. to DISCONNECT from the GL input Surface, which
                // the next session's producer (camera stream or P010 writer) can connect to only
                // afterwards. A device run saw that next P010 bridge refused once in 16 RAW-log-off
                // transitions. A late frame is dropped instead, as a camera stream drops one.
                EGL14.eglSwapInterval(display, 0)
            } catch (t: Throwable) {
                // Leave nothing behind — above all no window surface still connected as a producer.
                runCatching {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                }
                if (connected != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(display, connected) }
                runCatching { EGL14.eglDestroyContext(display, context) }
                runCatching { EGL14.eglReleaseThread() }
                throw t
            }
            surface = connected
        }

        /** Textures, the develop target and both programs; needs only a current context. */
        private fun allocate() {
            val ids = IntArray(3)
            GLES30.glGenTextures(2, ids, 0)
            rawTex = ids[0]
            devTex = ids[1]
            // A ONE-LAYER 2D ARRAY, not a 2D texture: Java GLES30 offers the pixel-unpack-buffer OFFSET
            // overload only for glTexSubImage3D, and the upload streams through PBOs (see upload()).
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, rawTex)
            if (profile.raw10) {
                // RAW10 bytes as-is (5 bytes per 4 pixels) in R8UI; the demosaic unpacks them. (Four
                // bytes per RGBA8UI texel was measured too: the CPU upload did not move and the
                // dynamic component pick doubled pass 1 on the GPU.)
                GLES30.glTexStorage3D(GLES30.GL_TEXTURE_2D_ARRAY, 1, GLES30.GL_R8UI, raw10BytesPerRow, rowCount, 1)
            } else {
                GLES30.glTexStorage3D(GLES30.GL_TEXTURE_2D_ARRAY, 1, GLES30.GL_R16UI, profile.arrayWidth, rowCount, 1)
            }
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, devTex)
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA16F, base.width, base.height)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            clampToEdge()
            GLES30.glGenFramebuffers(1, ids, 2)
            devFbo = ids[2]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, devFbo)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, devTex, 0,
            )
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
                "RGBA16F develop target incomplete"
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            developProgram = link(VERTEX, developFragment(profile.raw10))
            outputProgram = link(VERTEX, OUTPUT_FRAGMENT)
            // Fixed for this bridge's lifetime: set once (uniforms persist with the program).
            GLES30.glUseProgram(developProgram)
            GLES30.glUniform1i(loc(developProgram, "uRaw"), 0)
            GLES30.glUniform1i(loc(developProgram, "uShading"), 1)
            GLES30.glUniform2i(loc(developProgram, "uOrigin"), base.x, base.y)
            GLES30.glUniform2i(loc(developProgram, "uArrayMax"), profile.arrayWidth - 1, profile.arrayHeight - 1)
            GLES30.glUniform2i(loc(developProgram, "uRedSite"), profile.phase.redX, profile.phase.redY)
            GLES30.glUniform1i(loc(developProgram, "uRowOrigin"), rowStart)
            GLES30.glUniform1i(loc(developProgram, "uRowMax"), rowCount - 1)
            uBlack = loc(developProgram, "uBlack")
            uInvRange = loc(developProgram, "uInvRange")
            uGains = loc(developProgram, "uGains")
            uCamToSg3c = loc(developProgram, "uCamToSg3c")
            uUseShading = loc(developProgram, "uUseShading")
            uShadingScale = loc(developProgram, "uShadingScale")
            uShadingOffset = loc(developProgram, "uShadingOffset")
            GLES30.glUseProgram(outputProgram)
            GLES30.glUniform1i(loc(outputProgram, "uDeveloped"), 0)
            uHomographies = loc(outputProgram, "uH")
            GLES30.glUseProgram(0)
        }

        // DEBUG-only stage timing (null in release): see [DevelopProfiler].
        private val profiler: DevelopProfiler? = if (BuildConfig.DEBUG) DevelopProfiler() else null

        fun develop(image: Image, p: FrameParams, eis: RawEisConfig?, g: RawEisGyro?) {
            val start = System.nanoTime()
            upload(image)
            val uploaded = System.nanoTime()
            bindShading(p.shading)
            profiler?.begin(0)

            // Pass 1: demosaic + shading + WB + clamp + camera→S-Gamut3.Cine, unzoomed crop.
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, devFbo)
            GLES30.glViewport(0, 0, base.width, base.height)
            GLES30.glUseProgram(developProgram)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, rawTex)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lscTex)
            GLES30.glUniform1f(uUseShading, if (p.shading != null) 1f else 0f)
            GLES30.glUniform1f(uBlack, p.black)
            GLES30.glUniform1f(uInvRange, 1f / (p.white - p.black))
            GLES30.glUniform3f(uGains, p.gains[0].toFloat(), p.gains[1].toFloat(), p.gains[2].toFloat())
            columnMajorInto(p.matrix, matrixScratch)
            GLES30.glUniformMatrix3fv(uCamToSg3c, 1, false, matrixScratch, 0)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            profiler?.end()
            profiler?.begin(1)

            // Pass 2: zoom/aspect resample in linear light + S-Log3 into the GL input Surface.
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, outWidth, outHeight)
            GLES30.glUseProgram(outputProgram)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, devTex)
            computeBands(p, eis, g, image.timestamp)
            GLES30.glUniformMatrix3fv(uHomographies, EIS_BANDS, false, homographyScratch, 0)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            profiler?.end()
            EGLExt.eglPresentationTimeANDROID(display, surface, image.timestamp)
            check(EGL14.eglSwapBuffers(display, surface)) {
                "eglSwapBuffers 0x${Integer.toHexString(EGL14.eglGetError())}"
            }
            profiler?.frameDone(
                uploaded - start, System.nanoTime() - start, lastCopyNs, image.timestamp, p.frameDurationNs,
                SystemClock.elapsedRealtimeNanos() - image.timestamp,
            )
        }

        fun emitTerminalProfile() = profiler?.emit(terminal = true)

        /**
         * Fills [homographyScratch] with one output→developed-texture homography per row band. Without
         * EIS (or before the first gyro sample) every band is the plain zoomed crop. With EIS: the
         * output shows (1 − margin) of the zoomed crop through a smoothed VIRTUAL orientation, each band
         * maps it through the orientation the sensor had while THAT band's rows were exposed (rolling
         * shutter), and the smoothing is pushed toward the real path until the window fits.
         */
        private fun computeBands(p: FrameParams, eis: RawEisConfig?, g: RawEisGyro?, timestamp: Long) {
            val zoomed = RawDevelopMath.cropRect(profile.arrayWidth, profile.arrayHeight, outWidth, outHeight, p.zoom)
            val developed = RawEisMath.Rect(base.x.toDouble(), base.y.toDouble(), base.width.toDouble(), base.height.toDouble())
            val zoomedRect = RawEisMath.Rect(zoomed.x.toDouble(), zoomed.y.toDouble(), zoomed.width.toDouble(), zoomed.height.toDouble())
            val midTime = timestamp + p.exposureNs / 2 + p.skewNs / 2
            val rMid = if (eis != null && g != null) g.orientationAt(midTime) else null
            if (eis == null || g == null || rMid == null) {
                virtual = null
                val plain = RawEisMath.bandHomography(RawEisMath.Quat.IDENTITY, RawEisMath.Quat.IDENTITY, 1.0, profile.arrayWidth, profile.arrayHeight, zoomedRect, developed)
                for (k in 0 until EIS_BANDS) writeBand(k, plain)
                return
            }
            if (virtualGyro !== g) {
                virtual = null
                virtualGyro = g
            }
            // focal ÷ sensor width is already "image widths", so the physical width argument is 1.
            val focal = RawEisMath.effectiveFocalPx(eis.focalInImageWidths, 1.0, profile.arrayWidth, eis.converterMagnification)
            val keep = 1.0 - eis.margin
            val window = RawEisMath.Rect(
                zoomedRect.centerX - zoomedRect.width * keep / 2,
                zoomedRect.centerY - zoomedRect.height * keep / 2,
                zoomedRect.width * keep,
                zoomedRect.height * keep,
            )
            val reals = Array(EIS_BANDS) { k ->
                val row = base.y + base.height * k / (EIS_BANDS - 1.0)
                g.orientationAt(timestamp + p.exposureNs / 2 + (p.skewNs * row / profile.arrayHeight).toLong()) ?: rMid
            }
            val previous = virtual ?: rMid
            val baseFollow = RawEisMath.baseFollow(eis.strong)
            // Usage of the margin, as an angle: the correction the candidate needs vs the angle the
            // margin buys (half of the held-back crop width over |focal|).
            val allowed = (eis.margin / 2) * zoomedRect.width / kotlin.math.abs(focal)
            val trial = RawEisMath.slerp(previous, rMid, baseFollow)
            val follow = RawEisMath.followFor(baseFollow, RawEisMath.angleBetween(trial, rMid) / allowed)
            fun bandsAt(v: RawEisMath.Quat) = reals.map { real ->
                RawEisMath.bandHomography(real, v, focal, profile.arrayWidth, profile.arrayHeight, window, developed)
            }
            val inset = EIS_INSET_TEXELS / base.width
            var chosen = RawEisMath.slerp(previous, rMid, follow)
            var bands = bandsAt(chosen)
            if (!RawEisMath.windowFits(bands, inset)) {
                // Follow the real path just enough to bring the window back inside (8 halvings).
                var lo = follow
                var hi = 1.0
                var hiBands = bandsAt(rMid)
                var hiV: RawEisMath.Quat = rMid
                repeat(8) {
                    val mid = (lo + hi) / 2
                    val v = RawEisMath.slerp(previous, rMid, mid)
                    val b = bandsAt(v)
                    if (RawEisMath.windowFits(b, inset)) {
                        hi = mid
                        hiBands = b
                        hiV = v
                    } else {
                        lo = mid
                    }
                }
                chosen = hiV
                bands = hiBands
            }
            virtual = chosen
            for (k in 0 until EIS_BANDS) writeBand(k, bands[k])
        }

        // GLSL mat3 arrays are column-major, one matrix after another.
        private fun writeBand(k: Int, h: DoubleArray) {
            val o = k * 9
            for (i in 0 until 9) homographyScratch[o + i] = h[(i % 3) * 3 + i / 3].toFloat()
        }

        // Two pixel-unpack buffers, alternated so the GPU copy of frame N never blocks the CPU write of
        // frame N+1. Through a PBO the CPU does one plain copy and the GPU does the texture tiling;
        // straight from client memory the driver tiled on the CPU (measured 2026-10-25, RAW10 rows:
        // 4.7 ms per frame direct vs 2.1 ms copy + 0.25 ms call through the PBO). The copy left is
        // the read of the camera's buffer; only a zero-copy import (not reachable from Java) removes it.
        private val pbos = IntArray(2)
        private var pboSize = 0
        private var pboNext = 0
        // DEBUG profile input: the plain-copy share of the last upload.
        var lastCopyNs = 0L
            private set

        private fun upload(image: Image) {
            val plane = image.planes[0]
            val bytes = plane.rowStride * rowCount
            if (pbos[0] == 0) GLES30.glGenBuffers(2, pbos, 0)
            val pbo = pbos[pboNext]
            pboNext = 1 - pboNext
            GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, pbo)
            if (pboSize != bytes) {
                GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, pbos[1 - pboNext])
                GLES30.glBufferData(GLES30.GL_PIXEL_UNPACK_BUFFER, bytes, null, GLES30.GL_STREAM_DRAW)
                GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, pbos[pboNext])
                GLES30.glBufferData(GLES30.GL_PIXEL_UNPACK_BUFFER, bytes, null, GLES30.GL_STREAM_DRAW)
                GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, pbo)
                pboSize = bytes
            }
            val copyStart = System.nanoTime()
            val mapped = GLES30.glMapBufferRange(
                GLES30.GL_PIXEL_UNPACK_BUFFER, 0, bytes,
                GLES30.GL_MAP_WRITE_BIT or GLES30.GL_MAP_INVALIDATE_BUFFER_BIT,
            ) as java.nio.ByteBuffer
            val src = plane.buffer.duplicate()
            src.position(rowStart * plane.rowStride)
            src.limit(minOf(src.capacity(), rowStart * plane.rowStride + bytes))
            mapped.put(src)
            GLES30.glUnmapBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER)
            lastCopyNs = System.nanoTime() - copyStart
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, rawTex)
            if (profile.raw10) {
                GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
                GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, plane.rowStride)
                GLES30.glTexSubImage3D(
                    GLES30.GL_TEXTURE_2D_ARRAY, 0, 0, 0, 0, raw10BytesPerRow, rowCount, 1,
                    GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_BYTE, 0,
                )
            } else {
                GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 2)
                GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, plane.rowStride / 2)
                GLES30.glTexSubImage3D(
                    GLES30.GL_TEXTURE_2D_ARRAY, 0, 0, 0, 0, profile.arrayWidth, rowCount, 1,
                    GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_SHORT, 0,
                )
            }
            GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, 0)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        }

        private fun bindShading(shading: Shading?) {
            if (shading == null || shading === lscUploaded) return
            lscUploaded = shading
            val shape = (shading.columns.toLong() shl 32) or shading.rows.toLong()
            if (lscTex == 0) {
                val ids = IntArray(1)
                GLES30.glGenTextures(1, ids, 0)
                lscTex = ids[0]
            }
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lscTex)
            val data = ByteBuffer.allocateDirect(shading.gains.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().put(shading.gains).also { it.position(0) }
            if (shape != lscShape) {
                GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, shading.columns, shading.rows, 0,
                    GLES30.GL_RGBA, GLES30.GL_FLOAT, data,
                )
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                clampToEdge()
                lscShape = shape
                // The texel mapping depends only on the map's shape: set it when the shape changes.
                GLES30.glUseProgram(developProgram)
                GLES30.glUniform2f(
                    uShadingScale,
                    (shading.columns - 1f) / ((profile.arrayWidth - 1f) * shading.columns),
                    (shading.rows - 1f) / ((profile.arrayHeight - 1f) * shading.rows),
                )
                GLES30.glUniform2f(uShadingOffset, 0.5f / shading.columns, 0.5f / shading.rows)
            } else {
                GLES30.glTexSubImage2D(
                    GLES30.GL_TEXTURE_2D, 0, 0, 0, shading.columns, shading.rows,
                    GLES30.GL_RGBA, GLES30.GL_FLOAT, data,
                )
            }
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        }

        fun release() {
            runCatching { emitTerminalProfile() }
            runCatching {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            }
            runCatching { EGL14.eglDestroySurface(display, surface) }
            runCatching { EGL14.eglDestroyContext(display, context) }
            runCatching { EGL14.eglReleaseThread() }
        }

        private fun clampToEdge() {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        }

        private fun loc(program: Int, name: String) = GLES30.glGetUniformLocation(program, name)

        private fun link(vs: String, fs: String): Int {
            fun compile(type: Int, src: String): Int = GLES30.glCreateShader(type).also {
                GLES30.glShaderSource(it, src)
                GLES30.glCompileShader(it)
                val ok = IntArray(1)
                GLES30.glGetShaderiv(it, GLES30.GL_COMPILE_STATUS, ok, 0)
                check(ok[0] != 0) { "RAW shader: ${GLES30.glGetShaderInfoLog(it)}" }
            }
            return GLES30.glCreateProgram().also {
                GLES30.glAttachShader(it, compile(GLES30.GL_VERTEX_SHADER, vs))
                GLES30.glAttachShader(it, compile(GLES30.GL_FRAGMENT_SHADER, fs))
                GLES30.glLinkProgram(it)
                val ok = IntArray(1)
                GLES30.glGetProgramiv(it, GLES30.GL_LINK_STATUS, ok, 0)
                check(ok[0] != 0) { "RAW link: ${GLES30.glGetProgramInfoLog(it)}" }
            }
        }
    }

    companion object {
        private const val TAG = "RawLogBridge"
        private const val READER_MAX_IMAGES = 3
        private const val CLOSE_WAIT_MS = 1_000L
        private const val CREATE_WAIT_MS = 1_000L
        private const val QUERY_RING = 4
        private const val MHC_APRON = 2
        // Row bands of the rolling-shutter EIS warp, and the texel guard kept from the developed edge.
        private const val EIS_BANDS = 8
        private const val EIS_INSET_TEXELS = 1.5
        private const val PROFILE_WINDOW_NS = 10_000_000_000L
        private const val GL_TIME_ELAPSED_EXT = 0x88BF
        private const val GL_GPU_DISJOINT_EXT = 0x8FBB
        private const val CREATE_ABANDON_WAIT_MS = 4_000L

        /**
         * Builds the bridge or throws (the EGL window surface's producer connect fails while another
         * producer owns [glInput]; no RGBA1010102 ES3 config); the caller falls back to the ordinary
         * ladder.
         */
        fun create(
            context: android.content.Context,
            glInput: Surface,
            output: Size,
            profile: RawDevelopProfile,
        ): RawLogBridge {
            val thread = HandlerThread("RawLogBridge").apply { start() }
            val handler = Handler(thread.looper)
            val reader = try {
                ImageReader.newInstance(
                    profile.arrayWidth, profile.arrayHeight,
                    if (profile.raw10) ImageFormat.RAW10 else ImageFormat.RAW_SENSOR,
                    READER_MAX_IMAGES,
                )
            } catch (t: Throwable) {
                thread.quitSafely()
                throw t
            }
            val bridge = RawLogBridge(context.applicationContext, reader, thread, handler, profile, output.width, output.height)
            val ready = CountDownLatch(1)
            // Written on the bridge thread, read here even when the latch timed out (no happens-before).
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
            handler.post {
                try {
                    bridge.gl = bridge.Gl(glInput)
                } catch (t: Throwable) {
                    failure.set(t)
                }
                ready.countDown()
            }
            if (!ready.await(CREATE_WAIT_MS, TimeUnit.MILLISECONDS)) {
                // Abandon, then wait for the init task to DRAIN before refusing: it now stops before
                // connecting, or — already past that check — the close() queued behind it destroys
                // the window surface. The caller's next producer must never meet this one on the GL
                // input Surface (review 2026-10-25). Bounded, so a wedged driver cannot hang the
                // camera thread; past that bound the old residual risk is all that remains.
                bridge.cancelled = true
                ready.await(CREATE_ABANDON_WAIT_MS, TimeUnit.MILLISECONDS)
                failure.compareAndSet(null, IllegalStateException("RAW GL init timed out"))
            }
            failure.get()?.let {
                bridge.close()
                throw it
            }
            reader.setOnImageAvailableListener(bridge::onImage, handler)
            return bridge
        }

        // Full-screen triangle; uv (0,0) = top-left of the IMAGE, so row 0 of the output buffer is the
        // sensor's top row, exactly as a camera buffer is laid out.
        private const val VERTEX = """#version 300 es
            out vec2 uv;
            void main() {
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                uv = vec2(p.x, 1.0 - p.y);
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }"""

        /** The demosaic shader for RAW10 (packed bytes) or RAW16 storage. */
        private fun developFragment(raw10: Boolean): String =
            DEVELOP_FRAGMENT.replaceFirst("#version 300 es", "#version 300 es\n#define RAW10 ${if (raw10) 1 else 0}")

        // Malvar-He-Cutler (ICASSP 2004) gradient-corrected linear demosaic, written per Bayer site.
        private val DEVELOP_FRAGMENT = """#version 300 es
            precision highp float;
            precision highp int;
            precision highp usampler2DArray;
            uniform usampler2DArray uRaw;
            uniform sampler2D uShading;
            uniform float uUseShading;
            uniform vec2 uShadingScale;
            uniform vec2 uShadingOffset;
            uniform ivec2 uOrigin;
            uniform ivec2 uArrayMax;
            uniform int uRowOrigin;   // first array row held by uRaw
            uniform int uRowMax;      // last texture row
            uniform ivec2 uRedSite;
            uniform float uBlack;
            uniform float uInvRange;
            uniform vec3 uGains;
            uniform mat3 uCamToSg3c;
            out vec4 o;

            float rawAt(ivec2 p) {
                int y = clamp(p.y - uRowOrigin, 0, uRowMax);
            #if RAW10
                // Android RAW10: 4 pixels in 5 bytes — bytes 0..3 carry bits 9..2, byte 4 carries
                // bits 1..0 of pixel i at bit 2i.
                int g = (p.x >> 2) * 5;
                int i = p.x & 3;
                uint hi = texelFetch(uRaw, ivec3(g + i, y, 0), 0).r;
                uint lo = (texelFetch(uRaw, ivec3(g + 4, y, 0), 0).r >> uint(2 * i)) & 3u;
                return float((hi << 2u) | lo);
            #else
                return float(texelFetch(uRaw, ivec3(p.x, y, 0), 0).r);
            #endif
            }

            float px(ivec2 p) {
                p = clamp(p, ivec2(0), uArrayMax);
                return (rawAt(p) - uBlack) * uInvRange;
            }

            void main() {
                // FBO row k holds raw row uOrigin.y + k (no flip: pass 2 owns the image orientation).
                ivec2 p = uOrigin + ivec2(gl_FragCoord.xy);
                bool redRow = (p.y & 1) == uRedSite.y;
                bool redCol = (p.x & 1) == uRedSite.x;
                float c = px(p);
                float n = px(p + ivec2(0, -1)), s = px(p + ivec2(0, 1));
                float w = px(p + ivec2(-1, 0)), e = px(p + ivec2(1, 0));
                float nw = px(p + ivec2(-1, -1)), ne = px(p + ivec2(1, -1));
                float sw = px(p + ivec2(-1, 1)), se = px(p + ivec2(1, 1));
                float n2 = px(p + ivec2(0, -2)), s2 = px(p + ivec2(0, 2));
                float w2 = px(p + ivec2(-2, 0)), e2 = px(p + ivec2(2, 0));
                float cross = n + s + w + e;
                float diag = nw + ne + sw + se;
                float axial2 = n2 + s2 + w2 + e2;
                // Green at a red/blue site, and the opposite chroma at a red/blue site.
                float gAtRb = (4.0 * c + 2.0 * cross - axial2) / 8.0;
                float rbAtBr = (6.0 * c + 2.0 * diag - 1.5 * axial2) / 8.0;
                // Chroma at a green site: along the row, and along the column.
                float alongRow = (5.0 * c + 4.0 * (w + e) - diag - (w2 + e2) + 0.5 * (n2 + s2)) / 8.0;
                float alongCol = (5.0 * c + 4.0 * (n + s) - diag - (n2 + s2) + 0.5 * (w2 + e2)) / 8.0;
                vec3 rgb;
                if (redRow && redCol) rgb = vec3(c, gAtRb, rbAtBr);            // red site
                else if (!redRow && !redCol) rgb = vec3(rbAtBr, gAtRb, c);     // blue site
                else if (redRow) rgb = vec3(alongRow, c, alongCol);            // green on a red row
                else rgb = vec3(alongCol, c, alongRow);                        // green on a blue row

                vec3 shading = vec3(1.0);
                if (uUseShading > 0.5) {
                    vec4 g = texture(uShading, vec2(p) * uShadingScale + uShadingOffset); // R, Geven, Godd, B
                    shading = vec3(g.r, 0.5 * (g.g + g.b), g.a);
                }
                vec3 gained = rgb * shading * uGains;
                // A saturated photosite reaches (shading * gain) per channel; clamping every channel at
                // the LOWEST of those keeps clipped highlights neutral instead of magenta.
                vec3 clip = shading * uGains;
                gained = min(gained, vec3(min(clip.r, min(clip.g, clip.b))));
                o = vec4(uCamToSg3c * gained, 1.0);
            }
        """

        // S-Log3 constants single-sourced from LogProfiles (same as Shaders.kt).
        private val OUTPUT_FRAGMENT = """#version 300 es
            precision highp float;
            uniform sampler2D uDeveloped;
            // One output→developed-texture homography per row band (EIS; the plain crop when off).
            uniform mat3 uH[$EIS_BANDS];
            in vec2 uv;
            out vec4 o;
            vec3 slog3(vec3 x) {
                vec3 logY = (${LogProfiles.SLOG3_LOG_OFFSET_CODE}
                    + log(max(x + ${LogProfiles.SLOG3_LIN_OFFSET}, 1e-6) / ${LogProfiles.SLOG3_GREY_PLUS_OFFSET})
                    * ${LogProfiles.INV_LN10} * ${LogProfiles.SLOG3_LOG_SLOPE}) / ${LogProfiles.SLOG3_CODE_SCALE};
                vec3 linY = (x * (${LogProfiles.SLOG3_CUT_CODE} - ${LogProfiles.SLOG3_BLACK_CODE})
                    / ${LogProfiles.SLOG3_CUT} + ${LogProfiles.SLOG3_BLACK_CODE}) / ${LogProfiles.SLOG3_CODE_SCALE};
                return mix(logY, linY, step(x, vec3(${LogProfiles.SLOG3_CUT})));
            }
            vec2 bandMap(int k, vec2 q) {
                vec3 h = uH[k] * vec3(q, 1.0);
                return h.xy / h.z;
            }
            // Bands sit at developed rows k / (N - 1); between two, blend their mappings.
            vec2 mapAt(float band, vec2 q) {
                float b = clamp(band, 0.0, float($EIS_BANDS - 1));
                int k0 = int(floor(b));
                int k1 = min(k0 + 1, $EIS_BANDS - 1);
                return mix(bandMap(k0, q), bandMap(k1, q), b - float(k0));
            }
            void main() {
                // Rolling shutter: a row's exposure time follows the SOURCE row it was read from, not
                // the output row it is shown on — estimate with the output row, then refine once.
                vec2 first = mapAt(uv.y * float($EIS_BANDS - 1), uv);
                vec2 t = mapAt(first.y * float($EIS_BANDS - 1), uv);
                vec3 lin = texture(uDeveloped, t).rgb;
                o = vec4(slog3(max(lin, vec3(${LogProfiles.GAMUT_LINEAR_FLOOR}))), 1.0);
            }
        """

        private fun columnMajorInto(m: DoubleArray, out: FloatArray) {
            for (i in 0 until 9) out[i] = m[(i % 3) * 3 + i / 3].toFloat()
        }
    }
}

/**
 * Everything static the developer needs from one camera's characteristics, resolved once off the
 * camera thread. Null from [from] when the camera cannot be developed (no RAW_SENSOR, an unsupported
 * CFA, neither a ForwardMatrix nor a ColorMatrix).
 */
internal class RawDevelopProfile(
    val arrayWidth: Int,
    val arrayHeight: Int,
    /**
     * Stream RAW10 (packed, 1.25 B/px) instead of RAW16 (2 B/px) when the camera advertises it at the
     * same size: the per-frame CPU upload is the developer's largest cost (6.6 ms/frame measured at
     * RAW16 4096×3072, 2026-10-25), and PMA110 cams 2/4 advertise RAW10 at the same 33.3 ms.
     */
    val raw10: Boolean,
    val whiteLevel: Float,
    val staticBlack: Float,
    val phase: RawDevelopMath.BayerPhase,
    private val forward1: DoubleArray?,
    private val forward2: DoubleArray?,
    private val color1: DoubleArray?,
    private val color2: DoubleArray?,
    private val calibration1: DoubleArray?,
    private val calibration2: DoubleArray?,
    private val cct1: Double,
    private val cct2: Double?,
) {
    /** The composed camera→S-Gamut3.Cine matrix for these white-balance gains (R, G, B). */
    fun cameraToSg3c(gains: DoubleArray): DoubleArray {
        val cct = if (color1 != null) {
            RawDevelopMath.neutralCct(gains, color1, color2, calibration1, calibration2, cct1, cct2)
        } else {
            cct1
        }
        val weight = if (cct2 != null) RawDevelopMath.matrixWeight(cct, cct1, cct2) else 1.0
        return if (forward1 != null && RawDevelopMath.forwardMatrixInformative(forward1, forward2, color1, color2)) {
            RawDevelopMath.cameraToSg3c(gains, forward1, forward2, calibration1, calibration2, weight)
        } else {
            // PMA110 lands here: its ForwardMatrix pair is an sRGB placeholder (RawDevelopMath doc).
            RawDevelopMath.cameraToSg3cFromColorMatrix(
                gains, checkNotNull(color1), color2, calibration1, calibration2, weight,
            )
        }
    }

    companion object {
        fun from(chars: CameraCharacteristics, rawSize: Size): RawDevelopProfile? {
            val phase = RawDevelopMath.bayerPhase(chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT))
                ?: return null
            val forward1 = chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)?.rowMajor()
            val color1 = chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)?.rowMajor()
            if (forward1 == null && color1 == null) return null
            val white = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: return null
            val black = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
                ?.let { pattern -> IntArray(4).also { pattern.copyTo(it, 0) }.average().toFloat() } ?: 0f
            val cct1 = RawDevelopMath.illuminantCct(chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1))
                ?: return null
            val cct2 = chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt()
                ?.let { RawDevelopMath.illuminantCct(it) }
            // The packed row must be whole RGBA8UI texels (width a multiple of 16 px) for the upload.
            val raw10 = rawSize.width % 16 == 0 && runCatching {
                chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(ImageFormat.RAW10)?.any { it == rawSize } == true
            }.getOrDefault(false)
            return RawDevelopProfile(
                arrayWidth = rawSize.width,
                arrayHeight = rawSize.height,
                raw10 = raw10,
                whiteLevel = white.toFloat(),
                staticBlack = black,
                phase = phase,
                forward1 = forward1,
                forward2 = chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)?.rowMajor()?.takeIf { cct2 != null },
                color1 = color1,
                color2 = chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)?.rowMajor()?.takeIf { cct2 != null },
                calibration1 = chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)?.rowMajor(),
                calibration2 = chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)?.rowMajor()
                    ?.takeIf { cct2 != null },
                cct1 = cct1,
                cct2 = cct2,
            )
        }

        // ColorSpaceTransform.getElement takes (column, row).
        private fun ColorSpaceTransform.rowMajor(): DoubleArray =
            DoubleArray(9) { i -> getElement(i % 3, i / 3).toDouble() }
    }
}

/** RAW-log requests must ask for the shading map the developer applies. */
internal fun CaptureRequest.Builder.applyRawLogKeys() {
    set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)
}
