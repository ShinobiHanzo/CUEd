package dev.cued.app.share.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.util.Log
import dev.cued.app.Graph
import dev.cued.app.share.OfferedFile
import dev.cued.app.share.PartialFile
import dev.cued.app.ui.LocalFileReceiver
import dev.cued.app.ui.ReceiveStatus
import dev.cued.core.nfc.NfcTransfer
import dev.cued.core.share.SharePayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The reader side of a whole-file NFC transfer (`NfcTransfer`): puts this
 * phone in reader mode, pulls the file the other phone's Share screen offers,
 * verifies it and adds it to the library. Progress and the outcome go to
 * [onStatus] as the share's status, so the same "this just arrived" card
 * shows the bar fill.
 *
 * This is the fallback for when Bluetooth is off or refused: NFC alone is
 * slow, and the phones must stay touching. A tap that breaks off keeps what
 * arrived ([PartialFile]); the next tap carries on from that byte. Reader
 * mode stays on until the copy lands, [stop] is called, or the activity
 * leaves the front.
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
        if (!_active.value) {
            val extras = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250) }
            val ok = runCatching { a.enableReaderMode(activity, { tag -> onTag(tag) }, FLAGS, extras) }.onFailure { Log.w(TAG, "reader mode failed", it) }.isSuccess
            if (!ok) return false
            _active.value = true
        }
        expect?.let { onStatus(it, ReceiveStatus.Direct(0, 0, "Hold the phones back to back, with the other one's Share screen open.", VIA)) }
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
        var part: PartialFile? = null
        try {
            iso.connect()
            iso.timeout = 5_000
            val sel = iso.transceive(NfcTransfer.selectApdu())
            if (!NfcTransfer.isOk(sel)) { _note.value = "The other phone did not answer as CUEd. Is its Share screen open?"; return }
            val header = NfcTransfer.info(iso::transceive)
            if (header == null) {
                val why = "The other phone is not offering a file. On its Share screen, switch \"Include the audio file\" on."
                _note.value = why
                payload?.let { onStatus(it, ReceiveStatus.Failed(why)) }
                return
            }
            // Keep the share we were asked for (its card is on screen); otherwise build one from the header.
            val p = hint?.let { it.copy(link = it.link ?: header.link, genres = it.genres.ifEmpty { header.genres }, bpm = it.bpm ?: header.bpm) }
                ?: SharePayload(title = header.title, artist = header.artist, link = header.link, genres = header.genres, bpm = header.bpm, nfcFile = true)
            payload = p
            total = header.size
            val pf = PartialFile(graph.app, header)
            part = pf
            have = pf.have
            onStatus(p, ReceiveStatus.Direct(have, total, if (have > 0) "Carrying on from where the last attempt stopped. Keep the phones together." else "Keep the phones together until the bar fills.", VIA))
            if (!pf.complete) {
                val cap = NfcTransfer.chunkFor(iso.maxTransceiveLength, iso.isExtendedLengthApduSupported)
                chunk = NfcTransfer.pull(
                    header, pf.have, minOf(chunk, cap), iso::transceive, { iso.isConnected },
                    sink = { b -> pf.append(b); have = pf.have },
                    onProgress = { got, all -> onStatus(p, ReceiveStatus.Direct(got, all, "Keep the phones together until the bar fills.", VIA)) },
                )
            }
            val ok = pf.verify()
            runCatching { iso.transceive(NfcTransfer.doneApdu(ok)) }
            if (!ok) {
                onStatus(p, ReceiveStatus.Failed("The copy did not match the original (hash check). Tap again to start over."))
                return
            }
            onStatus(p, ReceiveStatus.Direct(total, total, "Verified. Adding it to your library…", VIA))
            stop()
            val id = runBlocking { LocalFileReceiver(graph).importFile(p, header.mime, pf.file) }
            pf.file.delete()
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
            onStatus(p, ReceiveStatus.Direct(have, total, "Lost contact$pct. Hold the phones together again to carry on from there.", VIA))
        } catch (e: Exception) {
            Log.w(TAG, "transfer failed", e)
            val p = payload ?: run { _note.value = e.message ?: "Transfer failed"; return }
            onStatus(p, ReceiveStatus.Failed(e.message ?: "Transfer failed"))
        } finally {
            part?.close()
            runCatching { iso.close() }
        }
    }

    companion object {
        private const val TAG = "NfcFileReceiver"
        private const val VIA = OfferedFile.VIA_NFC
        private const val FLAGS = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
    }
}
