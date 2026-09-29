package dev.cued.app.analysis

import android.content.Context
import android.net.Uri
import android.util.Log
import dev.cued.app.data.db.CuedDatabase
import dev.cued.app.data.db.TrackEntity
import dev.cued.core.dsp.BpmDetector
import dev.cued.core.mix.TrackTempo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Serial background tempo analyser. One track at a time so an entry-level phone
 * stays responsive; the playback engine can jump the queue for the track
 * that is about to be mixed in.
 */
class AnalysisQueue(
    private val context: Context,
    private val db: CuedDatabase,
    private val scope: CoroutineScope,
) {
    private val decoder = AudioDecoder(context)

    private val mutex = Mutex()
    private val queue = ArrayDeque<Long>()
    private val waiters = HashMap<Long, MutableList<CompletableDeferred<TrackEntity?>>>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val _current = MutableStateFlow<Long?>(null)
    val current: StateFlow<Long?> = _current
    private val _pending = MutableStateFlow(0)
    val pending: StateFlow<Int> = _pending

    init { scope.launch { worker() } }

    /** Queue a track; [urgent] puts it at the front. */
    fun request(trackId: Long, urgent: Boolean = false) {
        scope.launch {
            mutex.withLock {
                queue.remove(trackId)
                if (urgent) queue.addFirst(trackId) else queue.addLast(trackId)
                _pending.value = queue.size
            }
            wake.trySend(Unit)
        }
    }

    /** Queue every track that has never been analysed. */
    fun sweep() {
        scope.launch {
            val todo = db.tracks().unanalysed(5_000)
            mutex.withLock {
                for (t in todo) if (t.id !in queue) queue.addLast(t.id)
                _pending.value = queue.size
            }
            wake.trySend(Unit)
        }
    }

    /** Returns the analysed track, analysing it now (front of queue) if needed. Null on timeout/failure. */
    suspend fun ensureAnalysed(trackId: Long, timeoutMs: Long = 20_000): TrackEntity? {
        val t = db.tracks().byId(trackId) ?: return null
        if (t.analysedAt != null) return t
        val deferred = CompletableDeferred<TrackEntity?>()
        mutex.withLock {
            waiters.getOrPut(trackId) { ArrayList() }.add(deferred)
            queue.remove(trackId)
            queue.addFirst(trackId)
            _pending.value = queue.size
        }
        wake.trySend(Unit)
        return withTimeoutOrNull(timeoutMs) { deferred.await() }
    }

    suspend fun tempoOf(trackId: Long): TrackTempo? {
        val t = db.tracks().byId(trackId) ?: return null
        return t.toTempo()
    }

    private suspend fun worker() {
        while (true) {
            val id = mutex.withLock { queue.removeFirstOrNull().also { _pending.value = queue.size } }
            if (id == null) { wake.receive(); continue }
            _current.value = id
            val result = runCatching { analyse(id) }.onFailure { Log.w(TAG, "analysis failed for $id", it) }.getOrNull()
            _current.value = null
            val ws = mutex.withLock { waiters.remove(id) }
            ws?.forEach { it.complete(result) }
        }
    }

    private suspend fun analyse(trackId: Long): TrackEntity? = withContext(Dispatchers.Default) {
        val track = db.tracks().byId(trackId) ?: return@withContext null
        // Tempo only: decode to ~11 kHz mono, capped so long mixes stay cheap.
        var pcm = FloatArray(RATE * 60)
        var count = 0
        val cap = RATE * BPM_MAX_SECONDS
        val info = decoder.decode(Uri.parse(track.uri), RATE, BPM_MAX_SECONDS) { samples, n ->
            val take = minOf(n, cap - count)
            if (take > 0) {
                if (count + take > pcm.size) pcm = pcm.copyOf(maxOf(pcm.size * 2, count + take))
                System.arraycopy(samples, 0, pcm, count, take)
                count += take
            }
        }
        val rate = info.outputRate
        val bpm = BpmDetector(sampleRate = rate, hopSize = maxOf(64, rate / 86)).analyse(pcm.copyOf(count))
        db.tracks().setAnalysis(
            trackId,
            bpm = bpm.bpm.takeIf { it > 0f },
            confidence = bpm.confidence,
            firstBeat = bpm.firstBeatSec,
            spectrogramPath = null,
            at = System.currentTimeMillis(),
        )
        db.tracks().byId(trackId)
    }

    companion object {
        private const val TAG = "AnalysisQueue"
        const val RATE = 11_025
        const val BPM_MAX_SECONDS = 60 * 8

        fun TrackEntity.toTempo(): TrackTempo? {
            val b = bpm ?: return null
            return TrackTempo(durationMs = durationMs, bpm = b, firstBeatSec = firstBeatSec ?: 0f, confidence = bpmConfidence ?: 0f)
        }
    }
}
