package com.hletrd.findx9tele.ui

import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface

/**
 * Robolectric leaves EGL14's sentinel statics (EGL_NO_DISPLAY / EGL_NO_SURFACE / EGL_NO_CONTEXT)
 * null: the real framework assigns them from `_nativeClassInit()`, which the sandbox no-ops, and
 * ShadowEGL14 (4.16.1) shadows only the egl* entry points — it has no static-initializer shadow.
 * `GlPipeline`'s field initializers read `EGL14.EGL_NO_SURFACE` into non-null Kotlin types, so
 * constructing the REAL `CameraEngine` under Robolectric requires the sentinels first. The fields
 * are deliberately non-final in the framework; handle value 0 matches the native sentinel.
 */
internal object RobolectricEglSentinels {

    fun ensure() {
        if (EGL14.EGL_NO_DISPLAY == null) EGL14.EGL_NO_DISPLAY = construct(EGLDisplay::class.java)
        if (EGL14.EGL_NO_CONTEXT == null) EGL14.EGL_NO_CONTEXT = construct(EGLContext::class.java)
        if (EGL14.EGL_NO_SURFACE == null) EGL14.EGL_NO_SURFACE = construct(EGLSurface::class.java)
    }

    // EGLDisplay/EGLContext/EGLSurface each hide a (long) constructor; reflection is the only way
    // to mint the sentinel instances the native class-init would otherwise provide.
    private fun <T> construct(type: Class<T>): T =
        type.getDeclaredConstructor(Long::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .newInstance(0L)
}
