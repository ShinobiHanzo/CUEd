package dev.cued.app.share

import android.content.Context
import android.net.Uri
import android.util.Log
import dev.cued.app.Graph
import dev.cued.app.data.db.TrackEntity
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileInputStream
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * A tiny HTTP server on the phone for local transfers. Another device on the
 * same Wi-Fi (or hotspot) fetches the track file or the CUEd APK straight
 * from here: no cloud, no account, nothing leaves the LAN. Runs only while
 * the Share screen is open.
 */
class ShareServer(private val context: Context, private val graph: Graph) {
    private var server: Server? = null
    private val _running = MutableStateFlow<String?>(null)
    /** Base URL (http://ip:port) while running, else null. */
    val baseUrl: StateFlow<String?> = _running

    /** Ids of tracks currently offered; anything else is refused so the server can't be used to browse the library. */
    private val offered = HashSet<Long>()

    @Synchronized
    fun start(port: Int): String? {
        server?.let { return _running.value }
        val ip = localIpv4() ?: return null
        return runCatching {
            Server(port).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false); server = it }
            "http://$ip:$port".also { _running.value = it }
        }.onFailure { Log.w(TAG, "share server failed", it) }.getOrNull()
    }

    @Synchronized
    fun stop() {
        server?.stop(); server = null
        _running.value = null
        offered.clear()
    }

    @Synchronized fun offer(trackId: Long) { offered += trackId }
    @Synchronized fun isOffered(trackId: Long) = trackId in offered

    fun trackUrl(trackId: Long): String? = baseUrl.value?.let { "$it/track/$trackId" }
    fun apkUrl(): String? = baseUrl.value?.let { "$it/apk" }

    private inner class Server(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            val path = session.uri.trimEnd('/')
            return try {
                when {
                    path == "" || path == "/" -> html(indexPage())
                    path == "/apk" -> apk()
                    path.startsWith("/track/") -> track(path.removePrefix("/track/").toLongOrNull())
                    path.startsWith("/meta/") -> meta(path.removePrefix("/meta/").toLongOrNull())
                    path == "/health" -> newFixedLengthResponse(Response.Status.OK, "text/plain", "cued")
                    else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
                }
            } catch (e: Exception) {
                Log.w(TAG, "serve failed", e)
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message ?: "error")
            }
        }

        private fun html(body: String) = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body)

        private fun indexPage(): String {
            val tracks = synchronized(this@ShareServer) { offered.toList() }.mapNotNull { id -> runBlocking { graph.library.track(id) } }
            val items = tracks.joinToString("") { t -> "<li><a href=\"/track/${t.id}\">${esc(t.artist)} – ${esc(t.title)}</a></li>" }
            return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>CUEd share</title>
<style>body{font-family:sans-serif;background:#0e0f13;color:#eee;padding:24px}a{color:#5ef2c6}li{margin:8px 0}</style></head>
<body><h2>CUEd</h2><p>Shared from this phone. Nothing here leaves the local network.</p>
<ul>$items</ul><p><a href="/apk">Download the CUEd app (APK)</a></p></body></html>"""
        }

        private fun apk(): Response {
            val f = File(context.applicationInfo.sourceDir)
            return newFixedLengthResponse(Response.Status.OK, "application/vnd.android.package-archive", FileInputStream(f), f.length()).apply {
                addHeader("Content-Disposition", "attachment; filename=\"cued.apk\"")
            }
        }

        private fun track(id: Long?): Response {
            if (id == null || !isOffered(id)) return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "not shared")
            val t: TrackEntity = runBlocking { graph.library.track(id) } ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "gone")
            val uri = Uri.parse(t.uri)
            val mime = context.contentResolver.getType(uri) ?: "audio/mpeg"
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "gone")
            val length = pfd.statSize
            val stream = FileInputStream(pfd.fileDescriptor)
            val ext = when (mime) { "audio/mp4" -> "m4a"; "audio/flac" -> "flac"; "audio/ogg" -> "ogg"; else -> "mp3" }
            return newFixedLengthResponse(Response.Status.OK, mime, stream, length).apply {
                addHeader("Content-Disposition", "attachment; filename=\"${safe(t.artist)} - ${safe(t.title)}.$ext\"")
            }
        }

        private fun meta(id: Long?): Response {
            if (id == null || !isOffered(id)) return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", "{}")
            val t = runBlocking { graph.library.track(id) } ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{}")
            val genres = runBlocking { graph.library.genresOf(id) }
            val body = """{"title":${q(t.title)},"artist":${q(t.artist)},"album":${q(t.album)},"bpm":${t.bpm ?: "null"},"genres":[${genres.joinToString(",") { q(it) }}],"link":${t.sourceLink?.let { q(it) } ?: "null"}}"""
            return newFixedLengthResponse(Response.Status.OK, "application/json", body)
        }
    }

    companion object {
        private const val TAG = "ShareServer"

        fun localIpv4(): String? {
            val ifaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull() ?: return null
            val preferred = ifaces.sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("ap") || it.name.startsWith("swlan")) 0 else 1 }
            for (i in preferred) {
                if (!i.isUp || i.isLoopback) continue
                for (a in i.inetAddresses) if (a is Inet4Address && a.isSiteLocalAddress) return a.hostAddress
            }
            return null
        }

        private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""
        private fun safe(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)
    }
}
