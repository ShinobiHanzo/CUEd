package dev.cued.core

import dev.cued.core.ledger.Ledger
import dev.cued.core.ledger.LedgerPlay
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LedgerTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private fun at(date: String, hour: Int) = LocalDate.parse(date).atStartOfDay(zone).plusHours(hour.toLong()).toInstant().toEpochMilli()
    private fun play(key: String, date: String, hour: Int, artist: String, skipped: Boolean = false, album: String = "", genres: List<String> = emptyList()) =
        LedgerPlay(trackKey = key, at = at(date, hour), skipped = skipped, title = "Song $key", artist = artist, album = album, durationMs = 240_000, genres = genres)

    @Test fun summaryCountsTopsStreaksAndDiscoveries() {
        val plays = listOf(
            play("a", "2026-03-01", 9, "Daft Punk", album = "Discovery", genres = listOf("house")),
            play("a", "2026-03-02", 21, "Daft Punk", album = "Discovery", genres = listOf("house")),
            play("b", "2026-03-02", 22, "Daft Punk", album = "Discovery", genres = listOf("house")),
            play("c", "2026-03-03", 8, "Portishead", genres = listOf("trip-hop")),
            play("d", "2026-03-03", 8, "Portishead", skipped = true),
            play("c", "2026-03-10", 23, "Portishead", genres = listOf("trip-hop")),
            // Outside the period: an earlier play of "a" makes it a repeat, not a discovery.
            play("a", "2026-02-20", 12, "Daft Punk"),
            play("z", "2026-04-01", 12, "Nobody"),
        )
        val (from, to) = Ledger.thisMonth(at("2026-03-15", 12), zone)
        val s = Ledger.summarise(plays, from, to, zone, now = at("2026-03-11", 9))
        assertEquals(6, s.plays)
        assertEquals(1, s.skips)
        assertEquals(4, s.uniqueTracks)
        assertEquals(2, s.uniqueArtists)
        // a was played in February, so only b, c, d are new this month.
        assertEquals(3, s.discoveries)
        assertEquals(5 * 240_000L + 60_000L, s.listenedMs)
        assertEquals(4, s.activeDays)
        assertEquals(3, s.longestStreakDays)
        // Last active day is the 10th, "now" is the 11th: the streak is still alive at 1.
        assertEquals(1, s.currentStreakDays)
        assertEquals("Daft Punk", s.topArtists.first().name)
        assertEquals(3, s.topArtists.first().plays)
        assertEquals("Song a", s.topTracks.first().name)
        assertEquals("Discovery", s.topAlbums.first().name)
        assertEquals("house", s.topGenres.first().name)
        assertEquals(2, s.byHour[8])
        assertEquals(24, s.byHour.size); assertEquals(7, s.byWeekday.size)
        assertEquals(LocalDate.parse("2026-03-02").toEpochDay(), s.peakDay)
        assertEquals(2, s.peakDayPlays)
        assertTrue(s.skipRate > 0.16f && s.skipRate < 0.17f)
        // Skips are not counted towards an artist's top plays.
        assertEquals(2, s.topArtists.first { it.name == "Portishead" }.plays)
    }

    @Test fun emptyPeriod() {
        val s = Ledger.summarise(emptyList(), 0, 1, zone)
        assertEquals(0, s.plays); assertEquals(0, s.longestStreakDays); assertEquals(0f, s.skipRate); assertTrue(s.topArtists.isEmpty())
    }

    @Test fun streaks() {
        assertEquals(3 to 3, Ledger.streaks(listOf(10L, 11L, 12L), 12L))
        assertEquals(3 to 3, Ledger.streaks(listOf(10L, 11L, 12L), 13L))
        assertEquals(3 to 0, Ledger.streaks(listOf(10L, 11L, 12L), 14L))
        assertEquals(2 to 1, Ledger.streaks(listOf(1L, 2L, 5L), 5L))
    }

    @Test fun periodsAndExport() {
        val now = at("2026-10-14", 3)
        val (f1, t1) = Ledger.thisMonth(now, zone)
        assertEquals(at("2026-10-01", 0), f1); assertEquals(at("2026-11-01", 0), t1)
        val (f2, t2) = Ledger.lastMonth(now, zone)
        assertEquals(at("2026-09-01", 0), f2); assertEquals(at("2026-10-01", 0), t2)
        val (f3, _) = Ledger.thisYear(now, zone)
        assertEquals(at("2026-01-01", 0), f3)
        val (f4, t4) = Ledger.lastDays(now, 7, zone)
        assertEquals(at("2026-10-08", 0), f4); assertEquals(at("2026-10-15", 0), t4)
        val plays = listOf(play("a", "2026-10-02", 9, "X"))
        val json = Ledger.export(Ledger.summarise(plays, f1, t1, zone), plays)
        assertTrue(json.contains("\"format\": \"cued-ledger/1\"")); assertTrue(json.contains("Song a"))
        assertEquals("4 min", Ledger.formatHours(240_000)); assertEquals("2 h 5 min", Ledger.formatHours(125 * 60_000L))
    }
}
