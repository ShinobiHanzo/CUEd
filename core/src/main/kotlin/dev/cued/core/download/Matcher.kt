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
    data class Scored(val candidate: Candidate, val score: Float, val reasons: List<String>, val otherRecording: Boolean = false)

    private val noise = Regex("\\((official|music|lyric|lyrics|audio|video|hd|hq|4k|visualizer|visualiser)[^)]*\\)|\\[(official|music|lyric|lyrics|audio|video|hd|hq|4k)[^]]*]", RegexOption.IGNORE_CASE)
    private val featPattern = Regex("\\b(feat\\.?|ft\\.?|featuring)\\b.*$", RegexOption.IGNORE_CASE)
    /**
     * Words that mean "a different recording than the one asked for". The hard
     * ones (karaoke, instrumental, covers, tributes) are never acceptable unless the
     * request itself asked for them; the soft ones (live, remix, sped up…) only cost
     * points, since the request may be loose about them.
     */
    val hardWords = listOf("karaoke", "instrumental", "backing track", "minus one", "no vocals", "without vocals", "cover", "tribute", "in the style of", "originally performed", "made famous by", "soundalike", "sound alike", "piano version", "music box", "lullaby", "8 bit", "8bit", "chipmunk")
    val softWords = listOf("live", "remix", "sped up", "slowed", "nightcore", "reverb", "8d", "acoustic", "reaction", "tutorial", "mashup", "extended", "bass boosted", "1 hour", "one hour", "loop", "parody", "edit")
    /** Channels that only publish re-recordings, whatever their video titles say. */
    val hardUploaders = listOf("karaoke", "karafun", "sing king", "stingray", "sing2piano", "singking", "backing track", "instrumental", "tribute", "cover band", "piano tribute", "vitamin string", "lullaby", "8 bit", "rockabye baby")

    /** Hard-word flags for a candidate (title or uploader), minus anything the request itself asked for. */
    fun hardFlags(wanted: Wanted, c: Candidate): List<String> {
        val asked = normalize(wanted.title + " " + wanted.artists.joinToString(" "))
        val title = normalize(c.title)
        val up = normalize(c.uploader)
        val flags = ArrayList<String>()
        val askedForOne = hardWords.any { asked.contains(it) }
        for (w in hardWords) if ((title.contains(w) || up.contains(w)) && !asked.contains(w)) flags += w
        if (!askedForOne) for (u in hardUploaders) if (up.contains(u) && flags.none { it == u }) flags += "uploader:$u"
        return flags
    }

    fun normalize(s: String): String = s.lowercase()
        .replace(noise, " ")
        .replace(Regex("[\\u2018\\u2019'\"`´]"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    fun tokens(s: String): Set<String> = normalize(s).split(' ').filter { it.length > 1 || it.any(Char::isDigit) }.toSet()

    /** 0..1 similarity of two token sets (Jaccard). */
    fun similarity(a: String, b: String): Float = jaccard(tokens(a), tokens(b))

    fun jaccard(ta: Set<String>, tb: Set<String>): Float {
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
            // "Artist - Title (Official Video)" should score like "Title": drop the artist's words from the candidate first.
            val artistTokens = wanted.artists.flatMap { tokens(it) }.toSet()
            val candTokens = tokens(c.title).let { t -> if (artistTokens.isNotEmpty() && (t - artistTokens).isNotEmpty()) t - artistTokens else t }
            val titleSim = maxOf(similarity(wantTitle, c.title), similarity(wanted.title, c.title), jaccard(tokens(wantTitle), candTokens))
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
            val cu = normalize(c.uploader)
            // The auto-generated "Artist - Topic" channel is the label's own audio: the recording asked for, at the album length.
            if (cu.endsWith(" topic")) { score += 1.5f; reasons += "topic channel" }
            for (w in softWords) if (ct.contains(w) && w !in wantedWords && !normalize(wanted.title).contains(w)) { score -= 1.5f; reasons += "has '$w'" }
            val hard = hardFlags(wanted, c)
            for (w in hard) { score -= 5f; reasons += "has '$w'" }
            Scored(c, score, reasons, hard.isNotEmpty())
        }.sortedByDescending { it.score }
    }

    /**
     * Best candidate if it clears a sanity threshold, else null. A karaoke,
     * instrumental or cover upload never qualifies unless the request asked for
     * one: better no download than the wrong recording.
     */
    fun best(wanted: Wanted, candidates: List<Candidate>, minScore: Float = 2.5f): Scored? =
        rank(wanted, candidates).firstOrNull { !it.otherRecording }?.takeIf { it.score >= minScore }

    /** True when the candidate is a karaoke / instrumental / cover of what was asked for. */
    fun isOtherRecording(wanted: Wanted, c: Candidate): Boolean = hardFlags(wanted, c).isNotEmpty()
}
