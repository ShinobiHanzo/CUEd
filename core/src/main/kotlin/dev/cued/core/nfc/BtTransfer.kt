package dev.cued.core.nfc

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Whole-file transfer over a Bluetooth stream, with the tap (or the QR code)
 * as the introduction: the share payload carries a one-time token, the
 * sending phone advertises that token over Bluetooth LE together with the
 * channel it listens on, and the receiving phone finds the advert, connects
 * and pulls the file. No pairing, no Wi-Fi, no internet; only a phone that
 * was handed the share knows the token.
 *
 * On the wire (an L2CAP connection-oriented channel on Android, but any
 * reliable byte stream will do):
 *
 * ```
 * reader → sender   "CUBT" | version 1 | token (8 bytes)
 * sender → reader   status (0 ok, 1 refused) | header length u16 | header JSON (NfcFileHeader)
 * reader → sender   offset u64          where to start: what an earlier attempt already has
 * sender → reader   the file's bytes from that offset to the end
 * reader → sender   verdict (1 the copy verified, 0 it did not)
 * ```
 *
 * The header and the resume rule are the ones [NfcTransfer] uses, so a copy
 * begun over one radio can be finished over the other.
 */
object BtTransfer {
    /** Bluetooth SIG's "for testing" company id; the two magic bytes keep strangers' adverts out. */
    const val MANUFACTURER_ID = 0xFFFF
    const val TOKEN_BYTES = 8
    const val VERSION: Byte = 1
    private val MAGIC = byteArrayOf('C'.code.toByte(), 'U'.code.toByte(), 'B'.code.toByte(), 'T'.code.toByte())
    private val ADVERT_MAGIC = byteArrayOf('C'.code.toByte(), 'U'.code.toByte())
    private const val MAX_HEADER = 16 * 1024

    fun newToken(random: SecureRandom = SecureRandom()): String =
        ByteArray(TOKEN_BYTES).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    fun isToken(text: String?): Boolean = text != null && text.length == TOKEN_BYTES * 2 && text.all { Character.digit(it, 16) >= 0 }

    private fun tokenBytes(token: String): ByteArray {
        require(isToken(token)) { "token" }
        return ByteArray(TOKEN_BYTES) { i -> token.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    }

    // ---- the advert: "CU" | token | psm u16 ----

    /** Manufacturer data the sender advertises: which share this is and the channel (PSM) to connect to. */
    fun advert(token: String, psm: Int): ByteArray {
        require(psm in 1..0xFFFF) { "psm" }
        return ADVERT_MAGIC + tokenBytes(token) + byteArrayOf((psm ushr 8).toByte(), psm.toByte())
    }

    /** What a scan filter should match exactly (the mask covers the magic and the token, not the channel). */
    fun advertFilter(token: String): Pair<ByteArray, ByteArray> {
        val data = ADVERT_MAGIC + tokenBytes(token) + byteArrayOf(0, 0)
        val mask = ByteArray(data.size) { if (it < data.size - 2) 0xFF.toByte() else 0 }
        return data to mask
    }

    /** The channel in an advert for [token], or null when the advert is someone else's. */
    fun psmOf(advert: ByteArray?, token: String): Int? {
        if (advert == null || advert.size < ADVERT_MAGIC.size + TOKEN_BYTES + 2 || !isToken(token)) return null
        val head = ADVERT_MAGIC + tokenBytes(token)
        if (!MessageDigest.isEqual(advert.copyOfRange(0, head.size), head)) return null
        val psm = ((advert[head.size].toInt() and 0xFF) shl 8) or (advert[head.size + 1].toInt() and 0xFF)
        return psm.takeIf { it > 0 }
    }

    // ---- sender ----

    /**
     * Serves one connection. Returns the reader's verdict (null when it left
     * without one). A connection without this share's token is told so and
     * then fails with a [NfcTransfer.ProtocolException]. [packet] is the
     * largest single write; [onProgress] gets the highest byte sent.
     */
    fun serve(
        input: InputStream,
        output: OutputStream,
        token: String,
        header: NfcFileHeader,
        read: (offset: Long, length: Int) -> ByteArray,
        packet: Int = 4096,
        onProgress: (sent: Long) -> Unit = {},
    ): Boolean? {
        val hello = readFully(input, MAGIC.size + 1 + TOKEN_BYTES)
        val okMagic = MessageDigest.isEqual(hello.copyOfRange(0, MAGIC.size), MAGIC) && hello[MAGIC.size] == VERSION
        val okToken = MessageDigest.isEqual(hello.copyOfRange(MAGIC.size + 1, hello.size), tokenBytes(token))
        if (!okMagic || !okToken) {
            output.write(byteArrayOf(1, 0, 0)); output.flush()
            throw NfcTransfer.ProtocolException("refused a connection that did not present this share's token")
        }
        val h = NfcTransfer.encodeHeader(header)
        check(h.size <= MAX_HEADER) { "header too large" }
        output.write(byteArrayOf(0, (h.size ushr 8).toByte(), h.size.toByte()) + h)
        output.flush()
        val o = readFully(input, 8)
        var offset = 0L
        for (b in o) offset = (offset shl 8) or (b.toLong() and 0xFF)
        if (offset < 0 || offset > header.size) throw NfcTransfer.ProtocolException("The other phone asked for a byte past the end")
        val step = packet.coerceIn(64, 64 * 1024)
        onProgress(offset)
        while (offset < header.size) {
            val bytes = read(offset, minOf(step.toLong(), header.size - offset).toInt())
            if (bytes.isEmpty()) throw IOException("the file ended early")
            output.write(bytes)
            offset += bytes.size
            onProgress(offset)
        }
        output.flush()
        val v = try { input.read() } catch (e: IOException) { -1 }
        return if (v < 0) null else v == 1
    }

    // ---- reader ----

    /** Says hello with the token and returns what the sender offers. */
    fun hello(input: InputStream, output: OutputStream, token: String): NfcFileHeader {
        output.write(MAGIC + byteArrayOf(VERSION) + tokenBytes(token))
        output.flush()
        val head = readFully(input, 3)
        if (head[0] != 0.toByte()) throw NfcTransfer.ProtocolException("The other phone refused: this share is no longer on its screen")
        val len = ((head[1].toInt() and 0xFF) shl 8) or (head[2].toInt() and 0xFF)
        if (len == 0 || len > MAX_HEADER) throw NfcTransfer.ProtocolException("The other phone sent a header this version cannot read")
        return NfcTransfer.decodeHeader(readFully(input, len)) ?: throw NfcTransfer.ProtocolException("The other phone sent a header this version cannot read")
    }

    /** Asks for the bytes from [from] and feeds them to [sink] as they arrive; an [IOException] means the link dropped and [sink] has what came. */
    fun pull(
        input: InputStream,
        output: OutputStream,
        header: NfcFileHeader,
        from: Long,
        sink: (ByteArray, Int) -> Unit,
        onProgress: (have: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        require(from in 0..header.size) { "from" }
        output.write(ByteArray(8) { i -> (from ushr (8 * (7 - i))).toByte() })
        output.flush()
        var have = from
        val buf = ByteArray(16 * 1024)
        onProgress(have, header.size)
        while (have < header.size) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), header.size - have).toInt())
            if (n < 0) throw EOFException("the other phone closed the link at $have of ${header.size}")
            if (n == 0) continue
            sink(buf, n)
            have += n
            onProgress(have, header.size)
        }
    }

    fun verdict(output: OutputStream, ok: Boolean) {
        output.write(if (ok) 1 else 0)
        output.flush()
    }

    private fun readFully(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(out, got, n - got)
            if (r < 0) throw EOFException("the link closed")
            got += r
        }
        return out
    }
}
