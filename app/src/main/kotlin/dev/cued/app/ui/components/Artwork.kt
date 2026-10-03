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
    /** Bounded by bytes, not count: an eighth of the heap, capped at 24 MB. 64 full-size covers used to be able to take 64 MB. */
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).coerceAtMost(24L * 1024 * 1024).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val misses = java.util.Collections.synchronizedSet(HashSet<String>())

    const val SMALL = 128
    const val MEDIUM = 256
    const val LARGE = 512

    /** [px] is the longest side wanted: rows ask for [SMALL], cards for [MEDIUM], the player for [LARGE]. */
    suspend fun embedded(context: Context, trackUri: String, px: Int = MEDIUM): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$trackUri@$px"
        cache.get(key)?.let { return@withContext it }
        if (trackUri in misses) return@withContext null
        val uri = Uri.parse(trackUri)
        val bmp = runCatching {
            if (Build.VERSION.SDK_INT >= 29) context.contentResolver.loadThumbnail(uri, Size(px, px), null)
            else {
                val mmr = MediaMetadataRetriever()
                try { mmr.setDataSource(context, uri); mmr.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } } finally { runCatching { mmr.release() } }
            }
        }.getOrNull()
        if (bmp != null) cache.put(key, bmp) else misses += trackUri
        bmp
    }

    fun forget(trackUri: String) { for (px in listOf(SMALL, MEDIUM, LARGE)) cache.remove("$trackUri@$px"); misses.remove(trackUri) }
}
