package dev.cued.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.cued.app.data.DownloadBackend
import dev.cued.app.download.DownloadManager
import dev.cued.app.ui.DownloadViewModel
import dev.cued.app.ui.components.EmptyHint
import dev.cued.app.ui.components.SectionHeader
import dev.cued.app.ui.theme.Muted
import dev.cued.app.ui.theme.Teal
import dev.cued.core.share.SourceLinks
import kotlinx.coroutines.launch

@Composable
fun DownloadsScreen(vm: DownloadViewModel, initialSource: String? = null, onSourceConsumed: () -> Unit = {}) {
    val jobs by vm.jobs.collectAsState()
    val settings by vm.settings.collectAsState()
    var source by remember { mutableStateOf("") }
    var sharedText by remember { mutableStateOf<String?>(null) }
    // Shared text is usually "Song by Artist https://…": keep the link for the box, remember the prose as a search hint.
    LaunchedEffect(initialSource) {
        if (!initialSource.isNullOrBlank()) {
            source = SourceLinks.extractUrl(initialSource) ?: SourceLinks.shareTextToQuery(initialSource) ?: initialSource.trim()
            sharedText = initialSource
            onSourceConsumed()
        }
    }
    var companionUrl by remember(settings.companionUrl) { mutableStateOf(settings.companionUrl) }
    var pingResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            SectionHeader("Download with spotdl", "Paste a Spotify or YouTube link, or type a search. Files land in Music/CUEd.")
            OutlinedTextField(
                value = source, onValueChange = { source = it }, singleLine = true,
                placeholder = { Text("https://open.spotify.com/track/…  or  artist - title") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val link = SourceLinks.parse(source)
                val detected = when {
                    source.isBlank() -> ""
                    link == null -> "Search query"
                    link.direct -> "${link.label} · spotdl reads this directly"
                    link.platform == SourceLinks.Platform.DEEZER && (link.type == SourceLinks.LinkType.PLAYLIST || link.type == SourceLinks.LinkType.ALBUM) -> "${link.label} · will be expanded track by track"
                    link.type == SourceLinks.LinkType.PLAYLIST -> "${link.label} · not readable without an account; share a Spotify/YouTube/Deezer playlist or the tracks individually"
                    link.type == SourceLinks.LinkType.ARTIST -> "${link.label} · share an album, track or playlist instead"
                    else -> "${link.label} · will be matched via song.link"
                }
                Text(detected, color = if (link != null && !link.direct && link.type == SourceLinks.LinkType.PLAYLIST && link.platform != SourceLinks.Platform.DEEZER) MaterialTheme.colorScheme.error else Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Button(onClick = { if (source.isNotBlank()) { vm.enqueueShared(sharedText?.takeIf { SourceLinks.extractUrl(it) == source } ?: source); source = ""; sharedText = null } }, enabled = source.isNotBlank()) {
                    Icon(Icons.Default.Download, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Queue")
                }
            }
        }
        item {
            SectionHeader("Backend", "Where spotdl actually runs")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                DownloadBackend.entries.forEachIndexed { i, b ->
                    SegmentedButton(selected = settings.backend == b, onClick = { vm.setBackend(b) }, shape = SegmentedButtonDefaults.itemShape(i, DownloadBackend.entries.size)) {
                        Text(if (b == DownloadBackend.TERMUX) "Termux (on phone)" else "Companion (LAN)")
                    }
                }
            }
            when (settings.backend) {
                DownloadBackend.TERMUX -> Column(Modifier.padding(16.dp)) {
                    val installed = vm.termux.isTermuxInstalled()
                    val perm = vm.termux.hasPermission()
                    Text(if (!installed) "Termux not installed. Get it from F-Droid, then:" else if (!perm) "Termux found. Grant CUEd the 'Run commands in Termux' permission in system settings, then:" else "Termux ready.", color = if (installed && perm) Teal else Muted)
                    Text("pkg install python ffmpeg\npip install spotdl\ntermux-setup-storage\necho allow-external-apps=true >> ~/.termux/termux.properties", style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 8.dp))
                }
                DownloadBackend.COMPANION -> Column(Modifier.padding(16.dp)) {
                    Text("Run tools/spotdl-server/server.py on any computer or Pi on the same Wi-Fi. Enter its address:", color = Muted, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = companionUrl, onValueChange = { companionUrl = it }, singleLine = true, label = { Text("http://ip:8766") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    Row {
                        TextButton(onClick = { vm.setCompanionUrl(companionUrl) }) { Text("Save") }
                        TextButton(onClick = { scope.launch { vm.setCompanionUrl(companionUrl); pingResult = vm.pingCompanion().fold({ "Reachable" }, { "Unreachable: ${it.message}" }) } }) { Text("Test") }
                        pingResult?.let { Text(it, color = Muted, modifier = Modifier.padding(12.dp)) }
                    }
                }
            }
            Row(Modifier.padding(horizontal = 16.dp)) {
                listOf("mp3", "m4a", "opus", "flac").forEach { f ->
                    TextButton(onClick = { vm.setFormat(f) }) { Text(f, color = if (settings.format == f) Teal else Muted) }
                }
            }
            SectionHeader("Lyrics with downloads")
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = settings.generateLrc, onCheckedChange = { vm.setGenerateLrc(it) })
                Column { Text("Save .lrc lyrics files next to tracks"); Text("spotdl --generate-lrc. Companion: imported into CUEd too. Termux: readable by other players; CUEd reads them on Android 10 and older.", style = MaterialTheme.typography.bodySmall, color = Muted) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = settings.fetchLyricsAfter, onCheckedChange = { vm.setFetchLyricsAfter(it) })
                Column { Text("Look lyrics up in CUEd after download"); Text("Embedded tag first, then lrclib.net if allowed in Settings → Lyrics.", style = MaterialTheme.typography.bodySmall, color = Muted) }
            }
        }
        item {
            SectionHeader("Jobs") {
                IconButton(onClick = { vm.rescanFolder() }) { Icon(Icons.Default.Refresh, contentDescription = "Rescan download folder") }
                TextButton(onClick = { vm.clearFinished() }) { Text("Clear done") }
            }
            if (jobs.isEmpty()) EmptyHint("No downloads yet")
        }
        items(jobs, key = { it.id }) { j ->
            Column {
                ListItem(
                    headlineContent = { Text(j.title ?: j.source, maxLines = 1) },
                    supportingContent = { Text("${j.backend.lowercase()} · ${j.status.lowercase()}${j.message?.let { " · $it" } ?: ""}", maxLines = 2, color = Muted) },
                    trailingContent = { if (j.status == DownloadManager.STATUS_FAILED) TextButton(onClick = { vm.retry(j.id) }) { Text("Retry") } },
                )
                if (j.status == DownloadManager.STATUS_RUNNING) LinearProgressIndicator(progress = { j.progress }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}
