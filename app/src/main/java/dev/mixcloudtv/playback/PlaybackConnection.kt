package dev.mixcloudtv.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.mixcloudtv.data.Cloudcast
import dev.mixcloudtv.data.MixcloudClient
import dev.mixcloudtv.data.PositionStore
import dev.mixcloudtv.data.RecentStore
import dev.mixcloudtv.data.stream.StreamResolver
import dev.mixcloudtv.data.stream.StreamResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class NowPlaying(
    val cloudcast: Cloudcast? = null,
    val resolving: Boolean = false,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val error: String? = null,
)

/** App-wide handle on the playback service, used by every screen. */
class PlaybackConnection(
    private val context: Context,
    private val client: MixcloudClient,
    private val streams: StreamResolver,
    private val positions: PositionStore,
    private val recent: RecentStore,
) {
    private val scope = MainScope()
    private var controller: MediaController? = null
    private var ticker: Job? = null
    private val _state = MutableStateFlow(NowPlaying())
    val state: StateFlow<NowPlaying> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = _state.update { it.copy(isPlaying = isPlaying) }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.update { it.copy(buffering = playbackState == Player.STATE_BUFFERING) }
            if (playbackState == Player.STATE_ENDED) _state.value.cloudcast?.let { positions.clear(it.key) }
        }

        override fun onPlayerError(error: PlaybackException) =
            _state.update { it.copy(error = "Playback failed: ${error.errorCodeName}") }
    }

    private suspend fun controller(): MediaController {
        controller?.let { return it }
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val c = MediaController.Builder(context, token)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    this@PlaybackConnection.controller = null
                }
            })
            .buildAsync().await()
        c.addListener(listener)
        controller = c
        startTicker()
        return c
    }

    private var playJob: Job? = null

    fun play(cast: Cloudcast) {
        // A newer selection cancels an older one that is still resolving its stream.
        playJob?.cancel()
        playJob = scope.launch {
            saveCurrentPosition()
            _state.value = NowPlaying(cloudcast = cast, resolving = true)
            when (val result = streams.resolve(cast.key)) {
                is StreamResult.Unavailable -> {
                    controller?.stop()
                    _state.update { it.copy(resolving = false, error = result.message) }
                }
                is StreamResult.Playable -> {
                    val c = controller()
                    val item = MediaItem.Builder()
                        .setMediaId(cast.key)
                        .setRequestMetadata(
                            MediaItem.RequestMetadata.Builder()
                                .setMediaUri(result.url.toUri())
                                .setExtras(bundleOf(EXTRA_MIME to if (result.isHls) MimeTypes.APPLICATION_M3U8 else null))
                                .build(),
                        )
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(cast.name)
                                .setArtist(cast.user.name)
                                .setArtworkUri(cast.pictures.big()?.toUri())
                                .build(),
                        )
                        .build()
                    c.setMediaItem(item, positions.resumePoint(cast.key, cast.audioLength))
                    c.prepare()
                    c.play()
                    recent.add(cast)
                    _state.update { it.copy(resolving = false) }
                }
            }
            // List responses leave out the tracklist; fetch the full show for the now-playing screen.
            if (cast.sections.isEmpty()) {
                runCatching { client.cloudcast(cast.key) }.onSuccess { full ->
                    _state.update { if (it.cloudcast?.key == full.key) it.copy(cloudcast = full) else it }
                }
            }
        }
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun resume() {
        controller?.play()
    }

    fun pause() {
        controller?.pause()
    }

    fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        seekTo((c.currentPosition + deltaMs).coerceAtLeast(0))
    }

    fun seekTo(positionMs: Long) {
        val c = controller ?: return
        c.seekTo(positionMs)
        // Update straight away so the bar doesn't jump back until the next tick.
        _state.update { it.copy(positionMs = positionMs) }
    }

    private fun saveCurrentPosition() {
        val c = controller ?: return
        val key = c.currentMediaItem?.mediaId ?: return
        if (c.playbackState != Player.STATE_ENDED) positions.put(key, c.currentPosition)
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            var n = 0
            while (isActive) {
                controller?.let { c ->
                    _state.update {
                        it.copy(positionMs = c.currentPosition, durationMs = c.duration.coerceAtLeast(0))
                    }
                    if (++n % 20 == 0 && c.isPlaying) saveCurrentPosition()
                }
                delay(500)
            }
        }
    }
}
