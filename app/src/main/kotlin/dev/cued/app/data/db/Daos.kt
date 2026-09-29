package dev.cued.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks WHERE missing = 0 ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE missing = 0")
    suspend fun all(): List<TrackEntity>

    @Query("SELECT * FROM tracks")
    suspend fun allIncludingMissing(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun byId(id: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id = :id")
    fun observeById(id: Long): Flow<TrackEntity?>

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE mediaStoreId = :mediaStoreId")
    suspend fun byMediaStoreId(mediaStoreId: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE missing = 0 AND (title LIKE '%' || :q || '%' OR artist LIKE '%' || :q || '%' OR album LIKE '%' || :q || '%') ORDER BY title COLLATE NOCASE")
    fun search(q: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE missing = 0 AND analysedAt IS NULL ORDER BY lastPlayedAt DESC, addedAt DESC LIMIT :limit")
    suspend fun unanalysed(limit: Int): List<TrackEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(track: TrackEntity): Long

    @Update
    suspend fun update(track: TrackEntity)

    @Query("UPDATE tracks SET favourite = :fav WHERE id = :id")
    suspend fun setFavourite(id: Long, fav: Boolean)

    @Query("UPDATE tracks SET playCount = playCount + 1, lastPlayedAt = :at WHERE id = :id")
    suspend fun recordPlay(id: Long, at: Long)

    @Query("UPDATE tracks SET skipCount = skipCount + 1 WHERE id = :id")
    suspend fun recordSkip(id: Long)

    @Query("UPDATE tracks SET bpm = :bpm, bpmConfidence = :confidence, firstBeatSec = :firstBeat, spectrogramPath = :spectrogramPath, analysedAt = :at WHERE id = :id")
    suspend fun setAnalysis(id: Long, bpm: Float?, confidence: Float?, firstBeat: Float?, spectrogramPath: String?, at: Long)

    @Query("UPDATE tracks SET missing = :missing WHERE id = :id")
    suspend fun setMissing(id: Long, missing: Boolean)

    @Query("UPDATE tracks SET sourceLink = :link WHERE id = :id")
    suspend fun setSourceLink(id: Long, link: String?)

    // Genres
    @Query("SELECT genre FROM track_genres WHERE trackId = :trackId ORDER BY genre")
    suspend fun genresOf(trackId: Long): List<String>

    @Query("SELECT * FROM track_genres")
    suspend fun allGenreRows(): List<TrackGenreEntity>

    @Query("SELECT * FROM track_genres")
    fun observeGenreRows(): Flow<List<TrackGenreEntity>>

    @Query("SELECT DISTINCT genre FROM track_genres ORDER BY genre COLLATE NOCASE")
    fun observeGenres(): Flow<List<String>>

    @Query("SELECT t.* FROM tracks t JOIN track_genres g ON g.trackId = t.id WHERE g.genre = :genre AND t.missing = 0 ORDER BY t.title COLLATE NOCASE")
    fun observeByGenre(genre: String): Flow<List<TrackEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addGenre(row: TrackGenreEntity)

    @Query("DELETE FROM track_genres WHERE trackId = :trackId AND genre = :genre")
    suspend fun removeGenre(trackId: Long, genre: String)

    @Query("DELETE FROM track_genres WHERE trackId = :trackId")
    suspend fun clearGenres(trackId: Long)

    @Transaction
    suspend fun setGenres(trackId: Long, genres: Collection<String>) {
        clearGenres(trackId)
        for (g in genres.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()) addGenre(TrackGenreEntity(trackId, g))
    }
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observe(id: Long): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun byId(id: Long): PlaylistEntity?

    @Insert
    suspend fun insert(p: PlaylistEntity): Long

    @Update
    suspend fun update(p: PlaylistEntity)

    @Delete
    suspend fun delete(p: PlaylistEntity)

    @Query("SELECT t.* FROM tracks t JOIN playlist_tracks pt ON pt.trackId = t.id WHERE pt.playlistId = :playlistId ORDER BY pt.position")
    fun observeTracks(playlistId: Long): Flow<List<TrackEntity>>

    @Query("SELECT t.* FROM tracks t JOIN playlist_tracks pt ON pt.trackId = t.id WHERE pt.playlistId = :playlistId ORDER BY pt.position")
    suspend fun tracks(playlistId: Long): List<TrackEntity>

    @Query("SELECT COUNT(*) FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun count(playlistId: Long): Int

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position")
    suspend fun rows(playlistId: Long): List<PlaylistTrackEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRow(row: PlaylistTrackEntity)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun removeRow(playlistId: Long, trackId: Long)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun clearRows(playlistId: Long)

    @Transaction
    suspend fun addTrack(playlistId: Long, trackId: Long) {
        insertRow(PlaylistTrackEntity(playlistId, trackId, count(playlistId)))
    }

    @Transaction
    suspend fun reorder(playlistId: Long, trackIds: List<Long>) {
        clearRows(playlistId)
        trackIds.forEachIndexed { i, id -> insertRow(PlaylistTrackEntity(playlistId, id, i)) }
    }

    @Query("SELECT playlistId FROM playlist_tracks WHERE trackId = :trackId")
    suspend fun playlistsContaining(trackId: Long): List<Long>
}

@Dao
interface PlayEventDao {
    @Insert
    suspend fun insert(e: PlayEventEntity)

    @Query("SELECT * FROM play_events WHERE at >= :since ORDER BY at")
    suspend fun since(since: Long): List<PlayEventEntity>

    @Query("SELECT * FROM play_events WHERE at >= :since ORDER BY at")
    fun observeSince(since: Long): Flow<List<PlayEventEntity>>

    @Query("SELECT MAX(sessionId) FROM play_events")
    suspend fun lastSessionId(): Long?

    @Query("SELECT MAX(at) FROM play_events")
    suspend fun lastEventAt(): Long?
}

@Dao
interface DownloadJobDao {
    @Query("SELECT * FROM download_jobs ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadJobEntity>>

    @Query("SELECT * FROM download_jobs WHERE status IN ('QUEUED','RUNNING') ORDER BY createdAt")
    suspend fun pending(): List<DownloadJobEntity>

    @Query("SELECT * FROM download_jobs WHERE id = :id")
    suspend fun byId(id: Long): DownloadJobEntity?

    @Insert
    suspend fun insert(j: DownloadJobEntity): Long

    @Update
    suspend fun update(j: DownloadJobEntity)

    @Query("DELETE FROM download_jobs WHERE status IN ('DONE','FAILED')")
    suspend fun clearFinished()
}
