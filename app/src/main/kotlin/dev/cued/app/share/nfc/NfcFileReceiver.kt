package dev.cued.app.share.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.util.Log
import dev.cued.app.Graph
import dev.cued.app.ui.LocalFileReceiver
import dev.cued.app.ui.ReceiveStatus
import dev.cued.core.crypto.toHex
import dev.cued.core.nfc.NfcTransfer
import dev.cued.core.share.SharePayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The reader side of a whole-file NFC transfer (`NfcTransfer`): puts this
 * phone in reader mode, pulls the file the other phone's Share screen offers,
 * verifies it and adds it to the library. Progress and the outcome go to
 * [onStatus] as the share's status, so the same "this just arrived" card
 * shows the bar fill.
 *
 * A tap that breaks off keeps what arrived under the file's hash in the cache
 * directory; the next tap carries on from that byte. Reader mode stays on
 * until the copy lands, [stop] is called, or the activity leaves the front.
 */
class NfcFileReceiver(private val activity: Activity, private val graph: Graph, private val onStatus: (SharePayload, ReceiveStatus) -> Unit) {
    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    val available: Boolean get() = adapter != null
    val enabled: Boolean get() = adapter?.isEnabled == true

    private val _active = MutableStateFlow(false)
    /** True while this phone is in reader mode waiting for, or pulling, a file. */
    val active: StateFlow<Boolean> = _active
    private val busy = AtomicBoolean(false)
    @Volatile private var hint: SharePayload? = null
    /** The slice size that worked last; a pair of phones that choked on big slices is not asked again. */
    @Volatile private var chunk = NfcTransfer.MAX_CHUNK

    private val _note = MutableStateFlow<String?>(null)
    /** What happened at the last tap when there was no share to pin it to (a sticker, a wallet, a phone with nothing on offer). */
    val note: StateFlow<String?> = _note

    /** Enters reader mode. [expect] is the share whose file we are after, if known; its card shows the progress. */
    fun start(expect: SharePayload?): Boolean {
        val a = adapter ?: return false
        if (!a.isEnabled) return false
        hint = expect
        _note.value = null
        expect?.let { onStatus(it, ReceiveStatus.Nfc(0, 0, "Hold the phones back to back, with the other one's Share screen open.")) }
        if (_active.value) return true
        _active.value = true
        val extras = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250) }
        runCatching { a.enableReaderMode(activity, { tag -> onTag(tag) }, FLAGS, extras) }
            .onFailure { Log.w(TAG, "reader mode failed", it); _active.value = false; return false }
        return true
    }

    fun stop() {
        if (!_active.value) return
        _active.value = false
        runCatching { adapter?.disableReaderMode(activity) }
    }

    private fun onTag(tag: Tag) {
        if (!busy.compareAndSet(false, true)) return
        try { receive(tag) } finally { busy.set(false) }
    }

    /** Runs on the NFC callback thread: all the I/O happens right here, while the phones touch. */
    private fun receive(tag: Tag) {
        val iso = IsoDep.get(tag)
        if (iso == null) { _note.value = "That is not a CUEd phone (a sticker or a card). Hold it against a phone with its Share screen open."; return }
        var payload: SharePayload? = hint
        var have = 0L
        var total = 0L
        try {
            iso.connect()
            iso.timeout = 5_000
            val sel = iso.transceive(NfcTransfer.selectApdu())
            if (!NfcTransfer.isOk(sel)) { _note.value = "The other phone did not answer as CUEd. Is its Share screen open?"; return }
            val header = NfcTransfer.info(iso::transceive)
            if (header == null) {
                _note.value = "The other phone is not offering a file. On its Share screen, switch \"Include the audio file\" on."
                payload?.let { onStatus(it, ReceiveStatus.Failed(_note.value!!)) }
                return
            }
            val p = SharePayload(title = header.title, artist = header.artist, link = header.link ?: hint?.link, genres = header.genres, bpm = header.bpm, nfcFile = true)
            payload = p
            total = header.size
            val dir = File(graph.app.cacheDir, "nfc").apply { mkdirs() }
            val part = File(dir, header.sha256 + ".part")
            if (part.exists() && part.length() > header.size) part.delete()
            // Hash what an earlier tap left behind, so the final check still covers every byte.
            val md = MessageDigest.getInstance("SHA-256")
            if (part.exists()) part.inputStream().use { s ->
                val b = ByteArray(64 * 1024)
                while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) }
            }
            have = part.length()
            onStatus(p, ReceiveStatus.Nfc(have, total, if (have > 0) "Carrying on from where the last tap stopped. Keep the phones together." else "Keep the phones together until the bar fills."))
            if (have < total) {
                val cap = NfcTransfer.chunkFor(iso.maxTransceiveLength, iso.isExtendedLengthApduSupported)
                FileOutputStream(part, true).use { out ->
                    chunk = NfcTransfer.pull(
                        header, have, minOf(chunk, cap), iso::transceive, { iso.isConnected },
                        sink = { b -> out.write(b); md.update(b); have += b.size },
                        onProgress = { got, all -> onStatus(p, ReceiveStatus.Nfc(got, all, "Keep the phones together until the bar fills.")) },
                    )
                }
            }
            val ok = md.digest().toHex() == header.sha256
            runCatching { iso.transceive(NfcTransfer.doneApdu(ok)) }
            if (!ok) {
                part.delete()
                onStatus(p, ReceiveStatus.Failed("The copy did not match the original (hash check). Tap again to start over."))
                return
            }
            onStatus(p, ReceiveStatus.Nfc(total, total, "Verified. Adding it to your library…"))
            stop()
            val id = runBlocking { LocalFileReceiver(graph).importFile(p, header.mime, part) }
            part.delete()
            onStatus(p, if (id != null) ReceiveStatus.Done(id) else ReceiveStatus.Failed("The file arrived but the library could not see it"))
        } catch (e: NfcTransfer.ProtocolException) {
            Log.w(TAG, "transfer refused", e)
            val p = payload ?: run { _note.value = e.message; return }
            onStatus(p, ReceiveStatus.Failed(e.message ?: "Transfer failed"))
        } catch (e: IOException) {
            // TagLostException included: the phones parted. What arrived is kept; the next tap resumes.
            Log.i(TAG, "contact lost at $have/$total")
            val p = payload ?: run { _note.value = "Contact was lost before anything was read. Hold the phones still and try again."; return }
            val pct = if (total > 0) " at ${(have * 100 / total)}%" else ""
            onStatus(p, ReceiveStatus.Nfc(have, total, "Lost contact$pct. Hold the phones together again to carry on from there."))
        } catch (e: Exception) {
            Log.w(TAG, "transfer failed", e)
            val p = payload ?: run { _note.value = e.message ?: "Transfer failed"; return }
            onStatus(p, ReceiveStatus.Failed(e.message ?: "Transfer failed"))
        } finally {
            runCatching { iso.close() }
        }
    }

    companion object {
        private const val TAG = "NfcFileReceiver"
        private const val FLAGS = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
    }
}
