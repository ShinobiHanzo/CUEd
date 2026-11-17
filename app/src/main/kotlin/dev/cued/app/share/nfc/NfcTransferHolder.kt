package dev.cued.app.share.nfc

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
 * The file the Share screen offers over NFC alone, kept where the HCE
 * service (which answers on the main thread, one APDU at a time) can slice
 * it without opening anything: the file is memory-mapped once, hashed once,
 * and released when the Share screen closes.
 */
object NfcTransferHolder {
    private const val TAG = "NfcTransfer"

    data class Progress(val sent: Long, val total: Long, val verdict: Boolean?) {
        val fraction: Float get() = if (total <= 0) 0f else (sent.toDouble() / total).toFloat().coerceIn(0f, 1f)
    }

    @Volatile var responder: NfcTransfer.Responder? = null
        private set
    private var pfd: ParcelFileDescriptor? = null
    private var channel: FileChannel? = null
    private var map: ByteBuffer? = null

    private val _progress = MutableStateFlow<Progress?>(null)
    /** Null when nothing is offered; otherwise how far the reader has pulled. Updated as slices go out. */
    val progress: StateFlow<Progress?> = _progress

    /** Maps and hashes the track's file; call off the main thread. Returns false (and offers nothing) when the file cannot be read. */
    private var offeredUri: String? = null

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
            val r = NfcTransfer.Responder(header) { off, len ->
                val out = ByteArray(len)
                synchronized(this) { buf.duplicate().apply { position(off.toInt()) }.get(out) }
                _progress.value = Progress(maxOf(_progress.value?.sent ?: 0L, off + len), size, _progress.value?.verdict)
                out
            }
            pfd = fd; channel = ch; map = buf; responder = r; offeredUri = track.uri
            _progress.value = Progress(0, size, null)
            true
        }.onFailure { Log.w(TAG, "cannot offer ${track.title} over NFC", it); clear() }.getOrDefault(false)
    }

    /** Called by the HCE service once the reader has said whether its copy verified. */
    fun noteVerdict(ok: Boolean) { _progress.value = _progress.value?.copy(verdict = ok) }

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
