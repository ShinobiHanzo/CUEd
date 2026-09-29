package dev.cued.core.model

/**
 * The slice of a track's metadata that the pure-Kotlin logic needs. The
 * Android app maps its Room entities into this.
 */
data class TrackStats(
    val id: Long,
    val title: String,
    val artist: String,
    val genres: Set<String>,
    val bpm: Float?,
    val playCount: Int,
    val lastPlayedAt: Long?,   // epoch millis, null if never played
    val addedAt: Long,         // epoch millis
    val favourite: Boolean,
    val skipCount: Int = 0,
    val durationMs: Long = 0L,
)
