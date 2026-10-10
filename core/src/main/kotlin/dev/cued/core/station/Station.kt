package dev.cued.core.station

import dev.cued.core.CuedCore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * A station is a person's key plus a stream of signed "what I'm playing"
 * states. No audio crosses the network: listeners resolve each entry to a
 * file of their own (library match, or a temporary download from the source
 * link) and play it at the host's position.
 */
object Station {
    /** Parameterised-replaceable kind (NIP-01 30000–39999): a relay keeps only the newest per author + d tag. */
    const val KIND = 30777
    const val D_TAG = "cued"
    const val STATUS_ON_AIR = "on_air"
    const val STATUS_PAUSED = "paused"
    const val STATUS_OFF = "off"
    val tags: List<List<String>> = listOf(listOf("d", D_TAG), listOf("t", "cued-station"))

    fun encode(s: StationState): String = Nostr.json.encodeToString(s)
    fun decode(content: String): StationState? = runCatching { Nostr.json.decodeFromString(StationState.serializer(), content) }.getOrNull()

    /** Where the host is in [StationState.now] at wall-clock [nowMs]. */
    fun expectedPositionMs(s: StationState, nowMs: Long = System.currentTimeMillis()): Long = when (s.status) {
        STATUS_ON_AIR -> (nowMs - s.startedAt).coerceAtLeast(0)
        else -> s.positionMs
    }
}

@Serializable
data class StationTrack(
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0,
    /** Source link listeners can download from (Spotify / YouTube); null means search by title and artist. */
    val link: String? = null,
    val cover: String? = null,
    /** Stable key for this entry on the host's side, so listeners can tell "same track again" from "moved on". */
    val key: String = "",
    /** Capability URL on the host's desktop to fetch the audio (friend streaming on); listeners prefer it over the downloader. */
    val stream: String? = null,
    /** Capability URL for the lyrics on the host's desktop. */
    val lyrics: String? = null,
) {
    /** What a listener's downloader is given. */
    val source: String get() = link ?: "$artist $title"
}

@Serializable
data class StationState(
    val v: Int = 1,
    val name: String = "",
    val status: String = Station.STATUS_OFF,
    val now: StationTrack? = null,
    /** Wall-clock millis at which [now] was at position 0 (on air), so position = now − startedAt. */
    val startedAt: Long = 0,
    /** Position while paused. */
    val positionMs: Long = 0,
    val next: List<StationTrack> = emptyList(),
    /** Monotonic per host; listeners ignore anything older than what they have. */
    val seq: Long = 0,
)

/** `cued://station?p=<pubkey hex>&n=<name>&r=<relay,relay>`: what a QR code or NFC tap hands a follower. */
data class StationLink(val pubkey: String, val name: String = "", val relays: List<String> = emptyList()) {
    fun encode(): String {
        val q = LinkedHashMap<String, String>()
        q["p"] = pubkey
        if (name.isNotBlank()) q["n"] = name
        if (relays.isNotEmpty()) q["r"] = relays.joinToString(",")
        return PREFIX + q.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8").replace("+", "%20")}" }
    }

    companion object {
        private const val PREFIX = "${CuedCore.SHARE_SCHEME}://station?"
        fun decode(text: String): StationLink? {
            val t = text.trim()
            if (!t.startsWith(PREFIX)) return null
            val q = HashMap<String, String>()
            for (pair in t.removePrefix(PREFIX).split('&')) {
                val i = pair.indexOf('='); if (i <= 0) continue
                q[pair.substring(0, i)] = URLDecoder.decode(pair.substring(i + 1), "UTF-8")
            }
            val p = q["p"]?.lowercase() ?: return null
            if (p.length != 64 || p.any { Character.digit(it, 16) < 0 }) return null
            return StationLink(p, q["n"].orEmpty(), q["r"]?.split(',')?.filter { it.isNotBlank() } ?: emptyList())
        }
    }
}
