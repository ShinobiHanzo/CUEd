package dev.cued.core

import dev.cued.core.crypto.Secp256k1
import dev.cued.core.crypto.hexToBytes
import dev.cued.core.crypto.toHex
import dev.cued.core.party.Chunker
import dev.cued.core.party.Party
import dev.cued.core.party.PartyHello
import dev.cued.core.party.PartyNow
import dev.cued.core.party.PartyTrack
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PartyTest {
    private val guestSk = Secp256k1.generateSecret().toHex()
    private val guestPub = Secp256k1.publicKey(guestSk.hexToBytes()).toHex()

    @Test fun signRoundTripAndTamper() {
        val e = Party.sign(guestSk, Party.OP_SEARCH, Party.searchArg("daft punk"))
        assertTrue(Party.verify(e))
        assertEquals(guestPub, e.pubkey)
        val text = Party.encode(e)
        val back = Party.decode(text)!!
        assertTrue(Party.verify(back))
        assertEquals("daft punk", Party.queryOf(back.arg))
        // Any change breaks the signature: the op, the argument, the ok flag.
        assertFalse(Party.verify(back.copy(op = Party.OP_SKIP)))
        assertFalse(Party.verify(back.copy(arg = Party.searchArg("other"))))
        assertFalse(Party.verify(back.copy(ok = false)))
        assertFalse(Party.verify(back.copy(ts = back.ts + 1)))
    }

    @Test fun canonicalJsonIsOrderIndependent() {
        val a = buildJsonObject { put("b", JsonPrimitive(1)); put("a", JsonPrimitive("x")); put("c", buildJsonArray { add(JsonPrimitive(true)); add(JsonPrimitive(2.5)) }) }
        val b = buildJsonObject { put("c", buildJsonArray { add(JsonPrimitive(true)); add(JsonPrimitive(2.5)) }); put("a", JsonPrimitive("x")); put("b", JsonPrimitive(1)) }
        assertEquals(Party.canonical(a), Party.canonical(b))
        assertEquals("""{"a":"x","b":1,"c":[true,2.5]}""", Party.canonical(a))
    }

    @Test fun hostGateFriendsSkewAndReplay() {
        val now = 1_700_000_000L
        val e = Party.sign(guestSk, Party.OP_QUEUE, Party.queueArg(PartyTrack(sha256 = "ab", title = "T")), ts = now)
        val cache = Party.NonceCache()
        val friends = mapOf(guestPub to "friends")
        assertEquals(Party.Check.Ok, Party.check(e, now, { cache.seenBefore(it, now) }, friends::get))
        // Same nonce again: replay.
        assertTrue(Party.check(e, now, { cache.seenBefore(it, now) }, friends::get) is Party.Check.Bad)
        // Not a friend, pending, too old.
        assertTrue(Party.check(e, now, { false }) { null } is Party.Check.Bad)
        assertTrue(Party.check(e, now, { false }) { "pending_in" } is Party.Check.Bad)
        assertTrue(Party.check(e, now + Party.MAX_SKEW_SEC + 1, { false }, friends::get) is Party.Check.Bad)
        assertEquals(Party.Check.Ok, Party.check(e, now + Party.MAX_SKEW_SEC, { false }, friends::get))
    }

    @Test fun repliesCarryPayloadsAndErrors() {
        val hostSk = Secp256k1.generateSecret().toHex()
        val hello = PartyHello("Kitchen", "00".repeat(32), controls = true, now = PartyNow(current = PartyTrack(sha256 = "cd", title = "Song", artist = "A"), playing = true, queueLength = 3))
        val reply = Party.sign(hostSk, Party.OP_HELLO, Party.toJson(hello), nonce = "0011223344556677")
        assertTrue(Party.verify(reply))
        assertEquals("0011223344556677", reply.nonce)
        val h = Party.helloOf(reply.arg)!!
        assertEquals("Kitchen", h.name); assertTrue(h.controls); assertEquals("Song", h.now.current?.title); assertEquals(3, h.now.queueLength)
        assertNull(Party.errorOf(reply))

        val err = Party.sign(hostSk, Party.OP_QUEUE, Party.errorArg("no such track"), ok = false)
        assertTrue(Party.verify(err))
        assertEquals("no such track", Party.errorOf(err))

        val list = Party.sign(hostSk, Party.OP_SEARCH, Party.toJson(listOf(PartyTrack(id = 7, title = "x"), PartyTrack(sha256 = "ff", title = "y", durationMs = 1000))))
        val tracks = Party.tracksOf(Party.decode(Party.encode(list))!!.arg)
        assertEquals(2, tracks.size); assertEquals(7L, tracks[0].id); assertEquals(1000L, tracks[1].durationMs)
    }

    @Test fun beaconRoundTrip() {
        val text = Party.beaconAnswer("Living room", "ab".repeat(32), "http://192.168.1.9:8765")
        assertTrue(text.startsWith(Party.BEACON_PREFIX))
        val b = Party.parseBeacon(text)!!
        assertEquals("Living room", b.name); assertEquals("http://192.168.1.9:8765", b.url); assertEquals("lan", b.transport)
        assertNull(Party.parseBeacon("CUED-PARTY?"))
        assertNull(Party.parseBeacon("CUED-PARTY {\"pubkey\":\"short\"}"))
    }

    @Test fun chunkerSplitsAndReassemblesOutOfOrder() {
        val msg = ByteArray(1234) { (it * 7).toByte() }
        val frames = Chunker.split(msg, 100)
        assertEquals(13, frames.size)
        assertTrue(frames.all { it.size <= 100 })
        val asm = Chunker.Assembler()
        var out: ByteArray? = null
        for (f in frames.shuffled()) { val r = asm.feed(f); if (r != null) out = r }
        assertNotNull(out)
        assertTrue(msg.contentEquals(out!!))
        // A single short message is one frame.
        val one = Chunker.split("hi".toByteArray(), 20)
        assertEquals(1, one.size)
        assertTrue("hi".toByteArray().contentEquals(Chunker.Assembler().feed(one[0])!!))
    }
}
