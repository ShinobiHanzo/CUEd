package dev.cued.app.widget

import android.content.ComponentName
import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.cued.app.playback.PlaybackService
import dev.cued.app.util.DebugLog
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Widget buttons talk to playback the same way the UI does: through a
 * short-lived [MediaController]. Connecting one starts [PlaybackService] if
 * it is not running, which is allowed from a widget tap.
 */
@UnstableApi
object WidgetActions {
    suspend fun send(context: Context, block: (Player) -> Unit) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        val controller = withTimeoutOrNull(8_000) { runCatching { future.await() }.getOrNull() }
        if (controller == null) { MediaController.releaseFuture(future); DebugLog.w("widget", "controller did not connect"); return }
        try { block(controller) } catch (e: Exception) { DebugLog.w("widget", "command failed", e) } finally { controller.release() }
    }
}

@UnstableApi
class PlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetActions.send(context) { p ->
            if (p.mediaItemCount == 0) return@send
            if (p.isPlaying) p.pause() else { if (p.playbackState == Player.STATE_IDLE) p.prepare(); p.play() }
        }
    }
}

@UnstableApi
class NextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetActions.send(context) { if (it.mediaItemCount > 0) it.seekToNext() }
    }
}

@UnstableApi
class PreviousAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetActions.send(context) { if (it.mediaItemCount > 0) it.seekToPrevious() }
    }
}
