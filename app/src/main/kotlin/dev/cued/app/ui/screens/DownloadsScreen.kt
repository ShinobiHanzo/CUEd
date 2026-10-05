package dev.cued.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
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
    val bugs = dev.cued.app.ui.LocalGraph.current.bugs
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
                        Text(when (b) { DownloadBackend.BUILT_IN -> "Built-in"; DownloadBackend.TERMUX -> "Termux"; DownloadBackend.COMPANION -> "Companion" }, maxLines = 1)
                    }
                }
            }
            when (settings.backend) {
                DownloadBackend.BUILT_IN -> Column(Modifier.padding(16.dp)) {
                    Text("No setup. Finds the track on YouTube Music, downloads the AAC stream as .m4a, writes title, artist, album and cover into the file.", color = Teal)
                    Text("Spotify links work without keys via the public embed page. Add your own free Spotify developer keys for long playlists and genre tags (developer.spotify.com → Create app).", style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 6.dp))
                    var sid by remember(settings.spotifyClientId) { mutableStateOf(settings.spotifyClientId.orEmpty()) }
                    var ssec by remember(settings.spotifyClientSecret) { mutableStateOf(settings.spotifyClientSecret.orEmpty()) }
                    OutlinedTextField(value = sid, onValueChange = { sid = it }, singleLine = true, label = { Text("Spotify client ID (optional)") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    OutlinedTextField(value = ssec, onValueChange = { ssec = it }, singleLine = true, label = { Text("Spotify client secret (optional)") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    TextButton(onClick = { vm.setSpotifyKeys(sid, ssec) }) { Text("Save keys") }
                    val ntest by vm.nativeTest.collectAsState()
                    TextButton(onClick = { vm.testNative() }) { Text("Test built-in downloader") }
                    ntest?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.endsWith("OK")) Teal else Muted) }
                }
                DownloadBackend.TERMUX -> Column(Modifier.padding(16.dp)) {
                    val installed = vm.termux.isTermuxInstalled()
                    val perm = vm.termux.hasPermission()
                    Text(if (!installed) "Termux not installed. Get it from F-Droid, then:" else if (!perm) "Termux found. Grant CUEd the 'Run commands in Termux' permission in system settings, then:" else "Termux ready.", color = if (installed && perm) Teal else Muted)
                    Text("pkg install python ffmpeg\npip install spotdl\ntermux-setup-storage\necho allow-external-apps=true >> ~/.termux/termux.properties", style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 8.dp))
                    val test by vm.termuxTest.collectAsState()
                    Row {
                        TextButton(onClick = { vm.testTermux() }, enabled = installed && perm) { Text("Test spotdl in Termux") }
                        TextButton(onClick = { vm.repairTermux() }, enabled = installed && perm) { Text("Repair spotdl") }
                    }
                    test?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("Termux OK") || it.startsWith("Repair finished")) Teal else Muted) }
                    if (test?.contains("libpython", true) == true) Text("Termux's Python was upgraded after spotdl was installed, so its native modules point at the old libpython. Repair reinstalls spotdl for the current Python.", style = MaterialTheme.typography.bodySmall, color = Teal)
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
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Format", color = Muted, style = MaterialTheme.typography.labelMedium)
                (if (settings.backend == DownloadBackend.BUILT_IN) listOf("m4a", "mp3") else listOf("mp3", "m4a", "opus", "flac")).forEach { f ->
                    TextButton(onClick = { vm.setFormat(f) }) { Text(f, color = if (settings.format == f) Teal else Muted) }
                }
            }
            if (settings.backend == DownloadBackend.BUILT_IN) Text(
                if (settings.format == "mp3") "mp3: the AAC stream is decoded and re-encoded on the phone (192 kbps, pure-Java LAME). Slower and a generation lossier than m4a." else "m4a: the AAC stream exactly as YouTube Music serves it. Fastest, best quality.",
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = Muted,
            )
            SectionHeader("Lyrics with downloads")
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = settings.generateLrc, onCheckedChange = { vm.setGenerateLrc(it) })
                Column { Text("Save .lrc lyrics files next to tracks"); Text("Termux/companion only (spotdl --generate-lrc). The built-in downloader relies on the lookup below instead.", style = MaterialTheme.typography.bodySmall, color = Muted) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = settings.fetchLyricsAfter, onCheckedChange = { vm.setFetchLyricsAfter(it) })
                Column { Text("Look lyrics up in CUEd after download"); Text("Embedded tag first, then lrclib.net if allowed in Settings → Lyrics.", style = MaterialTheme.typography.bodySmall, color = Muted) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                Checkbox(checked = settings.enrichOnline, onCheckedChange = { vm.setEnrichOnline(it) })
                Text("Look up album, year, track number and cover on MusicBrainz for YouTube links and searches", style = MaterialTheme.typography.bodySmall)
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
                    leadingContent = {
                        Box(Modifier.size(48.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).background(dev.cued.app.ui.theme.Ink3), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Download, contentDescription = null, tint = Muted)
                            j.artworkUrl?.let { coil.compose.AsyncImage(model = it, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        }
                    },
                    headlineContent = { Text(j.title ?: j.source, maxLines = 1) },
                    supportingContent = { Text("${j.backend.lowercase()} · ${j.status.lowercase()}${j.message?.let { " · $it" } ?: ""}", maxLines = 2, color = Muted) },
                    trailingContent = {
                        if (j.status == DownloadManager.STATUS_FAILED) Row {
                            TextButton(onClick = { vm.retry(j.id) }) { Text("Retry") }
                            TextButton(onClick = { bugs.open(dev.cued.app.support.BugReporter.Prefill(title = "Download failed: ${(j.message ?: "unknown error").take(80)}", description = "Source: ${j.source}\nBackend: ${j.backend}\nError: ${j.message}\n\nWhat I expected: ")) }) { Text("Report") }
                        }
                    },
                )
                if (j.status == DownloadManager.STATUS_RUNNING) LinearProgressIndicator(progress = { j.progress }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}
