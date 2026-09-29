package dev.cued.core.share

/**
 * Recognises music links from the share sheet: which platform, and whether
 * it's a track, album, playlist or artist. spotdl only takes Spotify and
 * YouTube directly; everything else is resolved by the app before queueing.
 */
object SourceLinks {
    enum class Platform { SPOTIFY, YOUTUBE, YOUTUBE_MUSIC, APPLE_MUSIC, DEEZER, TIDAL, SOUNDCLOUD, BANDCAMP, AMAZON_MUSIC, OTHER }
    enum class LinkType { TRACK, ALBUM, PLAYLIST, ARTIST, UNKNOWN }

    data class ParsedLink(val url: String, val platform: Platform, val type: LinkType, val id: String? = null) {
        /** True when spotdl can take the URL as-is. */
        val direct: Boolean get() = platform == Platform.SPOTIFY || platform == Platform.YOUTUBE || platform == Platform.YOUTUBE_MUSIC
        val label: String get() = platform.name.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) } + " " + type.name.lowercase()
    }

    /** Backwards-compatible coarse classification. */
    enum class Kind { SPOTIFY_TRACK, SPOTIFY_ALBUM, SPOTIFY_PLAYLIST, YOUTUBE, SEARCH, UNKNOWN }

    fun classify(input: String): Kind {
        val p = parse(input)
        return when {
            p == null -> if (input.isBlank() || input.startsWith("http")) Kind.UNKNOWN else Kind.SEARCH
            p.platform == Platform.SPOTIFY && p.type == LinkType.TRACK -> Kind.SPOTIFY_TRACK
            p.platform == Platform.SPOTIFY && p.type == LinkType.ALBUM -> Kind.SPOTIFY_ALBUM
            p.platform == Platform.SPOTIFY && p.type == LinkType.PLAYLIST -> Kind.SPOTIFY_PLAYLIST
            p.platform == Platform.YOUTUBE || p.platform == Platform.YOUTUBE_MUSIC -> Kind.YOUTUBE
            else -> Kind.UNKNOWN
        }
    }

    private val urlPattern = Regex("(https?://[^\\s<>\"']+)")

    /** First URL inside arbitrary share text, or null. */
    fun extractUrl(text: String): String? = urlPattern.find(text)?.value?.trimEnd('.', ',', ')', ']')
        ?: Regex("spotify:(?:track|album|playlist|artist):[A-Za-z0-9]+").find(text)?.value

    /** Parses a URL (or share text containing one). Null if there is no link. */
    fun parse(text: String): ParsedLink? {
        val url = extractUrl(text.trim()) ?: return null
        val u = url.lowercase()
        fun seg(after: String): String? = Regex("$after/([A-Za-z0-9_\\-.]+)").find(url)?.groupValues?.get(1)
        return when {
            u.startsWith("spotify:") -> {
                val parts = url.split(':')
                ParsedLink(url, Platform.SPOTIFY, typeOf(parts.getOrNull(1)), parts.getOrNull(2))
            }
            u.contains("open.spotify.com/") || u.contains("spotify.link/") -> {
                val t = Regex("open\\.spotify\\.com/(?:intl-[a-z]+/)?(track|album|playlist|artist)/").find(u)?.groupValues?.get(1)
                ParsedLink(canonical(url), Platform.SPOTIFY, typeOf(t), seg("(?:track|album|playlist|artist)"))
            }
            u.contains("music.youtube.com") -> ParsedLink(url, Platform.YOUTUBE_MUSIC, if (u.contains("list=")) LinkType.PLAYLIST else if (u.contains("watch")) LinkType.TRACK else if (u.contains("/channel/")) LinkType.ARTIST else LinkType.UNKNOWN)
            u.contains("youtube.com") || u.contains("youtu.be/") -> ParsedLink(url, Platform.YOUTUBE, if (u.contains("list=")) LinkType.PLAYLIST else if (u.contains("watch") || u.contains("youtu.be/") || u.contains("/shorts/")) LinkType.TRACK else LinkType.UNKNOWN)
            u.contains("music.apple.com") -> ParsedLink(
                url, Platform.APPLE_MUSIC,
                when {
                    u.contains("/playlist/") -> LinkType.PLAYLIST
                    u.contains("/album/") && u.contains("?i=") -> LinkType.TRACK
                    u.contains("/song/") -> LinkType.TRACK
                    u.contains("/album/") -> LinkType.ALBUM
                    u.contains("/artist/") -> LinkType.ARTIST
                    else -> LinkType.UNKNOWN
                },
            )
            u.contains("deezer.com") || u.contains("deezer.page.link") -> {
                val t = Regex("deezer\\.com/(?:[a-z]{2}/)?(track|album|playlist|artist)/(\\d+)").find(u)
                ParsedLink(url, Platform.DEEZER, typeOf(t?.groupValues?.get(1)), t?.groupValues?.get(2))
            }
            u.contains("tidal.com") -> {
                val t = Regex("tidal\\.com/(?:browse/)?(track|album|playlist|artist)/([A-Za-z0-9\\-]+)").find(u)
                ParsedLink(url, Platform.TIDAL, typeOf(t?.groupValues?.get(1)), t?.groupValues?.get(2))
            }
            u.contains("soundcloud.com") -> ParsedLink(url, Platform.SOUNDCLOUD, if (u.contains("/sets/")) LinkType.PLAYLIST else if (Regex("soundcloud\\.com/[^/]+/[^/?]+").containsMatchIn(u)) LinkType.TRACK else LinkType.ARTIST)
            u.contains("bandcamp.com") -> ParsedLink(url, Platform.BANDCAMP, if (u.contains("/track/")) LinkType.TRACK else if (u.contains("/album/")) LinkType.ALBUM else LinkType.ARTIST)
            u.contains("music.amazon.") || u.contains("amazon.com/music") -> ParsedLink(url, Platform.AMAZON_MUSIC, if (u.contains("/playlists/") || u.contains("/user-playlists/")) LinkType.PLAYLIST else if (u.contains("trackasin=")) LinkType.TRACK else if (u.contains("/albums/")) LinkType.ALBUM else LinkType.UNKNOWN)
            else -> ParsedLink(url, Platform.OTHER, LinkType.UNKNOWN)
        }
    }

    private fun typeOf(s: String?): LinkType = when (s) {
        "track" -> LinkType.TRACK; "album" -> LinkType.ALBUM; "playlist" -> LinkType.PLAYLIST; "artist" -> LinkType.ARTIST; else -> LinkType.UNKNOWN
    }

    /** Strips tracking query params from a Spotify link so it can be used as a stable key. */
    fun canonical(input: String): String {
        val s = input.trim()
        val q = s.indexOf('?')
        return if (q > 0 && s.contains("open.spotify.com")) s.substring(0, q) else s
    }

    /**
     * Share sheets often send "Listen to Song by Artist on Apple Music" or
     * "Song - Artist" plus a link. Turns that prose into a spotdl search query.
     */
    fun shareTextToQuery(text: String): String? {
        var t = text.replace(urlPattern, " ").replace(Regex("\\s+"), " ").trim()
        if (t.isBlank()) return null
        t = t.replace(Regex("^(check out|listen to|i'm listening to|now playing|play)\\s+", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\s+(on|via)\\s+(apple music|spotify|deezer|tidal|soundcloud|youtube music|youtube|amazon music|bandcamp)\\.?$", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\s+by\\s+", RegexOption.IGNORE_CASE), " ").trim(' ', '"', '“', '”', '-', '–')
        return t.takeIf { it.length >= 3 }
    }
}
