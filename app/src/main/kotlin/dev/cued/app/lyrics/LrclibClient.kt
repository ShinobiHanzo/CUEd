package dev.cued.app.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/**
 * Minimal client for lrclib.net: an open, key-less lyrics database with
 * plain and LRC-synced text. This is the only host CUEd ever talks to
 * outside your LAN, and only when the lyrics setting allows it.
 */
class LrclibClient(private val userAgent: String) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Hit(
        val id: Long = 0,
        val trackName: String? = null,
        val artistName: String? = null,
        val albumName: String? = null,
        val duration: Double? = null,
        val instrumental: Boolean = false,
        val plainLyrics: String? = null,
        val syncedLyrics: String? = null,
    )

    /** Exact lookup first, then search; picks the closest duration. Null when nothing fits. */
    suspend fun lookup(artist: String, title: String, album: String?, durationSec: Int?): Hit? = withContext(Dispatchers.IO) {
        val q = buildString {
            append("artist_name=").append(enc(artist)).append("&track_name=").append(enc(title))
            if (!album.isNullOrBlank()) append("&album_name=").append(enc(album))
            if (durationSec != null && durationSec > 0) append("&duration=").append(durationSec)
        }
        get("$BASE/api/get?$q")?.let { body -> runCatching { json.decodeFromString<Hit>(body) }.getOrNull() }?.let { if (it.hasText()) return@withContext it }
        val results = get("$BASE/api/search?track_name=${enc(title)}&artist_name=${enc(artist)}")
            ?.let { body -> runCatching { json.decodeFromString<List<Hit>>(body) }.getOrNull() }.orEmpty()
            .filter { it.hasText() }
        if (results.isEmpty()) return@withContext null
        if (durationSec == null || durationSec <= 0) return@withContext results.first()
        results.minByOrNull { abs((it.duration ?: 0.0) - durationSec) }?.takeIf { abs((it.duration ?: 0.0) - durationSec) <= 15.0 } ?: results.first()
    }

    private fun Hit.hasText() = !syncedLyrics.isNullOrBlank() || !plainLyrics.isNullOrBlank() || instrumental

    private fun get(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000; conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", userAgent)
        conn.setRequestProperty("Accept", "application/json")
        return try {
            when (conn.responseCode) {
                in 200..299 -> conn.inputStream.bufferedReader().readText()
                404 -> null
                else -> error("lrclib HTTP ${conn.responseCode}")
            }
        } finally { conn.disconnect() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    companion object { const val BASE = "https://lrclib.net" }
}
