package dev.cued.app.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import dev.cued.app.data.db.TrackEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The UI's handle on playback. Connecting a [MediaController] is what
 * starts [PlaybackService], so playback keeps going when the activity dies.
 */
@UnstableApi
class PlayerConnection(private val context: Context) {
    private val _controller = MutableStateFlow<MediaController?>(null)
    val controller: StateFlow<MediaController?> = _controller
    private var pending: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null

    fun connect() {
        if (_controller.value != null || pending != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        pending = future
        future.addListener({
            runCatching { _controller.value = future.get() }
            pending = null
        }, MoreExecutors.directExecutor())
    }

    fun disconnect() {
        pending?.let { MediaController.releaseFuture(it) }
        pending = null
        _controller.value?.release()
        _controller.value = null
    }

    val player: Player? get() = _controller.value

    fun play(tracks: List<TrackEntity>, startIndex: Int = 0) {
        val p = player ?: return
        if (tracks.isEmpty()) return
        p.setMediaItems(tracks.map { MediaItems.fromTrack(it) }, startIndex.coerceIn(0, tracks.size - 1), 0L)
        p.prepare()
        p.play()
    }

    fun playNext(track: TrackEntity) {
        val p = player ?: return
        val at = if (p.mediaItemCount == 0) 0 else p.currentMediaItemIndex + 1
        p.addMediaItem(at, MediaItems.fromTrack(track))
        if (p.playbackState == Player.STATE_IDLE) { p.prepare() }
    }

    fun enqueue(tracks: List<TrackEntity>) {
        val p = player ?: return
        p.addMediaItems(tracks.map { MediaItems.fromTrack(it) })
        if (p.playbackState == Player.STATE_IDLE) { p.prepare() }
    }

    fun queue(): List<MediaItem> {
        val p = player ?: return emptyList()
        return (0 until p.mediaItemCount).map { p.getMediaItemAt(it) }
    }
}
