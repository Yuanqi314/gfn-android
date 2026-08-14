package dev.gfn.auth

import dev.gfn.auth.SimpleJson.arrayValue
import dev.gfn.auth.SimpleJson.int
import dev.gfn.auth.SimpleJson.objectValue
import dev.gfn.auth.SimpleJson.string
import dev.gfn.network.HttpRequest
import dev.gfn.network.HttpResponse
import dev.gfn.network.HttpTransport
import java.net.URLEncoder
import java.time.Instant
import java.util.UUID

/**
 * CloudNow 当前公开实现中已经验证可工作的 Device Flow 参数参考。
 *
 * 这些值不是密码或 client secret，但属于外部服务公开客户端配置，后端未来可能调整。
 * 所有值集中在一个可替换配置对象中，禁止散落到 UI 和业务代码。
 */
object NvidiaAuthReferenceDefaults {
    val config = AuthEndpointConfig(
        deviceAuthorizeUrl = "https://login.nvidia.com/device/authorize",
        tokenUrl = "https://login.nvidia.com/token",
        clientTokenUrl = "https://login.nvidia.com/client_token",
        userInfoUrl = "https://login.nvidia.com/userinfo",
        serviceUrlsUrl = "https://pcs.geforcenow.com/v1/serviceUrls",
        deviceFlowClientId = "zp4TWyCwtbLiUfcG0_ecveyZEK1OlNiee-8qthakGn8",
        mainClientId = "ZU7sPN-miLujMD95LfOQ453IB0AtjM8sMyvgJ9wCXEQ",
        scopes = "openid consent email tk_client age",
        defaultIdpId = "PDiAhv2kJTFeQ7WOPqiQ2tRZ7lGhR2X11dXvM4TZSxg",
        defaultStreamingServiceUrl = "https://prod.cloudmatchbeta.nvidiagrid.net/",
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36 " +
            "NVIDIACEFClient/HEAD/debb5919f6 GFN-PC/2.0.86.124",
        // 暂时保持 CloudNow 已验证的 Device Flow display_name，减少认证阶段变量。
        displayName = "Apple TV",
    )
}

class NvidiaAuthApi(
    private val transport: HttpTransport,
    private val config: AuthEndpointConfig = NvidiaAuthReferenceDefaults.config,
    private val now: () -> Instant = Instant::now,
    private val uuid: () -> UUID = UUID::randomUUID,
    private val sleepSeconds: suspend (Long) -> Unit = { Thread.sleep(it * 1_000L) },
) : AuthApi {
    override suspend fun fetchProviders(): List<LoginProvider> {
        val response = transport.execute(
            HttpRequest(
                method = "GET",
                url = config.serviceUrlsUrl,
                headers = mapOf(
                    "User-Agent" to config.userAgent,
                    "Accept" to "application/json",
                ),
            ),
        )
        require2xx(response, "获取登录 Provider")
        val root = parseObject(response, "Provider discovery 响应")
        val serviceInfo = root.objectValue("gfnServiceInfo") ?: return emptyList()
        val endpoints = serviceInfo.arrayValue("gfnServiceEndpoints") ?: return emptyList()
        return endpoints.mapNotNull { value ->
            val endpoint = (value as? SimpleJson.Value.Obj)?.value ?: return@mapNotNull null
            val idpId = endpoint.string("idpId") ?: return@mapNotNull null
            val code = endpoint.string("loginProviderCode") ?: "UNKNOWN"
            val displayName = endpoint.string("loginProviderDisplayName") ?: code
            val streamingUrl = (endpoint.string("streamingServiceUrl") ?: config.defaultStreamingServiceUrl)
                .let { if (it.endsWith('/')) it else "$it/" }
            LoginProvider(
                idpId = idpId,
                code = code,
                displayName = if (code == "BPC") "bro.game" else displayName,
                streamingServiceUrl = streamingUrl,
                priority = endpoint.int("loginProviderPriority") ?: 0,
            )
        }.sortedBy { it.priority }
    }

    override suspend fun beginDeviceAuthorization(idpId: String?): DeviceAuthorization {
        val resolvedIdpId = idpId ?: config.defaultIdpId
        val fields = buildList {
            add("client_id" to config.deviceFlowClientId)
            add("scope" to config.scopes)
            add("device_id" to uuid().toString())
            add("display_name" to config.displayName)
            if (!resolvedIdpId.isNullOrBlank()) add("idp_id" to resolvedIdpId)
        }
        val response = transport.execute(
            HttpRequest(
                method = "POST",
                url = config.deviceAuthorizeUrl,
                headers = authHeaders(contentType = FORM_CONTENT_TYPE),
                body = formUrlEncoded(fields),
            ),
        )
        require2xx(response, "获取设备登录码")
        val payload = parseObject(response, "设备登录码响应")
        return DeviceAuthorization(
            userCode = payload.requireString("user_code"),
            deviceCode = payload.requireString("device_code"),
            verificationUri = payload.requireString("verification_uri"),
            verificationUriComplete = payload.string("verification_uri_complete"),
            expiresInSeconds = payload.requireInt("expires_in"),
            pollIntervalSeconds = (payload.int("interval") ?: 5).coerceAtLeast(1),
        )
    }

    override suspend fun pollDeviceAuthorization(authorization: DeviceAuthorization): AuthTokens {
        val deadline = now().plusSeconds(authorization.expiresInSeconds.toLong())
        var intervalSeconds = authorization.pollIntervalSeconds.toLong().coerceAtLeast(1)

        while (now().isBefore(deadline)) {
            sleepSeconds(intervalSeconds)
            val response = transport.execute(
                HttpRequest(
                    method = "POST",
                    url = config.tokenUrl,
                    headers = authHeaders(contentType = FORM_CONTENT_TYPE),
                    body = formUrlEncoded(
                        listOf(
                            "grant_type" to DEVICE_CODE_GRANT,
                            "device_code" to authorization.deviceCode,
                            "client_id" to config.deviceFlowClientId,
                        ),
                    ),
                ),
            )

            if (response.statusCode == 200) return parseTokens(response)

            val error = runCatching { SimpleJson.parseObject(response.bodyText) }.getOrNull()
            when (error?.string("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> intervalSeconds += 5
                "expired_token" -> throw AuthException.DeviceFlowExpired
                "access_denied" -> throw AuthException.DeviceFlowDenied
                else -> throw AuthException.Http(
                    response.statusCode,
                    error?.string("error_description") ?: "设备登录轮询失败（HTTP ${response.statusCode}）",
                )
            }
        }
        throw AuthException.DeviceFlowExpired
    }

    override suspend fun fetchClientToken(accessToken: String): ClientToken {
        val response = transport.execute(
            HttpRequest(
                method = "GET",
                url = config.clientTokenUrl,
                headers = mapOf(
                    "User-Agent" to config.userAgent,
                    "Accept" to "application/json, text/plain, */*",
                    "Authorization" to "Bearer $accessToken",
                    "Origin" to "https://nvfile",
                ),
            ),
        )
        require2xx(response, "获取 client_token")
        val payload = parseObject(response, "client_token 响应")
        return ClientToken(
            value = payload.requireString("client_token"),
            expiresAt = now().plusSeconds((payload.int("expires_in") ?: 86_400).toLong()),
        )
    }

    override suspend fun refreshWithClientToken(clientToken: String, userId: String): AuthTokens {
        var lastError: AuthException = AuthException.RefreshRejected
        for (clientId in listOf(config.mainClientId, config.deviceFlowClientId)) {
            val response = transport.execute(
                HttpRequest(
                    method = "POST",
                    url = config.tokenUrl,
                    headers = authHeaders(contentType = FORM_CONTENT_TYPE, includeOrigin = true),
                    body = formUrlEncoded(
                        listOf(
                            "grant_type" to CLIENT_TOKEN_GRANT,
                            "client_token" to clientToken,
                            "client_id" to clientId,
                            "sub" to userId,
                        ),
                    ),
                ),
            )
            if (response.statusCode == 200) return parseTokens(response)
            lastError = AuthException.Http(
                response.statusCode,
                "client_token 重新绑定失败（HTTP ${response.statusCode}）",
            )
            if (response.statusCode !in 400..499) throw lastError
        }
        throw lastError
    }

    override suspend fun refresh(refreshToken: String): AuthTokens {
        // 与 CloudNow 当前行为保持一致：Device Flow client ID 优先，主 client ID 作为回退。
        for (clientId in listOf(config.deviceFlowClientId, config.mainClientId)) {
            val response = transport.execute(
                HttpRequest(
                    method = "POST",
                    url = config.tokenUrl,
                    headers = authHeaders(contentType = FORM_CONTENT_TYPE, includeOrigin = true),
                    body = formUrlEncoded(
                        listOf(
                            "grant_type" to "refresh_token",
                            "refresh_token" to refreshToken,
                            "client_id" to clientId,
                        ),
                    ),
                ),
            )
            if (response.statusCode == 200) return parseTokens(response)
            if (response.statusCode !in 400..499) {
                throw AuthException.Http(response.statusCode, "刷新登录状态失败（HTTP ${response.statusCode}）")
            }
        }
        throw AuthException.RefreshRejected
    }

    override suspend fun userInfo(accessToken: String): AuthUser {
        val response = transport.execute(
            HttpRequest(
                method = "GET",
                url = config.userInfoUrl,
                headers = mapOf(
                    "User-Agent" to config.userAgent,
                    "Accept" to "application/json",
                    "Authorization" to "Bearer $accessToken",
                    "Origin" to "https://nvfile",
                ),
            ),
        )
        require2xx(response, "获取账号信息")
        val payload = parseObject(response, "账号信息响应")
        val email = payload.string("email")
        val fallbackName = email?.substringBefore('@')?.takeIf { it.isNotBlank() } ?: "GFN 用户"
        return AuthUser(
            userId = payload.requireString("sub"),
            displayName = payload.string("preferred_username")?.takeIf { it.isNotBlank() } ?: fallbackName,
            email = email,
            membershipTier = payload.string("gfn_tier"),
        )
    }

    private fun parseTokens(response: HttpResponse): AuthTokens {
        val payload = parseObject(response, "Token 响应")
        val accessToken = payload.requireString("access_token")
        return AuthTokens(
            accessToken = accessToken,
            refreshToken = payload.string("refresh_token"),
            idToken = payload.string("id_token"),
            expiresAt = now().plusSeconds((payload.int("expires_in") ?: 86_400).toLong()),
            clientToken = payload.string("client_token"),
        )
    }

    private fun parseObject(response: HttpResponse, context: String): Map<String, SimpleJson.Value> =
        try {
            SimpleJson.parseObject(response.bodyText)
        } catch (error: Exception) {
            throw AuthException.Protocol("$context 无法解析：${error::class.simpleName}")
        }

    private fun Map<String, SimpleJson.Value>.requireString(name: String): String =
        string(name)?.takeIf { it.isNotBlank() } ?: throw AuthException.Protocol("响应缺少 $name")

    private fun Map<String, SimpleJson.Value>.requireInt(name: String): Int =
        int(name) ?: throw AuthException.Protocol("响应缺少 $name")

    private fun require2xx(response: HttpResponse, context: String) {
        if (response.statusCode !in 200..299) {
            val serverError = runCatching { SimpleJson.parseObject(response.bodyText) }.getOrNull()
            throw AuthException.Http(
                response.statusCode,
                serverError?.string("error_description") ?: "$context 失败（HTTP ${response.statusCode}）",
            )
        }
    }

    private fun authHeaders(contentType: String? = null, includeOrigin: Boolean = false): Map<String, String> =
        buildMap {
            put("User-Agent", config.userAgent)
            put("Accept", "application/json")
            if (contentType != null) put("Content-Type", contentType)
            if (includeOrigin) {
                // CloudNow 当前 token/refresh 路径只要求 nvfile Origin；不额外扩大请求变量。
                put("Origin", "https://nvfile")
            }
        }

    private fun formUrlEncoded(fields: List<Pair<String, String>>): ByteArray = fields.joinToString("&") { (name, value) ->
        "${encodeForm(name)}=${encodeForm(value)}"
    }.toByteArray(Charsets.UTF_8)

    private fun encodeForm(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {
        const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded; charset=UTF-8"
        const val DEVICE_CODE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
        const val CLIENT_TOKEN_GRANT = "urn:ietf:params:oauth:grant-type:client_token"
    }
}
