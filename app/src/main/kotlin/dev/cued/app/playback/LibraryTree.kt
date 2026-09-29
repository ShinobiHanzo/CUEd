package dev.cued.app.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import dev.cued.app.data.LibraryRepository
import dev.cued.app.data.SmartList
import dev.cued.app.data.db.TrackEntity
import kotlinx.coroutines.flow.first

/**
 * The browse tree that Android Auto, Wear and other MediaBrowser clients see.
 *
 *   root
 *   ├── smart/<KIND>       Trending, Newly downloaded, …  (browsable + playable)
 *   ├── playlists/         → playlist/<id>               (browsable + playable)
 *   ├── genres/            → genre/<name>                (browsable + playable)
 *   └── tracks/            → <trackId>                   (playable)
 *
 * Container ids are also accepted by [expand], so a client can "play" a
 * whole folder with one tap or one voice command.
 */
class LibraryTree(private val library: LibraryRepository) {
    companion object {
        const val ROOT = "root"
        const val SMART = "smart"
        const val PLAYLISTS = "playlists"
        const val GENRES = "genres"
        const val TRACKS = "tracks"
    }

    fun root(): MediaItem = folder(ROOT, "CUEd", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, playable = false)

    suspend fun children(parentId: String): List<MediaItem>? = when {
        parentId == ROOT -> listOf(
            folder(SMART, "For you", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, playable = false),
            folder(PLAYLISTS, "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS, playable = false),
            folder(GENRES, "Genres", MediaMetadata.MEDIA_TYPE_FOLDER_GENRES, playable = false),
            folder(TRACKS, "All tracks", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, playable = true),
        )
        parentId == SMART -> SmartList.entries.map { folder("$SMART/${it.name}", it.title, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, playable = true, subtitle = it.blurb) }
        parentId == PLAYLISTS -> library.playlists.first().map { folder("playlist/${it.id}", it.name, MediaMetadata.MEDIA_TYPE_PLAYLIST, playable = true) }
        parentId == GENRES -> library.genres.first().map { folder("genre/$it", it, MediaMetadata.MEDIA_TYPE_FOLDER_GENRES, playable = true) }
        parentId == TRACKS -> library.tracks.first().map { MediaItems.fromTrack(it) }
        parentId.startsWith("$SMART/") || parentId.startsWith("playlist/") || parentId.startsWith("genre/") -> expand(parentId)?.map { MediaItems.fromTrack(it) }
        else -> null
    }

    suspend fun item(mediaId: String): MediaItem? {
        mediaId.toLongOrNull()?.let { id -> return library.track(id)?.let { MediaItems.fromTrack(it) } }
        if (mediaId == ROOT) return root()
        return children(mediaId.substringBeforeLast('/', ROOT))?.firstOrNull { it.mediaId == mediaId }
    }

    /** Tracks behind a container id, or null if the id is not a container. */
    suspend fun expand(id: String): List<TrackEntity>? = when {
        id == TRACKS -> library.tracks.first()
        id.startsWith("$SMART/") -> runCatching { SmartList.valueOf(id.removePrefix("$SMART/")) }.getOrNull()?.let { library.smartLists.first()[it] }.orEmpty()
        id.startsWith("playlist/") -> id.removePrefix("playlist/").toLongOrNull()?.let { library.playlistTracksNow(it) }.orEmpty()
        id.startsWith("genre/") -> library.byGenre(id.removePrefix("genre/")).first()
        else -> null
    }

    suspend fun search(query: String): List<MediaItem> = VoiceResolver(library).resolveQuery(query).tracks.map { MediaItems.fromTrack(it) }

    private fun folder(id: String, title: String, type: Int, playable: Boolean, subtitle: String? = null): MediaItem =
        MediaItem.Builder().setMediaId(id).setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle).setIsBrowsable(true).setIsPlayable(playable).setMediaType(type).build()
        ).build()
}
