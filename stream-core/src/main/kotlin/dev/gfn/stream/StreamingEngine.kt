package dev.gfn.stream

import dev.gfn.core.model.RequestedColorMode
import dev.gfn.core.model.SessionInfo

/** v5.0 只允许 H.264；HEVC / AV1 保留枚举但 Android v5 engine 会拒绝它们。 */
enum class VideoCodecPreference {
    H264,
    Hevc,
    Av1,
}

data class StreamConfig(
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 60,
    val maxBitrateKbps: Int = 20_000,
    val codec: VideoCodecPreference = VideoCodecPreference.H264,
    val colorMode: RequestedColorMode = RequestedColorMode.CompatibilitySdr,
    val audioChannels: Int = 2,
)

sealed interface StreamState {
    data object Idle : StreamState
    data object OpeningSignaling : StreamState
    data object AwaitingOffer : StreamState
    data object NegotiatingSdp : StreamState
    data object IceChecking : StreamState
    data object Connected : StreamState
    data object FirstFrame : StreamState
    data object SessionEnded : StreamState
    data class Failed(val reason: String) : StreamState
    data object Closed : StreamState
}

data class SignalingDiagnostics(
    val websocketConnected: Boolean = false,
    val endpointHost: String? = null,
    val rxCount: Int = 0,
    val txCount: Int = 0,
    val lastRxType: String? = null,
    val lastTxType: String? = null,
    val lastRxEpochMillis: Long? = null,
    val lastTxEpochMillis: Long? = null,
    val closeCode: Int? = null,
    val closeReason: String? = null,
)

data class SdpDiagnostics(
    val offerPresent: Boolean = false,
    val answerPresent: Boolean = false,
    val videoCodecs: List<String> = emptyList(),
    val h264PayloadTypes: List<Int> = emptyList(),
    val iceUfragPresent: Boolean = false,
    val icePasswordPresent: Boolean = false,
    val dtlsFingerprintPresent: Boolean = false,
)

data class IceDiagnostics(
    val serverIceEntries: Int = 0,
    val effectiveIceServers: Int = 0,
    val fallbackActive: Boolean = false,
    val localCandidateCount: Int = 0,
    val remoteCandidateCount: Int = 0,
    val injectedRemoteCandidateCount: Int = 0,
    val signalingState: String = "NEW",
    val iceGatheringState: String = "NEW",
    val iceConnectionState: String = "NEW",
    val peerConnectionState: String = "NEW",
)



data class InputDiagnostics(
    val dataChannelOpen: Boolean = false,
    val protocolReady: Boolean = false,
    val protocolVersion: Int? = null,
    val windowFocused: Boolean = false,
    val pointerCaptured: Boolean = false,
    val overlayOpen: Boolean = false,
    val keyboardActive: Boolean = false,
    val mouseActive: Boolean = false,
    val inputEpoch: Long = 1,
    val remoteState: String = "ASSUMED_SYNCED",
    val physicalHeldKeys: Int = 0,
    val remoteHeldKeys: Int = 0,
    val physicalHeldMouseButtons: Int = 0,
    val remoteHeldMouseButtons: Int = 0,
    val generatedPackets: Long = 0,
    val submittedPackets: Long = 0,
    val acceptedPackets: Long = 0,
    val rejectedPackets: Long = 0,
    val droppedPackets: Long = 0,
    val staleEventsDropped: Long = 0,
    val transportBufferedBytes: Long = 0,
    val lastRawKeyCode: Int? = null,
    val lastRawMetaState: Int? = null,
    val lastAndroidReportedModifierMask: Int? = null,
    val lastTrackedModifierMask: Int? = null,
    val modifierMismatchCount: Long = 0,
    val keyboardWireMode: String = "SCAN_SET1",
    val lastMappedScanCode: Int? = null,
    val lastWireScanCode: Int? = null,
    val releaseCount: Long = 0,
    val lastEvent: String? = null,
    val lastReleaseReason: String? = null,
)

data class AudioDiagnostics(
    val remoteAudioTrackPresent: Boolean = false,
    val remoteAudioTrackEnabled: Boolean = false,
    val firstRtpPacketReceived: Boolean = false,
    val requestedChannels: Int = 2,
    val androidUsage: String = "USAGE_GAME",
    val androidContentType: String = "CONTENT_TYPE_MUSIC",
    val volumeStream: String = "STREAM_MUSIC",
)

data class ControlDiagnostics(
    val controlChannelPresent: Boolean = false,
    val controlChannelState: String = "NONE",
    val rxCount: Int = 0,
    val exitMessageSeen: Boolean = false,
    val lastEvent: String? = null,
)

data class VideoDiagnostics(
    val remoteVideoTrackPresent: Boolean = false,
    val firstRtpPacketReceived: Boolean = false,
    val firstFrameRendered: Boolean = false,
    val firstFrameWidth: Int? = null,
    val firstFrameHeight: Int? = null,
    val decoderPath: String = "libwebrtc DefaultVideoDecoderFactory（具体硬件/软件 decoder 待真机确认）",
)

data class StreamDiagnostics(
    val signaling: SignalingDiagnostics = SignalingDiagnostics(),
    val offer: SdpDiagnostics = SdpDiagnostics(),
    val answer: SdpDiagnostics = SdpDiagnostics(),
    val ice: IceDiagnostics = IceDiagnostics(),
    val video: VideoDiagnostics = VideoDiagnostics(),
    val audio: AudioDiagnostics = AudioDiagnostics(),
    val control: ControlDiagnostics = ControlDiagnostics(),
    val input: InputDiagnostics = InputDiagnostics(),
)

interface StreamingEngine {
    val state: StreamState
    val diagnostics: StreamDiagnostics

    fun connect(session: SessionInfo, config: StreamConfig)

    fun disconnect()
}

/**
 * Android v5 的输出目标最终落到 SurfaceViewRenderer；未来 Main10/HDR 可以换成
 * direct MediaCodec -> SurfaceView，而不改变 Session / Signaling 模块。
 */
interface VideoOutputTarget
