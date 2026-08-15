package dev.gfn.webrtc

import android.graphics.PixelFormat
import android.opengl.EGL14

/**
 * Dormant v6.1.1-C RGB10A2 contract.
 *
 * Stage C0 only probes whether a matching window EGLConfig exists. These attributes are deliberately
 * not wired into [GfnVideoSurfaceView] yet; Stage C1 activation is gated by true-device C0 evidence.
 */
internal object GfnEgl10BitConfig {
    const val RED_SIZE = 10
    const val GREEN_SIZE = 10
    const val BLUE_SIZE = 10
    const val ALPHA_SIZE = 2

    const val SURFACE_PIXEL_FORMAT_LABEL = "RGBA_1010102"
    val surfacePixelFormat: Int
        get() = PixelFormat.RGBA_1010102

    fun rendererAttributes(): IntArray = intArrayOf(
        EGL14.EGL_SURFACE_TYPE,
        EGL14.EGL_WINDOW_BIT,
        EGL14.EGL_RENDERABLE_TYPE,
        EGL14.EGL_OPENGL_ES2_BIT,
        EGL14.EGL_RED_SIZE,
        RED_SIZE,
        EGL14.EGL_GREEN_SIZE,
        GREEN_SIZE,
        EGL14.EGL_BLUE_SIZE,
        BLUE_SIZE,
        EGL14.EGL_ALPHA_SIZE,
        ALPHA_SIZE,
        EGL14.EGL_NONE,
    )
}
