package dev.cued.app.download

import android.util.Log
import dev.cued.core.share.SourceLinks
import dev.cued.core.share.SourceLinks.LinkType
import dev.cued.core.share.SourceLinks.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Turns any music link into something spotdl can download.
 *
 *  - Spotify / YouTube / YouTube Music: passed straight through.
 *  - Deezer albums and playlists: expanded track by track through Deezer's public API.
 *  - Any other platform's track or album: song.link (Odesli) finds the matching
 *    Spotify link; if it can't, the entity's title + artist becomes a search.
 *  - Playlists on Apple Music, Tidal, SoundCloud, Amazon: no open API, so they
 *    are reported as unsupported instead of failing silently.
 *  - Anything else: the page's og:title / og:description becomes a search.
 */
class LinkResolver(private val userAgent: String) {
    data class Item(val source: String, val title: String? = null, val artist: String? = null)

    sealed class Result {
        data class Direct(val source: String, val note: String? = null) : Result()
        data class Expanded(val items: List<Item>, val note: String) : Result()
        data class Unsupported(val reason: String) : Result()
    }

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun resolve(raw: String, hintQuery: String? = null): Result = withContext(Dispatchers.IO) {
        val link = SourceLinks.parse(raw) ?: return@withContext Result.Direct(raw.trim())
        dev.cued.app.util.DebugLog.d(TAG, "resolve ${link.label} ${link.url}")
        if (link.direct) return@withContext Result.Direct(link.url)
        when (link.platform) {
            Platform.DEEZER -> when (link.type) {
                LinkType.PLAYLIST, LinkType.ALBUM -> deezerExpand(link.type, link.id) ?: Result.Unsupported("Couldn't read that Deezer ${link.type.name.lowercase()}")
                LinkType.TRACK -> songlink(link.url) ?: deezerTrack(link.id) ?: fallback(link.url, hintQuery)
                else -> fallback(link.url, hintQuery)
            }
            Platform.APPLE_MUSIC, Platform.TIDAL, Platform.SOUNDCLOUD, Platform.AMAZON_MUSIC -> when (link.type) {
                LinkType.PLAYLIST -> Result.Unsupported("${link.label}s can't be read without an account. Share a Spotify, YouTube or Deezer playlist link instead, or share the tracks one by one.")
                LinkType.ARTIST -> Result.Unsupported("Artist pages aren't downloadable; share an album, track or playlist.")
                else -> songlink(link.url) ?: fallback(link.url, hintQuery)
            }
            Platform.BANDCAMP, Platform.OTHER -> fallback(link.url, hintQuery)
            else -> Result.Direct(link.url)
        }
    }

    /** song.link: any track/album URL → Spotify URL, or a title/artist search. */
    private fun songlink(url: String): Result? = runCatching {
        val body = get("https://api.song.link/v1-alpha.1/links?url=${enc(url)}&songIfSingle=true") ?: return null
        val root = json.parseToJsonElement(body).jsonObject
        val spotify = root["linksByPlatform"]?.jsonObject?.get("spotify")?.jsonObject?.get("url")?.jsonPrimitive?.content
        if (spotify != null) return Result.Direct(SourceLinks.canonical(spotify), "matched on Spotify via song.link")
        val youtube = root["linksByPlatform"]?.jsonObject?.get("youtubeMusic")?.jsonObject?.get("url")?.jsonPrimitive?.content
            ?: root["linksByPlatform"]?.jsonObject?.get("youtube")?.jsonObject?.get("url")?.jsonPrimitive?.content
        if (youtube != null) return Result.Direct(youtube, "matched on YouTube via song.link")
        val entityId = root["entityUniqueId"]?.jsonPrimitive?.content
        val entity = entityId?.let { root["entitiesByUniqueId"]?.jsonObject?.get(it)?.jsonObject }
        val title = entity?.get("title")?.jsonPrimitive?.content
        val artist = entity?.get("artistName")?.jsonPrimitive?.content
        if (!title.isNullOrBlank()) Result.Direct(listOfNotNull(artist, title).joinToString(" - "), "searching by title via song.link") else null
    }.onFailure { Log.w(TAG, "song.link: ${it.message}"); dev.cued.app.util.DebugLog.w(TAG, "song.link failed", it) }.getOrNull()

    private fun deezerTrack(id: String?): Result? {
        id ?: return null
        return runCatching {
            val o = json.parseToJsonElement(get("https://api.deezer.com/track/$id") ?: return null).jsonObject
            val title = o["title"]?.jsonPrimitive?.content ?: return null
            val artist = o["artist"]?.jsonObject?.get("name")?.jsonPrimitive?.content
            Result.Direct(listOfNotNull(artist, title).joinToString(" - "), "searching by title via Deezer")
        }.getOrNull()
    }

    private fun deezerExpand(type: LinkType, id: String?): Result? {
        id ?: return null
        return runCatching {
            val kind = if (type == LinkType.PLAYLIST) "playlist" else "album"
            val o = json.parseToJsonElement(get("https://api.deezer.com/$kind/$id") ?: return null).jsonObject
            val name = o["title"]?.jsonPrimitive?.content ?: kind
            val albumArtist = o["artist"]?.jsonObject?.get("name")?.jsonPrimitive?.content
            val items = ArrayList<Item>()
            var next: String? = "https://api.deezer.com/$kind/$id/tracks?limit=100"
            var guard = 0
            while (next != null && guard++ < 20) {
                val page = json.parseToJsonElement(get(next) ?: break).jsonObject
                for (t in page["data"]?.jsonArray.orEmpty()) {
                    val tt = t.jsonObject
                    val title = tt["title"]?.jsonPrimitive?.content ?: continue
                    val artist = tt["artist"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: albumArtist
                    items += Item(listOfNotNull(artist, title).joinToString(" - "), title, artist)
                }
                next = page["next"]?.jsonPrimitive?.content
            }
            if (items.isEmpty()) null else Result.Expanded(items, "Deezer $kind \"$name\": ${items.size} tracks")
        }.onFailure { Log.w(TAG, "deezer: ${it.message}") }.getOrNull()
    }

    /** Reads og:title / og:description from the page and searches by that; else the share-sheet prose. */
    private fun fallback(url: String, hintQuery: String?): Result {
        val og = runCatching {
            val head = getHead(url) ?: return@runCatching null
            val title = meta(head, "og:title") ?: meta(head, "twitter:title")
            val t: String? = title?.replace(Regex("\\s*[|·\\-–]\\s*(Spotify|Apple Music|Deezer|TIDAL|SoundCloud|Bandcamp|Amazon Music).*$", RegexOption.IGNORE_CASE), "")
            if (t.isNullOrBlank()) null
            else if (t.contains(", by ")) t.replace(", by ", " ")                     // Bandcamp style
            else t
        }.getOrNull()
        val q = og ?: hintQuery
        return if (q.isNullOrBlank()) Result.Unsupported("Couldn't work out what that link points to. Paste the artist and title instead.")
        else Result.Direct(q, "searching by page title")
    }

    private fun meta(html: String, prop: String): String? {
        val m = Regex("<meta[^>]+(?:property|name)=[\"']$prop[\"'][^>]+content=[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("<meta[^>]+content=[\"']([^\"']*)[\"'][^>]+(?:property|name)=[\"']$prop[\"']", RegexOption.IGNORE_CASE).find(html)
        return m?.groupValues?.get(1)?.replace("&amp;", "&")?.replace("&#39;", "'")?.replace("&quot;", "\"")?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun get(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000; conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", userAgent)
        conn.setRequestProperty("Accept", "application/json, text/html")
        return try { if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().readText() else null } finally { conn.disconnect() }
    }

    private fun getHead(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000; conn.readTimeout = 15_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) $userAgent")
        return try {
            if (conn.responseCode !in 200..299) return null
            val buf = CharArray(96 * 1024)
            val n = conn.inputStream.bufferedReader().read(buf)
            if (n > 0) String(buf, 0, n) else null
        } finally { conn.disconnect() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    companion object { private const val TAG = "LinkResolver" }
}
