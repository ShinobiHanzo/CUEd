package dev.cued.core

import dev.cued.core.crypto.Secp256k1
import dev.cued.core.crypto.toHex
import dev.cued.core.desktop.AccountChain
import dev.cued.core.desktop.FriendLink
import dev.cued.core.desktop.Seal
import dev.cued.core.share.SharePayload
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Codecs shared with CUEd-desktop (`cued-proto`) and its Python companion; vectors are the same on all three sides. */
class DesktopCodecsTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { i -> ((Character.digit(s[2 * i], 16) shl 4) or Character.digit(s[2 * i + 1], 16)).toByte() }

    @Test fun friendLinkRoundTrip() {
        val l = FriendLink("ab".repeat(32), "Idris", listOf("wss://nos.lol"))
        assertEquals("cued://friend?p=${"ab".repeat(32)}&n=Idris&r=wss%3A%2F%2Fnos.lol", l.encode())
        assertEquals(l, FriendLink.decode(l.encode()))
        assertNull(FriendLink.decode("cued://friend?p=zz"))
        assertEquals(listOf("d", "friend:${"cd".repeat(32)}"), FriendLink.tags("cd".repeat(32))[0])
    }

    @Test fun shareCarriesAFriendRequest() {
        val p = SharePayload(title = "Song", artist = "Band", friendPubkey = "ab".repeat(32), friendName = "Idris")
        assertTrue(p.encode().endsWith("&fr=${"ab".repeat(32)}&fn=Idris"), p.encode())
        val back = SharePayload.decode(p.encode())!!
        assertEquals("ab".repeat(32), back.friendPubkey)
        assertEquals("Idris", back.friendName)
        assertNull(SharePayload.decode("cued://share?t=x&a=y&fr=zz")!!.friendPubkey)
    }

    @Test fun sealVectorAndTamper() {
        val secret = hex("0f".repeat(16))
        val sealed = Seal.seal(secret, "account", "hello".toByteArray(), nonce = ByteArray(16))
        // nonce(16 zero bytes) || ct || tag, the vector shared with Rust and Python
        val raw = java.util.Base64.getUrlDecoder().decode(sealed)
        assertEquals("e19510564f", raw.copyOfRange(16, 21).toHex())
        assertEquals("30e13d3793e94f365e8cb8b3e9fee951adac2972f37a17413bc1e8ea0e1b216b", raw.copyOfRange(21, 53).toHex())
        assertEquals("hello", String(Seal.open(secret, "account", sealed)!!))
        assertNull(Seal.open(secret, "other", sealed))
        assertNull(Seal.open(hex("00".repeat(16)), "account", sealed))
        raw[20] = (raw[20].toInt() xor 1).toByte()
        assertNull(Seal.open(secret, "account", java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw)))
        val long = Seal.seal(secret, "account", ByteArray(100) { it.toByte() })
        assertEquals((0 until 100).map { it.toByte() }, Seal.open(secret, "account", long)!!.toList())
    }

    @Test fun accountChainBuildsValidatesAndMerges() {
        val sk = Secp256k1.generateSecret().toHex()
        var a = AccountChain.create(sk, "Idris", 1)
        a = a.append(sk, "bind_device", AccountChain.data("""{"devhash":"${"ab".repeat(32)}","name":"Pixel","kind":"phone"}"""), 2)
        assertNull(a.validate())
        assertEquals("Idris", a.name())
        assertEquals(1, a.devices().size)
        val b = a.append(sk, "settings", AccountChain.data("""{"relayEnabled":true}"""), 3).append(sk, "settings", AccountChain.data("""{"theme":{"accent":"#fff"}}"""), 4)
        assertEquals(JsonPrimitive(true), b.settings()["relayEnabled"])
        assertEquals(4, a.merge(b).height)
        assertEquals(a.height, a.merge(AccountChain(a.blocks.take(1))).height)
        // JSON round trip keeps `data` key order, so every hash still verifies.
        val back = AccountChain.decode(b.encode())!!
        assertNull(back.validate())
        assertEquals(b, back)
        // Tampering breaks it; a foreign key cannot extend it.
        val tampered = AccountChain(b.blocks.mapIndexed { i, blk -> if (i == 1) blk.copy(data = AccountChain.data("""{"devhash":"${"ab".repeat(32)}","name":"Other","kind":"phone"}""")) else blk })
        assertNotNull(tampered.validate())
        val other = Secp256k1.generateSecret().toHex()
        assertTrue(runCatching { b.append(other, "profile", AccountChain.data("""{"name":"x"}"""), 5) }.isFailure)
        a = a.append(sk, "unbind_device", AccountChain.data("""{"devhash":"${"ab".repeat(32)}"}"""), 6)
        assertTrue(a.devices().isEmpty())
        assertEquals(64, AccountChain.devhash("0011223344556677", "salt").length)
        assertEquals("b9b19a4064a063053eaa2e382fdf36ecb94a4e777dc3df01871177d43905e72a".length, AccountChain.accountId(Secp256k1.publicKey(hex(sk)).toHex()).length)
        assertEquals("Pixel", back.blocks[1].data["name"]!!.jsonPrimitive.content)
    }
}
