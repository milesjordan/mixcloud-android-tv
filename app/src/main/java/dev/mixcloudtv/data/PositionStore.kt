package dev.mixcloudtv.data

import android.content.Context

/** Remembers how far into each show you got, so it can resume. */
class PositionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("positions", Context.MODE_PRIVATE)

    fun get(key: String): Long = prefs.getLong(key, 0L)

    fun put(key: String, positionMs: Long) {
        prefs.edit().putLong(key, positionMs).apply()
    }

    fun clear(key: String) {
        prefs.edit().remove(key).apply()
    }

    /** Where to start [key] from: the saved position, unless it's near the start or the end. */
    fun resumePoint(key: String, audioLengthSec: Int): Long = resumePoint(get(key), audioLengthSec)

    companion object {
        /** Resume from [savedMs] unless it's in the first 30 s or the last minute of the show. */
        fun resumePoint(savedMs: Long, audioLengthSec: Int): Long {
            val lengthMs = audioLengthSec * 1000L
            return if (savedMs > 30_000 && (lengthMs == 0L || savedMs < lengthMs - 60_000)) savedMs else 0L
        }
    }
}
