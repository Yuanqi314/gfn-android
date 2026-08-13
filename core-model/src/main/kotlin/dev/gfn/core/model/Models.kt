package dev.gfn.core.model

data class GameSummary(
    val appId: String,
    val title: String,
    val artworkUrl: String? = null,
    val supportsHdr: Boolean = false,
    val supportsRtx: Boolean = false,
)

enum class RequestedColorMode {
    Automatic,
    CompatibilitySdr,
    PreferSdr10,
    PreferHdr10,
}

enum class NegotiatedColorMode {
    Unknown,
    Sdr8,
    Sdr10,
    Hdr10,
}

data class StreamingProfile(
    val codec: String? = null,
    val profile: String? = null,
    val bitDepth: Int? = null,
    val colorMode: NegotiatedColorMode = NegotiatedColorMode.Unknown,
)

data class SessionInfo(
    val sessionId: String,
    val status: Int,
    val queuePosition: Int? = null,
    val serverIp: String? = null,
    val streamingBaseUrl: String = "",
    val routingZoneUrl: String? = null,
    val clientId: String = "",
    val deviceId: String = "",
    val profile: StreamingProfile = StreamingProfile(),
) {
    val isInQueue: Boolean
        get() = queuePosition != null && queuePosition > 0
}

data class SessionCreateRequest(
    val appId: String,
    val token: String,
    val deviceId: String,
    val requestedColorMode: RequestedColorMode = RequestedColorMode.Automatic,
)
