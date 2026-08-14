package dev.gfn.core.model

data class GameSummary(
    val appId: String,
    val title: String,
    val artworkUrl: String? = null,
    val heroImageUrl: String? = null,
    val genres: List<String> = emptyList(),
    val supportsHdr: Boolean = false,
    val supportsRtx: Boolean = false,
    val supportsReflex: Boolean = false,
    val isInLibrary: Boolean = false,
    val variants: List<GameVariant> = emptyList(),
)

data class GameVariant(
    val id: String,
    val appStore: String,
    /** CloudMatch 启动用 appId。当前 GFN browse 中数值 variant id 可直接作为 appId。 */
    val appId: String? = null,
    val isOwned: Boolean = false,
) {
    val launchAppId: String
        get() = appId ?: id
}

data class GameDetail(
    val appId: String,
    val title: String,
    val description: String? = null,
    val artworkUrl: String? = null,
    val heroImageUrl: String? = null,
    val genres: List<String> = emptyList(),
    val developer: String? = null,
    val publisher: String? = null,
    val contentRating: String? = null,
    val supportsHdr: Boolean = false,
    val supportsRtx: Boolean = false,
    val supportsReflex: Boolean = false,
    val isInLibrary: Boolean = false,
    val variants: List<GameVariant> = emptyList(),
)

data class EntitledResolution(
    val width: Int,
    val height: Int,
    val fps: Int,
)

data class SubscriptionInfo(
    val membershipTier: String,
    val isUnlimited: Boolean = false,
    val remainingMinutes: Int? = null,
    val totalMinutes: Int? = null,
    val entitledResolutions: List<EntitledResolution> = emptyList(),
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

data class IceServer(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

data class SessionConnectionInfo(
    val usage: Int,
    val ip: String? = null,
    val port: Int? = null,
    val resourcePath: String? = null,
)

data class SessionAdRequirement(
    val required: Boolean,
    val queuePaused: Boolean? = null,
    val message: String? = null,
)

data class SessionInfo(
    val sessionId: String,
    val status: Int,
    val queuePosition: Int? = null,
    val seatSetupStep: Int? = null,
    val seatSetupEtaMs: Int? = null,
    val gpuType: String? = null,
    val serverIp: String? = null,
    val streamingBaseUrl: String = "",
    val routingZoneUrl: String? = null,
    val signalingUrl: String? = null,
    val sessionControlIp: String? = null,
    val connectionInfo: List<SessionConnectionInfo> = emptyList(),
    val iceServers: List<IceServer> = emptyList(),
    val clientId: String = "",
    val deviceId: String = "",
    val profile: StreamingProfile = StreamingProfile(),
    val adRequirement: SessionAdRequirement? = null,
) {
    /** 与 CloudNow 当前判定一致：seatSetupStep=1 或 queuePosition>1 才视为仍在队列。 */
    val isInQueue: Boolean
        get() = seatSetupStep == 1 || (queuePosition ?: 0) > 1

    val isReadyStatus: Boolean
        get() = status == 2 || status == 3
}

data class SessionCreateRequest(
    val appId: String,
    val token: String,
    val streamingBaseUrl: String,
    val internalTitle: String? = null,
    val routingZoneUrl: String? = null,
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 60,
    val keyboardLayout: String = "en-US",
    val gameLanguage: String = "en_US",
    val requestedColorMode: RequestedColorMode = RequestedColorMode.CompatibilitySdr,
    val audioChannels: Int = 2,
    val accountLinked: Boolean = true,
    val persistInGameSettings: Boolean = true,
    /** CloudMatch sessionRequestData 的客户端版本；与 HTTP nv-client-version 是两个字段。 */
    val sessionClientVersion: String = "30.0",
    /** 1=Default，2=GamepadFriendly，3=TouchFriendly。v4 用最保守的 Default。 */
    val appLaunchMode: Int = 1,
)

data class SessionClaimRequest(
    val session: SessionInfo,
    val appId: String,
    val token: String,
    val baseUrl: String,
    val keyboardLayout: String = "en-US",
    val gameLanguage: String = "en_US",
    val audioChannels: Int = 2,
    val persistInGameSettings: Boolean = true,
    val sessionClientVersion: String = "30.0",
    val appLaunchMode: Int = 1,
)
