package dev.mixcloudtv.data

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Shows played on this TV, newest first. Mixcloud only records listens made in its own players,
 * so plays in this app never reach the account's Mixcloud listening history.
 */
class RecentStore(context: Context, private val json: Json) {
    private val prefs = context.applicationContext.getSharedPreferences("recent", Context.MODE_PRIVATE)
    private val serializer = ListSerializer(Cloudcast.serializer())

    fun get(): List<Cloudcast> =
        prefs.getString(KEY, null)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    fun add(cast: Cloudcast) {
        prefs.edit().putString(KEY, json.encodeToString(serializer, withPlayed(get(), cast))).apply()
    }

    companion object {
        private const val KEY = "shows"
        const val MAX = 30

        /** [cast] moved to the front of [list], without its bulky tracklist and description. */
        fun withPlayed(list: List<Cloudcast>, cast: Cloudcast, max: Int = MAX): List<Cloudcast> =
            (listOf(cast.copy(sections = emptyList(), description = null)) + list.filter { it.key != cast.key }).take(max)
    }
}
