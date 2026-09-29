package dev.cued.core.reco

import dev.cued.core.model.TrackStats
import kotlin.math.exp
import kotlin.math.max

/**
 * The auto-curated lists. Everything is a plain, explainable heuristic over
 * local listening history. There is no model, no network and no training
 * data: the rules below are the whole story, and each one is documented so
 * they can be tuned or replaced.
 */
object SmartLists {
    const val DAY_MS = 86_400_000L

    /**
     * Trending: plays weighted by recency. A play today counts 1.0, a play a
     * week ago counts ~0.5 (half-life of one week). Skips subtract.
     */
    fun trending(tracks: List<TrackStats>, plays: List<PlayEvent>, now: Long, limit: Int = 50): List<TrackStats> {
        val halfLifeMs = 7 * DAY_MS
        val score = HashMap<Long, Double>()
        for (p in plays) {
            val age = max(0L, now - p.at).toDouble()
            val w = exp(-age * 0.693 / halfLifeMs)
            score.merge(p.trackId, if (p.skipped) -0.5 * w else w, Double::plus)
        }
        return tracks.filter { (score[it.id] ?: 0.0) > 0.0 }
            .sortedByDescending { score[it.id] ?: 0.0 }
            .take(limit)
    }

    /** Newly downloaded: newest first. */
    fun newlyAdded(tracks: List<TrackStats>, limit: Int = 50): List<TrackStats> =
        tracks.sortedByDescending { it.addedAt }.take(limit)

    /** Unplayed: never played, oldest additions first so nothing rots at the bottom. */
    fun unplayed(tracks: List<TrackStats>, limit: Int = 100): List<TrackStats> =
        tracks.filter { it.playCount == 0 }.sortedBy { it.addedAt }.take(limit)

    /**
     * Forgotten: played before, liked enough (>= [minPlays] plays) but not
     * heard in [staleDays]. Sorted by how long it's been.
     */
    fun forgotten(tracks: List<TrackStats>, now: Long, staleDays: Int = 45, minPlays: Int = 2, limit: Int = 100): List<TrackStats> =
        tracks.filter { t ->
            val last = t.lastPlayedAt ?: return@filter false
            t.playCount >= minPlays && now - last > staleDays * DAY_MS
        }.sortedBy { it.lastPlayedAt }.take(limit)

    fun favourites(tracks: List<TrackStats>): List<TrackStats> =
        tracks.filter { it.favourite }.sortedByDescending { it.lastPlayedAt ?: 0L }
}

data class PlayEvent(val trackId: Long, val at: Long, val skipped: Boolean = false)
