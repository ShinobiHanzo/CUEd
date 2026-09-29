package dev.cued.app.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
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

    val jobs: Flow<List<DownloadJobEntity>> = db.downloads().observeAll()

    fun enqueue(source: String, title: String? = null, artist: String? = null) {
        val cleaned = SourceLinks.canonical(source)
        graph.appScope.launch {
            val backend = graph.settings.downloadNow().backend
            db.downloads().insert(
                DownloadJobEntity(source = cleaned, title = title, artist = artist, backend = backend.name, status = STATUS_QUEUED, createdAt = System.currentTimeMillis())
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
            db.downloads().update(job.copy(status = STATUS_RUNNING))
            val result = runCatching {
                when (backend) {
                    DownloadBackend.TERMUX -> TermuxDownloader(context).start(job, settings.format)
                    DownloadBackend.COMPANION -> CompanionDownloader(context, settings.companionUrl, settings.format).run(job) { p ->
                        graph.appScope.launch { db.downloads().byId(job.id)?.let { db.downloads().update(it.copy(progress = p)) } }
                    }
                }
            }
            result.onFailure { e ->
                Log.w(TAG, "download failed", e)
                db.downloads().byId(job.id)?.let { db.downloads().update(it.copy(status = STATUS_FAILED, message = e.message ?: e.toString(), finishedAt = System.currentTimeMillis())) }
            }.onSuccess { outcome ->
                when (outcome) {
                    is Outcome.Handed -> db.downloads().byId(job.id)?.let { db.downloads().update(it.copy(message = "Running in Termux…")) }
                    is Outcome.Done -> {
                        db.downloads().byId(job.id)?.let { db.downloads().update(it.copy(status = STATUS_DONE, progress = 1f, message = outcome.summary, finishedAt = System.currentTimeMillis())) }
                        afterFilesArrived(outcome.mediaStoreIds, job.source)
                    }
                }
            }
        }
    }

    /** Called when a backend reports completion (also from [TermuxResultReceiver]). */
    fun complete(jobId: Long, ok: Boolean, message: String?) {
        graph.appScope.launch {
            val j = db.downloads().byId(jobId) ?: return@launch
            db.downloads().update(j.copy(status = if (ok) STATUS_DONE else STATUS_FAILED, progress = if (ok) 1f else j.progress, message = message, finishedAt = System.currentTimeMillis()))
            if (ok) {
                scanDownloadFolder()
                afterFilesArrived(emptyList(), j.source)
            }
            pump()
        }
    }

    private suspend fun afterFilesArrived(mediaStoreIds: List<Long>, source: String) {
        graph.library.rescan()
        // Remember where each new file came from so the track can be re-shared as a link.
        for (msId in mediaStoreIds) {
            db.tracks().byMediaStoreId(msId)?.let { t ->
                graph.library.setSourceLink(t.id, source)
                graph.analysis.request(t.id)
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
        data class Done(val mediaStoreIds: List<Long>, val summary: String) : Outcome()
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

        fun finishPending(context: Context, uri: Uri) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            }
        }
    }
}
