package dev.gfn.protocol

import dev.gfn.core.model.SessionCreateRequest
import dev.gfn.core.model.SessionInfo
import dev.gfn.core.model.RequestedColorMode
import dev.gfn.cloudmatch.GfnRequestContext
import dev.gfn.cloudmatch.SessionRequestFactory
import dev.gfn.network.NetworkRedaction
import dev.gfn.identity.GfnClientIdentity
import dev.gfn.session.CloudMatchPort
import dev.gfn.session.SessionOrchestrator
import dev.gfn.session.SessionReadinessState
import dev.gfn.session.SessionScheduler
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

private class FixtureCloudMatchPort : CloudMatchPort {
    private var pollCount = 0

    override suspend fun createSession(request: SessionCreateRequest): SessionInfo = SessionInfo(
        sessionId = "fixture-session",
        status = 1,
        queuePosition = 3,
        clientId = GfnClientIdentity.WindowsDesktop.clientIdentification,
        deviceId = request.deviceId,
    )

    override suspend fun pollSession(session: SessionInfo, token: String): SessionInfo {
        pollCount += 1
        return when (pollCount) {
            1 -> session.copy(queuePosition = 1)
            2 -> session.copy(status = 1, queuePosition = null)
            else -> session.copy(status = 2, queuePosition = null)
        }
    }

    override suspend fun stopSession(session: SessionInfo, token: String) = Unit
}

private fun <T> runSynchronously(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<T>) {
            outcome = result
        }
    })
    return checkNotNull(outcome) {
        "Fixture unexpectedly suspended; use a real coroutine runtime when network transport is added"
    }.getOrThrow()
}

fun main() {
    val identity = GfnClientIdentity.WindowsDesktop
    println("GFN Android protocol baseline")
    println("identity=${identity.clientIdentification}/${identity.clientPlatformName}")
    identity.protocolHeaders().forEach { (name, value) -> println("$name=$value") }

    val requestContext = GfnRequestContext(
        token = "fixture-secret",
        clientId = "fixture-client",
        deviceId = "fixture-device",
        clientVersion = "fixture-version",
        userAgent = "GFN-Android-Lab/fixture",
    )
    println("headers=${NetworkRedaction.headers(requestContext.headers())}")
    val sessionRequest = SessionRequestFactory().create(
        appId = "fixture-app",
        deviceId = "fixture-device",
        clientVersion = "30.0",
        width = 1920,
        height = 1080,
        fps = 60,
        colorMode = RequestedColorMode.CompatibilitySdr,
    )
    println("sessionRequest=$sessionRequest")

    val port = FixtureCloudMatchPort()
    val orchestrator = SessionOrchestrator(
        client = port,
        scheduler = SessionScheduler { /* deterministic fixture: no wait */ },
        nowMillis = object {
            var value = 0L
            operator fun invoke(): Long {
                value += 1_000
                return value
            }
        }::invoke,
        pollIntervalMillis = 0,
    )

    runSynchronously {
        val attempt = orchestrator.beginAttempt()
        val request = SessionCreateRequest(
            appId = "fixture-app",
            token = "redacted-fixture-token",
            deviceId = "fixture-device",
        )
        val created = orchestrator.createSession(request, attempt)
        println("created=${created.sessionId}")
        val ready = orchestrator.waitUntilReady(created, request.token, attempt) { session, state ->
            val detail = when (state) {
                is SessionReadinessState.InQueue -> "queue=${state.position}"
                SessionReadinessState.Preparing -> "preparing"
                SessionReadinessState.Ready -> "ready"
                SessionReadinessState.TimedOut -> "timed-out"
            }
            println("poll status=${session.status} $detail")
        }
        println("ready=${ready.sessionId}")
    }
}
