package dev.cued.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.cued.app.data.db.TrackEntity
import dev.cued.app.ui.LibraryViewModel
import dev.cued.app.ui.components.EmptyHint
import dev.cued.app.ui.components.SectionHeader
import dev.cued.app.ui.components.TrackRow

@Composable
fun PlaylistsScreen(vm: LibraryViewModel, onOpen: (Long) -> Unit) {
    val playlists by vm.playlists.collectAsState()
    var creating by remember { mutableStateOf(false) }
    Scaffold(floatingActionButton = { FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Default.Add, contentDescription = "New playlist") } }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            SectionHeader("Playlists", "Curate by hand; smart lists on Home can be saved here too")
            if (playlists.isEmpty()) EmptyHint("No playlists yet")
            LazyColumn {
                items(playlists, key = { it.id }) { p ->
                    ListItem(
                        headlineContent = { Text(p.name) },
                        supportingContent = { if (p.description.isNotBlank()) Text(p.description) },
                        leadingContent = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null) },
                        modifier = Modifier.clickable { onOpen(p.id) },
                    )
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
    }
    if (creating) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("New playlist") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) { vm.createPlaylist(name) { onOpen(it) }; creating = false } }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    vm: LibraryViewModel,
    playlistId: Long,
    playingId: Long?,
    onBack: () -> Unit,
    onPlay: (List<TrackEntity>, Int) -> Unit,
    onTrackMore: (TrackEntity) -> Unit,
) {
    val playlistFlow = remember(playlistId) { vm.playlist(playlistId) }
    val tracksFlow = remember(playlistId) { vm.playlistTracks(playlistId) }
    val playlist by playlistFlow.collectAsState(initial = null)
    val tracks by tracksFlow.collectAsState(initial = emptyList())
    val genreMap by vm.genreMap.collectAsState()
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(playlist?.name ?: "Playlist") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    IconButton(onClick = { if (tracks.isNotEmpty()) onPlay(tracks, 0) }) { Icon(Icons.Default.PlayArrow, contentDescription = "Play") }
                    IconButton(onClick = { if (tracks.isNotEmpty()) onPlay(tracks.shuffled(), 0) }) { Icon(Icons.Default.Shuffle, contentDescription = "Shuffle") }
                    IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, contentDescription = "Rename") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            playlist?.description?.takeIf { it.isNotBlank() }?.let { SectionHeader(it) }
            if (tracks.isEmpty()) EmptyHint("Empty. Add tracks from any track's menu.")
            LazyColumn {
                itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
                    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column {
                            IconButton(onClick = { if (i > 0) vm.reorderPlaylist(playlistId, tracks.map { it.id }.toMutableList().apply { add(i - 1, removeAt(i)) }) }, modifier = Modifier.height(24.dp)) { Icon(Icons.Default.ArrowUpward, contentDescription = "Up", modifier = Modifier.height(14.dp)) }
                            IconButton(onClick = { if (i < tracks.size - 1) vm.reorderPlaylist(playlistId, tracks.map { it.id }.toMutableList().apply { add(i + 1, removeAt(i)) }) }, modifier = Modifier.height(24.dp)) { Icon(Icons.Default.ArrowDownward, contentDescription = "Down", modifier = Modifier.height(14.dp)) }
                        }
                        Column(Modifier.weight(1f)) {
                            TrackRow(t, genres = genreMap[t.id].orEmpty(), playing = t.id == playingId, onClick = { onPlay(tracks, i) }, onMore = { onTrackMore(t) })
                        }
                        IconButton(onClick = { vm.removeFromPlaylist(playlistId, t.id) }) { Icon(Icons.Default.Delete, contentDescription = "Remove") }
                    }
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
    }
    if (editing) {
        var name by remember { mutableStateOf(playlist?.name.orEmpty()) }
        var desc by remember { mutableStateOf(playlist?.description.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Edit playlist") },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { vm.renamePlaylist(playlistId, name, desc); editing = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete playlist?") },
            text = { Text("The tracks stay in your library.") },
            confirmButton = { TextButton(onClick = { vm.deletePlaylist(playlistId); confirmDelete = false; onBack() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
