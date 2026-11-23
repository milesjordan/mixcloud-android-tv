package dev.mixcloudtv

import android.app.Application
import android.content.Context
import dev.mixcloudtv.data.MixcloudClient
import dev.mixcloudtv.data.PositionStore
import dev.mixcloudtv.data.RecentStore
import dev.mixcloudtv.data.auth.AuthRepository
import dev.mixcloudtv.data.stream.GraphqlStreamResolver
import dev.mixcloudtv.data.stream.StreamResolver
import dev.mixcloudtv.playback.PlaybackConnection
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class MixcloudTvApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Hand-wired dependencies; small enough not to need a DI framework. */
class AppContainer(context: Context) {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        isLenient = true
    }
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    val auth = AuthRepository(context, http, json)
    val client = MixcloudClient(http, json) { auth.currentToken() }
    val streams: StreamResolver = GraphqlStreamResolver(http, json)
    val positions = PositionStore(context)
    val recent = RecentStore(context, json)
    val playback by lazy { PlaybackConnection(context.applicationContext, client, streams, positions, recent) }

    /** Checks a token against /me/ and stores it with the username. */
    suspend fun completeLogin(token: String) {
        val me = client.me(token)
        auth.save(token, me.username)
    }
}
