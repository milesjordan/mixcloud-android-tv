package dev.mixcloudtv.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Pictures(
    val thumbnail: String? = null,
    val medium: String? = null,
    val large: String? = null,
    @SerialName("320wx320h") val w320: String? = null,
    @SerialName("extra_large") val extraLarge: String? = null,
    @SerialName("640wx640h") val w640: String? = null,
    @SerialName("1024wx1024h") val w1024: String? = null,
) {
    /** Artwork sized for a row card. */
    fun card(): String? = w320 ?: large ?: extraLarge ?: medium ?: thumbnail

    /** Artwork sized for the show and now-playing screens. */
    fun big(): String? = w640 ?: w1024 ?: extraLarge ?: card()
}

@Serializable
data class User(
    val key: String = "",
    val name: String = "",
    val username: String = "",
    val pictures: Pictures = Pictures(),
    val biog: String? = null,
    val city: String? = null,
    val country: String? = null,
    @SerialName("follower_count") val followerCount: Int = 0,
    @SerialName("cloudcast_count") val cloudcastCount: Int = 0,
)

@Serializable
data class Tag(val key: String = "", val name: String = "")

@Serializable
data class Artist(val name: String = "")

@Serializable
data class Track(val name: String = "", val artist: Artist? = null)

@Serializable
data class Section(
    @SerialName("start_time") val startTime: Int? = null,
    @SerialName("section_type") val type: String? = null,
    val track: Track? = null,
    val chapter: String? = null,
) {
    fun label(): String {
        val t = track
        return when {
            t != null && !t.artist?.name.isNullOrBlank() -> "${t.artist?.name} – ${t.name}"
            t != null -> t.name
            !chapter.isNullOrBlank() -> chapter
            else -> "Unknown"
        }
    }
}

@Serializable
data class Cloudcast(
    val key: String = "",
    val name: String = "",
    val url: String = "",
    val slug: String = "",
    val user: User = User(),
    val pictures: Pictures = Pictures(),
    @SerialName("audio_length") val audioLength: Int = 0,
    @SerialName("created_time") val createdTime: String? = null,
    @SerialName("play_count") val playCount: Int = 0,
    @SerialName("favorite_count") val favoriteCount: Int = 0,
    val tags: List<Tag> = emptyList(),
    val sections: List<Section> = emptyList(),
    val description: String? = null,
) {
    /** The tracklist section playing at [positionSec], if Mixcloud published one. */
    fun sectionAt(positionSec: Long): Section? =
        sections.filter { it.startTime != null }
            .sortedBy { it.startTime }
            .lastOrNull { (it.startTime ?: 0) <= positionSec }
}

@Serializable
data class Paging(val next: String? = null, val previous: String? = null)

@Serializable
data class Page<T>(val data: List<T> = emptyList(), val paging: Paging? = null)

/** An entry in a user's activity feed. Only the uploads part is used. */
@Serializable
data class FeedItem(val cloudcasts: List<Cloudcast> = emptyList())
