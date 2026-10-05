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
    @Serializable private data class RgRef(val id: String = "", @SerialName("primary-type") val primaryType: String? = null, @SerialName("secondary-types") val secondaryTypes: List<String> = emptyList())
    @Serializable private data class TrackRef(val number: String? = null, val title: String = "")
    @Serializable private data class Medium(val format: String? = null, @SerialName("track-count") val trackCount: Int = 0, val track: List<TrackRef> = emptyList())
    @Serializable private data class ReleaseRef(
        val id: String, val title: String = "", val status: String? = null, val date: String? = null, val country: String? = null,
        @SerialName("release-group") val releaseGroup: RgRef? = null, val media: List<Medium> = emptyList(),
        @SerialName("artist-credit") val artistCredit: List<Credit> = emptyList(),
    )
    @Serializable private data class RecordingWithReleases(
        val id: String, val score: Int = 0, val title: String = "",
        @SerialName("artist-credit") val artistCredit: List<Credit> = emptyList(),
        val releases: List<ReleaseRef> = emptyList(),
    )
    @Serializable private data class SearchWithReleases(val recordings: List<RecordingWithReleases> = emptyList())

    /** What a recording lookup gives back: enough to tag a file and find its cover. */
    data class Release(
        val title: String, val artist: String, val album: String?, val albumArtist: String?, val year: Int, val trackNo: Int,
        val releaseId: String?, val releaseGroupId: String?, val score: Int,
    ) {
        /** Cover Art Archive URLs, most specific first; each may 404. */
        val coverUrls: List<String> get() = listOfNotNull(
            releaseId?.let { "https://coverartarchive.org/release/$it/front-500" },
            releaseGroupId?.let { "https://coverartarchive.org/release-group/$it/front-500" },
        )
    }

    /**
     * Best official album release for a recording, so a YouTube download can
     * carry a real album, year and track number instead of the folder name.
     */
    suspend fun lookup(artist: String, title: String): Release? = withContext(Dispatchers.IO) {
        val q = "recording:\"${esc(title)}\" AND artist:\"${esc(artist)}\""
        val search = get("$BASE/recording?query=${enc(q)}&fmt=json&limit=5")?.let { runCatching { json.decodeFromString<SearchWithReleases>(it) }.getOrNull() } ?: return@withContext null
        val rec = search.recordings.filter { it.score >= 85 }.maxByOrNull { it.score } ?: return@withContext null
        val credited = rec.artistCredit.joinToString(", ") { it.artist.name }.ifBlank { artist }
        // Prefer official studio albums, then EPs and singles; earliest date wins among equals.
        fun rank(r: ReleaseRef): Int {
            val t = r.releaseGroup?.primaryType?.lowercase()
            val secondary = r.releaseGroup?.secondaryTypes?.map { it.lowercase() }.orEmpty()
            var s = when (t) { "album" -> 0; "ep" -> 2; "single" -> 3; else -> 5 }
            if (secondary.any { it in setOf("compilation", "live", "remix", "soundtrack", "dj-mix") }) s += 4
            if (r.status?.equals("Official", true) != true) s += 3
            return s
        }
        val rel = rec.releases.sortedWith(compareBy<ReleaseRef> { rank(it) }.thenBy { it.date ?: "9999" }).firstOrNull()
        val year = rel?.date?.take(4)?.toIntOrNull() ?: 0
        val trackNo = rel?.media?.firstOrNull()?.track?.firstOrNull()?.number?.toIntOrNull() ?: 0
        Release(
            title = rec.title.ifBlank { title }, artist = credited, album = rel?.title, albumArtist = rel?.artistCredit?.joinToString(", ") { it.artist.name }?.ifBlank { null } ?: credited,
            year = year, trackNo = trackNo, releaseId = rel?.id, releaseGroupId = rel?.releaseGroup?.id, score = rec.score,
        )
    }
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
