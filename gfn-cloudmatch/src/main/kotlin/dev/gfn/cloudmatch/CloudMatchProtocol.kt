package dev.gfn.cloudmatch

import dev.gfn.core.model.IceServer
import dev.gfn.core.model.NegotiatedColorMode
import dev.gfn.core.model.RequestedColorMode
import dev.gfn.core.model.SessionAdRequirement
import dev.gfn.core.model.SessionClaimRequest
import dev.gfn.core.model.SessionConnectionInfo
import dev.gfn.core.model.SessionCreateRequest
import dev.gfn.core.model.SessionInfo
import dev.gfn.core.model.StreamingProfile
import dev.gfn.identity.GfnClientIdentity
import dev.gfn.identity.GfnProtocolDefaults
import dev.gfn.network.HttpRequest
import dev.gfn.network.HttpResponse
import dev.gfn.network.HttpTransport
import dev.gfn.network.Json
import dev.gfn.network.Json.array
import dev.gfn.network.Json.asArray
import dev.gfn.network.Json.asObject
import dev.gfn.network.Json.asString
import dev.gfn.network.Json.boolean
import dev.gfn.network.Json.int
import dev.gfn.network.Json.obj
import dev.gfn.network.Json.string
import dev.gfn.session.CloudMatchPort
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

sealed class CloudMatchException(message: String) : Exception(message) {
    class Unauthorized : CloudMatchException("CloudMatch 拒绝了当前 GFN 凭据")
    class Http(val code: Int, message: String) : CloudMatchException(message)
    class ApiStatus(val code: Int, message: String) : CloudMatchException(message)
    class Protocol(message: String) : CloudMatchException(message)
}

/**
 * CloudMatch 每个 session lifecycle 使用一组随机 clientId + 稳定 x-device-id。
 * UI / ViewModel 不应自行拼这些 Header。
 */
data class GfnRequestContext(
    val token: String,
    val clientId: String,
    val deviceId: String,
    val clientVersion: String = GfnProtocolDefaults.clientVersion,
    val userAgent: String = GfnProtocolDefaults.userAgent,
    val identity: GfnClientIdentity = GfnClientIdentity.WindowsDesktop,
) {
    fun headers(includeOrigin: Boolean = true): Map<String, String> = linkedMapOf<String, String>().apply {
        put("User-Agent", userAgent)
        put("Authorization", "GFNJWT $token")
        put("Accept", "application/json")
        put("Content-Type", "application/json")
        put("nv-browser-type", "CHROME")
        put("nv-client-id", clientId)
        put("nv-client-streamer", "NVIDIA-CLASSIC")
        put("nv-client-type", "NATIVE")
        put("nv-client-version", clientVersion)
        put("nv-device-make", identity.deviceMake)
        put("nv-device-model", identity.deviceModel)
        put("nv-device-os", identity.deviceOs)
        put("nv-device-type", identity.deviceType)
        put("x-device-id", deviceId)
        if (includeOrigin) {
            put("Origin", GfnProtocolDefaults.webOrigin)
            put("Referer", GfnProtocolDefaults.webReferer)
        }
    }
}

/** 仅用于协议 fixture / diagnostics 的可读请求模型。 */
data class ClientMonitorRequest(
    val monitorId: Int = 0,
    val width: Int,
    val height: Int,
    val framesPerSecond: Int,
    val requestedColorMode: RequestedColorMode,
)

data class RequestedStreamingFeatures(
    val tenBitRequested: Boolean,
    val reflexRequested: Boolean = false,
    val l4sRequested: Boolean = false,
)

data class SessionRequestData(
    val appId: String,
    val clientIdentification: String,
    val clientPlatformName: String,
    val deviceHashId: String,
    val clientVersion: String,
    val monitor: ClientMonitorRequest,
    val streamingFeatures: RequestedStreamingFeatures,
)

class SessionRequestFactory(
    private val identity: GfnClientIdentity = GfnClientIdentity.WindowsDesktop,
) {
    fun create(
        appId: String,
        deviceId: String,
        clientVersion: String,
        width: Int,
        height: Int,
        fps: Int,
        colorMode: RequestedColorMode,
    ): SessionRequestData = SessionRequestData(
        appId = appId,
        clientIdentification = identity.clientIdentification,
        clientPlatformName = identity.clientPlatformName,
        deviceHashId = deviceId,
        clientVersion = clientVersion,
        monitor = ClientMonitorRequest(
            width = width,
            height = height,
            framesPerSecond = fps,
            requestedColorMode = colorMode,
        ),
        streamingFeatures = RequestedStreamingFeatures(
            tenBitRequested = colorMode == RequestedColorMode.PreferSdr10 ||
                colorMode == RequestedColorMode.PreferHdr10,
        ),
    )
}

/**
 * v4 CloudMatch 实现。
 *
 * 这一版刻意固定 H.264/SDR8 语义，只验证 Session Lifecycle。
 * Main10/HDR 的 request 映射留到媒体阶段，不在 v4 同时引入变量。
 */
class GfnCloudMatchClient(
    private val transport: HttpTransport,
    private val deviceId: () -> String,
    private val uuid: () -> UUID = UUID::randomUUID,
    private val timezoneOffsetMilliseconds: () -> Int = {
        ZonedDateTime.now(ZoneId.systemDefault()).offset.totalSeconds * 1_000
    },
    private val identity: GfnClientIdentity = GfnClientIdentity.WindowsDesktop,
) : CloudMatchPort {

    override suspend fun createSession(request: SessionCreateRequest): SessionInfo {
        require(request.appId.isNotBlank()) { "CloudMatch appId 不能为空" }
        require(request.streamingBaseUrl.isNotBlank()) { "streamingBaseUrl 不能为空" }
        require(request.requestedColorMode == RequestedColorMode.CompatibilitySdr) {
            "v4 只允许 SDR8 Session；Main10/HDR 留到媒体阶段"
        }

        val clientId = uuid().toString()
        val stableDeviceId = deviceId()
        val base = normalizeBase(request.streamingBaseUrl)
        val url = "$base/v2/session?keyboardLayout=${enc(request.keyboardLayout)}&languageCode=${enc(request.gameLanguage)}"
        val context = GfnRequestContext(
            token = request.token,
            clientId = clientId,
            deviceId = stableDeviceId,
            identity = identity,
        )
        val body = buildCreateBody(request, stableDeviceId, uuid().toString())
        val response = transport.execute(
            HttpRequest(
                method = "POST",
                url = url,
                headers = context.headers(includeOrigin = true),
                body = Json.stringify(body).toByteArray(Charsets.UTF_8),
            ),
        )

        if (response.statusCode == 401) throw CloudMatchException.Unauthorized()
        if (response.statusCode != 200) {
            // CloudMatch 偶尔可能在错误响应里已经分配 session；若能取到 id，立即 best-effort 清理。
            parseSessionId(response)?.let { phantomId ->
                runCatching {
                    stopById(
                        sessionId = phantomId,
                        token = request.token,
                        base = base,
                        serverIp = null,
                        clientId = clientId,
                        deviceId = stableDeviceId,
                    )
                }
            }
            throw CloudMatchException.Http(
                response.statusCode,
                "创建 CloudMatch Session 失败（HTTP ${response.statusCode}）",
            )
        }

        return parseSession(
            response = response,
            base = base,
            routingZoneUrl = request.routingZoneUrl,
            clientId = clientId,
            deviceId = stableDeviceId,
            context = "createSession",
        )
    }

    override suspend fun pollSession(session: SessionInfo, token: String): SessionInfo {
        val effectiveBase = session.serverIp?.takeIf { it.isNotBlank() }
            ?.let { "https://$it" }
            ?: normalizeBase(session.streamingBaseUrl)
        val response = transport.execute(
            HttpRequest(
                method = "GET",
                url = "$effectiveBase/v2/session/${encPath(session.sessionId)}",
                headers = GfnRequestContext(
                    token = token,
                    clientId = session.clientId,
                    deviceId = session.deviceId,
                    identity = identity,
                ).headers(includeOrigin = false),
            ),
        )
        requireHttpSuccess(response, "pollSession")
        val parsed = parseSession(
            response = response,
            base = effectiveBase,
            routingZoneUrl = session.routingZoneUrl,
            clientId = session.clientId,
            deviceId = session.deviceId,
            context = "pollSession",
        )

        // 与 CloudNow 当前行为一致：当 provider endpoint 在 Ready 阶段给出具体 server host 后，
        // 再通过 resolved server 拉一次最终 connectionInfo。
        if (session.serverIp.isNullOrBlank() && parsed.isReadyStatus && !parsed.serverIp.isNullOrBlank()) {
            val currentHost = hostOf(effectiveBase)
            val resolvedHost = parsed.serverIp.lowercase()
            if (currentHost != resolvedHost) {
                return pollSession(parsed, token)
            }
        }
        return parsed
    }

    override suspend fun claimSession(request: SessionClaimRequest): SessionInfo {
        require(request.session.sessionId.isNotBlank()) { "sessionId 不能为空" }
        val initial = request.session.copy(
            streamingBaseUrl = request.baseUrl.ifBlank { request.session.streamingBaseUrl },
        )
        val preflight = pollSession(initial, request.token)
        if (preflight.isInQueue) return preflight
        if (!preflight.isReadyStatus) return preflight

        val resumeBase = normalizeBase(preflight.streamingBaseUrl)
        val url = "$resumeBase/v2/session/${encPath(preflight.sessionId)}" +
            "?keyboardLayout=${enc(request.keyboardLayout)}&languageCode=${enc(request.gameLanguage)}"
        val body = linkedMapOf<String, Any?>(
            "action" to 2,
            "data" to "RESUME",
            "sessionRequestData" to buildResumeBody(
                request = request,
                deviceId = preflight.deviceId,
                subSessionId = uuid().toString(),
            ),
            "metaData" to emptyList<Any>(),
        )
        val response = transport.execute(
            HttpRequest(
                method = "PUT",
                url = url,
                headers = GfnRequestContext(
                    token = request.token,
                    clientId = preflight.clientId,
                    deviceId = preflight.deviceId,
                    identity = identity,
                ).headers(includeOrigin = true),
                body = Json.stringify(body).toByteArray(Charsets.UTF_8),
            ),
        )
        requireHttpSuccess(response, "claimSession")
        return parseSession(
            response = response,
            base = resumeBase,
            routingZoneUrl = preflight.routingZoneUrl,
            clientId = preflight.clientId,
            deviceId = preflight.deviceId,
            context = "claimSession",
        )
    }

    override suspend fun stopSession(session: SessionInfo, token: String) {
        stopById(
            sessionId = session.sessionId,
            token = token,
            base = session.streamingBaseUrl,
            serverIp = session.serverIp,
            clientId = session.clientId,
            deviceId = session.deviceId,
        )
    }

    private suspend fun stopById(
        sessionId: String,
        token: String,
        base: String,
        serverIp: String?,
        clientId: String,
        deviceId: String,
    ) {
        val effectiveBase = serverIp?.takeIf { it.isNotBlank() }
            ?.let { "https://$it" }
            ?: normalizeBase(base)
        val response = transport.execute(
            HttpRequest(
                method = "DELETE",
                url = "$effectiveBase/v2/session/${encPath(sessionId)}",
                headers = GfnRequestContext(
                    token = token,
                    clientId = clientId,
                    deviceId = deviceId,
                    identity = identity,
                ).headers(includeOrigin = false),
            ),
        )
        if (response.statusCode == 401) throw CloudMatchException.Unauthorized()
        if (response.statusCode !in 200..299) {
            throw CloudMatchException.Http(
                response.statusCode,
                "结束 CloudMatch Session 失败（HTTP ${response.statusCode}）",
            )
        }
    }

    private fun buildCreateBody(
        request: SessionCreateRequest,
        deviceId: String,
        subSessionId: String,
    ): Map<String, Any?> {
        val audioChannels = request.audioChannels.coerceIn(2, 6)
        val physicalResolution = Json.stringify(
            mapOf("horizontalPixels" to request.width, "verticalPixels" to request.height),
        )
        return mapOf(
            "sessionRequestData" to linkedMapOf(
                "appId" to request.appId,
                "internalTitle" to request.internalTitle,
                "availableSupportedControllers" to emptyList<Any>(),
                "networkTestSessionId" to null,
                "parentSessionId" to null,
                "clientIdentification" to identity.clientIdentification,
                "deviceHashId" to deviceId,
                "clientVersion" to request.sessionClientVersion,
                "sdkVersion" to "1.0",
                "streamerVersion" to 1,
                "clientPlatformName" to identity.clientPlatformName,
                "clientRequestMonitorSettings" to listOf(
                    mapOf(
                        "monitorId" to 0,
                        "positionX" to 0,
                        "positionY" to 0,
                        "widthInPixels" to request.width,
                        "heightInPixels" to request.height,
                        "framesPerSecond" to request.fps,
                        "sdrHdrMode" to 0,
                        "displayData" to null,
                        "hdr10PlusGamingData" to null,
                        "dpi" to 100,
                    ),
                ),
                "useOps" to true,
                "audioMode" to audioChannels,
                "metaData" to listOf(
                    mapOf("key" to "SubSessionId", "value" to subSessionId),
                    mapOf("key" to "wssignaling", "value" to "1"),
                    mapOf("key" to "GSStreamerType", "value" to "WebRTC"),
                    mapOf("key" to "networkType", "value" to "Unknown"),
                    mapOf("key" to "ClientImeSupport", "value" to "0"),
                    mapOf("key" to "clientPhysicalResolution", "value" to physicalResolution),
                    mapOf("key" to "surroundAudioInfo", "value" to audioChannels.toString()),
                ),
                "sdrHdrMode" to 0,
                "clientDisplayHdrCapabilities" to null,
                "surroundAudioInfo" to surroundAudioMask(audioChannels),
                "remoteControllersBitmap" to 0,
                "clientTimezoneOffset" to timezoneOffsetMilliseconds(),
                "enhancedStreamMode" to 1,
                "appLaunchMode" to request.appLaunchMode,
                "secureRTSPSupported" to false,
                "partnerCustomData" to "",
                "accountLinked" to request.accountLinked,
                "enablePersistingInGameSettings" to request.persistInGameSettings,
                "userAge" to 26,
                // v4 固定 SDR8：bitDepth=0；chromaFormat 保留 CloudNow 当前已验证值 1。
                "requestedStreamingFeatures" to mapOf(
                    "reflex" to false,
                    "bitDepth" to 0,
                    "cloudGsync" to false,
                    "enabledL4S" to false,
                    "profile" to 0,
                    "fallbackToLogicalResolution" to false,
                    "chromaFormat" to 1,
                    "prefilterMode" to 0,
                    "prefilterSharpness" to 0,
                    "prefilterNoiseReduction" to 0,
                    "hudStreamingMode" to 0,
                ),
            ),
        )
    }

    private fun buildResumeBody(
        request: SessionClaimRequest,
        deviceId: String,
        subSessionId: String,
    ): Map<String, Any?> {
        val audioChannels = request.audioChannels.coerceIn(2, 6)
        return linkedMapOf<String, Any?>(
            "audioMode" to audioChannels,
            "remoteControllersBitmap" to 0,
            "sdrHdrMode" to 0,
            "networkTestSessionId" to null,
            "availableSupportedControllers" to emptyList<Any>(),
            "clientVersion" to request.sessionClientVersion,
            "deviceHashId" to deviceId,
            "internalTitle" to null,
            "clientPlatformName" to identity.clientPlatformName,
            "metaData" to listOf(
                mapOf("key" to "SubSessionId", "value" to subSessionId),
                mapOf("key" to "wssignaling", "value" to "1"),
                mapOf("key" to "GSStreamerType", "value" to "WebRTC"),
                mapOf("key" to "networkType", "value" to "Unknown"),
                mapOf("key" to "ClientImeSupport", "value" to "0"),
                mapOf("key" to "surroundAudioInfo", "value" to audioChannels.toString()),
            ),
            "surroundAudioInfo" to surroundAudioMask(audioChannels),
            "clientTimezoneOffset" to timezoneOffsetMilliseconds(),
            "clientIdentification" to identity.clientIdentification,
            "parentSessionId" to null,
            "streamerVersion" to 1,
            "appLaunchMode" to request.appLaunchMode,
            "sdkVersion" to "1.0",
            "enhancedStreamMode" to 1,
            "useOps" to true,
            "clientDisplayHdrCapabilities" to null,
            "accountLinked" to true,
            "partnerCustomData" to "",
            "enablePersistingInGameSettings" to request.persistInGameSettings,
            "secureRTSPSupported" to false,
            "userAge" to 26,
        ).apply {
            request.appId.toLongOrNull()?.let { put("appId", it) }
        }
    }

    private fun parseSession(
        response: HttpResponse,
        base: String,
        routingZoneUrl: String?,
        clientId: String,
        deviceId: String,
        context: String,
    ): SessionInfo {
        val root = try {
            Json.parseObject(response.bodyText)
        } catch (error: Exception) {
            throw CloudMatchException.Protocol("$context JSON 无法解析：${error::class.simpleName}")
        }
        validateApiStatus(root, context)
        val session = root.obj("session")
            ?: throw CloudMatchException.Protocol("$context 响应缺少 session")
        val sessionId = scalarString(session["sessionId"])
            ?.takeIf { it.isNotBlank() }
            ?: throw CloudMatchException.Protocol("$context 响应缺少 sessionId")
        val status = scalarInt(session["status"])
            ?: throw CloudMatchException.Protocol("$context 响应缺少 status")

        val seatSetup = session.obj("seatSetupInfo")
        val progress = session.obj("sessionProgress") ?: session.obj("progressInfo")
        val queuePosition = scalarInt(session["queuePosition"])
            ?: seatSetup?.let { scalarInt(it["queuePosition"]) }
            ?: progress?.let { scalarInt(it["queuePosition"]) }
        val seatSetupStep = scalarInt(session["seatSetupStep"])
            ?: seatSetup?.let { scalarInt(it["seatSetupStep"]) }
        val seatSetupEtaMs = seatSetup?.let { scalarInt(it["seatSetupEta"]) }?.takeIf { it > 0 }

        val connections = session.array("connectionInfo").orEmpty().mapNotNull { value ->
            val item = value.asObject() ?: return@mapNotNull null
            SessionConnectionInfo(
                usage = scalarInt(item["usage"]) ?: return@mapNotNull null,
                ip = flexibleString(item["ip"]),
                port = scalarInt(item["port"]),
                resourcePath = item.string("resourcePath"),
            )
        }
        val signal = connections.firstOrNull { it.usage == 14 }
        val sessionControlIp = flexibleString(session.obj("sessionControlInfo")?.get("ip"))
        val serverIp = signal?.ip
            ?: hostFromResourcePath(signal?.resourcePath)
            ?: sessionControlIp
        val resourcePath = signal?.resourcePath ?: "/nvst/"
        val signalingUrl = serverIp?.takeIf { it.isNotBlank() }?.let {
            resolveSignalingUrl(it, resourcePath)
        }

        val iceServers = session.obj("iceServerConfiguration")
            ?.array("iceServers")
            .orEmpty()
            .mapNotNull { value ->
                val item = value.asObject() ?: return@mapNotNull null
                val urls = flexibleStringArray(item["urls"])
                if (urls.isEmpty()) return@mapNotNull null
                IceServer(
                    urls = urls,
                    username = item.string("username"),
                    credential = item.string("credential"),
                )
            }

        val streamingProfile = session.obj("streamingProfile")
        val bitDepth = streamingProfile?.let { scalarInt(it["bitDepth"]) }
        val hdrMode = streamingProfile?.let {
            scalarString(it["hdrStreamingMode"]) ?: scalarInt(it["hdrStreamingMode"])?.toString()
        }
        val negotiatedColor = when {
            bitDepth == 10 && (hdrMode.equals("HDR", true) || hdrMode == "1") -> NegotiatedColorMode.Hdr10
            bitDepth == 10 -> NegotiatedColorMode.Sdr10
            bitDepth == 8 -> NegotiatedColorMode.Sdr8
            else -> NegotiatedColorMode.Unknown
        }
        val profile = StreamingProfile(
            codec = streamingProfile?.string("codec"),
            profile = streamingProfile?.string("profile"),
            bitDepth = bitDepth,
            colorMode = negotiatedColor,
        )

        val hasAdPayload = session.array("sessionAds").orEmpty().isNotEmpty()
        val adRequired = boolFlexible(session["sessionAdsRequired"])
            ?: boolFlexible(session["isAdsRequired"])
            ?: progress?.let { boolFlexible(it["isAdsRequired"]) }
            ?: hasAdPayload
        val queuePaused = boolFlexible(session["isQueuePaused"])
            ?: session.obj("opportunity")?.let { boolFlexible(it["queuePaused"]) }
        val adMessage = session.obj("opportunity")?.string("message")
            ?: session.obj("opportunity")?.string("description")
        val adRequirement = if (adRequired || queuePaused == true) {
            SessionAdRequirement(adRequired, queuePaused, adMessage)
        } else {
            null
        }

        return SessionInfo(
            sessionId = sessionId,
            status = status,
            queuePosition = queuePosition,
            seatSetupStep = seatSetupStep,
            seatSetupEtaMs = seatSetupEtaMs,
            gpuType = session.string("gpuType"),
            serverIp = serverIp,
            streamingBaseUrl = normalizeBase(base),
            routingZoneUrl = normalizeRoutingZone(routingZoneUrl),
            signalingUrl = signalingUrl,
            sessionControlIp = sessionControlIp,
            connectionInfo = connections,
            iceServers = iceServers,
            clientId = clientId,
            deviceId = deviceId,
            profile = profile,
            adRequirement = adRequirement,
        )
    }

    private fun validateApiStatus(root: Map<String, Json.Value>, context: String) {
        val status = root.obj("requestStatus")
            ?: throw CloudMatchException.Protocol("$context 响应缺少 requestStatus")
        val code = scalarInt(status["statusCode"])
            ?: throw CloudMatchException.Protocol("$context requestStatus 缺少 statusCode")
        if (code != 1) {
            throw CloudMatchException.ApiStatus(
                code,
                "$context 被 CloudMatch 拒绝：${status.string("statusDescription") ?: "无说明"}",
            )
        }
    }

    private fun requireHttpSuccess(response: HttpResponse, context: String) {
        if (response.statusCode == 401) throw CloudMatchException.Unauthorized()
        if (response.statusCode != 200) {
            throw CloudMatchException.Http(
                response.statusCode,
                "$context 失败（HTTP ${response.statusCode}）",
            )
        }
    }

    private fun parseSessionId(response: HttpResponse): String? = runCatching {
        Json.parseObject(response.bodyText).obj("session")?.let { scalarString(it["sessionId"]) }
    }.getOrNull()

    private fun scalarString(value: Json.Value?): String? = when (value) {
        is Json.Value.Str -> value.value
        is Json.Value.Num -> value.value
        is Json.Value.Obj -> scalarString(value.value["value"])
        is Json.Value.Arr -> value.value.firstOrNull()?.let(::scalarString)
        else -> null
    }

    private fun scalarInt(value: Json.Value?): Int? = when (value) {
        is Json.Value.Num -> value.value.toIntOrNull()
        is Json.Value.Str -> value.value.toIntOrNull()
        is Json.Value.Obj -> scalarInt(value.value["value"])
        else -> null
    }

    private fun flexibleString(value: Json.Value?): String? = when (value) {
        is Json.Value.Str -> value.value
        is Json.Value.Num -> value.value.toLongOrNull()?.let(::ipv4FromUInt32) ?: value.value
        is Json.Value.Arr -> value.value.firstOrNull()?.let(::flexibleString)
        is Json.Value.Obj -> flexibleString(value.value["value"])
        else -> null
    }

    private fun flexibleStringArray(value: Json.Value?): List<String> = when (value) {
        is Json.Value.Arr -> value.value.mapNotNull(::flexibleString)
        else -> flexibleString(value)?.let(::listOf).orEmpty()
    }

    private fun boolFlexible(value: Json.Value?): Boolean? = when (value) {
        is Json.Value.Bool -> value.value
        is Json.Value.Num -> value.value.toIntOrNull()?.let { it != 0 }
        is Json.Value.Str -> when (value.value.lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
        else -> null
    }

    private fun ipv4FromUInt32(value: Long): String? {
        if (value !in 0..0xffff_ffffL) return null
        return listOf(
            (value ushr 24) and 0xff,
            (value ushr 16) and 0xff,
            (value ushr 8) and 0xff,
            value and 0xff,
        ).joinToString(".")
    }

    private fun hostFromResourcePath(resourcePath: String?): String? {
        if (resourcePath.isNullOrBlank() || resourcePath.startsWith('/')) return null
        return runCatching { URI.create(resourcePath).host }.getOrNull()
    }

    private fun resolveSignalingUrl(serverIp: String, resourcePath: String): String {
        if (resourcePath.startsWith("rtsps://") || resourcePath.startsWith("rtsp://")) {
            val withoutScheme = resourcePath.substringAfter("://")
            val host = withoutScheme.substringBefore(':').substringBefore('/')
            if (host.isNotBlank() && !host.startsWith('.')) return "wss://$host/nvst/"
        }
        if (resourcePath.startsWith("wss://")) return resourcePath
        if (resourcePath.startsWith('/')) return "wss://$serverIp:443$resourcePath"
        return "wss://$serverIp:443/nvst/"
    }

    private fun normalizeRoutingZone(url: String?): String? {
        val raw = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            val uri = URI.create(raw)
            val host = uri.host?.lowercase() ?: return@runCatching null
            if (!uri.scheme.equals("https", true) || !host.endsWith(".nvidiagrid.net")) return@runCatching null
            "https://$host/"
        }.getOrNull()
    }

    private fun normalizeBase(value: String): String = value.trim().trimEnd('/')

    private fun hostOf(url: String): String? = runCatching { URI.create(url).host?.lowercase() }.getOrNull()

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun encPath(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private fun surroundAudioMask(channels: Int): Int = if (channels >= 6) 4_128_774 else 0
}
