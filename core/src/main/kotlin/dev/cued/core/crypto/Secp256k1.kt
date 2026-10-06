package dev.cued.core.crypto

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * secp256k1 with BIP-340 Schnorr signatures, in plain Kotlin on BigInteger.
 *
 * Why not a native library: a station identity signs a few hundred bytes per
 * track change, so speed is irrelevant, and every line here can be read and
 * unit-tested against the BIP-340 vectors. Not constant-time; the threat
 * model is "another app timing my CPU while I change tracks", which is not one.
 */
object Secp256k1 {
    val P: BigInteger = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    val N: BigInteger = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val GX = BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16)
    private val GY = BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)
    private val SEVEN = BigInteger.valueOf(7)
    private val TWO = BigInteger.TWO
    private val THREE = BigInteger.valueOf(3)
    private val FOUR = BigInteger.valueOf(4)
    private val EIGHT = BigInteger.valueOf(8)

    /** Affine point; `null` elsewhere stands for the point at infinity. */
    class Point(val x: BigInteger, val y: BigInteger) {
        val hasEvenY: Boolean get() = !y.testBit(0)
    }

    private class J(val x: BigInteger, val y: BigInteger, val z: BigInteger)
    private val INF = J(BigInteger.ZERO, BigInteger.ONE, BigInteger.ZERO)
    private val G = J(GX, GY, BigInteger.ONE)

    private fun dbl(p: J): J {
        if (p.z.signum() == 0 || p.y.signum() == 0) return INF
        val ysq = p.y.multiply(p.y).mod(P)
        val s = FOUR.multiply(p.x).multiply(ysq).mod(P)
        val m = THREE.multiply(p.x).multiply(p.x).mod(P)
        val nx = m.multiply(m).subtract(TWO.multiply(s)).mod(P)
        val ny = m.multiply(s.subtract(nx)).subtract(EIGHT.multiply(ysq).multiply(ysq)).mod(P)
        val nz = TWO.multiply(p.y).multiply(p.z).mod(P)
        return J(nx, ny, nz)
    }

    private fun add(p: J, q: J): J {
        if (p.z.signum() == 0) return q
        if (q.z.signum() == 0) return p
        val z1z1 = p.z.multiply(p.z).mod(P)
        val z2z2 = q.z.multiply(q.z).mod(P)
        val u1 = p.x.multiply(z2z2).mod(P)
        val u2 = q.x.multiply(z1z1).mod(P)
        val s1 = p.y.multiply(q.z).multiply(z2z2).mod(P)
        val s2 = q.y.multiply(p.z).multiply(z1z1).mod(P)
        if (u1 == u2) return if (s1 != s2) INF else dbl(p)
        val h = u2.subtract(u1).mod(P)
        val r = s2.subtract(s1).mod(P)
        val h2 = h.multiply(h).mod(P)
        val h3 = h2.multiply(h).mod(P)
        val u1h2 = u1.multiply(h2).mod(P)
        val nx = r.multiply(r).subtract(h3).subtract(TWO.multiply(u1h2)).mod(P)
        val ny = r.multiply(u1h2.subtract(nx)).subtract(s1.multiply(h3)).mod(P)
        val nz = h.multiply(p.z).multiply(q.z).mod(P)
        return J(nx, ny, nz)
    }

    private fun mul(p: J, k: BigInteger): J {
        var r = INF
        var a = p
        var kk = k.mod(N)
        while (kk.signum() > 0) {
            if (kk.testBit(0)) r = add(r, a)
            a = dbl(a)
            kk = kk.shiftRight(1)
        }
        return r
    }

    private fun affine(p: J): Point? {
        if (p.z.signum() == 0) return null
        val zi = p.z.modInverse(P)
        val zi2 = zi.multiply(zi).mod(P)
        return Point(p.x.multiply(zi2).mod(P), p.y.multiply(zi2).multiply(zi).mod(P))
    }

    private fun jac(p: Point) = J(p.x, p.y, BigInteger.ONE)

    /** The point with this x and an even y, or null if x is not on the curve. */
    fun liftX(x: BigInteger): Point? {
        if (x.signum() < 0 || x >= P) return null
        val c = x.modPow(THREE, P).add(SEVEN).mod(P)
        val y = c.modPow(P.add(BigInteger.ONE).shiftRight(2), P)
        if (y.multiply(y).mod(P) != c) return null
        return Point(x, if (y.testBit(0)) P.subtract(y) else y)
    }

    fun bytes32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        val out = ByteArray(32)
        val start = raw.size - 32
        if (start >= 0) System.arraycopy(raw, start, out, 0, 32) else System.arraycopy(raw, 0, out, -start, raw.size)
        return out
    }

    fun int(b: ByteArray): BigInteger = BigInteger(1, b)

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) md.update(p)
        return md.digest()
    }

    fun taggedHash(tag: String, vararg parts: ByteArray): ByteArray {
        val t = sha256(tag.toByteArray(Charsets.UTF_8))
        return sha256(t, t, *parts)
    }

    fun isValidSecret(sk: ByteArray): Boolean = sk.size == 32 && int(sk).let { it.signum() > 0 && it < N }

    fun generateSecret(random: SecureRandom = SecureRandom()): ByteArray {
        while (true) {
            val b = ByteArray(32).also(random::nextBytes)
            if (isValidSecret(b)) return b
        }
    }

    /** x-only public key, 32 bytes. */
    fun publicKey(sk: ByteArray): ByteArray {
        require(isValidSecret(sk)) { "invalid secret key" }
        val p = affine(mul(G, int(sk))) ?: error("infinity")
        return bytes32(p.x)
    }

    /** BIP-340 signature (64 bytes); the message may be any length (Nostr signs the 32-byte event id). */
    fun sign(msg: ByteArray, sk: ByteArray, aux: ByteArray = ByteArray(32).also(SecureRandom()::nextBytes)): ByteArray {
        require(aux.size == 32) { "aux must be 32 bytes" }
        require(isValidSecret(sk)) { "invalid secret key" }
        val d0 = int(sk)
        val pt = affine(mul(G, d0)) ?: error("infinity")
        val d = if (pt.hasEvenY) d0 else N.subtract(d0)
        val t = xor(bytes32(d), taggedHash("BIP0340/aux", aux))
        val px = bytes32(pt.x)
        val rand = taggedHash("BIP0340/nonce", t, px, msg)
        val k0 = int(rand).mod(N)
        require(k0.signum() != 0) { "bad nonce" }
        val r = affine(mul(G, k0)) ?: error("infinity")
        val k = if (r.hasEvenY) k0 else N.subtract(k0)
        val rx = bytes32(r.x)
        val e = int(taggedHash("BIP0340/challenge", rx, px, msg)).mod(N)
        val s = k.add(e.multiply(d)).mod(N)
        val sig = rx + bytes32(s)
        check(verify(msg, px, sig)) { "signature self-check failed" }
        return sig
    }

    fun verify(msg: ByteArray, pub: ByteArray, sig: ByteArray): Boolean {
        if (pub.size != 32 || sig.size != 64) return false
        val pt = liftX(int(pub)) ?: return false
        val r = int(sig.copyOfRange(0, 32))
        val s = int(sig.copyOfRange(32, 64))
        if (r >= P || s >= N) return false
        val e = int(taggedHash("BIP0340/challenge", sig.copyOfRange(0, 32), pub, msg)).mod(N)
        val rr = affine(add(mul(G, s), mul(jac(pt), N.subtract(e)))) ?: return false
        return rr.hasEvenY && rr.x == r
    }

    private fun xor(a: ByteArray, b: ByteArray) = ByteArray(a.size) { (a[it].toInt() xor b[it].toInt()).toByte() }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
fun String.hexToBytes(): ByteArray {
    val s = trim()
    require(s.length % 2 == 0) { "odd hex length" }
    return ByteArray(s.length / 2) { i -> ((Character.digit(s[2 * i], 16) shl 4) or Character.digit(s[2 * i + 1], 16)).toByte() }
}
