package dev.gfn.games

import dev.gfn.core.model.GameDetail
import dev.gfn.core.model.GameSummary
import dev.gfn.core.model.GameVariant
import dev.gfn.identity.GfnClientIdentity
import dev.gfn.identity.GfnProtocolDefaults
import dev.gfn.network.HttpRequest
import dev.gfn.network.HttpResponse
import dev.gfn.network.HttpTransport
import dev.gfn.network.Json
import dev.gfn.network.Json.array
import dev.gfn.network.Json.asBoolean
import dev.gfn.network.Json.asObject
import dev.gfn.network.Json.asString
import dev.gfn.network.Json.boolean
import dev.gfn.network.Json.obj
import dev.gfn.network.Json.string
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

sealed class GfnGamesException(message: String) : Exception(message) {
    class Unauthorized : GfnGamesException("GFN 游戏 API 拒绝了当前凭据")
    class Http(val code: Int, message: String) : GfnGamesException(message)
    class GraphQl(message: String) : GfnGamesException(message)
    class Protocol(message: String) : GfnGamesException(message)
}

data class GfnGamesContext(
    val token: String,
    val vpcId: String,
    val localeCode: String,
    val identity: GfnClientIdentity = GfnClientIdentity.WindowsDesktop,
)

class GfnGamesClient(
    private val transport: HttpTransport,
    private val now: () -> Instant = Instant::now,
    private val uuid: () -> UUID = UUID::randomUUID,
) {
    suspend fun fetchCatalog(context: GfnGamesContext): List<GameSummary> = browseCatalog(
        context = context,
        filters = emptyMap(),
        maxPages = 15,
    )

    suspend fun fetchLibrary(context: GfnGamesContext): List<GameSummary> = browseCatalog(
        context = context,
        filters = mapOf(
            "variants" to mapOf(
                "gfn" to mapOf(
                    "library" to mapOf(
                        "status" to mapOf("notEquals" to "NOT_OWNED"),
                    ),
                ),
            ),
        ),
        maxPages = 10,
    )

    suspend fun search(context: GfnGamesContext, query: String): List<GameSummary> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return emptyList()
        return browseCatalog(
            context = context,
            filters = emptyMap(),
            searchString = normalized,
            maxPages = 3,
        )
    }

    suspend fun fetchGameDetail(context: GfnGamesContext, appId: String): GameDetail {
        require(appId.isNotBlank()) { "appId 不能为空" }
        val variables = mapOf(
            "vpcId" to context.vpcId,
            "locale" to context.localeCode,
            "appIds" to listOf(appId),
        )
        val extensions = mapOf(
            "persistedQuery" to mapOf("sha256Hash" to METADATA_QUERY_HASH),
        )
        val query = listOf(
            "requestType" to "appMetaData",
            "extensions" to Json.stringify(extensions),
            "huId" to requestId(),
            "variables" to Json.stringify(variables),
        ).joinToString("&") { (name, value) -> "${enc(name)}=${enc(value)}" }

        val response = transport.execute(
            HttpRequest(
                method = "GET",
                url = "$GRAPHQL_URL?$query",
                headers = gfnHeaders(context),
            ),
        )
        requireSuccess(response, "获取游戏详情")
        val root = parseObject(response, "游戏详情")
        throwIfGraphQlError(root)
        val items = root.obj("data")?.obj("apps")?.array("items").orEmpty()
        val app = items.firstOrNull()?.asObject()
            ?: throw GfnGamesException.Protocol("游戏详情响应不包含 app metadata")
        return metadataToDetail(app, appId)
    }

    private suspend fun browseCatalog(
        context: GfnGamesContext,
        filters: Map<String, Any?>,
        searchString: String? = null,
        maxPages: Int,
        includeGenres: Boolean = true,
    ): List<GameSummary> {
        require(context.vpcId.isNotBlank()) { "vpcId 不能为空" }
        return try {
            val large = runCatching {
                browsePages(context, filters, searchString, 500, maxPages, includeGenres)
            }.getOrElse { error ->
                if (error is GfnGamesException.Http && error.code in 400..499 && error.code !in setOf(401, 403)) {
                    emptyList()
                } else {
                    throw error
                }
            }
            if (large.isNotEmpty()) large else browsePages(
                context, filters, searchString, 200, maxPages, includeGenres,
            )
        } catch (error: GfnGamesException.GraphQl) {
            if (includeGenres && error.message.orEmpty().contains("genre", ignoreCase = true)) {
                browseCatalog(context, filters, searchString, maxPages, includeGenres = false)
            } else {
                throw error
            }
        }
    }

    private suspend fun browsePages(
        context: GfnGamesContext,
        filters: Map<String, Any?>,
        searchString: String?,
        pageSize: Int,
        maxPages: Int,
        includeGenres: Boolean,
    ): List<GameSummary> {
        val games = mutableListOf<GameSummary>()
        val seen = linkedSetOf<String>()
        var cursor = ""

        for (pageIndex in 0 until maxPages) {
            val variables = linkedMapOf<String, Any?>(
                "vpcId" to context.vpcId,
                "locale" to context.localeCode,
                "sortString" to if (searchString.isNullOrBlank()) "sortName:ASC" else "itemMetadata.relevance:DESC,sortName:ASC",
                "fetchCount" to pageSize,
                "cursor" to cursor,
                "filters" to filters,
            )
            if (!searchString.isNullOrBlank()) variables["searchString"] = searchString
            val body = Json.stringify(
                mapOf(
                    "query" to queryFor(searchString, includeGenres),
                    "variables" to variables,
                ),
            ).toByteArray(Charsets.UTF_8)

            val response = transport.execute(
                HttpRequest(
                    method = "POST",
                    url = GRAPHQL_URL,
                    headers = gfnHeaders(context) + ("Content-Type" to "application/json"),
                    body = body,
                ),
            )
            requireSuccess(response, "获取游戏列表")
            val root = parseObject(response, "游戏列表")
            throwIfGraphQlError(root)
            val apps = root.obj("data")?.obj("apps") ?: break
            for (value in apps.array("items").orEmpty()) {
                val item = value.asObject() ?: continue
                val game = browseItemToGame(item) ?: continue
                if (seen.add(game.appId)) games += game
            }
            val pageInfo = apps.obj("pageInfo")
            val hasNext = pageInfo?.boolean("hasNextPage") == true
            val next = pageInfo?.string("endCursor")
            if (!hasNext || next.isNullOrBlank()) return games
            if (next == cursor) throw GfnGamesException.Protocol("GraphQL 分页重复 cursor")
            cursor = next
        }
        return games
    }

    private fun browseItemToGame(item: Map<String, Json.Value>): GameSummary? {
        val id = scalarString(item["id"]) ?: return null
        val variantsRaw = item.array("variants").orEmpty()
        val variants = variantsRaw.mapNotNull { value ->
            val variant = value.asObject() ?: return@mapNotNull null
            val variantId = scalarString(variant["id"]) ?: return@mapNotNull null
            val store = variant.string("appStore")?.takeIf { it.isNotBlank() } ?: "unknown"
            val library = variant.obj("gfn")?.obj("library")
            GameVariant(
                id = variantId,
                appStore = store,
                isOwned = isOwned(library?.string("status")),
            )
        }
        val features = parseFeatures(variantsRaw)
        val images = item.obj("images")
        val genres = item.array("genres").orEmpty().mapNotNull { it.asString() }.filter { it.isNotBlank() }
        return GameSummary(
            appId = id,
            title = item.string("title") ?: id,
            artworkUrl = optimizeImageUrl(images?.string("GAME_BOX_ART"), 272),
            heroImageUrl = optimizeImageUrl(images?.string("TV_BANNER") ?: images?.string("HERO_IMAGE"), 1920),
            genres = genres,
            supportsHdr = "HDR" in features,
            supportsRtx = "RTX" in features,
            supportsReflex = "REFLEX" in features,
            isInLibrary = variants.any { it.isOwned },
            variants = variants,
        )
    }

    private fun metadataToDetail(item: Map<String, Json.Value>, fallbackId: String): GameDetail {
        val id = scalarString(item["id"]) ?: fallbackId
        val images = item.obj("images")
        val variantsRaw = item.array("variants").orEmpty()
        val variants = variantsRaw.mapNotNull { value ->
            val variant = value.asObject() ?: return@mapNotNull null
            val variantId = scalarString(variant["id"]) ?: return@mapNotNull null
            GameVariant(
                id = variantId,
                appStore = variant.string("appStore") ?: "unknown",
                isOwned = isOwned(variant.obj("gfn")?.obj("library")?.string("status")),
            )
        }
        val contentRating = item.obj("contentRatings")?.let { rating ->
            val type = rating.string("type")
            val category = rating.string("categoryKey")
            if (!type.isNullOrBlank() && !category.isNullOrBlank()) "$type $category" else null
        }
        val features = parseFeatures(variantsRaw)
        return GameDetail(
            appId = id,
            title = item.string("title") ?: id,
            description = item.string("longDescription"),
            artworkUrl = optimizeImageUrl(images?.string("GAME_BOX_ART"), 544),
            heroImageUrl = optimizeImageUrl(images?.string("TV_BANNER") ?: images?.string("HERO_IMAGE"), 1920),
            genres = item.array("genres").orEmpty().mapNotNull { it.asString() },
            developer = item.string("developerName"),
            publisher = item.string("publisherName"),
            contentRating = contentRating,
            supportsHdr = "HDR" in features,
            supportsRtx = "RTX" in features,
            supportsReflex = "REFLEX" in features,
            isInLibrary = variants.any { it.isOwned },
            variants = variants,
        )
    }

    private fun parseFeatures(variants: List<Json.Value>): Set<String> {
        val result = linkedSetOf<String>()
        for (value in variants) {
            val features = value.asObject()?.obj("gfn")?.array("features").orEmpty()
            for (featureValue in features) {
                val feature = featureValue.asObject() ?: continue
                when (feature.string("key")) {
                    "RTX_ENABLED" -> if (feature.string("value").equals("true", true)) result += "RTX"
                    "HDR_ENABLED" -> if (feature.string("value").equals("true", true)) result += "HDR"
                    "SUPPORTED_HDR_VERSION" -> if (!feature.array("values").isNullOrEmpty()) result += "HDR"
                    "REFLEX_ENABLED" -> if (feature.string("value").equals("true", true)) result += "REFLEX"
                }
            }
        }
        return result
    }

    private fun throwIfGraphQlError(root: Map<String, Json.Value>) {
        val messages = root.array("errors").orEmpty().mapNotNull { it.asObject()?.string("message") }
        if (messages.isNotEmpty() && root.obj("data")?.obj("apps") == null) {
            throw GfnGamesException.GraphQl(messages.joinToString("; "))
        }
        if (messages.any { it.contains("genre", ignoreCase = true) }) {
            throw GfnGamesException.GraphQl(messages.joinToString("; "))
        }
    }

    private fun gfnHeaders(context: GfnGamesContext): Map<String, String> = linkedMapOf(
        "Accept" to "application/json, text/plain, */*",
        "Origin" to GfnProtocolDefaults.webOrigin,
        "Referer" to GfnProtocolDefaults.webReferer,
        "Authorization" to "GFNJWT ${context.token}",
        "nv-client-id" to GfnProtocolDefaults.clientId,
        "nv-client-type" to "NATIVE",
        "nv-client-version" to GfnProtocolDefaults.clientVersion,
        "nv-client-streamer" to "NVIDIA-CLASSIC",
        "nv-browser-type" to "CHROME",
        "User-Agent" to GfnProtocolDefaults.userAgent,
    ) + context.identity.protocolHeaders().filterKeys {
        it in setOf("NV-Device-OS", "NV-Device-Type", "NV-Device-Make", "NV-Device-Model")
    }

    private fun requireSuccess(response: HttpResponse, context: String) {
        if (response.statusCode == 401) throw GfnGamesException.Unauthorized()
        if (response.statusCode !in 200..299) {
            throw GfnGamesException.Http(response.statusCode, "$context 失败（HTTP ${response.statusCode}）")
        }
    }

    private fun parseObject(response: HttpResponse, context: String): Map<String, Json.Value> =
        try { Json.parseObject(response.bodyText) } catch (error: Exception) {
            throw GfnGamesException.Protocol("$context JSON 无法解析：${error::class.simpleName}")
        }

    private fun queryFor(searchString: String?, includeGenres: Boolean): String {
        val idTitleGenres = if (includeGenres) "id title genres" else "id title"
        val dollar = '$'
        val searchArg = if (searchString.isNullOrBlank()) "" else ", searchQuery: ${dollar}searchString"
        val queryName = if (searchString.isNullOrBlank()) "GetFilterBrowseResults" else "GetSearchFilterResults"
        val searchVar = if (searchString.isNullOrBlank()) "" else ", ${dollar}searchString: String!"
        return """
            query $queryName(${dollar}vpcId: String!, ${dollar}locale: String!, ${dollar}sortString: String!, ${dollar}fetchCount: Int!, ${dollar}cursor: String!$searchVar, ${dollar}filters: AppFilterFields!) {
              apps(vpcId: ${dollar}vpcId, language: ${dollar}locale, orderBy: ${dollar}sortString, first: ${dollar}fetchCount, after: ${dollar}cursor$searchArg, filters: ${dollar}filters) {
                numberReturned pageInfo { hasNextPage endCursor totalCount }
                items {
                  $idTitleGenres
                  images { GAME_BOX_ART TV_BANNER HERO_IMAGE }
                  variants { id appStore supportedControls gfn { status library { status selected } features { __typename ... on GfnSubscriptionFeatureInterface { key } ... on GfnSubscriptionFeatureValue { value } ... on GfnSubscriptionFeatureValueList { values } } } }
                  gfn { playabilityState minimumMembershipTierLabel }
                }
              }
            }
        """.trimIndent()
    }

    private fun scalarString(value: Json.Value?): String? = when (value) {
        is Json.Value.Str -> value.value
        is Json.Value.Num -> value.value
        else -> null
    }

    private fun isOwned(status: String?): Boolean = status?.uppercase() in OWNED_STATUSES

    private fun optimizeImageUrl(url: String?, width: Int): String? {
        if (url.isNullOrBlank()) return null
        return if (url.contains("img.nvidiagrid.net") && !url.contains(";f=")) "$url;f=webp;w=$width" else url
    }

    private fun requestId(): String {
        val timestamp = java.lang.Long.toHexString(now().toEpochMilli())
        val nonce = uuid().toString().replace("-", "").lowercase()
        return timestamp + nonce
    }

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private companion object {
        const val GRAPHQL_URL = "https://games.geforce.com/graphql"
        const val METADATA_QUERY_HASH = "cf8b620dfd03617017ba7c858cee65197e1ace5180e41be194b39227227ced63"
        val OWNED_STATUSES = setOf("MANUAL", "PLATFORM_SYNC", "IN_LIBRARY")
    }
}
