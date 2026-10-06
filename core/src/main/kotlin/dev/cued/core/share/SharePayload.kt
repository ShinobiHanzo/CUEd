package dev.cued.core.share

import dev.cued.core.CuedCore
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * What one phone hands another over QR or NFC. Kept tiny so it fits a
 * QR code comfortably and a single NFC APDU exchange.
 *
 * Encoded as a `cued://share?...` URI so that a phone *without* the app can
 * still read the QR with its camera: the `apk` field points at the sender's
 * local share server (or a public release), and `link` is the original
 * source URL (Spotify / YouTube) that the receiving app can hand to spotdl.
 */
data class SharePayload(
    val title: String,
    val artist: String,
    /** Source URL usable by spotdl, if known (e.g. open.spotify.com/track/...). */
    val link: String? = null,
    /** URL of the audio file on the sender's local share server. */
    val fileUrl: String? = null,
    /** URL to download the CUEd APK (local share server or GitHub release). */
    val apkUrl: String? = null,
    val genres: List<String> = emptyList(),
    val bpm: Float? = null,
    val version: Int = CuedCore.SHARE_PAYLOAD_VERSION,
) {
    fun encode(): String = "${CuedCore.SHARE_SCHEME}://share?" + query()

    /**
     * Same fields behind the download page's URL. This is what NFC and QR
     * carry: a phone without CUEd lands on the page (which offers the app and
     * an "open in CUEd" link), a phone with CUEd gets it straight away.
     */
    fun encodeWeb(): String = CuedCore.SHARE_WEB_BASE + "#" + query()

    private fun query(): String {
        val q = LinkedHashMap<String, String>()
        q["v"] = version.toString()
        q["t"] = title
        q["a"] = artist
        link?.let { q["l"] = it }
        fileUrl?.let { q["f"] = it }
        apkUrl?.let { q["k"] = it }
        if (genres.isNotEmpty()) q["g"] = genres.joinToString(",")
        bpm?.let { q["b"] = "%.1f".format(java.util.Locale.ROOT, it) }
        return q.entries.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
    }

    companion object {
        private const val PREFIX = "${CuedCore.SHARE_SCHEME}://share?"

        /** Accepts `cued://share?…` and the web form `…/CUEd/#…` (or `?…`). */
        fun decode(text: String): SharePayload? {
            val t = text.trim()
            val query = when {
                t.startsWith(PREFIX) -> t.removePrefix(PREFIX)
                t.startsWith(CuedCore.SHARE_WEB_BASE, ignoreCase = true) -> t.substring(CuedCore.SHARE_WEB_BASE.length).trimStart('#', '?')
                else -> return null
            }
            val q = HashMap<String, String>()
            for (pair in query.split('&')) {
                val i = pair.indexOf('=')
                if (i <= 0) continue
                q[pair.substring(0, i)] = dec(pair.substring(i + 1))
            }
            val title = q["t"] ?: return null
            return SharePayload(
                title = title,
                artist = q["a"] ?: "",
                link = q["l"],
                fileUrl = q["f"],
                apkUrl = q["k"],
                genres = q["g"]?.split(',')?.filter { it.isNotBlank() } ?: emptyList(),
                bpm = q["b"]?.toFloatOrNull(),
                version = q["v"]?.toIntOrNull() ?: 1,
            )
        }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")
    }
}
