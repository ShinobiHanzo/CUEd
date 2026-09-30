package dev.cued.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cover art straight from the audio file, for when MediaStore has no album
 * art row yet (fresh downloads, single tracks with no album tag). Uses the
 * system thumbnailer on Android 10+ and the embedded picture otherwise.
 */
object Artwork {
    private val cache = LruCache<String, Bitmap>(64)
    private val misses = java.util.Collections.synchronizedSet(HashSet<String>())

    suspend fun embedded(context: Context, trackUri: String, px: Int = 512): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(trackUri)?.let { return@withContext it }
        if (trackUri in misses) return@withContext null
        val uri = Uri.parse(trackUri)
        val bmp = runCatching {
            if (Build.VERSION.SDK_INT >= 29) context.contentResolver.loadThumbnail(uri, Size(px, px), null)
            else {
                val mmr = MediaMetadataRetriever()
                try { mmr.setDataSource(context, uri); mmr.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } } finally { runCatching { mmr.release() } }
            }
        }.getOrNull()
        if (bmp != null) cache.put(trackUri, bmp) else misses += trackUri
        bmp
    }

    fun forget(trackUri: String) { cache.remove(trackUri); misses.remove(trackUri) }
}
