package dev.cued.core.nfc

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.security.MessageDigest

/**
 * Whole-file transfer over NFC alone: no Wi-Fi, no hotspot, no internet.
 *
 * The sending phone is an NFC card (host card emulation, the CUEd AID
 * `F043554544` it has always owned) and the receiving phone is the reader.
 * The reader pulls the file in slices with plain ISO 7816 commands, so the
 * two sides never need an address for each other; being touched is the
 * address. NFC is slow (roughly 5–30 KB/s phone to phone), so a song takes
 * minutes, which is why the protocol is resumable: a tap that breaks off is
 * continued from the byte it reached on the next tap.
 *
 * Commands (CLA 80):
 *
 * | INS | data            | answer                                  |
 * |-----|-----------------|-----------------------------------------|
 * | C0  | –               | header JSON ([NfcFileHeader]) + 9000    |
 * | C1  | offset u32, len u16 | the bytes at that range + 9000      |
 * | C2  | P1 = 1 ok / 0 bad | 9000, the receiver's verdict          |
 *
 * `6A82` means nothing is on offer (the Share screen is not open), `6B00`
 * an offset past the end, `6700` a malformed request.
 *
 * Everything in here is pure JVM so the exchange is tested end to end
 * without a radio ([Responder] on one side, [pull] on the other).
 */
@Serializable
data class NfcFileHeader(
    val v: Int = 1,
    /** Hex sha256 of the whole file: the receiver verifies before keeping it, and resumes only a file with the same hash. */
    val sha256: String,
    val size: Long,
    val mime: String,
    val title: String,
    val artist: String,
    val link: String? = null,
    val genres: List<String> = emptyList(),
    val bpm: Float? = null,
)

object NfcTransfer {
    /** The CUEd application identifier (statically registered by the app's HCE service). */
    const val AID_HEX = "F043554544"
    val AID: ByteArray = byteArrayOf(0xF0.toByte(), 0x43, 0x55, 0x45, 0x44)

    const val CLA: Byte = 0x80.toByte()
    const val INS_INFO: Byte = 0xC0.toByte()
    const val INS_GET: Byte = 0xC1.toByte()
    const val INS_DONE: Byte = 0xC2.toByte()

    /** Smallest slice worth asking for; below this the per-command overhead dominates and something is badly wrong. */
    const val MIN_CHUNK = 128
    /** Largest slice: bigger ones gain nothing and make a dropped frame costlier. */
    const val MAX_CHUNK = 16 * 1024
    /** What a reader without extended-length support can take back in one short response. */
    const val SHORT_CHUNK = 250

    val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
    val SW_NO_DATA = byteArrayOf(0x6A.toByte(), 0x82.toByte())
    val SW_WRONG_OFFSET = byteArrayOf(0x6B.toByte(), 0x00)
    val SW_WRONG_LENGTH = byteArrayOf(0x67.toByte(), 0x00)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    fun encodeHeader(h: NfcFileHeader): ByteArray = json.encodeToString(h).toByteArray(Charsets.UTF_8)
    fun decodeHeader(bytes: ByteArray): NfcFileHeader? =
        runCatching { json.decodeFromString<NfcFileHeader>(String(bytes, Charsets.UTF_8)) }.getOrNull()?.takeIf { it.size >= 0 && it.sha256.length == 64 }

    /** The slice size to ask for given what the reader's NFC stack can carry in one exchange. */
    fun chunkFor(maxTransceiveLength: Int, extendedLength: Boolean): Int {
        val room = maxTransceiveLength - 2 // status word
        val cap = if (extendedLength) MAX_CHUNK else SHORT_CHUNK
        return room.coerceAtMost(cap).coerceAtLeast(MIN_CHUNK)
    }

    // ---- command APDUs (reader side) ----

    fun selectApdu(): ByteArray = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, AID.size.toByte()) + AID + byteArrayOf(0x00)
    fun infoApdu(): ByteArray = byteArrayOf(CLA, INS_INFO, 0x00, 0x00, 0x00)
    fun getApdu(offset: Long, length: Int): ByteArray {
        require(offset in 0..0xFFFF_FFFFL) { "offset" }
        require(length in 1..0xFFFF) { "length" }
        return byteArrayOf(
            CLA, INS_GET, 0x00, 0x00, 0x06,
            (offset ushr 24).toByte(), (offset ushr 16).toByte(), (offset ushr 8).toByte(), offset.toByte(),
            (length ushr 8).toByte(), length.toByte(),
            0x00,
        )
    }
    fun doneApdu(ok: Boolean): ByteArray = byteArrayOf(CLA, INS_DONE, if (ok) 0x01 else 0x00, 0x00, 0x00)

    // ---- command parsing (card side) ----

    sealed class Command {
        data object Info : Command()
        data class Get(val offset: Long, val length: Int) : Command()
        data class Done(val ok: Boolean) : Command()
        /** Ours (CLA 80, a transfer INS) but malformed. */
        data object Malformed : Command()
    }

    /** Null when the APDU is not a transfer command at all (another AID's business). */
    fun parse(apdu: ByteArray): Command? {
        if (apdu.size < 4 || apdu[0] != CLA) return null
        return when (apdu[1]) {
            INS_INFO -> Command.Info
            INS_DONE -> Command.Done(apdu[2] == 0x01.toByte())
            INS_GET -> {
                if (apdu.size < 11 || apdu[4] != 0x06.toByte()) return Command.Malformed
                val offset = ((apdu[5].toLong() and 0xFF) shl 24) or ((apdu[6].toLong() and 0xFF) shl 16) or ((apdu[7].toLong() and 0xFF) shl 8) or (apdu[8].toLong() and 0xFF)
                val length = ((apdu[9].toInt() and 0xFF) shl 8) or (apdu[10].toInt() and 0xFF)
                if (length == 0) Command.Malformed else Command.Get(offset, length)
            }
            else -> null
        }
    }

    fun isOk(response: ByteArray): Boolean =
        response.size >= 2 && response[response.size - 2] == 0x90.toByte() && response[response.size - 1] == 0x00.toByte()

    fun body(response: ByteArray): ByteArray = response.copyOfRange(0, response.size - 2)

    /**
     * The card side: answers transfer commands for one file. [read] hands back
     * the bytes at an offset (fewer at the end of the file). Progress is the
     * highest byte the reader has asked for, which is what the sender's screen
     * shows.
     */
    class Responder(val header: NfcFileHeader, private val read: (offset: Long, length: Int) -> ByteArray) {
        private val headerBytes = encodeHeader(header)
        @Volatile var sentUpTo: Long = 0
            private set
        /** Null until the reader reports; then whether its copy verified. */
        @Volatile var verdict: Boolean? = null
            private set

        fun handle(apdu: ByteArray): ByteArray? = when (val c = parse(apdu) ?: return null) {
            Command.Info -> headerBytes + SW_OK
            is Command.Get -> when {
                c.offset >= header.size -> SW_WRONG_OFFSET
                c.length > MAX_CHUNK -> SW_WRONG_LENGTH
                else -> {
                    val len = minOf(c.length.toLong(), header.size - c.offset).toInt()
                    val bytes = read(c.offset, len)
                    sentUpTo = maxOf(sentUpTo, c.offset + bytes.size)
                    bytes + SW_OK
                }
            }
            is Command.Done -> { verdict = c.ok; SW_OK }
            Command.Malformed -> SW_WRONG_LENGTH
        }
    }

    /** Thrown when the card answers but not with what was asked: the offer changed under us or is gone. */
    class ProtocolException(message: String) : IOException(message)

    /** Asks the card what it offers. Null when nothing is on offer (its Share screen is closed). */
    fun info(transceive: (ByteArray) -> ByteArray): NfcFileHeader? {
        val r = transceive(infoApdu())
        if (!isOk(r)) return null
        return decodeHeader(body(r)) ?: throw ProtocolException("The other phone sent a header this version cannot read")
    }

    /**
     * Pulls bytes [from] to the end of the file into [sink], asking for [chunk]
     * bytes at a time. An [IOException] from [transceive] while [connected]
     * still holds is taken as "that slice was too big for this pair of phones"
     * and the slice is halved; once the link is really gone the exception
     * propagates, and the caller resumes from what [sink] received. Returns the
     * slice size that worked, for the next tap.
     */
    fun pull(
        header: NfcFileHeader,
        from: Long,
        chunk: Int,
        transceive: (ByteArray) -> ByteArray,
        connected: () -> Boolean,
        sink: (ByteArray) -> Unit,
        onProgress: (have: Long, total: Long) -> Unit = { _, _ -> },
    ): Int {
        var have = from
        var size = chunk.coerceIn(MIN_CHUNK, MAX_CHUNK)
        onProgress(have, header.size)
        while (have < header.size) {
            val want = minOf(size.toLong(), header.size - have).toInt()
            val r = try {
                transceive(getApdu(have, want))
            } catch (e: IOException) {
                if (e is ProtocolException || !connected() || size <= MIN_CHUNK) throw e
                size = maxOf(MIN_CHUNK, size / 2)
                continue
            }
            if (!isOk(r)) throw ProtocolException("The other phone stopped offering the file (status ${hex(r.takeLast(2))})")
            val b = body(r)
            if (b.isEmpty()) throw ProtocolException("The other phone sent an empty slice")
            if (b.size > want) throw ProtocolException("The other phone sent more than asked")
            sink(b)
            have += b.size
            onProgress(have, header.size)
        }
        return size
    }

    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun hex(b: List<Byte>) = b.joinToString("") { "%02x".format(it) }
}
