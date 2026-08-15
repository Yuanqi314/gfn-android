package dev.gfn.webrtc

import android.util.Log

/** Stage C render-target evidence. No renderer or Surface state is mutated here. */
internal object Gfn10BitRenderDiagnostics {
    private const val TAG = "GfnHevc10Bit"

    fun logEgl10BitCapability(viewId: Int, result: GfnEgl10BitCapabilityResult) {
        val selected = result.selected
        val common =
            "phase=EGL10_CAPABILITY view=$viewId status=${result.status} supported=${result.supported} " +
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
}
