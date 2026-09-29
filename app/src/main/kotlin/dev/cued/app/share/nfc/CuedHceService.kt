package dev.cued.app.share.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/**
 * Phone-to-phone NFC on modern Android: Android Beam is gone, so the sender
 * emulates a card and the receiver reads it. Protocol:
 *
 *   SELECT AID  (00 A4 04 00 05 F0 43 55 45 44)  -> 2-byte total length + 90 00
 *   READ chunk  (80 B0 <idx> 00 00)              -> up to 250 bytes of payload + 90 00
 *
 * The payload is the `cued://share?...` string. Tiny, no pairing, no Wi-Fi
 * needed for the link itself; the file transfer (if any) then happens over
 * the local share server URL inside the payload.
 */
class CuedHceService : HostApduService() {

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        val apdu = commandApdu ?: return SW_ERROR
        val payload = SharePayloadHolder.bytes ?: return SW_NO_DATA
        return when {
            isSelect(apdu) -> byteArrayOf((payload.size shr 8).toByte(), (payload.size and 0xFF).toByte()) + SW_OK
            apdu.size >= 4 && apdu[0] == CLA && apdu[1] == INS_READ -> {
                val idx = apdu[2].toInt() and 0xFF
                val from = idx * CHUNK
                if (from >= payload.size) SW_OK
                else payload.copyOfRange(from, minOf(payload.size, from + CHUNK)) + SW_OK
            }
            else -> SW_ERROR
        }
    }

    override fun onDeactivated(reason: Int) { /* nothing to clean up */ }

    private fun isSelect(apdu: ByteArray): Boolean =
        apdu.size >= 5 + AID.size && apdu[0] == 0x00.toByte() && apdu[1] == 0xA4.toByte() && apdu[2] == 0x04.toByte() &&
            apdu.copyOfRange(5, 5 + AID.size).contentEquals(AID)

    companion object {
        val AID = byteArrayOf(0xF0.toByte(), 0x43, 0x55, 0x45, 0x44) // "CUED"
        const val CLA: Byte = 0x80.toByte()
        const val INS_READ: Byte = 0xB0.toByte()
        const val CHUNK = 250
        val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
        val SW_ERROR = byteArrayOf(0x6F.toByte(), 0x00)
        val SW_NO_DATA = byteArrayOf(0x6A.toByte(), 0x82.toByte())

        fun selectApdu(): ByteArray = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, AID.size.toByte()) + AID + byteArrayOf(0x00)
        fun readApdu(index: Int): ByteArray = byteArrayOf(CLA, INS_READ, index.toByte(), 0x00, 0x00)
    }
}
