package dev.cued.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.cued.app.data.db.TrackEntity
import dev.cued.app.ui.components.EmptyHint
import dev.cued.app.ui.components.TrackRow

/** A generic list of tracks with play-all / shuffle / save-as-playlist. Used for smart lists, genres and "more like this". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackListScreen(
    title: String,
    subtitle: String?,
    tracks: List<TrackEntity>,
    genreMap: Map<Long, List<String>>,
    reasons: Map<Long, List<String>> = emptyMap(),
    playingId: Long?,
    onBack: () -> Unit,
    onPlay: (List<TrackEntity>, Int) -> Unit,
    onTrackMore: (TrackEntity) -> Unit,
    onSaveAsPlaylist: ((String) -> Unit)? = null,
) {
    var showSave by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text(title); if (subtitle != null) Text(subtitle, style = androidx.compose.material3.MaterialTheme.typography.bodySmall) } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    IconButton(onClick = { if (tracks.isNotEmpty()) onPlay(tracks, 0) }) { Icon(Icons.Default.PlayArrow, contentDescription = "Play all") }
                    IconButton(onClick = { if (tracks.isNotEmpty()) onPlay(tracks.shuffled(), 0) }) { Icon(Icons.Default.Shuffle, contentDescription = "Shuffle") }
                    if (onSaveAsPlaylist != null) IconButton(onClick = { showSave = true }) { Icon(Icons.Default.Save, contentDescription = "Save as playlist") }
                },
            )
        },
    ) { pad ->
        if (tracks.isEmpty()) {
            Column(Modifier.padding(pad).fillMaxSize()) { EmptyHint("Nothing here yet") }
        } else {
            LazyColumn(Modifier.padding(pad).fillMaxSize()) {
                itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
                    TrackRow(
                        t, genres = genreMap[t.id].orEmpty(), playing = t.id == playingId,
                        subtitle = reasons[t.id]?.joinToString(", "),
                        onClick = { onPlay(tracks, i) }, onMore = { onTrackMore(t) },
                    )
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
    }
    if (showSave && onSaveAsPlaylist != null) {
        var name by remember { mutableStateOf(title) }
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text("Save as playlist") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) { onSaveAsPlaylist(name.trim()); showSave = false } }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showSave = false }) { Text("Cancel") } },
        )
    }
}
