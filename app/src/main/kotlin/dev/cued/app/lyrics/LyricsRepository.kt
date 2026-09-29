package dev.cued.app.lyrics

import android.content.Context
import android.net.Uri
import android.util.Log
import dev.cued.app.data.Settings
import dev.cued.app.data.db.CuedDatabase
import dev.cued.app.data.db.LyricsEntity
import dev.cued.app.data.db.TrackEntity
import dev.cued.core.lyrics.Id3Lyrics
import dev.cued.core.lyrics.Lrc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Lyrics, offline-first:
 *  1. embedded in the file's ID3 tag (spotdl writes these),
 *  2. a `.lrc` / `.txt` sidecar next to the audio,
 *  3. lrclib.net, if the setting allows it.
 * Whatever is found is cached in the database; "not found" is cached too and
 * retried after a week so a missing song doesn't hammer the network.
 */
class LyricsRepository(
    private val context: Context,
    private val db: CuedDatabase,
    private val settings: Settings,
    private val scope: CoroutineScope,
    appVersion: String,
) {
    private val client = LrclibClient("CUEd/$appVersion (https://github.com/ShinobiHanzo/CUEd)")

    data class BulkProgress(val done: Int, val total: Int, val found: Int, val running: Boolean)
    private val _bulk = MutableStateFlow<BulkProgress?>(null)
    val bulk: StateFlow<BulkProgress?> = _bulk
    private var bulkJob: Job? = null
    val count: Flow<Int> = db.lyrics().observeCount()

    fun observe(trackId: Long): Flow<LyricsEntity?> = db.lyrics().observe(trackId)

    /** Cached lyrics, resolving them if we have never looked. Online only when [allowOnline]. */
    suspend fun ensure(trackId: Long, allowOnline: Boolean): LyricsEntity? {
        db.lyrics().get(trackId)?.let { cached ->
            val stale = cached.source == SOURCE_NONE && System.currentTimeMillis() - cached.fetchedAt > RETRY_MS
            if (!stale) return cached
        }
        return resolve(trackId, allowOnline)
    }

    /** Forces a fresh lookup including the network (if allowed by settings). */
    suspend fun refresh(trackId: Long): LyricsEntity? = resolve(trackId, settings.lyricsNow().fetchOnline)

    private suspend fun resolve(trackId: Long, allowOnline: Boolean): LyricsEntity? = withContext(Dispatchers.IO) {
        val track = db.tracks().byId(trackId) ?: return@withContext null
        val now = System.currentTimeMillis()
        val found = embedded(track)?.let { it to SOURCE_EMBEDDED }
            ?: sidecar(track)?.let { it to SOURCE_SIDECAR }
            ?: if (allowOnline) online(track)?.let { it to SOURCE_LRCLIB } else null
        val entity = if (found == null) LyricsEntity(trackId, null, null, SOURCE_NONE, now)
        else {
            val (text, source) = found
            if (Lrc.isSynced(text)) LyricsEntity(trackId, Lrc.toPlain(Lrc.parse(text)), text, source, now)
            else LyricsEntity(trackId, text, null, source, now)
        }
        db.lyrics().upsert(entity)
        entity
    }

    private fun embedded(t: TrackEntity): String? = runCatching {
        context.contentResolver.openInputStream(Uri.parse(t.uri))?.use { Id3Lyrics.read(it) }
    }.onFailure { Log.d(TAG, "embedded lyrics: ${it.message}") }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun sidecar(t: TrackEntity): String? {
        val path = t.path ?: return null
        val base = File(path).let { File(it.parentFile, it.nameWithoutExtension) }
        for (ext in listOf(".lrc", ".txt")) {
            val f = File(base.path + ext)
            val text = runCatching { if (f.isFile && f.length() < 512 * 1024) f.readText() else null }.getOrNull()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    private suspend fun online(t: TrackEntity): String? = runCatching {
        val hit = client.lookup(t.artist, t.title, t.album.takeIf { it.isNotBlank() }, (t.durationMs / 1000).toInt()) ?: return null
        if (hit.instrumental) return "[Instrumental]"
        hit.syncedLyrics?.takeIf { it.isNotBlank() } ?: hit.plainLyrics?.takeIf { it.isNotBlank() }
    }.onFailure { Log.w(TAG, "lrclib: ${it.message}") }.getOrNull()

    /** Walks the library for tracks without lyrics. Online, so it respects the setting. */
    fun fetchMissingAsync() {
        if (bulkJob?.isActive == true) return
        bulkJob = scope.launch {
            val allowOnline = settings.lyricsNow().fetchOnline
            val ids = db.lyrics().trackIdsWithoutLyrics(System.currentTimeMillis() - RETRY_MS)
            var found = 0
            _bulk.value = BulkProgress(0, ids.size, 0, running = true)
            for ((i, id) in ids.withIndex()) {
                val r = runCatching { resolve(id, allowOnline) }.getOrNull()
                if (r != null && r.source != SOURCE_NONE) found++
                _bulk.value = BulkProgress(i + 1, ids.size, found, running = true)
                if (allowOnline && (r == null || r.source == SOURCE_LRCLIB || r.source == SOURCE_NONE)) delay(400) // be polite to lrclib
            }
            _bulk.value = BulkProgress(ids.size, ids.size, found, running = false)
        }
    }

    fun cancelBulk() { bulkJob?.cancel(); _bulk.value = _bulk.value?.copy(running = false) }

    companion object {
        private const val TAG = "Lyrics"
        const val SOURCE_EMBEDDED = "embedded"
        const val SOURCE_SIDECAR = "sidecar"
        const val SOURCE_LRCLIB = "lrclib"
        const val SOURCE_NONE = "none"
        private const val RETRY_MS = 7L * 24 * 3600 * 1000
    }
}
