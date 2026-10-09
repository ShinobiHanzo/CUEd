package dev.cued.core.desktop

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * `seal` / `open` from CUEd-desktop `docs/protocol.md` §8: HMAC-SHA256 in
 * counter mode with an encrypt-then-MAC tag, keyed from a secret two devices
 * only ever saw inside a QR code. Carries the account bundle between a phone
 * and a desktop at pairing time. Same bytes as the Rust and Python versions.
 */
object Seal {
    private fun hmac(key: ByteArray, msg: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(msg)

    private fun keys(secret: ByteArray, label: String): Pair<ByteArray, ByteArray> {
        val k = hmac("cued-seal-v1".toByteArray(Charsets.UTF_8), secret + label.toByteArray(Charsets.UTF_8))
        return hmac(k, "enc".toByteArray()) to hmac(k, "mac".toByteArray())
    }

    private fun xorKeystream(ke: ByteArray, nonce: ByteArray, data: ByteArray) {
        var i = 0
        var block = 0
        while (i < data.size) {
            val counter = ByteArray(4) { b -> (block ushr (8 * (3 - b)) and 0xff).toByte() }
            val ks = hmac(ke, nonce + counter)
            val n = minOf(32, data.size - i)
            for (j in 0 until n) data[i + j] = (data[i + j].toInt() xor ks[j].toInt()).toByte()
            i += n; block++
        }
    }

    /** Seals [plaintext] under [secret] (raw bytes) and [label]; returns base64url without padding. */
    fun seal(secret: ByteArray, label: String, plaintext: ByteArray, nonce: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }): String {
        require(nonce.size == 16) { "nonce must be 16 bytes" }
        val (ke, km) = keys(secret, label)
        val ct = plaintext.copyOf()
        xorKeystream(ke, nonce, ct)
        val body = nonce + ct
        return Base64.getUrlEncoder().withoutPadding().encodeToString(body + hmac(km, body))
    }

    /** Opens what [seal] produced, or null on a wrong secret, label or any tampering. */
    fun open(secret: ByteArray, label: String, sealed: String): ByteArray? {
        val raw = runCatching { Base64.getUrlDecoder().decode(sealed.trim()) }.getOrNull() ?: return null
        if (raw.size < 48) return null
        val (ke, km) = keys(secret, label)
        val body = raw.copyOfRange(0, raw.size - 32)
        val tag = raw.copyOfRange(raw.size - 32, raw.size)
        if (!java.security.MessageDigest.isEqual(hmac(km, body), tag)) return null
        val pt = body.copyOfRange(16, body.size)
        xorKeystream(ke, body.copyOfRange(0, 16), pt)
        return pt
    }
}
