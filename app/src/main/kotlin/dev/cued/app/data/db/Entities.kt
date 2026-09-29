package dev.cued.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One audio file in the library. [id] is our own key; [mediaStoreId] links
 * back to MediaStore so rescans can update rows without losing user data
 * (genres, favourites, play counts, analysis).
 */
@Entity(tableName = "tracks", indices = [Index("mediaStoreId", unique = true), Index("addedAt"), Index("lastPlayedAt")])
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaStoreId: Long,
    val uri: String,
    val path: String?,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long?,
    val durationMs: Long,
    val addedAt: Long,
    val sourceLink: String? = null,
    val favourite: Boolean = false,
    val playCount: Int = 0,
    val skipCount: Int = 0,
    val lastPlayedAt: Long? = null,
    // Analysis results (null until the analyser has run)
    val bpm: Float? = null,
    val bpmConfidence: Float? = null,
    val firstBeatSec: Float? = null,
    val spectrogramPath: String? = null,
    val analysedAt: Long? = null,
    /** True if the file went missing on the last scan; kept so playlists survive an unplugged SD card. */
    val missing: Boolean = false,
)

/** Genre labels are free text and many-to-many; a track can be "house" and "deep house" at once. */
@Entity(
    tableName = "track_genres",
    primaryKeys = ["trackId", "genre"],
    foreignKeys = [ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("genre")],
)
data class TrackGenreEntity(val trackId: Long, val genre: String)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val description: String = "",
)

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "trackId"],
    foreignKeys = [
        ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("trackId")],
)
data class PlaylistTrackEntity(val playlistId: Long, val trackId: Long, val position: Int)

/** Every play (or skip) is a row: the raw material for trending / recommendations. */
@Entity(tableName = "play_events", indices = [Index("trackId"), Index("at")])
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val at: Long,
    val skipped: Boolean,
    /** Groups consecutive plays into a listening session for co-play stats. */
    val sessionId: Long,
)

@Entity(tableName = "download_jobs", indices = [Index("createdAt")])
data class DownloadJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,
    val title: String?,
    val artist: String?,
    val backend: String,
    val status: String, // QUEUED, RUNNING, DONE, FAILED
    val message: String? = null,
    val progress: Float = 0f,
    val createdAt: Long,
    val finishedAt: Long? = null,
    val remoteJobId: String? = null,
)

/** Lyrics for a track. [synced] is LRC text when available; [source] is embedded / sidecar / lrclib / none. */
@Entity(tableName = "lyrics")
data class LyricsEntity(
    @PrimaryKey val trackId: Long,
    val plain: String?,
    val synced: String?,
    val source: String,
    val fetchedAt: Long,
)
