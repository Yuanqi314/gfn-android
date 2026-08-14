package dev.gfn.webrtc

import android.content.Context
import dev.gfn.core.model.RequestedColorMode
import dev.gfn.core.model.SessionConnectionInfo
import dev.gfn.core.model.SessionInfo
import dev.gfn.signaling.GfnSdpTools
import dev.gfn.signaling.NvstSdpConfig
import dev.gfn.stream.IceDiagnostics
import dev.gfn.stream.SdpDiagnostics
import dev.gfn.stream.SignalingDiagnostics
import dev.gfn.stream.StreamConfig
import dev.gfn.stream.StreamDiagnostics
import dev.gfn.stream.StreamState
import dev.gfn.stream.StreamingEngine
import dev.gfn.stream.VideoCodecPreference
import dev.gfn.stream.VideoDiagnostics
import java.net.URI
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
import org.webrtc.VideoTrack

/** v5.0: Claimed Session -> GFN WSS -> SDP -> ICE -> H.264 -> SurfaceViewRenderer。 */
class GfnWebRtcEngine(
    context: Context,
    private val listener: Listener,
) : StreamingEngine {
    interface Listener {
        fun onUpdated(state: StreamState, diagnostics: StreamDiagnostics)
    }

    private val factory: PeerConnectionFactory = GfnWebRtcRuntime.factory(context)
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
    private var partialReliableThresholdMs = 300

    override fun connect(session: SessionInfo, config: StreamConfig) {
        val failure = validate(session, config)
        if (failure != null) {
            fail(failure)
            return
        }

        disconnectInternal(emitClosed = false)
        val resolvedSignalingUrl = session.signalingUrl?.takeIf { it.isNotBlank() }
            ?: run {
                fail("Claimed Session 缺少 signalingUrl。")
                return
            }
        val currentGeneration = generation.incrementAndGet()
        synchronized(lock) {
            this.session = session
            this.config = config
            remoteDescriptionReady = false
            answerSent = false
            pendingRemoteIce.clear()
            pendingLocalIce.clear()
            observedReceiverIds.clear()
            partialReliableThresholdMs = 300
            diagnostics = StreamDiagnostics(
                signaling = SignalingDiagnostics(
                    endpointHost = runCatching { URI(resolvedSignalingUrl).host }.getOrNull(),
                ),
                ice = IceDiagnostics(
                    serverIceEntries = session.iceServers.size,
                    effectiveIceServers = session.iceServers.size,
                    fallbackActive = false,
                ),
            )
            state = StreamState.OpeningSignaling
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
        generation.incrementAndGet()
        disconnectInternal(emitClosed = true)
    }

    fun bindVideoOutput(output: GfnVideoSurfaceView?) {
        synchronized(lock) {
            if (videoOutput === output) return
            videoOutput?.let { previous -> videoTrack?.removeSink(previous) }
            videoOutput = output
            if (output != null) {
                output.onFirstFrame = ::onFirstFrameRendered
                output.onResolutionChanged = ::onResolutionChanged
                videoTrack?.addSink(output)
            }
        }
    }

    private fun validate(session: SessionInfo, config: StreamConfig): String? = when {
        !session.isReadyStatus -> "v5 只接受已 Ready/Claimed 的 Session（status=${session.status}）。"
        session.signalingUrl.isNullOrBlank() -> "Claimed Session 缺少 signalingUrl。"
        config.codec != VideoCodecPreference.H264 -> "v5.0 强制 H.264；HEVC/AV1 尚未启用。"
        config.colorMode != RequestedColorMode.CompatibilitySdr -> "v5.0 强制 SDR8。"
        config.width != 1920 || config.height != 1080 -> "v5.0 当前固定 1920x1080。"
        config.fps != 60 -> "v5.0 当前固定 60 FPS。"
        config.audioChannels != 2 -> "v5.0 当前固定 Stereo。"
        else -> null
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
        updateDiagnostics {
            it.copy(
                offer = SdpDiagnostics(
                    offerPresent = true,
                    videoCodecs = offerSummary.videoCodecs,
                    h264PayloadTypes = offerSummary.h264PayloadTypes,
                    iceUfragPresent = offerSummary.iceUfragPresent,
                    icePasswordPresent = offerSummary.icePasswordPresent,
                    dtlsFingerprintPresent = offerSummary.dtlsFingerprintPresent,
                ),
            )
        }
        if (offerSummary.h264PayloadTypes.isEmpty()) {
            fail("GFN Offer 未包含 H.264 payload type；v5.0 不允许回退 HEVC/AV1。")
            return
        }
        synchronized(lock) {
            if (peerConnection != null) {
                fail("v5.0 收到重复 Offer；当前版本不做 renegotiation。")
                return
            }
        }
        setState(StreamState.NegotiatingSdp)

        val pc = createPeerConnection(currentSession, eventGeneration) ?: return
        synchronized(lock) { peerConnection = pc }
        partialReliableThresholdMs = GfnSdpTools.partialReliableThresholdMs(offerSdp)
        createExpectedDataChannels(pc, partialReliableThresholdMs)

        val mediaIp = resolveMediaIp(currentSession)
        val fixedOffer = mediaIp?.let { GfnSdpTools.rewriteOfferConnectionAddresses(offerSdp, it) } ?: offerSdp
        pc.setRemoteDescription(
            setObserver(
                onSuccess = {
                    if (generation.get() != eventGeneration) return@setObserver
                    synchronized(lock) { remoteDescriptionReady = true }
                    flushRemoteIce()
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
                        PeerConnection.IceConnectionState.COMPLETED -> setState(StreamState.Connected)
                        PeerConnection.IceConnectionState.CHECKING -> setState(StreamState.IceChecking)
                        PeerConnection.IceConnectionState.FAILED -> fail("ICE connection FAILED")
                        else -> Unit
                    }
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                ifCurrent(eventGeneration) {
                    updateIce { it.copy(peerConnectionState = newState.name) }
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> setState(StreamState.Connected)
                        PeerConnection.PeerConnectionState.FAILED -> fail("PeerConnection FAILED")
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
            override fun onDataChannel(dataChannel: DataChannel) = Unit
            override fun onRenegotiationNeeded() = Unit

            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
                ifCurrent(eventGeneration) { attachReceiver(receiver) }
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                ifCurrent(eventGeneration) { attachReceiver(transceiver.receiver) }
            }
        }
        return factory.createPeerConnection(rtc, observer).also {
            if (it == null) fail("PeerConnectionFactory.createPeerConnection 返回 null。")
        }
    }

    private fun createExpectedDataChannels(pc: PeerConnection, partialThresholdMs: Int) {
        pc.createDataChannel(
            "input_channel_v1",
            DataChannel.Init().apply { ordered = true },
        )?.let(dataChannels::add)
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

    private fun createAnswer(pc: PeerConnection, offerSdp: String, eventGeneration: Long) {
        pc.createAnswer(
            object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) {
                    if (generation.get() != eventGeneration) return
                    val h264Answer = GfnSdpTools.preferH264InAnswer(description.description)
                    val bounded = GfnSdpTools.injectBandwidth(h264Answer, config.maxBitrateKbps)
                    val answerSummary = GfnSdpTools.summarize(bounded, isOffer = false)
                    if (answerSummary.h264PayloadTypes.isEmpty()) {
                        fail("生成的 Answer 未保留 H.264；停止连接。")
                        return
                    }
                    updateDiagnostics {
                        it.copy(
                            answer = SdpDiagnostics(
                                answerPresent = true,
                                videoCodecs = answerSummary.videoCodecs,
                                h264PayloadTypes = answerSummary.h264PayloadTypes,
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
        val nvst = GfnSdpTools.buildNvstSdp(
            credentials,
            NvstSdpConfig(
                width = config.width,
                height = config.height,
                fps = config.fps,
                maxBitrateKbps = config.maxBitrateKbps,
                bitDepth = 8,
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
                if (mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO) {
                    updateVideo { it.copy(firstRtpPacketReceived = true) }
                }
            }
        })
        when (val track = receiver.track()) {
            is VideoTrack -> attachVideoTrack(track)
            is AudioTrack -> track.setEnabled(false) // v5.0 先只验证视频第一帧；音频放到 v5.x。
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

    private fun disconnectInternal(emitClosed: Boolean) {
        val oldTrack: VideoTrack?
        val oldOutput: GfnVideoSurfaceView?
        val oldPc: PeerConnection?
        val oldSignaling: GfnSignalingClient?
        val channels: List<DataChannel>
        synchronized(lock) {
            oldTrack = videoTrack
            oldOutput = videoOutput
            oldPc = peerConnection
            oldSignaling = signaling
            channels = dataChannels.toList()
            dataChannels.clear()
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
        }
        if (oldTrack != null && oldOutput != null) runCatching { oldTrack.removeSink(oldOutput) }
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

    private fun fail(reason: String) {
        if (state is StreamState.Failed) return
        generation.incrementAndGet()
        state = StreamState.Failed(reason)
        emit()
        disconnectInternal(emitClosed = false)
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

    private fun emit() {
        listener.onUpdated(state, diagnostics)
    }
}
