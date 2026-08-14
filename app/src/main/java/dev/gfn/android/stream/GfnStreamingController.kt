package dev.gfn.android.stream

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.gfn.core.model.SessionInfo
import dev.gfn.stream.StreamConfig
import dev.gfn.stream.StreamDiagnostics
import dev.gfn.stream.StreamState
import dev.gfn.webrtc.GfnKeyboardWireMode
import dev.gfn.webrtc.GfnVideoSurfaceView
import dev.gfn.webrtc.GfnWebRtcEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Android UI to stream-webrtc boundary. It never creates or claims CloudMatch sessions. */
class GfnStreamingController(
    context: Context,
    private val serverSessionEndedSink: (sessionId: String, source: String) -> Unit = { _, _ -> },
    private val transportReconcileSink: (sessionId: String, source: String) -> Unit = { _, _ -> },
) : GfnWebRtcEngine.Listener {
    private val engine = GfnWebRtcEngine(context.applicationContext, this)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<StreamState>(StreamState.Idle)
    val state: StateFlow<StreamState> = _state.asStateFlow()

    private val _diagnostics = MutableStateFlow(StreamDiagnostics())
    val diagnostics: StateFlow<StreamDiagnostics> = _diagnostics.asStateFlow()

    fun connectClaimedSession(session: SessionInfo) {
        engine.connect(session, StreamConfig())
    }

    fun disconnect() {
        engine.disconnect()
    }

    fun prepareForSessionEnd(onReleased: () -> Unit) {
        engine.prepareForSessionEnd {
            mainHandler.post(onReleased)
        }
    }

    fun onActivityResumed() = engine.onActivityResumed()
    fun onActivityPaused() = engine.onActivityPaused()
    fun onActivityDestroy() = engine.onActivityDestroy()
    fun setOverlayOpen(open: Boolean) = engine.onOverlayChanged(open)
    fun setKeyboardWireMode(mode: GfnKeyboardWireMode) = engine.setKeyboardWireMode(mode)
    fun onFullscreenExit() = engine.onFullscreenExit()

    fun bindVideoOutput(view: GfnVideoSurfaceView?) {
        engine.bindVideoOutput(view)
    }

    fun unbindVideoOutput(view: GfnVideoSurfaceView) {
        engine.unbindVideoOutput(view)
    }

    override fun onUpdated(state: StreamState, diagnostics: StreamDiagnostics) {
        Log.i("GfnStream", "state=${state.javaClass.simpleName} ice=${diagnostics.ice.iceConnectionState} pc=${diagnostics.ice.peerConnectionState}")
        _state.value = state
        _diagnostics.value = diagnostics
    }

    override fun onServerSessionEnded(sessionId: String, source: String) {
        Log.i("GfnStream", "server session ended source=$source")
        mainHandler.post { serverSessionEndedSink.invoke(sessionId, source) }
    }

    override fun onTransportNeedsReconcile(sessionId: String, source: String) {
        Log.i("GfnStream", "transport reconcile requested source=$source")
        mainHandler.post { transportReconcileSink.invoke(sessionId, source) }
    }
}
