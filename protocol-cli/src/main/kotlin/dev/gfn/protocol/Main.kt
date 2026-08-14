package dev.gfn.protocol

import dev.gfn.auth.AuthEndpointConfig
import dev.gfn.auth.AuthSessionService
import dev.gfn.auth.AuthTokens
import dev.gfn.auth.TokenStore
import dev.gfn.auth.DeviceAuthorization
import dev.gfn.auth.NvidiaAuthApi
import dev.gfn.account.GfnAccountClient
import dev.gfn.account.GfnAccountContext
import dev.gfn.games.GfnGamesClient
import dev.gfn.games.GfnGamesContext
import dev.gfn.cloudmatch.GfnRequestContext
import dev.gfn.cloudmatch.SessionRequestFactory
import dev.gfn.core.model.RequestedColorMode
import dev.gfn.core.model.SessionCreateRequest
import dev.gfn.core.model.SessionInfo
import dev.gfn.identity.GfnClientIdentity
import dev.gfn.network.HttpRequest
import dev.gfn.network.HttpResponse
import dev.gfn.network.HttpTransport
import dev.gfn.network.NetworkRedaction
import dev.gfn.session.CloudMatchPort
import dev.gfn.session.SessionOrchestrator
import dev.gfn.session.SessionReadinessState
import dev.gfn.session.SessionScheduler
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
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

private class FixtureAuthTransport : HttpTransport {
    private val responses = ArrayDeque(
        listOf(
            HttpResponse(
                200,
                body = """{"gfnServiceInfo":{"gfnServiceEndpoints":[{"idpId":"fixture-idp","loginProviderCode":"NVIDIA","loginProviderDisplayName":"NVIDIA","streamingServiceUrl":"https://stream.example.test","loginProviderPriority":0}]}}""".toByteArray(),
            ),
            HttpResponse(
                200,
                body = """{"user_code":"ABCD-EFGH","device_code":"fixture-device-code","verification_uri":"https://login.example.test","verification_uri_complete":"https://login.example.test?code=ABCD-EFGH","expires_in":600,"interval":1}""".toByteArray(),
            ),
            HttpResponse(400, body = """{"error":"authorization_pending"}""".toByteArray()),
            HttpResponse(
                200,
                body = """{"access_token":"fixture-access-token","refresh_token":"fixture-refresh-token","expires_in":3600}""".toByteArray(),
            ),
            HttpResponse(
                200,
                body = """{"sub":"fixture-user","preferred_username":"测试用户","email":"user@example.test","gfn_tier":"ULTIMATE"}""".toByteArray(),
            ),
            HttpResponse(
                200,
                body = """{"client_token":"fixture-client-token-1","expires_in":86400}""".toByteArray(),
            ),
            HttpResponse(
                200,
                body = """{"access_token":"fixture-rebound-access","expires_in":3600}""".toByteArray(),
            ),
            HttpResponse(
                200,
                body = """{"client_token":"fixture-client-token-2","expires_in":86400}""".toByteArray(),
            ),
        ),
    )

    val requests = mutableListOf<HttpRequest>()

    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests += request
        return responses.removeFirst()
    }
}


private class RestoreAuthTransport : HttpTransport {
    private val responses = ArrayDeque(
        listOf(
            HttpResponse(401, body = """{"error":"invalid_token"}""".toByteArray()),
            HttpResponse(200, body = """{"access_token":"restore-refreshed-access","expires_in":3600}""".toByteArray()),
            HttpResponse(
                200,
                body = """{"sub":"restore-user","preferred_username":"恢复用户","email":"restore@example.test"}""".toByteArray(),
            ),
            HttpResponse(
                200,
                body = """{"gfnServiceInfo":{"gfnServiceEndpoints":[{"idpId":"restore-idp","loginProviderCode":"NVIDIA","loginProviderDisplayName":"NVIDIA","streamingServiceUrl":"https://restore-stream.example.test","loginProviderPriority":0}]}}""".toByteArray(),
            ),
        ),
    )

    val requests = mutableListOf<HttpRequest>()

    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests += request
        return responses.removeFirst()
    }
}

private class MemoryTokenStore : TokenStore {
    var tokens: AuthTokens? = null
    override suspend fun load(): AuthTokens? = tokens
    override suspend fun save(tokens: AuthTokens) { this.tokens = tokens }
    override suspend fun clear() { tokens = null }
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
        "Fixture 不应该真正挂起；真实网络由 Android coroutine IO dispatcher 驱动"
    }.getOrThrow()
}

fun main() {
    println("=== GFN Android 第三版核心验证 ===")
    verifyIdentityAndSession()
    verifyDeviceFlow()
    verifySessionRestoreAfterUnauthorized()
    verifyContentApis()
}

private fun verifyIdentityAndSession() {
    val identity = GfnClientIdentity.WindowsDesktop
    println("\n[协议身份]")
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
        scheduler = SessionScheduler { },
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

private fun verifyDeviceFlow() {
    println("\n[Device Flow]")
    val transport = FixtureAuthTransport()
    var now = Instant.parse("2026-08-14T00:00:00Z")
    val api = NvidiaAuthApi(
        transport = transport,
        config = AuthEndpointConfig(
            deviceAuthorizeUrl = "https://login.example.test/device/authorize",
            tokenUrl = "https://login.example.test/token",
            clientTokenUrl = "https://login.example.test/client_token",
            userInfoUrl = "https://login.example.test/userinfo",
            serviceUrlsUrl = "https://login.example.test/serviceUrls",
            deviceFlowClientId = "fixture-device-client",
            mainClientId = "fixture-main-client",
            scopes = "openid email",
            userAgent = "fixture-agent",
            displayName = "Android",
        ),
        now = { now },
        uuid = { UUID.fromString("00000000-0000-0000-0000-000000000001") },
        sleepSeconds = { seconds -> now = now.plusSeconds(seconds) },
    )

    val tokenStore = MemoryTokenStore()
    val service = AuthSessionService(api, tokenStore, now = { now })
    runSynchronously {
        val authorization: DeviceAuthorization = service.beginDeviceAuthorization()
        println("userCode=${authorization.userCode}")
        println("verificationUri=${authorization.verificationUri}")
        val session = service.completeDeviceAuthorization(authorization)
        println("token=<已脱敏>")
        println("user=${session.user.displayName}/${session.user.membershipTier}")
        println("clientToken=${if (session.tokens.clientToken != null) "已绑定" else "缺失"}")
    }

    check(transport.requests.size == 8)
    check(transport.requests[0].url.endsWith("/serviceUrls"))
    check(transport.requests[1].body!!.toString(Charsets.UTF_8).contains("idp_id=fixture-idp"))
    check(transport.requests[1].body!!.toString(Charsets.UTF_8).contains("device_id=00000000-0000-0000-0000-000000000001"))
    check(transport.requests[2].body!!.toString(Charsets.UTF_8).contains("device_code=fixture-device-code"))
    check(transport.requests[4].headers["Authorization"] == "Bearer fixture-access-token")
    check(transport.requests[6].body!!.toString(Charsets.UTF_8).contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Aclient_token"))
    check(tokenStore.tokens?.accessToken == "fixture-rebound-access")
    check(tokenStore.tokens?.refreshToken == "fixture-refresh-token")
    check(tokenStore.tokens?.clientToken == "fixture-client-token-2")
    println("deviceFlowRequests=${transport.requests.size}，Provider discovery + client_token re-bind 验证通过")
}


private fun verifySessionRestoreAfterUnauthorized() {
    println("\n[登录态恢复]")
    val transport = RestoreAuthTransport()
    val now = Instant.parse("2026-08-14T00:00:00Z")
    val api = NvidiaAuthApi(
        transport = transport,
        config = AuthEndpointConfig(
            deviceAuthorizeUrl = "https://login.example.test/device/authorize",
            tokenUrl = "https://login.example.test/token",
            clientTokenUrl = "https://login.example.test/client_token",
            userInfoUrl = "https://login.example.test/userinfo",
            serviceUrlsUrl = "https://login.example.test/serviceUrls",
            deviceFlowClientId = "fixture-device-client",
            mainClientId = "fixture-main-client",
            scopes = "openid email",
            userAgent = "fixture-agent",
            displayName = "Android",
        ),
        now = { now },
        sleepSeconds = { },
    )
    val store = MemoryTokenStore().apply {
        tokens = AuthTokens(
            accessToken = "restore-old-access",
            refreshToken = "restore-refresh-token",
            expiresAt = now.plusSeconds(7_200),
        )
    }
    val service = AuthSessionService(api, store, now = { now })
    val session = runSynchronously { service.restore() }
    checkNotNull(session)
    check(session.tokens.accessToken == "restore-refreshed-access")
    check(session.tokens.refreshToken == "restore-refresh-token")
    check(session.user.userId == "restore-user")
    check(transport.requests.size == 4)
    check(transport.requests[0].headers["Authorization"] == "Bearer restore-old-access")
    check(transport.requests[1].body!!.toString(Charsets.UTF_8).contains("grant_type=refresh_token"))
    check(transport.requests[2].headers["Authorization"] == "Bearer restore-refreshed-access")
    check(transport.requests[3].url.endsWith("/serviceUrls"))
    check(session.provider?.streamingServiceUrl == "https://restore-stream.example.test/")
    println("userinfo 401 → refresh → userinfo 重试 + Provider 恢复验证通过")
}


private class FixtureContentTransport : HttpTransport {
    private val responses = ArrayDeque(
        listOf(
            HttpResponse(
                200,
                body = """{"requestStatus":{"serverId":"NP-TST-01"}}""".toByteArray(),
            ),
            HttpResponse(
                200,
                body = """[{"membershipTier":"ULTIMATE","subType":"UNLIMITED","remainingTimeInMinutes":42,"totalTimeInMinutes":480,"features":{"resolutions":[{"widthInPixels":1920,"heightInPixels":1080,"framesPerSecond":120,"isEntitled":true},{"widthInPixels":3840,"heightInPixels":2160,"framesPerSecond":60,"isEntitled":true}]}}]""".toByteArray(),
            ),
            HttpResponse(
                200,
                body = """{"data":{"apps":{"pageInfo":{"hasNextPage":false,"endCursor":"","totalCount":1},"items":[{"id":"101","title":"Fixture HDR Game","genres":["Action"],"images":{"GAME_BOX_ART":"https://img.nvidiagrid.net/box"},"variants":[{"id":"9001","appStore":"STEAM","gfn":{"library":{"status":"IN_LIBRARY","selected":true},"features":[{"key":"HDR_ENABLED","value":"true"},{"key":"RTX_ENABLED","value":"true"}]}}]}]}}}""".toByteArray(),
            ),
            HttpResponse(
                200,
                body = """{"data":{"apps":{"pageInfo":{"hasNextPage":false,"endCursor":"","totalCount":2},"items":[{"id":"101","title":"Fixture HDR Game","genres":["Action"],"images":{"GAME_BOX_ART":"https://img.nvidiagrid.net/box"},"variants":[{"id":"9001","appStore":"STEAM","gfn":{"library":{"status":"IN_LIBRARY","selected":true},"features":[{"key":"HDR_ENABLED","value":"true"}]}}]},{"id":202,"title":"Fixture Strategy","genres":["Strategy"],"images":{},"variants":[{"id":"9002","appStore":"EPIC","gfn":{"library":{"status":"NOT_OWNED","selected":false},"features":[{"key":"REFLEX_ENABLED","value":"true"}]}}]}]}}}""".toByteArray(),
            ),
            HttpResponse(
                200,
                body = """{"data":{"apps":{"items":[{"id":"101","title":"Fixture HDR Game","longDescription":"真实详情 fixture","genres":["Action"],"developerName":"Fixture Studio","publisherName":"Fixture Publisher","contentRatings":{"type":"ESRB","categoryKey":"T"},"images":{"GAME_BOX_ART":"https://img.nvidiagrid.net/box","TV_BANNER":"https://img.nvidiagrid.net/banner"},"variants":[{"id":"9001","appStore":"STEAM","gfn":{"library":{"status":"IN_LIBRARY","selected":true},"features":[{"key":"HDR_ENABLED","value":"true"}]}}]}]}}}""".toByteArray(),
            ),
        ),
    )
    val requests = mutableListOf<HttpRequest>()
    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests += request
        return responses.removeFirst()
    }
}

private fun verifyContentApis() {
    println("\n[Account / Subscription / Catalog / Library]")
    val transport = FixtureContentTransport()
    val account = GfnAccountClient(transport)
    val games = GfnGamesClient(
        transport = transport,
        now = { Instant.parse("2026-08-14T00:00:00Z") },
        uuid = { UUID.fromString("00000000-0000-0000-0000-000000000099") },
    )
    val accountContext = GfnAccountContext(
        token = "fixture-gfn-jwt",
        userId = "fixture-user",
        streamingServiceUrl = "https://stream.example.test/",
    )
    runSynchronously {
        val vpcId = account.fetchVpcId(accountContext)
        check(vpcId == "NP-TST-01")
        val subscription = account.fetchSubscription(accountContext, vpcId, "zh_CN")
        check(subscription.membershipTier == "ULTIMATE")
        check(subscription.entitledResolutions.size == 2)
        val context = GfnGamesContext("fixture-gfn-jwt", vpcId, "zh_CN")
        val library = games.fetchLibrary(context)
        check(library.size == 1)
        check(library.single().isInLibrary)
        check(library.single().supportsHdr)
        check(library.single().supportsRtx)
        val catalog = games.fetchCatalog(context)
        check(catalog.size == 2)
        check(catalog.any { it.appId == "202" && it.supportsReflex })
        val detail = games.fetchGameDetail(context, "101")
        check(detail.title == "Fixture HDR Game")
        check(detail.developer == "Fixture Studio")
        check(detail.supportsHdr)
        println("vpc=$vpcId, membership=${subscription.membershipTier}, library=${library.size}, catalog=${catalog.size}")
        println("gameDetail=${detail.title}/${detail.developer}")
    }
    check(transport.requests.size == 5)
    check(transport.requests[0].url == "https://stream.example.test/v2/serverInfo")
    check(transport.requests[0].headers["Authorization"] == "GFNJWT fixture-gfn-jwt")
    check(transport.requests[1].url.contains("mes.geforcenow.com/v4/subscriptions"))
    val libraryBody = transport.requests[2].body!!.toString(Charsets.UTF_8)
    check(libraryBody.contains("NOT_OWNED"))
    check(libraryBody.contains("$" + "vpcId"))
    check(transport.requests[3].url == "https://games.geforce.com/graphql")
    check(transport.requests[4].url.contains("requestType=appMetaData"))
    check(transport.requests[4].url.contains("cf8b620dfd03617017ba7c858cee65197e1ace5180e41be194b39227227ced63"))
    println("serverInfo → MES → Library → Catalog → Game Detail fixture 验证通过")
}
