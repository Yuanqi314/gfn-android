package dev.gfn.webrtc

import android.graphics.PixelFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLDisplay

internal data class GfnEgl10BitConfigCandidate(
    val configId: Int,
    val red: Int,
    val green: Int,
    val blue: Int,
    val alpha: Int,
    val renderableType: Int,
    val surfaceType: Int,
    val nativeVisualId: Int?,
    val colorComponentType: Int?,
) {
    val hasWindowSurface: Boolean
        get() = surfaceType and EGL14.EGL_WINDOW_BIT != 0

    val hasOpenGlEs2: Boolean
        get() = renderableType and EGL14.EGL_OPENGL_ES2_BIT != 0

    val isExactRgb10A2: Boolean
        get() = red == GfnEgl10BitConfig.RED_SIZE &&
            green == GfnEgl10BitConfig.GREEN_SIZE &&
            blue == GfnEgl10BitConfig.BLUE_SIZE &&
            alpha == GfnEgl10BitConfig.ALPHA_SIZE &&
            hasWindowSurface &&
            hasOpenGlEs2

    val nativeVisualMatchesRequestedSurfaceFormat: Boolean?
        get() = nativeVisualId?.let { it == PixelFormat.RGBA_1010102 }

    val isExplicitlyFloatColor: Boolean
        get() = colorComponentType == GfnEgl10BitCapabilityProbe.EGL_COLOR_COMPONENT_TYPE_FLOAT_EXT
}

internal enum class GfnEgl10BitCapabilityStatus {
    Supported,
    Unsupported,
    Unresolved,
}

internal data class GfnEgl10BitCapabilityResult(
    val status: GfnEgl10BitCapabilityStatus,
    val candidateCount: Int,
    val inspectedCount: Int,
    val selected: GfnEgl10BitConfigCandidate?,
    val error: String?,
) {
    val supported: Boolean
        get() = status == GfnEgl10BitCapabilityStatus.Supported
}

/**
 * Read-only Stage C0 capability probe.
 *
 * This is intentionally executed from the existing M144 renderer thread while an EGLDisplay is
 * current. It only calls eglQueryString/eglChooseConfig/eglGetConfigAttrib; it does not create or
 * rebind an EGLContext, create an EGLSurface, alter the native window, or change renderer config.
 */
internal object GfnEgl10BitCapabilityProbe {
    private const val MAX_SAFE_CANDIDATES = 4096

    // EGL_KHR/EXT_pixel_format_float constants from Khronos EGL extension headers.
    internal const val EGL_COLOR_COMPONENT_TYPE_EXT = 0x3339
    internal const val EGL_COLOR_COMPONENT_TYPE_FIXED_EXT = 0x333A
    internal const val EGL_COLOR_COMPONENT_TYPE_FLOAT_EXT = 0x333B

    fun queryCurrentDisplayEgl14(): GfnEgl10BitCapabilityResult {
        val display = EGL14.eglGetCurrentDisplay()
        if (display == null || display == EGL14.EGL_NO_DISPLAY) {
            return unresolved("EGL14 current display unavailable")
        }

        val extensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS).orEmpty()
        val hasColorComponentType = extensions.split(' ').any { extension ->
            extension == "EGL_EXT_pixel_format_float" || extension == "EGL_KHR_pixel_format_float"
        }
        val attributes = GfnEgl10BitConfig.rendererAttributes()

        // First pass asks EGL for the exact candidate count. Android's EGL14 JNI accepts a null
        // configs array when config_size=0 and forwards a null native EGLConfig pointer.
        val count = IntArray(1)
        val countOk = EGL14.eglChooseConfig(
            display,
            attributes,
            0,
            null,
            0,
            0,
            count,
            0,
        )
        if (!countOk) {
            return unresolved("RGB10A2 eglChooseConfig count query failed eglError=${eglErrorHex()}")
        }

        val candidateCount = count[0]
        if (candidateCount < 0) {
            return unresolved("RGB10A2 eglChooseConfig returned invalid candidateCount=$candidateCount")
        }
        if (candidateCount == 0) {
            return GfnEgl10BitCapabilityResult(
                status = GfnEgl10BitCapabilityStatus.Unsupported,
                candidateCount = 0,
                inspectedCount = 0,
                selected = null,
                error = "no EGLConfig matched RGB10A2 WINDOW + GLES2 request",
            )
        }
        if (candidateCount > MAX_SAFE_CANDIDATES) {
            return GfnEgl10BitCapabilityResult(
                status = GfnEgl10BitCapabilityStatus.Unresolved,
                candidateCount = candidateCount,
                inspectedCount = 0,
                selected = null,
                error = "RGB10A2 candidateCount=$candidateCount exceeds safe probe bound=$MAX_SAFE_CANDIDATES",
            )
        }

        val configs = arrayOfNulls<EGLConfig>(candidateCount)
        val filled = IntArray(1)
        val chooseOk = EGL14.eglChooseConfig(
            display,
            attributes,
            0,
            configs,
            0,
            configs.size,
            filled,
            0,
        )
        if (!chooseOk) {
            return GfnEgl10BitCapabilityResult(
                status = GfnEgl10BitCapabilityStatus.Unresolved,
                candidateCount = candidateCount,
                inspectedCount = 0,
                selected = null,
                error = "RGB10A2 eglChooseConfig fill query failed eglError=${eglErrorHex()}",
            )
        }

        val inspectedCount = minOf(filled[0].coerceAtLeast(0), configs.size)
        val candidates = buildList {
            for (index in 0 until inspectedCount) {
                val config = configs[index] ?: continue
                readCandidate(display, config, hasColorComponentType)?.let(::add)
            }
        }
        val selected = candidates
            .filter { it.isExactRgb10A2 && !it.isExplicitlyFloatColor }
            .minByOrNull { it.configId }

        if (selected != null) {
            return GfnEgl10BitCapabilityResult(
                status = GfnEgl10BitCapabilityStatus.Supported,
                candidateCount = candidateCount,
                inspectedCount = inspectedCount,
                selected = selected,
                error = null,
            )
        }

        val complete = inspectedCount == candidateCount
        return GfnEgl10BitCapabilityResult(
            status = if (complete) {
                GfnEgl10BitCapabilityStatus.Unsupported
            } else {
                GfnEgl10BitCapabilityStatus.Unresolved
            },
            candidateCount = candidateCount,
            inspectedCount = inspectedCount,
            selected = null,
            error = if (complete) {
                "all RGB10A2-request candidates inspected; none was exact fixed/non-float R10G10B10A2 WINDOW + GLES2"
            } else {
                "RGB10A2 candidate enumeration incomplete expected=$candidateCount inspected=$inspectedCount"
            },
        )
    }

    private fun readCandidate(
        display: EGLDisplay,
        config: EGLConfig,
        hasColorComponentType: Boolean,
    ): GfnEgl10BitConfigCandidate? {
        fun requiredAttr(attribute: Int): Int? {
            val value = IntArray(1)
            return if (EGL14.eglGetConfigAttrib(display, config, attribute, value, 0)) {
                value[0]
            } else {
                EGL14.eglGetError()
                null
            }
        }

        fun optionalAttr(attribute: Int): Int? {
            val value = IntArray(1)
            if (EGL14.eglGetConfigAttrib(display, config, attribute, value, 0)) return value[0]
            EGL14.eglGetError() // Clear diagnostics-only query errors before returning to renderer code.
            return null
        }

        return GfnEgl10BitConfigCandidate(
            configId = requiredAttr(EGL14.EGL_CONFIG_ID) ?: return null,
            red = requiredAttr(EGL14.EGL_RED_SIZE) ?: return null,
            green = requiredAttr(EGL14.EGL_GREEN_SIZE) ?: return null,
            blue = requiredAttr(EGL14.EGL_BLUE_SIZE) ?: return null,
            alpha = requiredAttr(EGL14.EGL_ALPHA_SIZE) ?: return null,
            renderableType = requiredAttr(EGL14.EGL_RENDERABLE_TYPE) ?: return null,
            surfaceType = requiredAttr(EGL14.EGL_SURFACE_TYPE) ?: return null,
            nativeVisualId = optionalAttr(EGL14.EGL_NATIVE_VISUAL_ID),
            colorComponentType = if (hasColorComponentType) optionalAttr(EGL_COLOR_COMPONENT_TYPE_EXT) else null,
        )
    }

    private fun unresolved(reason: String): GfnEgl10BitCapabilityResult =
        GfnEgl10BitCapabilityResult(
            status = GfnEgl10BitCapabilityStatus.Unresolved,
            candidateCount = 0,
            inspectedCount = 0,
            selected = null,
            error = reason,
        )

    private fun eglErrorHex(): String = "0x${EGL14.eglGetError().toString(16)}"
}
