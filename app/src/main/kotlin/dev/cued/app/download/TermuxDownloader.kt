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
        val args = arrayListOf(
            "download", source,
            "--output", "$outDir/{artists} - {title}.{output-ext}",
            "--format", format,
            "--overwrite", "skip",
        ).apply { if (generateLrc) add("--generate-lrc") }.toTypedArray()
        val resultIntent = Intent(context, TermuxResultReceiver::class.java)
            .setAction(TermuxResultReceiver.ACTION_RESULT)
            .putExtra(TermuxResultReceiver.EXTRA_JOB_ID, job.id)
        val pending = PendingIntent.getBroadcast(
            context, job.id.toInt(), resultIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val intent = Intent().apply {
            setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService")
            action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", "$TERMUX_PREFIX/bin/spotdl")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", args)
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "$TERMUX_HOME")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "CUEd download")
            putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pending)
        }
        context.startForegroundService(intent)
        return DownloadManager.Outcome.Handed
    }

    companion object {
        const val TERMUX_PACKAGE = "com.termux"
        const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
        const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
        const val TERMUX_HOME = "/data/data/com.termux/files/home"
    }
}
