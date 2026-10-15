package dev.cued.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.cued.app.ui.SettingsViewModel
import dev.cued.app.ui.theme.Muted
import dev.cued.app.ui.theme.Teal
import dev.cued.app.update.UpdateManager
import dev.cued.core.changelog.Changelog

/**
 * The update window: patch notes with the download button inside it. Opened
 * from the home-screen banner (then Download also closes it) or from Settings →
 * Updates → What's new (then it stays open while the download runs). With no
 * update pending it shows the running version's notes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateWindow(vm: SettingsViewModel, closeOnDownload: Boolean, onDismiss: () -> Unit) {
    val stState by vm.update.collectAsState()
    val st: UpdateManager.State = stState
    val context = LocalContext.current
    val release: UpdateManager.Release? = when (st) {
        is UpdateManager.State.Available -> st.release
        is UpdateManager.State.Downloading -> st.release
        is UpdateManager.State.Ready -> st.release
        is UpdateManager.State.Error -> st.release
        else -> null
    }
    val title = if (release != null) "What's new in ${release.version}" else "What's new in ${vm.currentVersion}"
    val markdown = remember(release?.tag, vm.currentVersion) { if (release != null) UpdateManager.notesOf(release) else vm.notesForCurrent() }
    val lines = remember(markdown) { Changelog.lines(markdown) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                if (release != null) "Installed: v${vm.currentVersion}. The download comes from GitHub Releases and is checked against the published SHA-256 before Android installs it."
                else "These notes shipped inside the app; nothing was fetched.",
                style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            LazyColumn(Modifier.fillMaxWidth().height(if (release != null) 340.dp else 420.dp)) {
                items(lines) { line ->
                    when (line) {
                        is Changelog.Line.Heading -> Text(
                            if (line.text.firstOrNull()?.isDigit() == true) "Version ${line.text}" else line.text,
                            style = MaterialTheme.typography.titleMedium, color = Teal, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                        )
                        is Changelog.Line.Bullet -> Row(Modifier.padding(vertical = 3.dp)) { Text("•  ", color = Teal); Text(line.text, style = MaterialTheme.typography.bodyMedium) }
                        is Changelog.Line.Para -> Text(line.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 3.dp))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            when (st) {
                is UpdateManager.State.Available -> Row(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    Button(onClick = { vm.downloadUpdate(); if (closeOnDownload) onDismiss() }, modifier = Modifier.weight(1f)) {
                        Text("Download v${st.release.version}" + if (st.release.apkSize > 0) " (${st.release.apkSize / 1024 / 1024} MB)" else "")
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { vm.dismissUpdate(); onDismiss() }) { Text("Later") }
                }
                is UpdateManager.State.Downloading -> Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    Text("Downloading v${st.release.version}… ${(st.progress * 100).toInt()}%", color = Muted, style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                }
                is UpdateManager.State.Ready -> Row(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    if (vm.canInstall()) Button(onClick = { context.startActivity(vm.installIntent(st.file)); onDismiss() }, modifier = Modifier.weight(1f)) { Text("Install v${st.release.version}") }
                    else Button(onClick = { context.startActivity(vm.unknownSourcesIntent()) }, modifier = Modifier.weight(1f)) { Text("Allow installs from CUEd") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onDismiss) { Text("Later") }
                }
                is UpdateManager.State.Error -> Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    Text(st.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Row { TextButton(onClick = { vm.checkForUpdate() }) { Text("Retry") }; if (st.release != null) TextButton(onClick = { vm.downloadUpdate() }) { Text("Download again") } }
                }
                else -> OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) { Text("Close") }
            }
        }
    }
}
