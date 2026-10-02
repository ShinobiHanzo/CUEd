package dev.cued.app.support

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.cued.app.BuildConfig
import dev.cued.app.data.Settings
import dev.cued.app.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * "Report a bug": turns a description plus the recent debug log into a GitHub
 * issue on the CUEd repository, where the maintainers (and the assistant that
 * writes most of the code) read it.
 *
 * Two routes, both explicit and both reviewable by the user:
 *  - With a GitHub token in Settings → Updates, the issue is posted directly
 *    through the API with the full (redacted) log tail attached.
 *  - Without one, the GitHub "new issue" form opens in the browser prefilled;
 *    URLs have a length cap, so the log is shortened there and the full report
 *    is copied to the clipboard for pasting.
 *
 * Nothing is sent until the user taps the button. Tokens and keys are redacted.
 */
class BugReporter(private val context: Context, private val settings: Settings, private val scope: CoroutineScope) {
    data class Prefill(val title: String = "", val description: String = "", val includeLog: Boolean = true)

    sealed class Outcome {
        data class Posted(val url: String) : Outcome()
        data class OpenedBrowser(val truncated: Boolean) : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    /** Non-null while the report dialog should be showing. Any screen can open it. */
    val request = MutableStateFlow<Prefill?>(null)
    val busy = MutableStateFlow(false)
    val outcome = MutableStateFlow<Outcome?>(null)

    fun open(prefill: Prefill = Prefill()) { outcome.value = null; request.value = prefill }
    fun dismiss() { request.value = null; busy.value = false }

    suspend fun hasToken(): Boolean = !settings.updatesNow().githubToken.isNullOrBlank()

    /** The full Markdown body: description, environment, settings that matter, then the log in a collapsible block. */
    suspend fun body(description: String, includeLog: Boolean, maxLogBytes: Int = FULL_LOG_BYTES): String {
        val dl = settings.download.first()
        val pb = settings.playback.first()
        val lock = settings.lockScreen.first()
        val sb = StringBuilder()
        sb.append(description.trim().ifBlank { "_(no description)_" }).append("\n\n---\n")
        sb.append("**Environment**\n").append(DebugLog.header).append(" · build ").append(BuildConfig.VERSION_CODE).append('\n')
        sb.append("Downloads: ").append(dl.backend.name.lowercase()).append(" · ").append(dl.format)
            .append(if (dl.spotifyClientId != null) " · spotify keys set" else "").append('\n')
        sb.append("Playback: crossfade ").append(if (pb.crossfadeEnabled) "${pb.crossfadeMs / 1000}s" else "off")
            .append(", tempo-match ").append(if (pb.tempoMatch) "on" else "off").append(", skip silence ").append(if (pb.skipSilence) "on" else "off").append('\n')
        sb.append("Lock screen player: ").append(if (lock) "on" else "off").append('\n')
        if (includeLog) {
            val log = DebugLog.redact(DebugLog.tail(maxLogBytes)).trim()
            sb.append("\n<details><summary>Debug log (last ${log.length / 1024} KB)</summary>\n\n```\n")
            sb.append(if (log.isBlank()) "(empty: turn on Settings → Debug log, reproduce, then report)" else log)
            sb.append("\n```\n</details>\n")
        }
        return sb.toString()
    }

    fun submit(title: String, description: String, includeLog: Boolean) {
        if (busy.value) return
        busy.value = true
        scope.launch {
            outcome.value = try {
                val t = title.trim().ifBlank { "Bug report from CUEd ${DebugLog.header.substringBefore(" ·").removePrefix("CUEd ")}" }
                val token = settings.updatesNow().githubToken
                if (!token.isNullOrBlank()) postIssue(t, body(description, includeLog), token)
                else openBrowser(t, description, includeLog)
            } catch (e: Exception) {
                DebugLog.w(TAG, "bug report failed", e)
                Outcome.Failed(e.message ?: e.toString())
            }
            busy.value = false
        }
    }

    private suspend fun postIssue(title: String, body: String, token: String): Outcome = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("title", title)
            put("body", body.take(MAX_ISSUE_BODY))
            put("labels", JsonArray(listOf(JsonPrimitive("bug"), JsonPrimitive("from-app"))))
        }.toString()
        val conn = URL("https://api.github.com/repos/$REPO/issues").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15_000; conn.readTimeout = 30_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("User-Agent", "CUEd/${BuildConfig.VERSION_NAME}")
        conn.doOutput = true
        conn.outputStream.use { it.write(payload.toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code == 201) {
            val url = Json.parseToJsonElement(text).jsonObject["html_url"]?.jsonPrimitive?.content ?: "https://github.com/$REPO/issues"
            DebugLog.i(TAG, "issue posted: $url")
            Outcome.Posted(url)
        } else {
            val msg = runCatching { Json.parseToJsonElement(text).jsonObject["message"]?.jsonPrimitive?.content }.getOrNull()
            Outcome.Failed("GitHub answered $code" + (msg?.let { ": $it" } ?: "") + if (code == 401 || code == 403) " (the token needs Issues: write, or public_repo for classic tokens)" else "")
        }
    }

    /** Browser fallback: prefilled new-issue form with a short log; the full report goes to the clipboard. */
    private suspend fun openBrowser(title: String, description: String, includeLog: Boolean): Outcome {
        val full = body(description, includeLog)
        val short = body(description, includeLog, maxLogBytes = BROWSER_LOG_BYTES)
        val truncated = full.length > short.length
        val note = if (truncated) "\n\n_(log shortened for the URL; the full report is on the clipboard, paste it here)_\n" else ""
        val url = "https://github.com/$REPO/issues/new?labels=bug,from-app&title=${enc(title)}&body=${enc(short + note)}"
        withContext(Dispatchers.Main) {
            runCatching { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("CUEd bug report", full)) }
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return Outcome.OpenedBrowser(truncated)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    companion object {
        const val REPO = "ShinobiHanzo/CUEd"
        const val ISSUES_URL = "https://github.com/$REPO/issues"
        private const val FULL_LOG_BYTES = 40 * 1024
        private const val BROWSER_LOG_BYTES = 4 * 1024
        private const val MAX_ISSUE_BODY = 65_000
        private const val TAG = "bugs"
    }
}
