package dev.cued.app.station

import dev.cued.app.util.DebugLog
import dev.cued.core.station.Nostr
import dev.cued.core.station.NostrEvent
import dev.cued.core.station.RelayMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Keeps a WebSocket to each configured relay while someone needs one (a
 * station on air, a station tuned in, the Following page open). Relays are
 * dumb store-and-forward boxes: every event is signed by its author, so it
 * does not matter which relay carried it or who runs it.
 */
class NostrClient(private val scope: CoroutineScope, private val relayUrls: Flow<List<String>>) {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).pingInterval(25, TimeUnit.SECONDS).build()
    private val relays = LinkedHashMap<String, Relay>()
    private val subs = ConcurrentHashMap<String, Array<out JsonObject>>()
    private val users = ConcurrentHashMap.newKeySet<String>()
    private var watcher: Job? = null
    @Volatile private var latest: NostrEvent? = null

    private val _events = MutableSharedFlow<RelayMessage.Event>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Every EVENT any relay delivers, tagged with the subscription id it answers. Verify before trusting. */
    val events: SharedFlow<RelayMessage.Event> = _events
    private val _status = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    /** Relay URL → connected. */
    val status: StateFlow<Map<String, Boolean>> = _status

    /** A user (host, listener, follow watcher) says it needs the relays up. */
    @Synchronized fun acquire(user: String) {
        users += user
        if (watcher == null) watcher = scope.launch {
            relayUrls.distinctUntilChanged().collect { urls -> sync(urls) }
        }
    }

    /** When the last user lets go, every socket closes. */
    @Synchronized fun release(user: String) {
        users -= user
        if (users.isEmpty()) {
            watcher?.cancel(); watcher = null
            relays.values.forEach { it.close() }; relays.clear()
            latest = null
            _status.value = emptyMap()
        }
    }

    @Synchronized private fun sync(urls: List<String>) {
        val wanted = urls.toSet()
        relays.keys.filter { it !in wanted }.forEach { relays.remove(it)?.close() }
        for (u in wanted) if (u !in relays) relays[u] = Relay(u).also { it.connect() }
        publishStatus()
    }

    /** Sends to every open relay now and to each relay as it (re)connects, so a relay that was down still ends up with the newest state. */
    fun publish(ev: NostrEvent) {
        latest = ev
        val msg = Nostr.eventMessage(ev)
        synchronized(this) { relays.values.forEach { it.send(msg) } }
    }

    fun subscribe(subId: String, vararg filters: JsonObject) {
        subs[subId] = filters
        val msg = Nostr.reqMessage(subId, *filters)
        synchronized(this) { relays.values.forEach { it.send(msg) } }
    }

    fun unsubscribe(subId: String) {
        subs.remove(subId) ?: return
        val msg = Nostr.closeMessage(subId)
        synchronized(this) { relays.values.forEach { it.send(msg) } }
    }

    @Synchronized private fun publishStatus() { _status.value = relays.mapValues { it.value.open } }

    private inner class Relay(val url: String) : WebSocketListener() {
        @Volatile var open = false
        @Volatile private var closed = false
        private var ws: WebSocket? = null
        private var attempts = 0

        fun connect() {
            if (closed) return
            ws = http.newWebSocket(Request.Builder().url(url).build(), this)
        }

        fun send(text: String) { if (open) ws?.send(text) }

        fun close() { closed = true; open = false; ws?.close(1000, "bye"); ws = null }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            open = true; attempts = 0
            DebugLog.i(TAG, "connected $url")
            publishStatus()
            for ((id, f) in subs) webSocket.send(Nostr.reqMessage(id, *f))
            latest?.let { webSocket.send(Nostr.eventMessage(it)) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            when (val m = Nostr.parse(text)) {
                is RelayMessage.Event -> _events.tryEmit(m)
                is RelayMessage.Ok -> if (!m.accepted) DebugLog.w(TAG, "$url rejected ${m.eventId.take(8)}: ${m.message}")
                is RelayMessage.Notice -> DebugLog.d(TAG, "$url notice: ${m.message}")
                is RelayMessage.Closed -> DebugLog.w(TAG, "$url closed sub ${m.subId}: ${m.message}")
                else -> {}
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { lost("failure: ${t.message}") }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { lost("closed $code $reason") }

        private fun lost(why: String) {
            open = false
            publishStatus()
            if (closed) return
            val wait = minOf(60_000L, 1_000L shl minOf(attempts, 6))
            attempts++
            DebugLog.w(TAG, "$url $why; retry in ${wait / 1000}s")
            scope.launch { delay(wait); if (!closed && isActive) connect() }
        }
    }

    companion object {
        private const val TAG = "Nostr"

        /** Setup check: can this relay be reached right now? Opens a socket, waits for the handshake, closes it. */
        suspend fun probe(url: String, timeoutMs: Long = 6_000): Boolean = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).build()
                val ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) { if (cont.isActive) cont.resume(true); webSocket.close(1000, "probe") }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { if (cont.isActive) cont.resume(false) }
                })
                cont.invokeOnCancellation { ws.cancel() }
            }
        } ?: false
    }
}
