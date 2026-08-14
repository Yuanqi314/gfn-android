package dev.gfn.android.session

import android.util.Log
import dev.gfn.android.auth.AuthController
import dev.gfn.auth.AuthSession
import dev.gfn.cloudmatch.CloudMatchException
import dev.gfn.cloudmatch.GfnCloudMatchClient
import dev.gfn.core.model.EntitledResolution
import dev.gfn.core.model.GameDetail
import dev.gfn.core.model.GameVariant
import dev.gfn.core.model.RequestedColorMode
import dev.gfn.core.model.SessionClaimRequest
import dev.gfn.core.model.SessionCreateRequest
import dev.gfn.core.model.SessionInfo
import dev.gfn.core.model.SubscriptionInfo
import dev.gfn.identity.GfnLocale
import dev.gfn.session.CloudMatchPort
import dev.gfn.session.SessionOrchestrator
import dev.gfn.session.SessionReadinessState
import dev.gfn.session.SessionScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface SessionUiState {
    data object Idle : SessionUiState
    data class Creating(val gameTitle: String, val appStore: String) : SessionUiState
    data class Queued(val gameTitle: String, val appStore: String, val session: SessionInfo) : SessionUiState
    data class Preparing(val gameTitle: String, val appStore: String, val session: SessionInfo) : SessionUiState
    data class Ready(val gameTitle: String, val appStore: String, val session: SessionInfo) : SessionUiState
    data class Claiming(val gameTitle: String, val appStore: String, val sessionId: String) : SessionUiState
    data class Claimed(val gameTitle: String, val appStore: String, val session: SessionInfo) : SessionUiState
    data class Ending(val sessionId: String?) : SessionUiState
    data object Ended : SessionUiState
    data object Cancelled : SessionUiState
    data class Error(val message: String, val session: SessionInfo? = null) : SessionUiState
}

/**
 * 在 CloudMatchPort 边界统一处理 401：共享 AuthController 的 single-flight refresh，然后只重试一次。
 * 403 / API status / protocol error 不会被误判成 token 过期。
 */
private class AuthRefreshingCloudMatchPort(
    private val delegate: GfnCloudMatchClient,
    private val authController: AuthController,
) : CloudMatchPort {
    override suspend fun createSession(request: SessionCreateRequest): SessionInfo =
        retryUnauthorized(request.token) { token ->
            withContext(Dispatchers.IO) { delegate.createSession(request.copy(token = token)) }
        }

    override suspend fun pollSession(session: SessionInfo, token: String): SessionInfo =
        retryUnauthorized(token) { usable ->
            withContext(Dispatchers.IO) { delegate.pollSession(session, usable) }
        }

    override suspend fun claimSession(request: SessionClaimRequest): SessionInfo =
        retryUnauthorized(request.token) { token ->
            withContext(Dispatchers.IO) { delegate.claimSession(request.copy(token = token)) }
        }

    override suspend fun stopSession(session: SessionInfo, token: String) {
        retryUnauthorized(token) { usable ->
            withContext(Dispatchers.IO) { delegate.stopSession(session, usable) }
            Unit
        }
    }

    private suspend fun <T> retryUnauthorized(
        rejectedToken: String,
        block: suspend (String) -> T,
    ): T {
        return try {
            block(rejectedToken)
        } catch (error: CloudMatchException.Unauthorized) {
            val refreshed = authController.refreshForApi(rejectedToken) ?: throw error
            block(refreshed.gfnToken)
        }
    }
}

class GfnSessionController(
    authController: AuthController,
    cloudMatchClient: GfnCloudMatchClient,
    private val recordStore: AndroidSessionRecordStore,
    private val scope: CoroutineScope,
) {
    private val port: CloudMatchPort = AuthRefreshingCloudMatchPort(cloudMatchClient, authController)
    private val orchestrator = SessionOrchestrator(
        client = port,
        scheduler = SessionScheduler { milliseconds -> delay(milliseconds) },
        pollIntervalMillis = 2_000,
    )
    private val authController = authController

    private val _state = MutableStateFlow<SessionUiState>(SessionUiState.Idle)
    val state: StateFlow<SessionUiState> = _state.asStateFlow()

    private val _resumeRecord = MutableStateFlow<PersistedSessionRecord?>(null)
    val resumeRecord: StateFlow<PersistedSessionRecord?> = _resumeRecord.asStateFlow()

    private var restored = false
    private var operationGeneration = 0L
    private var activeJob: Job? = null
    private var reconcileJob: Job? = null
    private var activeGame: ActiveGame? = null

    private data class ActiveGame(
        val appId: String,
        val title: String,
        val store: String,
    )

    private class QueueAdUnsupportedException(val session: SessionInfo) : Exception(
        "服务端要求 Queue Ad；v4 只验证 Session Lifecycle，尚未接广告播放器。",
    )

    fun restoreResumeRecordOnce() {
        if (restored) return
        restored = true
        scope.launch {
            _resumeRecord.value = withContext(Dispatchers.IO) { recordStore.load() }
        }
    }

    fun startGame(
        game: GameDetail,
        variant: GameVariant,
        subscription: SubscriptionInfo,
    ) {
        if (activeJob?.isActive == true) return
        if (_resumeRecord.value != null || currentSession() != null) {
            _state.value = SessionUiState.Error("已有可恢复/活动 Session，请先结束或清除后再创建新会话。", currentSession())
            return
        }
        val auth = authController.currentSession()
        if (auth == null) {
            _state.value = SessionUiState.Error("请先登录 GeForce NOW。")
            return
        }
        val base = auth.provider?.streamingServiceUrl?.takeIf { it.isNotBlank() }
        if (base == null) {
            _state.value = SessionUiState.Error("当前登录态缺少 streamingServiceUrl；请刷新登录 Provider 后重试。")
            return
        }

        operationGeneration += 1
        val operation = operationGeneration
        val attempt = orchestrator.beginAttempt()
        val active = ActiveGame(variant.launchAppId, game.title, variant.appStore)
        activeGame = active
        val preset = selectV4Preset(subscription)
        _state.value = SessionUiState.Creating(active.title, active.store)

        activeJob = scope.launch {
            try {
                val request = SessionCreateRequest(
                    appId = active.appId,
                    internalTitle = null,
                    token = auth.gfnToken,
                    streamingBaseUrl = base,
                    width = preset.width,
                    height = preset.height,
                    fps = preset.fps.coerceAtMost(60),
                    keyboardLayout = GfnLocale.keyboardLayoutCode(),
                    gameLanguage = GfnLocale.nvidiaCode(),
                    requestedColorMode = RequestedColorMode.CompatibilitySdr,
                    audioChannels = 2,
                    accountLinked = true,
                    persistInGameSettings = false,
                    appLaunchMode = 1,
                )
                var session = orchestrator.createSession(request, attempt)
                if (!isCurrent(operation)) return@launch
                ensureQueueAdsSupported(session)
                persist(session, active)
                applyReadiness(active, session, readinessOf(session))

                session = orchestrator.waitUntilReady(
                    initialSession = session,
                    token = request.token,
                    attempt = attempt,
                ) { updated, readiness ->
                    if (!isCurrent(operation)) return@waitUntilReady
                    ensureQueueAdsSupported(updated)
                    persistAsync(updated, active)
                    applyReadiness(active, updated, readiness)
                }
                if (!isCurrent(operation)) return@launch
                persist(session, active)
                _state.value = SessionUiState.Ready(active.title, active.store, session)
                Log.i(TAG, "CloudMatch Session Ready：status=${session.status}, queue=${session.queuePosition}")
            } catch (unsupported: QueueAdUnsupportedException) {
                if (isCurrent(operation)) handleUnsupportedQueueAd(unsupported, active)
            } catch (cancelled: CancellationException) {
                if (isCurrent(operation)) _state.value = SessionUiState.Cancelled
                throw cancelled
            } catch (error: Exception) {
                if (!isCurrent(operation)) return@launch
                Log.w(TAG, "Session 创建/排队失败：${error::class.simpleName}")
                _state.value = SessionUiState.Error(error.userFacing("Session 创建/排队失败"), currentSession())
            } finally {
                if (isCurrent(operation)) activeJob = null
            }
        }
    }

    /**
     * 对当前 Ready session 执行 RESUME/Claim。v4 用它单独验证 claim endpoint；不会连接 WebRTC。
     */
    fun claimCurrent() {
        val record = _resumeRecord.value ?: currentSession()?.let { session ->
            activeGame?.let { makeRecord(session, it) }
        }
        if (record == null) {
            _state.value = SessionUiState.Error("没有可 Claim/Resume 的 Session。")
            return
        }
        claimRecord(record)
    }

    fun resumePersisted() {
        val record = _resumeRecord.value
        if (record == null) {
            _state.value = SessionUiState.Error("没有持久化的可恢复 Session。")
            return
        }
        claimRecord(record)
    }

    private fun claimRecord(record: PersistedSessionRecord) {
        if (activeJob?.isActive == true) return
        val auth = authController.currentSession()
        if (auth == null) {
            _state.value = SessionUiState.Error("恢复 Session 前必须先恢复登录态。")
            return
        }
        operationGeneration += 1
        val operation = operationGeneration
        val attempt = orchestrator.beginAttempt()
        val active = ActiveGame(record.appId, record.gameTitle, record.appStore)
        activeGame = active
        _state.value = SessionUiState.Claiming(active.title, active.store, record.sessionId)

        activeJob = scope.launch {
            try {
                var session = orchestrator.claimSession(
                    SessionClaimRequest(
                        session = record.toSessionInfo(),
                        appId = record.appId,
                        token = auth.gfnToken,
                        baseUrl = record.streamingBaseUrl,
                        keyboardLayout = GfnLocale.keyboardLayoutCode(),
                        gameLanguage = GfnLocale.nvidiaCode(),
                        audioChannels = 2,
                        persistInGameSettings = false,
                        appLaunchMode = 1,
                    ),
                    attempt,
                )
                if (!isCurrent(operation)) return@launch
                ensureQueueAdsSupported(session)
                persist(session, active)
                session = orchestrator.waitUntilReady(
                    initialSession = session,
                    token = auth.gfnToken,
                    attempt = attempt,
                    requiredReadyResponses = 1,
                    setupTimeoutMillis = 60_000,
                ) { updated, readiness ->
                    if (!isCurrent(operation)) return@waitUntilReady
                    ensureQueueAdsSupported(updated)
                    persistAsync(updated, active)
                    applyReadiness(active, updated, readiness)
                }
                if (!isCurrent(operation)) return@launch
                persist(session, active)
                _state.value = SessionUiState.Claimed(active.title, active.store, session)
                Log.i(TAG, "CloudMatch Claim/Resume 成功：status=${session.status}")
            } catch (cancelled: CancellationException) {
                if (isCurrent(operation)) _state.value = SessionUiState.Cancelled
                throw cancelled
            } catch (error: Exception) {
                if (!isCurrent(operation)) return@launch
                Log.w(TAG, "Claim/Resume 失败：${error::class.simpleName}")
                _state.value = SessionUiState.Error(error.userFacing("Claim/Resume 失败"), currentSession())
            } finally {
                if (isCurrent(operation)) activeJob = null
            }
        }
    }

    /** 用户取消/结束：先使旧 generation 失效，再 best-effort DELETE owned session。 */
    fun endSession(cancelledByUser: Boolean = false) {
        operationGeneration += 1
        val operation = operationGeneration
        orchestrator.cancelAttempt()
        val current = currentSession()
        val persisted = _resumeRecord.value
        _state.value = SessionUiState.Ending(current?.sessionId ?: persisted?.sessionId)

        // 不直接 cancel 旧阻塞 HTTP Job：让 create 的迟到结果回到 orchestrator，触发 stale cleanup。
        scope.launch {
            var failure: Throwable? = null
            try {
                if (current != null) {
                    orchestrator.stopOwnedSession()
                } else if (persisted != null) {
                    val auth = authController.currentSession()
                    if (auth != null) {
                        port.stopSession(persisted.toSessionInfo(), auth.gfnToken)
                    }
                }
            } catch (error: Exception) {
                failure = error
            }
            withContext(Dispatchers.IO) { recordStore.clear() }
            _resumeRecord.value = null
            activeGame = null
            activeJob = null
            if (!isCurrent(operation)) return@launch
            _state.value = when {
                failure != null -> SessionUiState.Error(failure.userFacing("结束 Session 失败"), current)
                cancelledByUser -> SessionUiState.Cancelled
                else -> SessionUiState.Ended
            }
        }
    }

    /**
     * Server-originated terminal event. Do not DELETE again: the server already ended this session.
     * Session identity + terminal idempotence protect a new session from stale control-channel events.
     */
    fun onServerSessionEnded(sessionId: String, source: String) {
        val knownId = currentSession()?.sessionId ?: _resumeRecord.value?.sessionId
        if (knownId != sessionId) {
            Log.w(TAG, "Ignore stale server-end event source=$source")
            return
        }
        when (_state.value) {
            SessionUiState.Ended, SessionUiState.Cancelled -> return
            else -> Unit
        }

        operationGeneration += 1
        orchestrator.cancelAttempt()
        orchestrator.detachOwnedSession()
        Log.i(TAG, "Server ended current session source=$source")
        _state.value = SessionUiState.Ended

        val job = activeJob
        if (job == null || !job.isActive) {
            activeJob = null
        } else {
            job.invokeOnCompletion {
                scope.launch { if (activeJob === job) activeJob = null }
            }
        }
        activeGame = null
        scope.launch {
            withContext(Dispatchers.IO) { recordStore.clear() }
            _resumeRecord.value = null
        }
    }

    /**
     * WebRTC/control transport 异常断开后的保守服务端复核。
     * 只把 HTTP 404/410 当作“当前 Session 已不存在”的终态证据；其他返回不猜 NVIDIA 语义。
     */
    fun reconcileAfterStreamDisconnect(sessionId: String, source: String) {
        if (reconcileJob?.isActive == true) {
            Log.i(TAG, "Session reconcile coalesced source=$source")
            return
        }
        val current = currentSession()
        if (current == null || current.sessionId != sessionId) {
            Log.i(TAG, "Session reconcile skipped stale source=$source")
            return
        }
        val auth = authController.currentSession()
        if (auth == null) {
            Log.w(TAG, "Session reconcile skipped: auth unavailable source=$source")
            return
        }

        val job = scope.launch {
            try {
                val polled = port.pollSession(current, auth.gfnToken)
                if (currentSession()?.sessionId != sessionId) return@launch
                Log.i(
                    TAG,
                    "Session reconcile active source=$source status=${polled.status} queue=${polled.queuePosition}",
                )
            } catch (error: CloudMatchException.Http) {
                if (error.code == 404 || error.code == 410) {
                    Log.i(TAG, "Session reconcile terminal source=$source http=${error.code}")
                    onServerSessionEnded(sessionId, "reconcile.http.${error.code}")
                } else {
                    Log.w(TAG, "Session reconcile HTTP source=$source code=${error.code}")
                }
            } catch (error: CloudMatchException.ApiStatus) {
                Log.w(TAG, "Session reconcile API source=$source code=${error.code}")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.w(TAG, "Session reconcile failed source=$source error=${error::class.simpleName}")
            }
        }
        reconcileJob = job
        job.invokeOnCompletion {
            scope.launch {
                if (reconcileJob === job) reconcileJob = null
            }
        }
    }

    /** 仅删除本地 resume 记录；不会声称服务端 Session 已被结束。 */
    fun forgetResumeRecord() {
        scope.launch {
            withContext(Dispatchers.IO) { recordStore.clear() }
            _resumeRecord.value = null
            if (currentSession() == null) _state.value = SessionUiState.Idle
        }
    }

    private fun applyReadiness(
        active: ActiveGame,
        session: SessionInfo,
        readiness: SessionReadinessState,
    ) {
        _state.value = when (readiness) {
            is SessionReadinessState.InQueue -> SessionUiState.Queued(active.title, active.store, session)
            is SessionReadinessState.Preparing -> SessionUiState.Preparing(active.title, active.store, session)
            SessionReadinessState.Ready -> SessionUiState.Ready(active.title, active.store, session)
            SessionReadinessState.TimedOut -> SessionUiState.Error("Session provisioning 超时。", session)
        }
    }

    private fun ensureQueueAdsSupported(session: SessionInfo) {
        val ad = session.adRequirement ?: return
        if (ad.required || ad.queuePaused == true) {
            throw QueueAdUnsupportedException(session)
        }
    }

    private suspend fun handleUnsupportedQueueAd(
        error: QueueAdUnsupportedException,
        active: ActiveGame,
    ) {
        var cleanupError: Throwable? = null
        try {
            orchestrator.stopOwnedSession()
        } catch (failure: Throwable) {
            cleanupError = failure
        }

        if (cleanupError == null) {
            withContext(Dispatchers.IO) { recordStore.clear() }
            _resumeRecord.value = null
        } else {
            // DELETE 失败时保留 resume 信息，让用户仍能手工再次 Cleanup。
            persist(error.session, active)
        }
        _state.value = SessionUiState.Error(
            message = buildString {
                append(error.message)
                error.session.adRequirement?.message?.takeIf { it.isNotBlank() }?.let { append(" ").append(it) }
                if (cleanupError == null) {
                    append(" 已主动结束该 Session，避免留下孤儿会话。")
                } else {
                    append(" 自动 Cleanup 失败，请使用 End / Cleanup 重试。")
                }
            },
            session = if (cleanupError == null) null else error.session,
        )
    }

    private fun readinessOf(session: SessionInfo): SessionReadinessState = when {
        session.isInQueue -> SessionReadinessState.InQueue(session.queuePosition)
        session.isReadyStatus -> SessionReadinessState.Preparing(session.seatSetupStep)
        else -> SessionReadinessState.Preparing(session.seatSetupStep)
    }

    private fun currentSession(): SessionInfo? = when (val value = _state.value) {
        is SessionUiState.Queued -> value.session
        is SessionUiState.Preparing -> value.session
        is SessionUiState.Ready -> value.session
        is SessionUiState.Claimed -> value.session
        is SessionUiState.Error -> value.session
        else -> orchestrator.currentOwnedSession()
    }

    private suspend fun persist(session: SessionInfo, active: ActiveGame) {
        val record = makeRecord(session, active)
        withContext(Dispatchers.IO) { recordStore.save(record) }
        _resumeRecord.value = record
    }

    private fun persistAsync(session: SessionInfo, active: ActiveGame) {
        val record = makeRecord(session, active)
        _resumeRecord.value = record
        scope.launch(Dispatchers.IO) { recordStore.save(record) }
    }

    private fun makeRecord(session: SessionInfo, active: ActiveGame): PersistedSessionRecord =
        PersistedSessionRecord(
            sessionId = session.sessionId,
            appId = active.appId,
            gameTitle = active.title,
            appStore = active.store,
            status = session.status,
            serverIp = session.serverIp,
            streamingBaseUrl = session.streamingBaseUrl,
            routingZoneUrl = session.routingZoneUrl,
            clientId = session.clientId,
            deviceId = session.deviceId,
            createdAtEpochMillis = System.currentTimeMillis(),
        )

    private fun selectV4Preset(subscription: SubscriptionInfo): EntitledResolution {
        val entitled = subscription.entitledResolutions
        val safe = entitled.filter { it.width <= 1920 && it.height <= 1080 }
            .maxWithOrNull(compareBy<EntitledResolution>({ it.width * it.height }, { it.fps }))
        val selected = safe ?: entitled.minWithOrNull(compareBy({ it.width * it.height }, { it.fps }))
        return selected?.copy(fps = selected.fps.coerceAtMost(60))
            ?: EntitledResolution(width = 1280, height = 720, fps = 60)
    }

    private fun isCurrent(operation: Long): Boolean = operation == operationGeneration

    private fun Throwable.userFacing(prefix: String): String =
        "$prefix：${message?.takeIf { it.isNotBlank() } ?: this::class.simpleName ?: "未知错误"}"

    private companion object {
        const val TAG = "GfnSession"
    }
}
