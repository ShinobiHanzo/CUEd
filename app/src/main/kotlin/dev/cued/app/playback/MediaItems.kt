package dev.cued.app.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import dev.cued.app.data.LibraryRepository
import dev.cued.app.data.db.TrackEntity

/** Conversions between library rows and Media3 items. `mediaId` is the track's database id. */
object MediaItems {
    const val EXTRA_DURATION_MS = "cued.durationMs"
    const val EXTRA_BPM = "cued.bpm"
    const val EXTRA_KIND = "cued.kind"
    const val EXTRA_RESUME_MS = "cued.resumeMs"

    fun fromTrack(t: TrackEntity): MediaItem {
        val extras = Bundle().apply {
            putLong(EXTRA_DURATION_MS, t.durationMs)
            t.bpm?.let { putFloat(EXTRA_BPM, it) }
            putString(EXTRA_KIND, t.kind)
            putLong(EXTRA_RESUME_MS, t.resumeMs)
        }
        val meta = MediaMetadata.Builder()
            .setTitle(t.title)
            .setArtist(t.artist)
            .setAlbumTitle(t.album)
            .setArtworkUri(LibraryRepository.albumArtUri(t.albumId))
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setMediaId(t.id.toString())
            .setUri(Uri.parse(t.uri))
            .setMediaMetadata(meta)
            .build()
    }

    fun trackId(item: MediaItem): Long? = item.mediaId.toLongOrNull()
    fun durationMs(item: MediaItem): Long = item.mediaMetadata.extras?.getLong(EXTRA_DURATION_MS, 0L) ?: 0L
    fun isLong(item: MediaItem): Boolean = item.mediaMetadata.extras?.getString(EXTRA_KIND) == TrackEntity.KIND_LONG
    fun resumeMs(item: MediaItem): Long = item.mediaMetadata.extras?.getLong(EXTRA_RESUME_MS, 0L) ?: 0L
}
