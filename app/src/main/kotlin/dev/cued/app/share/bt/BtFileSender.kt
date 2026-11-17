package dev.cued.app.share.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.util.Log
import dev.cued.app.party.PartyHost
import dev.cued.app.share.OfferedFile
import dev.cued.core.nfc.BtTransfer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import kotlin.concurrent.thread

/**
 * The sending side of a Bluetooth file transfer (`BtTransfer`). While the
 * Share screen is open this phone listens on a Bluetooth LE connection-oriented
 * channel (L2CAP, Android 10+: a plain byte stream, no pairing) and advertises
 * a one-time token with the channel number. The token rides in the share
 * itself (NFC tap or QR), so only a phone that was handed the share can find
 * the advert and pull the file.
 */
class BtFileSender(private val context: Context) {
    /** [token] is set while listening; [note] says why not otherwise; [needsPermission] when asking would fix it. */
    data class Status(val token: String? = null, val note: String = "", val needsPermission: Boolean = false)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private var server: BluetoothServerSocket? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null

    /** Starts listening and advertising. Returns the token for the share, or null (see [status]). Safe to call again. */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(): String? {
        _status.value.token?.let { return it }
        if (Build.VERSION.SDK_INT < 29) return off("Bluetooth transfer needs Android 10 or later")
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return off("This phone has no Bluetooth")
        if (!adapter.isEnabled) return off("Bluetooth is off")
        if (!PartyHost.hasBlePermissions(context)) return off("Bluetooth transfer needs the Nearby devices permission", needsPermission = true)
        val adv = adapter.bluetoothLeAdvertiser ?: return off("This phone cannot advertise over Bluetooth")
        return try {
            val ss = adapter.listenUsingInsecureL2capChannel()
            val token = BtTransfer.newToken()
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setConnectable(true).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).build()
            // Flags (3 bytes) + manufacturer data (4 + 12) fits one legacy advert; no name, nothing that identifies the phone.
            val data = AdvertiseData.Builder()
                .addManufacturerData(BtTransfer.MANUFACTURER_ID, BtTransfer.advert(token, ss.psm)).setIncludeDeviceName(false).setIncludeTxPowerLevel(false).build()
            val cb = object : AdvertiseCallback() {
                override fun onStartFailure(errorCode: Int) {
                    Log.w(TAG, "advertising failed: $errorCode")
                    if (_status.value.token == token) { stop(); _status.value = Status(note = "Bluetooth advertising failed ($errorCode)") }
                }
            }
            adv.startAdvertising(settings, data, cb)
            server = ss; advertiser = adv; advertiseCallback = cb
            _status.value = Status(token = token, note = "Bluetooth ready")
            thread(name = "cued-bt-accept", isDaemon = true) { acceptLoop(ss, token) }
            token
        } catch (e: Exception) {
            Log.w(TAG, "could not start", e)
            stop()
            off("Bluetooth transfer failed to start: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun stop() {
        runCatching { advertiseCallback?.let { advertiser?.stopAdvertising(it) } }
        runCatching { server?.close() } // unblocks accept()
        server = null; advertiser = null; advertiseCallback = null
        _status.value = Status()
    }

    private fun off(note: String, needsPermission: Boolean = false): String? {
        _status.value = Status(note = note, needsPermission = needsPermission)
        return null
    }

    private fun acceptLoop(ss: BluetoothServerSocket, token: String) {
        while (_status.value.token == token) {
            val sock = try { ss.accept() } catch (e: IOException) { break }
            thread(name = "cued-bt-send", isDaemon = true) { serve(sock, token) }
        }
    }

    private fun serve(sock: BluetoothSocket, token: String) {
        try {
            val header = OfferedFile.header ?: return
            val packet = sock.maxTransmitPacketSize.takeIf { it > 0 } ?: 512
            val verdict = BtTransfer.serve(sock.inputStream, sock.outputStream, token, header, OfferedFile::read, packet.coerceAtMost(4096)) { sent ->
                OfferedFile.noteSent(sent, OfferedFile.VIA_BLUETOOTH)
            }
            if (verdict != null) OfferedFile.noteVerdict(verdict, OfferedFile.VIA_BLUETOOTH)
        } catch (e: Exception) {
            Log.i(TAG, "connection ended: ${e.message}")
        } finally {
            runCatching { sock.close() }
        }
    }

    private companion object { const val TAG = "BtFileSender" }
}
