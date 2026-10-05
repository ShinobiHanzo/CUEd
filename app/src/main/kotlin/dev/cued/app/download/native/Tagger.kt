package dev.cued.app.download.native

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.images.AndroidArtwork
import dev.cued.app.util.DebugLog
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
        if (file.extension.equals("mp3", true)) { writeMp3(file, meta); return }
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
                val bytes = coverJpeg(url) ?: run { DebugLog.w("tag", "cover fetch failed: $url"); return@runCatching }
                val art = AndroidArtwork().apply { binaryData = bytes; mimeType = "image/jpeg"; pictureType = 3 }
                tag.deleteArtworkField()
                tag.setField(art)
                DebugLog.d("tag", "cover embedded (${bytes.size / 1024} KB)")
            }.onFailure { DebugLog.w("tag", "cover embed failed", it) }
        }
        audio.commit()
    }

    /**
     * MP3s get a tag written by CUEd's own ID3v2.3 writer (core/tag/Id3v2): no
     * library in the loop, and the result is verified below. The encoder's own
     * (empty) tag, if any, is stripped.
     */
    private fun writeMp3(file: File, meta: Meta) {
        val cover = meta.coverUrl?.let { url -> runCatching { coverJpeg(url) }.onFailure { DebugLog.w("tag", "cover fetch failed: $url", it) }.getOrNull() }
        val tag = dev.cued.core.tag.Id3v2.build(dev.cued.core.tag.Id3v2.Tags(
            title = meta.title, artist = meta.artists.joinToString(", ").ifBlank { null }, album = meta.album,
            albumArtist = meta.albumArtist ?: meta.artists.firstOrNull(), track = meta.trackNumber, year = meta.year?.takeIf { it.isNotBlank() },
            genre = meta.genres.joinToString("; ").ifBlank { null }, comment = meta.comment, lyrics = meta.lyrics,
            picture = cover?.let { dev.cued.core.tag.Id3v2.Picture("image/jpeg", it) },
        ))
        val tmp = File(file.parentFile, file.name + ".tagging")
        file.inputStream().use { inp -> tmp.outputStream().buffered().use { out -> dev.cued.core.tag.Id3v2.prepend(tag, inp, out) } }
        check(tmp.length() > file.length() - 10) { "tag write produced a short file" }
        check(tmp.renameTo(file) || (file.delete() && tmp.renameTo(file))) { "could not replace ${file.name} with the tagged copy" }
        DebugLog.d("tag", "id3v2 written (${tag.size / 1024} KB, cover ${if (cover != null) "${cover.size / 1024} KB" else "none"})")
    }

    /** Reads the tags back the way MediaStore will, so the log says whether the cover actually made it. */
    fun verify(file: File): String {
        val mmr = android.media.MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            val title = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
            val artist = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val album = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val pic = mmr.embeddedPicture?.size ?: 0
            "verify ${file.extension}: title=${title ?: "-"} artist=${artist ?: "-"} album=${album ?: "-"} cover=${if (pic > 0) "${pic / 1024} KB" else "NONE"}"
        } catch (e: Exception) { "verify failed: $e" } finally { runCatching { mmr.release() } }
    }

    /** Fetches any image (jpeg/png/webp) and re-encodes it as a ≤800px JPEG, the safest thing to put in a tag. */
    private fun coverJpeg(url: String): ByteArray? {
        val raw = fetch(url) ?: return null
        val bmp = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return raw.takeIf { url.contains(".jpg") || url.contains(".jpeg") }
        val scale = 800f / maxOf(bmp.width, bmp.height)
        val out = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val bos = java.io.ByteArrayOutputStream()
        out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, bos)
        return bos.toByteArray()
    }

    private fun fetch(url: String): ByteArray? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000; conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", NewPipeHttp.USER_AGENT)
        conn.instanceFollowRedirects = true
        return try { if (conn.responseCode == 200) conn.inputStream.use { it.readBytes() }.takeIf { it.size < 4_000_000 } else null } finally { conn.disconnect() }
    }
}
