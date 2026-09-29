package dev.cued.app.share.nfc

import android.app.Activity
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.util.Log

/**
 * Reader-mode side of tap-to-share. Also writes/reads plain NDEF tags so a
 * sticker on a speaker or a jacket can hold a track link.
 */
class NfcReader(private val activity: Activity) {
    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    val available: Boolean get() = adapter != null
    val enabled: Boolean get() = adapter?.isEnabled == true

    /** Start listening; [onPayload] receives the decoded string from either a CUEd phone or an NDEF tag. */
    fun startReading(onPayload: (String) -> Unit, onError: (String) -> Unit = {}) {
        val a = adapter ?: return
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
        a.enableReaderMode(activity, { tag ->
            val text = readCued(tag) ?: readNdef(tag)
            if (text != null) activity.runOnUiThread { onPayload(text) }
            else activity.runOnUiThread { onError("Tag has no CUEd payload") }
        }, flags, null)
    }

    fun stopReading() { adapter?.disableReaderMode(activity) }

    /** Enters reader mode just to write [payload] as a URI record to the next tag tapped. */
    fun startWriting(payload: String, onDone: (Result<Unit>) -> Unit) {
        val a = adapter ?: return
        a.enableReaderMode(activity, { tag ->
            val r = runCatching { writeNdef(tag, payload) }
            activity.runOnUiThread { onDone(r) }
        }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V, null)
    }

    private fun readCued(tag: Tag): String? {
        val iso = IsoDep.get(tag) ?: return null
        return runCatching {
            iso.connect()
            iso.timeout = 2_000
            val sel = iso.transceive(CuedHceService.selectApdu())
            if (sel.size < 4 || !ok(sel)) return@runCatching null
            val total = ((sel[0].toInt() and 0xFF) shl 8) or (sel[1].toInt() and 0xFF)
            val out = java.io.ByteArrayOutputStream(total)
            var idx = 0
            while (out.size() < total && idx < 255) {
                val r = iso.transceive(CuedHceService.readApdu(idx))
                if (!ok(r)) break
                out.write(r, 0, r.size - 2)
                idx++
            }
            String(out.toByteArray(), Charsets.UTF_8)
        }.onFailure { Log.w("NfcReader", "iso read failed", it) }.getOrNull().also { runCatching { iso.close() } }
    }

    private fun readNdef(tag: Tag): String? {
        val ndef = Ndef.get(tag) ?: return null
        return runCatching {
            ndef.connect()
            val msg = ndef.ndefMessage ?: return@runCatching null
            msg.records.firstNotNullOfOrNull { rec ->
                when {
                    rec.tnf == NdefRecord.TNF_WELL_KNOWN && rec.type.contentEquals(NdefRecord.RTD_URI) -> rec.toUri()?.toString()
                    rec.tnf == NdefRecord.TNF_WELL_KNOWN && rec.type.contentEquals(NdefRecord.RTD_TEXT) -> textOf(rec.payload)
                    else -> null
                }
            }
        }.getOrNull().also { runCatching { ndef.close() } }
    }

    private fun writeNdef(tag: Tag, payload: String) {
        val msg = NdefMessage(arrayOf(NdefRecord.createUri(payload)))
        Ndef.get(tag)?.let { n ->
            n.connect()
            check(n.isWritable) { "Tag is read-only" }
            check(n.maxSize >= msg.toByteArray().size) { "Tag too small (${n.maxSize} bytes)" }
            n.writeNdefMessage(msg); n.close(); return
        }
        NdefFormatable.get(tag)?.let { f -> f.connect(); f.format(msg); f.close(); return }
        error("Tag does not support NDEF")
    }

    private fun ok(r: ByteArray) = r.size >= 2 && r[r.size - 2] == 0x90.toByte() && r[r.size - 1] == 0x00.toByte()

    private fun textOf(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val langLen = payload[0].toInt() and 0x3F
        val utf16 = payload[0].toInt() and 0x80 != 0
        return String(payload, 1 + langLen, payload.size - 1 - langLen, if (utf16) Charsets.UTF_16 else Charsets.UTF_8)
    }
}
