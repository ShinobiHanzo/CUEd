package dev.cued.app.download

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.cued.app.data.db.DownloadJobEntity

/**
 * Runs `spotdl` inside Termux via its RUN_COMMAND intent.
 *
 * One-time setup on the phone (documented in docs/downloading.md):
 *   pkg install python ffmpeg && pip install spotdl
 *   echo "allow-external-apps=true" >> ~/.termux/termux.properties
 *   termux-setup-storage
 * and grant CUEd the "Run commands in Termux" permission.
 */
class TermuxDownloader(private val context: Context) {

    fun isTermuxInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0); true
    }.getOrDefault(false)

    fun hasPermission(): Boolean =
        context.checkSelfPermission(RUN_COMMAND_PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun start(job: DownloadJobEntity, source: String, format: String, generateLrc: Boolean): DownloadManager.Outcome {
        check(isTermuxInstalled()) { "Termux is not installed" }
        check(hasPermission()) { "Grant CUEd the 'Run commands in Termux' permission" }
        val outDir = DownloadManager.downloadDir().absolutePath
        // Positional parameters ($0, $1…) keep the URL and paths out of the shell string, so quoting can't break.
        val script = buildString {
            append("exec spotdl download \"$0\" --output \"$1/{artists} - {title}.{output-ext}\" --format \"$2\" --overwrite skip")
            if (generateLrc) append(" --generate-lrc")
        }
        val resultIntent = Intent(context, TermuxResultReceiver::class.java)
            .setAction(TermuxResultReceiver.ACTION_RESULT)
            .putExtra(TermuxResultReceiver.EXTRA_JOB_ID, job.id)
        val pending = PendingIntent.getBroadcast(
            context, job.id.toInt(), resultIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        context.startForegroundService(shellCommand(script, listOf(source, outDir, format), "CUEd download", pending))
        return DownloadManager.Outcome.Handed
    }

    /** Runs a quick environment check inside Termux; the result comes back through [TermuxResultReceiver]. */
    fun test(): Boolean {
        if (!isTermuxInstalled() || !hasPermission()) return false
        val script = "echo \"python: $(python3 --version 2>&1)\"; echo \"spotdl: $(spotdl --version 2>&1 | tail -1)\"; echo \"ffmpeg: $(ffmpeg -version 2>&1 | head -1)\"; test -d /sdcard/Music && echo storage:ok || echo storage:missing"
        val resultIntent = Intent(context, TermuxResultReceiver::class.java)
            .setAction(TermuxResultReceiver.ACTION_RESULT)
            .putExtra(TermuxResultReceiver.EXTRA_JOB_ID, TEST_JOB_ID)
        val pending = PendingIntent.getBroadcast(context, TEST_JOB_ID.toInt(), resultIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        context.startForegroundService(shellCommand(script, emptyList(), "CUEd self-test", pending))
        return true
    }

    /**
     * Runs [script] through a Termux login shell. RUN_COMMAND executes a binary
     * with a bare environment, which breaks Python's native modules ("libpython
     * not found"); a login shell loads Termux's profile, and we also pin
     * LD_LIBRARY_PATH and PATH to the Termux prefix to be safe.
     */
    private fun shellCommand(script: String, args: List<String>, label: String, pending: PendingIntent): Intent {
        val wrapped = "export PREFIX=\"$TERMUX_PREFIX\"; export HOME=\"$TERMUX_HOME\"; export PATH=\"$TERMUX_PREFIX/bin:\$PATH\"; " +
            "export LD_LIBRARY_PATH=\"$TERMUX_PREFIX/lib\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}\"; export TMPDIR=\"$TERMUX_PREFIX/tmp\"; " +
            "export LANG=en_US.UTF-8; cd \"\$HOME\"; $script"
        return Intent().apply {
            setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService")
            action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", "$TERMUX_PREFIX/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", (listOf("-l", "-c", wrapped) + args).toTypedArray())
            putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", label)
            putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pending)
        }
    }

    companion object {
        const val TERMUX_PACKAGE = "com.termux"
        const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
        const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
        const val TERMUX_HOME = "/data/data/com.termux/files/home"
        const val TEST_JOB_ID = -1L
    }
}
