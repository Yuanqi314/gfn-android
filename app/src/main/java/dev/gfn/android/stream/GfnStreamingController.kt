package dev.gfn.android.stream

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.gfn.core.model.SessionInfo
import dev.gfn.stream.StreamConfig
import dev.gfn.stream.StreamDiagnostics
import dev.gfn.stream.StreamState
import dev.gfn.webrtc.GfnVideoSurfaceView
import dev.gfn.webrtc.GfnWebRtcEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Android UI 与 stream-webrtc 的薄边界；不参与 CloudMatch Create/Claim。 */
class GfnStreamingController(context: Context) : GfnWebRtcEngine.Listener {
    private val engine = GfnWebRtcEngine(context.applicationContext, this)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<StreamState>(StreamState.Idle)
    val state: StateFlow<StreamState> = _state.asStateFlow()

    private val _diagnostics = MutableStateFlow(StreamDiagnostics())
    val diagnostics: StateFlow<StreamDiagnostics> = _diagnostics.asStateFlow()

    fun connectClaimedSession(session: SessionInfo) {
        engine.connect(session, StreamConfig())
    }

    /** 主动断开会先在 ordered input queue 中 releaseAll，再关闭 DataChannel/PeerConnection。 */
    fun disconnect() {
        engine.disconnect()
    }

    /** Session DELETE 前先完成 release packet submission barrier。 */
    fun prepareForSessionEnd(onReleased: () -> Unit) {
        engine.prepareForSessionEnd {
            mainHandler.post(onReleased)
        }
    }

    fun onActivityResumed() = engine.onActivityResumed()
    fun onActivityPaused() = engine.onActivityPaused()
    fun onActivityDestroy() = engine.onActivityDestroy()
    fun setOverlayOpen(open: Boolean) = engine.onOverlayChanged(open)
    fun onFullscreenExit() = engine.onFullscreenExit()

    fun bindVideoOutput(view: GfnVideoSurfaceView?) {
        engine.bindVideoOutput(view)
    }

    fun unbindVideoOutput(view: GfnVideoSurfaceView) {
        engine.unbindVideoOutput(view)
    }

    override fun onUpdated(state: StreamState, diagnostics: StreamDiagnostics) {
        _state.value = state
        _diagnostics.value = diagnostics
    }
}
