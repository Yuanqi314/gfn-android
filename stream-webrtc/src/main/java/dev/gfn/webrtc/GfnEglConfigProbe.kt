package dev.gfn.webrtc

import android.opengl.EGL14
import android.opengl.EGLConfig
import org.webrtc.EglBase

internal data class GfnEglConfigRequestSnapshot(
    val red: Int?,
    val green: Int?,
    val blue: Int?,
    val alpha: Int?,
    val renderableType: Int?,
)

internal data class GfnEglRuntimeConfigSnapshot(
    val configId: Int,
    val red: Int,
    val green: Int,
    val blue: Int,
    val alpha: Int,
    val renderableType: Int,
    val surfaceType: Int,
) {
    val isAtLeastTenBitRgb: Boolean
        get() = red >= 10 && green >= 10 && blue >= 10

    val isExactRgb10A2: Boolean
        get() = red == GfnEgl10BitConfig.RED_SIZE &&
            green == GfnEgl10BitConfig.GREEN_SIZE &&
            blue == GfnEgl10BitConfig.BLUE_SIZE &&
            alpha == GfnEgl10BitConfig.ALPHA_SIZE &&
            surfaceType and EGL14.EGL_WINDOW_BIT != 0
}

internal data class GfnEglRuntimeProbeResult(
    val snapshot: GfnEglRuntimeConfigSnapshot?,
    val error: String?,
)

/**
 * EGL evidence for v6.1.1. No EGL state is created, rebound, or mutated here; the runtime query is
 * executed from SurfaceViewRenderer's render-thread frame callback while its EGL context is current.
 */
internal object GfnEglConfigProbe {
    fun webRtcPlainRequest(): GfnEglConfigRequestSnapshot =
        parseRequest(EglBase.CONFIG_PLAIN)

    fun queryCurrentEgl14(): GfnEglRuntimeProbeResult {
        val display = EGL14.eglGetCurrentDisplay()
        if (display == null || display == EGL14.EGL_NO_DISPLAY) {
            return GfnEglRuntimeProbeResult(null, "EGL14 current display unavailable")
        }
        val context = EGL14.eglGetCurrentContext()
        if (context == null || context == EGL14.EGL_NO_CONTEXT) {
            return GfnEglRuntimeProbeResult(null, "EGL14 current context unavailable")
        }
        val drawSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        if (drawSurface == null || drawSurface == EGL14.EGL_NO_SURFACE) {
            return GfnEglRuntimeProbeResult(null, "EGL14 current draw surface unavailable")
        }

        val configIdValue = IntArray(1)
        val configIdSource = when {
            EGL14.eglQueryContext(display, context, EGL14.EGL_CONFIG_ID, configIdValue, 0) -> "context"
            EGL14.eglQuerySurface(display, drawSurface, EGL14.EGL_CONFIG_ID, configIdValue, 0) -> "surface"
            else -> return GfnEglRuntimeProbeResult(
                null,
                "EGL_CONFIG_ID query failed eglError=0x${EGL14.eglGetError().toString(16)}",
            )
        }
        val configId = configIdValue[0]
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val chosen = EGL14.eglChooseConfig(
            display,
            intArrayOf(EGL14.EGL_CONFIG_ID, configId, EGL14.EGL_NONE),
            0,
            configs,
            0,
            configs.size,
            count,
            0,
        )
        val config = configs.firstOrNull()
        if (!chosen || count[0] < 1 || config == null) {
            return GfnEglRuntimeProbeResult(
                null,
                "EGLConfig id=$configId from $configIdSource not resolved eglError=0x${EGL14.eglGetError().toString(16)}",
            )
        }

        fun attr(attribute: Int, label: String): Int {
            val value = IntArray(1)
            check(EGL14.eglGetConfigAttrib(display, config, attribute, value, 0)) {
                "$label query failed eglError=0x${EGL14.eglGetError().toString(16)}"
            }
            return value[0]
        }

        return runCatching {
            GfnEglRuntimeProbeResult(
                snapshot = GfnEglRuntimeConfigSnapshot(
                    configId = configId,
                    red = attr(EGL14.EGL_RED_SIZE, "EGL_RED_SIZE"),
                    green = attr(EGL14.EGL_GREEN_SIZE, "EGL_GREEN_SIZE"),
                    blue = attr(EGL14.EGL_BLUE_SIZE, "EGL_BLUE_SIZE"),
                    alpha = attr(EGL14.EGL_ALPHA_SIZE, "EGL_ALPHA_SIZE"),
                    renderableType = attr(EGL14.EGL_RENDERABLE_TYPE, "EGL_RENDERABLE_TYPE"),
                    surfaceType = attr(EGL14.EGL_SURFACE_TYPE, "EGL_SURFACE_TYPE"),
                ),
                error = null,
            )
        }.getOrElse { error ->
            GfnEglRuntimeProbeResult(null, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun parseRequest(attributes: IntArray): GfnEglConfigRequestSnapshot {
        val values = mutableMapOf<Int, Int>()
        var index = 0
        while (index < attributes.size) {
            val key = attributes[index]
            if (key == EGL14.EGL_NONE) break
            if (index + 1 >= attributes.size) break
            values[key] = attributes[index + 1]
            index += 2
        }
        return GfnEglConfigRequestSnapshot(
            red = values[EGL14.EGL_RED_SIZE],
            green = values[EGL14.EGL_GREEN_SIZE],
            blue = values[EGL14.EGL_BLUE_SIZE],
            alpha = values[EGL14.EGL_ALPHA_SIZE],
            renderableType = values[EGL14.EGL_RENDERABLE_TYPE],
        )
    }
}
