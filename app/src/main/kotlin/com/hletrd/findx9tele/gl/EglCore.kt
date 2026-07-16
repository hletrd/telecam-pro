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
 * TextureView-owned preview Surface and a MediaCodec input Surface (recordable).
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
        checkEglResult(
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext),
            "eglMakeCurrent",
        )
    }

    fun makeNothingCurrent() {
        checkEglResult(
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            ),
            "eglMakeCurrent(none)",
        )
    }

    fun swapBuffers(eglSurface: EGLSurface) {
        checkEglResult(EGL14.eglSwapBuffers(eglDisplay, eglSurface), "eglSwapBuffers")
    }

    fun setPresentationTime(eglSurface: EGLSurface, nsecs: Long) {
        checkEglResult(
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nsecs),
            "eglPresentationTimeANDROID",
        )
    }

    fun releaseSurface(eglSurface: EGLSurface) {
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            checkEglResult(EGL14.eglDestroySurface(eglDisplay, eglSurface), "eglDestroySurface")
        }
    }

    /**
     * Relinquishes every surface/context currently owned by this thread. The checked
     * [EGL14.eglReleaseThread] fallback is intentionally separate from display teardown so callers
     * can use successful return as the native-window ownership boundary.
     */
    fun releaseCurrentOwnership() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            // A broken output can make the ordinary no-surface transition fail. eglReleaseThread
            // still relinquishes this thread's current context before context/display teardown.
            val ordinaryUnbind = runCatching { makeNothingCurrent() }
            if (ordinaryUnbind.isFailure) {
                checkEglResult(EGL14.eglReleaseThread(), "eglReleaseThread(unbind)")
            }
        }
    }

    /** Attempts every terminal cleanup stage; true means the EGLDisplay itself was terminated. */
    fun releaseAfterCurrentOwnership(): Boolean {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return true
        runCatching {
            checkEglResult(EGL14.eglDestroyContext(eglDisplay, eglContext), "eglDestroyContext")
        }
        runCatching { checkEglResult(EGL14.eglReleaseThread(), "eglReleaseThread") }
        return runCatching {
            checkEglResult(EGL14.eglTerminate(eglDisplay), "eglTerminate")
        }.isSuccess
    }

    fun release() {
        releaseCurrentOwnership()
        check(releaseAfterCurrentOwnership()) { "EGL display release failed" }
    }

    private fun checkEglError(op: String) {
        val err = EGL14.eglGetError()
        check(err == EGL14.EGL_SUCCESS) { "$op: EGL error 0x${Integer.toHexString(err)}" }
    }

    private fun checkEglResult(success: Boolean, op: String) {
        requireEglSuccess(success, op, EGL14::eglGetError)
    }
}

/** Consumes the failing EGL error immediately so it cannot poison a later checked operation. */
internal fun requireEglSuccess(success: Boolean, op: String, getError: () -> Int) {
    if (success) return
    val errorCode = getError()
    error("$op: EGL error 0x${Integer.toHexString(errorCode)}")
}
