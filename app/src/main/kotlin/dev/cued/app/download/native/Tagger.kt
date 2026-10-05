package dev.cued.app.download.native

import dev.cued.app.util.DebugLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Writes title/artist/album/cover/genre/lyrics into the downloaded file: mp3 via core/tag/Id3v2, m4a via core/tag/Mp4Tags. No tag library. */
object Tagger {
    data class Meta(
        val title: String, val artists: List<String>, val album: String?, val albumArtist: String?,
        val trackNumber: Int?, val year: String?, val genres: List<String>, val coverUrl: String?, val lyrics: String?, val comment: String?,
        /** Ready-made JPEG bytes win over [coverUrl]. */
        val cover: ByteArray? = null,
    )

    private fun coverBytes(meta: Meta): ByteArray? = meta.cover ?: meta.coverUrl?.let { url -> runCatching { coverJpeg(url) }.onFailure { DebugLog.w("tag", "cover fetch failed: $url", it) }.getOrNull() }

    fun write(file: File, meta: Meta) {
        if (file.extension.equals("mp3", true)) { writeMp3(file, meta); return }
        writeMp4(file, meta)
    }

    /**
     * M4A: YouTube's audio is a fragmented MP4, which no in-place tagger can
     * safely rewrite, so it is first remuxed (sample copy, no re-encode) into
     * a plain MP4 and then gets an iTunes-style ilst from core/tag/Mp4Tags.
     */
    private fun writeMp4(file: File, meta: Meta) {
        var src = file
        val plain = File(file.parentFile, file.nameWithoutExtension + ".plain.m4a")
        if (dev.cued.core.tag.Mp4Tags.isFragmented(file)) {
            dev.cued.app.tagging.Remux.toPlainMp4(file, plain)
            src = plain
            DebugLog.d("tag", "remuxed fragmented m4a (${file.length()} → ${plain.length()} bytes)")
        }
        val tagged = File(file.parentFile, file.name + ".tagging")
        try {
            val cover = coverBytes(meta)
            dev.cued.core.tag.Mp4Tags.write(src, tagged, dev.cued.core.tag.Mp4Tags.Tags(
                title = meta.title, artist = meta.artists.joinToString(", ").ifBlank { null }, album = meta.album,
                albumArtist = meta.albumArtist ?: meta.artists.firstOrNull(), track = meta.trackNumber, year = meta.year?.takeIf { it.isNotBlank() },
                genre = meta.genres.joinToString("; ").ifBlank { null }, comment = meta.comment, lyrics = meta.lyrics, cover = cover,
            ))
            check(tagged.length() > src.length() - 1_048_576) { "tag write produced a short file" }
            check(tagged.renameTo(file) || (file.delete() && tagged.renameTo(file))) { "could not replace ${file.name} with the tagged copy" }
            DebugLog.d("tag", "ilst written (cover ${if (cover != null) "${cover.size / 1024} KB" else "none"})")
        } finally { tagged.delete(); plain.delete() }
    }

    /**
     * MP3s get a tag written by CUEd's own ID3v2.3 writer (core/tag/Id3v2): no
     * library in the loop, and the result is verified below. The encoder's own
     * (empty) tag, if any, is stripped.
     */
    private fun writeMp3(file: File, meta: Meta) {
        val cover = coverBytes(meta)
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

    /** Duration and audio presence via the platform extractor: (ok, detail). */
    fun probe(file: File): Pair<Boolean, String> {
        val mmr = android.media.MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            val d = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val audio = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            (d > 1_000 && audio) to "duration=${d}ms audio=$audio"
        } catch (e: Exception) { false to (e.message ?: e.toString()) } finally { runCatching { mmr.release() } }
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
    private fun coverJpeg(url: String): ByteArray? = CoverFinder.fetchFirst(listOf(url))

    private fun fetch(url: String): ByteArray? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000; conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", NewPipeHttp.USER_AGENT)
        conn.instanceFollowRedirects = true
        return try { if (conn.responseCode == 200) conn.inputStream.use { it.readBytes() }.takeIf { it.size < 4_000_000 } else null } finally { conn.disconnect() }
    }
}
