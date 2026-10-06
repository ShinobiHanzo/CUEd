package dev.cued.app.share.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the emulated tag hands out while the Share screen is open.
 *
 * Record order matters: Android's NFC dispatcher only looks at the first
 * record. A `cued://` URI there is matched by CUEd alone and launched
 * directly, with no "open this link?" dialog and no browser in the running.
 * An http(s) first record would go down the web-link path instead, which
 * on Android 15 becomes a plain VIEW intent that only browsers (or verified
 * app links, impossible on a project page) can claim. The web URL rides
 * along as a second record for readers that show every record; phones
 * without CUEd use the QR code, which carries the web URL.
 */
object SharePayloadHolder {
    private val _current = MutableStateFlow<String?>(null)
    val current: StateFlow<String?> = _current
    @Volatile var ndefFile: ByteArray? = null
        private set
    /** cued:// form, also served raw to CUEd ≤ 0.1.21 readers over the legacy AID. */
    @Volatile var bytes: ByteArray? = null
        private set

    fun set(cuedUrl: String?, webUrl: String?) {
        _current.value = cuedUrl
        bytes = cuedUrl?.toByteArray(Charsets.UTF_8)
        ndefFile = cuedUrl?.let {
            val records = listOfNotNull(NdefRecord.createUri(it), webUrl?.let(NdefRecord::createUri))
            val msg = NdefMessage(records.toTypedArray()).toByteArray()
            byteArrayOf((msg.size shr 8).toByte(), (msg.size and 0xFF).toByte()) + msg // NLEN + message
        }
    }
}
