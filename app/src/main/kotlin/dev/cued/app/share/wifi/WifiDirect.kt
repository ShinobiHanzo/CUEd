package dev.cued.app.share.wifi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import dev.cued.app.Graph
import dev.cued.app.ui.LocalFileReceiver
import dev.cued.app.ui.ReceiveStatus
import dev.cued.core.share.SharePayload
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.URL
import java.security.SecureRandom
import kotlin.coroutines.resume

/**
 * The fast phone-to-phone path, the way AirDrop does it: a small radio makes
 * the introduction (here the NFC tap or the QR code) and Wi-Fi carries the
 * file directly between the two phones, with no router and no internet.
 *
 * The sender opens a Wi-Fi Direct group with a name and passphrase it made
 * up for this share and puts them in the share. From Android 10 a phone that
 * knows a group's credentials joins it like an access point: no invitation
 * dialog on either side. The receiver then fetches the file from the
 * sender's share server at the group owner's address, at Wi-Fi speed.
 */
object WifiDirect {
    const val VIA = "Wi-Fi Direct"
    /** The address every Android group owner takes; the receiver uses what the system reports, this is only for the share's URL. */
    const val OWNER_ADDRESS = "192.168.49.1"
    /** [unavailableReason] when only the runtime permission is missing. */
    const val NEEDS_PERMISSION = "Wi-Fi Direct needs the Nearby devices permission"
    private const val TAG = "WifiDirect"

    /** Android 13+: Nearby Wi-Fi devices (declared never-for-location). Before that the platform insists on the location permission. */
    fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasPermissions(context: Context): Boolean = permissions().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /** Why Wi-Fi Direct cannot be used right now, or null when it can. */
    fun unavailableReason(context: Context): String? {
        if (Build.VERSION.SDK_INT < 29) return "Wi-Fi Direct transfer needs Android 10 or later"
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) return "This phone has no Wi-Fi Direct"
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi?.isWifiEnabled != true) return "Wi-Fi is switched off (it only has to be on, not connected to anything)"
        if (!hasPermissions(context)) return NEEDS_PERMISSION
        if (Build.VERSION.SDK_INT < 33) {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm != null && !LocationManagerCompat.isLocationEnabled(lm)) return "Wi-Fi Direct needs Location switched on (Android 12 and older)"
        }
        return null
    }

    internal fun manager(context: Context): WifiP2pManager? = context.applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager

    /** Runs one WifiP2pManager action and reports "ok" or why not; never hangs on a listener that stays silent. */
    internal suspend fun act(call: (WifiP2pManager.ActionListener) -> Unit): String =
        withTimeoutOrNull(6_000) {
            suspendCancellableCoroutine<String> { c ->
                val listener = object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { if (c.isActive) c.resume("ok") }
                    override fun onFailure(reason: Int) {
                        if (c.isActive) c.resume(when (reason) { WifiP2pManager.BUSY -> "busy"; WifiP2pManager.P2P_UNSUPPORTED -> "unsupported"; else -> "error $reason" })
                    }
                }
                runCatching { call(listener) }.onFailure { e -> Log.w(TAG, "p2p call failed", e); if (c.isActive) c.resume(e.message ?: "failed") }
            }
        } ?: "no answer"

    @SuppressLint("MissingPermission")
    internal suspend fun groupInfo(mgr: WifiP2pManager, ch: WifiP2pManager.Channel): WifiP2pGroup? =
        withTimeoutOrNull(3_000) { suspendCancellableCoroutine<WifiP2pGroup?> { c -> mgr.requestGroupInfo(ch) { g -> if (c.isActive) c.resume(g) } } }

    internal suspend fun connectionInfo(mgr: WifiP2pManager, ch: WifiP2pManager.Channel): WifiP2pInfo? =
        withTimeoutOrNull(3_000) { suspendCancellableCoroutine<WifiP2pInfo?> { c -> mgr.requestConnectionInfo(ch) { i -> if (c.isActive) c.resume(i) } } }

    private const val ALNUM = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
    internal fun random(n: Int): String { val r = SecureRandom(); return String(CharArray(n) { ALNUM[r.nextInt(ALNUM.length)] }) }
}

/** The sending side: owns a Wi-Fi Direct group while the Share screen is open. */
class WifiDirectHost(private val context: Context) {
    data class Offer(val name: String, val pass: String, val freq: Int?)
    /** [offer] is set while the group is up; [note] says why not otherwise; [needsPermission] when asking would fix it. */
    data class Status(val offer: Offer? = null, val note: String = "", val needsPermission: Boolean = false)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status
    private var channel: WifiP2pManager.Channel? = null
    private val mutex = Mutex()
    @Volatile private var generation = 0

    /** Opens the group (a few seconds the first time). Returns its credentials for the share, or null (see [status]). Safe to call again. */
    @SuppressLint("MissingPermission", "NewApi") // API 29+ and the permission are checked in unavailableReason()
    suspend fun start(): Offer? = mutex.withLock {
        _status.value.offer?.let { return it }
        WifiDirect.unavailableReason(context)?.let { return off(it, it == WifiDirect.NEEDS_PERMISSION) }
        val mgr = WifiDirect.manager(context) ?: return off("This phone has no Wi-Fi Direct")
        val ch = channel ?: mgr.initialize(context.applicationContext, Looper.getMainLooper(), null)?.also { channel = it } ?: return off("Wi-Fi Direct did not start")
        val mine = generation
        return try {
            WifiDirect.act { mgr.removeGroup(ch, it) } // a group left over from an earlier share would make createGroup "busy"
            val name = "DIRECT-CU-" + WifiDirect.random(6)
            val pass = WifiDirect.random(12)
            val config = WifiP2pConfig.Builder().setNetworkName(name).setPassphrase(pass).enablePersistentMode(false).build()
            val made = WifiDirect.act { mgr.createGroup(ch, config, it) }
            if (made != "ok") return off("Wi-Fi Direct could not start ($made)")
            // "ok" only means the request was taken; wait for the group itself.
            val group = withTimeoutOrNull(8_000) {
                var g: WifiP2pGroup? = null
                while (g == null) { g = WifiDirect.groupInfo(mgr, ch)?.takeIf { it.isGroupOwner }; if (g == null) delay(300) }
                g
            }
            if (group == null || mine != generation) {
                WifiDirect.act { mgr.removeGroup(ch, it) }
                return off(if (group == null) "Wi-Fi Direct group did not come up" else "")
            }
            val offer = Offer(group.networkName ?: name, group.passphrase ?: pass, group.frequency.takeIf { it > 0 })
            _status.value = Status(offer = offer, note = "Wi-Fi Direct ready")
            offer
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "could not start", e)
            off("Wi-Fi Direct could not start: ${e.message}")
        }
    }

    fun stop() {
        generation++
        val mgr = WifiDirect.manager(context)
        val ch = channel
        if (mgr != null && ch != null && _status.value.offer != null) runCatching { mgr.removeGroup(ch, null) }
        _status.value = Status()
    }

    private fun off(note: String, needsPermission: Boolean = false): Offer? {
        _status.value = Status(note = note, needsPermission = needsPermission)
        return null
    }

    private companion object { const val TAG = "WifiDirectHost" }
}

/** The receiving side: joins the sender's group, fetches the file from its share server, leaves the group. */
class WifiDirectReceiver(private val graph: Graph) {
    private val context: Context get() = graph.app
    private var channel: WifiP2pManager.Channel? = null

    fun unavailableReason(): String? = WifiDirect.unavailableReason(context)

    /** Returns the new library track id (null if the scan missed the file). Throws with a message for the card when the copy cannot be made. */
    @SuppressLint("MissingPermission", "NewApi") // API 29+ and the permission are checked in unavailableReason()
    suspend fun fetch(payload: SharePayload, onStatus: (SharePayload, ReceiveStatus) -> Unit): Long? {
        val name = payload.p2pName; val pass = payload.p2pPass; val url = payload.p2pUrl
        if (name == null || pass == null || url == null) error("This share has no Wi-Fi Direct offer")
        unavailableReason()?.let { error(it) }
        val mgr = WifiDirect.manager(context) ?: error("This phone has no Wi-Fi Direct")
        val ch = channel ?: mgr.initialize(context.applicationContext, Looper.getMainLooper(), null)?.also { channel = it } ?: error("Wi-Fi Direct did not start")
        onStatus(payload, ReceiveStatus.Direct(0, 0, "Connecting to the other phone over Wi-Fi Direct…", WifiDirect.VIA))
        try {
            var owner: InetAddress? = null
            var why = "no answer"
            // With the channel the sender reported the join takes a second or two; if that fails, let the phone search every channel.
            for (freq in listOfNotNull(payload.p2pFreq, 0).distinct()) {
                val b = WifiP2pConfig.Builder().setNetworkName(name).setPassphrase(pass).enablePersistentMode(false)
                if (freq > 0) b.setGroupOperatingFrequency(freq)
                val asked = WifiDirect.act { mgr.connect(ch, b.build(), it) }
                if (asked != "ok") { why = asked; continue }
                owner = withTimeoutOrNull(if (freq > 0) 10_000L else 20_000L) {
                    var a: InetAddress? = null
                    while (a == null) {
                        val info = WifiDirect.connectionInfo(mgr, ch)
                        a = info?.takeIf { it.groupFormed && !it.isGroupOwner }?.groupOwnerAddress
                        if (a == null) delay(300)
                    }
                    a
                }
                if (owner != null) break
                why = "the group was not found"
                WifiDirect.act { mgr.cancelConnect(ch, it) }
                WifiDirect.act { mgr.removeGroup(ch, it) }
            }
            val host = owner?.hostAddress ?: error("Could not join the other phone's Wi-Fi Direct group ($why). Keep its Share screen open and the phones close.")
            val u = URL(url)
            val target = "http://$host:${if (u.port > 0) u.port else 80}${u.path}"
            var shownAt = 0L
            return LocalFileReceiver(graph).fetchUrl(payload, target, connectTimeoutMs = 6_000, attempts = 3) { have, total ->
                val now = SystemClock.elapsedRealtime()
                if (now - shownAt >= 150 || have >= total) { shownAt = now; onStatus(payload, ReceiveStatus.Direct(have, total, "Copying over Wi-Fi Direct.", WifiDirect.VIA)) }
            }
        } finally {
            withContext(NonCancellable) { WifiDirect.act { mgr.removeGroup(ch, it) } } // leave the group: the phone's normal Wi-Fi is untouched
        }
    }
}
