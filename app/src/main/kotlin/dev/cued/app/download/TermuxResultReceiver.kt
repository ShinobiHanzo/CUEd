package dev.cued.app.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.cued.app.CuedApp

/** Receives the exit code and output that Termux posts back when a spotdl run finishes. */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RESULT) return
        val jobId = intent.getLongExtra(EXTRA_JOB_ID, Long.MIN_VALUE)
        if (jobId == Long.MIN_VALUE) return
        val result = intent.getBundleExtra("result")
        val exit = result?.getInt("exitCode", -1) ?: -1
        val stdout = result?.getString("stdout").orEmpty()
        val stderr = result?.getString("stderr").orEmpty()
        val ok = exit == 0
        dev.cued.app.util.DebugLog.i("termux", "result job=$jobId exit=$exit err=${result?.getInt("err", 0)} errmsg=${result?.getString("errmsg")}\n--- stdout ---\n${stdout.take(4000)}\n--- stderr ---\n${stderr.take(4000)}")
        if (jobId == TermuxDownloader.TEST_JOB_ID || jobId == TermuxDownloader.REPAIR_JOB_ID) {
            val text = buildString {
                append(if (jobId == TermuxDownloader.REPAIR_JOB_ID) (if (ok) "Repair finished\n" else "Repair exited $exit\n") else if (ok) "Termux OK\n" else "exit $exit\n")
                append(stdout.trim())
                if (stderr.isNotBlank()) append("\n").append(stderr.trim().lines().takeLast(4).joinToString("\n"))
            }
            CuedApp.graph(context).downloads.termuxTest.value = text.take(1_200)
            return
        }
        val message = if (ok) stdout.lines().lastOrNull { it.isNotBlank() } ?: "Done"
        else (stderr.ifBlank { stdout }).lines().filter { it.isNotBlank() }.takeLast(3).joinToString(" · ").ifBlank { "spotdl exited with $exit" + (result?.getString("errmsg")?.let { " · $it" } ?: "") }
        CuedApp.graph(context).downloads.complete(jobId, ok, message.take(300))
    }

    companion object {
        const val ACTION_RESULT = "dev.cued.app.TERMUX_RESULT"
        const val EXTRA_JOB_ID = "jobId"
    }
}
