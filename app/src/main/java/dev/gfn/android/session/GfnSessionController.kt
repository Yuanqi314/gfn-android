package dev.gfn.android.session

import android.util.Log
import dev.gfn.android.auth.AuthController
import dev.gfn.cloudmatch.CloudMatchException
import dev.gfn.cloudmatch.GfnCloudMatchClient
import dev.gfn.core.model.GameDetail
import dev.gfn.core.model.GameVariant
import dev.gfn.core.model.SessionClaimRequest
import dev.gfn.core.model.SessionCreateRequest
import dev.gfn.core.model.SessionInfo
import dev.gfn.core.model.SubscriptionInfo
import dev.gfn.android.settings.GfnStreamSettingsController
import dev.gfn.android.settings.ResolvedLaunchProfile
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
    private val streamSettingsController: GfnStreamSettingsController,
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

    private val _activeLaunchProfile = MutableStateFlow<ResolvedLaunchProfile?>(null)
    val activeLaunchProfile: StateFlow<ResolvedLaunchProfile?> = _activeLaunchProfile.asStateFlow()

    private var restored = false
    private var operationGeneration = 0L
    private var activeJob: Job? = null
    private var reconcileJob: Job? = null
    private var reconnectJob: Job? = null
    private var activeGame: ActiveGame? = null

    private data class ActiveGame(
        val appId: String,
        val title: String,
        val store: String,
        val profile: ResolvedLaunchProfile,
    )

    private class QueueAdUnsupportedException(val session: SessionInfo) : Exception(
        "服务端要求 Queue Ad；当前 Android 客户端尚未接广告播放器。",
    )

    private class SameSessionReconnectViolation(
        val expectedSessionId: String,
        val actualSession: SessionInfo,
    ) : Exception(
        "Reconnect 必须保持原 Session ID：expected=$expectedSessionId actual=${actualSession.sessionId}",
    )

    fun restoreResumeRecordOnce() {
        if (restored) return
        restored = true
        scope.launch {
            val record = withContext(Dispatchers.IO) { recordStore.load() }
            _resumeRecord.value = record
            _activeLaunchProfile.value = record?.launchProfile
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

        val launchProfile = try {
            streamSettingsController.resolveForNewSession(
                subscription = subscription,
                autoKeyboardLayout = GfnLocale.keyboardLayoutCode(),
                gameLanguage = GfnLocale.nvidiaCode(),
            )
        } catch (error: IllegalArgumentException) {
            _state.value = SessionUiState.Error(
                "串流设置无法解析：${error.message ?: error::class.simpleName}",
            )
            return
        }

        operationGeneration += 1
        val operation = operationGeneration
        val attempt = orchestrator.beginAttempt()
        val active = ActiveGame(
            appId = variant.launchAppId,
            title = game.title,
            store = variant.appStore,
            profile = launchProfile,
        )
        activeGame = active
        _activeLaunchProfile.value = launchProfile
        _state.value = SessionUiState.Creating(active.title, active.store)
        Log.i(PROFILE_TAG, "RESOLVED newSession=true ${launchProfile.summary} entitlementVerified=${launchProfile.entitlementVerified}")

        activeJob = scope.launch {
            try {
                val streamConfig = active.profile.streamConfig
                val request = SessionCreateRequest(
                    appId = active.appId,
                    internalTitle = null,
                    token = auth.gfnToken,
                    streamingBaseUrl = base,
                    width = streamConfig.width,
                    height = streamConfig.height,
                    fps = streamConfig.fps,
                    keyboardLayout = active.profile.keyboardLayout,
                    gameLanguage = active.profile.gameLanguage,
                    requestedColorMode = streamConfig.colorMode,
                    audioChannels = streamConfig.audioChannels,
                    accountLinked = true,
                    persistInGameSettings = false,
                    appLaunchMode = 1,
                )
                Log.i(
                    PROFILE_TAG,
                    "CREATE ${active.profile.summary} cloudMatchBitDepth=" +
                        (if (streamConfig.colorMode == dev.gfn.core.model.RequestedColorMode.PreferSdr10) 1 else 0) +
                        " sdrHdrMode=0 hdr=false",
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
                val remainingSession = currentSession()
                if (remainingSession == null) {
                    activeGame = null
                    _activeLaunchProfile.value = null
                }
                _state.value = SessionUiState.Error(error.userFacing("Session 创建/排队失败"), remainingSession)
            } finally {
                if (isCurrent(operation)) activeJob = null
            }
        }
    }

    /** 对当前 Ready Session 执行 RESUME/Claim；WebRTC 仍由 GfnStreamingController 单独连接。 */
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
        val launchProfile = record.launchProfile
        if (launchProfile == null) {
            _state.value = SessionUiState.Error(
                "该 Session 来自 v5.1.9 或更早版本，缺少不可变 ResolvedLaunchProfile；" +
                    "为避免 CREATE/CLAIM/WebRTC 参数漂移，请先 End / Cleanup，再新建 Session。",
                record.toSessionInfo(),
            )
            return
        }
        operationGeneration += 1
        val operation = operationGeneration
        val attempt = orchestrator.beginAttempt()
        val active = ActiveGame(
            appId = record.appId,
            title = record.gameTitle,
            store = record.appStore,
            profile = launchProfile,
        )
        activeGame = active
        _activeLaunchProfile.value = launchProfile
        _state.value = SessionUiState.Claiming(active.title, active.store, record.sessionId)

        activeJob = scope.launch {
            try {
                val claimRequest = SessionClaimRequest(
                    session = record.toSessionInfo(),
                    appId = record.appId,
                    token = auth.gfnToken,
                    baseUrl = record.streamingBaseUrl,
                    keyboardLayout = active.profile.keyboardLayout,
                    gameLanguage = active.profile.gameLanguage,
                    audioChannels = active.profile.streamConfig.audioChannels,
                    persistInGameSettings = false,
                    appLaunchMode = 1,
                )
                Log.i(PROFILE_TAG, "CLAIM sessionId=${record.sessionId} ${active.profile.summary}")
                var session = orchestrator.claimSession(
                    claimRequest,
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
        val owned = orchestrator.currentOwnedSession()
        val persisted = _resumeRecord.value
        _state.value = SessionUiState.Ending(current?.sessionId ?: persisted?.sessionId)

        // 不直接 cancel 旧阻塞 HTTP Job：让 create 的迟到结果回到 orchestrator，触发 stale cleanup。
        // 恢复自磁盘、但尚未被 orchestrator claim 的 Session 不属于 ownedSession；此时必须直接
        // DELETE persisted Session，不能因为 Error state 带了 SessionInfo 就误调用 stopOwnedSession().
        scope.launch {
            var failure: Throwable? = null
            try {
                if (owned != null) {
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
            _activeLaunchProfile.value = null
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
        _activeLaunchProfile.value = null
        scope.launch {
            withContext(Dispatchers.IO) { recordStore.clear() }
            _resumeRecord.value = null
        }
    }

    /**
     * v5.2.1 same-session transport recovery.
     *
     * 只允许对当前 Session 执行 RESUME/Claim，并继续使用已冻结的 ResolvedLaunchProfile。
     * 这里绝不调用 createSession；如果服务端返回不同 Session ID，则作为协议边界异常拒绝。
     */
    fun recoverForStreamReconnect(
        sessionId: String,
        source: String,
        reconnectAttempt: Int,
        callback: (StreamReconnectSessionResult) -> Unit,
    ) {
        if (reconnectJob?.isActive == true) {
            Log.i(RECONNECT_TAG, "coalesced sessionId=$sessionId source=$source attempt=$reconnectAttempt")
            callback(StreamReconnectSessionResult.RetryableFailure("已有 same-session reconnect claim 正在进行。"))
            return
        }

        val current = currentSession()
        if (current == null || current.sessionId != sessionId) {
            callback(StreamReconnectSessionResult.SessionEnded("当前 Session 已变化或不存在。"))
            return
        }
        val frozenProfile = _activeLaunchProfile.value
        val record = _resumeRecord.value
        val active = activeGame ?: record?.takeIf { it.sessionId == sessionId && it.launchProfile != null }?.let { persisted ->
            ActiveGame(
                appId = persisted.appId,
                title = persisted.gameTitle,
                store = persisted.appStore,
                profile = persisted.launchProfile!!,
            )
        }
        if (active == null || frozenProfile == null || active.profile != frozenProfile) {
            callback(StreamReconnectSessionResult.RetryableFailure("当前 Session 缺少可验证的冻结 ResolvedLaunchProfile。"))
            return
        }
        val auth = authController.currentSession()
        if (auth == null) {
            callback(StreamReconnectSessionResult.RetryableFailure("Reconnect 时登录态不可用。"))
            return
        }

        operationGeneration += 1
        val operation = operationGeneration
        val sessionAttempt = orchestrator.beginAttempt()
        Log.i(
            RECONNECT_TAG,
            "BEGIN sameSession=true sessionId=$sessionId source=$source attempt=$reconnectAttempt ${active.profile.summary}",
        )

        val job = scope.launch {
            try {
                val request = SessionClaimRequest(
                    session = current,
                    appId = active.appId,
                    token = auth.gfnToken,
                    baseUrl = current.streamingBaseUrl,
                    keyboardLayout = active.profile.keyboardLayout,
                    gameLanguage = active.profile.gameLanguage,
                    audioChannels = active.profile.streamConfig.audioChannels,
                    persistInGameSettings = false,
                    appLaunchMode = 1,
                )
                Log.i(PROFILE_TAG, "RECONNECT_CLAIM sessionId=$sessionId ${active.profile.summary}")
                var reclaimed = orchestrator.claimSession(request, sessionAttempt)
                if (!isCurrent(operation)) return@launch
                verifySameReconnectSession(sessionId, reclaimed)
                ensureQueueAdsSupported(reclaimed)

                reclaimed = orchestrator.waitUntilReady(
                    initialSession = reclaimed,
                    token = auth.gfnToken,
                    attempt = sessionAttempt,
                    requiredReadyResponses = 1,
                    setupTimeoutMillis = 60_000,
                ) { updated, _ ->
                    verifySameReconnectSession(sessionId, updated)
                    ensureQueueAdsSupported(updated)
                }
                if (!isCurrent(operation)) return@launch
                verifySameReconnectSession(sessionId, reclaimed)
                persist(reclaimed, active)
                activeGame = active
                _activeLaunchProfile.value = active.profile
                _state.value = SessionUiState.Claimed(active.title, active.store, reclaimed)
                Log.i(
                    RECONNECT_TAG,
                    "RECOVERED sessionId=${reclaimed.sessionId} sameSession=true attempt=$reconnectAttempt " +
                        "signaling=${!reclaimed.signalingUrl.isNullOrBlank()} ${active.profile.summary}",
                )
                callback(StreamReconnectSessionResult.Recovered(reclaimed, active.profile))
            } catch (violation: SameSessionReconnectViolation) {
                if (!isCurrent(operation)) return@launch
                // Unexpected replacement Session is never adopted as the active stream. Best-effort stop only
                // that unexpected ID, then restore ownership of the original Session snapshot.
                runCatching { port.stopSession(violation.actualSession, auth.gfnToken) }
                orchestrator.adopt(current, auth.gfnToken)
                Log.e(RECONNECT_TAG, violation.message ?: "same-session violation")
                callback(StreamReconnectSessionResult.RetryableFailure(violation.message ?: "Session ID changed during reconnect."))
            } catch (error: CloudMatchException.Http) {
                if (!isCurrent(operation)) return@launch
                if (error.code == 404 || error.code == 410) {
                    val reason = "Reconnect 确认服务端 Session 已结束（HTTP ${error.code}）。"
                    Log.i(RECONNECT_TAG, "TERMINAL sessionId=$sessionId source=$source http=${error.code}")
                    callback(StreamReconnectSessionResult.SessionEnded(reason))
                    onServerSessionEnded(sessionId, "reconnect.http.${error.code}")
                } else {
                    Log.w(RECONNECT_TAG, "HTTP failure sessionId=$sessionId code=${error.code} attempt=$reconnectAttempt")
                    callback(StreamReconnectSessionResult.RetryableFailure("Reconnect HTTP ${error.code}"))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!isCurrent(operation)) return@launch
                Log.w(
                    RECONNECT_TAG,
                    "FAILED sessionId=$sessionId source=$source attempt=$reconnectAttempt error=${error::class.simpleName}",
                )
                callback(
                    StreamReconnectSessionResult.RetryableFailure(
                        error.message?.takeIf { it.isNotBlank() } ?: error::class.simpleName ?: "Reconnect failed",
                    ),
                )
            }
        }
        reconnectJob = job
        job.invokeOnCompletion {
            scope.launch {
                if (reconnectJob === job) reconnectJob = null
            }
        }
    }

    private fun verifySameReconnectSession(expectedSessionId: String, session: SessionInfo) {
        if (session.sessionId != expectedSessionId) {
            throw SameSessionReconnectViolation(expectedSessionId, session)
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
        val hasOwnedSession = orchestrator.currentOwnedSession() != null
        scope.launch {
            withContext(Dispatchers.IO) { recordStore.clear() }
            _resumeRecord.value = null
            if (!hasOwnedSession) {
                _activeLaunchProfile.value = null
                activeGame = null
                _state.value = SessionUiState.Idle
            }
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
            _activeLaunchProfile.value = null
            activeGame = null
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
            keyboardLayout = active.profile.keyboardLayout,
            gameLanguage = active.profile.gameLanguage,
            launchProfile = active.profile,
        )

    private fun isCurrent(operation: Long): Boolean = operation == operationGeneration

    private fun Throwable.userFacing(prefix: String): String =
        "$prefix：${message?.takeIf { it.isNotBlank() } ?: this::class.simpleName ?: "未知错误"}"

    private companion object {
        const val TAG = "GfnSession"
        const val PROFILE_TAG = "GfnLaunchProfile"
        const val RECONNECT_TAG = "GfnReconnect"
    }
}
