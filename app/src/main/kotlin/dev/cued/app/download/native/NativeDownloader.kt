package dev.cued.app.download.native

import android.content.Context
import dev.cued.app.data.db.DownloadJobEntity
import dev.cued.app.download.DownloadManager
import dev.cued.app.download.LinkResolver
import dev.cued.core.download.Matcher
import dev.cued.core.share.SourceLinks
import dev.cued.core.share.SourceLinks.LinkType
import dev.cued.core.share.SourceLinks.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The built-in downloader: spotdl's pipeline in Kotlin, no Python.
 *
 *   link or search → metadata (Spotify API / embed page / YouTube) → YouTube
 *   Music search → best match by title, artist and duration → AAC stream →
 *   tags + cover → Music/CUEd.
 *
 * Albums and playlists are expanded into one job per track by [expand] so
 * each shows its own progress and can be retried on its own.
 */
class NativeDownloader(private val context: Context, spotifyClientId: String?, spotifyClientSecret: String?) {
    private val spotify = SpotifyClient(spotifyClientId, spotifyClientSecret)
    private val youtube = YouTubeSource()

    /** For collections: the per-track items. Null when [source] is a single track or a search. */
    suspend fun expand(source: String): LinkResolver.Result.Expanded? = withContext(Dispatchers.IO) {
        val link = SourceLinks.parse(source) ?: return@withContext null
        val id = link.id
        when {
            link.platform == Platform.SPOTIFY && link.type == LinkType.ALBUM && id != null -> {
                val c = spotify.album(id)
                LinkResolver.Result.Expanded(c.tracks.map { item(it) }, "Album \"${c.name}\": ${c.tracks.size} tracks")
            }
            link.platform == Platform.SPOTIFY && link.type == LinkType.PLAYLIST && id != null -> {
                val c = spotify.playlist(id)
                LinkResolver.Result.Expanded(c.tracks.map { item(it) }, "Playlist \"${c.name}\": ${c.tracks.size} tracks")
            }
            (link.platform == Platform.YOUTUBE || link.platform == Platform.YOUTUBE_MUSIC) && link.type == LinkType.PLAYLIST -> {
                val ex = org.schabi.newpipe.extractor.ServiceList.YouTube.getPlaylistExtractor(link.url)
                ex.fetchPage()
                val items = ArrayList<org.schabi.newpipe.extractor.stream.StreamInfoItem>()
                var page = ex.initialPage
                items += page.items.filterIsInstance<org.schabi.newpipe.extractor.stream.StreamInfoItem>()
                var guard = 0
                while (page.hasNextPage() && items.size < 300 && guard++ < 10) { page = ex.getPage(page.nextPage); items += page.items.filterIsInstance<org.schabi.newpipe.extractor.stream.StreamInfoItem>() }
                LinkResolver.Result.Expanded(items.map { LinkResolver.Item(it.url, it.name, it.uploaderName) }, "YouTube playlist \"${ex.name}\": ${items.size} videos")
            }
            else -> null
        }
    }

    private fun item(t: SpotifyClient.Track) = LinkResolver.Item(
        if (t.id != null) "https://open.spotify.com/track/${t.id}" else "${t.artists.joinToString(", ")} - ${t.title}", t.title, t.artists.joinToString(", "),
    )

    /** Downloads one track or the best match for a search. */
    suspend fun download(job: DownloadJobEntity, source: String, onProgress: (Float) -> Unit): DownloadManager.Outcome = withContext(Dispatchers.IO) {
        val link = SourceLinks.parse(source)
        val spotifyId = link?.id
        val meta: SpotifyClient.Track?
        val ytUrl: String
        when {
            link != null && link.platform == Platform.SPOTIFY && link.type == LinkType.TRACK && spotifyId != null -> {
                meta = spotify.track(spotifyId)
                onProgress(0.05f)
                ytUrl = findOnYouTube(meta) ?: error("No convincing match on YouTube Music for \"${meta.artists.joinToString(", ")} - ${meta.title}\"")
            }
            link != null && (link.platform == Platform.YOUTUBE || link.platform == Platform.YOUTUBE_MUSIC) -> { meta = null; ytUrl = link.url }
            link != null -> error("The built-in downloader takes Spotify and YouTube links or a search; this was ${link.label}")
            else -> {
                meta = null
                val q = source.trim()
                val cands = youtube.search(q)
                val best = Matcher.best(Matcher.Wanted(q, emptyList(), null), cands, minScore = 1.0f)?.candidate ?: cands.firstOrNull()
                ytUrl = best?.id ?: error("Nothing found on YouTube Music for \"$q\"")
            }
        }
        onProgress(0.1f)
        val (info, stream) = youtube.info(ytUrl)
        val ext = youtube.extensionOf(stream)
        val title = meta?.title ?: info.name
        val artists = meta?.artists?.takeIf { it.isNotEmpty() } ?: listOf(info.uploaderName ?: "Unknown artist")
        val tmp = File(context.cacheDir, "dl").apply { mkdirs() }.let { File(it, "${System.currentTimeMillis()}.$ext") }
        try {
            youtube.download(stream, tmp) { p -> onProgress(0.1f + 0.8f * p) }
            if (ext == "m4a" || ext == "mp3") runCatching {
                Tagger.write(tmp, Tagger.Meta(
                    title = title, artists = artists, album = meta?.album, albumArtist = meta?.albumArtist,
                    trackNumber = meta?.trackNumber, year = meta?.year, genres = meta?.genres.orEmpty(),
                    coverUrl = meta?.coverUrl ?: info.thumbnails.maxByOrNull { it.width }?.url, lyrics = null,
                    comment = if (link?.platform == Platform.SPOTIFY) link.url else ytUrl,
                ))
            }
            onProgress(0.95f)
            val name = safe("${artists.joinToString(", ")} - $title") + ".$ext"
            val (uri, id) = DownloadManager.createPendingAudio(context, name, youtube.mimeOf(stream))
            try {
                context.contentResolver.openOutputStream(uri)!!.use { out -> tmp.inputStream().use { it.copyTo(out) } }
            } catch (e: Exception) { context.contentResolver.delete(uri, null, null); throw e }
            DownloadManager.finishPending(context, uri)
            onProgress(1f)
            DownloadManager.Outcome.Done(listOf(id), "${artists.first()} - $title · from YouTube Music" + if (meta != null) " (Spotify metadata)" else "", genresByMediaStoreId = mapOf(id to meta?.genres.orEmpty()))
        } finally { tmp.delete() }
    }

    private fun findOnYouTube(t: SpotifyClient.Track): String? {
        val artist = t.artists.firstOrNull().orEmpty()
        val wanted = Matcher.Wanted(t.title, t.artists, t.durationMs?.let { (it / 1000).toInt() })
        for (q in listOf("$artist - ${t.title}", "${t.artists.joinToString(" ")} ${t.title}", t.title)) {
            val cands = runCatching { youtube.search(q) }.getOrDefault(emptyList())
            Matcher.best(wanted, cands)?.let { return it.candidate.id }
        }
        return null
    }

    private fun safe(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").replace(Regex("\\s+"), " ").trim().take(120)
}
