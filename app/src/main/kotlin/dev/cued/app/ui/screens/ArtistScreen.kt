package dev.cued.app.ui.screens

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.cued.app.data.db.TrackEntity
import dev.cued.app.ui.LibraryViewModel
import dev.cued.app.ui.components.AlbumArt
import dev.cued.app.ui.components.AlbumCard
import dev.cued.app.ui.components.EmptyHint
import dev.cued.app.ui.components.SectionHeader
import dev.cued.app.ui.components.TrackRow
import dev.cued.app.ui.theme.Muted
import dev.cued.core.library.Discography

/**
 * One artist's shelf: albums, singles & EPs, loose tracks, and tracks they
 * appear on elsewhere. The search box narrows every section at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistScreen(
    vm: LibraryViewModel,
    name: String,
    playingId: Long?,
    onBack: () -> Unit,
    onPlay: (List<TrackEntity>, Int) -> Unit,
    onTrackMore: (TrackEntity) -> Unit,
    onOpenAlbum: (String, String) -> Unit,
) {
    val index by vm.discography.collectAsState()
    val genreMap by vm.genreMap.collectAsState()
    val artist = index.artist(name)
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim()
    fun List<Discography.Album<TrackEntity>>.hit() = if (q.isBlank()) this else filter { a -> a.name.contains(q, true) || a.tracks.any { it.title.contains(q, true) } }
    fun List<TrackEntity>.hit() = if (q.isBlank()) this else filter { it.title.contains(q, true) || it.album.contains(q, true) }

    Scaffold(topBar = {
        TopAppBar(title = { Text(name) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } })
    }) { pad ->
        if (artist == null) { Column(Modifier.padding(pad).fillMaxSize()) { EmptyHint("No tracks by $name in the library") }; return@Scaffold }
        val albums = artist.albums.hit()
        val singles = artist.singles.hit()
        val loose = artist.loose.hit()
        val appears = artist.appearsOn.hit()
        val everything = remember(artist) { artist.ownTracks }
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    AlbumArt(artist.coverTrack, Modifier.size(96.dp), corner = 48)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(artist.name, style = MaterialTheme.typography.headlineSmall)
                        val bits = ArrayList<String>()
                        if (artist.albums.isNotEmpty()) bits += "${artist.albums.size} album" + if (artist.albums.size == 1) "" else "s"
                        if (artist.singles.isNotEmpty()) bits += "${artist.singles.size} single" + if (artist.singles.size == 1) "" else "s"
                        if (artist.trackCount > 0) bits += "${artist.trackCount} tracks"
                        if (artist.appearsOn.isNotEmpty()) bits += "appears on ${artist.appearsOn.size}"
                        Text(bits.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = Muted)
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { if (everything.isNotEmpty()) onPlay(everything, 0) }, enabled = everything.isNotEmpty()) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Play") }
                            OutlinedButton(onClick = { if (everything.isNotEmpty()) onPlay(everything.shuffled(), 0) }, enabled = everything.isNotEmpty()) { Icon(Icons.Default.Shuffle, null); Spacer(Modifier.width(4.dp)); Text("Shuffle") }
                        }
                    }
                }
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true, placeholder = { Text("Search in ${artist.name}") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear") } },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (albums.isNotEmpty()) {
                item { SectionHeader("Albums", "Newest first") }
                items(albums.chunked(2), key = { it.first().key }) { pair -> AlbumPair(pair, onOpenAlbum) }
            }
            if (singles.isNotEmpty()) {
                item { SectionHeader("Singles & EPs") }
                items(singles.chunked(2), key = { "s" + it.first().key }) { pair -> AlbumPair(pair, onOpenAlbum) }
            }
            if (loose.isNotEmpty()) {
                item { SectionHeader("Tracks", "No album tag") }
                items(loose, key = { "l${it.id}" }) { t -> TrackRow(t, genres = genreMap[t.id].orEmpty(), playing = t.id == playingId, onClick = { onPlay(loose, loose.indexOf(t)) }, onMore = { onTrackMore(t) }) }
            }
            if (appears.isNotEmpty()) {
                item { SectionHeader("Appears on", "Credited on other artists' releases") }
                items(appears, key = { "a${it.id}" }) { t -> TrackRow(t, genres = genreMap[t.id].orEmpty(), playing = t.id == playingId, subtitle = t.album.ifBlank { null }, onClick = { onPlay(appears, appears.indexOf(t)) }, onMore = { onTrackMore(t) }) }
            }
            if (albums.isEmpty() && singles.isEmpty() && loose.isEmpty() && appears.isEmpty()) item { EmptyHint("Nothing matches \"$q\"") }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }
}

@Composable
private fun AlbumPair(pair: List<Discography.Album<TrackEntity>>, onOpen: (String, String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
        for (a in pair) AlbumCard(a, Modifier.weight(1f)) { onOpen(a.artist, a.name) }
        if (pair.size == 1) Spacer(Modifier.weight(1f))
    }
}
