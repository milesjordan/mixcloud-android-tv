package dev.mixcloudtv.data.stream

import dev.mixcloudtv.data.stream.StreamResult.Reason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder

/**
 * Resolves stream URLs through the GraphQL API that mixcloud.com itself uses.
 * Undocumented: if Mixcloud changes it, this is the class to fix.
 *
 * Restrictions Mixcloud reports (licence/geo blocks, repeat-play limits, Select-only shows)
 * are passed back to the UI as [StreamResult.Unavailable] and never worked around.
 */
class GraphqlStreamResolver(
    private val http: OkHttpClient,
    private val json: Json,
    private val endpoint: String = "https://app.mixcloud.com/graphql",
) : StreamResolver {

    override suspend fun resolve(cloudcastKey: String): StreamResult {
        val (username, slug) = splitKey(cloudcastKey)
            ?: return StreamResult.Unavailable(Reason.NotFound, "That show link doesn't look right.")

        val query = """
            { cloudcastLookup(lookup: {username: ${quote(username)}, slug: ${quote(slug)}}) {
                isExclusive restrictedReason streamInfo { hlsUrl url } } }
        """.trimIndent()

        val request = Request.Builder()
            .url(endpoint.toHttpUrl().newBuilder().addQueryParameter("query", query).build())
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .header("Accept", "application/json")
            .build()

        val body = try {
            withContext(Dispatchers.IO) {
                http.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext null.also { lastHttpCode = resp.code }
                    }
                    resp.body.string()
                }
            }
        } catch (e: Exception) {
            return StreamResult.Unavailable(Reason.Error, "Couldn't reach Mixcloud: ${e.message ?: e.javaClass.simpleName}")
        } ?: return StreamResult.Unavailable(Reason.Error, "Mixcloud returned HTTP $lastHttpCode for the stream lookup.")

        return parse(body)
    }

    @Volatile private var lastHttpCode: Int = 0

    internal fun parse(body: String): StreamResult {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return StreamResult.Unavailable(Reason.Error, "Unexpected reply from Mixcloud.")
        val lookup = root["data"]?.objOrNull()?.get("cloudcastLookup")?.objOrNull()
            ?: return StreamResult.Unavailable(Reason.NotFound, "Mixcloud couldn't find this show.")

        val restricted = lookup["restrictedReason"]?.stringOrNull()
        if (restricted != null) {
            return when (restricted) {
                "tracklist", "country" ->
                    StreamResult.Unavailable(Reason.Geo, "This show isn't available in your country for licensing reasons.")
                "repeat_play" ->
                    StreamResult.Unavailable(Reason.PlayLimit, "You've reached Mixcloud's play limit for this show. Try again later.")
                else ->
                    StreamResult.Unavailable(Reason.Restricted, "Mixcloud has restricted this show ($restricted).")
            }
        }

        val info = lookup["streamInfo"]?.objOrNull()
        val hls = info?.get("hlsUrl")?.stringOrNull()?.let(::decodeOrNull)
        val progressive = info?.get("url")?.stringOrNull()?.let(::decodeOrNull)
        return when {
            hls != null -> StreamResult.Playable(hls, isHls = true)
            progressive != null -> StreamResult.Playable(progressive, isHls = false)
            lookup["isExclusive"]?.jsonPrimitive?.booleanOrNull == true ->
                StreamResult.Unavailable(Reason.Exclusive, "This is a Mixcloud Select exclusive. It can't be played in this app.")
            else -> StreamResult.Unavailable(Reason.Restricted, "Mixcloud didn't return a stream for this show.")
        }
    }

    private fun decodeOrNull(s: String): String? =
        runCatching { XorCipher.decode(s) }.getOrNull()?.takeIf { it.startsWith("http") }

    private fun quote(s: String): String = Json.encodeToString(JsonPrimitive(s))

    private fun kotlinx.serialization.json.JsonElement.objOrNull(): JsonObject? = this as? JsonObject

    private fun kotlinx.serialization.json.JsonElement.stringOrNull(): String? =
        if (this is JsonNull) null else (this as? JsonPrimitive)?.contentOrNull

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 11; AFTKA) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
        const val REFERER = "https://www.mixcloud.com/"

        /** "/user/slug/" (possibly percent-encoded) → ("user", "slug"). */
        fun splitKey(key: String): Pair<String, String>? {
            val parts = key.trim('/').split('/')
            if (parts.size != 2 || parts.any { it.isBlank() }) return null
            fun dec(s: String) = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
            return dec(parts[0]) to dec(parts[1])
        }
    }
}
