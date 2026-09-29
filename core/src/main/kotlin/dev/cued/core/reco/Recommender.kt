package dev.cued.core.reco

import dev.cued.core.model.TrackStats
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

/**
 * Deterministic, explainable recommendations.
 *
 * Score(track) =
 *   w_genre  * genre affinity (how much you play the genres this track carries)
 *   + w_artist * artist affinity
 *   + w_tempo  * closeness of BPM to your recent listening tempo (up to simple ratios)
 *   + w_coplay * how often it was played in the same session as tracks you play a lot
 *   - penalty for tracks you skip
 *   + small bonus for tracks you haven't heard in a while (so the list rotates)
 *
 * Every term is a count or a ratio computed from local history. Weights are
 * exposed so they can be tuned from the settings screen.
 */
class Recommender(
    private val wGenre: Float = 1.0f,
    private val wArtist: Float = 0.6f,
    private val wTempo: Float = 0.5f,
    private val wCoplay: Float = 0.8f,
    private val wSkip: Float = 0.7f,
    private val wRotation: Float = 0.3f,
) {
    data class Scored(val track: TrackStats, val score: Float, val reasons: List<String>)

    fun recommend(
        tracks: List<TrackStats>,
        plays: List<PlayEvent>,
        sessions: List<List<Long>> = emptyList(),
        now: Long,
        seed: TrackStats? = null,
        limit: Int = 30,
    ): List<Scored> {
        if (tracks.isEmpty()) return emptyList()
        val byId = tracks.associateBy { it.id }

        // Affinity tables from play history (recent plays count more).
        val genreAff = HashMap<String, Float>()
        val artistAff = HashMap<String, Float>()
        var tempoSum = 0f; var tempoWeight = 0f
        for (p in plays) {
            val t = byId[p.trackId] ?: continue
            val ageDays = max(0L, now - p.at) / SmartLists.DAY_MS.toFloat()
            val w = 1f / (1f + ageDays / 14f)
            if (p.skipped) continue
            for (g in t.genres) genreAff.merge(g, w, Float::plus)
            artistAff.merge(t.artist, w, Float::plus)
            t.bpm?.let { if (it > 0f) { tempoSum += it * w; tempoWeight += w } }
        }
        val targetBpm = seed?.bpm ?: if (tempoWeight > 0f) tempoSum / tempoWeight else null
        val maxGenre = genreAff.values.maxOrNull() ?: 1f
        val maxArtist = artistAff.values.maxOrNull() ?: 1f

        // Co-play: for each track, how often it appears in a session with a heavily played track.
        val heavy = tracks.sortedByDescending { it.playCount }.take(20).map { it.id }.toSet()
        val coplay = HashMap<Long, Float>()
        for (s in sessions) {
            val hasHeavy = s.any { it in heavy }
            if (!hasHeavy) continue
            for (id in s) if (id !in heavy) coplay.merge(id, 1f, Float::plus)
        }
        val maxCoplay = coplay.values.maxOrNull() ?: 1f

        val scored = tracks.filter { it.id != seed?.id }.map { t ->
            val reasons = ArrayList<String>(4)
            var s = 0f
            if (seed != null) {
                val shared = t.genres intersect seed.genres
                if (shared.isNotEmpty()) { s += wGenre; reasons += "shares ${shared.first()}" }
                if (t.artist == seed.artist) { s += wArtist; reasons += "same artist" }
            } else {
                val g = t.genres.maxOfOrNull { genreAff[it] ?: 0f } ?: 0f
                if (g > 0f) { s += wGenre * g / maxGenre; reasons += "you play ${t.genres.maxByOrNull { genreAff[it] ?: 0f }}" }
                val a = artistAff[t.artist] ?: 0f
                if (a > 0f) { s += wArtist * a / maxArtist; reasons += "you play ${t.artist}" }
            }
            if (targetBpm != null && t.bpm != null && t.bpm > 0f) {
                val close = tempoCloseness(targetBpm, t.bpm)
                if (close > 0.5f) { s += wTempo * close; reasons += "tempo fits" }
            }
            val c = coplay[t.id] ?: 0f
            if (c > 0f) { s += wCoplay * c / maxCoplay; reasons += "often played together" }
            if (t.skipCount > 0) s -= wSkip * (t.skipCount.toFloat() / (t.playCount + t.skipCount))
            val last = t.lastPlayedAt
            if (last == null) { s += wRotation; reasons += "never played" }
            else {
                val days = (now - last) / SmartLists.DAY_MS.toFloat()
                if (days > 14f) { s += wRotation * minOf(1f, days / 90f); reasons += "not heard in a while" }
            }
            Scored(t, s, reasons)
        }
        return scored.filter { it.score > 0f }.sortedByDescending { it.score }.take(limit)
    }

    /** 1.0 when the tempos match up to a simple ratio, decaying to 0 at ~15% off. */
    fun tempoCloseness(a: Float, b: Float): Float {
        var best = Float.MAX_VALUE
        for ((n, d) in dev.cued.core.mix.TempoMatcher.RATIOS) {
            best = minOf(best, abs(ln(a / (b * n / d))))
        }
        return (1f - best / 0.15f).coerceIn(0f, 1f)
    }
}
