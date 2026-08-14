package dev.gfn.webrtc

import android.content.Context
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/** v5.0 的 H.264 SDR 输出面；后续 Main10/HDR 可替换为 direct MediaCodec Surface。 */
class GfnVideoSurfaceView(context: Context) : SurfaceViewRenderer(context) {
    var onFirstFrame: (() -> Unit)? = null
    var onResolutionChanged: ((width: Int, height: Int) -> Unit)? = null

    private var released = false

    init {
        init(
            GfnWebRtcRuntime.eglContext(),
            object : RendererCommon.RendererEvents {
                override fun onFirstFrameRendered() {
                    onFirstFrame?.invoke()
                }

                override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {
                    val width = if (rotation % 180 == 0) videoWidth else videoHeight
                    val height = if (rotation % 180 == 0) videoHeight else videoWidth
                    onResolutionChanged?.invoke(width, height)
                }
            },
        )
        setEnableHardwareScaler(true)
        setMirror(false)
        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
    }

    fun releaseRenderer() {
        if (released) return
        released = true
        onFirstFrame = null
        onResolutionChanged = null
        release()
    }
}
