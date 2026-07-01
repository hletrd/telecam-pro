package com.hletrd.findx9tele.gl

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.hletrd.findx9tele.camera.ColorTransfer

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
    var inputSurface: Surface? = null
        private set

    private var previewEgl: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderEgl: EGLSurface = EGL14.EGL_NO_SURFACE

    private var previewW = 0
    private var previewH = 0
    private var encoderW = 0
    private var encoderH = 0
    private var cameraW = 1
    private var cameraH = 1

    private var transfer: ColorTransfer? = null
    private var peaking = false
    private var zebra = false
    private var tenBit = false

    private var inited = false
    private val stMatrix = FloatArray(16)
    private var onInputReady: ((Surface) -> Unit)? = null

    fun start(tenBit: Boolean, onInputReady: (Surface) -> Unit) {
        this.tenBit = tenBit
        this.onInputReady = onInputReady
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
            return@post
        }
        previewW = width
        previewH = height
        previewEgl = core.createWindowSurface(surface)
        core.makeCurrent(previewEgl)
        if (!inited) {
            val texId = renderer.init()
            val st = SurfaceTexture(texId)
            st.setDefaultBufferSize(cameraW, cameraH)
            st.setOnFrameAvailableListener({ post { drawFrame() } }, handler)
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
    fun setTransfer(t: ColorTransfer?) = post { transfer = t }
    fun setPeaking(enabled: Boolean) = post { peaking = enabled }
    fun setZebra(enabled: Boolean) = post { zebra = enabled }

    fun setEncoderOutput(surface: Surface?, width: Int, height: Int) = post {
        val core = egl ?: return@post
        if (encoderEgl != EGL14.EGL_NO_SURFACE) {
            core.releaseSurface(encoderEgl)
            encoderEgl = EGL14.EGL_NO_SURFACE
        }
        if (surface != null) {
            encoderW = width
            encoderH = height
            encoderEgl = core.createWindowSurface(surface)
        }
    }

    private fun drawFrame() {
        val core = egl ?: return
        val st = surfaceTexture ?: return
        if (previewEgl == EGL14.EGL_NO_SURFACE) return

        st.updateTexImage()
        st.getTransformMatrix(stMatrix)

        core.makeCurrent(previewEgl)
        renderer.draw(stMatrix, previewW, previewH, transfer = null, peaking = peaking, zebra = zebra)
        core.swapBuffers(previewEgl)

        if (encoderEgl != EGL14.EGL_NO_SURFACE) {
            core.makeCurrent(encoderEgl)
            renderer.draw(stMatrix, encoderW, encoderH, transfer = transfer, peaking = false, zebra = false)
            core.setPresentationTime(encoderEgl, st.timestamp)
            core.swapBuffers(encoderEgl)
        }
    }

    fun stop() {
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
