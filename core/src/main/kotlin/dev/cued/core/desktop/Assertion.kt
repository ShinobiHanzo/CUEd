package dev.cued.core.desktop

/**
 * Biometric assertions (CUEd-desktop `docs/protocol.md` §7). The phone keeps
 * an ECDSA P-256 key in the Android Keystore that only signs after a
 * biometric unlock; the desktop verifies with the public half registered at
 * pairing. This file holds the parts with no Android in them.
 */
object Assertion {
    /** What the key signs for [device] and [challenge]. */
    fun message(device: String, challenge: String): ByteArray = "cued-bio|$device|$challenge".toByteArray(Charsets.UTF_8)

    /** The `X-Cued-Assertion` header value; the desktop accepts DER or raw `r||s` signatures. */
    fun header(challenge: String, signature: ByteArray): String = challenge + "." + signature.joinToString("") { "%02x".format(it) }

    /**
     * The SEC1 uncompressed point (65 bytes, hex) out of an X.509
     * `SubjectPublicKeyInfo` as `PublicKey.getEncoded()` returns it for a
     * P-256 key: the point is the last 65 bytes, starting with 0x04.
     */
    fun sec1FromSpki(spki: ByteArray): String? {
        if (spki.size < 65) return null
        val point = spki.copyOfRange(spki.size - 65, spki.size)
        if (point[0] != 0x04.toByte()) return null
        return point.joinToString("") { "%02x".format(it) }
    }

    /** `(challenge, expires at millis)` bookkeeping: an assertion is good for ten minutes on the desktop; renew a minute early. */
    const val LIFETIME_MS = 9L * 60_000L
}
