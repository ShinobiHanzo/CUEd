package dev.cued.app.genre

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import dev.cued.core.genre.GenreNormalizer

/**
 * Reads the genre tag straight from the file with the platform's metadata
 * reader (ID3 TCON, MP4 ©gen/gnre, Vorbis GENRE…), on every Android version,
 * and normalises it. This is the primary source: spotdl writes Spotify's
 * artist genres into this tag, so downloads usually arrive labelled.
 */
class TagGenreReader(private val context: Context) {
    fun read(uri: Uri): List<String> {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            GenreNormalizer.normalize(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE))
        } catch (_: Exception) {
            emptyList()
        } finally {
            runCatching { mmr.release() }
        }
    }
}
