package dev.gfn.webrtc

import android.util.Log

/** Stage C render-target evidence. Renderer selection is performed by GfnVideoSurfaceView. */
internal object Gfn10BitRenderDiagnostics {
    private const val TAG = "GfnHevc10Bit"

    fun logRendererTargetRequest(
        viewId: Int,
        requestedRgb10A2: Boolean,
        activeRgb10A2: Boolean,
        selectedConfigId: Int?,
        fallbackReason: String?,
    ) {
        val requested = if (requestedRgb10A2) "RGB10A2" else "WEBRTC_M144_RGB888"
        val active = if (activeRgb10A2) "RGB10A2" else "WEBRTC_M144_RGB888"
        val message =
            "phase=EGL_TARGET_REQUEST view=$viewId requested=$requested active=$active " +
                "selectedConfigId=${selectedConfigId ?: "NONE"} " +
                "surfaceFormatPolicy=AUTO_FROM_EGL_NATIVE_VISUAL " +
                "holderSetFormat=false fallback=\"${fallbackReason ?: "NONE"}\""
        if (requestedRgb10A2 && !activeRgb10A2) Log.w(TAG, message) else Log.i(TAG, message)
    }

    fun logEgl10BitCapability(
        viewId: Int,
        result: GfnEgl10BitCapabilityResult,
        phase: String = "EGL10_CAPABILITY",
    ) {
        val selected = result.selected
        val common =
            "phase=$phase view=$viewId status=${result.status} supported=${result.supported} " +
                "probeRequest=R10G10B10A2 candidateSurface=${GfnEgl10BitConfig.SURFACE_PIXEL_FORMAT_LABEL} " +
                "surfacePixelFormat=${GfnEgl10BitConfig.surfacePixelFormat} " +
                "candidateCount=${result.candidateCount} inspectedCount=${result.inspectedCount}"

        if (selected == null) {
            val message = "$common reason=\"${result.error ?: "unknown"}\""
            if (result.status == GfnEgl10BitCapabilityStatus.Unsupported) {
                Log.i(TAG, message)
            } else {
                Log.w(TAG, message)
            }
            return
        }

        Log.i(
            TAG,
            "$common configId=${selected.configId} red=${selected.red} green=${selected.green} " +
                "blue=${selected.blue} alpha=${selected.alpha} renderableType=${selected.renderableType} " +
                "surfaceType=${selected.surfaceType} nativeVisualId=${selected.nativeVisualId ?: "UNAVAILABLE"} " +
                "nativeVisualMatchesSurface=${selected.nativeVisualMatchesRequestedSurfaceFormat ?: "UNKNOWN"} " +
                "colorComponentType=${selected.colorComponentType ?: "UNAVAILABLE"} " +
                "explicitFloat=${selected.isExplicitlyFloatColor}",
        )
    }

    fun logRuntimeTargetVerdict(
        viewId: Int,
        requestedRgb10A2: Boolean,
        result: GfnEglRuntimeProbeResult,
    ) {
        val snapshot = result.snapshot
        if (snapshot == null) {
            Log.w(
                TAG,
                "phase=EGL_TARGET_ACTIVE view=$viewId requestedRgb10A2=$requestedRgb10A2 " +
                    "active=false reason=\"${result.error ?: "runtime EGL config unavailable"}\"",
            )
            return
        }
        val active = !requestedRgb10A2 || snapshot.isExactRgb10A2
        val message =
            "phase=EGL_TARGET_ACTIVE view=$viewId requestedRgb10A2=$requestedRgb10A2 active=$active " +
                "configId=${snapshot.configId} red=${snapshot.red} green=${snapshot.green} " +
                "blue=${snapshot.blue} alpha=${snapshot.alpha} exactRgb10A2=${snapshot.isExactRgb10A2}"
        if (requestedRgb10A2 && !active) Log.e(TAG, message) else Log.i(TAG, message)
    }

    fun logSurfaceFormat(
        viewId: Int,
        requestedRgb10A2: Boolean,
        activeRgb10A2: Boolean,
        actualFormat: Int,
        width: Int,
        height: Int,
    ) {
        Log.i(
            TAG,
            "phase=SURFACE_FORMAT view=$viewId requestedRgb10A2=$requestedRgb10A2 " +
                "activeRgb10A2=$activeRgb10A2 holderSetFormat=false actualCallbackFormat=$actualFormat " +
                "size=${width}x$height expectedRgb10A2=${GfnEgl10BitConfig.surfacePixelFormat}",
        )
    }
}
