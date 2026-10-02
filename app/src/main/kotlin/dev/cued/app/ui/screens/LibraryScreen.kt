package dev.cued.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.cued.app.data.db.TrackEntity
import dev.cued.app.ui.LibraryViewModel
import dev.cued.app.ui.components.AlbumCard
import dev.cued.app.ui.components.AlphabetRail
import dev.cued.app.ui.components.ArtistRow
import dev.cued.app.ui.components.EmptyHint
import dev.cued.app.ui.components.GenreChips
import dev.cued.app.ui.components.SectionHeader
import dev.cued.app.ui.components.TrackRow
import dev.cued.core.library.Discography
import kotlinx.coroutines.launch

const val UNLABELLED = "__unlabelled__"

private val TABS = listOf("Artists", "Albums", "Tracks", "Genres")

/**
 * The library, four ways: Artists (→ albums → tracks), Albums, every Track,
 * and Genres. The search box narrows whichever tab is open, so you can
 * drill in progressively instead of scrolling one giant list.
 */
@Composable
fun LibraryScreen(
    vm: LibraryViewModel,
    playingId: Long?,
    onPlay: (List<TrackEntity>, Int) -> Unit,
    onTrackMore: (TrackEntity) -> Unit,
    onOpenGenre: (String) -> Unit,
    onOpenArtist: (String) -> Unit = {},
    onOpenAlbum: (String, String) -> Unit = { _, _ -> },
) {
    val query by vm.query.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val index by vm.discography.collectAsState()

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query, onValueChange = { vm.query.value = it },
            placeholder = { Text(when (tab) { 0 -> "Search artists"; 1 -> "Search albums"; 3 -> "Search genres"; else -> "Search title, artist, album" }) }, singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear") } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        TabRow(selectedTabIndex = tab) {
            TABS.forEachIndexed { i, label -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) }) }
        }
        when (tab) {
            0 -> ArtistsTab(index, query, onOpenArtist)
            1 -> AlbumsTab(index, query, onOpenAlbum)
            2 -> TracksTab(vm, playingId, onPlay, onTrackMore)
            else -> GenresTab(vm, query, onOpenGenre)
        }
    }
}

@Composable
private fun ArtistsTab(index: Discography.Index<TrackEntity>, query: String, onOpen: (String) -> Unit) {
    val q = query.trim()
    val artists = remember(index, q) {
        if (q.isBlank()) index.artists
        else index.artists.filter { a -> a.name.contains(q, true) || a.albums.any { it.name.contains(q, true) } || a.singles.any { it.name.contains(q, true) } }
    }
    val letters = remember(artists) { artists.map { Discography.indexLetter(it.name) }.distinct() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    if (artists.isEmpty()) { EmptyHint(if (q.isBlank()) "No music yet. Pull down from Home to rescan, or download something." else "No artists match"); return }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            item { SectionHeader("${artists.size} artists") }
            itemsIndexed(artists, key = { _, a -> a.name }) { _, a -> ArtistRow(a) { onOpen(a.name) } }
            item { Spacer(Modifier.height(96.dp)) }
        }
        AlphabetRail(letters, onJump = { c ->
            val i = artists.indexOfFirst { Discography.indexLetter(it.name) == c }
            if (i >= 0) scope.launch { listState.scrollToItem(i + 1) }
        }, modifier = Modifier.align(Alignment.CenterEnd).padding(bottom = 96.dp))
    }
}

@Composable
private fun AlbumsTab(index: Discography.Index<TrackEntity>, query: String, onOpen: (String, String) -> Unit) {
    val q = query.trim()
    val albums = remember(index, q) { if (q.isBlank()) index.albums else index.albums.filter { it.name.contains(q, true) || it.artist.contains(q, true) } }
    if (albums.isEmpty()) { EmptyHint(if (q.isBlank()) "No albums yet" else "No albums match"); return }
    LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 10.dp, end = 10.dp, bottom = 96.dp)) {
        items(albums, key = { it.key }) { a -> AlbumCard(a, showArtist = true) { onOpen(a.artist, a.name) } }
    }
}

@Composable
private fun TracksTab(vm: LibraryViewModel, playingId: Long?, onPlay: (List<TrackEntity>, Int) -> Unit, onTrackMore: (TrackEntity) -> Unit) {
    val tracks by vm.tracks.collectAsState()
    val genreMap by vm.genreMap.collectAsState()
    val query by vm.query.collectAsState()
    SectionHeader("${tracks.size} tracks")
    if (tracks.isEmpty()) { EmptyHint(if (query.isBlank()) "No music yet. Pull down from Home to rescan, or download something." else "No matches"); return }
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
            TrackRow(t, genres = genreMap[t.id].orEmpty(), playing = t.id == playingId, onClick = { onPlay(tracks, i) }, onMore = { onTrackMore(t) })
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}

@Composable
private fun GenresTab(vm: LibraryViewModel, query: String, onOpenGenre: (String) -> Unit) {
    val genres by vm.genres.collectAsState()
    val unlabelledCount by vm.unlabelledCount.collectAsState()
    val q = query.trim()
    val shown = if (q.isBlank()) genres else genres.filter { it.contains(q, true) }
    val chips = (if (unlabelledCount > 0 && q.isBlank()) listOf("unlabelled ($unlabelledCount)") else emptyList()) + shown
    SectionHeader("${shown.size} genres", "Tap one to see its tracks")
    if (chips.isEmpty()) { EmptyHint("No genre labels yet. Settings → Genres can read them from tags."); return }
    GenreChips(chips, onClick = { if (it.startsWith("unlabelled")) onOpenGenre(UNLABELLED) else onOpenGenre(it) })
}
