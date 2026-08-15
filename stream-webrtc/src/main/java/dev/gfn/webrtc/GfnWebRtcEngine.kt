package dev.gfn.webrtc

import android.content.Context
import dev.gfn.core.model.RequestedColorMode
import dev.gfn.core.model.SessionConnectionInfo
import dev.gfn.core.model.SessionInfo
import dev.gfn.input.GfnInputHandshake
import dev.gfn.input.InputReleaseReason
import dev.gfn.signaling.GfnSdpTools
import dev.gfn.signaling.NvstSdpConfig
import dev.gfn.stream.AudioDiagnostics
import dev.gfn.stream.ControlDiagnostics
import dev.gfn.stream.IceDiagnostics
import dev.gfn.stream.InputDiagnostics
import dev.gfn.stream.SdpDiagnostics
import dev.gfn.stream.SignalingDiagnostics
import dev.gfn.stream.StreamCapabilityProfiles
import dev.gfn.stream.StreamConfig
import dev.gfn.stream.StreamDiagnostics
import dev.gfn.stream.StreamState
import dev.gfn.stream.StreamingEngine
import dev.gfn.stream.VideoDiagnostics
import dev.gfn.stream.VideoCodecPreference
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.json.JSONObject
import org.webrtc.VideoTrack

/** v6.1.0: production HEVC Main/Main10 capability negotiation; HDR activation remains disabled. */
class GfnWebRtcEngine(
    context: Context,
    private val listener: Listener,
) : StreamingEngine {
    interface Listener {
        fun onUpdated(state: StreamState, diagnostics: StreamDiagnostics)
        fun onServerSessionEnded(sessionId: String, source: String)
        fun onTransportNeedsReconcile(sessionId: String, source: String)
        fun onTransportNeedsReconnect(sessionId: String, source: String, immediate: Boolean)
    }

    private val appContext: Context = context.applicationContext
    private val factory: PeerConnectionFactory = GfnWebRtcRuntime.factory(appContext)
    private val localDecoderCodecs: Set<String> = GfnWebRtcRuntime.decoderCodecNames(appContext)
    private val localDecoderCapabilities: List<GfnVideoCodecCapabilitySnapshot> =
        GfnWebRtcRuntime.decoderCodecCapabilities(appContext)
    private val localReceiverCapabilities: List<GfnVideoCodecCapabilitySnapshot> =
        GfnWebRtcRuntime.receiverCodecCapabilities(appContext)
    private val localReceiverCodecNames: List<String> = localReceiverCapabilities
        .map { it.normalizedName }
        .distinct()
        .sorted()
    private val hevcProbeResult: GfnHevcDecoderProbeResult = GfnWebRtcRuntime.hevcDecoderProbeResult(appContext)
    private val hevcProductionCapability: GfnHevcDecoderCapability? = GfnWebRtcRuntime.hevcProductionCapability(appContext)
    private val hevcMain10ProductionCapability: GfnHevcDecoderCapability? =
        GfnWebRtcRuntime.hevcMain10ProductionCapability(appContext)
    private val hevcAdvertisementReason: String = GfnWebRtcRuntime.hevcAdvertisementReason(appContext)
    private val hevcMain10AdvertisementReason: String = GfnWebRtcRuntime.hevcMain10AdvertisementReason(appContext)
    private val lock = Any()
    private val generation = AtomicLong(0)

    @Volatile
    override var state: StreamState = StreamState.Idle
        private set

    @Volatile
    override var diagnostics: StreamDiagnostics = StreamDiagnostics()
        private set

    private var session: SessionInfo? = null
    private var config: StreamConfig = StreamConfig()
    private var signaling: GfnSignalingClient? = null
    private var peerConnection: PeerConnection? = null
    private var remoteDescriptionReady = false
    private var answerSent = false
    private val pendingRemoteIce = mutableListOf<GfnSignalingEvent.RemoteIce>()
    private val pendingLocalIce = mutableListOf<IceCandidate>()
    private var videoTrack: VideoTrack? = null
    private var videoOutput: GfnVideoSurfaceView? = null
    private val observedReceiverIds = mutableSetOf<String>()
    private val dataChannels = mutableListOf<DataChannel>()
    private var inputDataChannel: DataChannel? = null
    private var controlDataChannel: DataChannel? = null
    private var inputController: GfnKeyboardMouseInputController? = null
    private var gamepadController: GfnGamepadInputController? = null
    private var partialReliableThresholdMs = 300
    private var effectiveVideoCodec: VideoCodecPreference = VideoCodecPreference.H264
    private var videoCodecFallbackReason: String? = null
    private var serverEnded = false

    override fun connect(session: SessionInfo, config: StreamConfig) {
        val failure = validate(session, config)
        if (failure != null) {
            fail(failure)
            return
        }

        val hasExistingStream = synchronized(lock) {
            peerConnection != null || signaling != null || inputController != null || gamepadController != null
        }
        if (hasExistingStream) {
            disconnectWithReason(InputReleaseReason.SessionSwitch, emitClosed = false) {
                connect(session, config)
            }
            return
        }
        val resolvedSignalingUrl = session.signalingUrl?.takeIf { it.isNotBlank() }
            ?: run {
                fail("Claimed Session 缺少 signalingUrl。")
                return
            }
        val currentGeneration = generation.incrementAndGet()
        val requestedHevcProfile = targetHevcProfile(config)
        GfnHevcCompatLog.sessionStart(
            generation = currentGeneration,
            requestedCodec = config.codec,
            requestedColorMode = config.colorMode,
            targetProfile = requestedHevcProfile,
            decoderCapabilities = localDecoderCapabilities,
            receiverCapabilities = localReceiverCapabilities,
            probeResult = hevcProbeResult,
            mainCapability = hevcProductionCapability,
            mainAdvertisementReason = hevcAdvertisementReason,
            main10Capability = hevcMain10ProductionCapability,
            main10AdvertisementReason = hevcMain10AdvertisementReason,
        )
        val audioRoute = GfnAndroidAudioRouteProbe.detect(appContext)
        synchronized(lock) {
            this.session = session
            this.config = config
            remoteDescriptionReady = false
            answerSent = false
            pendingRemoteIce.clear()
            pendingLocalIce.clear()
            observedReceiverIds.clear()
            partialReliableThresholdMs = 300
            effectiveVideoCodec = config.codec
            videoCodecFallbackReason = null
            serverEnded = false
            controlDataChannel = null
            diagnostics = StreamDiagnostics(
                signaling = SignalingDiagnostics(
                    endpointHost = runCatching { URI(resolvedSignalingUrl).host }.getOrNull(),
                ),
                ice = IceDiagnostics(
                    serverIceEntries = session.iceServers.size,
                    effectiveIceServers = session.iceServers.size,
                    fallbackActive = false,
                ),
                audio = AudioDiagnostics(
                    requestedChannels = config.audioChannels,
                    admConfiguredOutputChannels = 2,
                    admStereoOutputEnabled = true,
                    likelyRouteMaxChannels = audioRoute.likelyMaxChannels,
                    likelyRouteSummary = audioRoute.summary,
                    nativeSurroundOutput = false,
                    outputMode = if (config.audioChannels >= 6) {
                        "SURROUND_MULTI_OPUS_ADM_2CH_PROBE"
                    } else {
                        "ADM_STEREO_2CH"
                    },
                    limitation = if (config.audioChannels >= 6) {
                        "请求 6ch 仅用于 multiopus 协商/接收探针；当前 upstream Android Java ADM 仅配置 2ch，6ch 输入最终是下混还是失败尚待真机验证。"
                    } else {
                        null
                    },
                ),
                video = VideoDiagnostics(
                    requestedCodec = config.codec.name,
                    requestedColorMode = config.colorMode.name,
                    requestedHevcProfile = requestedHevcProfile.sdpProfileId.takeIf { config.codec == VideoCodecPreference.Hevc },
                    expectedBitDepth = requestedBitDepth(config),
                    localDecoderCodecs = localDecoderCodecs.sorted(),
                    localReceiverCodecs = localReceiverCodecNames,
                    hevcProductionCapabilityReady = hevcProductionCapability != null,
                    hevcProductionDecoder = hevcProductionCapability?.codecName,
                    hevcProductionProfile = hevcProductionCapability?.profile?.sdpProfileId,
                    hevcProductionTier = hevcProductionCapability?.tier?.sdpTierFlag,
                    hevcProductionMaxLevel = hevcProductionCapability?.maxLevel?.sdpLevelId,
                    hevcMain10ProductionCapabilityReady = hevcMain10ProductionCapability != null,
                    hevcMain10ProductionDecoder = hevcMain10ProductionCapability?.codecName,
                    hevcMain10ProductionProfile = hevcMain10ProductionCapability?.profile?.sdpProfileId,
                    hevcMain10ProductionTier = hevcMain10ProductionCapability?.tier?.sdpTierFlag,
                    hevcMain10ProductionMaxLevel = hevcMain10ProductionCapability?.maxLevel?.sdpLevelId,
                    hevcMain10ProductionReason = hevcMain10AdvertisementReason,
                    hevcProductionReason = GfnWebRtcRuntime.hevcAdvertisementReason(appContext, requestedHevcProfile),
                    decoderPath = decoderPathFor(config.codec),
                ),
            )
            state = StreamState.OpeningSignaling
        }
        val keyboardMouse = createKeyboardMouseController(currentGeneration)
        val gamepad = createGamepadController(currentGeneration)
        synchronized(lock) {
            inputController = keyboardMouse
            gamepadController = gamepad
            videoOutput?.let(::installVideoOutputCallbacksLocked)
        }
        emit()

        val client = GfnSignalingClient(
            signalingUrl = resolvedSignalingUrl,
            sessionId = session.sessionId,
            resolution = "${config.width}x${config.height}",
            listener = { event -> handleSignalingEvent(event, currentGeneration) },
        )
        synchronized(lock) { signaling = client }
        try {
            client.connect()
        } catch (error: Exception) {
            fail("Signaling URL/连接初始化失败：${error.message ?: error::class.java.simpleName}")
        }
    }

    override fun disconnect() {
        disconnectWithReason(InputReleaseReason.UserDisconnect, emitClosed = true)
    }

    fun prepareForSessionEnd(onDrained: () -> Unit) {
        disconnectWithReason(InputReleaseReason.SessionEnd, emitClosed = true, onComplete = onDrained)
    }

    /**
     * v5.2.1 reconnect teardown: freeze/release/drain current input before closing the old
     * signaling/PeerConnection/DataChannels. The outer controller keeps the Session/profile snapshot
     * and will call connect() with freshly reclaimed connection information.
     */
    fun prepareForReconnect(onDrained: () -> Unit) {
        disconnectWithReason(InputReleaseReason.WebRtcDisconnect, emitClosed = false, onComplete = onDrained)
    }

    fun onActivityResumed() {
        val (keyboard, gamepad) = inputControllers()
        keyboard?.onActivityResumed()
        gamepad?.onActivityResumed()
    }

    fun onActivityPaused() {
        val (keyboard, gamepad) = inputControllers()
        keyboard?.onActivityPaused()
        gamepad?.onActivityPaused()
    }

    fun onActivityDestroy() {
        val (keyboard, gamepad) = inputControllers()
        keyboard?.onActivityDestroy()
        gamepad?.onActivityDestroy()
    }

    fun onOverlayChanged(open: Boolean) {
        val (keyboard, gamepad) = inputControllers()
        keyboard?.onOverlayChanged(open)
        gamepad?.onOverlayChanged(open)
    }

    fun onFullscreenExit() {
        val (keyboard, gamepad) = inputControllers()
        keyboard?.releaseForFullscreenExit()
        gamepad?.releaseForFullscreenExit()
    }

    fun bindVideoOutput(output: GfnVideoSurfaceView?) {
        synchronized(lock) {
            if (videoOutput === output) {
                output?.let(::installVideoOutputCallbacksLocked)
                return
            }
            videoOutput?.let { previous ->
                videoTrack?.removeSink(previous)
                previous.inputListener = null
            }
            videoOutput = output
            if (output != null) {
                installVideoOutputCallbacksLocked(output)
                videoTrack?.addSink(output)
            }
        }
    }

    /** lock must be held. Listener resolves inputController dynamically so a reconnect cannot
     * retain the old generation's controller. */
    private fun installVideoOutputCallbacksLocked(output: GfnVideoSurfaceView) {
        output.onFirstFrame = ::onFirstFrameRendered
        output.onResolutionChanged = ::onResolutionChanged
        output.inputListener = object : GfnVideoSurfaceView.InputListener {
            override fun onKey(down: Boolean, trace: GfnInputForensics.KeyTrace): Boolean =
                synchronized(lock) { inputController }?.onKey(down, trace) == true

            override fun onMouseMove(dx: Float, dy: Float) {
                synchronized(lock) { inputController }?.onMouseMove(dx, dy)
            }

            override fun onMouseButton(down: Boolean, button: Int): Boolean =
                synchronized(lock) { inputController }?.onMouseButton(down, button) == true

            override fun onMouseWheel(verticalAxis: Float) {
                synchronized(lock) { inputController }?.onMouseWheel(verticalAxis)
            }

            override fun onGamepadKey(down: Boolean, event: android.view.KeyEvent): Boolean =
                synchronized(lock) { gamepadController }?.onGamepadKey(down, event) == true

            override fun onGamepadMotion(event: android.view.MotionEvent): Boolean =
                synchronized(lock) { gamepadController }?.onGamepadMotion(event) == true

            override fun onWindowFocusChanged(focused: Boolean) {
                val (keyboard, gamepad) = inputControllers()
                keyboard?.onWindowFocusChanged(focused)
                gamepad?.onWindowFocusChanged(focused)
            }

            override fun onPointerCaptureChanged(captured: Boolean) {
                synchronized(lock) { inputController }?.onPointerCaptureChanged(captured)
            }
        }
    }

    private fun inputControllers(): Pair<GfnKeyboardMouseInputController?, GfnGamepadInputController?> =
        synchronized(lock) { inputController to gamepadController }

    private fun targetHevcProfile(config: StreamConfig = this.config): GfnHevcProfile =
        if (config.codec == VideoCodecPreference.Hevc && config.colorMode == RequestedColorMode.PreferSdr10) {
            GfnHevcProfile.Main10
        } else {
            GfnHevcProfile.Main
        }

    private fun requestedBitDepth(config: StreamConfig = this.config): Int =
        if (config.colorMode == RequestedColorMode.PreferSdr10) 10 else 8

    private fun allowHevcFallback(config: StreamConfig = this.config): Boolean =
        !(config.codec == VideoCodecPreference.Hevc && config.colorMode == RequestedColorMode.PreferSdr10)

    private fun targetProfilePayloadTypes(summary: dev.gfn.signaling.SdpSummary, profile: GfnHevcProfile): List<Int> =
        when (profile) {
            GfnHevcProfile.Main -> summary.hevcMainPayloadTypes
            GfnHevcProfile.Main10 -> summary.hevcMain10PayloadTypes
        }

    private fun matchingAnswerHevcProfilePayloadTypes(offer: String, answer: String, profile: GfnHevcProfile): List<Int> =
        GfnSdpTools.matchingAnswerHevcProfilePayloadTypes(offer, answer, profile.sdpProfileId)

    private fun notifyInputStreamConnected(connected: Boolean) {
        val (keyboard, gamepad) = inputControllers()
        keyboard?.onStreamConnected(connected)
        gamepad?.onStreamConnected(connected)
    }

    private fun validate(session: SessionInfo, config: StreamConfig): String? = when {
        !session.isReadyStatus -> "v5 只接受已 Ready/Claimed 的 Session（status=${session.status}）。"
        session.signalingUrl.isNullOrBlank() -> "Claimed Session 缺少 signalingUrl。"
        config.colorMode == RequestedColorMode.PreferSdr10 && config.codec != VideoCodecPreference.Hevc ->
            "v6.1.0 SDR10 必须与 HEVC/Main10 一起请求，不能与 ${config.codec} 配对。"
        config.colorMode == RequestedColorMode.PreferHdr10 || config.colorMode == RequestedColorMode.Automatic ->
            "v6.1.0 只开放 CompatibilitySdr / PreferSdr10；HDR Session request 仍关闭。"
        else -> StreamCapabilityProfiles.V610_ANDROID_WEBRTC.rejectionReason(config)
    }

    fun unbindVideoOutput(output: GfnVideoSurfaceView) {
        synchronized(lock) {
            if (videoOutput !== output) return
            videoTrack?.removeSink(output)
            output.inputListener = null
            videoOutput = null
        }
    }

    private fun handleSignalingEvent(event: GfnSignalingEvent, eventGeneration: Long) {
        if (generation.get() != eventGeneration) return
        when (event) {
            GfnSignalingEvent.Connected -> {
                updateSignaling { it.copy(websocketConnected = true) }
                setState(StreamState.AwaitingOffer)
            }
            is GfnSignalingEvent.Trace -> {
                updateSignaling { old ->
                    when (event.direction) {
                        GfnSignalingEvent.Direction.RX -> old.copy(
                            rxCount = old.rxCount + 1,
                            lastRxType = event.type,
                            lastRxEpochMillis = event.epochMillis,
                        )
                        GfnSignalingEvent.Direction.TX -> old.copy(
                            txCount = old.txCount + 1,
                            lastTxType = event.type,
                            lastTxEpochMillis = event.epochMillis,
                        )
                    }
                }
            }
            is GfnSignalingEvent.Offer -> handleOffer(event.sdp, eventGeneration)
            is GfnSignalingEvent.RemoteIce -> handleRemoteIce(event)
            is GfnSignalingEvent.Closed -> {
                updateSignaling {
                    it.copy(
                        websocketConnected = false,
                        closeCode = event.code,
                        closeReason = event.reason.takeIf(String::isNotBlank),
                    )
                }
                // Answer 已发出后 GFN 关闭 signaling 并不代表媒体失败；由 ICE/PC 状态决定。
                if (!answerSent) fail("Signaling 在 SDP 完成前关闭：${event.code} ${event.reason}")
            }
            is GfnSignalingEvent.Failure -> fail(event.message)
        }
    }

    private fun handleOffer(offerSdp: String, eventGeneration: Long) {
        if (generation.get() != eventGeneration) return
        val currentSession = synchronized(lock) { session } ?: return
        val offerSummary = GfnSdpTools.summarize(offerSdp, isOffer = true)
        GfnHevcCompatLog.sdp(eventGeneration, "OFFER", offerSdp)
        val requestedAudioName = if (config.audioChannels >= 6) "multiopus" else "opus"
        val requestedOfferAudio = GfnSdpTools.firstAudioCodec(offerSdp, requestedAudioName)
        val multiopusOffer = GfnSdpTools.firstAudioCodec(offerSdp, "multiopus")
        val surroundOfferPresent = multiopusOffer?.channels == 6
        updateAudio { current ->
            current.copy(
                offerCodec = requestedOfferAudio?.name ?: GfnSdpTools.firstAudioCodec(offerSdp)?.name,
                offerChannels = requestedOfferAudio?.channels ?: GfnSdpTools.firstAudioCodec(offerSdp)?.channels,
                surroundOfferPresent = surroundOfferPresent,
            )
        }
        updateDiagnostics {
            it.copy(
                offer = SdpDiagnostics(
                    offerPresent = true,
                    videoCodecs = offerSummary.videoCodecs,
                    h264PayloadTypes = offerSummary.h264PayloadTypes,
                    hevcPayloadTypes = offerSummary.hevcPayloadTypes,
                    hevcMainPayloadTypes = offerSummary.hevcMainPayloadTypes,
                    hevcMain10PayloadTypes = offerSummary.hevcMain10PayloadTypes,
                    iceUfragPresent = offerSummary.iceUfragPresent,
                    icePasswordPresent = offerSummary.icePasswordPresent,
                    dtlsFingerprintPresent = offerSummary.dtlsFingerprintPresent,
                ),
            )
        }
        val offerVideoCodecs = GfnSdpTools.firstVideoCodecDetails(offerSdp)
        val targetProfile = targetHevcProfile(config)
        val targetCapability = GfnWebRtcRuntime.hevcProductionCapability(appContext, targetProfile)
        val hevcStreamSupport = GfnWebRtcRuntime.hevcStreamSupport(
            context = appContext,
            profile = targetProfile,
            width = config.width,
            height = config.height,
            fps = config.fps,
            maxBitrateKbps = config.maxBitrateKbps,
        )
        val hevcCompatibility = GfnHevcProductionCompatibilityMatcher.evaluate(
            remoteCodecs = offerVideoCodecs,
            targetProfile = targetProfile,
            localCapability = targetCapability,
            streamSupport = hevcStreamSupport,
        )
        GfnHevcCompatLog.offerCompatibility(eventGeneration, hevcCompatibility)
        updateDiagnostics { current ->
            current.copy(
                offer = current.offer.copy(
                    hevcTargetMatchedPayloadTypes = hevcCompatibility.compatiblePayloadTypes,
                ),
            )
        }
        updateVideo { current ->
            current.copy(
                hevcProductionStreamSafe = hevcStreamSupport.supported,
                hevcProductionReason = hevcCompatibility.reason,
                hevcCompatibleOfferPayloadTypes = hevcCompatibility.compatiblePayloadTypes,
            )
        }
        val selectedVideoCodec = selectVideoCodecForOffer(offerSummary, hevcCompatibility) ?: return
        synchronized(lock) { effectiveVideoCodec = selectedVideoCodec }
        if (config.audioChannels >= 6 && !surroundOfferPresent) {
            fail("已请求 5.1/6ch，但 GFN Offer 未包含 multiopus/6；停止本次实验性 surround 连接。")
            return
        }
        synchronized(lock) {
            if (peerConnection != null) {
                fail("v6.1.0 收到重复 Offer；当前版本不做 renegotiation。")
                return
            }
        }
        setState(StreamState.NegotiatingSdp)

        val pc = createPeerConnection(currentSession, eventGeneration) ?: return
        synchronized(lock) { peerConnection = pc }
        partialReliableThresholdMs = GfnSdpTools.partialReliableThresholdMs(offerSdp)
        createExpectedDataChannels(pc, partialReliableThresholdMs, eventGeneration)

        val mediaIp = resolveMediaIp(currentSession)
        // v6.1.0 preserves the original server Main/Main10 codec attributes. The existing
        // connection-address correction is transport-only and never modifies H265 fmtp/profile/tier/level.
        val fixedOffer = mediaIp?.let { GfnSdpTools.rewriteOfferConnectionAddresses(offerSdp, it) } ?: offerSdp
        pc.setRemoteDescription(
            setObserver(
                onSuccess = {
                    if (generation.get() != eventGeneration) return@setObserver
                    synchronized(lock) { remoteDescriptionReady = true }
                    flushRemoteIce()
                    applyPreAnswerVideoCodecPreference(pc, fixedOffer, eventGeneration)
                    createAnswer(pc, fixedOffer, eventGeneration)
                },
                onFailure = { fail("setRemoteDescription 失败：$it") },
            ),
            SessionDescription(SessionDescription.Type.OFFER, fixedOffer),
        )
    }

    private fun createPeerConnection(session: SessionInfo, eventGeneration: Long): PeerConnection? {
        val iceServers = session.iceServers.mapNotNull { raw ->
            if (raw.urls.isEmpty()) return@mapNotNull null
            PeerConnection.IceServer.builder(raw.urls)
                .setUsername(raw.username.orEmpty())
                .setPassword(raw.credential.orEmpty())
                .createIceServer()
        }
        val rtc = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            audioJitterBufferFastAccelerate = true
            audioJitterBufferMaxPackets = 50
        }
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState) {
                ifCurrent(eventGeneration) { updateIce { it.copy(signalingState = newState.name) } }
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                ifCurrent(eventGeneration) {
                    updateIce { it.copy(iceConnectionState = newState.name) }
                    when (newState) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> {
                            notifyInputStreamConnected(true)
                            setState(StreamState.Connected)
                        }
                        PeerConnection.IceConnectionState.CHECKING -> setState(StreamState.IceChecking)
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            notifyInputStreamConnected(false)
                            requestSessionReconcile(eventGeneration, "ice.DISCONNECTED")
                            requestTransportReconnect(eventGeneration, "ice.DISCONNECTED", immediate = false)
                        }
                        PeerConnection.IceConnectionState.CLOSED -> notifyInputStreamConnected(false)
                        PeerConnection.IceConnectionState.FAILED -> {
                            notifyInputStreamConnected(false)
                            requestSessionReconcile(eventGeneration, "ice.FAILED")
                            requestTransportReconnect(eventGeneration, "ice.FAILED", immediate = true)
                        }
                        else -> Unit
                    }
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                ifCurrent(eventGeneration) {
                    updateIce { it.copy(peerConnectionState = newState.name) }
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            notifyInputStreamConnected(true)
                            setState(StreamState.Connected)
                        }
                        PeerConnection.PeerConnectionState.DISCONNECTED -> {
                            notifyInputStreamConnected(false)
                            requestSessionReconcile(eventGeneration, "pc.DISCONNECTED")
                            requestTransportReconnect(eventGeneration, "pc.DISCONNECTED", immediate = false)
                        }
                        PeerConnection.PeerConnectionState.CLOSED -> notifyInputStreamConnected(false)
                        PeerConnection.PeerConnectionState.FAILED -> {
                            notifyInputStreamConnected(false)
                            requestSessionReconcile(eventGeneration, "pc.FAILED")
                            requestTransportReconnect(eventGeneration, "pc.FAILED", immediate = true)
                        }
                        else -> Unit
                    }
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
                ifCurrent(eventGeneration) { updateIce { it.copy(iceGatheringState = newState.name) } }
            }

            override fun onIceCandidate(candidate: IceCandidate) {
                if (generation.get() != eventGeneration) return
                updateIce { it.copy(localCandidateCount = it.localCandidateCount + 1) }
                val sendNow = synchronized(lock) {
                    if (answerSent) true else {
                        pendingLocalIce += candidate
                        false
                    }
                }
                if (sendNow) sendLocalIce(candidate)
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onDataChannel(dataChannel: DataChannel) {
                ifCurrent(eventGeneration) { runCatching { registerServerDataChannel(dataChannel, eventGeneration) } }
            }
            override fun onRenegotiationNeeded() = Unit

            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
                ifCurrent(eventGeneration) { runCatching { attachReceiver(receiver) } }
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                ifCurrent(eventGeneration) { runCatching { attachReceiver(transceiver.receiver) } }
            }
        }
        return factory.createPeerConnection(rtc, observer).also {
            if (it == null) fail("PeerConnectionFactory.createPeerConnection 返回 null。")
        }
    }

    private fun createExpectedDataChannels(pc: PeerConnection, partialThresholdMs: Int, eventGeneration: Long) {
        pc.createDataChannel(
            "input_channel_v1",
            DataChannel.Init().apply { ordered = true },
        )?.let { channel ->
            dataChannels += channel
            GfnInputForensics.logDataChannel(
                connectionGeneration = eventGeneration,
                state = runCatching { channel.state().name }.getOrDefault("UNKNOWN"),
                protocolReady = false,
                note = "created configuredOrdered=true configuredNegotiated=false",
            )
            registerInputDataChannel(channel, eventGeneration)
        }
        pc.createDataChannel(
            "input_channel_partially_reliable",
            DataChannel.Init().apply {
                ordered = false
                maxRetransmitTimeMs = partialThresholdMs
            },
        )?.let(dataChannels::add)
        pc.createDataChannel(
            "stats_channel",
            DataChannel.Init().apply {
                ordered = false
                maxRetransmits = 0
            },
        )?.let(dataChannels::add)
    }

    private fun createKeyboardMouseController(eventGeneration: Long): GfnKeyboardMouseInputController =
        GfnKeyboardMouseInputController(
            connectionGeneration = eventGeneration,
            packetSink = object : GfnKeyboardMouseInputController.PacketSink {
                override fun sendBinary(packet: ByteArray): Boolean {
                    val channel = synchronized(lock) { inputDataChannel }
                    if (channel == null || channel.state() != DataChannel.State.OPEN) return false
                    return channel.send(DataChannel.Buffer(ByteBuffer.wrap(packet), true))
                }

                override fun sendKeyboard(
                    tx: GfnInputForensics.KeyboardTx,
                    packet: ByteArray,
                ): Boolean {
                    val channel = synchronized(lock) { inputDataChannel }
                    val finalBuffer = ByteBuffer.wrap(packet)
                    val channelState = runCatching { channel?.state()?.name ?: "NULL" }.getOrDefault("UNKNOWN")
                    val bufferedBefore = runCatching { channel?.bufferedAmount() ?: 0L }.getOrDefault(0L)
                    val logPrefix = GfnInputForensics.logKeyboardTxBeforeSend(
                        tx = tx,
                        buffer = finalBuffer,
                        channelState = channelState,
                        bufferedAmount = bufferedBefore,
                        binary = true,
                    )
                    if (channel == null || channelState != DataChannel.State.OPEN.name) {
                        GfnInputForensics.logKeyboardTxAfterSend(logPrefix, false, bufferedBefore)
                        return false
                    }
                    val accepted = channel.send(DataChannel.Buffer(finalBuffer, true))
                    val bufferedAfter = runCatching { channel.bufferedAmount() }.getOrDefault(bufferedBefore)
                    GfnInputForensics.logKeyboardTxAfterSend(logPrefix, accepted, bufferedAfter)
                    return accepted
                }

                override fun isOpen(): Boolean =
                    synchronized(lock) { inputDataChannel }?.state() == DataChannel.State.OPEN

                override fun bufferedAmount(): Long =
                    runCatching { synchronized(lock) { inputDataChannel }?.bufferedAmount() ?: 0L }.getOrDefault(0L)
            },
            onDiagnostics = { input ->
                ifCurrent(eventGeneration) {
                    updateDiagnostics { it.copy(input = input) }
                }
            },
        )

    private fun createGamepadController(eventGeneration: Long): GfnGamepadInputController =
        GfnGamepadInputController(
            context = appContext,
            connectionGeneration = eventGeneration,
            packetSink = object : GfnGamepadInputController.PacketSink {
                override fun sendBinary(packet: ByteArray): Boolean {
                    val channel = synchronized(lock) { inputDataChannel }
                    if (channel == null || channel.state() != DataChannel.State.OPEN) return false
                    return channel.send(DataChannel.Buffer(ByteBuffer.wrap(packet), true))
                }

                override fun isOpen(): Boolean =
                    synchronized(lock) { inputDataChannel }?.state() == DataChannel.State.OPEN

                override fun bufferedAmount(): Long =
                    runCatching { synchronized(lock) { inputDataChannel }?.bufferedAmount() ?: 0L }.getOrDefault(0L)
            },
            onDiagnostics = { gamepad ->
                ifCurrent(eventGeneration) {
                    updateDiagnostics { it.copy(gamepad = gamepad) }
                }
            },
        )

    private fun registerInputDataChannel(channel: DataChannel, eventGeneration: Long) {
        synchronized(lock) { inputDataChannel = channel }
        channel.registerObserver(
            object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) = Unit

                override fun onStateChange() {
                    if (generation.get() != eventGeneration) return
                    // Native -> Java callback: never allow a Java/Kotlin exception to escape back into WebRTC JNI.
                    runCatching {
                        val channelState = channel.state()
                        val open = channelState == DataChannel.State.OPEN
                        GfnInputForensics.logDataChannel(
                            connectionGeneration = eventGeneration,
                            state = channelState.name,
                            protocolReady = diagnostics.input.protocolReady,
                            note = "observer.onStateChange",
                        )
                        val (keyboard, gamepad) = inputControllers()
                        keyboard?.onDataChannelState(open)
                        gamepad?.onDataChannelState(open)
                        if (channelState == DataChannel.State.CLOSED) {
                            requestSessionReconcile(eventGeneration, "input_channel.CLOSED")
                            requestTransportReconnect(eventGeneration, "input_channel.CLOSED", immediate = true)
                        }
                    }
                }

                override fun onMessage(buffer: DataChannel.Buffer) {
                    if (generation.get() != eventGeneration) return
                    // CloudNow parses the handshake bytes regardless of DataChannel's text/binary flag.
                    // Copy inside the callback because WebRTC owns buffer.data after this function returns.
                    runCatching {
                        val source = buffer.data.slice()
                        val bytes = ByteArray(source.remaining())
                        source.get(bytes)
                        val version = GfnInputHandshake.parseProtocolVersion(bytes)
                        // Input Forensics: raw callback bytes must be recorded even when parsing fails.
                        GfnInputForensics.logHandshake(eventGeneration, bytes, version)
                        version ?: return@runCatching
                        if (version < 2) return@runCatching
                        val (keyboard, gamepad) = inputControllers()
                        // 防御回调时序：即使 OPEN state callback 晚于第一条 handshake message，
                        // 也以 DataChannel 当前真实 state 先同步 transport gate，再处理 protocolReady。
                        val open = channel.state() == DataChannel.State.OPEN
                        keyboard?.onDataChannelState(open)
                        gamepad?.onDataChannelState(open)
                        keyboard?.onProtocolReady(version)
                        gamepad?.onProtocolReady(version)
                    }
                }
            },
        )
        val initialState = channel.state()
        GfnInputForensics.logDataChannel(
            connectionGeneration = eventGeneration,
            state = initialState.name,
            protocolReady = diagnostics.input.protocolReady,
            note = "observer.registered",
        )
        val (keyboard, gamepad) = inputControllers()
        val open = initialState == DataChannel.State.OPEN
        keyboard?.onDataChannelState(open)
        gamepad?.onDataChannelState(open)
    }

    private fun registerServerDataChannel(channel: DataChannel, eventGeneration: Long) {
        val label = runCatching { channel.label() }.getOrNull() ?: return
        if (label != "control_channel") return
        synchronized(lock) {
            if (generation.get() != eventGeneration) return
            if (controlDataChannel === channel) return
            controlDataChannel = channel
            if (!dataChannels.contains(channel)) dataChannels += channel
        }
        updateControl { it.copy(controlChannelPresent = true, controlChannelState = runCatching { channel.state().name }.getOrDefault("UNKNOWN")) }
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit

            override fun onStateChange() {
                if (generation.get() != eventGeneration) return
                runCatching {
                    if (!isCurrentControlChannel(channel)) return@runCatching
                    val channelState = channel.state().name
                    updateControl { it.copy(controlChannelState = channelState) }
                    if (channelState == DataChannel.State.CLOSED.name) {
                        requestSessionReconcile(eventGeneration, "control_channel.CLOSED")
                    }
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                if (generation.get() != eventGeneration) return
                runCatching {
                    if (!isCurrentControlChannel(channel)) return@runCatching
                    val source = buffer.data.slice()
                    val bytes = ByteArray(source.remaining())
                    source.get(bytes)
                    val json = JSONObject(bytes.toString(Charsets.UTF_8))
                    val hasExitMessage = json.has("exitMessage")
                    updateControl {
                        it.copy(
                            rxCount = it.rxCount + 1,
                            lastEvent = if (hasExitMessage) "exitMessage" else "json",
                        )
                    }
                    if (hasExitMessage) handleServerSessionEnded(channel, eventGeneration)
                }
            }
        })
    }

    private fun isCurrentControlChannel(channel: DataChannel): Boolean =
        synchronized(lock) { controlDataChannel === channel }

    private fun handleServerSessionEnded(channel: DataChannel, eventGeneration: Long) {
        val sessionId = synchronized(lock) {
            if (generation.get() != eventGeneration || controlDataChannel !== channel || serverEnded) return
            serverEnded = true
            session?.sessionId
        } ?: return
        updateControl { it.copy(exitMessageSeen = true, lastEvent = "exitMessage") }
        disconnectWithReason(InputReleaseReason.SessionEnd, emitClosed = false) {
            state = StreamState.SessionEnded
            emit()
            listener.onServerSessionEnded(sessionId, "control_channel.exitMessage")
        }
    }

    private fun requestSessionReconcile(eventGeneration: Long, source: String) {
        val sessionId = synchronized(lock) {
            if (generation.get() != eventGeneration || serverEnded) return
            session?.sessionId
        } ?: return
        listener.onTransportNeedsReconcile(sessionId, source)
    }

    private fun requestTransportReconnect(eventGeneration: Long, source: String, immediate: Boolean) {
        val sessionId = synchronized(lock) {
            if (generation.get() != eventGeneration || serverEnded) return
            session?.sessionId
        } ?: return
        listener.onTransportNeedsReconnect(sessionId, source, immediate)
    }

    private fun decoderPathFor(codec: VideoCodecPreference): String = when (codec) {
        VideoCodecPreference.H264 ->
            "libwebrtc DefaultVideoDecoderFactory -> H264（具体硬件/软件 decoder 待真机确认）"
        VideoCodecPreference.Hevc -> {
            val profile = targetHevcProfile()
            GfnWebRtcRuntime.hevcProductionCapability(appContext, profile)?.let { capability ->
                "GfnHevcAwareVideoDecoderFactory -> ${capability.codecName} " +
                    "(bound H265 ${profile.label}/High level ${capability.maxLevel.label})"
            } ?: "GfnHevcAwareVideoDecoderFactory -> H265 ${profile.label} unavailable"
        }
        VideoCodecPreference.Av1 ->
            "AV1（v6.1.0 未启用）"
    }

    private fun selectVideoCodecForOffer(
        offerSummary: dev.gfn.signaling.SdpSummary,
        hevcCompatibility: GfnHevcOfferCompatibility,
    ): VideoCodecPreference? {
        val decision = GfnVideoCodecNegotiationPolicy.selectForOffer(
            requested = config.codec,
            h264Available = offerSummary.h264PayloadTypes.isNotEmpty(),
            hevcCompatibleAvailable = hevcCompatibility.compatible,
            hevcIncompatibilityReason = hevcCompatibility.reason,
            allowHevcFallback = allowHevcFallback(),
            hevcProfileLabel = hevcCompatibility.targetProfile.label,
        ).getOrElse { error ->
            fail(error.message ?: "无法选择视频 codec。")
            return null
        }
        videoCodecFallbackReason = decision.fallbackReason
        GfnHevcCompatLog.decision(
            generation = generation.get(),
            stage = "OFFER",
            requested = config.codec,
            effective = decision.codec,
            fallbackReason = decision.fallbackReason,
            targetProfile = targetHevcProfile(),
        )
        updateVideo {
            it.copy(
                negotiatedCodec = decision.codec.name,
                localDecoderCodecs = localDecoderCodecs.sorted(),
                localReceiverCodecs = localReceiverCodecNames,
                codecFallbackUsed = decision.fallbackReason != null,
                codecFallbackReason = decision.fallbackReason,
                decoderPath = decoderPathFor(decision.codec),
            )
        }
        return decision.codec
    }

    /**
     * v6.0.4 production preference ordering. It runs after setRemoteDescription materializes the
     * Unified Plan receive transceiver and before createAnswer(). Only local H265 capabilities that
     * match an original remote Main/High/SRST candidate are included ahead of H264.
     */
    private fun applyPreAnswerVideoCodecPreference(
        pc: PeerConnection,
        offerSdp: String,
        eventGeneration: Long,
    ) {
        if (generation.get() != eventGeneration) return
        val selected = synchronized(lock) { effectiveVideoCodec }
        if (selected != VideoCodecPreference.Hevc) {
            GfnHevcCompatLog.preferenceApply(
                generation = eventGeneration,
                attempted = false,
                applied = false,
                transceiverMid = null,
                reason = "effectiveCodec=${selected.name}; HEVC preference not required",
            )
            updateVideo {
                it.copy(
                    preAnswerCodecPreferenceAttempted = false,
                    preAnswerCodecPreferenceApplied = false,
                    preAnswerCodecPreferenceError = null,
                )
            }
            return
        }

        val liveCapabilities = runCatching {
            GfnWebRtcRuntime.liveVideoReceiverCodecCapabilities(appContext)
        }.getOrElse { error ->
            val reason = "receiver capability query failed: ${error.message ?: error.javaClass.simpleName}"
            GfnHevcCompatLog.preferenceApply(
                generation = eventGeneration,
                attempted = true,
                applied = false,
                transceiverMid = null,
                reason = reason,
            )
            updateVideo {
                it.copy(
                    preAnswerCodecPreferenceAttempted = true,
                    preAnswerCodecPreferenceApplied = false,
                    preAnswerCodecPreferenceError = reason,
                )
            }
            return
        }
        val targetProfile = targetHevcProfile()
        val plan = GfnHevcCodecPreferencePlanner.build(
            capabilities = liveCapabilities,
            remoteCodecs = GfnSdpTools.firstVideoCodecDetails(offerSdp),
            targetProfile = targetProfile,
        )
        GfnHevcCompatLog.preferencePlan(eventGeneration, plan)
        if (!plan.hasHevcCandidate || plan.orderedCapabilities.isEmpty()) {
            val reason = "receiver capability list has no H265 ${targetProfile.label}/High candidate compatible with original Offer"
            GfnHevcCompatLog.preferenceApply(
                generation = eventGeneration,
                attempted = true,
                applied = false,
                transceiverMid = null,
                reason = reason,
            )
            updateVideo {
                it.copy(
                    preAnswerCodecPreferenceAttempted = true,
                    preAnswerCodecPreferenceApplied = false,
                    preAnswerCodecPreferenceError = reason,
                )
            }
            return
        }

        val transceiver = runCatching {
            pc.transceivers.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }
        }.getOrElse { error ->
            val reason = "video transceiver query failed: ${error.message ?: error.javaClass.simpleName}"
            GfnHevcCompatLog.preferenceApply(
                generation = eventGeneration,
                attempted = true,
                applied = false,
                transceiverMid = null,
                reason = reason,
            )
            updateVideo {
                it.copy(
                    preAnswerCodecPreferenceAttempted = true,
                    preAnswerCodecPreferenceApplied = false,
                    preAnswerCodecPreferenceError = reason,
                )
            }
            return
        }
        if (transceiver == null) {
            val reason = "no video transceiver after setRemoteDescription"
            GfnHevcCompatLog.preferenceApply(
                generation = eventGeneration,
                attempted = true,
                applied = false,
                transceiverMid = null,
                reason = reason,
            )
            updateVideo {
                it.copy(
                    preAnswerCodecPreferenceAttempted = true,
                    preAnswerCodecPreferenceApplied = false,
                    preAnswerCodecPreferenceError = reason,
                )
            }
            return
        }

        val result = runCatching { transceiver.setCodecPreferences(plan.orderedCapabilities) }
        val failure = result.exceptionOrNull()
        val rtcError = result.getOrNull()
        val errorText = when {
            failure != null -> failure.message ?: failure.javaClass.simpleName
            rtcError == null -> "setCodecPreferences returned null"
            rtcError.isError -> rtcError.error()?.message ?: "setCodecPreferences rejected the list"
            else -> null
        }
        val applied = errorText == null
        GfnHevcCompatLog.preferenceApply(
            generation = eventGeneration,
            attempted = true,
            applied = applied,
            transceiverMid = runCatching { transceiver.mid }.getOrNull(),
            reason = errorText ?: "compatible H265 ${targetProfile.label}/High -> H264 -> auxiliary",
        )
        updateVideo {
            it.copy(
                preAnswerCodecPreferenceAttempted = true,
                preAnswerCodecPreferenceApplied = applied,
                preAnswerCodecPreferenceError = errorText,
            )
        }
    }

    private fun selectVideoCodecInAnswer(rawAnswer: String, hevcTargetMatchedPayloadTypes: List<Int>): String? {
        val selected = synchronized(lock) { effectiveVideoCodec }
        val targetProfile = targetHevcProfile()
        val hevcCandidate = if (selected == VideoCodecPreference.Hevc) {
            GfnSdpTools.preferVideoCodecInAnswer(
                rawAnswer,
                codec = "H265",
                allowedPrimaryPayloadTypes = hevcTargetMatchedPayloadTypes.toSet(),
            )
        } else {
            rawAnswer
        }
        val h264Candidate = GfnSdpTools.preferVideoCodecInAnswer(rawAnswer, codec = "H264")
        val h264Summary = GfnSdpTools.summarize(h264Candidate, isOffer = false)
        val decision = GfnVideoCodecNegotiationPolicy.selectAfterAnswer(
            selected = selected,
            h264Available = h264Summary.h264PayloadTypes.isNotEmpty(),
            hevcMainAvailable = hevcTargetMatchedPayloadTypes.isNotEmpty(),
            allowHevcFallback = allowHevcFallback(),
            hevcProfileLabel = targetProfile.label,
        ).getOrElse { error ->
            fail(error.message ?: "Answer 未形成可用视频 codec 交集。")
            return null
        }
        if (decision.codec != selected) synchronized(lock) { effectiveVideoCodec = decision.codec }
        if (decision.fallbackReason != null) videoCodecFallbackReason = decision.fallbackReason
        GfnHevcCompatLog.decision(
            generation = generation.get(),
            stage = "RAW_ANSWER",
            requested = config.codec,
            effective = decision.codec,
            fallbackReason = videoCodecFallbackReason,
            targetProfile = targetProfile,
        )
        updateVideo {
            it.copy(
                negotiatedCodec = decision.codec.name,
                negotiatedHevcProfile = targetProfile.sdpProfileId.takeIf { decision.codec == VideoCodecPreference.Hevc },
                localDecoderCodecs = localDecoderCodecs.sorted(),
                localReceiverCodecs = localReceiverCodecNames,
                codecFallbackUsed = videoCodecFallbackReason != null,
                codecFallbackReason = videoCodecFallbackReason,
                decoderPath = decoderPathFor(decision.codec),
            )
        }
        return if (decision.codec == VideoCodecPreference.Hevc) hevcCandidate else h264Candidate
    }

    private fun createAnswer(pc: PeerConnection, offerSdp: String, eventGeneration: Long) {
        val offerSummary = GfnSdpTools.summarize(offerSdp, isOffer = true)
        val targetProfile = targetHevcProfile()
        pc.createAnswer(
            object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) {
                    if (generation.get() != eventGeneration) return
                    val rawAnswer = description.description
                    GfnHevcCompatLog.sdp(eventGeneration, "RAW_ANSWER", rawAnswer)
                    val rawAnswerSummary = GfnSdpTools.summarize(rawAnswer, isOffer = false)
                    val rawAnswerHevcTargetMatched = matchingAnswerHevcProfilePayloadTypes(offerSdp, rawAnswer, targetProfile)
                    GfnHevcCompatLog.answerHevcProfileLineage(
                        generation = eventGeneration,
                        stage = "RAW_ANSWER",
                        targetProfile = targetProfile,
                        offerProfilePayloadTypes = targetProfilePayloadTypes(offerSummary, targetProfile),
                        answerHevcPayloadTypes = rawAnswerSummary.hevcPayloadTypes,
                        matchedPayloadTypes = rawAnswerHevcTargetMatched,
                    )
                    updateDiagnostics {
                        it.copy(
                            rawAnswer = SdpDiagnostics(
                                answerPresent = true,
                                videoCodecs = rawAnswerSummary.videoCodecs,
                                h264PayloadTypes = rawAnswerSummary.h264PayloadTypes,
                                hevcPayloadTypes = rawAnswerSummary.hevcPayloadTypes,
                                hevcMainPayloadTypes = rawAnswerSummary.hevcMainPayloadTypes,
                                hevcMain10PayloadTypes = rawAnswerSummary.hevcMain10PayloadTypes,
                                hevcMainMatchedPayloadTypes = if (targetProfile == GfnHevcProfile.Main) rawAnswerHevcTargetMatched else emptyList(),
                                hevcTargetMatchedPayloadTypes = rawAnswerHevcTargetMatched,
                                iceUfragPresent = rawAnswerSummary.iceUfragPresent,
                                icePasswordPresent = rawAnswerSummary.icePasswordPresent,
                                dtlsFingerprintPresent = rawAnswerSummary.dtlsFingerprintPresent,
                            ),
                        )
                    }
                    val videoAnswer = selectVideoCodecInAnswer(rawAnswer, rawAnswerHevcTargetMatched) ?: return
                    val audioMunge = GfnSdpTools.mungeAudioAnswer(
                        answer = videoAnswer,
                        offer = offerSdp,
                        requestedChannels = config.audioChannels,
                    )
                    if (config.audioChannels >= 6 && !audioMunge.surroundAccepted) {
                        updateAudio { current ->
                            current.copy(
                                answerCodec = audioMunge.selectedCodec?.name,
                                answerChannels = audioMunge.selectedCodec?.channels,
                                opusStereoEnabled = audioMunge.opusStereoEnabled,
                                surroundNegotiationAccepted = false,
                                outputMode = audioMunge.mode,
                                limitation = audioMunge.limitation,
                            )
                        }
                        fail(audioMunge.limitation ?: "无法在 Answer 中接受 GFN multiopus 6ch。")
                        return
                    }
                    val audioKbps = if (config.audioChannels >= 6) 256 else 128
                    val bounded = GfnSdpTools.injectBandwidth(
                        audioMunge.sdp,
                        config.maxBitrateKbps,
                        audioKbps = audioKbps,
                    )
                    GfnHevcCompatLog.sdp(eventGeneration, "FINAL_ANSWER", bounded)
                    val answerSummary = GfnSdpTools.summarize(bounded, isOffer = false)
                    val finalAnswerHevcTargetMatched = matchingAnswerHevcProfilePayloadTypes(offerSdp, bounded, targetProfile)
                    GfnHevcCompatLog.answerHevcProfileLineage(
                        generation = eventGeneration,
                        stage = "FINAL_ANSWER",
                        targetProfile = targetProfile,
                        offerProfilePayloadTypes = targetProfilePayloadTypes(offerSummary, targetProfile),
                        answerHevcPayloadTypes = answerSummary.hevcPayloadTypes,
                        matchedPayloadTypes = finalAnswerHevcTargetMatched,
                    )
                    val answerAudio = GfnSdpTools.firstAudioCodec(
                        bounded,
                        if (config.audioChannels >= 6) "multiopus" else "opus",
                    ) ?: GfnSdpTools.firstAudioCodec(bounded)
                    updateAudio { current ->
                        current.copy(
                            answerCodec = answerAudio?.name,
                            answerChannels = answerAudio?.channels,
                            opusStereoEnabled = audioMunge.opusStereoEnabled,
                            surroundNegotiationAccepted = audioMunge.surroundAccepted,
                            nativeSurroundOutput = false,
                            outputMode = audioMunge.mode,
                            limitation = audioMunge.limitation ?: current.limitation,
                        )
                    }
                    val finalCodec = synchronized(lock) { effectiveVideoCodec }
                    val finalCodecAccepted = when (finalCodec) {
                        VideoCodecPreference.H264 -> answerSummary.h264PayloadTypes.isNotEmpty()
                        VideoCodecPreference.Hevc -> finalAnswerHevcTargetMatched.isNotEmpty()
                        VideoCodecPreference.Av1 -> false
                    }
                    if (!finalCodecAccepted) {
                        fail("生成的 Answer 未保留 ${finalCodec.name} 可用 payload；停止连接。")
                        return
                    }
                    GfnHevcCompatLog.decision(
                        generation = eventGeneration,
                        stage = "FINAL_ANSWER",
                        requested = config.codec,
                        effective = finalCodec,
                        fallbackReason = videoCodecFallbackReason,
                        targetProfile = targetProfile,
                    )
                    updateVideo { current ->
                        current.copy(
                            negotiatedCodec = finalCodec.name,
                            negotiatedHevcProfile = targetProfile.sdpProfileId.takeIf { finalCodec == VideoCodecPreference.Hevc },
                            localDecoderCodecs = localDecoderCodecs.sorted(),
                            localReceiverCodecs = localReceiverCodecNames,
                            codecFallbackUsed = videoCodecFallbackReason != null,
                            codecFallbackReason = videoCodecFallbackReason,
                            decoderPath = decoderPathFor(finalCodec),
                        )
                    }
                    updateDiagnostics {
                        it.copy(
                            answer = SdpDiagnostics(
                                answerPresent = true,
                                videoCodecs = answerSummary.videoCodecs,
                                h264PayloadTypes = answerSummary.h264PayloadTypes,
                                hevcPayloadTypes = answerSummary.hevcPayloadTypes,
                                hevcMainPayloadTypes = answerSummary.hevcMainPayloadTypes,
                                hevcMain10PayloadTypes = answerSummary.hevcMain10PayloadTypes,
                                hevcMainMatchedPayloadTypes = if (targetProfile == GfnHevcProfile.Main) finalAnswerHevcTargetMatched else emptyList(),
                                hevcTargetMatchedPayloadTypes = finalAnswerHevcTargetMatched,
                                iceUfragPresent = answerSummary.iceUfragPresent,
                                icePasswordPresent = answerSummary.icePasswordPresent,
                                dtlsFingerprintPresent = answerSummary.dtlsFingerprintPresent,
                            ),
                        )
                    }
                    val local = SessionDescription(SessionDescription.Type.ANSWER, bounded)
                    pc.setLocalDescription(
                        setObserver(
                            onSuccess = {
                                if (generation.get() != eventGeneration) return@setObserver
                                sendAnswerAndInjectRemoteCandidates(pc, offerSdp, bounded, eventGeneration)
                            },
                            onFailure = { fail("setLocalDescription 失败：$it") },
                        ),
                        local,
                    )
                }

                override fun onSetSuccess() = Unit
                override fun onCreateFailure(error: String) { fail("createAnswer 失败：$error") }
                override fun onSetFailure(error: String) = Unit
            },
            MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            },
        )
    }

    private fun sendAnswerAndInjectRemoteCandidates(
        pc: PeerConnection,
        offerSdp: String,
        answerSdp: String,
        eventGeneration: Long,
    ) {
        if (generation.get() != eventGeneration) return
        val credentials = GfnSdpTools.extractIceCredentials(answerSdp)
        if (credentials.ufrag.isBlank() || credentials.password.isBlank() || credentials.fingerprintSha256.isBlank()) {
            fail("Answer 缺少 ICE credential 或 DTLS fingerprint；不能构造 NVST SDP。")
            return
        }
        val nvstBitDepth = requestedBitDepth()
        GfnHevcCompatLog.nvstConfig(
            generation = eventGeneration,
            colorMode = config.colorMode,
            bitDepth = nvstBitDepth,
        )
        val nvst = GfnSdpTools.buildNvstSdp(
            credentials,
            NvstSdpConfig(
                width = config.width,
                height = config.height,
                fps = config.fps,
                maxBitrateKbps = config.maxBitrateKbps,
                bitDepth = nvstBitDepth,
                partialReliableThresholdMs = partialReliableThresholdMs,
            ),
        )
        signaling?.sendAnswer(answerSdp, nvst)
        val locals = synchronized(lock) {
            answerSent = true
            pendingLocalIce.toList().also { pendingLocalIce.clear() }
        }
        locals.forEach(::sendLocalIce)
        injectRemoteHostCandidates(pc, offerSdp)
        setState(StreamState.IceChecking)
    }

    private fun handleRemoteIce(event: GfnSignalingEvent.RemoteIce) {
        updateIce { it.copy(remoteCandidateCount = it.remoteCandidateCount + 1) }
        val pc = synchronized(lock) { peerConnection }
        val ready = synchronized(lock) { remoteDescriptionReady }
        if (pc == null || !ready) {
            synchronized(lock) { pendingRemoteIce += event }
            return
        }
        pc.addIceCandidate(IceCandidate(event.sdpMid, event.sdpMLineIndex ?: 0, event.candidate))
    }

    private fun flushRemoteIce() {
        val pc = synchronized(lock) { peerConnection } ?: return
        val pending = synchronized(lock) { pendingRemoteIce.toList().also { pendingRemoteIce.clear() } }
        pending.forEach { event ->
            pc.addIceCandidate(IceCandidate(event.sdpMid, event.sdpMLineIndex ?: 0, event.candidate))
        }
    }

    private fun sendLocalIce(candidate: IceCandidate) {
        signaling?.sendIceCandidate(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
    }

    private fun injectRemoteHostCandidates(pc: PeerConnection, offerSdp: String) {
        val current = synchronized(lock) { session } ?: return
        val target = GfnSdpTools.firstVideoTarget(offerSdp) ?: return
        val mediaConnection = selectMediaConnection(current)
        val ips = buildList {
            mediaConnection?.ip?.let(::extractIpv4FromHost)?.let { if (!contains(it)) add(it) }
            current.serverIp?.let(::extractIpv4FromHost)?.let { if (!contains(it)) add(it) }
            current.sessionControlIp?.let(::extractIpv4FromHost)?.let { if (!contains(it)) add(it) }
        }
        val ports = buildList {
            mediaConnection?.port?.takeIf { it > 0 }?.let { if (!contains(it)) add(it) }
            target.port?.let { if (!contains(it)) add(it) }
        }
        var injected = 0
        var foundation = 1
        ips.forEach { ip ->
            ports.forEach { port ->
                val candidate = IceCandidate(
                    target.mid,
                    target.mLineIndex,
                    "candidate:${foundation++} 1 UDP 2130706431 $ip $port typ host",
                )
                if (pc.addIceCandidate(candidate)) injected += 1
            }
        }
        if (injected > 0) updateIce { it.copy(injectedRemoteCandidateCount = it.injectedRemoteCandidateCount + injected) }
    }

    private fun attachReceiver(receiver: RtpReceiver) {
        val id = runCatching { receiver.id() }.getOrNull() ?: return
        if (!observedReceiverIds.add(id)) return
        receiver.SetObserver(object : RtpReceiver.Observer {
            override fun onFirstPacketReceived(mediaType: MediaStreamTrack.MediaType) {
                when (mediaType) {
                    MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO -> {
                        val effective = synchronized(lock) { effectiveVideoCodec }
                        GfnHevcCompatLog.milestone(
                            generation = generation.get(),
                            stage = "FIRST_VIDEO_RTP",
                            effective = effective,
                            targetProfile = targetHevcProfile(),
                        )
                        updateVideo { it.copy(firstRtpPacketReceived = true) }
                    }
                    MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO -> updateAudio { it.copy(firstRtpPacketReceived = true) }
                    else -> Unit
                }
            }
        })
        when (val track = receiver.track()) {
            is VideoTrack -> attachVideoTrack(track)
            is AudioTrack -> {
                val enabled = runCatching { track.setEnabled(true) }.getOrDefault(false)
                updateAudio {
                    it.copy(
                        remoteAudioTrackPresent = true,
                        remoteAudioTrackEnabled = enabled,
                    )
                }
            }
        }
    }

    private fun attachVideoTrack(track: VideoTrack) {
        synchronized(lock) {
            if (videoTrack === track) return
            videoOutput?.let { output -> videoTrack?.removeSink(output) }
            videoTrack = track
            videoOutput?.let(track::addSink)
        }
        updateVideo { it.copy(remoteVideoTrackPresent = true) }
    }

    private fun onFirstFrameRendered() {
        val effective = synchronized(lock) { effectiveVideoCodec }
        GfnHevcCompatLog.milestone(
            generation = generation.get(),
            stage = "FIRST_FRAME",
            effective = effective,
            detail = decoderPathFor(effective),
            targetProfile = targetHevcProfile(),
        )
        updateVideo { it.copy(firstFrameRendered = true) }
        setState(StreamState.FirstFrame)
    }

    private fun onResolutionChanged(width: Int, height: Int) {
        updateVideo { it.copy(firstFrameWidth = width, firstFrameHeight = height) }
    }

    private fun resolveMediaIp(session: SessionInfo): String? =
        selectMediaConnection(session)?.ip?.let(::extractIpv4FromHost)
            ?: session.serverIp?.let(::extractIpv4FromHost)
            ?: session.sessionControlIp?.let(::extractIpv4FromHost)

    private fun selectMediaConnection(session: SessionInfo): SessionConnectionInfo? {
        fun usable(info: SessionConnectionInfo): Boolean =
            (info.port ?: 0) > 0 && info.ip?.let(::extractIpv4FromHost) != null
        return session.connectionInfo.firstOrNull { it.usage == 2 && usable(it) }
            ?: session.connectionInfo.firstOrNull { it.usage == 17 && usable(it) }
            ?: session.connectionInfo.filter { it.usage == 14 && usable(it) }
                .maxByOrNull { it.port ?: 0 }
    }

    private fun extractIpv4FromHost(value: String): String? {
        val host = value.trim().removePrefix("https://").removePrefix("wss://").substringBefore('/').substringBefore(':')
        val dotted = host.split('.')
        if (dotted.size == 4 && dotted.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }) return host
        val firstLabel = dotted.firstOrNull().orEmpty()
        val dashed = firstLabel.split('-')
        if (dashed.size == 4 && dashed.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }) return dashed.joinToString(".")
        return null
    }

    private fun disconnectInternal(emitClosed: Boolean, inputAlreadyDrained: Boolean = false) {
        val oldTrack: VideoTrack?
        val oldOutput: GfnVideoSurfaceView?
        val oldPc: PeerConnection?
        val oldSignaling: GfnSignalingClient?
        val oldInput: GfnKeyboardMouseInputController?
        val oldGamepad: GfnGamepadInputController?
        val channels: List<DataChannel>
        synchronized(lock) {
            oldTrack = videoTrack
            oldOutput = videoOutput
            oldPc = peerConnection
            oldSignaling = signaling
            oldInput = inputController
            oldGamepad = gamepadController
            channels = dataChannels.toList()
            dataChannels.clear()
            inputDataChannel = null
            controlDataChannel = null
            inputController = null
            gamepadController = null
            videoTrack = null
            peerConnection = null
            signaling = null
            session = null
            remoteDescriptionReady = false
            answerSent = false
            pendingRemoteIce.clear()
            pendingLocalIce.clear()
            observedReceiverIds.clear()
            partialReliableThresholdMs = 300
            effectiveVideoCodec = VideoCodecPreference.H264
            videoCodecFallbackReason = null
        }
        if (oldTrack != null && oldOutput != null) runCatching {
            oldTrack.removeSink(oldOutput)
            oldOutput.inputListener = null
        }
        if (!inputAlreadyDrained) {
            oldInput?.shutdownWithoutTransport()
            oldGamepad?.shutdownWithoutTransport()
        }
        channels.forEach { channel -> runCatching { channel.close(); channel.dispose() } }
        oldSignaling?.disconnect()
        oldPc?.close()
        if (emitClosed) {
            state = StreamState.Closed
            emit()
        }
    }

    private fun setObserver(onSuccess: () -> Unit, onFailure: (String) -> Unit): SdpObserver =
        object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) = Unit
            override fun onSetSuccess() = onSuccess()
            override fun onCreateFailure(error: String) = Unit
            override fun onSetFailure(error: String) = onFailure(error)
        }

    private inline fun ifCurrent(expectedGeneration: Long, block: () -> Unit) {
        if (generation.get() == expectedGeneration) block()
    }

    private fun setState(value: StreamState) {
        state = value
        emit()
    }

    private fun disconnectWithReason(
        reason: InputReleaseReason,
        emitClosed: Boolean,
        onComplete: () -> Unit = {},
    ) {
        generation.incrementAndGet()
        val (keyboard, gamepad) = inputControllers()
        val drainCount = listOfNotNull(keyboard, gamepad).size
        if (drainCount == 0) {
            disconnectInternal(emitClosed = emitClosed, inputAlreadyDrained = true)
            onComplete()
            return
        }
        val remaining = java.util.concurrent.atomic.AtomicInteger(drainCount)
        val drained = {
            if (remaining.decrementAndGet() == 0) {
                disconnectInternal(emitClosed = emitClosed, inputAlreadyDrained = true)
                onComplete()
            }
        }
        keyboard?.prepareForDisconnect(reason, drained)
        gamepad?.prepareForDisconnect(reason, drained)
    }

    private fun fail(reason: String) {
        if (state is StreamState.Failed) return
        state = StreamState.Failed(reason)
        emit()
        disconnectWithReason(InputReleaseReason.WebRtcDisconnect, emitClosed = false)
    }

    private fun updateDiagnostics(transform: (StreamDiagnostics) -> StreamDiagnostics) {
        synchronized(lock) { diagnostics = transform(diagnostics) }
        emit()
    }

    private fun updateSignaling(transform: (SignalingDiagnostics) -> SignalingDiagnostics) =
        updateDiagnostics { it.copy(signaling = transform(it.signaling)) }

    private fun updateIce(transform: (IceDiagnostics) -> IceDiagnostics) =
        updateDiagnostics { it.copy(ice = transform(it.ice)) }

    private fun updateVideo(transform: (VideoDiagnostics) -> VideoDiagnostics) =
        updateDiagnostics { it.copy(video = transform(it.video)) }

    private fun updateAudio(transform: (AudioDiagnostics) -> AudioDiagnostics) =
        updateDiagnostics { it.copy(audio = transform(it.audio)) }

    private fun updateControl(transform: (ControlDiagnostics) -> ControlDiagnostics) =
        updateDiagnostics { it.copy(control = transform(it.control)) }

    private fun emit() {
        listener.onUpdated(state, diagnostics)
    }
}
