package dev.gfn.session

import dev.gfn.core.model.SessionCreateRequest
import dev.gfn.core.model.SessionInfo

interface CloudMatchPort {
    suspend fun createSession(request: SessionCreateRequest): SessionInfo

    suspend fun pollSession(session: SessionInfo, token: String): SessionInfo

    suspend fun stopSession(session: SessionInfo, token: String)
}

fun interface SessionScheduler {
    suspend fun sleep(milliseconds: Long)
}

data class SessionAttemptToken internal constructor(val generation: Long)

sealed interface SessionReadinessState {
    data class InQueue(val position: Int?) : SessionReadinessState
    data object Preparing : SessionReadinessState
    data object Ready : SessionReadinessState
    data object TimedOut : SessionReadinessState
}

class SessionReadinessTracker(
    private val requiredReadyResponses: Int = 2,
    private val setupTimeoutMillis: Long = 180_000,
) {
    private var firstPostQueueTimestamp: Long? = null
    private var consecutiveReadyResponses: Int = 0

    fun observe(session: SessionInfo, nowMillis: Long): SessionReadinessState {
        if (session.isInQueue) {
            firstPostQueueTimestamp = null
            consecutiveReadyResponses = 0
            return SessionReadinessState.InQueue(session.queuePosition)
        }

        if (firstPostQueueTimestamp == null) {
            firstPostQueueTimestamp = nowMillis
        }

        if (session.status >= READY_STATUS) {
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
        return SessionReadinessState.Preparing
    }

    private companion object {
        const val READY_STATUS = 2
    }
}

class SessionOrchestrator(
    private val client: CloudMatchPort,
    private val scheduler: SessionScheduler,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val pollIntervalMillis: Long = 2_000,
) {
    private var generation: Long = 0
    private var attemptsEnabled: Boolean = false

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
            client.stopSession(session, request.token)
            throw IllegalStateException("Session attempt became stale during creation")
        }
        return session
    }

    suspend fun waitUntilReady(
        initialSession: SessionInfo,
        token: String,
        attempt: SessionAttemptToken,
        onUpdate: (SessionInfo, SessionReadinessState) -> Unit = { _, _ -> },
    ): SessionInfo {
        requireAccepted(attempt)
        var session = initialSession
        val readiness = SessionReadinessTracker()

        while (true) {
            requireAccepted(attempt)
            val state = readiness.observe(session, nowMillis())
            onUpdate(session, state)

            when (state) {
                SessionReadinessState.Ready -> return session
                SessionReadinessState.TimedOut -> error("Session setup timed out")
                is SessionReadinessState.InQueue,
                SessionReadinessState.Preparing -> Unit
            }

            scheduler.sleep(pollIntervalMillis)
            requireAccepted(attempt)
            session = client.pollSession(session, token)
        }
    }

    suspend fun teardown(session: SessionInfo?, token: String?) {
        cancelAttempt()
        if (session != null && token != null) {
            client.stopSession(session, token)
        }
    }

    private fun requireAccepted(attempt: SessionAttemptToken) {
        check(accepts(attempt)) { "Stale or cancelled session attempt" }
    }
}
