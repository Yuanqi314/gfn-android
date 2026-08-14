package dev.gfn.auth

import dev.gfn.network.HttpRequest
import dev.gfn.network.HttpResponse
import dev.gfn.network.HttpTransport
import java.time.Instant
import java.util.ArrayDeque
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.coroutines.startCoroutine

class NvidiaAuthApiTest {
    @Test
    fun deviceFlowHandlesPendingThenSuccess() = runSuspendTest {
        val requests = mutableListOf<HttpRequest>()
        val responses = ArrayDeque(
            listOf(
                jsonResponse(200, """{"user_code":"ABCD","device_code":"dev","verification_uri":"https://example.test","expires_in":600,"interval":1}"""),
                jsonResponse(400, """{"error":"authorization_pending"}"""),
                jsonResponse(200, """{"access_token":"access","refresh_token":"refresh","expires_in":3600}"""),
            ),
        )
        var clock = Instant.parse("2026-08-14T00:00:00Z")
        val api = NvidiaAuthApi(
            transport = HttpTransport { request -> requests += request; responses.removeFirst() },
            config = testConfig(),
            now = { clock },
            sleepSeconds = { clock = clock.plusSeconds(it) },
        )

        val authorization = api.beginDeviceAuthorization()
        val tokens = api.pollDeviceAuthorization(authorization)

        assertEquals("ABCD", authorization.userCode)
        assertEquals("access", tokens.accessToken)
        assertEquals(3, requests.size)
        assertTrue(requests[0].body!!.toString(Charsets.UTF_8).contains("grant_type").not())
        assertTrue(requests[1].body!!.toString(Charsets.UTF_8).contains("device_code=dev"))
    }

    @Test
    fun slowDownAddsFiveSeconds() = runSuspendTest {
        val responses = ArrayDeque(
            listOf(
                jsonResponse(400, """{"error":"slow_down"}"""),
                jsonResponse(200, """{"access_token":"access","expires_in":3600}"""),
            ),
        )
        var clock = Instant.parse("2026-08-14T00:00:00Z")
        val sleeps = mutableListOf<Long>()
        val api = NvidiaAuthApi(
            transport = HttpTransport { responses.removeFirst() },
            config = testConfig(),
            now = { clock },
            sleepSeconds = { seconds -> sleeps += seconds; clock = clock.plusSeconds(seconds) },
        )
        api.pollDeviceAuthorization(
            DeviceAuthorization("code", "dev", "https://example.test", expiresInSeconds = 60, pollIntervalSeconds = 2),
        )
        assertEquals(listOf(2L, 7L), sleeps)
    }

    private fun jsonResponse(status: Int, body: String) = HttpResponse(
        statusCode = status,
        body = body.toByteArray(),
    )

    private fun testConfig() = AuthEndpointConfig(
        deviceAuthorizeUrl = "https://example.test/device",
        tokenUrl = "https://example.test/token",
        clientTokenUrl = "https://example.test/client_token",
        userInfoUrl = "https://example.test/user",
        serviceUrlsUrl = "https://example.test/serviceUrls",
        deviceFlowClientId = "device-client",
        mainClientId = "main-client",
        scopes = "openid",
        userAgent = "test",
        displayName = "Apple TV",
    )
}

private fun <T> runSuspendTest(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(object : kotlin.coroutines.Continuation<T> {
        override val context = kotlin.coroutines.EmptyCoroutineContext
        override fun resumeWith(resumeResult: Result<T>) { result = resumeResult }
    })
    return checkNotNull(result).getOrThrow()
}
