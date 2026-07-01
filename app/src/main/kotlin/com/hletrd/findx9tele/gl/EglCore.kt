package com.hletrd.findx9tele.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface

/**
 * Minimal EGL 1.4 wrapper for an offscreen/onscreen GL ES 2 context that can render into both a
 * SurfaceView (preview) and a MediaCodec input Surface (recordable).
 *
 * @param tenBit request an RGBA1010102 config for 10-bit HDR video. Falls back to 8-bit if the
 *               device has no matching recordable config. True end-to-end HDR (BT.2020/HLG surface
 *               colorspace tagging) is device-specific and must be verified on hardware.
 */
class EglCore(sharedContext: EGLContext = EGL14.EGL_NO_CONTEXT, tenBit: Boolean = false) {

    val eglDisplay: EGLDisplay
    val eglContext: EGLContext
    private val eglConfig: EGLConfig

    init {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }

        eglConfig = chooseConfig(tenBit) ?: chooseConfig(false)
            ?: error("No suitable EGL config")

        val attrib = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, sharedContext, attrib, 0)
        checkEglError("eglCreateContext")
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "null EGL context" }
    }

    private fun chooseConfig(tenBit: Boolean): EGLConfig? {
        val r = if (tenBit) 10 else 8
        val a = if (tenBit) 2 else 8
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, r,
            EGL14.EGL_GREEN_SIZE, r,
            EGL14.EGL_BLUE_SIZE, r,
            EGL14.EGL_ALPHA_SIZE, a,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0)) return null
        return if (num[0] > 0) configs[0] else null
    }

    fun createWindowSurface(surface: Surface): EGLSurface {
        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        val eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, surfaceAttribs, 0)
        checkEglError("eglCreateWindowSurface")
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "null window surface" }
        return eglSurface
    }

    fun makeCurrent(eglSurface: EGLSurface) {
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }
    }

    fun makeNothingCurrent() {
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
    }

    fun swapBuffers(eglSurface: EGLSurface): Boolean = EGL14.eglSwapBuffers(eglDisplay, eglSurface)

    fun setPresentationTime(eglSurface: EGLSurface, nsecs: Long) {
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nsecs)
    }

    fun releaseSurface(eglSurface: EGLSurface) {
        if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
    }

    fun release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            makeNothingCurrent()
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
    }

    private fun checkEglError(op: String) {
        val err = EGL14.eglGetError()
        check(err == EGL14.EGL_SUCCESS) { "$op: EGL error 0x${Integer.toHexString(err)}" }
    }
}
