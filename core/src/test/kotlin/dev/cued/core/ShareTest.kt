package dev.cued.core

import dev.cued.core.share.SharePayload
import dev.cued.core.share.SourceLinks
import dev.cued.core.share.SourceLinks.LinkType
import dev.cued.core.share.SourceLinks.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun `parses platforms and types`() {
        fun p(s: String) = SourceLinks.parse(s)!!
        assertEquals(Platform.SPOTIFY to LinkType.PLAYLIST, p("Check this https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc").let { it.platform to it.type })
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", p("https://open.spotify.com/intl-de/playlist/37i9dQZF1DXcBWIGoYBM5M").id)
        assertEquals(Platform.APPLE_MUSIC to LinkType.TRACK, p("https://music.apple.com/au/album/song-name/123456?i=7891011").let { it.platform to it.type })
        assertEquals(Platform.APPLE_MUSIC to LinkType.ALBUM, p("https://music.apple.com/au/album/album-name/123456").let { it.platform to it.type })
        assertEquals(Platform.APPLE_MUSIC to LinkType.PLAYLIST, p("https://music.apple.com/au/playlist/chill/pl.u-abc").let { it.platform to it.type })
        assertEquals(Platform.DEEZER to LinkType.PLAYLIST, p("https://www.deezer.com/en/playlist/1234567").let { it.platform to it.type })
        assertEquals("1234567", p("https://www.deezer.com/en/playlist/1234567").id)
        assertEquals(Platform.TIDAL to LinkType.ALBUM, p("https://tidal.com/browse/album/98765").let { it.platform to it.type })
        assertEquals(Platform.SOUNDCLOUD to LinkType.PLAYLIST, p("https://soundcloud.com/someone/sets/mixtape").let { it.platform to it.type })
        assertEquals(Platform.SOUNDCLOUD to LinkType.TRACK, p("https://soundcloud.com/someone/track-name").let { it.platform to it.type })
        assertEquals(Platform.BANDCAMP to LinkType.ALBUM, p("https://artist.bandcamp.com/album/the-record").let { it.platform to it.type })
        assertEquals(Platform.YOUTUBE to LinkType.PLAYLIST, p("https://www.youtube.com/playlist?list=PL123").let { it.platform to it.type })
        assertEquals(Platform.YOUTUBE_MUSIC to LinkType.TRACK, p("https://music.youtube.com/watch?v=abc").let { it.platform to it.type })
        assertTrue(p("https://open.spotify.com/track/1").direct)
        assertTrue(!p("https://music.apple.com/au/album/x/1?i=2").direct)
        assertNull(SourceLinks.parse("just some words"))
    }

    @Test
    fun `share prose becomes a query`() {
        assertEquals("Blue Monday New Order", SourceLinks.shareTextToQuery("Listen to Blue Monday by New Order on Apple Music https://music.apple.com/x"))
        assertEquals("Around the World Daft Punk", SourceLinks.shareTextToQuery("Around the World by Daft Punk"))
        assertNull(SourceLinks.shareTextToQuery("https://only.a/link"))
    }
}
