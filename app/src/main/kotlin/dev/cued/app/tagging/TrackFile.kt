package dev.cued.app.tagging

import android.content.Context
import android.content.IntentSender
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import dev.cued.app.data.db.TrackEntity
import dev.cued.app.download.DownloadManager
import dev.cued.app.download.native.CoverFinder
import dev.cued.app.download.native.SpotifyClient
import dev.cued.app.download.native.Tagger
import dev.cued.app.download.native.YouTubeSource
import dev.cued.app.genre.MusicBrainzClient
import dev.cued.app.util.DebugLog
import dev.cued.core.share.SourceLinks
import dev.cued.core.share.SourceLinks.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reads and rewrites the tags inside a library file, for the Track details
 * screen. Reading uses Android's own extractor, so what it reports is what
 * the system scanner sees. Writing copies the file out, retags the copy
 * (CUEd's ID3 writer for mp3, jaudiotagger for m4a) and streams it back
 * through MediaStore; files CUEd does not own need the user's consent on
 * Android 10+, which is returned as an intent to launch.
 */
class TrackFile(private val context: Context, private val userAgent: String) {
    data class FileTags(
        val title: String?, val artist: String?, val album: String?, val albumArtist: String?, val trackNo: Int, val year: Int,
        val genre: String?, val coverBytes: Int, val mime: String?, val bitrateKbps: Int, val sizeBytes: Long, val durationMs: Long,
    )

    sealed class WriteResult {
        data object Done : WriteResult()
        data class NeedsConsent(val sender: IntentSender) : WriteResult()
        data class Failed(val reason: String) : WriteResult()
    }

    fun read(uri: Uri): FileTags? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            fun s(k: Int) = mmr.extractMetadata(k)?.takeIf { it.isNotBlank() }
            val size = runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: 0L
            FileTags(
                title = s(MediaMetadataRetriever.METADATA_KEY_TITLE), artist = s(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = s(MediaMetadataRetriever.METADATA_KEY_ALBUM), albumArtist = s(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                trackNo = s(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0,
                year = s(MediaMetadataRetriever.METADATA_KEY_YEAR)?.take(4)?.toIntOrNull() ?: s(MediaMetadataRetriever.METADATA_KEY_DATE)?.take(4)?.toIntOrNull() ?: 0,
                genre = s(MediaMetadataRetriever.METADATA_KEY_GENRE), coverBytes = mmr.embeddedPicture?.size ?: 0,
                mime = s(MediaMetadataRetriever.METADATA_KEY_MIMETYPE), bitrateKbps = (s(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0) / 1000,
                sizeBytes = size, durationMs = s(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
            )
        } catch (e: Exception) { DebugLog.w(TAG, "read tags failed for $uri", e); null } finally { runCatching { mmr.release() } }
    }

    /** Finds a cover for the track: its source link first (Spotify cover, YouTube thumbnail), then MusicBrainz/Cover Art Archive. */
    suspend fun findCover(track: TrackEntity, artist: String, title: String, allowOnline: Boolean = true): ByteArray? = withContext(Dispatchers.IO) {
        val urls = ArrayList<String>()
        track.sourceLink?.let { SourceLinks.parse(it) }?.let { link ->
            val id = link.id
            when {
                link.platform == Platform.SPOTIFY && link.type == SourceLinks.LinkType.TRACK && id != null ->
                    runCatching { SpotifyClient(null, null).track(id).coverUrl }.getOrNull()?.let { urls += it }
                (link.platform == Platform.YOUTUBE || link.platform == Platform.YOUTUBE_MUSIC) && id != null -> urls += CoverFinder.youtubeCandidates(id)
                else -> {}
            }
        }
        if (allowOnline) runCatching { MusicBrainzClient(userAgent).lookup(artist, title) }.getOrNull()?.let { urls += it.coverUrls }
        if (urls.isEmpty() && allowOnline) runCatching {
            // Last resort: the top YouTube Music hit's thumbnail.
            YouTubeSource().search("$artist $title", 1).firstOrNull()?.let { c -> SourceLinks.parse(c.id)?.id?.let { urls += CoverFinder.youtubeCandidates(it) } }
        }
        CoverFinder.fetchFirst(urls)
    }

    suspend fun lookup(artist: String, title: String): MusicBrainzClient.Release? = runCatching { MusicBrainzClient(userAgent).lookup(artist, title) }.getOrNull()

    /**
     * Rewrites the file's tags. [cover] null keeps whatever picture the file already has.
     */
    suspend fun write(track: TrackEntity, meta: Tagger.Meta, keepCover: Boolean = true): WriteResult = withContext(Dispatchers.IO) {
        val uri = Uri.parse(track.uri)
        val mime = context.contentResolver.getType(uri) ?: read(uri)?.mime ?: ""
        val ext = when {
            mime.contains("mpeg") || track.path?.endsWith(".mp3", true) == true -> "mp3"
            mime.contains("mp4") || mime.contains("m4a") || track.path?.endsWith(".m4a", true) == true -> "m4a"
            else -> return@withContext WriteResult.Failed("Only mp3 and m4a tags can be edited (this file is ${mime.ifBlank { "unknown" }})")
        }
        val dir = File(context.cacheDir, "edit").apply { mkdirs() }
        val tmp = File(dir, "${track.id}.$ext")
        try {
            context.contentResolver.openInputStream(uri)?.use { inp -> tmp.outputStream().use { inp.copyTo(it) } } ?: return@withContext WriteResult.Failed("Couldn't read the file")
            var m = meta
            if (m.cover == null && keepCover) {
                val existing = MediaMetadataRetriever().let { r -> try { r.setDataSource(tmp.absolutePath); r.embeddedPicture } catch (e: Exception) { null } finally { runCatching { r.release() } } }
                if (existing != null) m = m.copy(cover = existing)
            }
            Tagger.write(tmp, m)
            try {
                context.contentResolver.openOutputStream(uri, "wt")?.use { out -> tmp.inputStream().use { it.copyTo(out) } } ?: return@withContext WriteResult.Failed("Couldn't open the file for writing")
            } catch (e: SecurityException) {
                if (Build.VERSION.SDK_INT >= 30) return@withContext WriteResult.NeedsConsent(MediaStore.createWriteRequest(context.contentResolver, listOf(uri)).intentSender)
                if (Build.VERSION.SDK_INT == 29 && e is android.app.RecoverableSecurityException) return@withContext WriteResult.NeedsConsent(e.userAction.actionIntent.intentSender)
                return@withContext WriteResult.Failed("No permission to change this file")
            }
            DownloadManager.rescanFile(context, uri)
            DebugLog.i(TAG, "tags rewritten for #${track.id}: ${Tagger.verify(tmp)}")
            WriteResult.Done
        } catch (e: Exception) {
            DebugLog.w(TAG, "tag write failed", e); WriteResult.Failed(e.message ?: e.toString())
        } finally { tmp.delete() }
    }

    companion object { private const val TAG = "tagfile" }
}
