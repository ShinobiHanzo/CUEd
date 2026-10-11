package dev.cued.app.data.db

import androidx.room.ColumnInfo
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
    /** [KIND_MUSIC] or [KIND_LONG] (podcasts, audiobooks, mixes over 12 minutes). Set on scan, user-overridable. */
    @ColumnInfo(defaultValue = "music") val kind: String = KIND_MUSIC,
    /** Last playback position for long plays so they resume where you left off. */
    @ColumnInfo(defaultValue = "0") val resumeMs: Long = 0L,
    /** True once the user edits genres by hand; automatic re-labelling then leaves this track alone. */
    @ColumnInfo(defaultValue = "0") val genresLocked: Boolean = false,
    /** Position on its album (0 = unknown); used to order album pages. */
    @ColumnInfo(defaultValue = "0") val trackNo: Int = 0,
    @ColumnInfo(defaultValue = "0") val discNo: Int = 0,
    /** Release year from tags (0 = unknown). */
    @ColumnInfo(defaultValue = "0") val year: Int = 0,
    /** Album-artist tag when the file has one; groups compilations and "feat." tracks under the right artist. */
    val albumArtist: String? = null,
) {
    val isLong: Boolean get() = kind == KIND_LONG

    companion object {
        const val KIND_MUSIC = "music"
        const val KIND_LONG = "long"
        /** Temporary file fetched for a station you are listening to; lives in the cache, hidden from the library until kept. */
        const val KIND_STATION = "station"
        /** Anything longer than this is treated as a podcast / audiobook / mix, not a song. */
        const val LONG_THRESHOLD_MS = 12L * 60_000L
    }
}

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
    /** Cover/thumbnail for the job row, once known (Spotify cover or YouTube thumbnail). */
    @ColumnInfo(defaultValue = "NULL") val artworkUrl: String? = null,
    /** The CUEd playlist this track joins when it lands (a playlist link being rebuilt), and its slot in it. */
    @ColumnInfo(defaultValue = "NULL") val playlistId: Long? = null,
    @ColumnInfo(defaultValue = "0") val playlistPosition: Int = 0,
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

/**
 * An artist the release feed watches: followed by hand ([followed]) or picked
 * up automatically from what you play. [mbid] is the MusicBrainz id once found.
 */
@Entity(tableName = "feed_artists")
data class FeedArtistEntity(
    @PrimaryKey val name: String,
    val mbid: String?,
    val followed: Boolean,
    val addedAt: Long,
    val lastRefreshAt: Long = 0L,
    val lastError: String? = null,
    /** Spotify artist id when the feed source is Spotify. */
    val spotifyId: String? = null,
)

/** One release group (album, EP or single) from MusicBrainz, with its Cover Art Archive URL. */
@Entity(tableName = "releases", indices = [Index("artist"), Index("dateMs")])
data class ReleaseEntity(
    @PrimaryKey val id: String,
    val artist: String,
    val artistMbid: String,
    val title: String,
    /** Album, EP or Single. */
    val type: String,
    /** As MusicBrainz gives it: yyyy, yyyy-mm or yyyy-mm-dd; empty when unknown. */
    val date: String,
    /** [date] parsed to UTC midnight, 0 when unknown; what the feed sorts and filters by. */
    val dateMs: Long,
    val coverUrl: String?,
    val fetchedAt: Long,
    /** When the track list was last fetched; 0 = not yet. */
    val tracksFetchedAt: Long = 0L,
    /** Where this row came from: musicbrainz or spotify ([id] is that source's id). */
    @ColumnInfo(defaultValue = "musicbrainz") val source: String = SOURCE_MUSICBRAINZ,
    val spotifyId: String? = null,
) {
    companion object {
        const val SOURCE_MUSICBRAINZ = "musicbrainz"
        const val SOURCE_SPOTIFY = "spotify"
    }
}

/** A track of a release, metadata only; the audio arrives when you play or download it. */
@Entity(
    tableName = "release_tracks",
    primaryKeys = ["releaseId", "position"],
    foreignKeys = [ForeignKey(entity = ReleaseEntity::class, parentColumns = ["id"], childColumns = ["releaseId"], onDelete = ForeignKey.CASCADE)],
)
data class ReleaseTrackEntity(
    val releaseId: String,
    val position: Int,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val recordingId: String?,
    /** Spotify track id when known: downloads then carry Spotify's metadata. */
    val spotifyId: String? = null,
)
