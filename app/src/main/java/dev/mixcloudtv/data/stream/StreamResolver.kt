package dev.mixcloudtv.data.stream

sealed interface StreamResult {
    data class Playable(val url: String, val isHls: Boolean) : StreamResult
    data class Unavailable(val reason: Reason, val message: String) : StreamResult

    enum class Reason { Geo, PlayLimit, Exclusive, Restricted, NotFound, Error }
}

/**
 * Turns a show key ("/username/slug/") into a playable audio URL.
 * Mixcloud's public API has no audio URLs, so this is the one piece that relies on
 * the website's private API. Keep everything that depends on it behind this interface.
 */
fun interface StreamResolver {
    suspend fun resolve(cloudcastKey: String): StreamResult
}
