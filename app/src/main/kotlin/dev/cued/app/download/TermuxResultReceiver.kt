package dev.cued.app.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.cued.app.CuedApp

/** Receives the exit code and output that Termux posts back when a spotdl run finishes. */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RESULT) return
        val jobId = intent.getLongExtra(EXTRA_JOB_ID, -1L)
        if (jobId < 0) return
        val result = intent.getBundleExtra("result")
        val exit = result?.getInt("exitCode", -1) ?: -1
        val stdout = result?.getString("stdout").orEmpty()
        val stderr = result?.getString("stderr").orEmpty()
        val ok = exit == 0
        val message = if (ok) stdout.lines().lastOrNull { it.isNotBlank() } ?: "Done"
        else (stderr.ifBlank { stdout }).lines().lastOrNull { it.isNotBlank() } ?: "spotdl exited with $exit"
        CuedApp.graph(context).downloads.complete(jobId, ok, message.take(300))
    }

    companion object {
        const val ACTION_RESULT = "dev.cued.app.TERMUX_RESULT"
        const val EXTRA_JOB_ID = "jobId"
    }
}
