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
    fun `karaoke never beats the real recording even when the video runs long`() {
        // The official video has an intro, so its length is 60 s off; the karaoke upload matches the album length exactly.
        val wanted = Wanted("Blinding Lights", listOf("The Weeknd"), 200)
        val cands = listOf(
            Candidate("k", "The Weeknd - Blinding Lights (Karaoke Version)", "Sing King Karaoke", 201),
            Candidate("i", "Blinding Lights (Instrumental)", "Random Uploads", 200),
            Candidate("v", "The Weeknd - Blinding Lights (Official Video)", "TheWeekndVEVO", 263),
        )
        val best = Matcher.best(wanted, cands)!!
        assertEquals("v", best.candidate.id)
        val ranked = Matcher.rank(wanted, cands)
        assertTrue(ranked.first { it.candidate.id == "k" }.otherRecording)
        assertTrue(ranked.first { it.candidate.id == "i" }.otherRecording)
    }

    @Test
    fun `only re-recordings means no match at all`() {
        val wanted = Wanted("Blinding Lights", listOf("The Weeknd"), 200)
        val cands = listOf(
            Candidate("k", "Blinding Lights (Karaoke Version) - The Weeknd", "Sing King Karaoke", 201),
            Candidate("c", "Blinding Lights - The Weeknd (Cover)", "Some Singer", 199),
            Candidate("s", "The Weeknd - Blinding Lights", "Stingray Karaoke", 200), // the channel gives it away
        )
        assertNull(Matcher.best(wanted, cands))
        assertTrue(cands.all { Matcher.isOtherRecording(wanted, it) })
    }

    @Test
    fun `karaoke is fine when that is what was asked for`() {
        val wanted = Wanted("Blinding Lights karaoke", listOf("The Weeknd"), null)
        val cands = listOf(Candidate("k", "The Weeknd - Blinding Lights (Karaoke Version)", "Sing King Karaoke", 201))
        assertEquals("k", Matcher.best(wanted, cands)!!.candidate.id)
    }

    @Test
    fun `the Topic channel wins a tie over a lyric video`() {
        val wanted = Wanted("Around the World", listOf("Daft Punk"), 429)
        val cands = listOf(
            Candidate("l", "Daft Punk - Around the World (Lyrics)", "LyricsHub", 429),
            Candidate("t", "Around the World", "Daft Punk - Topic", 429),
        )
        assertEquals("t", Matcher.best(wanted, cands)!!.candidate.id)
    }

    @Test
    fun `normalisation strips noise`() {
        assertEquals("blue monday", Matcher.normalize("Blue Monday (Official Music Video) [HD]"))
        assertTrue(Matcher.similarity("Blue Monday", "Blue Monday (Official Video)") > 0.99f)
    }
}
