package dev.cued.app.party

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import dev.cued.core.party.Chunker
import dev.cued.core.party.Party
import dev.cued.core.party.PartyBeacon
import dev.cued.core.party.PartyEnvelope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** A way to send one signed envelope and get the signed reply back. */
interface PartyTransport {
    val label: String
    suspend fun call(request: PartyEnvelope): PartyEnvelope
    fun close() {}
}

/** HTTP on the LAN: `POST <url>/party` (a phone) or `<url>/api/party` (the desktop). */
class LanTransport(private val baseUrl: String) : PartyTransport {
    override val label = "Wi-Fi"
    override suspend fun call(request: PartyEnvelope): PartyEnvelope = withContext(Dispatchers.IO) {
        val body = Party.encode(request).toByteArray(Charsets.UTF_8)
        var last: Exception? = null
        for (path in listOf("/party", "/api/party")) {
            val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 4_000; conn.readTimeout = 10_000
                conn.requestMethod = "POST"; conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body) }
                val code = conn.responseCode
                if (code == 404 || code == 405) continue
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
                if (code !in 200..299) throw IOException("HTTP $code from the host")
                return@withContext Party.decode(text) ?: throw IOException("the host did not answer with a party envelope")
            } catch (e: IOException) { last = e } finally { conn.disconnect() }
        }
        throw last ?: IOException("no party endpoint at $baseUrl")
    }
}

/**
 * Bluetooth LE: connect, ask for a big MTU, subscribe to TX, write the request
 * to RX in frames, collect the reply frames. One request at a time.
 */
class BleTransport(private val context: Context, private val device: BluetoothDevice) : PartyTransport {
    override val label = "Bluetooth"
    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private val events = LinkedBlockingQueue<String>()
    private val assembler = Chunker.Assembler()
    private val replies = LinkedBlockingQueue<ByteArray>()

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices() else if (newState == BluetoothProfile.STATE_DISCONNECTED) events.offer("disconnected")
        }
        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(UUID.fromString(Party.BLE_SERVICE))
            rx = svc?.getCharacteristic(UUID.fromString(Party.BLE_RX)); tx = svc?.getCharacteristic(UUID.fromString(Party.BLE_TX))
            if (rx == null || tx == null) { events.offer("no party service"); return }
            if (!g.requestMtu(512)) events.offer("mtu:23")
        }
        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            mtu = if (status == BluetoothGatt.GATT_SUCCESS) newMtu else 23
            val t = tx ?: return
            g.setCharacteristicNotification(t, true)
            val d = t.getDescriptor(PartyHost.CCCD) ?: run { events.offer("ready"); return }
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) else legacyWriteDescriptor(g, d)
        }
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) { events.offer(if (status == BluetoothGatt.GATT_SUCCESS) "ready" else "subscribe failed") }
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) { events.offer(if (status == BluetoothGatt.GATT_SUCCESS) "written" else "write failed") }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) { assembler.feed(value)?.let { replies.offer(it) } }
        @Suppress("DEPRECATION")
        @Deprecated("pre-33 signature")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) characteristic.value?.let { v -> assembler.feed(v)?.let { replies.offer(it) } }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun ensureConnected() = withContext(Dispatchers.IO) {
        if (gatt != null && rx != null) return@withContext
        events.clear()
        gatt = if (Build.VERSION.SDK_INT >= 23) device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE) else device.connectGatt(context, false, callback)
        val deadline = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < deadline) {
            when (val ev = events.poll(500, TimeUnit.MILLISECONDS)) {
                null -> {}
                "ready" -> return@withContext
                "mtu:23" -> { mtu = 23; tx?.let { t -> gatt?.setCharacteristicNotification(t, true) }; return@withContext }
                else -> throw IOException("Bluetooth: $ev")
            }
        }
        throw IOException("Bluetooth: the host did not answer in time")
    }

    @SuppressLint("MissingPermission")
    override suspend fun call(request: PartyEnvelope): PartyEnvelope = withContext(Dispatchers.IO) {
        ensureConnected()
        val g = gatt ?: throw IOException("Bluetooth: not connected")
        val c = rx ?: throw IOException("Bluetooth: no party service")
        replies.clear(); assembler.reset()
        val frames = Chunker.split(Party.encode(request).toByteArray(Charsets.UTF_8), mtu - 3)
        for (f in frames) {
            events.clear()
            val ok = if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, f, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS else legacyWrite(g, c, f)
            if (!ok) throw IOException("Bluetooth: write refused")
            if (events.poll(3, TimeUnit.SECONDS) != "written") throw IOException("Bluetooth: write not confirmed")
        }
        val reply = replies.poll(15, TimeUnit.SECONDS) ?: throw IOException("Bluetooth: no reply")
        Party.decode(String(reply, Charsets.UTF_8)) ?: throw IOException("Bluetooth: bad reply")
    }

    @SuppressLint("MissingPermission")
    override fun close() { runCatching { gatt?.disconnect(); gatt?.close() }; gatt = null; rx = null; tx = null }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun legacyWriteDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor) {
        d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        g.writeDescriptor(d)
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun legacyWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, f: ByteArray): Boolean {
        c.value = f
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return g.writeCharacteristic(c)
    }
}

/** Finding hosts and talking to one, with every reply checked. */
class PartyClient(private val context: Context) {
    data class Host(val beacon: PartyBeacon, val device: BluetoothDevice? = null) {
        val key get() = beacon.pubkey + "/" + beacon.transport
    }

    /** Broadcasts the beacon question and collects the hosts that answer within [timeoutMs]. */
    suspend fun discoverLan(timeoutMs: Long = 1_500): List<Host> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, Host>()
        runCatching {
            DatagramSocket().use { sock ->
                sock.broadcast = true
                sock.soTimeout = 300
                val q = Party.BEACON_QUERY.toByteArray(Charsets.UTF_8)
                for (target in listOf("255.255.255.255", broadcastOf())) {
                    if (target == null) continue
                    runCatching { sock.send(DatagramPacket(q, q.size, InetAddress.getByName(target), Party.BEACON_PORT)) }
                }
                val deadline = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(1024)
                while (System.currentTimeMillis() < deadline) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try { sock.receive(pkt) } catch (_: SocketTimeoutException) { continue }
                    val b = Party.parseBeacon(String(pkt.data, 0, pkt.length, Charsets.UTF_8)) ?: continue
                    found[b.pubkey] = Host(b)
                }
            }
        }.onFailure { Log.w(TAG, "lan discovery failed", it) }
        found.values.toList()
    }

    private fun broadcastOf(): String? = dev.cued.app.share.ShareServer.localIpv4()?.let { ip -> ip.substringBeforeLast('.') + ".255" }

    /** Scans for phones advertising the party service; the host's name and key come from a `hello` afterwards. */
    @SuppressLint("MissingPermission")
    suspend fun scanBle(timeoutMs: Long = 4_000): List<Host> {
        if (!PartyHost.hasBlePermissions(context)) return emptyList()
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return emptyList()
        val scanner = mgr.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return emptyList()
        val found = LinkedHashMap<String, Host>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d = result.device
                found[d.address] = Host(PartyBeacon(name = "", pubkey = "", url = null, transport = "ble"), d)
            }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID.fromString(Party.BLE_SERVICE))).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(listOf(filter), settings, cb) }.onFailure { return emptyList() }
        try { delay(timeoutMs) } finally { runCatching { scanner.stopScan(cb) } }
        return found.values.toList()
    }

    fun transportFor(host: Host): PartyTransport = when {
        host.device != null -> BleTransport(context, host.device)
        host.beacon.url != null -> LanTransport(host.beacon.url)
        else -> throw IOException("this host has no address")
    }

    /**
     * Sends [op] signed with [secretHex] and returns the verified reply: the
     * signature must check out, the nonce must echo ours, and when [hostPubkey]
     * is known the reply must come from that key.
     */
    suspend fun exchange(transport: PartyTransport, secretHex: String, op: String, arg: kotlinx.serialization.json.JsonElement = kotlinx.serialization.json.JsonNull, hostPubkey: String? = null): PartyEnvelope {
        val req = Party.sign(secretHex, op, arg)
        val reply = withTimeout(20_000) { transport.call(req) }
        if (!Party.verify(reply)) throw IOException("the host's reply is not signed correctly")
        if (reply.nonce != req.nonce) throw IOException("the host answered a different request")
        if (hostPubkey != null && reply.pubkey != hostPubkey) throw IOException("the reply came from a different key")
        return reply
    }

    companion object { private const val TAG = "PartyClient" }
}
