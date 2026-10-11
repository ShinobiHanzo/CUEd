package dev.cued.core.feed

import dev.cued.core.download.Matcher
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The pure parts of the release feed: which artists count as "listened to",
 * what counts as a new release, how MusicBrainz's partial dates and release
 * types are read. Everything network- or database-shaped lives in the app.
 */
object ReleaseFeed {
    const val DAY_MS = 24L * 60 * 60 * 1000
    /** A release is "new" when it came out within this many days (or is announced up to two weeks ahead). */
    const val RECENT_DAYS = 120
    const val UPCOMING_DAYS = 14

    data class ArtistPlays(val name: String, val playCount: Int, val recentPlays: Int, val tracks: Int)

    private val ignoredArtists = setOf("", "unknown artist", "unknown", "various artists", "various", "va", "soundtrack")

    /** Artists worth following automatically: recent plays weigh most, then lifetime plays, then how much of them you own. */
    fun rankListened(stats: List<ArtistPlays>, limit: Int = 12): List<String> = stats
        .filter { it.name.trim().lowercase() !in ignoredArtists && (it.playCount > 0 || it.recentPlays > 0) }
        .sortedByDescending { it.recentPlays * 3.0 + it.playCount + minOf(it.tracks, 10) * 0.5 }
        .map { it.name.trim() }
        .distinctBy { it.lowercase() }
        .take(limit)

    /** MusicBrainz dates are `yyyy`, `yyyy-mm` or `yyyy-mm-dd`; the missing parts mean the first day. UTC midnight, or null. */
    fun parseDate(s: String?): Long? {
        val t = s?.trim().orEmpty()
        if (t.isEmpty()) return null
        val parts = t.split('-')
        val y = parts.getOrNull(0)?.toIntOrNull() ?: return null
        if (y < 1000 || y > 3000) return null
        val m = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 12) ?: 1
        val d = parts.getOrNull(2)?.toIntOrNull()?.coerceIn(1, 31) ?: 1
        return runCatching { LocalDate.of(y, m, minOf(d, LocalDate.of(y, m, 1).lengthOfMonth())).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
    }

    fun isRecent(dateMs: Long, now: Long, days: Int = RECENT_DAYS): Boolean =
        dateMs > 0 && dateMs >= now - days * DAY_MS && dateMs <= now + UPCOMING_DAYS * DAY_MS

    fun isUpcoming(dateMs: Long, now: Long): Boolean = dateMs > now

    /** "17 May 2024", "May 2024" or "2024", following how much MusicBrainz knew. */
    fun formatDate(date: String): String {
        val t = date.trim()
        val parts = t.split('-')
        val y = parts.getOrNull(0)?.toIntOrNull() ?: return t
        val m = parts.getOrNull(1)?.toIntOrNull()
        val d = parts.getOrNull(2)?.toIntOrNull()
        return when {
            m == null -> y.toString()
            d == null -> LocalDate.of(y, m.coerceIn(1, 12), 1).format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH))
            else -> runCatching { LocalDate.of(y, m, d).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)) }.getOrDefault(t)
        }
    }

    /**
     * Label for a release group, or null when it is not something the feed
     * should list (compilations, live sets, remix albums, DJ mixes, soundtracks).
     */
    fun typeLabel(primary: String?, secondary: List<String>): String? {
        val sec = secondary.map { it.lowercase() }
        if (sec.any { it in setOf("compilation", "live", "remix", "dj-mix", "soundtrack", "mixtape/street", "demo", "interview", "audiobook", "audio drama", "spokenword") }) return null
        return when (primary?.lowercase()) {
            "album" -> "Album"
            "ep" -> "EP"
            "single" -> "Single"
            null, "" -> null
            else -> null
        }
    }

    data class ArtistCandidate(val id: String, val name: String, val score: Int, val disambiguation: String = "")

    /** The MusicBrainz artist for a name: an exact (normalised) name with a good score, else only a near-perfect score. */
    fun pickArtist(query: String, candidates: List<ArtistCandidate>): ArtistCandidate? {
        val want = Matcher.normalize(query)
        val exact = candidates.filter { Matcher.normalize(it.name) == want && it.score >= 85 }.maxByOrNull { it.score }
        if (exact != null) return exact
        return candidates.filter { it.score >= 97 }.maxByOrNull { it.score }
    }

    /** Same song? Title words must largely agree and the artist must match; feat. tags and "(Remastered)" do not matter. */
    fun sameTrack(title: String, artist: String, otherTitle: String, otherArtist: String): Boolean {
        val a = Matcher.normalize(artist); val b = Matcher.normalize(otherArtist)
        val artistOk = a == b || a.contains(b) || b.contains(a)
        return artistOk && Matcher.similarity(title, otherTitle) >= 0.6f
    }
}
