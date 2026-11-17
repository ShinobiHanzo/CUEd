package dev.cued.core

import dev.cued.core.nfc.BtTransfer
import dev.cued.core.nfc.NfcFileHeader
import dev.cued.core.nfc.NfcTransfer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BtTransferTest {
    private val file = Random(11).nextBytes(500_000 + 77)
    private val header = NfcFileHeader(sha256 = NfcTransfer.sha256Hex(file), size = file.size.toLong(), mime = "audio/flac", title = "Tide", artist = "Moon")
    private val token = "0123456789abcdef"

    /** Two pipes make a link; the sender runs on its own thread like the accept loop does. */
    private class Link {
        val toSender = PipedOutputStream(); val senderIn = PipedInputStream(toSender, 64 * 1024)
        val toReader = PipedOutputStream(); val readerIn = PipedInputStream(toReader, 64 * 1024)
    }

    private fun serveOn(link: Link, tok: String = token, progress: (Long) -> Unit = {}): CompletableFuture<Boolean?> {
        val f = CompletableFuture<Boolean?>()
        thread(isDaemon = true) {
            try { f.complete(BtTransfer.serve(link.senderIn, link.toReader, tok, header, { off, len -> file.copyOfRange(off.toInt(), off.toInt() + len) }, 990, progress)) }
            catch (e: Throwable) { f.completeExceptionally(e) }
            finally { runCatching { link.toReader.close() } }
        }
        return f
    }

    @Test
    fun `tokens and adverts`() {
        val t = BtTransfer.newToken()
        assertTrue(BtTransfer.isToken(t))
        assertFalse(BtTransfer.isToken("xyz")); assertFalse(BtTransfer.isToken(null)); assertFalse(BtTransfer.isToken(t + "00"))
        val adv = BtTransfer.advert(t, 0x0081)
        assertEquals(12, adv.size) // fits a legacy advert next to the flags
        assertEquals(0x0081, BtTransfer.psmOf(adv, t))
        assertNull(BtTransfer.psmOf(adv, token))
        assertNull(BtTransfer.psmOf(byteArrayOf(1, 2, 3), t))
        assertNull(BtTransfer.psmOf(null, t))
        val (data, mask) = BtTransfer.advertFilter(t)
        assertEquals(adv.size, data.size)
        for (i in adv.indices) assertEquals(data[i].toInt() and mask[i].toInt(), adv[i].toInt() and mask[i].toInt())
        assertEquals(0, mask[mask.size - 1].toInt()); assertEquals(0, mask[mask.size - 2].toInt())
    }

    @Test
    fun `a whole file crosses and both sides agree`() {
        val link = Link()
        var sent = 0L
        val verdict = serveOn(link) { sent = it }
        val got = BtTransfer.hello(link.readerIn, link.toSender, token)
        assertEquals(header, got)
        val out = ByteArrayOutputStream()
        var last = -1L
        BtTransfer.pull(link.readerIn, link.toSender, got, 0, { b, n -> out.write(b, 0, n) }) { have, total -> assertTrue(have >= last); last = have; assertEquals(file.size.toLong(), total) }
        assertContentEquals(file, out.toByteArray())
        BtTransfer.verdict(link.toSender, NfcTransfer.sha256Hex(out.toByteArray()) == got.sha256)
        assertEquals(true, verdict.get(5, TimeUnit.SECONDS))
        assertEquals(file.size.toLong(), sent)
    }

    @Test
    fun `a dropped link resumes from the byte it reached`() {
        val out = ByteArrayOutputStream()
        // First attempt: the reader's side of the link dies after ~120 KB.
        run {
            val link = Link()
            serveOn(link)
            val h = BtTransfer.hello(link.readerIn, link.toSender, token)
            val flaky = object : InputStream() {
                var left = 120_000
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (left <= 0) throw IOException("link lost")
                    val n = link.readerIn.read(b, off, minOf(len, left)); left -= n; return n
                }
            }
            assertFailsWith<IOException> { BtTransfer.pull(flaky, link.toSender, h, 0, { b, n -> out.write(b, 0, n) }) }
            link.readerIn.close()
        }
        assertEquals(120_000, out.size())
        // Second attempt: a new link, carry on.
        val link = Link()
        val verdict = serveOn(link)
        val h = BtTransfer.hello(link.readerIn, link.toSender, token)
        BtTransfer.pull(link.readerIn, link.toSender, h, out.size().toLong(), { b, n -> out.write(b, 0, n) })
        assertContentEquals(file, out.toByteArray())
        BtTransfer.verdict(link.toSender, true)
        assertEquals(true, verdict.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `the wrong token is refused before anything is sent`() {
        val link = Link()
        val verdict = serveOn(link)
        val e = assertFailsWith<NfcTransfer.ProtocolException> { BtTransfer.hello(link.readerIn, link.toSender, "ffffffffffffffff") }
        assertTrue(e.message!!.contains("refused"))
        val served = assertFailsWith<java.util.concurrent.ExecutionException> { verdict.get(5, TimeUnit.SECONDS) }
        assertTrue(served.cause is NfcTransfer.ProtocolException)
    }

    @Test
    fun `a reader that leaves without a verdict is not counted as verified`() {
        val link = Link()
        val verdict = serveOn(link)
        val h = BtTransfer.hello(link.readerIn, link.toSender, token)
        BtTransfer.pull(link.readerIn, link.toSender, h, file.size - 10L, { _, _ -> })
        link.toSender.close()
        assertNull(verdict.get(5, TimeUnit.SECONDS))
    }
}
