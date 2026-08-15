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

data class StreamResolution(val width: Int, val height: Int)

data class StreamEngineCapabilities(
    val resolutions: Set<StreamResolution>,
    val frameRates: Set<Int>,
    val maxBitrateKbpsRange: IntRange,
    val codecs: Set<VideoCodecPreference>,
    val colorModes: Set<RequestedColorMode>,
    val audioChannels: Set<Int>,
) {
    fun rejectionReason(config: StreamConfig): String? = when {
        StreamResolution(config.width, config.height) !in resolutions ->
            "当前引擎不支持 ${config.width}x${config.height}。"
        config.fps !in frameRates -> "当前引擎不支持 ${config.fps} FPS。"
        config.maxBitrateKbps !in maxBitrateKbpsRange ->
            "最大码率必须在 ${maxBitrateKbpsRange.first / 1_000}-${maxBitrateKbpsRange.last / 1_000} Mbps。"
        config.codec !in codecs -> "当前引擎不支持 codec=${config.codec}。"
        config.colorMode !in colorModes -> "当前引擎不支持 colorMode=${config.colorMode}。"
        config.audioChannels !in audioChannels -> "当前引擎不支持 ${config.audioChannels} 声道。"
        else -> null
    }
}

/**
 * v5.2 只公开当前 Android WebRTC production path 已经具备的媒体维度。
 * 1080p60 / H.264 / SDR8 / Stereo 是现有稳定路径；码率范围是客户端参数 guard，
 * 非默认码率仍需真机 A/B 验证实际服务端效果，不能把 5-100 Mbps 解释为已验证的服务端上限。
 * HEVC/Main10/HDR/5.1/120 FPS 会在后续版本单独取证，不提前出现在可选能力集合。
 */
object StreamCapabilityProfiles {
    val V52_ANDROID_WEBRTC = StreamEngineCapabilities(
        resolutions = setOf(StreamResolution(1920, 1080)),
        frameRates = setOf(60),
        maxBitrateKbpsRange = 5_000..100_000,
        codecs = setOf(VideoCodecPreference.H264),
        colorModes = setOf(RequestedColorMode.CompatibilitySdr),
        audioChannels = setOf(2),
    )
}

sealed interface StreamState {
    data object Idle : StreamState
    data object OpeningSignaling : StreamState
    data object AwaitingOffer : StreamState
    data object NegotiatingSdp : StreamState
    data object IceChecking : StreamState
    data object Connected : StreamState
    data object FirstFrame : StreamState
    data class Reconnecting(val attempt: Int, val source: String) : StreamState
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
    val lastScanCode: Int? = null,
    val releaseCount: Long = 0,
    val lastEvent: String? = null,
    val lastReleaseReason: String? = null,
)

data class GamepadDiagnostics(
    val connected: Boolean = false,
    val active: Boolean = false,
    val dataChannelOpen: Boolean = false,
    val protocolReady: Boolean = false,
    val protocolVersion: Int? = null,
    val deviceId: Int? = null,
    val deviceName: String? = null,
    val buttons: Int = 0,
    val leftTrigger: Int = 0,
    val rightTrigger: Int = 0,
    val leftStickX: Int = 0,
    val leftStickY: Int = 0,
    val rightStickX: Int = 0,
    val rightStickY: Int = 0,
    val generatedPackets: Long = 0,
    val submittedPackets: Long = 0,
    val acceptedPackets: Long = 0,
    val rejectedPackets: Long = 0,
    val droppedPackets: Long = 0,
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

data class ReconnectDiagnostics(
    val active: Boolean = false,
    val attempt: Int = 0,
    val maxAttempts: Int = 3,
    val source: String? = null,
    val phase: String = "IDLE",
    val sessionId: String? = null,
    val sameSessionIdVerified: Boolean = false,
    val frozenProfileVerified: Boolean = false,
    val lastError: String? = null,
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
    val gamepad: GamepadDiagnostics = GamepadDiagnostics(),
    val reconnect: ReconnectDiagnostics = ReconnectDiagnostics(),
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
