package dev.cued.app.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.room.withTransaction
import dev.cued.app.data.db.CuedDatabase
import dev.cued.app.data.db.PlayEventEntity
import dev.cued.app.data.db.PlaylistEntity
import dev.cued.app.data.db.TrackEntity
import dev.cued.core.genre.GenreNormalizer
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

    /**
     * Music only. Every list that can start playback or grow the queue on its own
     * (smart lists, genres, search, recommendations, voice "play something",
     * Android Auto "All tracks") is derived from this flow, so a podcast or
     * audiobook can only play when you pick it directly, name it, or put it in a
     * playlist yourself.
     */
    val tracks: Flow<List<TrackEntity>> = db.tracks().observeAll()
    /** Podcasts, audiobooks, long mixes: anything over 12 minutes (or moved there by hand). */
    val longPlays: Flow<List<TrackEntity>> = db.tracks().observeLong()
    suspend fun longPlaysNow(): List<TrackEntity> = db.tracks().allLong()
    suspend fun setKind(id: Long, kind: String) = db.tracks().setKind(id, kind)
    suspend fun saveResume(id: Long, positionMs: Long) = db.tracks().setResume(id, positionMs.coerceAtLeast(0L))
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
    /** Hand edits lock the track so automatic re-labelling never overwrites them. */
    suspend fun setGenres(id: Long, genres: Collection<String>) {
        db.tracks().setGenres(id, genres.flatMap { GenreNormalizer.normalize(it) }.distinct())
        db.tracks().setGenresLocked(id, true)
    }
    suspend fun addGenre(id: Long, genre: String) {
        for (g in GenreNormalizer.normalize(genre)) db.tracks().addGenre(dev.cued.app.data.db.TrackGenreEntity(id, g))
        db.tracks().setGenresLocked(id, true)
    }
    suspend fun removeGenre(id: Long, genre: String) { db.tracks().removeGenre(id, genre); db.tracks().setGenresLocked(id, true) }
    suspend fun unlockGenres(id: Long) = db.tracks().setGenresLocked(id, false)
    /** Set automatically (share payload, downloads): does not lock. */
    suspend fun setGenresAuto(id: Long, genres: Collection<String>) = db.tracks().setGenres(id, genres.flatMap { GenreNormalizer.normalize(it) }.distinct())

    /** Called after every scan; the graph wires it to the genre sweep. */
    var onScanned: (() -> Unit)? = null
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

    /** Result of [deleteFromDevice]: either done, or Android wants the user to confirm through its own dialog first. */
    sealed class DeleteOutcome {
        data class Done(val deleted: Int, val failed: Int) : DeleteOutcome()
        data class NeedsConsent(val sender: android.content.IntentSender, val ids: List<Long>) : DeleteOutcome()
    }

    /**
     * Removes the files from the phone, then the rows. Files CUEd wrote itself
     * delete straight away; files from Termux, the companion or elsewhere are
     * not ours in MediaStore's eyes, so Android 10+ asks the user to confirm
     * (one system dialog for the whole batch on 11+). Call [confirmDeleted]
     * with the same ids once that dialog returns OK.
     */
    suspend fun deleteFromDevice(ids: List<Long>): DeleteOutcome = withContext(Dispatchers.IO) {
        val tracks = db.tracks().byIds(ids)
        val needConsent = ArrayList<TrackEntity>()
        var deleted = 0; var failed = 0
        for (t in tracks) {
            val uri = Uri.parse(t.uri)
            var ok = false
            try {
                ok = context.contentResolver.delete(uri, null, null) > 0
            } catch (e: SecurityException) {
                if (Build.VERSION.SDK_INT >= 30) { needConsent += t; continue }
                if (Build.VERSION.SDK_INT == 29 && e is android.app.RecoverableSecurityException) {
                    // Android 10 can only ask per file; the caller confirms and we are called again for the rest.
                    return@withContext DeleteOutcome.NeedsConsent(e.userAction.actionIntent.intentSender, listOf(t.id))
                }
            }
            if (!ok) {
                // Already gone from MediaStore, or an old Android without scoped storage: try the path.
                val f = t.path?.let { java.io.File(it) }
                ok = f != null && (!f.exists() || f.delete())
            }
            if (ok) { forgetTrack(t); deleted++ } else failed++
        }
        if (needConsent.isNotEmpty() && Build.VERSION.SDK_INT >= 30) {
            val pi = MediaStore.createDeleteRequest(context.contentResolver, needConsent.map { Uri.parse(it.uri) })
            return@withContext DeleteOutcome.NeedsConsent(pi.intentSender, needConsent.map { it.id })
        }
        DeleteOutcome.Done(deleted, failed)
    }

    /** After the system delete dialog returned OK: the files are gone, drop the rows. */
    suspend fun confirmDeleted(ids: List<Long>) = withContext(Dispatchers.IO) {
        for (t in db.tracks().byIds(ids)) forgetTrack(t)
    }

    private suspend fun forgetTrack(t: TrackEntity) {
        db.lyrics().deleteFor(t.id)
        db.tracks().deleteById(t.id) // playlist rows and genres cascade
        runCatching { t.spectrogramPath?.let { java.io.File(it).delete() } }
    }

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
                MediaStore.Audio.Media.TRACK, MediaStore.Audio.Media.YEAR,
            )
            val hasGenreColumn = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            if (hasGenreColumn) projection += MediaStore.Audio.Media.GENRE
            val hasAlbumArtist = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            if (hasAlbumArtist) projection += ALBUM_ARTIST_COLUMN
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 15000"
            val existing = db.tracks().allIncludingMissing().associateBy { it.mediaStoreId }
            // One transaction for the whole pass: a first scan or a schema upgrade touches every row.
            db.withTransaction {
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
                val iTrack = c.getColumnIndex(MediaStore.Audio.Media.TRACK)
                val iYear = c.getColumnIndex(MediaStore.Audio.Media.YEAR)
                val iAlbumArtist = if (hasAlbumArtist) c.getColumnIndex(ALBUM_ARTIST_COLUMN) else -1
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
                    // MediaStore packs disc and track as disc*1000 + track.
                    val rawTrack = if (iTrack >= 0 && !c.isNull(iTrack)) c.getInt(iTrack) else 0
                    val discNo = if (rawTrack >= 1000) rawTrack / 1000 else 0
                    val trackNo = if (rawTrack >= 1000) rawTrack % 1000 else rawTrack.coerceAtLeast(0)
                    val year = if (iYear >= 0 && !c.isNull(iYear)) c.getInt(iYear).takeIf { it in 1900..2100 } ?: 0 else 0
                    val albumArtist = if (iAlbumArtist >= 0) c.getString(iAlbumArtist)?.takeIf { it.isNotBlank() && it != "<unknown>" } else null
                    val old = existing[msId]
                    if (old == null) {
                        val id = db.tracks().insert(
                            TrackEntity(
                                mediaStoreId = msId, uri = uri, path = path, title = title, artist = artist,
                                album = album, albumId = albumId, durationMs = duration, addedAt = added,
                                trackNo = trackNo, discNo = discNo, year = year, albumArtist = albumArtist,
                                kind = if (duration > TrackEntity.LONG_THRESHOLD_MS) TrackEntity.KIND_LONG else TrackEntity.KIND_MUSIC,
                            )
                        )
                        if (id > 0 && !genre.isNullOrBlank()) db.tracks().setGenres(id, GenreNormalizer.normalize(genre))
                    } else if (old.title != title || old.artist != artist || old.album != album || old.uri != uri || old.missing || old.durationMs != duration ||
                        old.trackNo != trackNo || old.discNo != discNo || old.year != year || old.albumArtist != albumArtist) {
                        db.tracks().update(old.copy(title = title, artist = artist, album = album, albumId = albumId, uri = uri, path = path, durationMs = duration, missing = false,
                            trackNo = trackNo, discNo = discNo, year = year, albumArtist = albumArtist))
                    }
                }
            }
            for (t in existing.values) if (t.mediaStoreId !in seen && !t.missing) db.tracks().setMissing(t.id, true)
            } // transaction
        } finally {
            _scanning.value = false
        }
        onScanned?.invoke()
    }

    companion object {
        const val SESSION_GAP_MS = 30 * 60 * 1000L
        /** MediaStore.Audio.AudioColumns.ALBUM_ARTIST; the constant is API 30 but the column name is stable. */
        private const val ALBUM_ARTIST_COLUMN = "album_artist"
        const val SKIP_THRESHOLD = 0.4f

        fun albumArtUri(albumId: Long?): Uri? = albumId?.let {
            ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), it)
        }
    }
}
