package dev.gfn.android.auth

import android.util.Log
import dev.gfn.auth.AuthSessionService
import dev.gfn.auth.AuthUser
import dev.gfn.auth.DeviceAuthorization
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface AuthUiState {
    data object Restoring : AuthUiState
    data object SignedOut : AuthUiState
    data object RequestingCode : AuthUiState
    data class AwaitingAuthorization(val authorization: DeviceAuthorization) : AuthUiState
    data class SignedIn(val user: AuthUser) : AuthUiState
    data class Error(val message: String) : AuthUiState
}

class AuthController(
    private val service: AuthSessionService,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<AuthUiState>(AuthUiState.Restoring)
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    private var loginJob: Job? = null
    private var restored = false
    private var generation = 0L

    fun restoreOnce() {
        if (restored) return
        restored = true
        val operation = generation
        scope.launch {
            Log.i(TAG, "开始恢复本地登录状态")
            _state.value = AuthUiState.Restoring
            try {
                val session = withContext(Dispatchers.IO) { service.restore() }
                if (operation != generation) return@launch
                _state.value = session?.let { AuthUiState.SignedIn(it.user) } ?: AuthUiState.SignedOut
                Log.i(TAG, if (session == null) "没有可恢复的登录状态" else "登录状态恢复成功")
            } catch (error: Exception) {
                if (operation != generation) return@launch
                Log.w(TAG, "恢复登录状态失败：${error::class.simpleName}")
                _state.value = AuthUiState.Error(error.userFacingMessage("恢复登录状态失败"))
            }
        }
    }

    fun startLogin() {
        loginJob?.cancel()
        generation += 1
        val operation = generation
        loginJob = scope.launch {
            _state.value = AuthUiState.RequestingCode
            Log.i(TAG, "开始 NVIDIA Device Flow")
            try {
                val authorization = withContext(Dispatchers.IO) { service.beginDeviceAuthorization() }
                if (operation != generation) return@launch
                _state.value = AuthUiState.AwaitingAuthorization(authorization)
                Log.i(TAG, "已获取 Device Flow 登录码，等待用户授权")

                val session = withContext(Dispatchers.IO) { service.completeDeviceAuthorization(authorization) }
                if (operation != generation) {
                    // 旧任务在取消后才完成时，清掉它可能刚写入的 token。
                    withContext(Dispatchers.IO) { service.signOut() }
                    return@launch
                }
                _state.value = AuthUiState.SignedIn(session.user)
                Log.i(TAG, "Device Flow 登录完成")
            } catch (cancelled: CancellationException) {
                Log.i(TAG, "Device Flow 已取消")
                throw cancelled
            } catch (error: Exception) {
                if (operation != generation) return@launch
                Log.w(TAG, "登录失败：${error::class.simpleName}")
                _state.value = AuthUiState.Error(error.userFacingMessage("登录失败"))
            }
        }
    }

    fun cancelLogin() {
        generation += 1
        loginJob?.cancel()
        loginJob = null
        _state.value = AuthUiState.SignedOut
        scope.launch {
            // 防止取消发生在阻塞 HTTP 返回前，先主动清理当前凭据；过期任务完成后还会再清一次。
            runCatching { withContext(Dispatchers.IO) { service.signOut() } }
        }
        Log.i(TAG, "用户取消登录")
    }

    fun signOut() {
        generation += 1
        loginJob?.cancel()
        loginJob = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { service.signOut() } }
            _state.value = AuthUiState.SignedOut
            Log.i(TAG, "已退出登录")
        }
    }

    private fun Throwable.userFacingMessage(prefix: String): String {
        val detail = when (this) {
            is IOException -> "网络连接失败"
            else -> message?.takeIf { it.isNotBlank() } ?: this::class.simpleName ?: "未知错误"
        }
        return "$prefix：$detail"
    }

    private companion object {
        const val TAG = "GfnAuth"
    }
}
