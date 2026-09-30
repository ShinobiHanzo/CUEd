package dev.cued.app.download.native

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.images.AndroidArtwork
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Writes title/artist/album/cover/genre/lyrics into the downloaded file (MP4 and MP3 supported). */
object Tagger {
    data class Meta(
        val title: String, val artists: List<String>, val album: String?, val albumArtist: String?,
        val trackNumber: Int?, val year: String?, val genres: List<String>, val coverUrl: String?, val lyrics: String?, val comment: String?,
    )

    fun write(file: File, meta: Meta) {
        TagOptionSingleton.getInstance().isAndroid = true
        val audio = AudioFileIO.read(file)
        val tag = audio.tagOrCreateAndSetDefault
        tag.setField(FieldKey.TITLE, meta.title)
        if (meta.artists.isNotEmpty()) tag.setField(FieldKey.ARTIST, meta.artists.joinToString(", "))
        meta.album?.let { tag.setField(FieldKey.ALBUM, it) }
        (meta.albumArtist ?: meta.artists.firstOrNull())?.let { tag.setField(FieldKey.ALBUM_ARTIST, it) }
        meta.trackNumber?.let { runCatching { tag.setField(FieldKey.TRACK, it.toString()) } }
        meta.year?.takeIf { it.isNotBlank() }?.let { runCatching { tag.setField(FieldKey.YEAR, it) } }
        if (meta.genres.isNotEmpty()) tag.setField(FieldKey.GENRE, meta.genres.joinToString("; "))
        meta.lyrics?.takeIf { it.isNotBlank() }?.let { runCatching { tag.setField(FieldKey.LYRICS, it) } }
        meta.comment?.let { runCatching { tag.setField(FieldKey.COMMENT, it) } }
        meta.coverUrl?.let { url ->
            runCatching {
                val bytes = fetch(url) ?: return@runCatching
                val art = AndroidArtwork().apply { binaryData = bytes; mimeType = if (url.contains(".png")) "image/png" else "image/jpeg"; pictureType = 3 }
                tag.deleteArtworkField()
                tag.setField(art)
            }
        }
        audio.commit()
    }

    private fun fetch(url: String): ByteArray? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000; conn.readTimeout = 20_000
        return try { if (conn.responseCode == 200) conn.inputStream.use { it.readBytes() }.takeIf { it.size < 4_000_000 } else null } finally { conn.disconnect() }
    }
}
