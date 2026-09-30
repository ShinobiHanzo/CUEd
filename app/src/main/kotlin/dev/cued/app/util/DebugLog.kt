package dev.cued.app.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Opt-in diagnostic log. Off: nothing is written anywhere. On: every tagged
 * line goes to logcat, a 2000-line ring in memory, and a rolling file under
 * the app's private storage that Settings can share as a text file.
 * Never uploaded by the app; you decide where the export goes.
 */
object DebugLog {
    @Volatile var enabled: Boolean = false
        set(value) { field = value; if (value) i("log", "debug logging enabled") }

    private const val RING = 2_000
    private const val MAX_FILE = 2L * 1024 * 1024
    private val ring = ArrayDeque<String>(RING)
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private var file: File? = null
    private var appInfo: String = ""

    fun init(context: Context, versionName: String) {
        file = File(File(context.filesDir, "logs").apply { mkdirs() }, "cued.log")
        appInfo = "CUEd $versionName · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}"
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { write("E", "crash", "uncaught on ${t.name}: ${Log.getStackTraceString(e)}", force = true) }
            prev?.uncaughtException(t, e)
        }
    }

    fun d(tag: String, msg: String) { Log.d("CUEd/$tag", msg); if (enabled) write("D", tag, msg) }
    fun i(tag: String, msg: String) { Log.i("CUEd/$tag", msg); if (enabled) write("I", tag, msg) }
    fun w(tag: String, msg: String, t: Throwable? = null) { Log.w("CUEd/$tag", msg, t); if (enabled) write("W", tag, msg + (t?.let { "\n" + Log.getStackTraceString(it) } ?: "")) }
    fun e(tag: String, msg: String, t: Throwable? = null) { Log.e("CUEd/$tag", msg, t); if (enabled) write("E", tag, msg + (t?.let { "\n" + Log.getStackTraceString(it) } ?: "")) }

    @Synchronized
    private fun write(level: String, tag: String, msg: String, force: Boolean = false) {
        val line = "${fmt.format(Date())} $level/$tag: $msg"
        if (ring.size >= RING) ring.removeFirst()
        ring.addLast(line)
        val f = file ?: return
        if (!enabled && !force) return
        runCatching {
            if (f.length() > MAX_FILE) {
                val keep = f.readText().takeLast((MAX_FILE / 2).toInt())
                f.writeText(keep)
            }
            f.appendText(line + "\n")
        }
    }

    @Synchronized fun recent(lines: Int = 200): String = ring.takeLast(lines).joinToString("\n")
    @Synchronized fun sizeBytes(): Long = file?.length() ?: 0L
    @Synchronized fun clear() { ring.clear(); file?.writeText("") }

    /** Writes a fresh export (header + file contents) and returns a share intent for it. */
    @Synchronized
    fun shareIntent(context: Context): Intent {
        val out = File(File(context.filesDir, "logs").apply { mkdirs() }, "cued-log-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.txt")
        out.writeText(buildString {
            append(appInfo).append('\n')
            append("exported ").append(Date()).append("\n\n")
            file?.takeIf { it.exists() }?.let { append(it.readText()) }
            if (ring.isNotEmpty() && (file == null || file!!.length() == 0L)) append(ring.joinToString("\n"))
        })
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", out)
        return Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "CUEd debug log").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
