package dev.cued.app.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import dev.cued.app.CuedApp
import dev.cued.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future

/**
 * Keeps playback alive in the background, publishes the media notification /
 * lock-screen controls, exposes the library to Android Auto (and any other
 * MediaBrowser client) and answers voice requests routed through the media
 * session ("play <something> on CUEd"). All playback logic lives in
 * [CrossfadePlayer]; this class only wires the session around it.
 */
@UnstableApi
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        val graph = CuedApp.graph(this)
        val player = graph.player.player
        val tree = LibraryTree(graph.library)
        val launch = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_NOW_PLAYING),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        session = MediaLibrarySession.Builder(this, player, object : MediaLibrarySession.Callback {

            /**
             * Controllers send items with only a mediaId (the URI is stripped across the
             * session boundary); Assistant/Auto voice requests arrive as an item whose
             * requestMetadata.searchQuery is set. Resolve both against the library and
             * expand container ids (playlist/…, genre/…, smart/…) into their tracks.
             */
            override fun onAddMediaItems(
                mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
            ): ListenableFuture<MutableList<MediaItem>> = scope.future {
                val out = ArrayList<MediaItem>()
                for (item in mediaItems) {
                    val query = item.requestMetadata.searchQuery
                    when {
                        item.localConfiguration != null -> out += item
                        query != null -> out += tree.search(query)
                        else -> {
                            val id = item.mediaId
                            val trackId = id.toLongOrNull()
                            if (trackId != null) graph.library.track(trackId)?.let { out += MediaItems.fromTrack(it) }
                            else tree.expand(id)?.let { tracks -> out += tracks.map { MediaItems.fromTrack(it) } }
                        }
                    }
                }
                out
            }

            override fun onGetLibraryRoot(
                session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?,
            ): ListenableFuture<LibraryResult<MediaItem>> = scope.future { LibraryResult.ofItem(tree.root(), params) }

            override fun onGetItem(
                session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String,
            ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
                tree.item(mediaId)?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            }

            override fun onGetChildren(
                session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?,
            ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
                val all = tree.children(parentId) ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                val from = (page * pageSize).coerceAtMost(all.size)
                val to = (from + pageSize).coerceAtMost(all.size)
                LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, to)), params)
            }

            override fun onSearch(
                session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?,
            ): ListenableFuture<LibraryResult<Void>> = scope.future {
                val n = tree.search(query).size
                session.notifySearchResultChanged(browser, query, n, params)
                LibraryResult.ofVoid()
            }

            override fun onGetSearchResult(
                session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int, params: LibraryParams?,
            ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
                val all = tree.search(query)
                val from = (page * pageSize).coerceAtMost(all.size)
                val to = (from + pageSize).coerceAtMost(all.size)
                LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, to)), params)
            }
        })
            .setId("cued")
            .setSessionActivity(launch)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

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
