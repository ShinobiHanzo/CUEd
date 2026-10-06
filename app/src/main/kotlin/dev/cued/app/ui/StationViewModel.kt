package dev.cued.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import dev.cued.app.Graph
import dev.cued.app.station.Follow
import dev.cued.app.station.Identity
import dev.cued.app.station.StationCache
import dev.cued.core.crypto.Nip19
import dev.cued.core.station.StationLink
import dev.cued.core.station.StationState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@UnstableApi
class StationViewModel(private val graph: Graph) : ViewModel() {
    private val svc = graph.station
    private val store = svc.store

    val enabled: StateFlow<Boolean> = store.enabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val setupDone: StateFlow<Boolean> = store.setupDone.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    /** Both the beta switch and the one-time setup: the only state in which station screens and follow links work. */
    val ready: StateFlow<Boolean> = combine(enabled, setupDone) { e, d -> e && d }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val identity: StateFlow<Identity?> = store.identity.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val name: StateFlow<String> = store.name.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val follows: StateFlow<List<Follow>> = store.follows.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val relays: StateFlow<List<String>> = store.relays.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val prefetchOnMobile: StateFlow<Boolean> = store.prefetchOnMobile.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val cacheMb: StateFlow<Int> = store.cacheMb.stateIn(viewModelScope, SharingStarted.Eagerly, 300)
    val relayStatus: StateFlow<Map<String, Boolean>> = svc.client.status
    val onAir: StateFlow<Boolean> = svc.host.onAir
    val hostState: StateFlow<StationState?> = svc.host.state
    val tuned: StateFlow<Follow?> = svc.listener.tuned
    val listenState: StateFlow<StationState?> = svc.listener.state
    val playingTrackId: StateFlow<Long?> = svc.listener.playingTrackId
    val note: StateFlow<String?> = svc.listener.note
    val fetch: StateFlow<Map<String, StationCache.Fetch>> = svc.cache.status
    val live: StateFlow<Map<String, StationState>> = svc.live

    /** What a QR code or NFC tap hands a follower; null until the key pair exists. */
    val stationLink: StateFlow<String?> = combine(identity, name, relays) { id, n, r -> id?.let { StationLink(it.pubkeyHex, n, r).encode() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setEnabled(on: Boolean) { viewModelScope.launch { if (!on) svc.shutdown(); store.setEnabled(on) } }
    fun finishSetup() { viewModelScope.launch { store.setSetupDone(true); store.setEnabled(true) } }
    /** Forget the key pair, follows and settings; the cache is cleared too. */
    fun reset() { viewModelScope.launch { svc.shutdown(); svc.cache.clear(); store.reset() } }
    /** Tries each relay once; [onResult] gets url → reachable. */
    fun testRelays(onResult: (Map<String, Boolean>) -> Unit) {
        viewModelScope.launch {
            val urls = relays.value.ifEmpty { dev.cued.app.station.StationStore.DEFAULT_RELAYS }
            onResult(urls.associateWith { dev.cued.app.station.NostrClient.probe(it) })
        }
    }

    fun goOnAir() { viewModelScope.launch { store.ensureIdentity(); svc.host.start() } }
    fun goOffAir() { svc.host.stop() }
    fun createIdentity() { viewModelScope.launch { store.ensureIdentity() } }
    fun setName(n: String) { viewModelScope.launch { store.setName(n) } }
    fun importSecret(text: String, onDone: (String) -> Unit) { viewModelScope.launch { onDone(store.importSecret(text).fold({ "Key imported: ${it.npub.take(16)}…" }, { it.message ?: "Could not import" })) } }
    fun setRelays(text: String) { viewModelScope.launch { store.setRelays(text.split('\n', ',', ' ')) } }
    fun setPrefetchOnMobile(on: Boolean) { viewModelScope.launch { store.setPrefetchOnMobile(on) } }
    fun setCacheMb(mb: Int) { viewModelScope.launch { store.setCacheMb(mb) } }
    fun clearCache() { viewModelScope.launch { svc.cache.clear() } }

    /** Accepts a cued://station link, an npub or a hex key. Returns a message for a snackbar. */
    fun follow(text: String, onDone: (String) -> Unit = {}) {
        viewModelScope.launch {
            val link = StationLink.decode(text) ?: Nip19.parse(text)?.takeIf { it.first != "nsec" }?.let { StationLink(it.second) }
            if (link == null) { onDone("That is not a station link or public key"); return@launch }
            if (link.pubkey == identity.value?.pubkeyHex) { onDone("That is your own station"); return@launch }
            store.addFollow(Follow(link.pubkey, link.name, link.relays))
            onDone("Following ${link.name.ifBlank { "station " + Nip19.npub(link.pubkey).take(12) + "…" }}")
        }
    }
    fun unfollow(pubkey: String) { viewModelScope.launch { if (tuned.value?.pubkey == pubkey) svc.listener.leave(); store.removeFollow(pubkey) } }

    fun tuneIn(f: Follow) { svc.listener.tuneIn(f) }
    fun leave() { svc.listener.leave() }
    fun clearNote() { svc.listener.clearNote() }
    fun retryFetch(t: dev.cued.core.station.StationTrack) { svc.cache.ensure(t, retry = true) }
    fun keep(trackId: Long, onDone: (String) -> Unit) { viewModelScope.launch { onDone(runCatching { svc.cache.keep(trackId) }.fold({ if (it != null) "Kept in your library" else "Nothing to keep" }, { "Couldn't keep it: ${it.message}" })) } }

    fun watchFollowing() = svc.watchFollowing()
    fun unwatchFollowing() = svc.unwatchFollowing()
}
