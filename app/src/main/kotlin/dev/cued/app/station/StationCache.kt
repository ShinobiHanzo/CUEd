package dev.cued.app.station

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import dev.cued.app.Graph
import dev.cued.app.data.db.TrackEntity
import dev.cued.app.download.DownloadManager
import dev.cued.app.download.native.NativeDownloader
import dev.cued.app.download.native.Tagger
import dev.cued.app.util.DebugLog
import dev.cued.core.station.StationTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Resolves a station entry to something this phone can play: a library track
 * with the same title and artist, or a temporary download from the entry's
 * source link kept in the cache (bounded, oldest first) and hidden from the
 * library until "Keep" moves it into Music/CUEd.
 */
class StationCache(private val graph: Graph, private val store: StationStore) {
    sealed class Fetch {
        data object Pending : Fetch()
        data class Fetching(val progress: Float) : Fetch()
        data class Ready(val trackId: Long, val fromLibrary: Boolean) : Fetch()
        data class Failed(val reason: String) : Fetch()
    }

    private val dir = File(graph.app.cacheDir, "station").apply { mkdirs() }
    private val scope = graph.appScope
    private val jobs = HashMap<String, Job>()
    private val limit = Semaphore(2)
    private val _status = MutableStateFlow<Map<String, Fetch>>(emptyMap())
    /** Entry key → where its file stands. */
    val status: StateFlow<Map<String, Fetch>> = _status

    fun keyOf(t: StationTrack): String = t.key.ifBlank { "${t.artist}|${t.title}".lowercase() }

    /** Starts (or re-starts after a failure, when [retry]) resolving this entry. Idempotent while in flight or ready. */
    @Synchronized fun ensure(t: StationTrack, retry: Boolean = false) {
        val key = keyOf(t)
        val cur = _status.value[key]
        if (cur is Fetch.Ready || (cur is Fetch.Failed && !retry) || jobs[key]?.isActive == true) return
        set(key, Fetch.Pending)
        jobs[key] = scope.launch {
            limit.withPermit {
                runCatching { fetch(t, key) }.onFailure { e ->
                    DebugLog.w(TAG, "fetch failed for ${t.artist} - ${t.title}", e)
                    set(key, Fetch.Failed(e.message ?: "Download failed"))
                }
            }
        }
    }

    private suspend fun fetch(t: StationTrack, key: String) = withContext(Dispatchers.IO) {
        graph.db.tracks().byTitleArtist(t.title, t.artist)?.let { have ->
            if (have.kind != TrackEntity.KIND_STATION) { set(key, Fetch.Ready(have.id, fromLibrary = true)); return@withContext }
            if (have.path != null && File(have.path).exists()) { set(key, Fetch.Ready(have.id, fromLibrary = false)); return@withContext }
            graph.db.tracks().deleteById(have.id)
        }
        if (!networkAllowed()) error("Waiting for Wi-Fi (downloading on mobile data is off in the station settings)")
        set(key, Fetch.Fetching(0f))
        val s = graph.settings.downloadNow()
        val dl = NativeDownloader(graph.app, s.spotifyClientId, s.spotifyClientSecret, enrichOnline = false)
        val base = File(dir, safe(key).ifBlank { System.currentTimeMillis().toString() })
        val file = dl.fetchTemp(t, base) { p -> set(key, Fetch.Fetching(p)) }
        val probe = Tagger.probe(file)
        if (!probe.first) { file.delete(); error("The downloaded file isn't playable (${probe.second})") }
        val duration = runCatching {
            val r = MediaMetadataRetriever()
            try { r.setDataSource(file.path); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() } finally { r.release() }
        }.getOrNull() ?: t.durationMs
        val id = graph.db.tracks().insert(TrackEntity(
            mediaStoreId = -System.currentTimeMillis(), uri = Uri.fromFile(file).toString(), path = file.path,
            title = t.title, artist = t.artist, album = t.album, albumId = null, durationMs = duration,
            addedAt = System.currentTimeMillis(), sourceLink = t.link, kind = TrackEntity.KIND_STATION,
        ))
        set(key, Fetch.Ready(id, fromLibrary = false))
        DebugLog.i(TAG, "cached ${t.artist} - ${t.title} (${file.length() / 1024} KB)")
    }

    /** Moves a cached station track into the music library for good. Returns the library track id. */
    suspend fun keep(trackId: Long): Long? = withContext(Dispatchers.IO) {
        val t = graph.db.tracks().byId(trackId) ?: return@withContext null
        if (t.kind != TrackEntity.KIND_STATION) return@withContext t.id
        val file = t.path?.let(::File)?.takeIf { it.exists() } ?: return@withContext null
        val ext = file.extension.lowercase()
        val mime = when (ext) { "mp3" -> "audio/mpeg"; "m4a", "mp4" -> "audio/mp4"; "webm" -> "audio/webm"; "opus", "ogg" -> "audio/ogg"; else -> "audio/mpeg" }
        val name = "${t.artist} - ${t.title}.$ext".replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val (uri, msId) = DownloadManager.createPendingAudio(graph.app, name, mime)
        try { graph.app.contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } } }
        catch (e: Exception) { graph.app.contentResolver.delete(uri, null, null); throw e }
        DownloadManager.finishPending(graph.app, uri)
        graph.library.rescan()
        val kept = graph.db.tracks().byMediaStoreId(msId)
        kept?.let { k -> t.sourceLink?.let { graph.library.setSourceLink(k.id, it) }; if (!k.isLong) graph.analysis.request(k.id) }
        graph.db.tracks().deleteById(t.id); file.delete()
        synchronized(this@StationCache) { _status.value = _status.value.mapValues { (_, v) -> if (v is Fetch.Ready && v.trackId == t.id && kept != null) Fetch.Ready(kept.id, true) else v } }
        kept?.id
    }

    /** Drops the oldest cached files over the cap, never touching [protect] (now + next). */
    suspend fun evict(protect: Set<Long>) = withContext(Dispatchers.IO) {
        val cap = store.cacheMb.first().toLong() * 1024 * 1024
        val rows = graph.db.tracks().stationRows().sortedBy { it.addedAt }
        var total = rows.sumOf { it.path?.let { p -> File(p).length() } ?: 0L }
        for (r in rows) {
            if (total <= cap) break
            if (r.id in protect) continue
            val f = r.path?.let(::File)
            total -= f?.length() ?: 0L
            f?.delete(); graph.db.tracks().deleteById(r.id)
            synchronized(this@StationCache) { _status.value = _status.value.filterValues { !(it is Fetch.Ready && it.trackId == r.id) } }
        }
    }

    /** Removes every cached station file and row (used when leaving with nothing to keep, and from settings). */
    suspend fun clear() = withContext(Dispatchers.IO) {
        for (r in graph.db.tracks().stationRows()) { r.path?.let { File(it).delete() }; graph.db.tracks().deleteById(r.id) }
        dir.listFiles()?.forEach { it.delete() }
        synchronized(this@StationCache) { _status.value = _status.value.filterValues { it is Fetch.Ready && it.fromLibrary } }
    }

    private suspend fun networkAllowed(): Boolean {
        val cm = graph.app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return true
        return store.prefetchOnMobile.first()
    }

    @Synchronized private fun set(key: String, f: Fetch) { _status.value = _status.value + (key to f) }
    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)

    companion object { private const val TAG = "StationCache" }
}
