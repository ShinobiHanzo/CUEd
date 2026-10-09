package dev.cued.core.desktop

import dev.cued.core.CuedCore
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * `cued://friend?p=<pubkey>&n=<name>&r=<relays>`: what one person hands another
 * over QR or NFC to become friends. Scanning it sends a request; two people
 * scanning each other are friends at once. Only the public key and a name are
 * in it (CUEd-desktop `docs/protocol.md` §9).
 */
data class FriendLink(val pubkey: String, val name: String = "", val relays: List<String> = emptyList()) {
    fun encode(): String {
        val q = LinkedHashMap<String, String>()
        q["p"] = pubkey
        if (name.isNotBlank()) q["n"] = name
        if (relays.isNotEmpty()) q["r"] = relays.joinToString(",")
        return PREFIX + q.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8").replace("+", "%20")}" }
    }

    companion object {
        private const val PREFIX = "${CuedCore.SHARE_SCHEME}://friend?"
        /** Nostr kind of a friend request / accept / remove event (parameterised-replaceable, `d` = `friend:<target>`). */
        const val KIND = 30778

        fun decode(text: String): FriendLink? {
            val t = text.trim()
            if (!t.startsWith(PREFIX)) return null
            val q = HashMap<String, String>()
            for (pair in t.removePrefix(PREFIX).split('&')) {
                val i = pair.indexOf('='); if (i <= 0) continue
                q[pair.substring(0, i)] = URLDecoder.decode(pair.substring(i + 1), "UTF-8")
            }
            val p = q["p"]?.lowercase() ?: return null
            if (p.length != 64 || p.any { Character.digit(it, 16) < 0 }) return null
            return FriendLink(p, q["n"].orEmpty(), q["r"]?.split(',')?.filter { it.isNotBlank() } ?: emptyList())
        }

        /** Tags of a friend event aimed at [target]. */
        fun tags(target: String): List<List<String>> = listOf(listOf("d", "friend:$target"), listOf("p", target), listOf("t", "cued-friend"))
    }
}
