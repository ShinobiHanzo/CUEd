package dev.cued.app.station

import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.cued.app.Graph
import dev.cued.app.data.db.TrackEntity
import dev.cued.app.playback.MediaItems
import dev.cued.app.util.DebugLog
import dev.cued.core.station.Nostr
import dev.cued.core.station.Station
import dev.cued.core.station.StationState
import dev.cued.core.station.StationTrack
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The host side: watches the phone's own player and publishes a signed
 * "what I'm playing" state whenever it changes, plus a heartbeat so late
 * joiners and relays that dropped get the latest. Nothing but metadata leaves.
 */
@UnstableApi
class StationHost(private val graph: Graph, private val client: NostrClient, private val store: StationStore) {
    private val main = Handler(Looper.getMainLooper())
    private val scope = graph.appScope
    private val _onAir = MutableStateFlow(false)
    val onAir: StateFlow<Boolean> = _onAir
    private val _state = MutableStateFlow<StationState?>(null)
    /** The last state published, for the host's own screen. */
    val state: StateFlow<StationState?> = _state
    private var seq = System.currentTimeMillis() / 1000
    private var heartbeat: Job? = null
    private var pending: Job? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_PLAY_WHEN_READY_CHANGED,
                    Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_TIMELINE_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED,
                )
            ) schedule()
        }
    }

    fun start() {
        if (_onAir.value) return
        _onAir.value = true
        client.acquire(USER)
        main.post { graph.player.player.addListener(listener) }
        schedule()
        heartbeat = scope.launch { while (isActive) { delay(HEARTBEAT_MS); schedule() } }
        DebugLog.i(TAG, "on air")
    }

    fun stop() {
        if (!_onAir.value) return
        _onAir.value = false
        heartbeat?.cancel(); heartbeat = null
        pending?.cancel(); pending = null
        main.post { graph.player.player.removeListener(listener) }
        scope.launch {
            runCatching { publish(StationState(name = store.name.first(), status = Station.STATUS_OFF, seq = ++seq)) }
            client.release(USER)
            _state.value = null
        }
        DebugLog.i(TAG, "off air")
    }

    private fun schedule() {
        pending?.cancel()
        pending = scope.launch { delay(DEBOUNCE_MS); runCatching { publishNow() }.onFailure { DebugLog.w(TAG, "publish failed", it) } }
    }

    private suspend fun publishNow() {
        if (!_onAir.value) return
        val snap = snapshot()
        val now = snap.trackId?.let { graph.library.track(it) }
        val next = snap.nextIds.mapNotNull { graph.library.track(it) }
        val state = StationState(
            name = store.name.first(),
            status = if (now == null) Station.STATUS_PAUSED else if (snap.playing) Station.STATUS_ON_AIR else Station.STATUS_PAUSED,
            now = now?.let(::toStationTrack),
            startedAt = snap.at - snap.positionMs,
            positionMs = snap.positionMs,
            next = next.map(::toStationTrack),
            seq = ++seq,
        )
        publish(state)
    }

    private suspend fun publish(state: StationState) {
        val id = store.ensureIdentity()
        val ev = Nostr.sign(id.secretHex, Station.KIND, Station.tags, Station.encode(state))
        client.publish(ev)
        _state.value = state
        DebugLog.d(TAG, "published seq=${state.seq} ${state.status} ${state.now?.artist} - ${state.now?.title}")
    }

    private fun toStationTrack(t: TrackEntity) = StationTrack(
        title = t.title, artist = t.artist, album = t.album, durationMs = t.durationMs,
        link = t.sourceLink?.takeIf { it.startsWith("http") }, cover = null, key = t.id.toString(),
    )

    private class Snap(val trackId: Long?, val nextIds: List<Long>, val positionMs: Long, val playing: Boolean, val at: Long)

    private suspend fun snapshot(): Snap = suspendCancellableCoroutine { cont ->
        main.post {
            val p = graph.player.player
            val idx = p.currentMediaItemIndex
            val next = if (p.mediaItemCount == 0) emptyList() else (idx + 1 until minOf(p.mediaItemCount, idx + 1 + LOOKAHEAD)).mapNotNull { MediaItems.trackId(p.getMediaItemAt(it)) }
            val playing = p.playWhenReady && p.playbackState == Player.STATE_READY
            cont.resume(Snap(p.currentMediaItem?.let(MediaItems::trackId), next, p.currentPosition.coerceAtLeast(0), playing, System.currentTimeMillis()))
        }
    }

    companion object {
        private const val TAG = "StationHost"
        private const val USER = "host"
        private const val DEBOUNCE_MS = 400L
        private const val HEARTBEAT_MS = 30_000L
        /** How many upcoming tracks listeners get to prefetch. More leaks the playlist early and wastes downloads on skips. */
        const val LOOKAHEAD = 3
    }
}
