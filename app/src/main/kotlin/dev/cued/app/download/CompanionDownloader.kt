package dev.cued.app.download

import android.content.Context
import dev.cued.app.data.db.DownloadJobEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Talks to `tools/spotdl-server/server.py` running on a machine on the same
 * network. Protocol (all JSON):
 *   POST /jobs            {"url": "...", "format": "mp3"}   -> {"id": "..."}
 *   GET  /jobs/{id}       -> {"status": "queued|running|done|failed", "progress": 0..1, "message": "...", "files": ["name.mp3", ...]}
 *   GET  /files/{name}    -> the audio bytes
 */
class CompanionDownloader(private val context: Context, private val baseUrl: String, private val format: String) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable private data class JobRequest(val url: String, val format: String)
    @Serializable private data class JobCreated(val id: String)
    @Serializable private data class JobStatus(val status: String, val progress: Float = 0f, val message: String? = null, val files: List<String> = emptyList())

    suspend fun run(job: DownloadJobEntity, onProgress: (Float) -> Unit): DownloadManager.Outcome = withContext(Dispatchers.IO) {
        val created = json.decodeFromString<JobCreated>(post("$baseUrl/jobs", json.encodeToString(JobRequest(job.source, format))))
        var status: JobStatus
        while (true) {
            delay(2_000)
            status = json.decodeFromString(get("$baseUrl/jobs/${created.id}"))
            onProgress(status.progress.coerceIn(0f, 0.9f))
            if (status.status == "done") break
            if (status.status == "failed") error(status.message ?: "spotdl failed on the companion")
        }
        val ids = ArrayList<Long>()
        status.files.forEachIndexed { i, name ->
            val mime = when (name.substringAfterLast('.', "").lowercase()) {
                "m4a" -> "audio/mp4"; "opus", "ogg" -> "audio/ogg"; "flac" -> "audio/flac"; else -> "audio/mpeg"
            }
            val (uri, id) = DownloadManager.createPendingAudio(context, name, mime)
            val conn = URL("$baseUrl/files/${URLEncoder.encode(name, "UTF-8").replace("+", "%20")}").openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000; conn.readTimeout = 60_000
            try {
                check(conn.responseCode in 200..299) { "file download failed: HTTP ${conn.responseCode}" }
                context.contentResolver.openOutputStream(uri)!!.use { out -> conn.inputStream.use { it.copyTo(out) } }
            } catch (e: Exception) {
                context.contentResolver.delete(uri, null, null)
                throw e
            } finally { conn.disconnect() }
            DownloadManager.finishPending(context, uri)
            ids += id
            onProgress(0.9f + 0.1f * (i + 1) / status.files.size)
        }
        DownloadManager.Outcome.Done(ids, "${status.files.size} file(s) from companion")
    }

    /** Quick reachability probe for the settings screen. */
    suspend fun ping(): Result<String> = withContext(Dispatchers.IO) { runCatching { get("$baseUrl/health") } }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000; conn.readTimeout = 15_000
        try {
            check(conn.responseCode in 200..299) { "HTTP ${conn.responseCode} from $url" }
            return conn.inputStream.bufferedReader().readText()
        } finally { conn.disconnect() }
    }

    private fun post(url: String, body: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000; conn.readTimeout = 15_000
        conn.requestMethod = "POST"; conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            check(conn.responseCode in 200..299) { "HTTP ${conn.responseCode} from $url" }
            return conn.inputStream.bufferedReader().readText()
        } finally { conn.disconnect() }
    }
}
