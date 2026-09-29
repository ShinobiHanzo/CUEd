package dev.cued.app.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import dev.cued.app.data.db.CuedDatabase
import dev.cued.app.data.db.PlayEventEntity
import dev.cued.app.data.db.PlaylistEntity
import dev.cued.app.data.db.TrackEntity
import dev.cued.core.model.TrackStats
import dev.cued.core.reco.PlayEvent
import dev.cued.core.reco.Recommender
import dev.cued.core.reco.SmartLists
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sections on the home screen. Each maps to one pure function in [SmartLists] or [Recommender]. */
enum class SmartList(val title: String, val blurb: String) {
    TRENDING("Trending", "What you've been playing most lately"),
    NEW("Newly downloaded", "Latest additions to the library"),
    UNPLAYED("Unplayed", "Never heard, oldest first"),
    FORGOTTEN("Forgotten", "Liked before, not heard in a while"),
    FAVOURITES("Favourites", "Everything you starred"),
    RECOMMENDED("Recommended", "Local heuristics: genre, artist, tempo, co-play. No cloud, no model."),
}

class LibraryRepository(
    private val context: Context,
    private val db: CuedDatabase,
    private val scope: CoroutineScope,
) {
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    val tracks: Flow<List<TrackEntity>> = db.tracks().observeAll()
    val genres: Flow<List<String>> = db.tracks().observeGenres()
    val playlists: Flow<List<PlaylistEntity>> = db.playlists().observeAll()

    /** Map of trackId -> genres, kept as a flow so lists can show labels without N queries. */
    val genreMap: Flow<Map<Long, List<String>>> = db.tracks().observeGenreRows().map { rows ->
        rows.groupBy({ it.trackId }, { it.genre })
    }

    fun search(q: String): Flow<List<TrackEntity>> = if (q.isBlank()) tracks else db.tracks().search(q)
    fun byGenre(genre: String): Flow<List<TrackEntity>> = db.tracks().observeByGenre(genre)
    fun observeTrack(id: Long): Flow<TrackEntity?> = db.tracks().observeById(id)
    suspend fun track(id: Long): TrackEntity? = db.tracks().byId(id)
    suspend fun tracks(ids: List<Long>): List<TrackEntity> {
        val byId = db.tracks().byIds(ids).associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    // ---- Smart lists -------------------------------------------------------

    /** Live view of every smart list; recomputed when tracks, genres or play history change. */
    val smartLists: Flow<Map<SmartList, List<TrackEntity>>> = combine(
        tracks, genreMap, db.playEvents().observeSince(0L),
    ) { all, genres, events ->
        val now = System.currentTimeMillis()
        val stats = all.map { it.toStats(genres[it.id].orEmpty()) }
        val byId = all.associateBy { it.id }
        val plays = events.map { PlayEvent(it.trackId, it.at, it.skipped) }
        val sessions = events.groupBy { it.sessionId }.values.map { s -> s.map { it.trackId } }
        fun ids(list: List<TrackStats>) = list.mapNotNull { byId[it.id] }
        mapOf(
            SmartList.TRENDING to ids(SmartLists.trending(stats, plays, now)),
            SmartList.NEW to ids(SmartLists.newlyAdded(stats)),
            SmartList.UNPLAYED to ids(SmartLists.unplayed(stats)),
            SmartList.FORGOTTEN to ids(SmartLists.forgotten(stats, now)),
            SmartList.FAVOURITES to ids(SmartLists.favourites(stats)),
            SmartList.RECOMMENDED to Recommender().recommend(stats, plays, sessions, now).mapNotNull { byId[it.track.id] },
        )
    }

    /** "More like this" for one track, with the reasons the heuristic gives. */
    suspend fun similarTo(trackId: Long, limit: Int = 20): List<Pair<TrackEntity, List<String>>> {
        val all = db.tracks().all()
        val genres = db.tracks().allGenreRows().groupBy({ it.trackId }, { it.genre })
        val stats = all.map { it.toStats(genres[it.id].orEmpty()) }
        val seed = stats.firstOrNull { it.id == trackId } ?: return emptyList()
        val byId = all.associateBy { it.id }
        val events = db.playEvents().since(0L)
        val plays = events.map { PlayEvent(it.trackId, it.at, it.skipped) }
        val sessions = events.groupBy { it.sessionId }.values.map { s -> s.map { it.trackId } }
        return Recommender().recommend(stats, plays, sessions, System.currentTimeMillis(), seed, limit)
            .mapNotNull { r -> byId[r.track.id]?.let { it to r.reasons } }
    }

    private fun TrackEntity.toStats(genres: List<String>) = TrackStats(
        id = id, title = title, artist = artist, genres = genres.toSet(), bpm = bpm,
        playCount = playCount, lastPlayedAt = lastPlayedAt, addedAt = addedAt, favourite = favourite,
        skipCount = skipCount, durationMs = durationMs,
    )

    // ---- Mutations ---------------------------------------------------------

    suspend fun setFavourite(id: Long, fav: Boolean) = db.tracks().setFavourite(id, fav)
    suspend fun genresOf(id: Long): List<String> = db.tracks().genresOf(id)
    suspend fun setGenres(id: Long, genres: Collection<String>) = db.tracks().setGenres(id, genres)
    suspend fun addGenre(id: Long, genre: String) {
        val g = genre.trim().lowercase()
        if (g.isNotEmpty()) db.tracks().addGenre(dev.cued.app.data.db.TrackGenreEntity(id, g))
    }
    suspend fun removeGenre(id: Long, genre: String) = db.tracks().removeGenre(id, genre)
    suspend fun setSourceLink(id: Long, link: String?) = db.tracks().setSourceLink(id, link)

    suspend fun createPlaylist(name: String, description: String = ""): Long =
        db.playlists().insert(PlaylistEntity(name = name.trim(), createdAt = System.currentTimeMillis(), description = description))
    suspend fun renamePlaylist(id: Long, name: String, description: String) {
        val p = db.playlists().byId(id) ?: return
        db.playlists().update(p.copy(name = name.trim(), description = description))
    }
    suspend fun deletePlaylist(id: Long) { db.playlists().byId(id)?.let { db.playlists().delete(it) } }
    fun observePlaylist(id: Long) = db.playlists().observe(id)
    fun playlistTracks(id: Long): Flow<List<TrackEntity>> = db.playlists().observeTracks(id)
    suspend fun playlistTracksNow(id: Long): List<TrackEntity> = db.playlists().tracks(id)
    suspend fun addToPlaylist(playlistId: Long, trackId: Long) = db.playlists().addTrack(playlistId, trackId)
    suspend fun removeFromPlaylist(playlistId: Long, trackId: Long) = db.playlists().removeRow(playlistId, trackId)
    suspend fun reorderPlaylist(playlistId: Long, trackIds: List<Long>) = db.playlists().reorder(playlistId, trackIds)
    suspend fun playlistsContaining(trackId: Long) = db.playlists().playlistsContaining(trackId)

    /** Saves any list of tracks (e.g. a smart list) as a real playlist you can curate. */
    suspend fun saveAsPlaylist(name: String, trackIds: List<Long>): Long {
        val id = createPlaylist(name)
        db.playlists().reorder(id, trackIds)
        return id
    }

    // ---- Play history ------------------------------------------------------

    private var sessionId: Long = -1L

    /** Called by the playback engine when a track finishes or is skipped. */
    suspend fun recordPlayback(trackId: Long, playedFraction: Float) {
        val now = System.currentTimeMillis()
        if (sessionId < 0L) {
            val lastAt = db.playEvents().lastEventAt() ?: 0L
            val lastId = db.playEvents().lastSessionId() ?: 0L
            sessionId = if (now - lastAt < SESSION_GAP_MS) lastId else lastId + 1
        } else {
            val lastAt = db.playEvents().lastEventAt() ?: 0L
            if (now - lastAt >= SESSION_GAP_MS) sessionId += 1
        }
        val skipped = playedFraction < SKIP_THRESHOLD
        db.playEvents().insert(PlayEventEntity(trackId = trackId, at = now, skipped = skipped, sessionId = sessionId))
        if (skipped) db.tracks().recordSkip(trackId) else db.tracks().recordPlay(trackId, now)
    }

    // ---- Scanning ----------------------------------------------------------

    fun rescanAsync() { scope.launch { rescan() } }

    /**
     * Pulls every music file MediaStore knows about into the database.
     * Existing rows keep their user data; files that vanished are flagged
     * [TrackEntity.missing] rather than deleted so playlists survive.
     */
    suspend fun rescan() = withContext(Dispatchers.IO) {
        if (_scanning.value) return@withContext
        _scanning.value = true
        try {
            val seen = HashSet<Long>()
            val projection = arrayListOf(
                MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.DATA,
            )
            val hasGenreColumn = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            if (hasGenreColumn) projection += MediaStore.Audio.Media.GENRE
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 15000"
            val existing = db.tracks().allIncludingMissing().associateBy { it.mediaStoreId }
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection.toTypedArray(), selection, null, null,
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val iDuration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val iAdded = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val iGenre = if (hasGenreColumn) c.getColumnIndex(MediaStore.Audio.Media.GENRE) else -1
                while (c.moveToNext()) {
                    val msId = c.getLong(iId)
                    seen += msId
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, msId).toString()
                    val title = c.getString(iTitle)?.takeIf { it.isNotBlank() } ?: "Untitled"
                    val artist = c.getString(iArtist)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown artist"
                    val album = c.getString(iAlbum)?.takeIf { it.isNotBlank() } ?: ""
                    val albumId = c.getLong(iAlbumId).takeIf { it > 0 }
                    val duration = c.getLong(iDuration)
                    val added = c.getLong(iAdded) * 1000L
                    val path = c.getString(iData)
                    val genre = if (iGenre >= 0) c.getString(iGenre) else null
                    val old = existing[msId]
                    if (old == null) {
                        val id = db.tracks().insert(
                            TrackEntity(
                                mediaStoreId = msId, uri = uri, path = path, title = title, artist = artist,
                                album = album, albumId = albumId, durationMs = duration, addedAt = added,
                            )
                        )
                        if (id > 0 && !genre.isNullOrBlank()) db.tracks().setGenres(id, genre.split('/', ';', ',').map { it.trim() })
                    } else if (old.title != title || old.artist != artist || old.album != album || old.uri != uri || old.missing || old.durationMs != duration) {
                        db.tracks().update(old.copy(title = title, artist = artist, album = album, albumId = albumId, uri = uri, path = path, durationMs = duration, missing = false))
                    }
                }
            }
            for (t in existing.values) if (t.mediaStoreId !in seen && !t.missing) db.tracks().setMissing(t.id, true)
        } finally {
            _scanning.value = false
        }
    }

    companion object {
        const val SESSION_GAP_MS = 30 * 60 * 1000L
        const val SKIP_THRESHOLD = 0.4f

        fun albumArtUri(albumId: Long?): Uri? = albumId?.let {
            ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), it)
        }
    }
}
