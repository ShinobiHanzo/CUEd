@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.cued.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cued.app.data.SmartList
import dev.cued.app.data.db.TrackEntity
import dev.cued.app.ui.LibraryViewModel
import dev.cued.app.ui.components.AlbumArt
import dev.cued.app.ui.components.EmptyHint
import dev.cued.app.ui.components.SectionHeader
import dev.cued.app.ui.theme.Muted

@Composable
fun HomeScreen(
    vm: LibraryViewModel,
    onPlay: (List<TrackEntity>, Int) -> Unit,
    onOpenList: (SmartList) -> Unit,
    onTrackMore: (TrackEntity) -> Unit,
    updateAvailable: String? = null,
    onUpdate: () -> Unit = {},
    crashed: Boolean = false,
    onReportCrash: () -> Unit = {},
    onDismissCrash: () -> Unit = {},
) {
    val lists by vm.smartLists.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val pending by vm.analysisPending.collectAsState()

    LazyColumn(Modifier.fillMaxWidth()) {
        if (crashed) item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clickable(onClick = onReportCrash)
                    .background(dev.cued.app.ui.theme.Ink3, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Icon(androidx.compose.material.icons.Icons.Default.BugReport, contentDescription = null, tint = dev.cued.app.ui.theme.Amber)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) { Text("CUEd crashed last time", style = MaterialTheme.typography.bodyMedium); Text("Tap to report it with the crash details", style = MaterialTheme.typography.bodySmall, color = Muted) }
                TextButton(onClick = onDismissCrash) { Text("Dismiss") }
            }
        }
        if (updateAvailable != null) item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clickable(onClick = onUpdate)
                    .background(dev.cued.app.ui.theme.Ink3, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Icon(androidx.compose.material.icons.Icons.Default.SystemUpdate, contentDescription = null, tint = dev.cued.app.ui.theme.Teal)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) { Text("CUEd v$updateAvailable is available", style = MaterialTheme.typography.bodyMedium); Text("Tap to download and install", style = MaterialTheme.typography.bodySmall, color = Muted) }
            }
        }
        item {
            SectionHeader("CUEd", subtitle = if (scanning) "Scanning library…" else if (pending > 0) "Analysing $pending track(s) in the background" else "Offline. Local. Yours.") {
                if (scanning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else IconButton(onClick = { vm.rescan() }) { Icon(Icons.Default.Refresh, contentDescription = "Rescan") }
            }
        }
        for (kind in SmartList.entries) {
            val tracks = lists[kind].orEmpty()
            item(key = kind.name) {
                Column {
                    SectionHeader(kind.title, kind.blurb) {
                        if (tracks.isNotEmpty()) TextButton(onClick = { onOpenList(kind) }) { Text("All ${tracks.size}") }
                    }
                    if (tracks.isEmpty()) {
                        EmptyHint(
                            when (kind) {
                                SmartList.TRENDING -> "Play something and it shows up here"
                                SmartList.FAVOURITES -> "Star tracks from their menu"
                                SmartList.RECOMMENDED -> "Needs a few plays and some genre labels"
                                SmartList.FORGOTTEN -> "Nothing has gone stale yet"
                                SmartList.UNPLAYED -> "You've heard everything"
                                SmartList.NEW -> "No music found. Rescan or download some"
                            }
                        )
                    } else {
                        LazyRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                            items(tracks.take(20), key = { it.id }) { t ->
                                Column(
                                    Modifier.width(132.dp).padding(8.dp).androidx.compose.foundation.combinedClickable(onClick = { onPlay(tracks, tracks.indexOf(t)) }, onLongClick = { onTrackMore(t) }),
                                ) {
                                    AlbumArt(t, Modifier.size(116.dp), corner = 12)
                                    Spacer(Modifier.height(6.dp))
                                    Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                    Text(t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Muted)
                                }
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}
