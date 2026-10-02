package dev.cued.app.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.cued.app.support.BugReporter
import dev.cued.app.ui.theme.Muted
import dev.cued.app.ui.theme.Teal
import dev.cued.app.util.DebugLog

/** The "Report a bug" sheet. Shown from the root whenever [BugReporter.request] is set. */
@Composable
fun BugReportDialog(bugs: BugReporter) {
    val prefill by bugs.request.collectAsState()
    val p = prefill ?: return
    val busy by bugs.busy.collectAsState()
    val outcome by bugs.outcome.collectAsState()
    val context = LocalContext.current
    var title by remember(p) { mutableStateOf(p.title) }
    var description by remember(p) { mutableStateOf(p.description) }
    var includeLog by remember(p) { mutableStateOf(p.includeLog) }
    var hasToken by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    LaunchedEffect(p) { hasToken = bugs.hasToken() }

    AlertDialog(
        onDismissRequest = { if (!busy) bugs.dismiss() },
        title = { Text("Report a bug") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when (val o = outcome) {
                    is BugReporter.Outcome.Posted -> {
                        Text("Posted. Thanks: it will be read and, where it can be, fixed in a future release.", color = Teal)
                        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(o.url))) }) { Text(o.url, style = MaterialTheme.typography.bodySmall) }
                    }
                    is BugReporter.Outcome.OpenedBrowser -> Text(
                        if (o.truncated) "GitHub opened in your browser with the form prefilled. The log was shortened to fit the link; the full report is on your clipboard, paste it into the issue body before submitting."
                        else "GitHub opened in your browser with the form prefilled. Review it and press Submit there.", color = Teal,
                    )
                    is BugReporter.Outcome.Failed -> Text("Couldn't send: ${o.reason}", color = MaterialTheme.colorScheme.error)
                    null -> {
                        OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true, label = { Text("What went wrong (one line)") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("What you did, what you expected") }, modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp), enabled = !busy)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = includeLog, onCheckedChange = { includeLog = it }, enabled = !busy)
                            Text("Attach the recent debug log", style = MaterialTheme.typography.bodyMedium)
                        }
                        if (includeLog) {
                            val bytes = DebugLog.sizeBytes()
                            Text(
                                if (bytes == 0L) "The log is empty. Turn on Settings → Debug log, reproduce the problem, then report." else "Last ${minOf(bytes, 40 * 1024L) / 1024} KB, tokens and keys redacted.",
                                style = MaterialTheme.typography.bodySmall, color = Muted,
                            )
                            TextButton(onClick = { showLog = !showLog }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text(if (showLog) "Hide what gets sent" else "Show what gets sent") }
                            if (showLog) Text(DebugLog.redact(DebugLog.tail(4 * 1024)).ifBlank { "(empty)" }, style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (hasToken) "Posts a public issue on github.com/${BugReporter.REPO} using the token from Settings → Updates. Anyone can read it."
                            else "Opens GitHub's new-issue form in your browser, prefilled; you submit it there (a GitHub account is needed). Issues are public. Add a token under Settings → Updates to post straight from the app.",
                            style = MaterialTheme.typography.bodySmall, color = Muted,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (outcome == null) TextButton(onClick = { bugs.submit(title, description, includeLog) }, enabled = !busy) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp).padding(end = 6.dp), strokeWidth = 2.dp)
                Text(if (hasToken) "Post issue" else "Open GitHub")
            } else TextButton(onClick = { bugs.dismiss() }) { Text("Done") }
        },
        dismissButton = { if (outcome == null) TextButton(onClick = { bugs.dismiss() }, enabled = !busy) { Text("Cancel") } },
    )
}
