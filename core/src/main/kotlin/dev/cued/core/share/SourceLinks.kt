package dev.cued.core.share

/** Recognises the kinds of URLs spotdl can resolve. */
object SourceLinks {
    enum class Kind { SPOTIFY_TRACK, SPOTIFY_ALBUM, SPOTIFY_PLAYLIST, YOUTUBE, SEARCH, UNKNOWN }

    fun classify(input: String): Kind {
        val s = input.trim()
        return when {
            s.contains("open.spotify.com/track/") || s.startsWith("spotify:track:") -> Kind.SPOTIFY_TRACK
            s.contains("open.spotify.com/album/") || s.startsWith("spotify:album:") -> Kind.SPOTIFY_ALBUM
            s.contains("open.spotify.com/playlist/") || s.startsWith("spotify:playlist:") -> Kind.SPOTIFY_PLAYLIST
            s.contains("youtube.com/watch") || s.contains("youtu.be/") || s.contains("music.youtube.com") -> Kind.YOUTUBE
            s.startsWith("http://") || s.startsWith("https://") -> Kind.UNKNOWN
            s.isNotBlank() -> Kind.SEARCH
            else -> Kind.UNKNOWN
        }
    }

    /** Strips tracking query params from a Spotify link so it can be used as a stable key. */
    fun canonical(input: String): String {
        val s = input.trim()
        val q = s.indexOf('?')
        return if (q > 0 && s.contains("open.spotify.com")) s.substring(0, q) else s
    }
}
