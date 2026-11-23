package dev.mixcloudtv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class MixcloudException(val code: Int, message: String) : IOException(message)

/**
 * Client for Mixcloud's public REST API (https://www.mixcloud.com/developers/).
 * Object keys from the API look like "/username/show-slug/" and are used as paths directly.
 */
class MixcloudClient(
    private val http: OkHttpClient,
    private val json: Json,
    private val baseUrl: String = "https://api.mixcloud.com",
    private val tokenProvider: suspend () -> String?,
) {
    private val castPage = Page.serializer(Cloudcast.serializer())
    private val userPage = Page.serializer(User.serializer())

    suspend fun cloudcasts(path: String, limit: Int = 30): List<Cloudcast> =
        get(path, castPage, mapOf("limit" to "$limit")).data.distinctBy { it.key }

    suspend fun users(path: String, limit: Int = 30): List<User> =
        get(path, userPage, mapOf("limit" to "$limit")).data.distinctBy { it.key }

    suspend fun cloudcast(key: String): Cloudcast = get(key, Cloudcast.serializer())

    suspend fun user(key: String): User = get(key, User.serializer())

    suspend fun searchCloudcasts(query: String): List<Cloudcast> =
        get("/search/", castPage, mapOf("q" to query, "type" to "cloudcast", "limit" to "30"))
            .data.distinctBy { it.key }

    suspend fun searchUsers(query: String): List<User> =
        get("/search/", userPage, mapOf("q" to query, "type" to "user", "limit" to "20"))
            .data.distinctBy { it.key }

    /**
     * Latest uploads from the people [username] follows, newest first. Mixcloud's /feed/ endpoint
     * is the user's own activity, so this is built from each followed account's uploads instead.
     */
    suspend fun followingUploads(username: String, accounts: Int = 30, perAccount: Int = 5): List<Cloudcast> =
        coroutineScope {
            val limit = Semaphore(6)
            users("/$username/following/", accounts)
                .map { u -> async { limit.withPermit { runCatching { cloudcasts(u.key + "cloudcasts/", perAccount) }.getOrDefault(emptyList()) } } }
                .awaitAll().flatten()
                .distinctBy { it.key }
                .sortedByDescending { it.createdTime.orEmpty() }
                .take(40)
        }

    /** Lower-cased keys of everyone [username] follows, following Mixcloud's paging up to [max]. */
    suspend fun followingKeys(username: String, max: Int = 1000): Set<String> {
        val keys = linkedSetOf<String>()
        var params = mapOf("limit" to "100")
        while (keys.size < max) {
            val page = get("/$username/following/", userPage, params)
            page.data.forEach { keys += it.key.lowercase() }
            val until = page.paging?.next?.toHttpUrlOrNull()?.queryParameter("until")
            if (page.data.isEmpty() || until == null) break
            params = mapOf("limit" to "100", "until" to until)
        }
        return keys
    }

    /** The logged-in user. Pass [token] to check a token before it is saved. */
    suspend fun me(token: String? = null): User = get("/me/", User.serializer(), token = token)

    suspend fun favorite(cloudcastKey: String) = send(cloudcastKey + "favorite/", "POST")
    suspend fun unfavorite(cloudcastKey: String) = send(cloudcastKey + "favorite/", "DELETE")
    suspend fun listenLater(cloudcastKey: String) = send(cloudcastKey + "listen-later/", "POST")
    suspend fun follow(userKey: String) = send(userKey + "follow/", "POST")
    suspend fun unfollow(userKey: String) = send(userKey + "follow/", "DELETE")

    private fun url(path: String, params: Map<String, String>): HttpUrl {
        val normalised = if (path.startsWith("/")) path else "/$path"
        val builder = "$baseUrl$normalised".toHttpUrl().newBuilder()
        params.forEach { (k, v) -> builder.setQueryParameter(k, v) }
        return builder.build()
    }

    /** Sends the token as a header (not a query parameter) so it stays out of URLs and logs. */
    private suspend fun Request.Builder.auth(token: String? = null): Request.Builder = apply {
        (token ?: tokenProvider())?.let { header("Authorization", "Bearer $it") }
    }

    private suspend fun <T> get(
        path: String,
        deserializer: DeserializationStrategy<T>,
        params: Map<String, String> = emptyMap(),
        token: String? = null,
    ): T {
        val request = Request.Builder()
            .url(url(path, params))
            .header("Accept", "application/json")
            .auth(token)
            .build()
        return withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) throw MixcloudException(resp.code, errorMessage(body) ?: "HTTP ${resp.code}")
                json.decodeFromString(deserializer, body)
            }
        }
    }

    private suspend fun send(path: String, method: String) {
        val request = Request.Builder()
            .url(url(path, emptyMap()))
            .method(method, ByteArray(0).toRequestBody())
            .auth()
            .build()
        withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw MixcloudException(resp.code, errorMessage(resp.body.string()) ?: "HTTP ${resp.code}")
                }
            }
        }
    }

    private fun errorMessage(body: String): String? = runCatching {
        json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
    }.getOrNull()
}
