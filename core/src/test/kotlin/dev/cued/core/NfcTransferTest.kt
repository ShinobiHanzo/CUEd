package dev.cued.core

import dev.cued.core.nfc.NfcFileHeader
import dev.cued.core.nfc.NfcTransfer
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NfcTransferTest {
    private val file = Random(7).nextBytes(300_000 + 123) // not a multiple of any slice size
    private val header = NfcFileHeader(
        sha256 = NfcTransfer.sha256Hex(file), size = file.size.toLong(), mime = "audio/mpeg",
        title = "Night Drive", artist = "Ünï", link = "https://open.spotify.com/track/x", genres = listOf("synthwave"), bpm = 118f,
    )
    private fun responder() = NfcTransfer.Responder(header) { off, len -> file.copyOfRange(off.toInt(), off.toInt() + len) }

    @Test
    fun `header round trips and rejects junk`() {
        val h = NfcTransfer.decodeHeader(NfcTransfer.encodeHeader(header))
        assertEquals(header, h)
        assertNull(NfcTransfer.decodeHeader("nope".toByteArray()))
        assertNull(NfcTransfer.decodeHeader("""{"v":1,"sha256":"short","size":1,"mime":"a","title":"t","artist":"a"}""".toByteArray()))
    }

    @Test
    fun `commands parse back`() {
        assertEquals(NfcTransfer.Command.Info, NfcTransfer.parse(NfcTransfer.infoApdu()))
        assertEquals(NfcTransfer.Command.Get(0x01020304L, 0x1234), NfcTransfer.parse(NfcTransfer.getApdu(0x01020304L, 0x1234)))
        assertEquals(NfcTransfer.Command.Get(0xFFFF_FFFFL, 1), NfcTransfer.parse(NfcTransfer.getApdu(0xFFFF_FFFFL, 1)))
        assertEquals(NfcTransfer.Command.Done(true), NfcTransfer.parse(NfcTransfer.doneApdu(true)))
        assertEquals(NfcTransfer.Command.Done(false), NfcTransfer.parse(NfcTransfer.doneApdu(false)))
        assertNull(NfcTransfer.parse(byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00))) // SELECT is not ours
        assertNull(NfcTransfer.parse(byteArrayOf(0x80.toByte(), 0xB0.toByte(), 0x00, 0x00, 0x00))) // the legacy READ is not ours either
        assertEquals(NfcTransfer.Command.Malformed, NfcTransfer.parse(byteArrayOf(0x80.toByte(), 0xC1.toByte(), 0, 0, 2, 1, 1)))
        assertEquals("F043554544", NfcTransfer.AID_HEX)
        assertContentEquals(NfcTransfer.AID, NfcTransfer.selectApdu().copyOfRange(5, 10))
    }

    @Test
    fun `slice size follows what the reader can carry`() {
        assertEquals(250, NfcTransfer.chunkFor(253, false))
        assertEquals(251, NfcTransfer.chunkFor(253, true))
        assertEquals(NfcTransfer.MAX_CHUNK, NfcTransfer.chunkFor(65279, true))
        assertEquals(NfcTransfer.MIN_CHUNK, NfcTransfer.chunkFor(10, true))
    }

    @Test
    fun `responder answers info, slices, bounds and the verdict`() {
        val r = responder()
        val info = r.handle(NfcTransfer.infoApdu())!!
        assertTrue(NfcTransfer.isOk(info))
        assertEquals(header, NfcTransfer.decodeHeader(NfcTransfer.body(info)))
        val slice = r.handle(NfcTransfer.getApdu(1000, 500))!!
        assertContentEquals(file.copyOfRange(1000, 1500), NfcTransfer.body(slice))
        assertEquals(1500, r.sentUpTo)
        // Short at the tail, never past it.
        val tail = r.handle(NfcTransfer.getApdu(file.size - 10L, 4000))!!
        assertEquals(10, NfcTransfer.body(tail).size)
        assertContentEquals(NfcTransfer.SW_WRONG_OFFSET, r.handle(NfcTransfer.getApdu(file.size.toLong(), 1)))
        assertContentEquals(NfcTransfer.SW_WRONG_LENGTH, r.handle(NfcTransfer.getApdu(0, NfcTransfer.MAX_CHUNK + 1)))
        assertNull(r.handle(byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, 0x00)))
        assertNull(r.verdict)
        r.handle(NfcTransfer.doneApdu(true))
        assertEquals(true, r.verdict)
    }

    @Test
    fun `a whole file crosses in slices and verifies`() {
        val r = responder()
        val out = ByteArrayOutputStream()
        var last = -1L
        val got = NfcTransfer.info { r.handle(it)!! }
        assertEquals(header, got)
        val used = NfcTransfer.pull(got!!, 0, 4096, { r.handle(it)!! }, { true }, { out.write(it) }) { have, total -> assertTrue(have > last); last = have; assertEquals(file.size.toLong(), total) }
        assertEquals(4096, used)
        assertContentEquals(file, out.toByteArray())
        assertEquals(header.sha256, NfcTransfer.sha256Hex(out.toByteArray()))
        assertEquals(file.size.toLong(), r.sentUpTo)
    }

    @Test
    fun `a broken tap resumes from where it reached`() {
        val r = responder()
        val out = ByteArrayOutputStream()
        var calls = 0
        // The link dies after 20 slices.
        assertFailsWith<IOException> {
            NfcTransfer.pull(header, 0, 1024, { if (++calls > 20) throw IOException("tag lost") else r.handle(it)!! }, { calls <= 20 }, { out.write(it) })
        }
        val have = out.size().toLong()
        assertEquals(20 * 1024L, have)
        // Next tap: carry on from the bytes we kept.
        NfcTransfer.pull(header, have, 1024, { r.handle(it)!! }, { true }, { out.write(it) })
        assertContentEquals(file, out.toByteArray())
    }

    @Test
    fun `an oversized slice is halved until it fits`() {
        val r = responder()
        val out = ByteArrayOutputStream()
        val limit = 1000 // this "pair of phones" chokes on anything bigger
        val sizes = ArrayList<Int>()
        val used = NfcTransfer.pull(header, 0, NfcTransfer.MAX_CHUNK, { apdu ->
            val c = NfcTransfer.parse(apdu) as NfcTransfer.Command.Get
            if (c.length > limit) throw IOException("frame too big")
            sizes += c.length
            r.handle(apdu)!!
        }, { true }, { out.write(it) })
        assertTrue(used <= limit, "used $used")
        assertTrue(sizes.all { it <= limit })
        assertContentEquals(file, out.toByteArray())
    }

    @Test
    fun `an offer that vanishes is reported, not retried forever`() {
        val out = ByteArrayOutputStream()
        val e = assertFailsWith<NfcTransfer.ProtocolException> {
            NfcTransfer.pull(header, 0, 512, { NfcTransfer.SW_NO_DATA }, { true }, { out.write(it) })
        }
        assertTrue(e.message!!.contains("6a82"))
        assertNull(NfcTransfer.info { NfcTransfer.SW_NO_DATA })
    }
}
