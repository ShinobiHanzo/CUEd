package dev.cued.app.download.native

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.cued.app.util.DebugLog
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Finds a usable cover for a track from whatever is known about it and
 * returns it as a square JPEG (≤ 800 px), the one format every tag reader
 * and Android's own scanner agree on. Tries candidates in order and skips
 * 404s and undecodable responses.
 */
object CoverFinder {
    /** YouTube thumbnails, largest first; maxresdefault is missing for many videos. */
    fun youtubeCandidates(videoId: String): List<String> = listOf("maxresdefault", "sddefault", "hqdefault", "mqdefault").map { "https://i.ytimg.com/vi/$videoId/$it.jpg" }

    fun fetchFirst(urls: List<String>): ByteArray? {
        for (u in urls.distinct()) {
            val raw = runCatching { fetch(u) }.onFailure { DebugLog.d("cover", "$u: ${it.message}") }.getOrNull() ?: continue
            val jpeg = squareJpeg(raw)
            if (jpeg != null) { DebugLog.d("cover", "using $u (${jpeg.size / 1024} KB)"); return jpeg }
        }
        return null
    }

    /** Decodes, centre-crops anything wider than 1.15:1 (YouTube's 16:9 frames), scales to ≤ 800 px, re-encodes as JPEG. */
    fun squareJpeg(raw: ByteArray): ByteArray? {
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
        if (bmp.width < 120 || bmp.height < 120) return null // placeholder thumbnails
        var out = bmp
        if (bmp.width.toFloat() / bmp.height > 1.15f || bmp.height.toFloat() / bmp.width > 1.15f) {
            val side = minOf(bmp.width, bmp.height)
            out = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
        }
        if (out.width > 800) out = Bitmap.createScaledBitmap(out, 800, 800, true)
        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 90, bos)
        return bos.toByteArray()
    }

    private fun fetch(url: String): ByteArray? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000; conn.readTimeout = 20_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", NewPipeHttp.USER_AGENT)
        return try {
            val code = conn.responseCode
            if (code in 300..399) { // coverartarchive redirects to archive.org across schemes; follow once by hand
                val loc = conn.getHeaderField("Location") ?: return null
                conn.disconnect(); return fetch(loc)
            }
            if (code != 200) { DebugLog.d("cover", "$url -> HTTP $code"); null } else conn.inputStream.use { it.readBytes() }.takeIf { it.size in 1_000..6_000_000 }
        } finally { conn.disconnect() }
    }
}
