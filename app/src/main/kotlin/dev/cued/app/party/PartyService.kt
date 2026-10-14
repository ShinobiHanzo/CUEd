package dev.cued.app.party

import android.util.Log
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.cued.app.Graph
import dev.cued.app.playback.MediaItems
import dev.cued.app.playback.PlayerConnection
import dev.cued.core.party.Party
import dev.cued.core.party.PartyEnvelope
import dev.cued.core.party.PartyHello
import dev.cued.core.party.PartyNow
import dev.cued.core.party.PartyTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

/**
 * The host side of a party, independent of transport: [handle] takes one
 * signed request (JSON text) and returns the signed reply. [PartyHost] feeds it
 * from the LAN server and the Bluetooth GATT server alike.
 *
 * Only friends (status `friends` in the friends list) get through; what they
 * can do is search, queue, and, when the *guests may control playback* setting
 * is on, skip / pause / resume. Replies are signed with this phone's station
 * key, so a guest can check it is really talking to its friend.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PartyService(private val graph: Graph) {
    data class Event(val who: String, val what: String, val at: Long)

    private val nonces = Party.NonceCache()
    private val _log = MutableStateFlow<List<Event>>(emptyList())
    /** Recent guest actions, newest first, for the Party screen. */
    val log: StateFlow<List<Event>> = _log

    /** A controller of our own: the host's phone player, driven from the main thread. */
    val connection = PlayerConnection(graph.app)

    val host: PartyHost by lazy { PartyHost(graph.app, this) }

    suspend fun identity() = graph.station.store.ensureIdentity()
    suspend fun name(): String = graph.desktop.store.phoneName.first()

    suspend fun handle(requestJson: String): String {
        val req = Party.decode(requestJson) ?: return Party.encode(Party.sign(identity().secretHex, Party.OP_HELLO, Party.errorArg("not a party envelope"), ok = false))
        return Party.encode(handle(req))
    }

    suspend fun handle(req: PartyEnvelope): PartyEnvelope {
        val id = identity()
        fun reply(ok: Boolean, arg: JsonElement) = Party.sign(id.secretHex, req.op, arg, ok = ok, nonce = req.nonce)
        fun err(m: String) = reply(false, Party.errorArg(m))

        val friends = graph.desktop.store.friends.first()
        val nowSec = System.currentTimeMillis() / 1000
        when (val c = Party.check(req, nowSec, { nonces.seenBefore(it, nowSec) }, { pk -> friends.firstOrNull { it.pubkey == pk }?.status })) {
            is Party.Check.Bad -> return err(c.reason)
            Party.Check.Ok -> {}
        }
        val who = friends.first { it.pubkey == req.pubkey }.label
        val controls = graph.settings.playback.first().partyControls
        if (req.op in Party.CONTROL_OPS && !controls) return err("the host only lets guests queue songs")

        return try {
            when (req.op) {
                Party.OP_HELLO -> reply(true, Party.toJson(PartyHello(name(), id.pubkeyHex, controls, now())))
                Party.OP_NOW -> reply(true, Party.toJson(now()))
                Party.OP_SEARCH -> {
                    val q = Party.queryOf(req.arg).trim()
                    if (q.isEmpty()) return reply(true, Party.toJson(emptyList<PartyTrack>()))
                    val tracks = graph.library.search(q).first().take(Party.MAX_RESULTS)
                    reply(true, Party.toJson(tracks.map { PartyTrack(id = it.id, title = it.title, artist = it.artist, album = it.album, durationMs = it.durationMs) }))
                }
                Party.OP_QUEUE -> {
                    val t = Party.trackOf(req.arg) ?: return err("bad track")
                    val track = (if (t.id != 0L) graph.library.track(t.id) else null)
                        ?: (if (t.title.isNotBlank()) graph.db.tracks().byTitleArtist(t.title, t.artist) else null)
                        ?: return err("no such track on this phone")
                    val startedFresh = withContext(Dispatchers.Main) {
                        val p = connection.player
                        if (p == null) { connection.connect(); null }
                        else if (p.mediaItemCount == 0 || p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) { connection.play(listOf(track)); true }
                        else { connection.enqueue(listOf(track)); false }
                    } ?: return err("the host's player is not ready yet, try again")
                    announce(who, (if (startedFresh) "started " else "queued ") + "${track.artist} – ${track.title}")
                    reply(true, Party.toJson(now()))
                }
                Party.OP_SKIP -> { withContext(Dispatchers.Main) { connection.player?.seekToNext() }; announce(who, "skipped"); reply(true, Party.toJson(now())) }
                Party.OP_PAUSE -> { withContext(Dispatchers.Main) { connection.player?.pause() }; announce(who, "paused"); reply(true, Party.toJson(now())) }
                Party.OP_PLAY -> { withContext(Dispatchers.Main) { connection.player?.let { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } }; announce(who, "resumed"); reply(true, Party.toJson(now())) }
                else -> err("unknown op")
            }
        } catch (e: Exception) {
            Log.w(TAG, "party op ${req.op} failed", e)
            err(e.message ?: "failed")
        }
    }

    private suspend fun now(): PartyNow = withContext(Dispatchers.Main) {
        val p = connection.player ?: return@withContext PartyNow()
        val cur = p.currentMediaItem
        val idx = p.currentMediaItemIndex
        val queue = (idx + 1 until p.mediaItemCount).take(10).map { partyTrack(p.getMediaItemAt(it)) }
        PartyNow(current = cur?.let { partyTrack(it) }, positionMs = p.currentPosition.coerceAtLeast(0), playing = p.isPlaying, queue = queue, queueLength = (p.mediaItemCount - idx - 1).coerceAtLeast(0))
    }

    private fun partyTrack(item: androidx.media3.common.MediaItem) = PartyTrack(
        id = MediaItems.trackId(item) ?: 0L, title = item.mediaMetadata.title?.toString().orEmpty(), artist = item.mediaMetadata.artist?.toString().orEmpty(),
        album = item.mediaMetadata.albumTitle?.toString().orEmpty(), durationMs = MediaItems.durationMs(item),
    )

    private fun announce(who: String, what: String) {
        _log.value = (listOf(Event(who, what, System.currentTimeMillis())) + _log.value).take(50)
    }

    companion object { private const val TAG = "Party" }
}
