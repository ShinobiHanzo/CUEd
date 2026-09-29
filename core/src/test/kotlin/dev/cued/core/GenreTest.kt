package dev.cued.core

import dev.cued.core.genre.GenreNormalizer
import kotlin.test.Test
import kotlin.test.assertEquals

class GenreTest {
    @Test
    fun `splits and canonicalises`() {
        assertEquals(listOf("hip hop", "rap"), GenreNormalizer.normalize("Hip-Hop; Rap"))
        assertEquals(listOf("drum and bass"), GenreNormalizer.normalize("DnB"))
        assertEquals(listOf("drum and bass", "jungle"), GenreNormalizer.normalize("Drum & Bass / Jungle"))
        assertEquals(listOf("r&b", "soul"), GenreNormalizer.normalize("RnB, Soul"))
        assertEquals(listOf("lo-fi"), GenreNormalizer.normalize("Lofi Hip Hop"))
        assertEquals(listOf("dance pop", "pop"), GenreNormalizer.normalize("dance pop; pop"))
    }

    @Test
    fun `id3v1 numeric codes`() {
        assertEquals(listOf("rock"), GenreNormalizer.normalize("(17)"))
        assertEquals(listOf("rock"), GenreNormalizer.normalize("17"))
        assertEquals(listOf("rock", "hip hop"), GenreNormalizer.normalize("(17)(7)"))
        assertEquals(listOf("drum and bass"), GenreNormalizer.normalize("(127)"))
        assertEquals(listOf("rock", "indie"), GenreNormalizer.normalize("(17)Indie"))
    }

    @Test
    fun `junk is dropped`() {
        assertEquals(emptyList(), GenreNormalizer.normalize("Unknown"))
        assertEquals(emptyList(), GenreNormalizer.normalize("  "))
        assertEquals(emptyList(), GenreNormalizer.normalize(null))
        assertEquals(listOf("house"), GenreNormalizer.normalize("Other; House"))
    }
}
