package dev.cued.app.share

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import dev.cued.app.data.db.TrackEntity
import dev.cued.core.nfc.NfcFileHeader
import dev.cued.core.nfc.NfcTransfer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

/**
 * The file the Share screen hands out without a network: over Bluetooth
 * (`BtFileSender`) or over NFC alone (the HCE service, which answers on the
 * main thread one APDU at a time). It is memory-mapped once and hashed once,
 * so either radio slices it without opening anything, and released when the
 * Share screen closes.
 */
object OfferedFile {
    private const val TAG = "OfferedFile"
    const val VIA_BLUETOOTH = "Bluetooth"
    const val VIA_NFC = "NFC"

    data class Progress(val sent: Long, val total: Long, val verdict: Boolean?, val via: String?) {
        val fraction: Float get() = if (total <= 0) 0f else (sent.toDouble() / total).toFloat().coerceIn(0f, 1f)
    }

    /** Answers the NFC transfer commands for the offered file; null when nothing is offered. */
    @Volatile var responder: NfcTransfer.Responder? = null
        private set
    @Volatile private var map: ByteBuffer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var channel: FileChannel? = null
    private var offeredUri: String? = null

    val header: NfcFileHeader? get() = responder?.header

    private val _progress = MutableStateFlow<Progress?>(null)
    /** Null when nothing is offered; otherwise how far a reader has pulled, and over which radio. */
    val progress: StateFlow<Progress?> = _progress

    /** Maps and hashes the track's file; call off the main thread. Returns false (and offers nothing) when the file cannot be read. */
    @Synchronized
    fun offer(context: Context, track: TrackEntity, genres: List<String>): Boolean {
        if (responder != null && offeredUri == track.uri) return true // the same file is already mapped and hashed
        clear()
        return runCatching {
            val uri = Uri.parse(track.uri)
            val mime = context.contentResolver.getType(uri) ?: "audio/mpeg"
            val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("cannot open ${track.uri}")
            val ch = FileInputStream(fd.fileDescriptor).channel
            val size = ch.size()
            require(size in 1..0xFFFF_FFFFL) { "size $size" }
            val buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, size)
            val md = MessageDigest.getInstance("SHA-256")
            md.update(buf.duplicate())
            val header = NfcFileHeader(
                sha256 = md.digest().joinToString("") { "%02x".format(it) }, size = size, mime = mime,
                title = track.title, artist = track.artist, link = track.sourceLink, genres = genres, bpm = track.bpm,
            )
            pfd = fd; channel = ch; map = buf; offeredUri = track.uri
            _progress.value = Progress(0, size, null, null)
            responder = NfcTransfer.Responder(header) { off, len -> read(off, len).also { noteSent(off + it.size, VIA_NFC) } }
            true
        }.onFailure { Log.w(TAG, "cannot offer ${track.title}", it); clear() }.getOrDefault(false)
    }

    /** The bytes at [offset] (fewer at the end of the file, none when nothing is offered). Safe from any thread. */
    fun read(offset: Long, length: Int): ByteArray {
        val buf = map ?: return ByteArray(0)
        val view = buf.duplicate() // own position; the mapping itself is shared and read-only
        if (offset < 0 || offset >= view.limit()) return ByteArray(0)
        view.position(offset.toInt())
        val out = ByteArray(minOf(length, view.remaining()))
        view.get(out)
        return out
    }

    fun noteSent(sent: Long, via: String) {
        val p = _progress.value ?: return
        _progress.value = p.copy(sent = if (via == p.via) maxOf(p.sent, sent) else sent, via = via, verdict = if (via == p.via) p.verdict else null)
    }

    /** The reader has said whether its copy verified. */
    fun noteVerdict(ok: Boolean, via: String) { _progress.value = _progress.value?.copy(verdict = ok, via = via) }

    @Synchronized
    fun clear() {
        responder = null
        offeredUri = null
        map = null
        runCatching { channel?.close() }; channel = null
        runCatching { pfd?.close() }; pfd = null
        _progress.value = null
    }
}
