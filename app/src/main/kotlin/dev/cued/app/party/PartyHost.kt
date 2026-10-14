package dev.cued.app.party

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import dev.cued.app.share.ShareServer
import dev.cued.core.party.Chunker
import dev.cued.core.party.Party
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

/**
 * Hosts a party: three doors into [PartyService.handle].
 *
 * 1. HTTP `POST /party` on its own small server (port [HTTP_PORT]) for guests on
 *    the same Wi-Fi.
 * 2. A UDP beacon on 8772 answering `CUED-PARTY?` with name, key and URL.
 * 3. A Bluetooth LE GATT server (service [Party.BLE_SERVICE]) for guests next to
 *    you with no shared Wi-Fi: requests arrive as frames on RX, replies go out
 *    as notifications on TX.
 */
class PartyHost(private val context: Context, private val service: PartyService) {
    data class State(val hosting: Boolean = false, val lanUrl: String? = null, val ble: String = "off", val error: String? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var scope: CoroutineScope? = null
    private var http: Http? = null
    private var beacon: Thread? = null
    private var gatt: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null

    @Synchronized
    fun start() {
        if (_state.value.hosting) return
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }
        sc.launch(Dispatchers.Main) { service.connection.connect() }
        val ip = ShareServer.localIpv4()
        var lanUrl: String? = null
        var error: String? = null
        if (ip != null) {
            runCatching { Http(HTTP_PORT).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false); http = it } }
                .onSuccess { lanUrl = "http://$ip:$HTTP_PORT" }
                .onFailure { error = "LAN server: ${it.message}" }
        } else error = "No Wi-Fi address: LAN guests cannot find this phone (Bluetooth still works)"
        lanUrl?.let { url -> beacon = Thread({ beaconLoop(url) }, "party-beacon").apply { isDaemon = true; start() } }
        val ble = startBle()
        _state.value = State(hosting = true, lanUrl = lanUrl, ble = ble, error = error)
    }

    @Synchronized
    fun stop() {
        http?.stop(); http = null
        beacon?.interrupt(); beacon = null
        stopBle()
        scope?.cancel(); scope = null
        _state.value = State()
    }

    // ---- HTTP ---------------------------------------------------------------------------------

    private inner class Http(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            val path = session.uri.trimEnd('/')
            if (path == "/health") return newFixedLengthResponse(Response.Status.OK, "text/plain", "cued-party")
            if (path != "/party" && path != "/api/party") return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
            if (session.method != Method.POST) return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "POST a party envelope")
            val len = session.headers["content-length"]?.toIntOrNull() ?: 0
            if (len <= 0 || len > MAX_BODY) return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "party messages are small")
            val buf = ByteArray(len)
            var read = 0
            while (read < len) { val n = session.inputStream.read(buf, read, len - read); if (n < 0) break; read += n }
            val reply = runBlocking { service.handle(String(buf, 0, read, Charsets.UTF_8)) }
            return newFixedLengthResponse(Response.Status.OK, "application/json", reply)
        }
    }

    // ---- UDP beacon ----------------------------------------------------------------------------

    private fun beaconLoop(url: String) {
        try {
            DatagramSocket(Party.BEACON_PORT).use { sock ->
                sock.broadcast = true
                val buf = ByteArray(256)
                while (!Thread.currentThread().isInterrupted) {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                    if (text != Party.BEACON_QUERY) continue
                    if (!pkt.address.isSiteLocalAddress && !pkt.address.isLoopbackAddress && !pkt.address.isLinkLocalAddress) continue
                    val answer = runBlocking { Party.beaconAnswer(service.name(), service.identity().pubkeyHex, url) }.toByteArray(Charsets.UTF_8)
                    sock.send(DatagramPacket(answer, answer.size, pkt.address, pkt.port))
                }
            }
        } catch (e: Exception) {
            if (!Thread.currentThread().isInterrupted) Log.w(TAG, "beacon stopped: ${e.message}")
        }
    }

    // ---- Bluetooth LE GATT server --------------------------------------------------------------

    private val assemblers = ConcurrentHashMap<String, Chunker.Assembler>()
    private val mtus = ConcurrentHashMap<String, Int>()
    private val subscribed = ConcurrentHashMap<String, BluetoothDevice>()
    /** Frames waiting to be notified per device; one in flight at a time (onNotificationSent gates the next). */
    private val outgoing = ConcurrentHashMap<String, LinkedBlockingQueue<ByteArray>>()
    private val inFlight = ConcurrentHashMap<String, Boolean>()
    private var tx: BluetoothGattCharacteristic? = null

    @SuppressLint("MissingPermission")
    private fun startBle(): String {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return "no Bluetooth"
        val adapter = mgr.adapter ?: return "no Bluetooth"
        if (!adapter.isEnabled) return "Bluetooth is off"
        if (!hasBlePermissions(context)) return "needs the Nearby devices permission"
        val adv = adapter.bluetoothLeAdvertiser ?: return "this phone cannot advertise"
        return try {
            val server = mgr.openGattServer(context, gattCallback) ?: return "GATT server unavailable"
            val svc = BluetoothGattService(UUID.fromString(Party.BLE_SERVICE), BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val rx = BluetoothGattCharacteristic(UUID.fromString(Party.BLE_RX), BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, BluetoothGattCharacteristic.PERMISSION_WRITE)
            val txc = BluetoothGattCharacteristic(UUID.fromString(Party.BLE_TX), BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ)
            txc.addDescriptor(BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
            svc.addCharacteristic(rx); svc.addCharacteristic(txc)
            server.addService(svc)
            gatt = server; tx = txc
            val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setConnectable(true).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).build()
            val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(UUID.fromString(Party.BLE_SERVICE))).setIncludeDeviceName(false).build()
            val cb = object : AdvertiseCallback() {
                override fun onStartFailure(errorCode: Int) { _state.value = _state.value.copy(ble = "advertising failed ($errorCode)") }
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) { _state.value = _state.value.copy(ble = "advertising") }
            }
            adv.startAdvertising(settings, data, cb)
            advertiser = adv; advertiseCallback = cb
            "starting"
        } catch (e: Exception) {
            Log.w(TAG, "ble host failed", e)
            "failed: ${e.message}"
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBle() {
        runCatching { advertiseCallback?.let { advertiser?.stopAdvertising(it) } }
        runCatching { gatt?.close() }
        gatt = null; tx = null; advertiser = null; advertiseCallback = null
        assemblers.clear(); mtus.clear(); subscribed.clear(); outgoing.clear(); inFlight.clear()
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) { assemblers.remove(device.address); mtus.remove(device.address); subscribed.remove(device.address); outgoing.remove(device.address); inFlight.remove(device.address) }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) { mtus[device.address] = mtu }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            if (descriptor.uuid == CCCD) {
                if (value != null && value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) subscribed[device.address] = device else subscribed.remove(device.address)
            }
            if (responseNeeded) gatt?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            if (responseNeeded) gatt?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            if (characteristic.uuid != UUID.fromString(Party.BLE_RX) || value == null) return
            val whole = assemblers.getOrPut(device.address) { Chunker.Assembler() }.feed(value) ?: return
            if (whole.size > MAX_BODY) return
            scope?.launch {
                val reply = service.handle(String(whole, Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
                val payload = (mtus[device.address] ?: 23) - 3
                val q = outgoing.getOrPut(device.address) { LinkedBlockingQueue() }
                Chunker.split(reply, payload).forEach { q.put(it) }
                pump(device)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            inFlight[device.address] = false
            pump(device)
        }
    }

    @SuppressLint("MissingPermission")
    private fun pump(device: BluetoothDevice) {
        val server = gatt ?: return
        val c = tx ?: return
        if (inFlight[device.address] == true) return
        val frame = outgoing[device.address]?.poll() ?: return
        if (subscribed[device.address] == null) return
        inFlight[device.address] = true
        if (Build.VERSION.SDK_INT >= 33) server.notifyCharacteristicChanged(device, c, false, frame) else legacyNotify(server, device, c, frame)
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun legacyNotify(server: BluetoothGattServer, device: BluetoothDevice, c: BluetoothGattCharacteristic, frame: ByteArray) {
        c.value = frame
        server.notifyCharacteristicChanged(device, c, false)
    }

    companion object {
        private const val TAG = "PartyHost"
        const val HTTP_PORT = 8773
        private const val MAX_BODY = 8 * 1024
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun blePermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT, android.Manifest.permission.BLUETOOTH_ADVERTISE)
            else arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)

        fun hasBlePermissions(context: Context): Boolean = blePermissions().all { androidx.core.content.ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
    }
}
