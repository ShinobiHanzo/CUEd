package dev.cued.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import dev.cued.app.ui.components.Artwork
import dev.cued.app.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What the home-screen widget shows, and how the playback service feeds it.
 *
 * The widget is a RemoteViews under the hood, so it cannot hold a live
 * player; instead the service publishes a small snapshot (title, artist,
 * playing flag, a 256 px cover) every time the track or play state changes,
 * and Glance re-renders every placed widget from that snapshot.
 */
object WidgetState {
    val TITLE = stringPreferencesKey("title")
    val ARTIST = stringPreferencesKey("artist")
    val PLAYING = booleanPreferencesKey("playing")
    val HAS_TRACK = booleanPreferencesKey("has_track")
    val TRACK_URI = stringPreferencesKey("track_uri")
    /** Bumped whenever the cover file changes so Glance re-reads it. */
    val ART_STAMP = longPreferencesKey("art_stamp")

    private const val ART_PX = 256

    fun artFile(context: Context) = File(context.cacheDir, "widget_art.png")

    fun loadArt(context: Context): Bitmap? = runCatching {
        val f = artFile(context)
        if (f.exists() && f.length() > 0) BitmapFactory.decodeFile(f.path) else null
    }.getOrNull()

    @Volatile private var lastArtKey: String? = null

    /** Called by the service on every relevant player event. Cheap when no widget is placed. */
    suspend fun publish(context: Context, title: String?, artist: String?, playing: Boolean, trackUri: String?, artworkUri: Uri?) {
        val mgr = GlanceAppWidgetManager(context)
        val ids = runCatching { mgr.getGlanceIds(CuedWidget::class.java) }.getOrDefault(emptyList())
        if (ids.isEmpty()) return
        val hasTrack = !title.isNullOrBlank()
        val artKey = if (hasTrack) trackUri.orEmpty() else null
        val stamp: Long? = when {
            artKey == null -> { artFile(context).delete(); lastArtKey = null; 0L }
            artKey != lastArtKey || !artFile(context).exists() -> writeArt(context, trackUri, artworkUri).also { lastArtKey = artKey }
            else -> null
        }
        for (id in ids) {
            updateAppWidgetState(context, id) { p ->
                p[TITLE] = title.orEmpty()
                p[ARTIST] = artist.orEmpty()
                p[PLAYING] = playing && hasTrack
                p[HAS_TRACK] = hasTrack
                p[TRACK_URI] = trackUri.orEmpty()
                stamp?.let { p[ART_STAMP] = it }
            }
        }
        runCatching { CuedWidget().updateAll(context) }.onFailure { DebugLog.w(TAG, "widget update failed", it) }
    }

    /** Resolves cover art (MediaStore album art, then the file's own picture), scales it and writes it beside the state. */
    private suspend fun writeArt(context: Context, trackUri: String?, artworkUri: Uri?): Long = withContext(Dispatchers.IO) {
        val f = artFile(context)
        val bmp = loadBitmap(context, artworkUri) ?: trackUri?.let { Artwork.embedded(context, it, ART_PX) }
        if (bmp == null) { f.delete(); return@withContext 0L }
        val scaled = scale(bmp, ART_PX)
        val ok = runCatching { f.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            .onFailure { DebugLog.w(TAG, "cover write failed", it) }.isSuccess
        if (ok) System.currentTimeMillis() else { f.delete(); 0L }
    }

    private fun loadBitmap(context: Context, uri: Uri?): Bitmap? {
        uri ?: return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= ART_PX && bounds.outHeight / (sample * 2) >= ART_PX) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull()
    }

    private fun scale(b: Bitmap, px: Int): Bitmap {
        val side = minOf(b.width, b.height)
        val square = if (b.width == b.height) b else Bitmap.createBitmap(b, (b.width - side) / 2, (b.height - side) / 2, side, side)
        return if (side <= px) square else Bitmap.createScaledBitmap(square, px, px, true)
    }

    private const val TAG = "widget"
}
