package dev.gfn.android.stream

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.gfn.android.session.StreamReconnectSessionResult
import dev.gfn.android.settings.ResolvedLaunchProfile
import dev.gfn.core.model.RequestedColorMode
import dev.gfn.core.model.SessionInfo
import dev.gfn.stream.ReconnectDiagnostics
import dev.gfn.stream.StreamDiagnostics
import dev.gfn.stream.StreamState
import dev.gfn.stream.VideoCodecPreference
import dev.gfn.webrtc.GfnVideoSurfaceView
import dev.gfn.webrtc.GfnWebRtcEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android UI to stream-webrtc boundary.
 *
 * v5.2.1 transport recovery is deliberately split here:
 * - GfnWebRtcEngine owns transport teardown/rebuild only;
 * - GfnSessionController performs same-session RESUME/Claim;
 * - this controller keeps the immutable Session ID + ResolvedLaunchProfile invariant.
 *
 * No reconnect path in this class creates a CloudMatch Session.
 */
class GfnStreamingController(
    context: Context,
    private val serverSessionEndedSink: (sessionId: String, source: String) -> Unit = { _, _ -> },
    private val transportReconcileSink: (sessionId: String, source: String) -> Unit = { _, _ -> },
    private val transportRecoverySink: (
        sessionId: String,
        source: String,
        attempt: Int,
        callback: (StreamReconnectSessionResult) -> Unit,
    ) -> Unit = { _, _, _, callback ->
        callback(StreamReconnectSessionResult.RetryableFailure("same-session reconnect sink 未配置。"))
    },
) : GfnWebRtcEngine.Listener {
    private val engine = GfnWebRtcEngine(context.applicationContext, this)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<StreamState>(StreamState.Idle)
    val state: StateFlow<StreamState> = _state.asStateFlow()

    private val _diagnostics = MutableStateFlow(StreamDiagnostics())
    val diagnostics: StateFlow<StreamDiagnostics> = _diagnostics.asStateFlow()

    private var activeSessionId: String? = null
    private var frozenProfile: ResolvedLaunchProfile? = null

    private var recoveryGeneration = 0L
    private var reconnectActive = false
    private var reconnectAttempt = 0
    private var reconnectSource: String? = null
    private var reconnectPhase = PHASE_IDLE
    private var reconnectLastError: String? = null
    private var sameSessionIdVerified = false
    private var frozenProfileVerified = false
    private var pendingRecoveryRunnable: Runnable? = null
    private var graceTransportHealthy = false

    fun connectClaimedSession(session: SessionInfo, profile: ResolvedLaunchProfile) {
        cancelReconnect(clearActive = false, resetAttempt = true)
        activeSessionId = session.sessionId
        frozenProfile = profile
        Log.i("GfnLaunchProfile", "WEBRTC sessionId=${session.sessionId} ${profile.summary}")
        engine.connect(session, profile.streamConfig)
    }

    fun disconnect() {
        cancelReconnect(clearActive = true, resetAttempt = true)
        engine.disconnect()
    }

    fun prepareForSessionEnd(onReleased: () -> Unit) {
        cancelReconnect(clearActive = true, resetAttempt = true)
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

    /**
     * Stage C1 is scoped strictly to the immutable Session snapshot. Never consult live Settings
     * here: reconnect/fullscreen recreation must rebuild the same render-target intent.
     */
    fun shouldUseRgb10A2RenderTarget(): Boolean {
        val config = frozenProfile?.streamConfig ?: return false
        return config.codec == VideoCodecPreference.Hevc &&
            config.colorMode == RequestedColorMode.PreferSdr10
    }

    override fun onUpdated(state: StreamState, diagnostics: StreamDiagnostics) {
        mainHandler.post {
            handleEngineUpdate(state, diagnostics)
        }
    }

    override fun onServerSessionEnded(sessionId: String, source: String) {
        mainHandler.post {
            Log.i(TAG, "server session ended source=$source")
            cancelReconnect(clearActive = true, resetAttempt = true)
            publish(StreamState.SessionEnded, engine.diagnostics)
            serverSessionEndedSink.invoke(sessionId, source)
        }
    }

    override fun onTransportNeedsReconcile(sessionId: String, source: String) {
        Log.i(TAG, "transport reconcile requested source=$source")
        mainHandler.post { transportReconcileSink.invoke(sessionId, source) }
    }

    override fun onTransportNeedsReconnect(sessionId: String, source: String, immediate: Boolean) {
        mainHandler.post {
            requestReconnect(sessionId, source, immediate)
        }
    }

    private fun handleEngineUpdate(state: StreamState, diagnostics: StreamDiagnostics) {
        Log.i(
            TAG,
            "engineState=${state.javaClass.simpleName} ice=${diagnostics.ice.iceConnectionState} " +
                "pc=${diagnostics.ice.peerConnectionState} reconnect=$reconnectPhase/$reconnectAttempt",
        )

        if (!reconnectActive) {
            publish(state, diagnostics)
            return
        }

        // A DISCONNECTED grace period may self-heal only after both ICE/PC are healthy and
        // fresh video activity proves the media path is actually flowing again. StreamState can
        // remain FirstFrame/Connected while ICE/PC are DISCONNECTED, so state alone is not a
        // recovery witness.
        if (reconnectPhase == PHASE_GRACE) {
            val transportHealthy = isTransportHealthy(state, diagnostics)
            if (transportHealthy && !graceTransportHealthy) {
                graceTransportHealthy = true
                engine.beginVideoRecoveryLiveness()
                Log.i(RECONNECT_TAG, "transport restored; awaiting fresh video source=$reconnectSource")
            } else if (!transportHealthy && graceTransportHealthy) {
                graceTransportHealthy = false
                engine.invalidateVideoRecoveryLiveness()
            }
            if (transportHealthy && isGraceMediaHealthy(logDecision = false)) {
                completeGraceRecovery(state, diagnostics)
                return
            }
        }

        // After a reclaimed Session is connected, v5.2.1 considers recovery complete only after
        // both a rendered video frame and the fresh input_channel_v1 protocol handshake exist.
        if (
            reconnectPhase == PHASE_CONNECTING &&
            state is StreamState.FirstFrame &&
            diagnostics.video.firstFrameRendered &&
            diagnostics.input.protocolReady
        ) {
            Log.i(
                RECONNECT_TAG,
                "SUCCESS sessionId=$activeSessionId attempt=$reconnectAttempt " +
                    "firstFrame=true inputHandshake=true",
            )
            val completedAttempt = reconnectAttempt
            val completedSource = reconnectSource
            cancelScheduledRecovery()
            reconnectActive = false
            reconnectPhase = PHASE_IDLE
            reconnectLastError = null
            sameSessionIdVerified = true
            frozenProfileVerified = true
            publish(state, diagnostics)
            Log.i(RECONNECT_TAG, "STABLE attempt=$completedAttempt source=$completedSource")
            reconnectAttempt = 0
            reconnectSource = null
            return
        }

        if (reconnectPhase == PHASE_CONNECTING && state is StreamState.Failed) {
            scheduleRetry("new transport failed: ${state.reason}")
            return
        }

        // Keep the public state in Reconnecting while the new signaling/ICE/input pipeline is built.
        val displayAttempt = reconnectAttempt.coerceAtLeast(1)
        publish(StreamState.Reconnecting(displayAttempt, reconnectSource ?: "transport"), diagnostics)
    }

    private fun requestReconnect(sessionId: String, source: String, immediate: Boolean) {
        val expectedSessionId = activeSessionId
        val profile = frozenProfile
        if (expectedSessionId == null || profile == null || expectedSessionId != sessionId) {
            Log.i(
                RECONNECT_TAG,
                "ignore stale reconnect request expected=$expectedSessionId actual=$sessionId source=$source",
            )
            return
        }
        if (_state.value is StreamState.SessionEnded || _state.value is StreamState.Closed) return

        if (reconnectActive) {
            // FAILED is stronger evidence than DISCONNECTED. If still only waiting in grace,
            // accelerate the existing recovery. If the freshly rebuilt transport itself fails,
            // consume one bounded retry instead of waiting for a StreamState.Failed that the engine
            // intentionally no longer emits for recoverable ICE/PC failures.
            if (immediate && reconnectPhase == PHASE_GRACE) {
                reconnectSource = source
                cancelScheduledRecovery()
                beginReconnectAttempt()
            } else if (immediate && reconnectPhase == PHASE_CONNECTING) {
                reconnectSource = source
                scheduleRetry("reclaimed transport failure: $source")
            } else {
                Log.i(RECONNECT_TAG, "coalesced source=$source phase=$reconnectPhase attempt=$reconnectAttempt")
            }
            return
        }

        reconnectActive = true
        reconnectSource = source
        reconnectLastError = null
        sameSessionIdVerified = false
        frozenProfileVerified = false
        graceTransportHealthy = false
        engine.invalidateVideoRecoveryLiveness()

        if (immediate) {
            beginReconnectAttempt()
        } else {
            reconnectPhase = PHASE_GRACE
            publish(
                StreamState.Reconnecting(1, source),
                engine.diagnostics,
            )
            val generation = recoveryGeneration
            val runnable = Runnable {
                pendingRecoveryRunnable = null
                if (!reconnectActive || recoveryGeneration != generation || reconnectPhase != PHASE_GRACE) return@Runnable
                if (isTransportHealthy(engine.state, engine.diagnostics) && isGraceMediaHealthy(logDecision = true)) {
                    completeGraceRecovery(engine.state, engine.diagnostics)
                    return@Runnable
                }
                Log.w(
                    RECONNECT_TAG,
                    "grace expired without verified media; rebuilding same Session source=$reconnectSource",
                )
                beginReconnectAttempt()
            }
            pendingRecoveryRunnable = runnable
            mainHandler.postDelayed(runnable, DISCONNECTED_GRACE_MS)
            Log.i(RECONNECT_TAG, "grace source=$source delayMs=$DISCONNECTED_GRACE_MS sessionId=$sessionId")
        }
    }

    private fun beginReconnectAttempt() {
        cancelScheduledRecovery()
        if (!reconnectActive) return
        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            exhaustReconnectBudget(reconnectLastError ?: "Reconnect retry budget exhausted")
            return
        }
        val sessionId = activeSessionId ?: run {
            exhaustReconnectBudget("Reconnect Session ID missing")
            return
        }
        val profile = frozenProfile ?: run {
            exhaustReconnectBudget("Reconnect frozen profile missing")
            return
        }

        reconnectAttempt += 1
        reconnectPhase = PHASE_TEARDOWN
        graceTransportHealthy = false
        engine.invalidateVideoRecoveryLiveness()
        sameSessionIdVerified = false
        frozenProfileVerified = false
        publish(StreamState.Reconnecting(reconnectAttempt, reconnectSource ?: "transport"), engine.diagnostics)
        Log.i(
            RECONNECT_TAG,
            "ATTEMPT $reconnectAttempt/$MAX_RECONNECT_ATTEMPTS sessionId=$sessionId source=$reconnectSource ${profile.summary}",
        )

        val generation = ++recoveryGeneration
        engine.prepareForReconnect {
            mainHandler.post {
                if (!isCurrentRecovery(generation)) return@post
                requestSameSessionClaim(generation, sessionId, profile)
            }
        }
    }

    private fun requestSameSessionClaim(
        generation: Long,
        sessionId: String,
        expectedProfile: ResolvedLaunchProfile,
    ) {
        if (!isCurrentRecovery(generation)) return
        reconnectPhase = PHASE_CLAIMING
        publish(StreamState.Reconnecting(reconnectAttempt, reconnectSource ?: "transport"), engine.diagnostics)
        val source = reconnectSource ?: "transport"
        transportRecoverySink.invoke(sessionId, source, reconnectAttempt) { result ->
            mainHandler.post {
                if (!isCurrentRecovery(generation)) return@post
                when (result) {
                    is StreamReconnectSessionResult.Recovered -> {
                        if (result.session.sessionId != sessionId) {
                            scheduleRetry(
                                "same-session invariant failed: expected=$sessionId actual=${result.session.sessionId}",
                            )
                            return@post
                        }
                        if (result.profile != expectedProfile || result.profile != frozenProfile) {
                            scheduleRetry("frozen ResolvedLaunchProfile changed during reconnect")
                            return@post
                        }
                        sameSessionIdVerified = true
                        frozenProfileVerified = true
                        reconnectPhase = PHASE_CONNECTING
                        publish(
                            StreamState.Reconnecting(reconnectAttempt, source),
                            engine.diagnostics,
                        )
                        Log.i(
                            RECONNECT_TAG,
                            "CLAIM_OK sameSession=true frozenProfile=true sessionId=$sessionId attempt=$reconnectAttempt",
                        )
                        engine.connect(result.session, result.profile.streamConfig)
                    }
                    is StreamReconnectSessionResult.RetryableFailure -> scheduleRetry(result.reason)
                    is StreamReconnectSessionResult.SessionEnded -> {
                        Log.i(RECONNECT_TAG, "TERMINAL sessionId=$sessionId reason=${result.reason}")
                        cancelReconnect(clearActive = true, resetAttempt = true)
                        publish(StreamState.SessionEnded, engine.diagnostics)
                    }
                }
            }
        }
    }

    private fun scheduleRetry(reason: String) {
        if (!reconnectActive) return
        reconnectLastError = reason
        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            exhaustReconnectBudget(reason)
            return
        }
        reconnectPhase = PHASE_BACKOFF
        publish(StreamState.Reconnecting(reconnectAttempt, reconnectSource ?: "transport"), engine.diagnostics)
        val delayMs = when (reconnectAttempt) {
            0 -> 0L
            1 -> 1_000L
            else -> 3_000L
        }
        val generation = recoveryGeneration
        val runnable = Runnable {
            pendingRecoveryRunnable = null
            if (!reconnectActive || recoveryGeneration != generation || reconnectPhase != PHASE_BACKOFF) return@Runnable
            beginReconnectAttempt()
        }
        pendingRecoveryRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
        Log.w(
            RECONNECT_TAG,
            "RETRY pending after=${delayMs}ms attempt=$reconnectAttempt/$MAX_RECONNECT_ATTEMPTS reason=$reason",
        )
    }

    private fun exhaustReconnectBudget(reason: String) {
        Log.e(RECONNECT_TAG, "EXHAUSTED sessionId=$activeSessionId attempts=$reconnectAttempt reason=$reason")
        cancelScheduledRecovery()
        reconnectActive = false
        reconnectPhase = PHASE_EXHAUSTED
        reconnectLastError = reason
        publish(StreamState.Failed("Reconnect failed after $reconnectAttempt attempts: $reason"), engine.diagnostics)
    }

    private fun cancelReconnect(clearActive: Boolean, resetAttempt: Boolean) {
        recoveryGeneration += 1
        cancelScheduledRecovery()
        reconnectActive = false
        reconnectPhase = PHASE_IDLE
        reconnectLastError = null
        reconnectSource = null
        sameSessionIdVerified = false
        frozenProfileVerified = false
        graceTransportHealthy = false
        engine.invalidateVideoRecoveryLiveness()
        if (resetAttempt) reconnectAttempt = 0
        if (clearActive) {
            activeSessionId = null
            frozenProfile = null
        }
    }

    private fun cancelScheduledRecovery() {
        pendingRecoveryRunnable?.let(mainHandler::removeCallbacks)
        pendingRecoveryRunnable = null
    }

    private fun isCurrentRecovery(generation: Long): Boolean =
        reconnectActive && recoveryGeneration == generation

    private fun isTransportHealthy(state: StreamState, diagnostics: StreamDiagnostics): Boolean {
        val logicalHealthy = state is StreamState.Connected || state is StreamState.FirstFrame
        val iceHealthy = diagnostics.ice.iceConnectionState == "CONNECTED" ||
            diagnostics.ice.iceConnectionState == "COMPLETED"
        val peerHealthy = diagnostics.ice.peerConnectionState == "CONNECTED"
        return logicalHealthy && iceHealthy && peerHealthy
    }

    private fun isGraceMediaHealthy(logDecision: Boolean): Boolean {
        if (!graceTransportHealthy) return false
        val requestedFps = frozenProfile?.streamConfig?.fps?.coerceAtLeast(1) ?: return false
        val liveness = engine.videoFrameLiveness(GRACE_MEDIA_WINDOW_MS)
        val minFrames = requestedFps
        val lastFrameAgeMs = liveness.lastFrameAgeMs
        val lastRenderedFrameAgeMs = liveness.lastRenderedFrameAgeMs
        val healthy = liveness.framesInWindow >= minFrames &&
            lastFrameAgeMs != null &&
            lastFrameAgeMs <= GRACE_MEDIA_MAX_LAST_FRAME_AGE_MS &&
            liveness.renderedFrameSeen
        if (logDecision || healthy) {
            Log.i(
                RECONNECT_TAG,
                "grace media gate healthy=$healthy frames=${liveness.framesInWindow}/$minFrames " +
                    "windowMs=${liveness.windowMs} lastFrameAgeMs=${lastFrameAgeMs ?: -1} " +
                    "rendered=${liveness.renderedFrameSeen} " +
                    "lastRenderedFrameAgeMs=${lastRenderedFrameAgeMs ?: -1}",
            )
        }
        return healthy
    }

    private fun completeGraceRecovery(state: StreamState, diagnostics: StreamDiagnostics) {
        Log.i(RECONNECT_TAG, "transient recovery verified with fresh video source=$reconnectSource")
        cancelReconnect(clearActive = false, resetAttempt = true)
        publish(state, diagnostics)
    }

    private fun publish(state: StreamState, engineDiagnostics: StreamDiagnostics) {
        _state.value = state
        _diagnostics.value = engineDiagnostics.copy(
            reconnect = ReconnectDiagnostics(
                active = reconnectActive,
                attempt = reconnectAttempt,
                maxAttempts = MAX_RECONNECT_ATTEMPTS,
                source = reconnectSource,
                phase = reconnectPhase,
                sessionId = activeSessionId,
                sameSessionIdVerified = sameSessionIdVerified,
                frozenProfileVerified = frozenProfileVerified,
                lastError = reconnectLastError,
            ),
        )
    }

    private companion object {
        const val TAG = "GfnStream"
        const val RECONNECT_TAG = "GfnReconnect"
        const val MAX_RECONNECT_ATTEMPTS = 3
        const val DISCONNECTED_GRACE_MS = 7_000L
        const val GRACE_MEDIA_WINDOW_MS = 2_000L
        const val GRACE_MEDIA_MAX_LAST_FRAME_AGE_MS = 1_000L

        const val PHASE_IDLE = "IDLE"
        const val PHASE_GRACE = "GRACE"
        const val PHASE_TEARDOWN = "TEARDOWN"
        const val PHASE_CLAIMING = "SAME_SESSION_CLAIM"
        const val PHASE_CONNECTING = "REBUILD_TRANSPORT"
        const val PHASE_BACKOFF = "BACKOFF"
        const val PHASE_EXHAUSTED = "EXHAUSTED"
    }
}
