package dev.cued.core.ledger

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Your listening ledger: everything the play log says about a period,
 * computed on the device from the raw play events. Nothing is sent anywhere;
 * the export is a JSON file you own.
 */
@Serializable
data class LedgerPlay(
    val trackKey: String,
    val at: Long,
    val skipped: Boolean,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0,
    val genres: List<String> = emptyList(),
    /** When the track entered the library, to count discoveries. 0 = unknown. */
    val addedAt: Long = 0,
)

@Serializable
data class LedgerEntry(val name: String, val plays: Int, val listenedMs: Long, val sub: String = "")

@Serializable
data class LedgerSummary(
    val fromMs: Long,
    val toMs: Long,
    val plays: Int,
    val skips: Int,
    val listenedMs: Long,
    val uniqueTracks: Int,
    val uniqueArtists: Int,
    /** Tracks whose first ever play fell inside the period. */
    val discoveries: Int,
    val activeDays: Int,
    val longestStreakDays: Int,
    val currentStreakDays: Int,
    /** Plays per hour of day (24 entries) and per weekday (7, Monday first). */
    val byHour: List<Int>,
    val byWeekday: List<Int>,
    /** Plays per calendar day across the period, oldest first, `[epochDay, plays]`. */
    val byDay: List<List<Long>>,
    val topArtists: List<LedgerEntry>,
    val topTracks: List<LedgerEntry>,
    val topAlbums: List<LedgerEntry>,
    val topGenres: List<LedgerEntry>,
    val peakDay: Long? = null,
    val peakDayPlays: Int = 0,
    /** Share of plays that were skips, 0..1. */
    val skipRate: Float = 0f,
)

object Ledger {
    val json: Json = Json { encodeDefaults = true; prettyPrint = true }

    /** Period presets, as [from, to) epoch millis in [zone]. */
    fun thisMonth(now: Long, zone: ZoneId): Pair<Long, Long> {
        val d = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().withDayOfMonth(1)
        return d.atStartOfDay(zone).toInstant().toEpochMilli() to d.plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    fun lastMonth(now: Long, zone: ZoneId): Pair<Long, Long> {
        val d = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().withDayOfMonth(1).minusMonths(1)
        return d.atStartOfDay(zone).toInstant().toEpochMilli() to d.plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    fun thisYear(now: Long, zone: ZoneId): Pair<Long, Long> {
        val d = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().withDayOfYear(1)
        return d.atStartOfDay(zone).toInstant().toEpochMilli() to d.plusYears(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    fun lastDays(now: Long, days: Int, zone: ZoneId): Pair<Long, Long> {
        val end = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone)
        return end.minusDays(days.toLong()).toInstant().toEpochMilli() to end.toInstant().toEpochMilli()
    }

    /**
     * Summarises [all] plays restricted to [fromMs, toMs). The whole history is
     * passed so discoveries (first play ever inside the period) can be told
     * from repeats. Listening time counts a full duration per completed play
     * and a quarter per skip (we do not log where the skip happened).
     */
    fun summarise(all: List<LedgerPlay>, fromMs: Long, toMs: Long, zone: ZoneId, now: Long = toMs, top: Int = 10): LedgerSummary {
        val firstPlay = HashMap<String, Long>()
        for (p in all) firstPlay.merge(p.trackKey, p.at) { a, b -> minOf(a, b) }
        val inRange = all.filter { it.at >= fromMs && it.at < toMs }.sortedBy { it.at }

        val byHour = IntArray(24)
        val byWeekday = IntArray(7)
        val byDay = sortedMapOf<Long, Int>()
        val artists = HashMap<String, Acc>()
        val tracks = HashMap<String, Acc>()
        val albums = HashMap<String, Acc>()
        val genres = HashMap<String, Acc>()
        var skips = 0
        var listened = 0L
        var discoveries = 0
        val seenTracks = HashSet<String>()
        val seenArtists = HashSet<String>()

        for (p in inRange) {
            val z: ZonedDateTime = Instant.ofEpochMilli(p.at).atZone(zone)
            byHour[z.hour]++
            byWeekday[z.dayOfWeek.value - 1]++
            byDay.merge(z.toLocalDate().toEpochDay(), 1, Int::plus)
            val ms = if (p.skipped) p.durationMs / 4 else p.durationMs
            listened += ms
            if (p.skipped) skips++
            if (seenTracks.add(p.trackKey) && (firstPlay[p.trackKey] ?: p.at) >= fromMs) discoveries++
            if (p.artist.isNotBlank()) seenArtists.add(p.artist.lowercase())
            if (!p.skipped) {
                artists.getOrPut(p.artist.ifBlank { "Unknown artist" }) { Acc() }.add(ms)
                tracks.getOrPut(p.trackKey) { Acc(sub = p.artist, name = p.title) }.add(ms)
                if (p.album.isNotBlank()) albums.getOrPut("${p.album}\u0000${p.artist}") { Acc(name = p.album, sub = p.artist) }.add(ms)
                for (g in p.genres) genres.getOrPut(g) { Acc() }.add(ms)
            }
        }

        val days = byDay.keys.toList()
        val (longest, current) = streaks(days, Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay())
        val peak = byDay.maxByOrNull { it.value }

        fun top(m: Map<String, Acc>, defaultName: (String) -> String) = m.entries
            .sortedWith(compareByDescending<Map.Entry<String, Acc>> { it.value.plays }.thenByDescending { it.value.ms })
            .take(top)
            .map { (k, a) -> LedgerEntry(a.name ?: defaultName(k), a.plays, a.ms, a.sub) }

        return LedgerSummary(
            fromMs = fromMs, toMs = toMs,
            plays = inRange.size, skips = skips, listenedMs = listened,
            uniqueTracks = seenTracks.size, uniqueArtists = seenArtists.size,
            discoveries = discoveries,
            activeDays = days.size, longestStreakDays = longest, currentStreakDays = current,
            byHour = byHour.toList(), byWeekday = byWeekday.toList(),
            byDay = byDay.map { listOf(it.key, it.value.toLong()) },
            topArtists = top(artists) { it }, topTracks = top(tracks) { it }, topAlbums = top(albums) { it.substringBefore('\u0000') }, topGenres = top(genres) { it },
            peakDay = peak?.key, peakDayPlays = peak?.value ?: 0,
            skipRate = if (inRange.isEmpty()) 0f else skips.toFloat() / inRange.size,
        )
    }

    /** Longest run of consecutive active days, and the run ending today or yesterday. */
    fun streaks(activeEpochDays: List<Long>, todayEpochDay: Long): Pair<Int, Int> {
        if (activeEpochDays.isEmpty()) return 0 to 0
        val days = activeEpochDays.distinct().sorted()
        var longest = 1; var run = 1
        for (i in 1 until days.size) {
            run = if (days[i] == days[i - 1] + 1) run + 1 else 1
            if (run > longest) longest = run
        }
        val last = days.last()
        val current = if (last == todayEpochDay || last == todayEpochDay - 1) run else 0
        return longest to current
    }

    fun export(summary: LedgerSummary, plays: List<LedgerPlay>): String =
        json.encodeToString(LedgerExport(summary = summary, plays = plays))

    fun formatHours(ms: Long): String {
        val mins = ms / 60_000
        return if (mins < 60) "${mins} min" else "${mins / 60} h ${mins % 60} min"
    }

    fun dayLabel(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).toString()

    private class Acc(var name: String? = null, var sub: String = "") {
        var plays = 0
        var ms = 0L
        fun add(m: Long) { plays++; ms += m }
    }
}

@Serializable
data class LedgerExport(val format: String = "cued-ledger/1", val summary: LedgerSummary, val plays: List<LedgerPlay>)
