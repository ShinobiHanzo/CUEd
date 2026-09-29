package dev.cued.app.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.ListenableFuture
import dev.cued.app.CuedApp
import dev.cued.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future

/**
 * Keeps playback alive in the background and publishes the media
 * notification / lock-screen controls. All the playback logic lives in
 * [CrossfadePlayer]; this class only wires the session around it.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        val graph = CuedApp.graph(this)
        val player = graph.player.player
        val launch = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_NOW_PLAYING),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        session = MediaSession.Builder(this, player)
            .setId("cued")
            .setSessionActivity(launch)
            .setCallback(object : MediaSession.Callback {
                /**
                 * Controllers send items with only a mediaId (the URI is stripped across the
                 * session boundary). Fill in the URI and metadata from the library.
                 */
                override fun onAddMediaItems(
                    mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> = scope.future {
                    val ids = mediaItems.mapNotNull { it.mediaId.toLongOrNull() }
                    val tracks = graph.library.tracks(ids).associateBy { it.id }
                    mediaItems.mapNotNull { item ->
                        if (item.localConfiguration != null) item
                        else item.mediaId.toLongOrNull()?.let { tracks[it] }?.let { MediaItems.fromTrack(it) }
                    }.toMutableList()
                }
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        scope.cancel()
        super.onDestroy()
    }
}
