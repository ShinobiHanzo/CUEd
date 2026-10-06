package dev.cued.app.station

import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.cued.app.Graph
import dev.cued.app.playback.MediaItems
import dev.cued.app.util.DebugLog
import dev.cued.core.station.Nostr
import dev.cued.core.station.NostrEvent
import dev.cued.core.station.Station
import dev.cued.core.station.StationState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * The listener side: follows one host's state, has the cache resolve the
 * current and upcoming entries, and keeps the local player on the host's
 * position. Corrections are gentle (a few percent of speed) and only seek
 * when far off, so nothing clicks.
 */
@UnstableApi
class StationListener(private val graph: Graph, private val client: NostrClient, private val cache: StationCache) {
    private val main = Handler(Looper.getMainLooper())
    private val scope = graph.appScope
    private val _tuned = MutableStateFlow<Follow?>(null)
    val tuned: StateFlow<Follow?> = _tuned
    private val _state = MutableStateFlow<StationState?>(null)
    val state: StateFlow<StationState?> = _state
    private val _note = MutableStateFlow<String?>(null)
    /** Something the person should know ("host went off air"); cleared by [clearNote]. */
    val note: StateFlow<String?> = _note
    private val _playingTrackId = MutableStateFlow<Long?>(null)
    /** The cached/library track currently playing for the station, if any. */
    val playingTrackId: StateFlow<Long?> = _playingTrackId

    private var events: Job? = null
    private var loop: Job? = null
    private var playingKey: String? = null
    private var lastCorrection = 0L
    private var speed = 1f

    fun tuneIn(f: Follow) {
        if (_tuned.value?.pubkey == f.pubkey) return
        leave(silent = true)
        _tuned.value = f
        _note.value = null
        client.acquire(USER)
        client.subscribe(SUB, Nostr.stationFilter(listOf(f.pubkey)))
        events = scope.launch {
            client.events.filter { it.subId == SUB && it.event.pubkey == f.pubkey }.collect { onEvent(it.event) }
        }
        loop = scope.launch { while (isActive) { runCatching { tick() }.onFailure { DebugLog.w(TAG, "tick", it) }; delay(1_000) } }
        DebugLog.i(TAG, "tuned in to ${f.label}")
    }

    fun leave(silent: Boolean = false) {
        if (_tuned.value == null) return
        client.unsubscribe(SUB)
        events?.cancel(); events = null
        loop?.cancel(); loop = null
        client.release(USER)
        val id = _playingTrackId.value
        main.post {
            val p = graph.player.player
            if (id != null && p.currentMediaItem?.mediaId == id.toString()) { p.setPlaybackSpeed(1f); p.stop(); p.clearMediaItems() }
        }
        playingKey = null; speed = 1f
        _playingTrackId.value = null
        _tuned.value = null
        _state.value = null
        if (!silent) _note.value = null
    }

    fun clearNote() { _note.value = null }

    private fun onEvent(ev: NostrEvent) {
        if (!Nostr.verify(ev)) { DebugLog.w(TAG, "dropped event with bad signature"); return }
        val st = Station.decode(ev.content) ?: return
        val cur = _state.value
        if (cur != null && st.seq <= cur.seq) return
        _state.value = st
        if (st.status == Station.STATUS_OFF) {
            _note.value = "${_tuned.value?.label ?: "The host"} went off air."
            stopStationPlayback()
            return
        }
        st.now?.let { cache.ensure(it) }
        st.next.forEach { cache.ensure(it) }
        scope.launch { cache.evict(protect = readyIds(st)) }
    }

    private fun readyIds(st: StationState): Set<Long> =
        (listOfNotNull(st.now) + st.next).mapNotNull { (cache.status.value[cache.keyOf(it)] as? StationCache.Fetch.Ready)?.trackId }.toSet()

    private suspend fun tick() {
        val st = _state.value ?: return
        if (st.status == Station.STATUS_OFF) return
        val now = st.now ?: return
        val key = cache.keyOf(now)
        val ready = cache.status.value[key] as? StationCache.Fetch.Ready
        if (playingKey != key) {
            if (ready == null) return
            val track = graph.library.track(ready.trackId) ?: return
            val pos = Station.expectedPositionMs(st)
            if (track.durationMs > 0 && pos > track.durationMs - 1500) return // host is about to move on; wait for the next entry
            val item = MediaItems.fromTrack(track)
            onMain { p ->
                p.setPlaybackSpeed(1f)
                p.setMediaItems(listOf(item), 0, pos)
                p.prepare()
                if (st.status == Station.STATUS_ON_AIR) p.play() else p.pause()
            }
            playingKey = key; speed = 1f; lastCorrection = System.currentTimeMillis()
            _playingTrackId.value = ready.trackId
            DebugLog.i(TAG, "playing ${now.artist} - ${now.title} from ${pos / 1000}s")
            return
        }
        val playingId = _playingTrackId.value ?: return
        onMain { p ->
            if (p.currentMediaItem?.mediaId != playingId.toString()) {
                // The person started something else: that wins, the station is left quietly.
                scope.launch { _note.value = "Left the station because you played something else."; leave(silent = true) }
                return@onMain
            }
            when (st.status) {
                Station.STATUS_PAUSED -> if (p.playWhenReady) p.pause()
                Station.STATUS_ON_AIR -> {
                    if (!p.playWhenReady) p.play()
                    if (p.playbackState != Player.STATE_READY) return@onMain
                    val expected = Station.expectedPositionMs(st)
                    val drift = expected - p.currentPosition
                    val since = System.currentTimeMillis() - lastCorrection
                    when {
                        abs(drift) > HARD_SEEK_MS && since > 3_000 -> { p.seekTo(expected); lastCorrection = System.currentTimeMillis(); DebugLog.d(TAG, "seek, drift ${drift}ms") }
                        abs(drift) > SOFT_MS -> setSpeed(p, if (drift > 0) 1.03f else 0.97f)
                        else -> setSpeed(p, 1f)
                    }
                }
            }
        }
    }

    private fun setSpeed(p: Player, s: Float) { if (speed != s) { speed = s; p.setPlaybackSpeed(s) } }

    private fun stopStationPlayback() {
        val id = _playingTrackId.value ?: return
        main.post { val p = graph.player.player; if (p.currentMediaItem?.mediaId == id.toString()) { p.setPlaybackSpeed(1f); p.pause() } }
        playingKey = null
        _playingTrackId.value = null
    }

    private suspend fun onMain(block: (Player) -> Unit) = suspendCancellableCoroutine<Unit> { cont ->
        main.post { runCatching { block(graph.player.player) }; cont.resume(Unit) }
    }

    companion object {
        private const val TAG = "StationListener"
        private const val USER = "listener"
        private const val SUB = "cued-listen"
        private const val HARD_SEEK_MS = 2_500L
        private const val SOFT_MS = 400L
    }
}
