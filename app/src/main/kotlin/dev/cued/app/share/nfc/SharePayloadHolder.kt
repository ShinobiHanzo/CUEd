package dev.cued.app.share.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the emulated tag hands out while the Share screen is open: the share URL as an NDEF URI record. */
object SharePayloadHolder {
    private val _current = MutableStateFlow<String?>(null)
    val current: StateFlow<String?> = _current
    @Volatile var ndefFile: ByteArray? = null
        private set
    /** Legacy cued:// form for old receivers. */
    @Volatile var bytes: ByteArray? = null
        private set

    /** [webUrl] goes into the tag; [legacy] (cued://…) serves CUEd ≤ 0.1.21 readers. */
    fun set(webUrl: String?, legacy: String? = null) {
        _current.value = webUrl
        bytes = legacy?.toByteArray(Charsets.UTF_8)
        ndefFile = webUrl?.let {
            val msg = NdefMessage(arrayOf(NdefRecord.createUri(it))).toByteArray()
            byteArrayOf((msg.size shr 8).toByte(), (msg.size and 0xFF).toByte()) + msg // NLEN + message
        }
    }
}
