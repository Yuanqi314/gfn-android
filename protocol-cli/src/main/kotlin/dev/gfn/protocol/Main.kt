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
import dev.gfn.cloudmatch.GfnCloudMatchClient
import dev.gfn.cloudmatch.GfnRequestContext
import dev.gfn.cloudmatch.SessionRequestFactory
import dev.gfn.core.model.RequestedColorMode
import dev.gfn.core.model.SessionClaimRequest
import dev.gfn.core.model.SessionCreateRequest
import dev.gfn.core.model.SessionInfo
import dev.gfn.identity.GfnClientIdentity
import dev.gfn.input.GfnInputHandshake
import dev.gfn.input.GfnInputPacketEncoder
import dev.gfn.input.GfnKey
import dev.gfn.input.HeldKey
import dev.gfn.input.InputEpochGate
import dev.gfn.input.InputStateTracker
import dev.gfn.input.ReleaseCommand
import dev.gfn.network.HttpRequest
import dev.gfn.network.HttpResponse
import dev.gfn.network.HttpTransport
import dev.gfn.network.NetworkRedaction
import dev.gfn.session.SessionOrchestrator
import dev.gfn.session.SessionReadinessState
import dev.gfn.session.SessionScheduler
import dev.gfn.signaling.GfnSdpTools
import dev.gfn.signaling.GfnSignalingEndpoint
import dev.gfn.signaling.GfnSignalingMessageCodec
import dev.gfn.signaling.NvstSdpConfig
import dev.gfn.signaling.SignalingPeerPayload
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

private class FixtureCloudMatchTransport : HttpTransport {
    private val responses = ArrayDeque(
        listOf(
            // POST create -> queue
            HttpResponse(
                200,
                body = """{"requestStatus":{"statusCode":1},"session":{"sessionId":"v4-session","status":1,"queuePosition":5,"seatSetupStep":1}}""".toByteArray(),
            ),
            // GET queue
            HttpResponse(
                200,
                body = """{"requestStatus":{"statusCode":1},"session":{"sessionId":"v4-session","status":1,"queuePosition":2,"seatSetupStep":1}}""".toByteArray(),
            ),
            // GET preparing
            HttpResponse(
                200,
                body = """{"requestStatus":{"statusCode":1},"session":{"sessionId":"v4-session","status":1,"queuePosition":1,"seatSetupStep":3,"seatSetupInfo":{"seatSetupEta":15000}}}""".toByteArray(),
            ),
            // GET provider endpoint -> ready and resolves concrete signaling host
            readyResponse(),
            // automatic re-poll through resolved host
            readyResponse(),
            // second consecutive ready observation
            readyResponse(),
            // claim preflight
            readyResponse(),
            // claim RESUME PUT
            readyResponse(),
            // DELETE end
            HttpResponse(204),
        ),
    )

    val requests = mutableListOf<HttpRequest>()

    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests += request
        check(responses.isNotEmpty()) { "CloudMatch fixture 响应已耗尽：${request.method} ${request.url}" }
        return responses.removeFirst()
    }

    private companion object {
        fun readyResponse(): HttpResponse = HttpResponse(
            200,
            body = """{
                "requestStatus":{"statusCode":1},
                "session":{
                    "sessionId":"v4-session",
                    "status":2,
                    "gpuType":"fixture-gpu",
                    "queuePosition":1,
                    "seatSetupStep":4,
                    "connectionInfo":[
                        {"usage":14,"ip":"80.84.170.152","port":443,"resourcePath":"/nvst/"},
                        {"usage":2,"ip":"80.84.170.153","port":47998,"resourcePath":"/media/"}
                    ],
                    "sessionControlInfo":{"ip":"80.84.170.152"},
                    "iceServerConfiguration":{"iceServers":[
                        {"urls":["stun:fixture.example.test:19302"],"username":"fixture-user","credential":"fixture-credential"}
                    ]},
                    "streamingProfile":{"bitDepth":8,"hdrStreamingMode":"SDR"}
                }
            }""".trimIndent().toByteArray(),
        )
    }
}


private class StaleCreatePort : dev.gfn.session.CloudMatchPort {
    lateinit var onCreateBeforeReturn: () -> Unit
    var stopCalls = 0

    override suspend fun createSession(request: SessionCreateRequest): SessionInfo {
        onCreateBeforeReturn()
        return SessionInfo(
            sessionId = "stale-session",
            status = 1,
            streamingBaseUrl = request.streamingBaseUrl,
            clientId = "stale-client",
            deviceId = "stale-device",
        )
    }

    override suspend fun pollSession(session: SessionInfo, token: String): SessionInfo = session

    override suspend fun claimSession(request: SessionClaimRequest): SessionInfo = request.session

    override suspend fun stopSession(session: SessionInfo, token: String) {
        stopCalls += 1
    }
}


private fun verifyV513KeyboardForensicsGoldenPackets() {
    println("\n[v5.1.3 Keyboard Forensics Golden Packets]")
    val timestamp = 0x0102030405060708L
    val keys = listOf(
        "A" to GfnKey(0x41, 0x1E),
        "W" to GfnKey(0x57, 0x11),
        "K" to GfnKey(0x4B, 0x25),
        "1" to GfnKey(0x31, 0x02),
        "Space" to GfnKey(0x20, 0x39),
        "Esc" to GfnKey(0x1B, 0x01),
    )

    fun assertPacket(packet: ByteArray, version: Int, down: Boolean, key: GfnKey) {
        val payloadOffset = if (version >= 3) 10 else 0
        check(packet.size == payloadOffset + 18)
        if (version >= 3) {
            check((packet[0].toInt() and 0xff) == 0x23)
            check((packet[9].toInt() and 0xff) == 0x22)
        }
        val type = if (down) 3 else 4
        check(packet.sliceArray(payloadOffset until payloadOffset + 4).contentEquals(byteArrayOf(type.toByte(), 0, 0, 0)))
        check((packet[payloadOffset + 4].toInt() and 0xff) == ((key.virtualKey ushr 8) and 0xff))
        check((packet[payloadOffset + 5].toInt() and 0xff) == (key.virtualKey and 0xff))
        check((packet[payloadOffset + 6].toInt() and 0xff) == 0)
        check((packet[payloadOffset + 7].toInt() and 0xff) == 0)
        check((packet[payloadOffset + 8].toInt() and 0xff) == ((key.scanCode ushr 8) and 0xff))
        check((packet[payloadOffset + 9].toInt() and 0xff) == (key.scanCode and 0xff))
    }

    for (version in listOf(2, 3)) {
        val encoder = GfnInputPacketEncoder(protocolVersion = version, timestampMicros = { timestamp })
        for ((name, key) in keys) {
            assertPacket(encoder.keyboard(true, key, 0), version, true, key)
            assertPacket(encoder.keyboard(false, key, 0), version, false, key)
            println("golden $name v$version DOWN/UP PASS")
        }
    }
    println("V513_KEYBOARD_GOLDEN_PACKETS=PASS")
}

private fun verifyStaleCreateCleanup() {
    println("\n[Session race cleanup]")
    val port = StaleCreatePort()
    val orchestrator = SessionOrchestrator(
        client = port,
        scheduler = SessionScheduler { },
        pollIntervalMillis = 0,
    )
    port.onCreateBeforeReturn = { orchestrator.cancelAttempt() }
    runSynchronously {
        val attempt = orchestrator.beginAttempt()
        val result = runCatching {
            orchestrator.createSession(
                SessionCreateRequest(
                    appId = "9001",
                    token = "fixture-token",
                    streamingBaseUrl = "https://stream.example.test",
                ),
                attempt,
            )
        }
        check(result.isFailure)
    }
    check(port.stopCalls == 1) { "迟到 create 应 cleanup 一次，实际=${port.stopCalls}" }
    println("Cancel while Creating → late create → DELETE cleanup 验证通过")
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
    println("=== GFN Android 第五版核心验证 ===")
    verifyV4CloudMatchLifecycle()
    verifyV5SignalingAndSdp()
    verifyV51KeyboardMouseProtocol()
    verifyV513KeyboardForensicsGoldenPackets()
    verifyStaleCreateCleanup()
    verifyDeviceFlow()
    verifySessionRestoreAfterUnauthorized()
    verifyContentApis()
}


private fun verifyV51KeyboardMouseProtocol() {
    println("\n[v5.1 Keyboard / Mouse]")

    check(GfnInputHandshake.parseProtocolVersion(byteArrayOf(0x0e, 0x02, 0x03, 0x00)) == 3)
    check(GfnInputHandshake.parseProtocolVersion(byteArrayOf(0x0e, 0x02)) == 2)
    check(GfnInputHandshake.parseProtocolVersion(byteArrayOf(0x01, 0x00)) == null)

    val fixedTs = 0x0102030405060708L
    val encoder = GfnInputPacketEncoder(protocolVersion = 2, timestampMicros = { fixedTs })
    val w = GfnKey(virtualKey = 0x57, scanCode = 0x11)
    val ctrl = GfnKey(virtualKey = 0xA2, scanCode = 0x1D, modifierBit = 0x0002)
    val wDown = encoder.keyboard(down = true, key = w, modifiers = 0x0002)
    check(wDown.size == 18)
    check(wDown.sliceArray(0..3).contentEquals(byteArrayOf(3, 0, 0, 0)))
    check((wDown[4].toInt() and 0xff) == 0 && (wDown[5].toInt() and 0xff) == 0x57)
    check((wDown[6].toInt() and 0xff) == 0 && (wDown[7].toInt() and 0xff) == 0x02)
    check((wDown[8].toInt() and 0xff) == 0 && (wDown[9].toInt() and 0xff) == 0x11)

    encoder.protocolVersion = 3
    val mouse = encoder.mouseMove(dx = 14, dy = -7)
    check(mouse.size == 34)
    check((mouse[0].toInt() and 0xff) == 0x23)
    check((mouse[9].toInt() and 0xff) == 0x21)
    check((mouse[10].toInt() and 0xff) == 0 && (mouse[11].toInt() and 0xff) == 22)
    check(mouse.sliceArray(12..15).contentEquals(byteArrayOf(7, 0, 0, 0)))

    val physicalModifierTracker = InputStateTracker()
    check(physicalModifierTracker.currentPhysicalModifierMask() == 0)
    physicalModifierTracker.recordPhysicalKeyDown(HeldKey(ctrl, 0x0002))
    check(physicalModifierTracker.currentPhysicalModifierMask() == 0x0002)
    physicalModifierTracker.recordPhysicalKeyDown(HeldKey(w, 0x0002))
    check(physicalModifierTracker.currentPhysicalModifierMask() == 0x0002)
    physicalModifierTracker.recordPhysicalKeyUp(ctrl)
    check(physicalModifierTracker.currentPhysicalModifierMask() == 0)

    val tracker = InputStateTracker()
    val ctrlHeld = HeldKey(ctrl, 0x0002)
    // 模拟 W 先按下、随后 Ctrl 才按下；releaseAll 时 W UP 仍必须携带当前 Ctrl mask。
    val wHeld = HeldKey(w, 0x0000)
    tracker.markKeyDownAccepted(wHeld)
    tracker.markKeyDownAccepted(ctrlHeld)
    tracker.markMouseDownAccepted(1)
    val release = tracker.buildReleasePlan()
    check(release.size == 3)
    check(release[0] == ReleaseCommand.KeyUp(wHeld.copy(modifiersAtDown = 0x0002)))
    check(release[1] == ReleaseCommand.MouseButtonUp(1))
    check(release[2] == ReleaseCommand.KeyUp(ctrlHeld))
    tracker.clearRemoteKey(w)
    tracker.clearRemoteMouseButton(1)
    tracker.clearRemoteKey(ctrl)
    tracker.finishRelease(channelUsable = true)
    check(tracker.buildReleasePlan().isEmpty())

    val epoch = InputEpochGate(10)
    val old = epoch.currentEpoch
    check(epoch.isCurrent(old))
    check(epoch.advance() == 11L)
    check(!epoch.isCurrent(old))
    check(epoch.isCurrent(11L))

    println("Handshake → packet framing → tracked modifier truth → release 顺序 → epoch stale rejection 验证通过")
}

private fun verifyV5SignalingAndSdp() {
    println("\n[v5 Signaling / SDP]")
    val signIn = GfnSignalingEndpoint.signInUrl(
        signalingUrl = "wss://66-22-144-44.cloudmatchbeta.nvidiagrid.net/nvst/",
        sessionId = "fixture-session",
        peerName = "peer-fixture",
    )
    check(signIn == "wss://66-22-144-44.cloudmatchbeta.nvidiagrid.net/nvst/sign_in?peer_id=peer-fixture&version=2&peer_role=1&pairing_id=fixture-session")
    check(GfnSignalingEndpoint.sessionSubprotocol("fixture-session") == "x-nv-sessionid.fixture-session")

    val offer = listOf(
        "v=0",
        "o=- 1 2 IN IP4 0.0.0.0",
        "s=-",
        "t=0 0",
        "m=video 9 UDP/TLS/RTP/SAVPF 96 97 98 99",
        "c=IN IP4 0.0.0.0",
        "a=mid:1",
        "a=rtpmap:96 H264/90000",
        "a=fmtp:96 packetization-mode=1;profile-level-id=42e01f",
        "a=rtpmap:97 rtx/90000",
        "a=fmtp:97 apt=96",
        "a=rtpmap:98 VP8/90000",
        "a=rtpmap:99 red/90000",
        "a=ri.partialReliableThresholdMs:455",
        "m=audio 9 UDP/TLS/RTP/SAVPF 111",
        "a=mid:0",
        "a=rtpmap:111 opus/48000/2",
        "",
    ).joinToString("\r\n")
    val offerPayload = dev.gfn.network.Json.stringify(mapOf("type" to "offer", "sdp" to offer))
    val envelope = dev.gfn.network.Json.stringify(
        mapOf(
            "peer_msg" to mapOf("from" to 1, "to" to 2, "msg" to offerPayload),
            "ackid" to 7,
        ),
    )
    val decoded = GfnSignalingMessageCodec.decode(envelope)
    val offerSummary = GfnSdpTools.summarize(offer, isOffer = true)
    check(offerSummary.videoCodecs.contains("H264"))
    check(offerSummary.videoCodecs.none { it.equals("opus", ignoreCase = true) })
    check(decoded.acknowledgementId == 7)
    check(decoded.peerFrom == 1)
    check((decoded.payload as? SignalingPeerPayload.Offer)?.sdp == offer)

    val candidateEnvelope = GfnSignalingMessageCodec.encodeIceCandidate(
        candidate = "candidate:1 1 UDP 2130706431 80.84.170.153 47998 typ host",
        sdpMid = "1",
        sdpMLineIndex = 0,
        from = 2,
        to = 1,
        acknowledgementId = 8,
    )
    val candidateRoot = dev.gfn.network.Json.parseObject(candidateEnvelope)
    check(candidateRoot.containsKey("peer_msg"))
    check(!GfnSignalingMessageCodec.isTcpIceCandidate("candidate:1 1 UDP 1 1.2.3.4 9 typ host"))
    check(GfnSignalingMessageCodec.isTcpIceCandidate("candidate:1 1 TCP 1 1.2.3.4 9 typ host tcptype active"))

    val answer = listOf(
        "v=0",
        "o=- 1 2 IN IP4 127.0.0.1",
        "s=-",
        "t=0 0",
        "a=ice-ufrag:fixtureUfrag",
        "a=ice-pwd:fixturePassword",
        "a=fingerprint:sha-256 AA:BB:CC",
        "m=video 9 UDP/TLS/RTP/SAVPF 96 97 98 99",
        "a=mid:1",
        "a=rtpmap:96 H264/90000",
        "a=rtpmap:97 rtx/90000",
        "a=fmtp:97 apt=96",
        "a=rtpmap:98 VP8/90000",
        "a=rtpmap:99 red/90000",
        "m=audio 9 UDP/TLS/RTP/SAVPF 111",
        "a=mid:0",
        "a=rtpmap:111 opus/48000/2",
        "",
    ).joinToString("\r\n")
    check(GfnSdpTools.partialReliableThresholdMs(offer) == 455)
    val h264 = GfnSdpTools.preferH264InAnswer(answer)
    check(h264.contains("m=video 9 UDP/TLS/RTP/SAVPF 96 97 99"))
    check(!h264.contains("a=rtpmap:98 VP8/90000"))
    val bounded = GfnSdpTools.injectBandwidth(h264, 20_000)
    check(bounded.contains("b=AS:20000"))
    val creds = GfnSdpTools.extractIceCredentials(bounded)
    check(creds.ufrag == "fixtureUfrag")
    check(creds.password == "fixturePassword")
    check(creds.fingerprintSha256 == "AA:BB:CC")
    val nvst = GfnSdpTools.buildNvstSdp(
        creds,
        NvstSdpConfig(width = 1920, height = 1080, fps = 60, maxBitrateKbps = 20_000),
    )
    check(nvst.contains("a=video.bitDepth:8"))
    check(nvst.contains("a=video.maxFPS:60"))
    check(nvst.contains("a=msid:input_1"))
    check(!nvst.contains("10-bit", ignoreCase = true))
    println("WSS sign_in + session subprotocol → peer envelope → H.264 Answer → NVST SDP fixture 验证通过")
}

private fun verifyV4CloudMatchLifecycle() {
    println("\n[CloudMatch Session Lifecycle]")
    val identity = GfnClientIdentity.WindowsDesktop
    println("identity=${identity.clientIdentification}/${identity.clientPlatformName}")

    val requestContext = GfnRequestContext(
        token = "fixture-secret",
        clientId = "fixture-client",
        deviceId = "fixture-device",
        clientVersion = "fixture-version",
        userAgent = "GFN-Android-Lab/fixture",
    )
    println("headers=${NetworkRedaction.headers(requestContext.headers())}")

    val readableRequest = SessionRequestFactory().create(
        appId = "9001",
        deviceId = "fixture-device",
        clientVersion = "30.0",
        width = 1920,
        height = 1080,
        fps = 60,
        colorMode = RequestedColorMode.CompatibilitySdr,
    )
    check(!readableRequest.streamingFeatures.tenBitRequested)

    val transport = FixtureCloudMatchTransport()
    val cloudMatch = GfnCloudMatchClient(
        transport = transport,
        deviceId = { "fixture-stable-device" },
        uuid = { UUID.fromString("00000000-0000-0000-0000-000000000004") },
        timezoneOffsetMilliseconds = { 28_800_000 },
    )
    val orchestrator = SessionOrchestrator(
        client = cloudMatch,
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
        val createRequest = SessionCreateRequest(
            appId = "9001",
            token = "fixture-gfn-token",
            streamingBaseUrl = "https://stream.example.test/",
            width = 1920,
            height = 1080,
            fps = 60,
            keyboardLayout = "zh-CN",
            gameLanguage = "zh_CN",
            requestedColorMode = RequestedColorMode.CompatibilitySdr,
            persistInGameSettings = false,
            appLaunchMode = 1,
        )
        val created = orchestrator.createSession(createRequest, attempt)
        println("create=${created.sessionId}, queue=${created.queuePosition}")

        val ready = orchestrator.waitUntilReady(created, createRequest.token, attempt) { session, state ->
            val label = when (state) {
                is SessionReadinessState.InQueue -> "Queued(${state.position})"
                is SessionReadinessState.Preparing -> "Preparing(step=${state.step})"
                SessionReadinessState.Ready -> "Ready"
                SessionReadinessState.TimedOut -> "TimedOut"
            }
            println("poll status=${session.status} -> $label")
        }
        check(ready.isReadyStatus)
        check(ready.serverIp == "80.84.170.152")
        check(ready.signalingUrl == "wss://80.84.170.152:443/nvst/")
        check(ready.iceServers.size == 1)
        println("ready=${ready.sessionId}, signaling=${ready.signalingUrl}")

        val claimed = orchestrator.claimSession(
            SessionClaimRequest(
                session = ready,
                appId = "9001",
                token = createRequest.token,
                baseUrl = ready.streamingBaseUrl,
                keyboardLayout = "zh-CN",
                gameLanguage = "zh_CN",
                persistInGameSettings = false,
                appLaunchMode = 1,
            ),
            attempt,
        )
        check(claimed.isReadyStatus)
        println("claim=${claimed.sessionId}, status=${claimed.status}")
        orchestrator.stopOwnedSession()
        println("end=DELETE sent")
    }

    check(transport.requests.size == 9) { "实际 CloudMatch fixture 请求数=${transport.requests.size}" }
    val create = transport.requests[0]
    check(create.method == "POST")
    check(create.url.startsWith("https://stream.example.test/v2/session?"))
    check(create.headers["nv-device-os"] == "WINDOWS")
    check(create.headers["nv-device-type"] == "DESKTOP")
    check(create.headers["x-device-id"] == "fixture-stable-device")
    val createBody = create.body!!.toString(Charsets.UTF_8)
    check(createBody.contains("\"clientIdentification\":\"GFN-PC\""))
    check(createBody.contains("\"clientPlatformName\":\"windows\""))
    check(createBody.contains("\"appId\":\"9001\""))
    check(createBody.contains("\"sdrHdrMode\":0"))
    check(createBody.contains("\"bitDepth\":0"))
    check(!createBody.contains("PreferHdr10"))

    check(transport.requests[3].url == "https://stream.example.test/v2/session/v4-session")
    check(transport.requests[4].url == "https://80.84.170.152/v2/session/v4-session")

    val resume = transport.requests[7]
    check(resume.method == "PUT")
    val resumeBody = resume.body!!.toString(Charsets.UTF_8)
    check(resumeBody.contains("\"action\":2"))
    check(resumeBody.contains("\"data\":\"RESUME\""))
    check(!resumeBody.contains("requestedStreamingFeatures"))
    check(!resumeBody.contains("clientRequestMonitorSettings"))
    check(transport.requests[8].method == "DELETE")
    println("Create → Queue → Preparing → 双 Ready → Claim/Resume → End fixture 验证通过")
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
        check(library.single().variants.first().launchAppId == "9001")
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
