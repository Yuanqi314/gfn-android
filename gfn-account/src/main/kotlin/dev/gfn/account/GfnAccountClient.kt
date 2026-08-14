package dev.gfn.account

import dev.gfn.core.model.EntitledResolution
import dev.gfn.core.model.SubscriptionInfo
import dev.gfn.identity.GfnClientIdentity
import dev.gfn.identity.GfnProtocolDefaults
import dev.gfn.network.HttpRequest
import dev.gfn.network.HttpResponse
import dev.gfn.network.HttpTransport
import dev.gfn.network.Json
import dev.gfn.network.Json.array
import dev.gfn.network.Json.asObject
import dev.gfn.network.Json.boolean
import dev.gfn.network.Json.int
import dev.gfn.network.Json.obj
import dev.gfn.network.Json.string
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

sealed class GfnAccountException(message: String) : Exception(message) {
    class Unauthorized : GfnAccountException("GFN 凭据已失效")
    class Http(val code: Int, message: String) : GfnAccountException(message)
    class Protocol(message: String) : GfnAccountException(message)
}

data class GfnAccountContext(
    val token: String,
    val userId: String,
    val streamingServiceUrl: String,
    val identity: GfnClientIdentity = GfnClientIdentity.WindowsDesktop,
)

class GfnAccountClient(
    private val transport: HttpTransport,
) {
    suspend fun fetchVpcId(context: GfnAccountContext): String {
        val base = context.streamingServiceUrl.trimEnd('/')
        val response = transport.execute(
            HttpRequest(
                method = "GET",
                url = "$base/v2/serverInfo",
                headers = serverInfoHeaders(context),
            ),
        )
        requireSuccess(response, "获取 VPC / serverInfo")
        val root = parseObject(response, "serverInfo")
        return root.obj("requestStatus")?.string("serverId")?.takeIf { it.isNotBlank() }
            ?: throw GfnAccountException.Protocol("serverInfo 响应缺少 requestStatus.serverId")
    }

    suspend fun fetchSubscription(
        context: GfnAccountContext,
        vpcId: String,
        localeCode: String,
    ): SubscriptionInfo {
        require(vpcId.isNotBlank()) { "vpcId 不能为空" }
        val query = listOf(
            "serviceName" to "gfn_pc",
            "languageCode" to localeCode,
            "vpcId" to vpcId,
            "userId" to context.userId,
        ).joinToString("&") { (name, value) -> "${enc(name)}=${enc(value)}" }

        val response = transport.execute(
            HttpRequest(
                method = "GET",
                url = "https://mes.geforcenow.com/v4/subscriptions?$query",
                headers = subscriptionHeaders(context),
            ),
        )
        requireSuccess(response, "获取订阅信息")
        return parseSubscription(response)
    }

    private fun serverInfoHeaders(context: GfnAccountContext): Map<String, String> = linkedMapOf(
        "Authorization" to "GFNJWT ${context.token}",
        "Accept" to "application/json",
        "Origin" to GfnProtocolDefaults.webOrigin,
        "Referer" to GfnProtocolDefaults.webReferer,
        "nv-client-id" to GfnProtocolDefaults.clientId,
        "nv-client-type" to "BROWSER",
        "nv-client-version" to GfnProtocolDefaults.clientVersion,
        "nv-client-streamer" to "WEBRTC",
        "nv-browser-type" to "CHROME",
        "User-Agent" to GfnProtocolDefaults.userAgent,
    ) + context.identity.protocolHeaders().filterKeys {
        it in setOf("NV-Device-OS", "NV-Device-Type", "NV-Device-Make", "NV-Device-Model")
    }

    private fun subscriptionHeaders(context: GfnAccountContext): Map<String, String> = linkedMapOf(
        "Authorization" to "GFNJWT ${context.token}",
        "Accept" to "application/json",
        "nv-client-id" to GfnProtocolDefaults.clientId,
        "nv-client-type" to "NATIVE",
        "nv-client-version" to GfnProtocolDefaults.clientVersion,
        "nv-client-streamer" to "NVIDIA-CLASSIC",
        "User-Agent" to GfnProtocolDefaults.userAgent,
    ) + context.identity.protocolHeaders().filterKeys {
        it in setOf("NV-Device-OS", "NV-Device-Type", "NV-Device-Make", "NV-Device-Model")
    }

    private fun parseSubscription(response: HttpResponse): SubscriptionInfo {
        val root = try { Json.parse(response.bodyText) } catch (error: Exception) {
            throw GfnAccountException.Protocol("MES JSON 无法解析：${error::class.simpleName}")
        }
        val obj = when (root) {
            is Json.Value.Obj -> root.value
            is Json.Value.Arr -> root.value.firstOrNull()?.asObject()
            else -> null
        } ?: throw GfnAccountException.Protocol("MES 响应既不是 object，也不是非空 array")

        val tier = obj.string("membershipTier") ?: obj.string("type") ?: "FREE"
        val resolutions = obj.obj("features")?.array("resolutions").orEmpty().mapNotNull { value ->
            val item = value.asObject() ?: return@mapNotNull null
            if (item.boolean("isEntitled") == false) return@mapNotNull null
            val width = item.int("widthInPixels") ?: return@mapNotNull null
            val height = item.int("heightInPixels") ?: return@mapNotNull null
            val fps = item.int("framesPerSecond") ?: return@mapNotNull null
            EntitledResolution(width, height, fps)
        }
        return SubscriptionInfo(
            membershipTier = if (tier.equals("FREE", ignoreCase = true)) "Free" else tier,
            isUnlimited = obj.string("subType")?.equals("UNLIMITED", ignoreCase = true) == true,
            remainingMinutes = obj.int("remainingTimeInMinutes"),
            totalMinutes = obj.int("totalTimeInMinutes"),
            entitledResolutions = resolutions,
        )
    }

    private fun parseObject(response: HttpResponse, context: String): Map<String, Json.Value> =
        try { Json.parseObject(response.bodyText) } catch (error: Exception) {
            throw GfnAccountException.Protocol("$context JSON 无法解析：${error::class.simpleName}")
        }

    private fun requireSuccess(response: HttpResponse, context: String) {
        if (response.statusCode == 401) throw GfnAccountException.Unauthorized()
        if (response.statusCode !in 200..299) {
            throw GfnAccountException.Http(response.statusCode, "$context 失败（HTTP ${response.statusCode}）")
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
