package dev.cued.core

import dev.cued.core.desktop.PairLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairLinkTest {
    private val secret = "0f".repeat(16)

    @Test fun roundTripMatchesDesktopEncoding() {
        val link = PairLink(
            name = "Idris's laptop",
            addresses = listOf("http://192.168.1.10:8770", "http://10.8.0.2:8770"),
            funnel = "https://home.example.net:443",
            certSha256 = "ab".repeat(32),
            secret = secret,
            pubkey = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d",
            relay = "ws://192.168.1.10:8770/relay",
        )
        val s = link.encode()
        // Same bytes the desktop's cued-proto produces for these fields.
        assertTrue(s.startsWith("cued://pair?v=1&n=Idris%27s%20laptop&a=http%3A%2F%2F192.168.1.10%3A8770%2Chttp%3A%2F%2F10.8.0.2%3A8770&f=https%3A%2F%2Fhome.example.net%3A443&c=${"ab".repeat(32)}&s=$secret&k="), s)
        assertEquals(link, PairLink.decode(s))
        assertNull(PairLink.decode("cued://pair?n=x"))
        assertNull(PairLink.decode("cued://pair?s=nothex"))
        assertNull(PairLink.decode("cued://share?t=x"))
    }

    @Test fun proofIsStableAndSecretBound() {
        // Independently computed: HMAC-SHA256(key = 0x0f*16, "0011223344556677|Pixel 8|deadbeef")
        assertEquals("78f4743fd76a4f52d564add687136ba32ea146005f309f237bee9781d13048d7", PairLink.proof(secret, "0011223344556677", "Pixel 8", "deadbeef"))
        assertTrue(PairLink.proof(secret, "0011223344556677", "Pixel 8", "deadbeef") != PairLink.proof("00".repeat(16), "0011223344556677", "Pixel 8", "deadbeef"))
        val req = PairLink.decode(PairLink(name = "d", addresses = emptyList(), secret = secret).encode())!!.request("Pixel 8", "0011223344556677")
        assertEquals(16, req.nonce.length)
        assertEquals(req.proof, PairLink.proof(secret, "0011223344556677", "Pixel 8", req.nonce))
        assertTrue(req.toJson().startsWith("{\"v\":1,\"name\":\"Pixel 8\",\"device\":\"0011223344556677\",\"pubkey\":\"\",\"nonce\":\""))
    }
}
