package dev.gfn.webrtc

import android.graphics.PixelFormat
import android.opengl.EGL14

/**
 * v6.1.1-C RGB10A2 render-target contract.
 *
 * Stage C1 uses this only for HEVC + PreferSdr10 views after a pre-init capability check resolves
 * one exact fixed/non-float RGB10A2 window config. SDR8/H264 views stay on WebRTC CONFIG_PLAIN.
 */
internal object GfnEgl10BitConfig {
    const val RED_SIZE = 10
    const val GREEN_SIZE = 10
    const val BLUE_SIZE = 10
    const val ALPHA_SIZE = 2

    const val SURFACE_PIXEL_FORMAT_LABEL = "RGBA_1010102"
    val surfacePixelFormat: Int
        get() = PixelFormat.RGBA_1010102

    fun rendererAttributes(configId: Int? = null): IntArray = buildList {
        if (configId != null) {
            add(EGL14.EGL_CONFIG_ID)
            add(configId)
        }
        add(EGL14.EGL_SURFACE_TYPE)
        add(EGL14.EGL_WINDOW_BIT)
        add(EGL14.EGL_RENDERABLE_TYPE)
        add(EGL14.EGL_OPENGL_ES2_BIT)
        add(EGL14.EGL_RED_SIZE)
        add(RED_SIZE)
        add(EGL14.EGL_GREEN_SIZE)
        add(GREEN_SIZE)
        add(EGL14.EGL_BLUE_SIZE)
        add(BLUE_SIZE)
        add(EGL14.EGL_ALPHA_SIZE)
        add(ALPHA_SIZE)
        add(EGL14.EGL_NONE)
    }.toIntArray()
}
