package dev.cued.app.share.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import dev.cued.app.Graph
import dev.cued.app.party.PartyHost
import dev.cued.app.share.OfferedFile
import dev.cued.app.share.PartialFile
import dev.cued.app.ui.LocalFileReceiver
import dev.cued.app.ui.ReceiveStatus
import dev.cued.core.nfc.BtTransfer
import dev.cued.core.nfc.NfcTransfer
import dev.cued.core.share.SharePayload
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * The receiving side of a Bluetooth file transfer: finds the phone that
 * advertises the share's token, opens a channel to it and pulls the file,
 * with the same resume file and hash check the NFC copy uses. Needs no
 * activity, so it runs from the view model as soon as a share arrives.
 */
class BtFileReceiver(private val graph: Graph) {
    private val context: Context get() = graph.app

    private fun adapter(): BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /** Why a Bluetooth copy cannot start right now, or null when it can. */
    fun unavailableReason(): String? = when {
        Build.VERSION.SDK_INT < 29 -> "Bluetooth transfer needs Android 10 or later on this phone"
        adapter() == null -> "This phone has no Bluetooth"
        adapter()?.isEnabled != true -> "Bluetooth is off: turn it on and try again"
        !PartyHost.hasBlePermissions(context) -> NEEDS_PERMISSION
        else -> null
    }

    /** Returns the new library track id (null if the scan missed the file). Throws with a message for the card when the copy cannot be made. */
    @SuppressLint("MissingPermission", "NewApi") // API 29+ is checked in unavailableReason()
    suspend fun fetch(payload: SharePayload, onStatus: (SharePayload, ReceiveStatus) -> Unit): Long? = withContext(Dispatchers.IO) {
        val token = payload.btToken ?: error("This share has no Bluetooth offer")
        unavailableReason()?.let { error(it) }
        var have = 0L
        var total = 0L
        var lastDrop: IOException? = null
        for (attempt in 1..ATTEMPTS) {
            onStatus(payload, ReceiveStatus.Direct(have, total, if (attempt == 1) "Looking for the other phone over Bluetooth…" else "The link dropped. Reconnecting…", VIA))
            val (device, psm) = find(token) ?: error("Could not find the other phone over Bluetooth. Keep its Share screen open and the phones within a few metres.")
            val sock = device.createInsecureL2capChannel(psm)
            try {
                sock.connect()
                val input = sock.inputStream
                val output = sock.outputStream
                val header = BtTransfer.hello(input, output, token)
                total = header.size
                val part = PartialFile(context, header)
                try {
                    have = part.have
                    var shownAt = 0L
                    BtTransfer.pull(input, output, header, part.have, { b, n -> part.append(b, n) }) { got, all ->
                        have = got
                        val now = SystemClock.elapsedRealtime()
                        if (now - shownAt >= 200 || got >= all) { shownAt = now; onStatus(payload, ReceiveStatus.Direct(got, all, "Copying over Bluetooth. Keep the phones within a few metres.", VIA)) }
                    }
                    val ok = part.verify()
                    runCatching { BtTransfer.verdict(output, ok); delay(250) } // let the byte leave before the link closes
                    if (!ok) throw NfcTransfer.ProtocolException("The copy did not match the original (hash check). Try again to start over.")
                    onStatus(payload, ReceiveStatus.Direct(total, total, "Verified. Adding it to your library…", VIA))
                    val merged = payload.copy(link = payload.link ?: header.link, genres = payload.genres.ifEmpty { header.genres }, bpm = payload.bpm ?: header.bpm)
                    val id = LocalFileReceiver(graph).importFile(merged, header.mime, part.file)
                    part.file.delete()
                    return@withContext id
                } finally {
                    part.close()
                }
            } catch (e: NfcTransfer.ProtocolException) {
                throw e
            } catch (e: IOException) {
                Log.i(TAG, "attempt $attempt dropped at $have/$total: ${e.message}")
                lastDrop = e
                delay(700)
            } finally {
                runCatching { sock.close() }
            }
        }
        throw IOException("The Bluetooth link kept dropping (${lastDrop?.message ?: "no reason given"}). What arrived is kept; try again to carry on.")
    }

    @SuppressLint("MissingPermission")
    private suspend fun find(token: String): Pair<BluetoothDevice, Int>? {
        val scanner = adapter()?.bluetoothLeScanner ?: return null
        val found = CompletableDeferred<Pair<BluetoothDevice, Int>>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val psm = BtTransfer.psmOf(result.scanRecord?.getManufacturerSpecificData(BtTransfer.MANUFACTURER_ID), token) ?: return
                found.complete(result.device to psm)
            }
            override fun onScanFailed(errorCode: Int) { found.completeExceptionally(IOException("Bluetooth scan failed ($errorCode)")) }
        }
        val (data, mask) = BtTransfer.advertFilter(token)
        val filter = ScanFilter.Builder().setManufacturerData(BtTransfer.MANUFACTURER_ID, data, mask).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, cb)
        return try { withTimeoutOrNull(SCAN_MS) { found.await() } } finally { runCatching { scanner.stopScan(cb) } }
    }

    companion object {
        private const val TAG = "BtFileReceiver"
        private const val ATTEMPTS = 3
        private const val SCAN_MS = 12_000L
        private const val VIA = OfferedFile.VIA_BLUETOOTH
        /** [unavailableReason] when the only thing missing is the runtime permission. */
        const val NEEDS_PERMISSION = "Bluetooth transfer needs the Nearby devices permission"
    }
}
