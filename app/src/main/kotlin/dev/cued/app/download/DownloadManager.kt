package dev.cued.app.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import dev.cued.app.util.DebugLog
import dev.cued.app.Graph
import dev.cued.app.data.DownloadBackend
import dev.cued.app.data.db.DownloadJobEntity
import dev.cued.core.share.SourceLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Queues spotdl downloads and runs them through whichever backend is
 * configured. spotdl is Python; a phone can run it inside Termux, or a
 * laptop/Pi on the same Wi-Fi can run the tiny companion server in
 * `tools/spotdl-server`. Either way the files end up in Music/CUEd and get
 * picked up by the library scan.
 */
class DownloadManager(private val context: Context, private val graph: Graph) {
    private val db get() = graph.db
    private val mutex = Mutex()
    private var pump: Job? = null
    private val resolver = LinkResolver("CUEd/${dev.cued.app.BuildConfig.VERSION_NAME} (https://github.com/ShinobiHanzo/CUEd)")

    val jobs: Flow<List<DownloadJobEntity>> = db.downloads().observeAll()
    /** Output of the last "Test spotdl in Termux" run. */
    val termuxTest = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** Output of the last built-in self-test. */
    val nativeTest = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    fun testNative() {
        nativeTest.value = "Testing…"
        graph.appScope.launch {
            val s = graph.settings.downloadNow()
            nativeTest.value = dev.cued.app.download.native.NativeDownloader(context, s.spotifyClientId, s.spotifyClientSecret).selfTest()
        }
    }
    fun repairTermux() { termuxTest.value = "Repairing spotdl in Termux. This reinstalls it for the current Python and can take several minutes…"; if (!TermuxDownloader(context).repair()) termuxTest.value = "Termux not installed or permission not granted" }
    fun testTermux() { termuxTest.value = "Running in Termux…"; if (!TermuxDownloader(context).test()) termuxTest.value = "Termux not installed or permission not granted" }

    /**
     * [source] may be a link from any platform, a search, or raw share-sheet text.
     * Links are resolved to something spotdl understands when the job runs.
     */
    fun enqueue(source: String, title: String? = null, artist: String? = null) {
        val url = SourceLinks.extractUrl(source)
        val cleaned = SourceLinks.canonical(url ?: source.trim())
        val hint = title ?: SourceLinks.shareTextToQuery(source)
        graph.appScope.launch {
            val backend = graph.settings.downloadNow().backend
            db.downloads().insert(
                DownloadJobEntity(source = cleaned, title = hint, artist = artist, backend = backend.name, status = STATUS_QUEUED, createdAt = System.currentTimeMillis())
            )
            pump()
        }
    }

    fun retry(jobId: Long) {
        graph.appScope.launch {
            val j = db.downloads().byId(jobId) ?: return@launch
            db.downloads().update(j.copy(status = STATUS_QUEUED, message = null, progress = 0f, finishedAt = null, remoteJobId = null))
            pump()
        }
    }

    fun clearFinished() { graph.appScope.launch { db.downloads().clearFinished() } }

    fun pump() {
        graph.appScope.launch {
            mutex.withLock {
                if (pump?.isActive == true) return@launch
                pump = graph.appScope.launch { drain() }
            }
        }
    }

    private suspend fun drain() {
        while (true) {
            val job = db.downloads().pending().firstOrNull { it.status == STATUS_QUEUED } ?: return
            val settings = graph.settings.downloadNow()
            val backend = runCatching { DownloadBackend.valueOf(job.backend) }.getOrDefault(settings.backend)
            db.downloads().update(job.copy(status = STATUS_RUNNING, message = "Resolving link…"))
            DebugLog.i(TAG, "job #${job.id} start backend=$backend source=${job.source} hint=${job.title}")

            // 1. Turn whatever was shared into something spotdl can take.
            val resolved = runCatching { resolver.resolve(job.source, job.title) }
                .getOrElse { LinkResolver.Result.Unsupported("Couldn't resolve the link: ${it.message}") }
            val source = when (resolved) {
                is LinkResolver.Result.Unsupported -> {
                    DebugLog.w(TAG, "job #${job.id} unsupported: ${resolved.reason}")
                    db.downloads().update(job.copy(status = STATUS_FAILED, message = resolved.reason, finishedAt = System.currentTimeMillis()))
                    continue
                }
                is LinkResolver.Result.Expanded -> {
                    // A playlist/album from a platform spotdl can't read: one job per track.
                    for (item in resolved.items) {
                        db.downloads().insert(
                            DownloadJobEntity(source = item.source, title = item.title, artist = item.artist, backend = backend.name, status = STATUS_QUEUED, createdAt = System.currentTimeMillis(), artworkUrl = item.artworkUrl)
                        )
                    }
                    db.downloads().update(job.copy(status = STATUS_DONE, progress = 1f, message = resolved.note, finishedAt = System.currentTimeMillis()))
                    continue
                }
                is LinkResolver.Result.Direct -> resolved.source
            }
            val note = (resolved as LinkResolver.Result.Direct).note
            DebugLog.i(TAG, "job #${job.id} resolved -> $source (${note ?: "direct"})")
            db.downloads().update(job.copy(status = STATUS_RUNNING, message = note ?: "Downloading…"))

            // 1b. Built-in backend expands Spotify/YouTube albums and playlists itself.
            val native = if (backend == DownloadBackend.BUILT_IN) dev.cued.app.download.native.NativeDownloader(context, settings.spotifyClientId, settings.spotifyClientSecret, settings.enrichOnline) else null
            if (native != null) {
                val expandedResult = runCatching { native.expand(source) }
                if (expandedResult.isFailure) {
                    val e = expandedResult.exceptionOrNull()
                    DebugLog.e(TAG, "job #${job.id} expand failed", e)
                    db.downloads().update(job.copy(status = STATUS_FAILED, message = e?.message ?: e.toString(), finishedAt = System.currentTimeMillis()))
                    continue
                }
                val expanded = expandedResult.getOrNull()
                if (expanded != null) {
                    for (item in expanded.items) db.downloads().insert(DownloadJobEntity(source = item.source, title = item.title, artist = item.artist, backend = backend.name, status = STATUS_QUEUED, createdAt = System.currentTimeMillis(), artworkUrl = item.artworkUrl))
                    db.downloads().update(job.copy(status = STATUS_DONE, progress = 1f, message = expanded.note, finishedAt = System.currentTimeMillis()))
                    continue
                }
            }

            // 2. Run it.
            val result = runCatching {
                when (backend) {
                    DownloadBackend.BUILT_IN -> native!!.download(job, source, settings.format, onProgress = { p ->
                        graph.appScope.launch { db.downloads().byId(job.id)?.let { db.downloads().update(it.copy(progress = p)) } }
                    }, onArtwork = { url -> graph.appScope.launch { db.downloads().setArtwork(job.id, url) } })
                    DownloadBackend.TERMUX -> TermuxDownloader(context).start(job, source, settings.format, settings.generateLrc)
                    DownloadBackend.COMPANION -> CompanionDownloader(context, settings.companionUrl, settings.format).run(job, source, settings.generateLrc) { p ->
                        graph.appScope.launch { db.downloads().byId(job.id)?.let { db.downloads().update(it.copy(progress = p)) } }
                    }
                }
            }
            result.onFailure { e ->
                Log.w(TAG, "download failed", e)
                DebugLog.e(TAG, "job #${job.id} failed: ${e.message}", e)
                db.downloads().byId(job.id)?.let { db.downloads().update(it.copy(status = STATUS_FAILED, message = e.message ?: e.toString(), finishedAt = System.currentTimeMillis())) }
            }.onSuccess { outcome ->
                when (outcome) {
                    is Outcome.Handed -> db.downloads().byId(job.id)?.let { db.downloads().update(it.copy(message = "Running in Termux…")) }
                    is Outcome.Done -> {
                        DebugLog.i(TAG, "job #${job.id} done: ${outcome.summary} ids=${outcome.mediaStoreIds}")
                        db.downloads().byId(job.id)?.let { db.downloads().update(it.copy(status = STATUS_DONE, progress = 1f, message = outcome.summary, finishedAt = System.currentTimeMillis())) }
                        afterFilesArrived(outcome.mediaStoreIds, if (source.startsWith("http")) source else job.source, job.createdAt, outcome.lrcByBase, outcome.genresByMediaStoreId, outcome.metaByMediaStoreId)
                    }
                }
            }
        }
    }

    /** Called when a backend reports completion (also from [TermuxResultReceiver]). */
    fun complete(jobId: Long, ok: Boolean, message: String?) {
        DebugLog.i(TAG, "termux job #$jobId complete ok=$ok: $message")
        graph.appScope.launch {
            val j = db.downloads().byId(jobId) ?: return@launch
            db.downloads().update(j.copy(status = if (ok) STATUS_DONE else STATUS_FAILED, progress = if (ok) 1f else j.progress, message = message, finishedAt = System.currentTimeMillis()))
            if (ok) {
                scanDownloadFolder()
                afterFilesArrived(emptyList(), j.source, j.createdAt, emptyMap(), emptyMap())
            }
            pump()
        }
    }

    private suspend fun afterFilesArrived(mediaStoreIds: List<Long>, source: String, since: Long, lrcByBase: Map<String, String>, genresByMediaStoreId: Map<Long, List<String>> = emptyMap(), metaByMediaStoreId: Map<Long, KnownMeta> = emptyMap()) {
        graph.library.rescan()
        // MediaStore sometimes publishes a row before it has read the tags; our own tags win until it catches up.
        for ((msId, m) in metaByMediaStoreId) db.tracks().byMediaStoreId(msId)?.let { t ->
            if (t.artist == "Unknown artist" || t.title != m.title || t.album != (m.album ?: "")) {
                DebugLog.d(TAG, "patching row #${t.id} from download meta (MediaStore had \"${t.title}\" / \"${t.artist}\")")
                db.tracks().update(t.copy(title = m.title, artist = m.artist, album = m.album ?: t.album, albumArtist = m.albumArtist ?: t.albumArtist, trackNo = if (m.trackNo > 0) m.trackNo else t.trackNo, year = if (m.year > 0) m.year else t.year))
            }
        }
        // Which tracks are new? Companion tells us the MediaStore ids; Termux doesn't, so fall back to "added since the job started".
        val tracks = if (mediaStoreIds.isNotEmpty()) mediaStoreIds.mapNotNull { db.tracks().byMediaStoreId(it) }
        else db.tracks().addedSince(since - 60_000L)
        val ds = graph.settings.downloadNow()
        val ls = graph.settings.lyricsNow()
        for (t in tracks) {
            if (t.sourceLink == null && source.startsWith("http")) graph.library.setSourceLink(t.id, source)
            genresByMediaStoreId[t.mediaStoreId]?.takeIf { it.isNotEmpty() }?.let { graph.library.setGenresAuto(t.id, it) }
            if (!t.isLong) graph.analysis.request(t.id)
            // .lrc that came back from the companion, matched by file base name.
            val base = t.path?.let { java.io.File(it).nameWithoutExtension }
            val lrc = base?.let { lrcByBase[it] }
            if (lrc != null) {
                val synced = dev.cued.core.lyrics.Lrc.isSynced(lrc)
                db.lyrics().upsert(
                    dev.cued.app.data.db.LyricsEntity(
                        t.id, if (synced) dev.cued.core.lyrics.Lrc.toPlain(dev.cued.core.lyrics.Lrc.parse(lrc)) else lrc,
                        if (synced) lrc else null, dev.cued.app.lyrics.LyricsRepository.SOURCE_SIDECAR, System.currentTimeMillis(),
                    )
                )
            } else if (ds.fetchLyricsAfter && !t.isLong) {
                graph.appScope.launch { runCatching { graph.lyrics.ensure(t.id, allowOnline = ls.fetchOnline) } }
            }
        }
    }

    /** Ask MediaStore to index whatever Termux dropped in the download folder. */
    suspend fun scanDownloadFolder() = withContext(Dispatchers.IO) {
        val dir = downloadDir()
        val files = runCatching { dir.listFiles()?.filter { it.isFile }?.map { it.absolutePath } }.getOrNull().orEmpty()
        if (files.isEmpty()) return@withContext
        MediaScannerConnection.scanFile(context, files.toTypedArray(), null, null)
        kotlinx.coroutines.delay(1_500)
    }

    sealed class Outcome {
        /** Termux took the command; a broadcast will tell us when it is done. */
        data object Handed : Outcome()
        data class Done(
            val mediaStoreIds: List<Long>, val summary: String, val lrcByBase: Map<String, String> = emptyMap(),
            val genresByMediaStoreId: Map<Long, List<String>> = emptyMap(),
            /** Tags the downloader wrote itself, so the library row is right even if MediaStore has not read the file yet. */
            val metaByMediaStoreId: Map<Long, KnownMeta> = emptyMap(),
        ) : Outcome()
    }

    /** What the built-in downloader knows about a file it just wrote. */
    data class KnownMeta(val title: String, val artist: String, val album: String?, val albumArtist: String?, val trackNo: Int, val year: Int) {
        companion object { }
    }

    companion object {
        private const val TAG = "DownloadManager"
        const val STATUS_QUEUED = "QUEUED"
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_DONE = "DONE"
        const val STATUS_FAILED = "FAILED"
        const val RELATIVE_DIR = "Music/CUEd"

        fun downloadDir(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "CUEd")

        /** Creates a MediaStore entry in Music/CUEd and returns its uri plus id. Caller writes the bytes then calls [finishPending]. */
        fun createPendingAudio(context: Context, displayName: String, mime: String): Pair<Uri, Long> {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Audio.Media.RELATIVE_PATH, RELATIVE_DIR)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                } else {
                    val dir = downloadDir().apply { mkdirs() }
                    put(MediaStore.Audio.Media.DATA, File(dir, displayName).absolutePath)
                }
            }
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val uri = context.contentResolver.insert(collection, values) ?: error("MediaStore refused the insert")
            return uri to android.content.ContentUris.parseId(uri)
        }

        /** Re-index one existing file after its tags were rewritten; waits for the scanner. */
        fun rescanFile(context: Context, uri: Uri) {
            val path = runCatching {
                context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull() ?: return
            val latch = java.util.concurrent.CountDownLatch(1)
            android.media.MediaScannerConnection.scanFile(context, arrayOf(path), null) { _, _ -> latch.countDown() }
            latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
        }

        fun finishPending(context: Context, uri: Uri) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            }
            // Ask the scanner outright (and wait for it) so tags and the cover are indexed before we rescan.
            val path = runCatching {
                context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()
            if (path != null) {
                val latch = java.util.concurrent.CountDownLatch(1)
                android.media.MediaScannerConnection.scanFile(context, arrayOf(path), null) { _, _ -> latch.countDown() }
                latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
    }
}
