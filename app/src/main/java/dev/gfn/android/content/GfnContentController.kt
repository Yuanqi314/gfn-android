package dev.gfn.android.content

import android.util.Log
import dev.gfn.account.GfnAccountClient
import dev.gfn.account.GfnAccountContext
import dev.gfn.account.GfnAccountException
import dev.gfn.android.auth.AuthController
import dev.gfn.android.auth.AuthUiState
import dev.gfn.auth.AuthSession
import dev.gfn.auth.NvidiaAuthReferenceDefaults
import dev.gfn.core.model.GameDetail
import dev.gfn.core.model.GameSummary
import dev.gfn.core.model.SubscriptionInfo
import dev.gfn.games.GfnGamesClient
import dev.gfn.games.GfnGamesContext
import dev.gfn.games.GfnGamesException
import dev.gfn.identity.GfnLocale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ContentUiState {
    data object WaitingForLogin : ContentUiState
    data object Loading : ContentUiState
    data class Ready(
        val vpcId: String,
        val subscription: SubscriptionInfo,
        val library: List<GameSummary>,
        val catalog: List<GameSummary>,
    ) : ContentUiState
    data class Error(val message: String) : ContentUiState
}

sealed interface GameDetailUiState {
    data object Idle : GameDetailUiState
    data object Loading : GameDetailUiState
    data class Ready(val detail: GameDetail) : GameDetailUiState
    data class Error(val message: String) : GameDetailUiState
}

class GfnContentController(
    private val authController: AuthController,
    private val accountClient: GfnAccountClient,
    private val gamesClient: GfnGamesClient,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ContentUiState>(ContentUiState.WaitingForLogin)
    val state: StateFlow<ContentUiState> = _state.asStateFlow()

    private val _searchResults = MutableStateFlow<List<GameSummary>>(emptyList())
    val searchResults: StateFlow<List<GameSummary>> = _searchResults.asStateFlow()

    private val _detailState = MutableStateFlow<GameDetailUiState>(GameDetailUiState.Idle)
    val detailState: StateFlow<GameDetailUiState> = _detailState.asStateFlow()

    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private var generation = 0L
    private var loadedUserId: String? = null

    fun onAuthStateChanged(authState: AuthUiState) {
        when (authState) {
            is AuthUiState.SignedIn -> {
                val userId = authState.session.user.userId
                if (loadedUserId != userId || _state.value is ContentUiState.WaitingForLogin) {
                    load(authState.session)
                }
            }
            AuthUiState.Restoring, AuthUiState.RequestingCode, is AuthUiState.AwaitingAuthorization -> Unit
            AuthUiState.SignedOut, is AuthUiState.Error -> clearForLogout()
        }
    }

    fun refresh() {
        authController.currentSession()?.let(::load)
    }

    fun search(query: String) {
        val normalized = query.trim()
        if (normalized.isEmpty()) {
            searchJob?.cancel()
            _searchResults.value = emptyList()
            return
        }
        val ready = _state.value as? ContentUiState.Ready ?: return
        val session = authController.currentSession() ?: return
        searchJob?.cancel()
        val operation = generation
        searchJob = scope.launch {
            runCatching {
                withAuthRetry(session) { usable ->
                    withContext(Dispatchers.IO) {
                        gamesClient.search(gamesContext(usable, ready.vpcId), normalized)
                    }
                }
            }.onSuccess { results ->
                if (operation == generation) _searchResults.value = results
            }.onFailure { error ->
                if (error !is CancellationException) Log.w(TAG, "搜索失败：${error::class.simpleName}")
            }
        }
    }

    fun openGame(appId: String) {
        val ready = _state.value as? ContentUiState.Ready ?: return
        val session = authController.currentSession() ?: return
        val base = (ready.library + ready.catalog).firstOrNull { it.appId == appId }
        detailJob?.cancel()
        val operation = generation
        _detailState.value = GameDetailUiState.Loading
        detailJob = scope.launch {
            try {
                val fetched = withAuthRetry(session) { usable ->
                    withContext(Dispatchers.IO) {
                        gamesClient.fetchGameDetail(gamesContext(usable, ready.vpcId), appId)
                    }
                }
                val detail = if (base == null) fetched else fetched.copy(
                    supportsHdr = base.supportsHdr,
                    supportsRtx = base.supportsRtx,
                    supportsReflex = base.supportsReflex,
                    isInLibrary = base.isInLibrary,
                    variants = fetched.variants.ifEmpty { base.variants },
                )
                if (operation == generation) _detailState.value = GameDetailUiState.Ready(detail)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (operation == generation) {
                    _detailState.value = GameDetailUiState.Error(error.userFacing("获取游戏详情失败"))
                }
            }
        }
    }

    fun closeGameDetail() {
        detailJob?.cancel()
        detailJob = null
        _detailState.value = GameDetailUiState.Idle
    }

    private fun load(session: AuthSession) {
        generation += 1
        val operation = generation
        loadJob?.cancel()
        searchJob?.cancel()
        detailJob?.cancel()
        _searchResults.value = emptyList()
        _detailState.value = GameDetailUiState.Idle
        _state.value = ContentUiState.Loading
        loadedUserId = session.user.userId
        loadJob = scope.launch {
            try {
                val result = withAuthRetry(session) { usable -> loadOnce(usable) }
                if (operation != generation) return@launch
                loadedUserId = authController.currentSession()?.user?.userId ?: session.user.userId
                _state.value = result
                Log.i(TAG, "GFN 内容加载完成：library=${result.library.size}, catalog=${result.catalog.size}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (operation != generation) return@launch
                Log.w(TAG, "GFN 内容加载失败：${error::class.simpleName}")
                _state.value = ContentUiState.Error(error.userFacing("加载 GFN 内容失败"))
            }
        }
    }

    private suspend fun loadOnce(session: AuthSession): ContentUiState.Ready = withContext(Dispatchers.IO) {
        val accountContext = accountContext(session)
        val vpcId = accountClient.fetchVpcId(accountContext)
        coroutineScope {
            val subscription = async {
                accountClient.fetchSubscription(accountContext, vpcId, localeCode())
            }
            val gamesContext = gamesContext(session, vpcId)
            val library = async { gamesClient.fetchLibrary(gamesContext) }
            val catalog = async { gamesClient.fetchCatalog(gamesContext) }
            ContentUiState.Ready(
                vpcId = vpcId,
                subscription = subscription.await(),
                library = library.await(),
                catalog = catalog.await(),
            )
        }
    }

    private suspend fun <T> withAuthRetry(initial: AuthSession, block: suspend (AuthSession) -> T): T {
        try {
            return block(initial)
        } catch (error: Exception) {
            if (!error.isUnauthorized()) throw error
            Log.i(TAG, "GFN 内容 API 拒绝凭据，执行一次认证 refresh")
            val refreshed = authController.refreshForApi() ?: throw error
            return block(refreshed)
        }
    }

    private fun accountContext(session: AuthSession): GfnAccountContext = GfnAccountContext(
        token = session.gfnToken,
        userId = session.user.userId,
        streamingServiceUrl = session.provider?.streamingServiceUrl
            ?: NvidiaAuthReferenceDefaults.config.defaultStreamingServiceUrl,
    )

    private fun gamesContext(session: AuthSession, vpcId: String): GfnGamesContext = GfnGamesContext(
        token = session.gfnToken,
        vpcId = vpcId,
        localeCode = localeCode(),
    )

    private fun localeCode(): String = GfnLocale.nvidiaCode()

    private fun clearForLogout() {
        generation += 1
        loadJob?.cancel(); loadJob = null
        searchJob?.cancel(); searchJob = null
        detailJob?.cancel(); detailJob = null
        loadedUserId = null
        _state.value = ContentUiState.WaitingForLogin
        _searchResults.value = emptyList()
        _detailState.value = GameDetailUiState.Idle
    }

    private fun Throwable.isUnauthorized(): Boolean =
        this is GfnAccountException.Unauthorized || this is GfnGamesException.Unauthorized

    private fun Throwable.userFacing(prefix: String): String =
        "$prefix：${message?.takeIf { it.isNotBlank() } ?: this::class.simpleName ?: "未知错误"}"

    private companion object { const val TAG = "GfnContent" }
}
