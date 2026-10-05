package dev.cued.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.cued.app.data.db.PlaylistEntity
import dev.cued.app.data.db.TrackEntity
import dev.cued.app.ui.theme.Muted

/** Long-press / "more" menu for a track. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackSheet(
    track: TrackEntity,
    genres: List<String>,
    allGenres: List<String>,
    playlists: List<PlaylistEntity>,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onEnqueue: () -> Unit,
    onToggleFavourite: () -> Unit,
    onAddToPlaylist: (Long) -> Unit,
    onCreatePlaylistAndAdd: (String) -> Unit,
    onSetGenres: (List<String>) -> Unit,
    onSetSourceLink: (String) -> Unit,
    onShare: () -> Unit,
    onSimilar: () -> Unit,
    onAnalyse: () -> Unit,
    onArtist: (() -> Unit)? = null,
    onAlbum: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onDetails: (() -> Unit)? = null,
    onToggleKind: () -> Unit = {},
    onUnlockGenres: () -> Unit = {},
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showPlaylists by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showGenres by remember { mutableStateOf(false) }
    var showLink by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        // The menu is taller than most screens now: scroll, and clear the navigation bar.
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
            Text(track.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp))
            Text(
                buildString {
                    append(track.artist)
                    track.bpm?.let { append("  ·  ${"%.1f".format(it)} BPM (${((track.bpmConfidence ?: 0f) * 100).toInt()}% sure)") }
                    append("  ·  played ${track.playCount}×")
                },
                style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            if (onDetails != null) SheetItem(Icons.Default.Info, "Track details (edit, cover, re-download)") { onDetails(); onDismiss() }
            SheetItem(Icons.Default.PlayArrow, "Play") { onPlay(); onDismiss() }
            SheetItem(Icons.AutoMirrored.Filled.QueueMusic, "Play next") { onPlayNext(); onDismiss() }
            SheetItem(Icons.AutoMirrored.Filled.PlaylistAdd, "Add to queue") { onEnqueue(); onDismiss() }
            SheetItem(if (track.favourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (track.favourite) "Remove from favourites" else "Add to favourites") { onToggleFavourite(); onDismiss() }
            SheetItem(Icons.AutoMirrored.Filled.PlaylistAdd, "Add to playlist") { showPlaylists = true }
            SheetItem(Icons.AutoMirrored.Filled.Label, "Edit genres" + (if (genres.isNotEmpty()) " (${genres.joinToString(", ")})" else "") + (if (track.genresLocked) " · hand-edited" else "")) { showGenres = true }
            if (track.genresLocked) SheetItem(Icons.Default.LockOpen, "Let automatic labelling change this track again") { onUnlockGenres(); onDismiss() }
            SheetItem(Icons.Default.Timeline, "More like this") { onSimilar(); onDismiss() }
            if (onArtist != null) SheetItem(Icons.Default.Person, "Go to artist") { onArtist(); onDismiss() }
            if (onAlbum != null && track.album.isNotBlank()) SheetItem(Icons.Default.Album, "Go to album") { onAlbum(); onDismiss() }
            SheetItem(Icons.Default.Share, "Share (QR / NFC / local)") { onShare(); onDismiss() }
            SheetItem(Icons.Default.Link, if (track.sourceLink == null) "Set source link (for re-download sharing)" else "Source: ${track.sourceLink}") { showLink = true }
            if (!track.isLong) SheetItem(Icons.Default.GraphicEq, if (track.analysedAt == null) "Analyse tempo" else "Re-analyse tempo") { onAnalyse(); onDismiss() }
            SheetItem(Icons.Default.SwapHoriz, if (track.isLong) "Move to Music" else "Move to Podcasts & audiobooks") { onToggleKind(); onDismiss() }
            if (onDelete != null) SheetItem(Icons.Default.DeleteForever, "Delete from device") { showDelete = true }
        }
    }

    if (showPlaylists) {
        var newName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPlaylists = false },
            title = { Text("Add to playlist") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newName, onValueChange = { newName = it }, label = { Text("New playlist") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (newName.isNotBlank()) { onCreatePlaylistAndAdd(newName.trim()); showPlaylists = false; onDismiss() } }),
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.height(240.dp)) {
                        items(playlists) { p ->
                            ListItem(headlineContent = { Text(p.name) }, modifier = Modifier.padding(0.dp), colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                leadingContent = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                                trailingContent = { TextButton(onClick = { onAddToPlaylist(p.id); showPlaylists = false; onDismiss() }) { Text("Add") } })
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { if (newName.isNotBlank()) { onCreatePlaylistAndAdd(newName.trim()); showPlaylists = false; onDismiss() } }) { Text("Create & add") } },
            dismissButton = { TextButton(onClick = { showPlaylists = false }) { Text("Close") } },
        )
    }

    if (showGenres) {
        GenreEditor(current = genres, suggestions = allGenres, onDismiss = { showGenres = false }, onSave = { onSetGenres(it); showGenres = false })
    }

    if (showDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete from device?") },
            text = { Text("\"${track.title}\" will be removed from your phone, not just from CUEd. Playlists lose it. This cannot be undone." + if (track.sourceLink != null) " It can be downloaded again from its source link." else "") },
            confirmButton = { TextButton(onClick = { showDelete = false; onDelete(); onDismiss() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } },
        )
    }
    if (showLink) {
        var link by remember { mutableStateOf(track.sourceLink.orEmpty()) }
        AlertDialog(
            onDismissRequest = { showLink = false },
            title = { Text("Source link") },
            text = {
                Column {
                    Text("A Spotify or YouTube URL. When you share this track as a link, the other phone re-downloads it with spotdl instead of copying the file.", style = MaterialTheme.typography.bodySmall, color = Muted)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = link, onValueChange = { link = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { onSetSourceLink(link); showLink = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showLink = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SheetItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenreEditor(current: List<String>, suggestions: List<String>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    var chosen by remember { mutableStateOf(current.toList()) }
    var input by remember { mutableStateOf("") }
    fun add(g: String) { val n = g.trim().lowercase(); if (n.isNotEmpty() && n !in chosen) chosen = chosen + n; input = "" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Genres") },
        text = {
            Column {
                OutlinedTextField(
                    value = input, onValueChange = { input = it }, label = { Text("Add a genre") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { add(input) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    androidx.compose.foundation.lazy.LazyRow {
                        items(chosen) { g -> InputChip(selected = true, onClick = { chosen = chosen - g }, label = { Text(g) }, modifier = Modifier.padding(end = 4.dp)) }
                    }
                }
                val suggest = suggestions.filter { it !in chosen && (input.isBlank() || it.contains(input.trim().lowercase())) }
                if (suggest.isNotEmpty()) {
                    Text("Existing labels", style = MaterialTheme.typography.labelSmall, color = Muted)
                    androidx.compose.foundation.lazy.LazyRow {
                        items(suggest.take(30)) { g -> InputChip(selected = false, onClick = { add(g) }, label = { Text(g) }, modifier = Modifier.padding(end = 4.dp)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (input.isNotBlank()) add(input); onSave(chosen) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
