package dev.cued.core

import dev.cued.core.download.Matcher
import dev.cued.core.download.Matcher.Candidate
import dev.cued.core.download.Matcher.Wanted
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatcherTest {
    @Test
    fun `prefers the studio version with matching duration`() {
        val wanted = Wanted("Around the World", listOf("Daft Punk"), 429)
        val cands = listOf(
            Candidate("a", "Daft Punk - Around the World (Live 2007)", "Daft Punk", 600),
            Candidate("b", "Around the World (Official Video)", "Daft Punk", 430),
            Candidate("c", "Around the World - Nightcore", "SomeUser", 300),
            Candidate("d", "Around The World", "Random Cover Band", 428),
        )
        val best = Matcher.best(wanted, cands)!!
        assertEquals("b", best.candidate.id)
        assertTrue(best.reasons.contains("artist matches"))
    }

    @Test
    fun `keeps remix when the request asked for one`() {
        val wanted = Wanted("One More Time (Remix)", listOf("Daft Punk"), null)
        val cands = listOf(
            Candidate("a", "One More Time", "Daft Punk", 320),
            Candidate("b", "One More Time (Remix)", "Daft Punk", 330),
        )
        assertEquals("b", Matcher.best(wanted, cands)!!.candidate.id)
    }

    @Test
    fun `rejects nonsense`() {
        val wanted = Wanted("Blue Monday", listOf("New Order"), 448)
        assertNull(Matcher.best(wanted, listOf(Candidate("x", "Unboxing my new keyboard", "TechGuy", 900))))
    }

    @Test
    fun `normalisation strips noise`() {
        assertEquals("blue monday", Matcher.normalize("Blue Monday (Official Music Video) [HD]"))
        assertTrue(Matcher.similarity("Blue Monday", "Blue Monday (Official Video)") > 0.99f)
    }
}
