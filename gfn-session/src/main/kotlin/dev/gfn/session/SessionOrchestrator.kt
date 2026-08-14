package dev.gfn.session

import dev.gfn.core.model.SessionClaimRequest
import dev.gfn.core.model.SessionCreateRequest
import dev.gfn.core.model.SessionInfo

interface CloudMatchPort {
    suspend fun createSession(request: SessionCreateRequest): SessionInfo

    suspend fun pollSession(session: SessionInfo, token: String): SessionInfo

    suspend fun claimSession(request: SessionClaimRequest): SessionInfo

    suspend fun stopSession(session: SessionInfo, token: String)
}

fun interface SessionScheduler {
    suspend fun sleep(milliseconds: Long)
}

data class SessionAttemptToken internal constructor(val generation: Long)

sealed interface SessionReadinessState {
    data class InQueue(val position: Int?) : SessionReadinessState
    data class Preparing(val step: Int?) : SessionReadinessState
    data object Ready : SessionReadinessState
    data object TimedOut : SessionReadinessState
}

class SessionReadinessTracker(
    private val requiredReadyResponses: Int = 2,
    private val setupTimeoutMillis: Long = 180_000,
) {
    private var firstPostQueueTimestamp: Long? = null
    private var consecutiveReadyResponses: Int = 0

    init {
        require(requiredReadyResponses > 0) { "requiredReadyResponses 必须大于 0" }
        require(setupTimeoutMillis > 0) { "setupTimeoutMillis 必须大于 0" }
    }

    fun observe(session: SessionInfo, nowMillis: Long): SessionReadinessState {
        if (session.isInQueue) {
            firstPostQueueTimestamp = null
            consecutiveReadyResponses = 0
            return SessionReadinessState.InQueue(session.queuePosition)
        }

        if (firstPostQueueTimestamp == null) {
            firstPostQueueTimestamp = nowMillis
        }

        if (session.isReadyStatus) {
            consecutiveReadyResponses += 1
            if (consecutiveReadyResponses >= requiredReadyResponses) {
                return SessionReadinessState.Ready
            }
        } else {
            consecutiveReadyResponses = 0
        }

        val startedAt = firstPostQueueTimestamp ?: nowMillis
        if (nowMillis - startedAt >= setupTimeoutMillis) {
            return SessionReadinessState.TimedOut
        }
        return SessionReadinessState.Preparing(session.seatSetupStep)
    }
}

/**
 * CloudMatch 生命周期协调器。
 *
 * 规则：
 * - generation 拒绝旧 create/poll/claim 的迟到结果；
 * - create/claim 在结果返回时若已 stale，会 best-effort DELETE 服务端 session；
 * - 队列等待不设总超时，离开队列后才启用 setup timeout；
 * - Ready 必须连续观察两次（恢复/claim 可由调用方改为 1 次）；
 * - teardown 只停止当前 owned session 一次。
 *
 * Android 层负责把用户按钮压成 single-flight Job；此核心负责协议生命周期和 stale cleanup。
 */
class SessionOrchestrator(
    private val client: CloudMatchPort,
    private val scheduler: SessionScheduler,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val pollIntervalMillis: Long = 2_000,
) {
    private data class OwnedSession(val info: SessionInfo, val token: String)

    private var generation: Long = 0
    private var attemptsEnabled: Boolean = false
    private var ownedSession: OwnedSession? = null
    private val stoppedSessionIds = linkedSetOf<String>()

    fun beginAttempt(): SessionAttemptToken {
        generation += 1
        attemptsEnabled = true
        return SessionAttemptToken(generation)
    }

    fun cancelAttempt() {
        attemptsEnabled = false
        generation += 1
    }

    fun accepts(token: SessionAttemptToken): Boolean =
        attemptsEnabled && token.generation == generation

    suspend fun createSession(
        request: SessionCreateRequest,
        attempt: SessionAttemptToken,
    ): SessionInfo {
        requireAccepted(attempt)
        val session = client.createSession(request)
        if (!accepts(attempt)) {
            stopOnce(session, request.token)
            throw IllegalStateException("Session attempt became stale during creation")
        }
        adopt(session, request.token)
        return session
    }

    suspend fun claimSession(
        request: SessionClaimRequest,
        attempt: SessionAttemptToken,
    ): SessionInfo {
        requireAccepted(attempt)
        val session = client.claimSession(request)
        if (!accepts(attempt)) {
            stopOnce(session, request.token)
            throw IllegalStateException("Session attempt became stale during claim")
        }
        adopt(session, request.token)
        return session
    }

    suspend fun waitUntilReady(
        initialSession: SessionInfo,
        token: String,
        attempt: SessionAttemptToken,
        requiredReadyResponses: Int = 2,
        setupTimeoutMillis: Long = 180_000,
        onUpdate: (SessionInfo, SessionReadinessState) -> Unit = { _, _ -> },
    ): SessionInfo {
        requireAccepted(attempt)
        var session = initialSession
        val readiness = SessionReadinessTracker(
            requiredReadyResponses = requiredReadyResponses,
            setupTimeoutMillis = setupTimeoutMillis,
        )

        while (true) {
            requireAccepted(attempt)
            adopt(session, token)
            val state = readiness.observe(session, nowMillis())
            onUpdate(session, state)

            when (state) {
                SessionReadinessState.Ready -> return session
                SessionReadinessState.TimedOut -> error("Session setup timed out")
                is SessionReadinessState.InQueue,
                is SessionReadinessState.Preparing -> Unit
            }

            scheduler.sleep(pollIntervalMillis)
            requireAccepted(attempt)
            val polled = client.pollSession(session, token)
            requireAccepted(attempt)
            session = polled
        }
    }

    fun adopt(session: SessionInfo, token: String) {
        stoppedSessionIds.remove(session.sessionId)
        ownedSession = OwnedSession(session, token)
    }

    fun currentOwnedSession(): SessionInfo? = ownedSession?.info

    fun detachOwnedSession() {
        ownedSession = null
    }

    suspend fun stopOwnedSession() {
        val owned = ownedSession ?: return
        ownedSession = null
        stopOnce(owned.info, owned.token)
    }

    suspend fun teardown() {
        cancelAttempt()
        stopOwnedSession()
    }

    private suspend fun stopOnce(session: SessionInfo, token: String) {
        if (!stoppedSessionIds.add(session.sessionId)) return
        try {
            client.stopSession(session, token)
        } catch (error: Exception) {
            // DELETE 失败时允许后续重试，而不是永久把 session 标为已停止。
            stoppedSessionIds.remove(session.sessionId)
            throw error
        }
    }

    private fun requireAccepted(attempt: SessionAttemptToken) {
        check(accepts(attempt)) { "Stale or cancelled session attempt" }
    }
}
