package dev.cued.core.crypto

/** BIP-173 bech32, enough for NIP-19 `npub…` / `nsec…` identifiers. */
object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GEN = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    private fun polymod(values: IntArray): Int {
        var chk = 1
        for (v in values) {
            val b = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) if ((b ushr i) and 1 == 1) chk = chk xor GEN[i]
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray =
        IntArray(hrp.length * 2 + 1).also { out ->
            for (i in hrp.indices) { out[i] = hrp[i].code ushr 5; out[i + hrp.length + 1] = hrp[i].code and 31 }
        }

    private fun convertBits(data: ByteArray, from: Int, to: Int, pad: Boolean): IntArray {
        var acc = 0; var bits = 0
        val out = ArrayList<Int>()
        val maxv = (1 shl to) - 1
        for (b in data) {
            acc = (acc shl from) or (b.toInt() and 0xff)
            bits += from
            while (bits >= to) { bits -= to; out += (acc ushr bits) and maxv }
        }
        if (pad) { if (bits > 0) out += (acc shl (to - bits)) and maxv }
        else require(bits < from && ((acc shl (to - bits)) and maxv) == 0) { "invalid padding" }
        return out.toIntArray()
    }

    fun encode(hrp: String, data: ByteArray): String {
        val d = convertBits(data, 8, 5, true)
        val values = hrpExpand(hrp) + d + IntArray(6)
        val mod = polymod(values) xor 1
        val checksum = IntArray(6) { (mod ushr (5 * (5 - it))) and 31 }
        return hrp + "1" + (d + checksum).joinToString("") { CHARSET[it].toString() }
    }

    /** Returns hrp and payload bytes, or null when the string is not valid bech32. */
    fun decode(text: String): Pair<String, ByteArray>? {
        val s = text.trim()
        if (s != s.lowercase() && s != s.uppercase()) return null
        val t = s.lowercase()
        val pos = t.lastIndexOf('1')
        if (pos < 1 || pos + 7 > t.length) return null
        val hrp = t.substring(0, pos)
        val data = IntArray(t.length - pos - 1) { i ->
            val c = CHARSET.indexOf(t[pos + 1 + i]); if (c < 0) return null; c
        }
        if (polymod(hrpExpand(hrp) + data) != 1) return null
        val payload = data.copyOfRange(0, data.size - 6)
        return runCatching { hrp to convertBits(ByteArray(payload.size) { payload[it].toByte() }, 5, 8, false).let { ints -> ByteArray(ints.size) { ints[it].toByte() } } }.getOrNull()
    }
}

/** NIP-19 helpers. */
object Nip19 {
    fun npub(pubkeyHex: String) = Bech32.encode("npub", pubkeyHex.hexToBytes())
    fun nsec(secretHex: String) = Bech32.encode("nsec", secretHex.hexToBytes())
    /** Accepts npub/nsec or bare 64-char hex; returns (kind, hex) where kind is "npub", "nsec" or "hex". */
    fun parse(text: String): Pair<String, String>? {
        val t = text.trim()
        if (t.length == 64 && t.all { Character.digit(it, 16) >= 0 }) return "hex" to t.lowercase()
        val (hrp, data) = Bech32.decode(t) ?: return null
        if (data.size != 32) return null
        return when (hrp) { "npub", "nsec" -> hrp to data.toHex(); else -> null }
    }
}
