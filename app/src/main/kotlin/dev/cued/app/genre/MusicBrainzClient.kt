package dev.cued.app.genre

import dev.cued.core.genre.GenreNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * MusicBrainz lookup for tracks whose files carry no genre at all. Open data,
 * no key, one request per second by their rules. Genres come from the
 * community's votes on the recording, falling back to the artist's genres.
 * Off unless enabled in Settings → Genres.
 */
class MusicBrainzClient(private val userAgent: String) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable private data class Named(val name: String, val count: Int = 0)
    @Serializable private data class ArtistRef(val id: String, val name: String = "")
    @Serializable private data class Credit(val artist: ArtistRef)
    @Serializable private data class Recording(
        val id: String, val score: Int = 0, val title: String = "",
        @SerialName("artist-credit") val artistCredit: List<Credit> = emptyList(),
        val genres: List<Named> = emptyList(), val tags: List<Named> = emptyList(),
    )
    @Serializable private data class SearchResult(val recordings: List<Recording> = emptyList())
    @Serializable private data class Artist(val genres: List<Named> = emptyList(), val tags: List<Named> = emptyList())

    /** Normalised genre labels for the track, best first; empty if MusicBrainz has nothing. */
    suspend fun genresFor(artist: String, title: String): List<String> = withContext(Dispatchers.IO) {
        val q = "recording:\"${esc(title)}\" AND artist:\"${esc(artist)}\""
        val search = get("$BASE/recording?query=${enc(q)}&fmt=json&limit=3")?.let { runCatching { json.decodeFromString<SearchResult>(it) }.getOrNull() }
        val best = search?.recordings?.filter { it.score >= 80 }?.maxByOrNull { it.score } ?: return@withContext emptyList()
        delay(RATE_MS)
        val rec = get("$BASE/recording/${best.id}?inc=genres+tags&fmt=json")?.let { runCatching { json.decodeFromString<Recording>(it) }.getOrNull() }
        val fromRecording = pick(rec?.genres, rec?.tags)
        if (fromRecording.isNotEmpty()) return@withContext fromRecording
        val artistId = best.artistCredit.firstOrNull()?.artist?.id ?: return@withContext emptyList()
        delay(RATE_MS)
        val art = get("$BASE/artist/$artistId?inc=genres+tags&fmt=json")?.let { runCatching { json.decodeFromString<Artist>(it) }.getOrNull() }
        pick(art?.genres, art?.tags)
    }

    private fun pick(genres: List<Named>?, tags: List<Named>?): List<String> {
        val src = genres?.takeIf { it.isNotEmpty() } ?: tags.orEmpty()
        return src.filter { it.count >= 1 }.sortedByDescending { it.count }.take(3)
            .flatMap { GenreNormalizer.normalize(it.name) }.distinct()
    }

    private fun get(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000; conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", userAgent)
        conn.setRequestProperty("Accept", "application/json")
        return try {
            when (conn.responseCode) {
                in 200..299 -> conn.inputStream.bufferedReader().readText()
                404 -> null
                503 -> { Thread.sleep(2_000); null } // rate limited: back off, caller moves on
                else -> null
            }
        } finally { conn.disconnect() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun esc(s: String) = s.replace("\"", "\\\"")

    companion object {
        const val BASE = "https://musicbrainz.org/ws/2"
        const val RATE_MS = 1_100L
    }
}
