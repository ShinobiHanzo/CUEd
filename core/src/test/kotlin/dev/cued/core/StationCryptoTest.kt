package dev.cued.core

import dev.cued.core.crypto.Bech32
import dev.cued.core.crypto.Nip19
import dev.cued.core.crypto.Secp256k1
import dev.cued.core.crypto.hexToBytes
import dev.cued.core.crypto.toHex
import dev.cued.core.station.Nostr
import dev.cued.core.station.RelayMessage
import dev.cued.core.station.Station
import dev.cued.core.station.StationLink
import dev.cued.core.station.StationState
import dev.cued.core.station.StationTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StationCryptoTest {
    private fun vectors(): List<List<String>> =
        javaClass.getResourceAsStream("/bip340-vectors.csv")!!.bufferedReader().readLines().drop(1)
            .filter { it.isNotBlank() }.map { line -> line.split(",") }

    @Test fun bip340VectorsSign() {
        var signed = 0
        for (v in vectors()) {
            val (idx, sk, pk, aux, msg) = v
            val sig = v[5]; val ok = v[6] == "TRUE"
            if (sk.isBlank()) continue
            assertEquals(pk.lowercase(), Secp256k1.publicKey(sk.hexToBytes()).toHex(), "pubkey #$idx")
            if (ok) {
                assertEquals(sig.lowercase(), Secp256k1.sign(msg.hexToBytes(), sk.hexToBytes(), aux.hexToBytes()).toHex(), "sig #$idx")
                signed++
            }
        }
        assertTrue(signed >= 4)
    }

    @Test fun bip340VectorsVerify() {
        var checked = 0
        for (v in vectors()) {
            val (idx, _, pk, _, msg) = v
            val sig = v[5]; val ok = v[6] == "TRUE"
            if (pk.length != 64 || sig.length != 128) continue
            assertEquals(ok, Secp256k1.verify(msg.hexToBytes(), pk.hexToBytes(), sig.hexToBytes()), "verify #$idx")
            checked++
        }
        assertTrue(checked >= 10)
    }

    @Test fun keysRoundTripThroughBech32() {
        val sk = Secp256k1.generateSecret()
        val pk = Secp256k1.publicKey(sk).toHex()
        val npub = Nip19.npub(pk)
        assertTrue(npub.startsWith("npub1"))
        assertEquals("npub" to pk, Nip19.parse(npub))
        assertEquals("nsec" to sk.toHex(), Nip19.parse(Nip19.nsec(sk.toHex())))
        assertEquals("hex" to pk, Nip19.parse(pk.uppercase()))
        assertNull(Nip19.parse("npub1notvalid"))
        // NIP-19's own example
        assertEquals("npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6", Nip19.npub("3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"))
        assertNull(Bech32.decode("npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w7"))
    }

    @Test fun eventsSignVerifyAndSerialize() {
        val sk = Secp256k1.generateSecret().toHex()
        val state = StationState(name = "Idris", status = Station.STATUS_ON_AIR, now = StationTrack("Afterlife", "Evanescence", key = "k1"), startedAt = 1_700_000_000_000, seq = 3)
        val ev = Nostr.sign(sk, Station.KIND, Station.tags, Station.encode(state), createdAt = 1_700_000_000)
        assertTrue(Nostr.verify(ev))
        assertFalse(Nostr.verify(ev.copy(content = ev.content + " ")))
        assertEquals("cued", ev.tag("d"))
        val wire = Nostr.eventMessage(ev)
        val back = Nostr.parse("""["EVENT","sub",${wire.removePrefix("[\"EVENT\",").removeSuffix("]")}]""")
        assertTrue(back is RelayMessage.Event)
        assertEquals(ev, (back as RelayMessage.Event).event)
        assertEquals(state, Station.decode(ev.content))
        assertEquals(RelayMessage.Ok("abc", true, ""), Nostr.parse("""["OK","abc",true,""]"""))
        assertTrue(Nostr.parse("garbage") is RelayMessage.Unknown)
        assertTrue(Nostr.reqMessage("s1", Nostr.stationFilter(listOf(ev.pubkey))).contains("\"#d\":[\"cued\"]"))
    }

    @Test fun canonicalEscaping() {
        // NIP-01: newline, quote and backslash escaped; non-ASCII left alone.
        val c = Nostr.canonical("ab", 1, 1, emptyList(), "a\"b\\c\nd é")
        assertEquals("""[0,"ab",1,1,[],"a\"b\\c\nd é"]""", c)
    }

    @Test fun stationLinkRoundTrip() {
        val l = StationLink("3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d", "Idris & co", listOf("wss://a.example", "wss://b.example"))
        assertEquals(l, StationLink.decode(l.encode()))
        assertNull(StationLink.decode("cued://share?t=x"))
        assertNull(StationLink.decode("cued://station?p=zz"))
        assertNotNull(StationLink.decode("cued://station?p=" + l.pubkey.uppercase()))
    }

    @Test fun expectedPosition() {
        val s = StationState(status = Station.STATUS_ON_AIR, startedAt = 1000)
        assertEquals(500, Station.expectedPositionMs(s, 1500))
        assertEquals(42, Station.expectedPositionMs(s.copy(status = Station.STATUS_PAUSED, positionMs = 42), 99_999))
    }
}
