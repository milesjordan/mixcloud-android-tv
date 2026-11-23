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
    fun resumePoint(key: String, audioLengthSec: Int): Long {
        val saved = get(key)
        val lengthMs = audioLengthSec * 1000L
        return if (saved > 30_000 && (lengthMs == 0L || saved < lengthMs - 60_000)) saved else 0L
    }
}
