package dev.mixcloudtv.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.mixcloudtv.MixcloudTvApp
import dev.mixcloudtv.data.stream.GraphqlStreamResolver
import dev.mixcloudtv.data.stream.StreamResult
import dev.mixcloudtv.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

const val EXTRA_MIME = "mime"

/** Background audio: ExoPlayer inside a MediaSession, so remote keys and the system media card work. */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var refreshedFor: String? = null

    override fun onCreate() {
        super.onCreate()
        val container = (application as MixcloudTvApp).container
        val dataSource = OkHttpDataSource.Factory(container.http)
            .setUserAgent(GraphqlStreamResolver.USER_AGENT)
            .setDefaultRequestProperties(mapOf("Referer" to GraphqlStreamResolver.REFERER))

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(30_000)
            .setSeekForwardIncrementMs(30_000)
            .build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) refreshedFor = null
            }

            override fun onPlayerError(error: PlaybackException) {
                // Stream URLs expire. After a long pause, fetch a fresh one once and carry on.
                val item = player.currentMediaItem ?: return
                if (error.errorCode != PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) return
                if (refreshedFor == item.mediaId) return
                refreshedFor = item.mediaId
                val position = player.currentPosition
                scope.launch {
                    val result = container.streams.resolve(item.mediaId)
                    if (result is StreamResult.Playable) {
                        val fresh = item.buildUpon()
                            .setUri(result.url)
                            .setMimeType(if (result.isHls) androidx.media3.common.MimeTypes.APPLICATION_M3U8 else null)
                            .build()
                        player.setMediaItem(fresh, position)
                        player.prepare()
                        player.play()
                    }
                }
            }
        })

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(object : MediaSession.Callback {
                // MediaItem URIs aren't passed from controller to session, so they travel in requestMetadata.
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
                    mediaItems.map { item ->
                        val uri = item.requestMetadata.mediaUri ?: return@map item
                        item.buildUpon()
                            .setUri(uri)
                            .setMimeType(item.requestMetadata.extras?.getString(EXTRA_MIME))
                            .build()
                    }.toMutableList(),
                )
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }
}
