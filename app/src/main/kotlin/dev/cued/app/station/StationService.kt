package dev.cued.app.station

import androidx.media3.common.util.UnstableApi
import dev.cued.app.Graph
import dev.cued.core.station.Nostr
import dev.cued.core.station.Station
import dev.cued.core.station.StationState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** One object the rest of the app talks to: identity store, relay client, host, cache, listener, and the Following watcher. */
@UnstableApi
class StationService(private val graph: Graph) {
    val store = StationStore(graph.app)
    val client = NostrClient(graph.appScope, store.relays)
    val cache = StationCache(graph, store)
    val host = StationHost(graph, client, store)
    val listener = StationListener(graph, client, cache)

    private val _live = MutableStateFlow<Map<String, StationState>>(emptyMap())
    /** Latest verified state per followed host, while the Following page is watching. */
    val live: StateFlow<Map<String, StationState>> = _live
    private var watch: Job? = null
    private var watchers = 0

    /** The Following page calls this while visible; the relays are held only as long as someone watches. */
    @Synchronized fun watchFollowing() {
        watchers++
        if (watch != null) return
        client.acquire(USER)
        watch = graph.appScope.launch {
            launch {
                client.events.filter { it.subId == SUB }.collect { m ->
                    val ev = m.event
                    if (!Nostr.verify(ev)) return@collect
                    val st = Station.decode(ev.content) ?: return@collect
                    val cur = _live.value[ev.pubkey]
                    if (cur == null || st.seq > cur.seq) _live.value = _live.value + (ev.pubkey to st)
                }
            }
            store.follows.collectLatest { list ->
                if (list.isEmpty()) client.unsubscribe(SUB) else client.subscribe(SUB, Nostr.stationFilter(list.map { it.pubkey }))
            }
        }
    }

    @Synchronized fun unwatchFollowing() {
        watchers = (watchers - 1).coerceAtLeast(0)
        if (watchers > 0) return
        client.unsubscribe(SUB)
        watch?.cancel(); watch = null
        client.release(USER)
    }

    companion object { private const val USER = "following"; private const val SUB = "cued-following" }
}
