package dev.cued.core.desktop

import dev.cued.core.CuedCore
import java.net.URLDecoder
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * `cued://pair?…`: what the CUEd desktop shows as a QR code to link this phone.
 *
 * Fields (see CUEd-desktop `docs/protocol.md` §2): `n` desktop name, `a` LAN
 * base URLs, `f` funnel base URL, `c` SHA-256 of the funnel's self-signed TLS
 * certificate (to pin), `s` one-time pairing secret (16 bytes hex), `k` the
 * desktop's station pubkey, `r` the desktop's embedded relay URL.
 */
data class PairLink(
    val name: String,
    val addresses: List<String>,
    val funnel: String? = null,
    val certSha256: String? = null,
    val secret: String,
    val pubkey: String? = null,
    val relay: String? = null,
    val version: Int = VERSION,
) {
    fun encode(): String {
        val q = LinkedHashMap<String, String>()
        q["v"] = version.toString()
        q["n"] = name
        q["a"] = addresses.joinToString(",")
        funnel?.let { q["f"] = it }
        certSha256?.let { q["c"] = it }
        q["s"] = secret
        pubkey?.let { q["k"] = it }
        relay?.let { q["r"] = it }
        return PREFIX + q.entries.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
    }

    /**
     * Body of `POST /api/pair`. [device] is this phone's stable 16-hex id,
     * [pubkey] its station key (or empty). The proof shows the desktop this
     * phone saw the QR; the secret itself never crosses the wire.
     */
    fun request(phoneName: String, device: String, pubkey: String = "", nonce: String = randomHex(8), account: String = "separate", biokey: String = "", devhash: String = ""): PairRequest =
        PairRequest(name = phoneName, device = device, pubkey = pubkey, nonce = nonce, proof = proof(secret, device, phoneName, nonce), account = account, biokey = biokey, devhash = devhash)

    companion object {
        const val VERSION = 1
        private const val PREFIX = "${CuedCore.SHARE_SCHEME}://pair?"

        fun decode(text: String): PairLink? {
            val t = text.trim()
            if (!t.startsWith(PREFIX)) return null
            val q = HashMap<String, String>()
            for (pair in t.removePrefix(PREFIX).split('&')) {
                val i = pair.indexOf('='); if (i <= 0) continue
                q[pair.substring(0, i)] = dec(pair.substring(i + 1))
            }
            val secret = q["s"]?.lowercase() ?: return null
            if (!isHex(secret, 32)) return null
            val pubkey = q["k"]?.lowercase()
            if (pubkey != null && !isHex(pubkey, 64)) return null
            return PairLink(
                name = q["n"].orEmpty(),
                addresses = q["a"]?.split(',')?.filter { it.isNotBlank() } ?: emptyList(),
                funnel = q["f"],
                certSha256 = q["c"]?.lowercase(),
                secret = secret,
                pubkey = pubkey,
                relay = q["r"],
                version = q["v"]?.toIntOrNull() ?: VERSION,
            )
        }

        /** `HMAC-SHA256(secret bytes, device|name|nonce)` as hex; the desktop computes the same. */
        fun proof(secretHex: String, device: String, name: String, nonce: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(hexToBytes(secretHex), "HmacSHA256"))
            return mac.doFinal("$device|$name|$nonce".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }

        fun randomHex(bytes: Int): String = ByteArray(bytes).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

        private fun isHex(s: String, len: Int) = s.length == len && s.all { Character.digit(it, 16) >= 0 }
        private fun hexToBytes(s: String) = ByteArray(s.length / 2) { i -> ((Character.digit(s[2 * i], 16) shl 4) or Character.digit(s[2 * i + 1], 16)).toByte() }
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")
    }
}

/** JSON body of `POST /api/pair`; field names are the wire names (§2 and §8: `account`, `biokey`, `devhash`). */
data class PairRequest(
    val v: Int = PairLink.VERSION,
    val name: String,
    val device: String,
    val pubkey: String,
    val nonce: String,
    val proof: String,
    /** `join` (take the desktop's account), `keep` (give the desktop mine) or `separate`. */
    val account: String = "separate",
    /** SEC1 hex of the phone's biometric P-256 key, or empty. */
    val biokey: String = "",
    /** `sha256("cued-device|" + device + "|" + salt)`, or empty. */
    val devhash: String = "",
) {
    fun toJson(): String = buildString {
        append('{')
        append("\"v\":").append(v).append(',')
        append("\"name\":").append(q(name)).append(',')
        append("\"device\":").append(q(device)).append(',')
        append("\"pubkey\":").append(q(pubkey)).append(',')
        append("\"nonce\":").append(q(nonce)).append(',')
        append("\"proof\":").append(q(proof)).append(',')
        append("\"account\":").append(q(account)).append(',')
        append("\"biokey\":").append(q(biokey)).append(',')
        append("\"devhash\":").append(q(devhash))
        append('}')
    }

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
