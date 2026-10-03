package dev.cued.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.cued.app.data.db.TrackEntity
import dev.cued.app.ui.LibraryViewModel
import dev.cued.app.ui.components.AlbumArt
import dev.cued.app.ui.components.EmptyHint
import dev.cued.app.ui.components.NumberedTrackRow
import dev.cued.app.ui.components.SectionHeader
import dev.cued.app.ui.components.formatMs
import dev.cued.app.ui.theme.Muted
import dev.cued.app.ui.theme.Teal
import dev.cued.core.library.Discography

/** One album: big cover, facts, play/shuffle/save, numbered tracks (with disc headers when there are several). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumScreen(
    vm: LibraryViewModel,
    artist: String,
    album: String,
    playingId: Long?,
    onBack: () -> Unit,
    onPlay: (List<TrackEntity>, Int) -> Unit,
    onTrackMore: (TrackEntity) -> Unit,
    onOpenArtist: (String) -> Unit,
    onSaveAsPlaylist: (String, List<Long>) -> Unit,
) {
    val index by vm.discography.collectAsState()
    val a = index.album(artist, album)
    var showSave by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(album) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            actions = { if (a != null) IconButton(onClick = { showSave = true }) { Icon(Icons.Default.Save, contentDescription = "Save as playlist") } },
        )
    }) { pad ->
        if (a == null) { Column(Modifier.padding(pad).fillMaxSize()) { EmptyHint("Album not in the library") }; return@Scaffold }
        val tracks = a.tracks
        val discs = tracks.map { it.discNo }.distinct().size
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    AlbumArt(tracks.firstOrNull(), Modifier.size(220.dp), corner = 16, px = dev.cued.app.ui.components.Artwork.LARGE)
                    Spacer(Modifier.height(12.dp))
                    Text(a.name, style = MaterialTheme.typography.headlineSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text(a.artist, style = MaterialTheme.typography.bodyLarge, color = Teal, modifier = Modifier.clickable { onOpenArtist(a.artist) }.padding(4.dp))
                    Text(listOfNotNull(Discography.yearLabel(a.year), "${tracks.size} track" + if (tracks.size == 1) "" else "s", formatMs(a.durationMs)).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Muted)
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onPlay(tracks, 0) }) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Play") }
                        OutlinedButton(onClick = { onPlay(tracks.shuffled(), 0) }) { Icon(Icons.Default.Shuffle, null); Spacer(Modifier.width(4.dp)); Text("Shuffle") }
                    }
                }
            }
            var lastDisc = -1
            tracks.forEachIndexed { i, t ->
                if (discs > 1 && t.discNo != lastDisc) { lastDisc = t.discNo; item(key = "disc${t.discNo}") { SectionHeader("Disc ${t.discNo.coerceAtLeast(1)}") } }
                item(key = t.id) { NumberedTrackRow(t, number = t.trackNo, playing = t.id == playingId, onClick = { onPlay(tracks, i) }, onMore = { onTrackMore(t) }) }
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }
    if (showSave && a != null) {
        var name by remember { mutableStateOf("${a.artist} – ${a.name}") }
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text("Save as playlist") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) { onSaveAsPlaylist(name.trim(), a.tracks.map { it.id }); showSave = false } }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showSave = false }) { Text("Cancel") } },
        )
    }
}
