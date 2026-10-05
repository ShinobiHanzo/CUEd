package dev.cued.core

import dev.cued.core.download.TitleClean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TitleCleanTest {
    @Test fun stripsNoise() {
        assertEquals("How You Remind Me", TitleClean.clean("How You Remind Me (Official Video) [HD]"))
        assertEquals("Get Lucky feat. Pharrell Williams", TitleClean.clean("Get Lucky (feat. Pharrell Williams) (Official Audio)"))
        assertEquals("Around the World", TitleClean.clean("Around the World | Official Lyric Video"))
        assertEquals("Song", TitleClean.clean("Song (Remastered 2011)"))
    }

    @Test fun splitsArtistDashTitle() {
        val s = TitleClean.split("Nickelback - How You Remind Me (Official Video)")
        assertEquals("How You Remind Me", s.title); assertEquals("Nickelback", s.artist)
        val t = TitleClean.split("How You Remind Me")
        assertEquals("How You Remind Me", t.title); assertNull(t.artist)
    }

    @Test fun uploaderToArtist() {
        assertEquals("Nickelback", TitleClean.artistFromUploader("Nickelback - Topic"))
        assertEquals("Nickelback", TitleClean.artistFromUploader("NickelbackVEVO"))
        assertEquals("Daft Punk", TitleClean.artistFromUploader("Daft Punk"))
        assertNull(TitleClean.artistFromUploader(""))
    }
}
