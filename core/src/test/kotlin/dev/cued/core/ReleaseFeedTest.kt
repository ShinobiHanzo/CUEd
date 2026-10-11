package dev.cued.core

import dev.cued.core.feed.ReleaseFeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseFeedTest {
    @Test fun partialDatesParseToTheFirstDay() {
        assertEquals(1715904000000L, ReleaseFeed.parseDate("2024-05-17"))
        assertEquals(ReleaseFeed.parseDate("2024-05-01"), ReleaseFeed.parseDate("2024-05"))
        assertEquals(ReleaseFeed.parseDate("2024-01-01"), ReleaseFeed.parseDate("2024"))
        assertNull(ReleaseFeed.parseDate(""))
        assertNull(ReleaseFeed.parseDate(null))
        assertNull(ReleaseFeed.parseDate("????"))
        assertEquals("17 May 2024", ReleaseFeed.formatDate("2024-05-17"))
        assertEquals("May 2024", ReleaseFeed.formatDate("2024-05"))
        assertEquals("2024", ReleaseFeed.formatDate("2024"))
    }

    @Test fun recentWindowIncludesAnnouncedReleases() {
        val now = ReleaseFeed.parseDate("2026-10-11")!!
        assertTrue(ReleaseFeed.isRecent(ReleaseFeed.parseDate("2026-09-01")!!, now))
        assertTrue(ReleaseFeed.isRecent(ReleaseFeed.parseDate("2026-10-20")!!, now))
        assertFalse(ReleaseFeed.isRecent(ReleaseFeed.parseDate("2026-12-01")!!, now))
        assertFalse(ReleaseFeed.isRecent(ReleaseFeed.parseDate("2025-01-01")!!, now))
        assertFalse(ReleaseFeed.isRecent(0L, now))
    }

    @Test fun typesFilterCompilationsAndLiveSets() {
        assertEquals("Album", ReleaseFeed.typeLabel("Album", emptyList()))
        assertEquals("EP", ReleaseFeed.typeLabel("EP", emptyList()))
        assertEquals("Single", ReleaseFeed.typeLabel("Single", emptyList()))
        assertNull(ReleaseFeed.typeLabel("Album", listOf("Compilation")))
        assertNull(ReleaseFeed.typeLabel("Album", listOf("Live")))
        assertNull(ReleaseFeed.typeLabel("Other", emptyList()))
        assertNull(ReleaseFeed.typeLabel(null, emptyList()))
    }

    @Test fun listenedArtistsRankRecentPlaysFirstAndSkipPlaceholders() {
        val ranked = ReleaseFeed.rankListened(listOf(
            ReleaseFeed.ArtistPlays("Unknown artist", 50, 50, 50),
            ReleaseFeed.ArtistPlays("Various Artists", 50, 50, 50),
            ReleaseFeed.ArtistPlays("Daft Punk", 10, 0, 30),
            ReleaseFeed.ArtistPlays("Bonobo", 2, 5, 4),
            ReleaseFeed.ArtistPlays("Never Played", 0, 0, 40),
            ReleaseFeed.ArtistPlays("daft punk", 1, 1, 1),
        ))
        assertEquals(listOf("Bonobo", "Daft Punk"), ranked)
    }

    @Test fun artistPickPrefersExactNamesAndNeedsCertaintyOtherwise() {
        val c = listOf(
            ReleaseFeed.ArtistCandidate("a", "Daft Punk", 100),
            ReleaseFeed.ArtistCandidate("b", "Daft Punk Tribute", 90),
        )
        assertEquals("a", ReleaseFeed.pickArtist("daft punk", c)?.id)
        assertEquals("a", ReleaseFeed.pickArtist("DAFT  PUNK", c)?.id)
        assertNull(ReleaseFeed.pickArtist("Daft", listOf(ReleaseFeed.ArtistCandidate("b", "Daft Punk", 90))))
        assertEquals("b", ReleaseFeed.pickArtist("Daft", listOf(ReleaseFeed.ArtistCandidate("b", "Daft Punk", 98)))?.id)
    }

    @Test fun sameTrackToleratesRemasterTagsAndFeaturedArtists() {
        assertTrue(ReleaseFeed.sameTrack("Around the World", "Daft Punk", "Around the World (Remastered)", "Daft Punk"))
        assertTrue(ReleaseFeed.sameTrack("Harder Better Faster Stronger", "Daft Punk", "Harder, Better, Faster, Stronger", "Daft Punk feat. Pharrell"))
        assertFalse(ReleaseFeed.sameTrack("Around the World", "Daft Punk", "One More Time", "Daft Punk"))
        assertFalse(ReleaseFeed.sameTrack("Around the World", "Daft Punk", "Around the World", "ATC"))
    }
}
