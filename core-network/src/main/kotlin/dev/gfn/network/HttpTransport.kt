package dev.gfn.network

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
)

fun interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
}

/**
 * Network logging must never expose OAuth tokens, GFN JWTs, device codes, or personal IDs.
 */
object NetworkRedaction {
    private val sensitiveHeaderNames = setOf("authorization", "cookie", "set-cookie")

    fun headers(headers: Map<String, String>): Map<String, String> = headers.mapValues { (name, value) ->
        if (name.lowercase() in sensitiveHeaderNames) "<redacted>" else value
    }
}
