package dev.cued.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.cued.app.data.db.TrackEntity
import dev.cued.app.ui.LibraryViewModel
import dev.cued.app.ui.components.EmptyHint
import dev.cued.app.ui.components.GenreChips
import dev.cued.app.ui.components.SectionHeader
import dev.cued.app.ui.components.TrackRow

const val UNLABELLED = "__unlabelled__"

@Composable
fun LibraryScreen(
    vm: LibraryViewModel,
    playingId: Long?,
    onPlay: (List<TrackEntity>, Int) -> Unit,
    onTrackMore: (TrackEntity) -> Unit,
    onOpenGenre: (String) -> Unit,
) {
    val query by vm.query.collectAsState()
    val tracks by vm.tracks.collectAsState()
    val genreMap by vm.genreMap.collectAsState()
    val genres by vm.genres.collectAsState()

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query, onValueChange = { vm.query.value = it },
            placeholder = { Text("Search title, artist, album") }, singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear") } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        val unlabelledCount by vm.unlabelledCount.collectAsState()
        val chips = (if (unlabelledCount > 0) listOf("unlabelled ($unlabelledCount)") else emptyList()) + genres
        if (chips.isNotEmpty()) GenreChips(chips, onClick = { if (it.startsWith("unlabelled")) onOpenGenre(UNLABELLED) else onOpenGenre(it) })
        SectionHeader("${tracks.size} tracks")
        if (tracks.isEmpty()) EmptyHint(if (query.isBlank()) "No music yet. Pull down from Home to rescan, or download something." else "No matches")
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
                TrackRow(t, genres = genreMap[t.id].orEmpty(), playing = t.id == playingId, onClick = { onPlay(tracks, i) }, onMore = { onTrackMore(t) })
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }
}
