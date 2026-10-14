package dev.cued.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.cued.app.Graph
import dev.cued.app.data.Friend
import dev.cued.app.party.PartyClient
import dev.cued.app.party.PartyHost
import dev.cued.app.party.PartyService
import dev.cued.app.party.PartyTransport
import dev.cued.core.party.Party
import dev.cued.core.party.PartyHello
import dev.cued.core.party.PartyNow
import dev.cued.core.party.PartyTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Both sides of a party. Host: the switch, the LAN / Bluetooth status and the
 * guest log. Guest: discovery, a session with one host, search and queue.
 */
class PartyViewModel(private val graph: Graph) : ViewModel() {
    private val service: PartyService get() = graph.party
    private val client = PartyClient(graph.app)

    // ---- host
    val host: StateFlow<PartyHost.State> = service.host.state
    val log: StateFlow<List<PartyService.Event>> = service.log
    val controls: StateFlow<Boolean> = graph.settings.playback.map { it.partyControls }.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val friends: StateFlow<List<Friend>> = graph.desktop.store.friends.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val myPubkey = MutableStateFlow("")

    fun setHosting(on: Boolean) { if (on) service.host.start() else service.host.stop() }
    fun setControls(on: Boolean) = viewModelScope.launch { graph.settings.setPartyControls(on) }

    // ---- guest
    data class Session(val host: PartyClient.Host, val hello: PartyHello, val transport: PartyTransport)

    val discovering = MutableStateFlow(false)
    val hosts = MutableStateFlow<List<PartyClient.Host>>(emptyList())
    val session = MutableStateFlow<Session?>(null)
    val now = MutableStateFlow<PartyNow?>(null)
    val results = MutableStateFlow<List<PartyTrack>>(emptyList())
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)

    init { viewModelScope.launch { myPubkey.value = service.identity().pubkeyHex } }

    fun hasBlePermissions(): Boolean = PartyHost.hasBlePermissions(graph.app)
    fun blePermissions(): Array<String> = PartyHost.blePermissions()

    fun discover() {
        if (discovering.value) return
        viewModelScope.launch {
            discovering.value = true
            val lan = client.discoverLan()
            hosts.value = lan
            val ble = client.scanBle()
            hosts.value = lan + ble
            if (hosts.value.isEmpty()) message.value = "No party found nearby. The host needs Party on, on the same Wi-Fi or within Bluetooth range."
            discovering.value = false
        }
    }

    /** `hello` first: it tells us who the host is, and that they are a friend, before anything else is sent. */
    fun join(h: PartyClient.Host) {
        viewModelScope.launch {
            busy.value = true
            val transport = runCatching { client.transportFor(h) }.getOrElse { message.value = it.message; busy.value = false; return@launch }
            try {
                val sk = service.identity().secretHex
                val expected = h.beacon.pubkey.takeIf { it.isNotEmpty() }
                val reply = client.exchange(transport, sk, Party.OP_HELLO, hostPubkey = expected)
                Party.errorOf(reply)?.let { throw IllegalStateException(it) }
                val hello = Party.helloOf(reply.arg) ?: throw IllegalStateException("odd hello from the host")
                if (hello.pubkey != reply.pubkey) throw IllegalStateException("the host's key does not match its signature")
                val friend = friends.value.firstOrNull { it.pubkey == hello.pubkey }
                if (friend == null || friend.status != "friends") throw IllegalStateException("${hello.name.ifBlank { "This host" }} is not in your friends list; add each other first")
                session.value = Session(h, hello, transport)
                now.value = hello.now
                results.value = emptyList()
            } catch (e: Exception) {
                transport.close()
                message.value = e.message ?: "Could not join"
            }
            busy.value = false
        }
    }

    fun leave() { session.value?.transport?.close(); session.value = null; now.value = null; results.value = emptyList() }

    private suspend fun op(op: String, arg: kotlinx.serialization.json.JsonElement = kotlinx.serialization.json.JsonNull): kotlinx.serialization.json.JsonElement? {
        val s = session.value ?: return null
        busy.value = true
        return try {
            val reply = client.exchange(s.transport, service.identity().secretHex, op, arg, hostPubkey = s.hello.pubkey)
            Party.errorOf(reply)?.let { message.value = it; return null }
            reply.arg
        } catch (e: Exception) {
            message.value = e.message ?: "Request failed"
            null
        } finally { busy.value = false }
    }

    fun search(q: String) = viewModelScope.launch { op(Party.OP_SEARCH, Party.searchArg(q))?.let { results.value = Party.tracksOf(it) } }
    fun queue(t: PartyTrack) = viewModelScope.launch { op(Party.OP_QUEUE, Party.queueArg(t))?.let { Party.nowOf(it)?.let { n -> now.value = n }; message.value = "Queued ${t.title}" } }
    fun refreshNow() = viewModelScope.launch { op(Party.OP_NOW)?.let { Party.nowOf(it)?.let { n -> now.value = n } } }
    fun skip() = viewModelScope.launch { op(Party.OP_SKIP)?.let { refreshNow() } }
    fun pause() = viewModelScope.launch { op(Party.OP_PAUSE)?.let { Party.nowOf(it)?.let { n -> now.value = n } } }
    fun play() = viewModelScope.launch { op(Party.OP_PLAY)?.let { Party.nowOf(it)?.let { n -> now.value = n } } }

    suspend fun friendsCount(): Int = graph.desktop.store.friends.first().count { it.status == "friends" }

    override fun onCleared() { leave() }
}
