package dev.gfn.auth

import java.time.Instant

/**
 * 登录生命周期服务。
 *
 * UI 只关心“未登录 / 等待授权 / 已登录”，不会直接管理 refresh token 或 client_token。
 */
class AuthSessionService(
    private val api: AuthApi,
    private val tokenStore: TokenStore,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun restore(): AuthSession? {
        val stored = tokenStore.load() ?: return null
        var usable = if (stored.isNearExpiry(now())) {
            refreshStoredTokens(stored) ?: return null
        } else {
            stored
        }

        tokenStore.save(usable)
        val user = try {
            api.userInfo(usable.accessToken)
        } catch (error: AuthException.Http) {
            if (error.statusCode != 401 || usable.refreshToken == null) throw error
            usable = refreshStoredTokens(usable) ?: return null
            tokenStore.save(usable)
            api.userInfo(usable.accessToken)
        }
        return AuthSession(tokens = usable, user = user)
    }

    suspend fun beginDeviceAuthorization(idpId: String? = null): DeviceAuthorization {
        if (idpId != null) return api.beginDeviceAuthorization(idpId)
        val provider = runCatching { api.fetchProviders().firstOrNull() }.getOrNull()
        return api.beginDeviceAuthorization(provider?.idpId).copy(provider = provider)
    }

    suspend fun completeDeviceAuthorization(authorization: DeviceAuthorization): AuthSession {
        val deviceFlowTokens = api.pollDeviceAuthorization(authorization)
        val user = api.userInfo(deviceFlowTokens.accessToken)
        var finalTokens = deviceFlowTokens

        // CloudNow 当前成功路径：先拿 client_token，再将 Device Flow token re-bind 到主 client ID。
        // 这一步失败不阻断“登录”本身，但会保留 Device Flow token，后续 GFN API 可明确诊断。
        val firstClientToken = runCatching { api.fetchClientToken(deviceFlowTokens.accessToken) }.getOrNull()
        if (firstClientToken != null) {
            finalTokens = finalTokens.copy(
                clientToken = firstClientToken.value,
                clientTokenExpiresAt = firstClientToken.expiresAt,
            )
            val rebound = runCatching {
                api.refreshWithClientToken(firstClientToken.value, user.userId)
            }.getOrNull()
            if (rebound != null) {
                finalTokens = rebound.copy(
                    refreshToken = rebound.refreshToken ?: deviceFlowTokens.refreshToken,
                    idToken = rebound.idToken ?: deviceFlowTokens.idToken,
                    clientToken = firstClientToken.value,
                    clientTokenExpiresAt = firstClientToken.expiresAt,
                )
                val reboundClientToken = runCatching { api.fetchClientToken(finalTokens.accessToken) }.getOrNull()
                if (reboundClientToken != null) {
                    finalTokens = finalTokens.copy(
                        clientToken = reboundClientToken.value,
                        clientTokenExpiresAt = reboundClientToken.expiresAt,
                    )
                }
            }
        }

        tokenStore.save(finalTokens)
        return AuthSession(finalTokens, user, authorization.provider)
    }

    suspend fun signOut() {
        tokenStore.clear()
    }

    private suspend fun refreshStoredTokens(stored: AuthTokens): AuthTokens? {
        val refreshToken = stored.refreshToken ?: run {
            tokenStore.clear()
            return null
        }
        return try {
            api.refresh(refreshToken).let { refreshed ->
                refreshed.copy(
                    refreshToken = refreshed.refreshToken ?: refreshToken,
                    idToken = refreshed.idToken ?: stored.idToken,
                    clientToken = refreshed.clientToken ?: stored.clientToken,
                    clientTokenExpiresAt = refreshed.clientTokenExpiresAt ?: stored.clientTokenExpiresAt,
                )
            }
        } catch (_: AuthException.RefreshRejected) {
            tokenStore.clear()
            null
        }
    }
}
