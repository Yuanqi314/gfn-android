package dev.gfn.auth

import java.time.Instant

data class DeviceAuthorization(
    val userCode: String,
    val deviceCode: String,
    val verificationUri: String,
    val verificationUriComplete: String? = null,
    val expiresInSeconds: Int,
    val pollIntervalSeconds: Int,
)

data class AuthTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    val idToken: String? = null,
    val expiresAt: Instant,
    val clientToken: String? = null,
) {
    fun isNearExpiry(now: Instant = Instant.now(), marginSeconds: Long = 600): Boolean =
        expiresAt.minusSeconds(marginSeconds).isBefore(now)
}

data class AuthUser(
    val userId: String,
    val displayName: String,
    val email: String? = null,
    val membershipTier: String? = null,
)

interface AuthApi {
    suspend fun beginDeviceAuthorization(): DeviceAuthorization
    suspend fun pollDeviceAuthorization(deviceCode: String): AuthTokens
    suspend fun refresh(refreshToken: String): AuthTokens
    suspend fun userInfo(accessToken: String): AuthUser
}

interface TokenStore {
    suspend fun load(): AuthTokens?
    suspend fun save(tokens: AuthTokens)
    suspend fun clear()
}

/**
 * Endpoint/client-id values are deliberately absent from the baseline. They will be added only
 * after current official behavior and CloudNow's implementation are reconciled into fixtures.
 */
data class AuthEndpointConfig(
    val deviceAuthorizeUrl: String,
    val tokenUrl: String,
    val userInfoUrl: String,
    val clientId: String,
    val scopes: String,
)
