package dev.cued.core

import dev.cued.core.share.SharePayload
import dev.cued.core.share.SourceLinks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShareTest {
    @Test
    fun `payload round trips`() {
        val p = SharePayload(
            title = "Blue & Gold / Remix", artist = "Ünïcode Artist",
            link = "https://open.spotify.com/track/abc?si=123",
            fileUrl = "http://192.168.1.5:8765/track/42",
            apkUrl = "http://192.168.1.5:8765/apk",
            genres = listOf("house", "deep house"), bpm = 124.5f,
        )
        val s = p.encode()
        assert(s.startsWith("cued://share?"))
        assertEquals(p, SharePayload.decode(s))
    }

    @Test
    fun `non payloads are rejected`() {
        assertNull(SharePayload.decode("https://example.com"))
        assertNull(SharePayload.decode("cued://share?a=only"))
    }

    @Test
    fun `source link classification`() {
        assertEquals(SourceLinks.Kind.SPOTIFY_TRACK, SourceLinks.classify("https://open.spotify.com/track/1?si=x"))
        assertEquals(SourceLinks.Kind.SPOTIFY_PLAYLIST, SourceLinks.classify("spotify:playlist:abc"))
        assertEquals(SourceLinks.Kind.YOUTUBE, SourceLinks.classify("https://youtu.be/abc"))
        assertEquals(SourceLinks.Kind.SEARCH, SourceLinks.classify("daft punk around the world"))
        assertEquals("https://open.spotify.com/track/1", SourceLinks.canonical("https://open.spotify.com/track/1?si=x"))
    }
}
