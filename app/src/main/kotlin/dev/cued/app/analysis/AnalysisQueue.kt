package dev.cued.app.analysis

import android.content.Context
import android.net.Uri
import android.util.Log
import dev.cued.app.data.db.CuedDatabase
import dev.cued.app.data.db.TrackEntity
import dev.cued.core.dsp.BpmDetector
import dev.cued.core.dsp.Spectrogram
import dev.cued.core.dsp.SpectrogramImage
import dev.cued.core.dsp.StreamingSpectrogram
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
 * Serial background analyser. One track at a time so an entry-level phone
 * stays responsive; the playback engine can jump the queue for the track
 * that is about to be mixed in.
 */
class AnalysisQueue(
    private val context: Context,
    private val db: CuedDatabase,
    private val scope: CoroutineScope,
) {
    val store = SpectrogramStore(context)
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
        val spectrogram = Spectrogram(fftSize = FFT_SIZE, hopSize = HOP, bands = BANDS, sampleRate = RATE, minHz = 30f, maxHz = RATE / 2f)
        val columns = ArrayList<FloatArray>(8192)
        val streaming = StreamingSpectrogram(spectrogram) { columns += it }
        // BPM works on a further-decimated copy (about 11 kHz), capped so long mixes stay cheap.
        var bpmPcm = FloatArray(RATE / 2 * 60)
        var bpmCount = 0
        var carry = 0f; var carryN = 0
        val bpmCap = RATE / 2 * BPM_MAX_SECONDS
        val info = decoder.decode(Uri.parse(track.uri), RATE, MAX_SECONDS) { samples, count ->
            streaming.push(samples, count)
            var i = 0
            while (i < count && bpmCount < bpmCap) {
                carry += samples[i]; carryN++
                if (carryN == 2) {
                    if (bpmCount == bpmPcm.size) bpmPcm = bpmPcm.copyOf(bpmPcm.size * 2)
                    bpmPcm[bpmCount++] = carry / 2f
                    carry = 0f; carryN = 0
                }
                i++
            }
        }
        streaming.finish()
        val bpmRate = info.outputRate / 2
        val bpm = BpmDetector(sampleRate = bpmRate, hopSize = maxOf(64, bpmRate / 86)).analyse(bpmPcm.copyOf(bpmCount))

        val image = SpectrogramImage(columns.size, BANDS, FloatArray(columns.size * BANDS).also { data ->
            columns.forEachIndexed { c, col -> System.arraycopy(col, 0, data, c * BANDS, BANDS) }
        }).downsample(STORE_COLUMNS)
        val file = store.save(trackId, image)
        db.tracks().setAnalysis(
            trackId,
            bpm = bpm.bpm.takeIf { it > 0f },
            confidence = bpm.confidence,
            firstBeat = bpm.firstBeatSec,
            spectrogramPath = file.absolutePath,
            at = System.currentTimeMillis(),
        )
        db.tracks().byId(trackId)
    }

    companion object {
        private const val TAG = "AnalysisQueue"
        const val RATE = 22_050
        const val FFT_SIZE = 1024
        const val HOP = 512
        const val BANDS = 64
        const val STORE_COLUMNS = 512
        const val MAX_SECONDS = 60 * 30
        const val BPM_MAX_SECONDS = 60 * 8

        fun TrackEntity.toTempo(): TrackTempo? {
            val b = bpm ?: return null
            return TrackTempo(durationMs = durationMs, bpm = b, firstBeatSec = firstBeatSec ?: 0f, confidence = bpmConfidence ?: 0f)
        }
    }
}
