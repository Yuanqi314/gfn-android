package dev.gfn.auth

import java.time.Instant

data class LoginProvider(
    val idpId: String,
    val code: String,
    val displayName: String,
    val streamingServiceUrl: String,
    val priority: Int = 0,
)

data class DeviceAuthorization(
    val userCode: String,
    val deviceCode: String,
    val verificationUri: String,
    val verificationUriComplete: String? = null,
    val expiresInSeconds: Int,
    val pollIntervalSeconds: Int,
    val provider: LoginProvider? = null,
)

data class AuthTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    val idToken: String? = null,
    val expiresAt: Instant,
    val clientToken: String? = null,
    val clientTokenExpiresAt: Instant? = null,
) {
    fun isNearExpiry(now: Instant = Instant.now(), marginSeconds: Long = 600): Boolean =
        expiresAt.minusSeconds(marginSeconds).isBefore(now)
}

data class ClientToken(
    val value: String,
    val expiresAt: Instant,
)

data class AuthUser(
    val userId: String,
    val displayName: String,
    val email: String? = null,
    val membershipTier: String? = null,
)

data class AuthSession(
    val tokens: AuthTokens,
    val user: AuthUser,
    val provider: LoginProvider? = null,
)

interface AuthApi {
    suspend fun fetchProviders(): List<LoginProvider>
    suspend fun beginDeviceAuthorization(idpId: String? = null): DeviceAuthorization
    suspend fun pollDeviceAuthorization(authorization: DeviceAuthorization): AuthTokens
    suspend fun fetchClientToken(accessToken: String): ClientToken
    suspend fun refreshWithClientToken(clientToken: String, userId: String): AuthTokens
    suspend fun refresh(refreshToken: String): AuthTokens
    suspend fun userInfo(accessToken: String): AuthUser
}

interface TokenStore {
    suspend fun load(): AuthTokens?
    suspend fun save(tokens: AuthTokens)
    suspend fun clear()
}

data class AuthEndpointConfig(
    val deviceAuthorizeUrl: String,
    val tokenUrl: String,
    val clientTokenUrl: String,
    val userInfoUrl: String,
    val deviceFlowClientId: String,
    val mainClientId: String,
    val scopes: String,
    val userAgent: String,
    val serviceUrlsUrl: String = "https://pcs.geforcenow.com/v1/serviceUrls",
    val defaultIdpId: String? = null,
    val defaultStreamingServiceUrl: String = "https://prod.cloudmatchbeta.nvidiagrid.net/",
    val displayName: String,
)

sealed class AuthException(message: String) : Exception(message) {
    class Http(val statusCode: Int, message: String) : AuthException(message)
    class Protocol(message: String) : AuthException(message)
    data object DeviceFlowExpired : AuthException("登录码已过期，请重新获取")
    data object DeviceFlowDenied : AuthException("登录请求已被拒绝")
    data object RefreshRejected : AuthException("刷新令牌已失效，请重新登录")
}
