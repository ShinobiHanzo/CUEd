package dev.cued.app.download.native

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * Track metadata for Spotify links.
 *
 * With your own (free) Spotify developer client id/secret it uses the Web API
 * proper: full playlists and albums, ISRC, cover art, artist genres. Without
 * keys it reads the public embed page, which carries names, artists, durations
 * and covers for tracks, albums and playlists but no genres, and may be
 * truncated for very long playlists.
 */
class SpotifyClient(private val clientId: String?, private val clientSecret: String?) {
    data class Track(
        val id: String?, val title: String, val artists: List<String>, val album: String?, val albumArtist: String?,
        val durationMs: Long?, val trackNumber: Int?, val year: String?, val coverUrl: String?, val genres: List<String>, val isrc: String?,
    )
    data class Collection(val name: String, val tracks: List<Track>)

    private val json = Json { ignoreUnknownKeys = true }
    private var token: String? = null
    private var tokenExpiresAt = 0L
    val hasKeys: Boolean get() = !clientId.isNullOrBlank() && !clientSecret.isNullOrBlank()

    fun track(id: String): Track = if (hasKeys) apiTrack(id) else embed("track", id).tracks.firstOrNull() ?: error("Couldn't read that Spotify track")
    fun album(id: String): Collection = if (hasKeys) apiAlbum(id) else embed("album", id)
    fun playlist(id: String): Collection = if (hasKeys) apiPlaylist(id) else embed("playlist", id)

    // ---- Web API --------------------------------------------------------------

    private fun auth(): String {
        val now = System.currentTimeMillis()
        token?.takeIf { now < tokenExpiresAt - 30_000 }?.let { return it }
        val conn = URL("https://accounts.spotify.com/api/token").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"; conn.doOutput = true
        conn.setRequestProperty("Authorization", "Basic " + Base64.encodeToString("$clientId:$clientSecret".toByteArray(), Base64.NO_WRAP))
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use { it.write("grant_type=client_credentials".toByteArray()) }
        try {
            check(conn.responseCode == 200) { "Spotify rejected the client id/secret (HTTP ${conn.responseCode})" }
            val o = json.parseToJsonElement(conn.inputStream.bufferedReader().readText()).jsonObject
            token = o["access_token"]!!.jsonPrimitive.content
            tokenExpiresAt = now + (o["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L) * 1000L
            return token!!
        } finally { conn.disconnect() }
    }

    private fun api(path: String): JsonObject {
        val conn = URL("https://api.spotify.com/v1/$path").openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "Bearer ${auth()}")
        try {
            check(conn.responseCode == 200) { "Spotify API HTTP ${conn.responseCode} for $path" }
            return json.parseToJsonElement(conn.inputStream.bufferedReader().readText()).jsonObject
        } finally { conn.disconnect() }
    }

    private fun apiTrack(id: String): Track {
        val t = api("tracks/$id")
        val artistIds = t["artists"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
        return trackFrom(t, albumObj = t["album"]?.jsonObject, genres = artistGenres(artistIds))
    }

    private fun apiAlbum(id: String): Collection {
        val a = api("albums/$id")
        val genres = artistGenres(a["artists"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content })
        val items = ArrayList<JsonObject>()
        var next: String? = "albums/$id/tracks?limit=50"
        while (next != null) { val p = api(next.removePrefix("https://api.spotify.com/v1/")); items += p["items"]!!.jsonArray.map { it.jsonObject }; next = p["next"]?.jsonPrimitive?.contentOrNull() }
        return Collection(a["name"]?.jsonPrimitive?.content ?: "Album", items.map { trackFrom(it, albumObj = a, genres = genres) })
    }

    private fun apiPlaylist(id: String): Collection {
        val p0 = api("playlists/$id?fields=name")
        val items = ArrayList<JsonObject>()
        var next: String? = "playlists/$id/tracks?limit=100&fields=next,items(track(id,name,artists(id,name),album(name,images,release_date),duration_ms,track_number,external_ids))"
        while (next != null) { val p = api(next.removePrefix("https://api.spotify.com/v1/")); items += p["items"]!!.jsonArray.mapNotNull { it.jsonObject["track"]?.takeIf { t -> t !is kotlinx.serialization.json.JsonNull }?.jsonObject }; next = p["next"]?.jsonPrimitive?.contentOrNull() }
        val artistIds = items.flatMap { it["artists"]?.jsonArray.orEmpty().mapNotNull { a -> a.jsonObject["id"]?.jsonPrimitive?.content } }.distinct()
        val genresByArtist = artistGenresMap(artistIds)
        return Collection(p0["name"]?.jsonPrimitive?.content ?: "Playlist", items.map { t ->
            val ids = t["artists"]?.jsonArray.orEmpty().mapNotNull { a -> a.jsonObject["id"]?.jsonPrimitive?.content }
            trackFrom(t, albumObj = t["album"]?.jsonObject, genres = ids.flatMap { genresByArtist[it].orEmpty() }.distinct())
        })
    }

    private fun artistGenres(ids: List<String>): List<String> = artistGenresMap(ids).values.flatten().distinct()

    private fun artistGenresMap(ids: List<String>): Map<String, List<String>> {
        val out = HashMap<String, List<String>>()
        for (chunk in ids.distinct().chunked(50)) {
            val r = runCatching { api("artists?ids=${chunk.joinToString(",")}") }.getOrNull() ?: continue
            for (a in r["artists"]?.jsonArray.orEmpty()) {
                val o = a.jsonObject
                out[o["id"]?.jsonPrimitive?.content ?: continue] = o["genres"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
            }
        }
        return out
    }

    private fun trackFrom(t: JsonObject, albumObj: JsonObject?, genres: List<String>): Track = Track(
        id = t["id"]?.jsonPrimitive?.contentOrNull(),
        title = t["name"]?.jsonPrimitive?.content ?: "Unknown",
        artists = t["artists"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content },
        album = albumObj?.get("name")?.jsonPrimitive?.content,
        albumArtist = albumObj?.get("artists")?.jsonArray?.firstOrNull()?.jsonObject?.get("name")?.jsonPrimitive?.content,
        durationMs = t["duration_ms"]?.jsonPrimitive?.content?.toLongOrNull(),
        trackNumber = t["track_number"]?.jsonPrimitive?.content?.toIntOrNull(),
        year = albumObj?.get("release_date")?.jsonPrimitive?.contentOrNull()?.take(4),
        coverUrl = albumObj?.get("images")?.jsonArray?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content,
        genres = genres,
        isrc = t["external_ids"]?.jsonObject?.get("isrc")?.jsonPrimitive?.contentOrNull(),
    )

    // ---- Keyless: the embed page -------------------------------------------------

    private fun embed(kind: String, id: String): Collection {
        val conn = URL("https://open.spotify.com/embed/$kind/$id").openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", NewPipeHttp.USER_AGENT)
        conn.setRequestProperty("Accept-Language", "en")
        val html = try { check(conn.responseCode == 200) { "Spotify embed HTTP ${conn.responseCode}" }; conn.inputStream.bufferedReader().readText() } finally { conn.disconnect() }
        val m = Regex("<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(html)
            ?: error("Spotify's embed page changed; add Spotify API keys in Downloads → Built-in to keep this working")
        val root = json.parseToJsonElement(m.groupValues[1])
        val entity = find(root, "entity")?.jsonObject ?: error("Couldn't read Spotify metadata")
        val name = entity["name"]?.jsonPrimitive?.contentOrNull() ?: entity["title"]?.jsonPrimitive?.contentOrNull() ?: kind
        val cover = entity["visualIdentity"]?.jsonObject?.get("image")?.jsonArray?.maxByOrNull { it.jsonObject["maxWidth"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0 }?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull()
            ?: entity["coverArt"]?.jsonObject?.get("sources")?.jsonArray?.lastOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull()
        if (kind == "track") {
            val artists = entity["artists"]?.jsonArray?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull() }
                ?: entity["subtitle"]?.jsonPrimitive?.contentOrNull()?.split(",")?.map { it.trim() }.orEmpty()
            return Collection(name, listOf(Track(id, name, artists, null, artists.firstOrNull(), entity["duration"]?.jsonPrimitive?.content?.toLongOrNull(), null, null, cover, emptyList(), null)))
        }
        val list = entity["trackList"]?.jsonArray ?: error("No track list on the embed page; add Spotify API keys for full playlists")
        val tracks = list.mapIndexed { i, el ->
            val o = el.jsonObject
            val title = o["title"]?.jsonPrimitive?.content ?: return@mapIndexed null
            val artists = o["subtitle"]?.jsonPrimitive?.contentOrNull()?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            Track(o["uri"]?.jsonPrimitive?.contentOrNull()?.substringAfterLast(':'), title, artists, if (kind == "album") name else null, null,
                o["duration"]?.jsonPrimitive?.content?.toLongOrNull(), if (kind == "album") i + 1 else null, null, cover, emptyList(), null)
        }.filterNotNull()
        return Collection(name, tracks)
    }

    private fun find(el: JsonElement, key: String): JsonElement? {
        if (el is JsonObject) { el[key]?.let { return it }; for (v in el.values) find(v, key)?.let { return it } }
        if (el is kotlinx.serialization.json.JsonArray) for (v in el) find(v, key)?.let { return it }
        return null
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? = if (this is kotlinx.serialization.json.JsonNull) null else content
}
