package dev.gfn.network

import java.net.HttpURLConnection
import java.net.URI

/** 与具体 HTTP 库解耦的协议层请求。 */
data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
)

data class HttpResponse(
    val statusCode: Int,
    val headers: Map<String, List<String>> = emptyMap(),
    val body: ByteArray = byteArrayOf(),
) {
    val bodyText: String
        get() = body.toString(Charsets.UTF_8)
}

fun interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
}

/**
 * 第二版可验证 transport。
 *
 * 这是阻塞 I/O 实现，调用方必须在 IO dispatcher 上调用。协议层保持 HttpTransport 抽象，
 * 后续迁移到 OkHttp 时不影响 gfn-auth / gfn-cloudmatch。
 */
class UrlConnectionHttpTransport(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 30_000,
) : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpResponse {
        val connection = (URI.create(request.url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = request.method.uppercase()
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            instanceFollowRedirects = true
            useCaches = false
            request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }

        try {
            request.body?.let { body ->
                connection.doOutput = true
                connection.outputStream.use { it.write(body) }
            }

            val statusCode = connection.responseCode
            val stream = if (statusCode >= 400) connection.errorStream else connection.inputStream
            val body = stream?.use { it.readBytes() } ?: byteArrayOf()
            val headers = connection.headerFields
                .filterKeys { it != null }
                .mapKeys { (name, _) -> name!! }
                .mapValues { (_, values) -> values ?: emptyList() }

            return HttpResponse(statusCode = statusCode, headers = headers, body = body)
        } finally {
            connection.disconnect()
        }
    }
}

/** 网络日志必须先经过此处脱敏。 */
object NetworkRedaction {
    private val sensitiveHeaderNames = setOf(
        "authorization",
        "cookie",
        "set-cookie",
        "x-device-id",
    )

    fun headers(headers: Map<String, String>): Map<String, String> = headers.mapValues { (name, value) ->
        if (name.lowercase() in sensitiveHeaderNames) "<已脱敏>" else value
    }
}
