package me.hletrd.telecampro.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES30
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DEVICE PROBE (not a shipping assertion): can GL render YUV STRAIGHT into the video encoder's input
 * Surface on this device, and what does the encoder do with the RGBA buffers the app sends today?
 *
 * Background: a perfetto trace of a 4K take (2026-10-26) showed `c2.qti.hevc.encoder` converting every
 * RGBA input frame in its own process (an image-transformer thread at ~0.3 core, two GPU submits per
 * frame). `EGL_EXT_yuv_surface` + `GL_EXT_YUV_target` would let GL write YUV itself. This probe:
 *  1. lists every YUV EGL config (plane depth, CSC standard, range, native format);
 *  2. per bit depth, encodes the same four colour bars twice into a real 3840x2160 HEVC encoder —
 *     through an RGBA EGL surface (today's path) and through a YUV EGL surface — then decodes the first
 *     frame and reads Y/Cb/Cr at each bar's centre, so the YUV path's correctness and the encoder's
 *     own RGB→YUV matrix/range are both visible.
 * Phase timestamps are CLOCK_BOOTTIME so a perfetto trace taken alongside can be split by path.
 *
 * Never fails the build. Results: `adb shell run-as <pkg> cat cache/yuv_encoder_probe.txt`.
 */
@RunWith(AndroidJUnit4::class)
class YuvEncoderSurfaceProbeTest {

    private companion object {
        const val TAG = "YuvEncoderProbe"
        const val W = 3840
        const val H = 2160
        const val FRAMES = 90
        const val EGL_RECORDABLE_ANDROID = 0x3142
        const val EGL_DONT_CARE = -1
        const val EGL_OPENGL_ES3_BIT = 0x40
        const val EGL_YUV_BUFFER_EXT = 0x3300
        const val EGL_YUV_ORDER_EXT = 0x3301
        const val EGL_YUV_NUMBER_OF_PLANES_EXT = 0x3311
        const val EGL_YUV_SUBSAMPLE_EXT = 0x3312
        const val EGL_YUV_DEPTH_RANGE_EXT = 0x3317
        const val EGL_YUV_CSC_STANDARD_EXT = 0x330A
        const val EGL_YUV_PLANE_BPP_EXT = 0x331A
        const val EGL_YUV_PLANE_BPP_8_EXT = 0x331C
        const val EGL_YUV_PLANE_BPP_10_EXT = 0x331D

        // Four bars as normalized (Y, Cb, Cr) code values for the YUV path...
        val YUV_BARS = floatArrayOf(
            0.25f, 0.50f, 0.50f,
            0.50f, 0.30f, 0.70f,
            0.75f, 0.70f, 0.30f,
            0.90f, 0.50f, 0.50f,
        )
        // ...and four RGB bars for the RGBA path (grey, red-ish, blue-ish, light grey).
        val RGB_BARS = floatArrayOf(
            0.25f, 0.25f, 0.25f,
            0.80f, 0.20f, 0.20f,
            0.20f, 0.30f, 0.80f,
            0.90f, 0.90f, 0.90f,
        )

        const val VS = """#version 300 es
layout(location = 0) in vec2 aPos;
out vec2 vUv;
void main() { vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }"""

        const val FS_RGB = """#version 300 es
precision highp float;
in vec2 vUv;
uniform vec3 uBars[4];
out vec4 outColor;
void main() { int i = int(clamp(floor(vUv.x * 4.0), 0.0, 3.0)); outColor = vec4(uBars[i], 1.0); }"""

        // The same RGB bars as the RGBA path, converted by GL_EXT_YUV_target's own BT.709 helper: if the
        // decoded codes match the RGBA path's, an encoder-side swap keeps colours identical.
        const val FS_YUV_FROM_RGB = """#version 300 es
#extension GL_EXT_YUV_target : require
precision highp float;
in vec2 vUv;
uniform vec3 uBars[4];
layout(yuv) out vec4 outColor;
void main() { int i = int(clamp(floor(vUv.x * 4.0), 0.0, 3.0)); outColor = vec4(rgb_2_yuv(uBars[i], itu_709), 1.0); }"""

        const val FS_YUV = """#version 300 es
#extension GL_EXT_YUV_target : require
precision highp float;
in vec2 vUv;
uniform vec3 uBars[4];
layout(yuv) out vec4 outColor;
void main() { int i = int(clamp(floor(vUv.x * 4.0), 0.0, 3.0)); outColor = vec4(uBars[i], 1.0); }"""
    }

    private val out = StringBuilder()
    private fun line(s: String) {
        out.append(s).append('\n')
        flush()
    }
    private fun flush() {
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "yuv_encoder_probe.txt")
            .writeText(out.toString())
    }

    @Test
    fun probe() {
        val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        EGL14.eglInitialize(dpy, ver, 0, ver, 1)
        val ext = EGL14.eglQueryString(dpy, EGL14.EGL_EXTENSIONS)
        line("EGL ${ver[0]}.${ver[1]} EGL_EXT_yuv_surface=${ext.contains("EGL_EXT_yuv_surface")}")
        // eglChooseConfig's DEFAULTS (window surface, ES renderable, RGB order rules) can hide YUV
        // configs, so enumerate every config the display has and filter by colour-buffer type too.
        val total = IntArray(1)
        EGL14.eglGetConfigs(dpy, null, 0, 0, total, 0)
        val all = arrayOfNulls<EGLConfig>(total[0])
        EGL14.eglGetConfigs(dpy, all, 0, all.size, total, 0)
        val byType = all.filterNotNull().groupBy { attr(dpy, it, EGL14.EGL_COLOR_BUFFER_TYPE) }
        line("all configs: ${total[0]}, by colour-buffer type: " + byType.mapKeys { "0x" + Integer.toHexString(it.key) }.mapValues { it.value.size })
        val chosen = chooseConfigs(
            dpy,
            intArrayOf(
                EGL14.EGL_COLOR_BUFFER_TYPE, EGL_YUV_BUFFER_EXT,
                EGL14.EGL_SURFACE_TYPE, EGL_DONT_CARE,
                EGL14.EGL_RENDERABLE_TYPE, EGL_DONT_CARE,
                EGL_YUV_ORDER_EXT, EGL_DONT_CARE,
                EGL14.EGL_NONE,
            ),
        )
        line("eglChooseConfig(YUV, surface/renderable/order DONT_CARE): ${chosen.size}")
        val yuvConfigs = (byType[EGL_YUV_BUFFER_EXT].orEmpty() + chosen).distinct()
        line("YUV configs: ${yuvConfigs.size}")
        yuvConfigs.forEach { line("  " + describe(dpy, it)) }
        for (tenBit in listOf(false, true)) {
            val rgba = rgbaConfig(dpy, tenBit)
            line("== ${if (tenBit) "10-bit" else "8-bit"}: RGBA config ${rgba?.let { describe(dpy, it) } ?: "none"}")
            if (rgba != null) line("RGBA path: " + runCatching { encode(dpy, rgba, tenBit, yuv = false) }.getOrElse { "error $it" })
            val bpp = if (tenBit) EGL_YUV_PLANE_BPP_10_EXT else EGL_YUV_PLANE_BPP_8_EXT
            val yuv = yuvConfigs.firstOrNull { attr(dpy, it, EGL_YUV_PLANE_BPP_EXT) == bpp }
            line("YUV config: ${yuv?.let { describe(dpy, it) } ?: "none at this depth"}")
            if (yuv != null) line("YUV path: " + runCatching { encode(dpy, yuv, tenBit, yuv = true) }.getOrElse { "error $it" })
            if (yuv != null && !tenBit) {
                line("YUV path, RGB bars via rgb_2_yuv(itu_709): " + runCatching { encode(dpy, yuv, tenBit, yuv = true, fromRgb = true) }.getOrElse { "error $it" })
            }
        }
        EGL14.eglTerminate(dpy)
        Log.i(TAG, "done")
    }

    private fun chooseConfigs(dpy: android.opengl.EGLDisplay, base: IntArray): List<EGLConfig> {
        val configs = arrayOfNulls<EGLConfig>(64)
        val n = IntArray(1)
        if (!EGL14.eglChooseConfig(dpy, base, 0, configs, 0, configs.size, n, 0)) return emptyList()
        return configs.take(n[0]).filterNotNull()
    }

    private fun rgbaConfig(dpy: android.opengl.EGLDisplay, tenBit: Boolean): EGLConfig? = chooseConfigs(
        dpy,
        intArrayOf(
            EGL14.EGL_RED_SIZE, if (tenBit) 10 else 8,
            EGL14.EGL_GREEN_SIZE, if (tenBit) 10 else 8,
            EGL14.EGL_BLUE_SIZE, if (tenBit) 10 else 8,
            EGL14.EGL_ALPHA_SIZE, if (tenBit) 2 else 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        ),
    ).firstOrNull()

    private fun attr(dpy: android.opengl.EGLDisplay, c: EGLConfig, a: Int): Int {
        val v = IntArray(1)
        return if (EGL14.eglGetConfigAttrib(dpy, c, a, v, 0)) v[0] else -1
    }

    private fun describe(dpy: android.opengl.EGLDisplay, c: EGLConfig): String =
        "visual=0x${Integer.toHexString(attr(dpy, c, EGL14.EGL_NATIVE_VISUAL_ID))} " +
            "type=0x${Integer.toHexString(attr(dpy, c, EGL14.EGL_COLOR_BUFFER_TYPE))} " +
            "rgba=${attr(dpy, c, EGL14.EGL_RED_SIZE)}/${attr(dpy, c, EGL14.EGL_GREEN_SIZE)}/${attr(dpy, c, EGL14.EGL_BLUE_SIZE)}/${attr(dpy, c, EGL14.EGL_ALPHA_SIZE)} " +
            "order=0x${Integer.toHexString(attr(dpy, c, EGL_YUV_ORDER_EXT))} planes=${attr(dpy, c, EGL_YUV_NUMBER_OF_PLANES_EXT)} " +
            "subsample=0x${Integer.toHexString(attr(dpy, c, EGL_YUV_SUBSAMPLE_EXT))} range=0x${Integer.toHexString(attr(dpy, c, EGL_YUV_DEPTH_RANGE_EXT))} " +
            "csc=0x${Integer.toHexString(attr(dpy, c, EGL_YUV_CSC_STANDARD_EXT))} bpp=0x${Integer.toHexString(attr(dpy, c, EGL_YUV_PLANE_BPP_EXT))} " +
            "recordable=${attr(dpy, c, EGL_RECORDABLE_ANDROID)} renderable=0x${Integer.toHexString(attr(dpy, c, EGL14.EGL_RENDERABLE_TYPE))} " +
            "surface=0x${Integer.toHexString(attr(dpy, c, EGL14.EGL_SURFACE_TYPE))}"

    private fun encoderFormat(tenBit: Boolean) = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, W, H).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, 40_000_000)
        setFloat(MediaFormat.KEY_FRAME_RATE, 30f)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        if (tenBit) {
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        } else {
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
    }

    /** Encodes [FRAMES] frames of the four bars through [config]; returns the decoded bar centres. */
    private fun encode(dpy: android.opengl.EGLDisplay, config: EGLConfig, tenBit: Boolean, yuv: Boolean, fromRgb: Boolean = false): String {
        val startNs = SystemClock.elapsedRealtimeNanos()
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
        val packets = ArrayList<Pair<ByteArray, Long>>()
        var outFormat: MediaFormat? = null
        var input: Surface? = null
        var ctx = EGL14.EGL_NO_CONTEXT
        var surf = EGL14.EGL_NO_SURFACE
        try {
            encoder.configure(encoderFormat(tenBit), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            input = encoder.createInputSurface()
            encoder.start()
            ctx = EGL14.eglCreateContext(dpy, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            if (ctx == EGL14.EGL_NO_CONTEXT) return "eglCreateContext failed 0x${Integer.toHexString(EGL14.eglGetError())}"
            surf = EGL14.eglCreateWindowSurface(dpy, config, input, intArrayOf(EGL14.EGL_NONE), 0)
            if (surf == EGL14.EGL_NO_SURFACE) return "eglCreateWindowSurface failed 0x${Integer.toHexString(EGL14.eglGetError())}"
            if (!EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) return "eglMakeCurrent failed 0x${Integer.toHexString(EGL14.eglGetError())}"
            val program = program(VS, if (fromRgb) FS_YUV_FROM_RGB else if (yuv) FS_YUV else FS_RGB)
                ?: return "shader failed: $lastShaderLog"
            GLES30.glUseProgram(program)
            GLES30.glUniform3fv(GLES30.glGetUniformLocation(program, "uBars"), 4, if (yuv && !fromRgb) YUV_BARS else RGB_BARS, 0)
            val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quad)
            GLES30.glViewport(0, 0, W, H)
            val info = MediaCodec.BufferInfo()
            fun drain(untilEos: Boolean) {
                val deadline = System.nanoTime() + 5_000_000_000L
                while (System.nanoTime() < deadline) {
                    val idx = encoder.dequeueOutputBuffer(info, if (untilEos) 10_000 else 0)
                    when {
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFormat = encoder.outputFormat
                        idx >= 0 -> {
                            val b = encoder.getOutputBuffer(idx)!!
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val a = ByteArray(info.size)
                                b.position(info.offset)
                                b.get(a)
                                packets += a to info.presentationTimeUs
                            }
                            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            encoder.releaseOutputBuffer(idx, false)
                            if (eos) return
                        }
                        idx == MediaCodec.INFO_TRY_AGAIN_LATER && !untilEos -> return
                    }
                }
            }
            for (f in 0 until FRAMES) {
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                EGLExt.eglPresentationTimeANDROID(dpy, surf, f * 33_333_333L)
                if (!EGL14.eglSwapBuffers(dpy, surf)) return "eglSwapBuffers failed 0x${Integer.toHexString(EGL14.eglGetError())} at frame $f"
                drain(false)
            }
            encoder.signalEndOfInputStream()
            drain(true)
        } finally {
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, surf)
            if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)
            runCatching { encoder.stop() }
            encoder.release()
            input?.release()
        }
        val endNs = SystemClock.elapsedRealtimeNanos()
        val fmt = outFormat ?: return "no output format (packets=${packets.size})"
        return "boottime ${startNs / 1_000_000}..${endNs / 1_000_000} ms, packets=${packets.size}, " +
            "bars(Y,Cb,Cr)=${decodeBars(fmt, packets)}"
    }

    /** Decodes the first frame and returns Y/Cb/Cr at each bar's centre, as raw code values. */
    private fun decodeBars(fmt: MediaFormat, packets: List<Pair<ByteArray, Long>>): String {
        val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
        val decFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, W, H).apply {
            fmt.getByteBuffer("csd-0")?.let { setByteBuffer("csd-0", it) }
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        }
        try {
            decoder.configure(decFormat, null, null, 0)
            decoder.start()
            val info = MediaCodec.BufferInfo()
            var next = 0
            val deadline = System.nanoTime() + 10_000_000_000L
            while (System.nanoTime() < deadline) {
                if (next < packets.size) {
                    val i = decoder.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val b = decoder.getInputBuffer(i)!!
                        b.clear()
                        b.put(packets[next].first)
                        decoder.queueInputBuffer(i, 0, packets[next].first.size, packets[next].second, 0)
                        next++
                    }
                }
                val o = decoder.dequeueOutputBuffer(info, 5_000)
                if (o >= 0) {
                    val image = decoder.getOutputImage(o)
                    val result = image?.let { img ->
                        val y = img.planes[0]
                        val u = img.planes[1]
                        val v = img.planes[2]
                        val tenBitSamples = y.pixelStride == 2 || img.format == android.graphics.ImageFormat.YCBCR_P010
                        fun sample(p: android.media.Image.Plane, x: Int, row: Int): Int {
                            val off = row * p.rowStride + x * p.pixelStride
                            val buf = p.buffer
                            return if (tenBitSamples) {
                                // P010 holds 10 bits in the HIGH bits of a 16-bit little-endian word.
                                ((buf.get(off).toInt() and 0xFF) or ((buf.get(off + 1).toInt() and 0xFF) shl 8)) shr 6
                            } else {
                                buf.get(off).toInt() and 0xFF
                            }
                        }
                        (0 until 4).joinToString(" ") { bar ->
                            val cx = (bar * 2 + 1) * img.width / 8
                            val cy = img.height / 2
                            "(${sample(y, cx, cy)},${sample(u, cx / 2, cy / 2)},${sample(v, cx / 2, cy / 2)})"
                        } + " fmt=0x${Integer.toHexString(img.format)} ${img.width}x${img.height} yStride=${y.pixelStride}"
                    } ?: "no image"
                    decoder.releaseOutputBuffer(o, false)
                    return result
                }
            }
            return "decode timeout"
        } finally {
            runCatching { decoder.stop() }
            decoder.release()
        }
    }

    private var lastShaderLog = ""

    private fun program(vs: String, fs: String): Int? {
        fun compile(type: Int, src: String): Int? {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                lastShaderLog = GLES30.glGetShaderInfoLog(s)
                return null
            }
            return s
        }
        val v = compile(GLES30.GL_VERTEX_SHADER, vs) ?: return null
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs) ?: return null
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v)
        GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            lastShaderLog = GLES30.glGetProgramInfoLog(p)
            return null
        }
        return p
    }
}
