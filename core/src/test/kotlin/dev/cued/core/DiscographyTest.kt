package dev.cued.core

import dev.cued.core.library.Discography
import dev.cued.core.library.Discography.Fields
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscographyTest {
    private data class T(val id: Int, val f: Fields)
    private fun t(id: Int, title: String, artist: String, album: String, no: Int = 0, year: Int = 0, albumArtist: String? = null, disc: Int = 0) =
        T(id, Fields(title, artist, albumArtist, album, no, disc, year, 200_000L))

    private val lib = listOf(
        t(1, "Around the World", "Daft Punk", "Homework", 7, 1997),
        t(2, "Da Funk", "Daft Punk", "Homework", 2, 1997),
        t(3, "Revolution 909", "Daft Punk", "Homework", 4, 1997),
        t(4, "Alive", "Daft Punk", "Homework", 16, 1997),
        t(5, "Get Lucky", "Daft Punk feat. Pharrell Williams", "Random Access Memories", 8, 2013),
        t(6, "Giorgio by Moroder", "Daft Punk", "Random Access Memories", 3, 2013),
        t(7, "Instant Crush", "Daft Punk feat. Julian Casablancas", "Random Access Memories", 5, 2013),
        t(8, "Contact", "Daft Punk", "Random Access Memories", 13, 2013),
        t(9, "Happy", "Pharrell Williams", "G I R L", 4, 2014),
        t(10, "Lose Yourself to Dance", "Daft Punk", "Lose Yourself to Dance", 1, 2013),
        t(11, "Loose demo", "Daft Punk", ""),
        t(12, "Various cut", "Someone", "Big Compilation", 1, 2020, albumArtist = "Various Artists"),
        t(13, "Another cut", "The Other Band", "Big Compilation", 2, 2020, albumArtist = "Various Artists"),
    )
    private val index = Discography.build(lib) { it.f }

    @Test fun primaryArtistStripsFeatures() {
        assertEquals("Daft Punk", Discography.primaryArtist("Daft Punk feat. Pharrell Williams"))
        assertEquals("Daft Punk", Discography.primaryArtist("Daft Punk ft Pharrell"))
        assertEquals("Simon & Garfunkel", Discography.primaryArtist("Simon & Garfunkel"))
    }

    @Test fun creditedSplitsEveryName() {
        assertEquals(listOf("Daft Punk", "Pharrell Williams", "Nile Rodgers"), Discography.credited("Daft Punk feat. Pharrell Williams & Nile Rodgers"))
    }

    @Test fun albumsGroupAcrossFeaturedTracks() {
        val dp = assertNotNull(index.artist("daft punk"))
        assertEquals(listOf("Random Access Memories", "Homework"), dp.albums.map { it.name })
        val ram = index.album("Daft Punk", "Random Access Memories")!!
        assertEquals(2013, ram.year)
        assertEquals(listOf(6, 7, 5, 8), ram.tracks.map { it.id }) // by track number
        assertEquals(listOf("Lose Yourself to Dance"), dp.singles.map { it.name })
        assertEquals(listOf(11), dp.loose.map { it.id })
    }

    @Test fun featuredArtistAppearsOn() {
        val ph = assertNotNull(index.artist("Pharrell Williams"))
        assertEquals(listOf(5), ph.appearsOn.map { it.id })
        assertEquals(1, ph.singles.size) // G I R L has one track here, so it's a single
        assertNull(index.artist("Julian Casablancas")?.albums?.firstOrNull())
        assertEquals(listOf(7), index.artist("Julian Casablancas")!!.appearsOn.map { it.id })
    }

    @Test fun compilationsBelongToAlbumArtist() {
        val va = assertNotNull(index.artist("Various Artists"))
        assertEquals(1, va.singles.size + va.albums.size)
        assertEquals(listOf(12), index.artist("Someone")!!.appearsOn.map { it.id })
    }

    @Test fun sortingIgnoresThe() {
        val names = index.artists.map { it.name }
        assertTrue(names.indexOf("The Other Band") < names.indexOf("Pharrell Williams"))
        assertEquals('O', Discography.indexLetter("The Other Band"))
        assertEquals('#', Discography.indexLetter("2Pac"))
    }
}
