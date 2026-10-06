package dev.cued.app.share.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/**
 * Tap-to-share without anything running on the receiving phone: this phone
 * pretends to be a standard NFC Forum Type 4 tag holding the share as URL
 * records (cued:// first, so the receiving phone's NFC stack launches CUEd
 * directly; see SharePayloadHolder for why the order matters).
 *
 * Protocol (NFC Forum T4T): SELECT the NDEF application, SELECT the
 * capability container (E103) or the NDEF file (E104), READ BINARY.
 * The older CUEd-only AID is still answered for receivers on previous versions.
 */
class CuedHceService : HostApduService() {
    private var selected: ByteArray? = null

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        val apdu = commandApdu ?: return SW_ERROR
        val ndefFile = SharePayloadHolder.ndefFile ?: return SW_NO_DATA
        return when {
            isSelectByName(apdu, NDEF_AID) -> { selected = null; SW_OK }
            isSelectFile(apdu, CC_ID) -> { selected = capabilityContainer(ndefFile.size); SW_OK }
            isSelectFile(apdu, NDEF_ID) -> { selected = ndefFile; SW_OK }
            isReadBinary(apdu) -> {
                val file = selected ?: return SW_NOT_FOUND
                val offset = ((apdu[2].toInt() and 0xFF) shl 8) or (apdu[3].toInt() and 0xFF)
                val le = if (apdu.size >= 5) (apdu[4].toInt() and 0xFF).let { if (it == 0) 256 else it } else 256
                if (offset >= file.size) SW_WRONG_OFFSET else file.copyOfRange(offset, minOf(file.size, offset + le)) + SW_OK
            }
            // ---- legacy CUEd reader (CUEd ≤ 0.1.21 Receive screen) ----
            isSelectByName(apdu, LEGACY_AID) -> SharePayloadHolder.bytes?.let { byteArrayOf((it.size shr 8).toByte(), (it.size and 0xFF).toByte()) + SW_OK } ?: SW_NO_DATA
            apdu.size >= 4 && apdu[0] == LEGACY_CLA && apdu[1] == INS_READ -> {
                val payload = SharePayloadHolder.bytes ?: return SW_NO_DATA
                val from = (apdu[2].toInt() and 0xFF) * LEGACY_CHUNK
                if (from >= payload.size) SW_OK else payload.copyOfRange(from, minOf(payload.size, from + LEGACY_CHUNK)) + SW_OK
            }
            else -> SW_ERROR
        }
    }

    override fun onDeactivated(reason: Int) { selected = null }

    private fun isSelectByName(apdu: ByteArray, aid: ByteArray) =
        apdu.size >= 5 + aid.size && apdu[0] == 0x00.toByte() && apdu[1] == 0xA4.toByte() && apdu[2] == 0x04.toByte() && apdu.copyOfRange(5, 5 + aid.size).contentEquals(aid)
    private fun isSelectFile(apdu: ByteArray, id: ByteArray) =
        apdu.size >= 7 && apdu[0] == 0x00.toByte() && apdu[1] == 0xA4.toByte() && apdu[2] == 0x00.toByte() && apdu[4] == 0x02.toByte() && apdu[5] == id[0] && apdu[6] == id[1]
    private fun isReadBinary(apdu: ByteArray) = apdu.size >= 4 && apdu[0] == 0x00.toByte() && apdu[1] == 0xB0.toByte()

    /** 15-byte CC: version 2.0, MLe/MLc 255, one NDEF file control TLV (E104, max size, readable, not writable). */
    private fun capabilityContainer(ndefLen: Int): ByteArray {
        val max = maxOf(ndefLen, 0x0400).coerceAtMost(0x7FFF)
        return byteArrayOf(
            0x00, 0x0F, 0x20, 0x00, 0xFF.toByte(), 0x00, 0xFF.toByte(),
            0x04, 0x06, NDEF_ID[0], NDEF_ID[1], (max shr 8).toByte(), (max and 0xFF).toByte(), 0x00, 0xFF.toByte(),
        )
    }

    companion object {
        val NDEF_AID = byteArrayOf(0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01)
        val CC_ID = byteArrayOf(0xE1.toByte(), 0x03)
        val NDEF_ID = byteArrayOf(0xE1.toByte(), 0x04)
        val LEGACY_AID = byteArrayOf(0xF0.toByte(), 0x43, 0x55, 0x45, 0x44) // "CUED"
        const val LEGACY_CLA: Byte = 0x80.toByte()
        const val INS_READ: Byte = 0xB0.toByte()
        const val LEGACY_CHUNK = 250
        val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
        val SW_ERROR = byteArrayOf(0x6F.toByte(), 0x00)
        val SW_NO_DATA = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        val SW_NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        val SW_WRONG_OFFSET = byteArrayOf(0x6B.toByte(), 0x00)

        // Kept for NfcReader's legacy path.
        val AID get() = LEGACY_AID
        fun selectApdu(): ByteArray = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, LEGACY_AID.size.toByte()) + LEGACY_AID + byteArrayOf(0x00)
        fun readApdu(index: Int): ByteArray = byteArrayOf(LEGACY_CLA, INS_READ, index.toByte(), 0x00, 0x00)
    }
}
