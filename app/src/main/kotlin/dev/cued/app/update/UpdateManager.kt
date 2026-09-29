package dev.cued.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as SysSettings
import androidx.core.content.FileProvider
import dev.cued.app.BuildConfig
import dev.cued.app.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * In-app updates straight from GitHub Releases: check → download → verify
 * SHA-256 against the published checksum → hand the APK to Android's
 * installer. Talks only to api.github.com and the release asset host.
 *
 * Needs the repository to be public (or a read-only token in Settings) so
 * the Releases API answers without a login.
 */
class UpdateManager(private val context: Context, private val settings: Settings, private val scope: CoroutineScope) {

    data class Release(val tag: String, val version: String, val name: String, val notes: String, val publishedAt: String, val apkUrl: String, val apkName: String, val apkSize: Long, val shaUrl: String?)

    sealed class State {
        data object Idle : State()
        data object Checking : State()
        data class UpToDate(val current: String, val checkedAt: Long) : State()
        data class Available(val release: Release) : State()
        data class Downloading(val release: Release, val progress: Float) : State()
        data class Ready(val release: Release, val file: File) : State()
        data class Error(val message: String, val release: Release? = null) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private val json = Json { ignoreUnknownKeys = true }
    val currentVersion: String = BuildConfig.VERSION_NAME.substringBefore('-')

    /** Once a day, if enabled. Silent on failure. */
    fun autoCheckIfDue() {
        scope.launch {
            val s = settings.updatesNow()
            if (!s.autoCheck) return@launch
            if (System.currentTimeMillis() - s.lastCheckAt < 24 * 3600 * 1000L) return@launch
            check(quiet = true)
        }
    }

    fun checkAsync() { scope.launch { check(quiet = false) } }

    suspend fun check(quiet: Boolean) {
        if (_state.value is State.Downloading) return
        _state.value = State.Checking
        val token = settings.updatesNow().githubToken
        val result = withContext(Dispatchers.IO) { runCatching { fetchLatest(token) } }
        settings.setUpdatesLastCheck(System.currentTimeMillis())
        result.onSuccess { rel ->
            _state.value = if (rel == null || compareVersions(rel.version, currentVersion) <= 0) State.UpToDate(currentVersion, System.currentTimeMillis()) else State.Available(rel)
        }.onFailure { e ->
            _state.value = if (quiet) State.Idle else State.Error(friendly(e))
        }
    }

    private fun friendly(e: Throwable): String {
        val m = e.message.orEmpty()
        return when {
            "404" in m || "403" in m -> "GitHub says the repository isn't visible without a login. Make it public, or paste a read-only token below."
            "401" in m -> "The token was rejected."
            else -> "Couldn't reach GitHub: ${m.ifBlank { e.javaClass.simpleName }}"
        }
    }

    private fun fetchLatest(token: String?): Release? {
        val body = get("https://api.github.com/repos/$OWNER/$REPO/releases?per_page=10", token) ?: return null
        val list = json.parseToJsonElement(body).jsonArray
        var best: Release? = null
        for (el in list) {
            val o = el.jsonObject
            if (o["draft"]?.jsonPrimitive?.content == "true") continue
            val tag = o["tag_name"]?.jsonPrimitive?.content ?: continue
            val version = tag.removePrefix("v")
            val assets = o["assets"]?.jsonArray.orEmpty().map { it.jsonObject }
            val apk = assets.firstOrNull { it["name"]?.jsonPrimitive?.content == "CUEd-$tag.apk" }
                ?: assets.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true } ?: continue
            val sha = assets.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk.sha256") == true }
            val rel = Release(
                tag = tag, version = version,
                name = o["name"]?.jsonPrimitive?.content ?: tag,
                notes = o["body"]?.jsonPrimitive?.content.orEmpty(),
                publishedAt = o["published_at"]?.jsonPrimitive?.content.orEmpty(),
                apkUrl = apk["browser_download_url"]!!.jsonPrimitive.content,
                apkName = apk["name"]!!.jsonPrimitive.content,
                apkSize = apk["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                shaUrl = sha?.get("browser_download_url")?.jsonPrimitive?.content,
            )
            if (best == null || compareVersions(rel.version, best.version) > 0) best = rel
        }
        return best
    }

    fun downloadAsync() {
        val rel = (_state.value as? State.Available)?.release ?: (_state.value as? State.Error)?.release ?: return
        scope.launch {
            _state.value = State.Downloading(rel, 0f)
            val r = withContext(Dispatchers.IO) { runCatching { download(rel) } }
            r.onSuccess { _state.value = State.Ready(rel, it) }.onFailure { _state.value = State.Error("Download failed: ${it.message}", rel) }
        }
    }

    private fun download(rel: Release): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { if (it.name != rel.apkName) it.delete() }
        val file = File(dir, rel.apkName)
        val token = kotlinx.coroutines.runBlocking { settings.updatesNow().githubToken }
        val conn = open(rel.apkUrl, token)
        try {
            check(conn.responseCode in 200..299) { "HTTP ${conn.responseCode}" }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: rel.apkSize
            val digest = MessageDigest.getInstance("SHA-256")
            conn.inputStream.use { inp ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        out.write(buf, 0, n); digest.update(buf, 0, n); done += n
                        if (total > 0) _state.value = State.Downloading(rel, (done.toFloat() / total).coerceIn(0f, 0.99f))
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            rel.shaUrl?.let { url ->
                val expected = get(url, token)?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.lowercase()
                if (expected != null && expected != actual) { file.delete(); error("checksum mismatch: the file did not match the published SHA-256") }
            }
            return file
        } finally { conn.disconnect() }
    }

    /** True if Android will let this app start an APK install; otherwise send the user to the toggle. */
    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(): Intent =
        Intent(SysSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun dismiss() { _state.value = State.Idle }

    private fun open(url: String, token: String?): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000; conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "CUEd/${BuildConfig.VERSION_NAME} (https://github.com/$OWNER/$REPO)")
        conn.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream, */*")
        if (!token.isNullOrBlank() && url.contains("github.com")) conn.setRequestProperty("Authorization", "Bearer $token")
        return conn
    }

    private fun get(url: String, token: String?): String? {
        val conn = open(url, token)
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().readText()
        } finally { conn.disconnect() }
    }

    companion object {
        const val OWNER = "ShinobiHanzo"
        const val REPO = "CUEd"
        const val RELEASES_URL = "https://github.com/$OWNER/$REPO/releases"

        /** Compares dotted versions numerically; suffixes after '-' are ignored. */
        fun compareVersions(a: String, b: String): Int {
            fun parts(v: String) = v.substringBefore('-').removePrefix("v").split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
            val pa = parts(a); val pb = parts(b)
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val d = (pa.getOrNull(i) ?: 0).compareTo(pb.getOrNull(i) ?: 0)
                if (d != 0) return d
            }
            return 0
        }
    }
}
