package dev.mixcloudtv.data.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mixcloudtv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

private val Context.authStore by preferencesDataStore("auth")

/** Mixcloud documents a form-encoded reply ("access_token=..."); JSON is accepted too. */
internal fun parseTokenResponse(json: Json, body: String): String? {
    val fromJson = runCatching {
        json.parseToJsonElement(body).jsonObject["access_token"]?.jsonPrimitive?.content
    }.getOrNull()
    val token = fromJson ?: body.trim().split('&')
        .firstOrNull { it.startsWith("access_token=") }
        ?.substringAfter('=')
        ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
    return token?.takeIf { it.isNotBlank() }
}

/** Mixcloud OAuth 2.0 (authorisation-code flow) and token storage. */
class AuthRepository(
    context: Context,
    private val http: OkHttpClient,
    private val json: Json,
) {
    private val store = context.applicationContext.authStore

    val token: Flow<String?> = store.data.map { it[TOKEN] }
    val username: Flow<String?> = store.data.map { it[USERNAME] }

    suspend fun currentToken(): String? = token.first()

    val isConfigured: Boolean
        get() = BuildConfig.MIXCLOUD_CLIENT_ID.isNotBlank() && BuildConfig.MIXCLOUD_CLIENT_SECRET.isNotBlank()

    /**
     * With [redirectUri] null, Mixcloud shows the authorisation code on screen for the user
     * to type into the app, which needs no network path from the phone back to the TV.
     */
    fun authorizeUrl(redirectUri: String? = REDIRECT_URI): String =
        "https://www.mixcloud.com/oauth/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", BuildConfig.MIXCLOUD_CLIENT_ID)
            .apply { if (redirectUri != null) addQueryParameter("redirect_uri", redirectUri) }
            .build().toString()

    /** [redirectUri] must be the same one (or null) used for [authorizeUrl]. */
    suspend fun exchangeCode(code: String, redirectUri: String? = REDIRECT_URI): String {
        val url = "https://www.mixcloud.com/oauth/access_token".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", BuildConfig.MIXCLOUD_CLIENT_ID)
            .apply { if (redirectUri != null) addQueryParameter("redirect_uri", redirectUri) }
            .addQueryParameter("client_secret", BuildConfig.MIXCLOUD_CLIENT_SECRET)
            .addQueryParameter("code", code.trim())
            .build()
        return withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                val body = resp.body.string()
                val token = parseTokenResponse(json, body)
                if (!resp.isSuccessful || token.isNullOrBlank()) {
                    throw IOException("Mixcloud didn't return a token (HTTP ${resp.code}).")
                }
                token
            }
        }
    }

    suspend fun save(token: String, username: String) {
        store.edit {
            it[TOKEN] = token
            it[USERNAME] = username
        }
    }

    suspend fun logout() {
        store.edit { it.clear() }
    }

    companion object {
        const val REDIRECT_URI = "http://localhost/mixcloudtv-callback"
        private val TOKEN = stringPreferencesKey("access_token")
        private val USERNAME = stringPreferencesKey("username")
    }
}
