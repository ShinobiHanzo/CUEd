package dev.cued.core.download

import kotlin.math.abs

/**
 * Picks the YouTube Music result that best matches a wanted track, the way
 * spotdl does: title words, artist presence, and duration closeness. Plain
 * scoring, no model, tuned to prefer studio versions over live/remix/cover
 * uploads unless those words were in the request.
 */
object Matcher {
    data class Wanted(val title: String, val artists: List<String>, val durationSec: Int?)
    data class Candidate(val id: String, val title: String, val uploader: String, val durationSec: Int?)
    data class Scored(val candidate: Candidate, val score: Float, val reasons: List<String>)

    private val noise = Regex("\\((official|music|lyric|lyrics|audio|video|hd|hq|4k|visualizer|visualiser)[^)]*\\)|\\[(official|music|lyric|lyrics|audio|video|hd|hq|4k)[^]]*]", RegexOption.IGNORE_CASE)
    private val featPattern = Regex("\\b(feat\\.?|ft\\.?|featuring)\\b.*$", RegexOption.IGNORE_CASE)
    private val badWords = listOf("live", "remix", "cover", "karaoke", "instrumental", "sped up", "slowed", "nightcore", "reverb", "8d", "acoustic", "reaction", "tutorial", "mashup", "extended")

    fun normalize(s: String): String = s.lowercase()
        .replace(noise, " ")
        .replace(Regex("[\\u2018\\u2019'\"`´]"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    fun tokens(s: String): Set<String> = normalize(s).split(' ').filter { it.length > 1 || it.any(Char::isDigit) }.toSet()

    /** 0..1 similarity of two token sets (Jaccard). */
    fun similarity(a: String, b: String): Float {
        val ta = tokens(a); val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0f
        val inter = ta.intersect(tb).size.toFloat()
        return inter / (ta.size + tb.size - inter)
    }

    fun rank(wanted: Wanted, candidates: List<Candidate>): List<Scored> {
        val wantTitle = wanted.title.replace(featPattern, "").trim()
        val wantedWords = tokens(wanted.title + " " + wanted.artists.joinToString(" "))
        return candidates.map { c ->
            val reasons = ArrayList<String>()
            var score = 0f
            val titleSim = maxOf(similarity(wantTitle, c.title), similarity(wanted.title, c.title))
            score += 3f * titleSim
            if (normalize(c.title).contains(normalize(wantTitle)) && wantTitle.isNotBlank()) { score += 1f; reasons += "title contained" }
            val artistHit = wanted.artists.any { a -> val n = normalize(a); n.isNotBlank() && (normalize(c.uploader).contains(n) || normalize(c.title).contains(n)) }
            if (artistHit) { score += 2f; reasons += "artist matches" }
            if (wanted.durationSec != null && c.durationSec != null && c.durationSec > 0) {
                val diff = abs(wanted.durationSec - c.durationSec)
                when {
                    diff <= 3 -> { score += 2f; reasons += "duration exact" }
                    diff <= 10 -> { score += 1f; reasons += "duration close" }
                    diff > 60 -> { score -= 3f; reasons += "duration far off" }
                    else -> score -= 0.5f
                }
            }
            val ct = normalize(c.title)
            for (w in badWords) if (ct.contains(w) && w !in wantedWords && !normalize(wanted.title).contains(w)) { score -= 1.5f; reasons += "has '$w'" }
            Scored(c, score, reasons)
        }.sortedByDescending { it.score }
    }

    /** Best candidate if it clears a sanity threshold, else null. */
    fun best(wanted: Wanted, candidates: List<Candidate>, minScore: Float = 2.5f): Scored? =
        rank(wanted, candidates).firstOrNull()?.takeIf { it.score >= minScore }
}
