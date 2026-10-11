package dev.cued.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.cued.app.data.db.ReleaseEntity
import dev.cued.app.data.db.ReleaseTrackEntity
import dev.cued.app.data.db.TrackEntity
import dev.cued.app.ui.FeedViewModel
import dev.cued.app.ui.components.EmptyHint
import dev.cued.app.ui.components.SectionHeader
import dev.cued.app.ui.components.formatMs
import dev.cued.app.ui.theme.Amber
import dev.cued.app.ui.theme.Ink3
import dev.cued.app.ui.theme.Muted
import dev.cued.app.ui.theme.Teal
import dev.cued.core.feed.ReleaseFeed
import dev.cued.core.library.Discography
import java.text.DateFormat

/*
 * The release feed: "New releases" and "Your artists" on the home page, an
 * artist's RSS-like page of everything they put out, and a release page whose
 * tracks show metadata and covers before any audio exists on the phone.
 * Metadata is MusicBrainz, cached in Room; covers are the Cover Art Archive.
 */

/** Home page row: the newest releases from artists you play or follow. */
@Composable
fun NewReleasesRow(fvm: FeedViewModel, onOpenRelease: (String) -> Unit) {
    val releases by fvm.newReleases.collectAsState()
    val refreshing by fvm.refreshing.collectAsState()
    val note by fvm.note.collectAsState()
    Column {
        SectionHeader("New releases", note ?: "From the artists you follow") {
            if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else IconButton(onClick = { fvm.refresh() }) { Icon(Icons.Default.Refresh, contentDescription = "Check for new releases") }
        }
        if (releases.isEmpty()) EmptyHint(if (refreshing) "Checking for new releases…" else "Nothing new from the artists you follow. Follow some below.")
        else LazyRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            items(releases, key = { it.id }) { r -> ReleaseCard(r, Modifier.width(132.dp).padding(8.dp)) { onOpenRelease(r.id) } }
        }
    }
}

/** Home page row: the artists you follow, each opening their release page. */
@Composable
fun FollowedArtistsRow(fvm: FeedViewModel, onOpenArtist: (String) -> Unit) {
    val followed by fvm.followed.collectAsState()
    val suggestions by fvm.suggestions.collectAsState()
    Column {
        SectionHeader("Your artists", "Followed artists and everything they release")
        if (followed.isEmpty()) EmptyHint("Open an artist and tap the star to follow them")
        else LazyRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            items(followed, key = { it.name }) { a -> ArtistTile(fvm, a.name, Modifier.width(112.dp).padding(8.dp)) { onOpenArtist(a.name) } }
        }
        // One-tap follows for the artists you actually play.
        if (suggestions.isNotEmpty()) LazyRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            items(suggestions, key = { "s:$it" }) { name ->
                AssistChip(onClick = { fvm.setFollowed(name, true) }, label = { Text("Follow $name") }, leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp)) }, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
    }
}

@Composable
private fun Cover(url: String?, modifier: Modifier, corner: Int = 12) {
    Box(modifier.clip(RoundedCornerShape(corner.dp)).background(Ink3), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Album, contentDescription = null, tint = Muted)
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun ReleaseCard(r: ReleaseEntity, modifier: Modifier, onClick: () -> Unit) {
    val now = System.currentTimeMillis()
    Column(modifier.clickable(onClick = onClick)) {
        Cover(r.coverUrl, Modifier.size(116.dp))
        Spacer(Modifier.height(6.dp))
        Text(r.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        Text(r.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Muted)
        Text(
            (if (ReleaseFeed.isUpcoming(r.dateMs, now)) "Out " else "") + r.type + " · " + ReleaseFeed.formatDate(r.date),
            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
            color = if (ReleaseFeed.isUpcoming(r.dateMs, now)) Amber else Teal,
        )
    }
}

@Composable
private fun ArtistTile(fvm: FeedViewModel, name: String, modifier: Modifier, onClick: () -> Unit) {
    val latest by remember(name) { fvm.latestFor(name) }.collectAsState(initial = null)
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(96.dp).clip(CircleShape).background(Ink3), contentAlignment = Alignment.Center) {
            Text(name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }, style = MaterialTheme.typography.headlineSmall, color = Muted)
            latest?.coverUrl?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Spacer(Modifier.height(6.dp))
        Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
    }
}

/** An artist's page: follow, what you own, and every release newest first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistFeedScreen(fvm: FeedViewModel, name: String, onBack: () -> Unit, onOpenRelease: (String) -> Unit, onOpenLibraryArtist: (String) -> Unit, onMessage: (String) -> Unit) {
    LaunchedEffect(name) { fvm.openArtist(name) }
    val artist by remember(name) { fvm.artist(name) }.collectAsState(initial = null)
    val releases by remember(name) { fvm.releasesFor(name) }.collectAsState(initial = emptyList())
    val refreshing by fvm.refreshing.collectAsState()
    val fetchingArtists by fvm.fetchingArtists.collectAsState()
    val fetching = refreshing || name in fetchingArtists
    val library by fvm.library.collectAsState()
    val owned = remember(library, name) { library.count { Discography.primaryArtist(it.albumArtist?.takeIf { a -> a.isNotBlank() } ?: it.artist).equals(name, ignoreCase = true) } }
    val followed = artist?.followed == true
    val now = System.currentTimeMillis()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(name) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            actions = {
                IconButton(onClick = { fvm.setFollowed(name, !followed) }) { Icon(if (followed) Icons.Default.Star else Icons.Default.StarBorder, contentDescription = if (followed) "Unfollow" else "Follow", tint = if (followed) Amber else Muted) }
                IconButton(onClick = { fvm.refreshArtist(name) }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
            },
        )
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(releases.firstOrNull()?.coverUrl, Modifier.size(96.dp), corner = 48)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.headlineSmall)
                        val bits = ArrayList<String>()
                        if (releases.isNotEmpty()) bits += "${releases.size} release" + if (releases.size == 1) "" else "s"
                        if (owned > 0) bits += "$owned track${if (owned == 1) "" else "s"} in your library"
                        val a = artist
                        bits += when {
                            a == null -> "Looking up…"
                            a.lastError != null -> a.lastError!!
                            a.lastRefreshAt == 0L -> if (fetching) "Fetching the discography…" else "Not fetched yet (offline?)"
                            else -> "Checked " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(a.lastRefreshAt)
                        }
                        Text(bits.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Muted)
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (followed) OutlinedButton(onClick = { fvm.setFollowed(name, false) }) { Icon(Icons.Default.Star, null, tint = Amber); Spacer(Modifier.width(4.dp)); Text("Following") }
                            else Button(onClick = { fvm.setFollowed(name, true) }) { Icon(Icons.Default.StarBorder, null); Spacer(Modifier.width(4.dp)); Text("Follow") }
                            if (owned > 0) TextButton(onClick = { onOpenLibraryArtist(name) }) { Text("In library") }
                        }
                    }
                }
            }
            if (releases.isEmpty()) item {
                if (fetching) Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)); Text("Fetching the discography…", color = Muted) }
                else Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyHint(if (artist?.lastRefreshAt == 0L) "Nothing saved yet. This needs a connection once." else "No albums, EPs or singles found for $name")
                    TextButton(onClick = { fvm.refreshArtist(name) }) { Text("Try again") }
                }
            }
            else {
                item { SectionHeader("Releases", "Newest first · tap one for its tracks") }
                items(releases, key = { it.id }) { r ->
                    Row(Modifier.fillMaxWidth().clickable { onOpenRelease(r.id) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cover(r.coverUrl, Modifier.size(56.dp), corner = 8)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(r.type + " · " + ReleaseFeed.formatDate(r.date), style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                        if (ReleaseFeed.isRecent(r.dateMs, now)) Text(if (ReleaseFeed.isUpcoming(r.dateMs, now)) "SOON" else "NEW", style = MaterialTheme.typography.labelSmall, color = Teal, fontWeight = FontWeight.Bold)
                    }
                }
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }
}

/** One release: cover, facts and its tracks; a tap plays (streaming while the file downloads) or opens the library copy. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReleaseScreen(fvm: FeedViewModel, releaseId: String, onBack: () -> Unit, onOpenArtist: (String) -> Unit, onPlay: (List<TrackEntity>) -> Unit, onEnqueue: (TrackEntity) -> Unit, onMessage: (String) -> Unit) {
    LaunchedEffect(releaseId) { fvm.ensureTracks(releaseId) }
    val release by remember(releaseId) { fvm.release(releaseId) }.collectAsState(initial = null)
    val tracks by remember(releaseId) { fvm.tracks(releaseId) }.collectAsState(initial = emptyList())
    val library by fvm.library.collectAsState()
    val busy by fvm.busy.collectAsState()
    val fetchingReleases by fvm.fetchingReleases.collectAsState()
    val fetching = releaseId in fetchingReleases
    val copies = remember(tracks, library) { tracks.associate { it.position to fvm.libraryCopy(it, library) } }
    val owned = copies.values.count { it != null }

    Scaffold(topBar = {
        TopAppBar(title = { Text(release?.title ?: "Release", maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } })
    }) { pad ->
        val r = release
        if (r == null) { Column(Modifier.padding(pad).fillMaxSize()) { EmptyHint("This release is no longer in the feed") }; return@Scaffold }
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(r.coverUrl, Modifier.size(120.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.title, style = MaterialTheme.typography.titleLarge)
                        Text(r.artist, style = MaterialTheme.typography.bodyMedium, color = Teal, modifier = Modifier.clickable { onOpenArtist(r.artist) })
                        Text(listOf(r.type, ReleaseFeed.formatDate(r.date), if (tracks.isNotEmpty()) "${tracks.size} tracks" else null, if (owned > 0) "$owned in your library" else null).filterNotNull().joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Muted)
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { fvm.playFrom(r, tracks, 0, library, onPlay, onEnqueue) }, enabled = tracks.isNotEmpty() && busy == null) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Play") }
                            if (tracks.size > owned) OutlinedButton(onClick = { fvm.downloadMissing(tracks, library) }) { Icon(Icons.Default.CloudDownload, null); Spacer(Modifier.width(4.dp)); Text("Download") }
                        }
                    }
                }
                busy?.let { Text(it, color = Teal, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp)) }
                Text("Metadata and cover from ${if (r.source == ReleaseEntity.SOURCE_SPOTIFY) "Spotify" else "MusicBrainz"}. Tap a track to play from there: the first song you do not have streams right away while it downloads; the following ones download and join the queue as they land.", style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            if (tracks.isEmpty()) item {
                if (fetching) Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)); Text("Fetching the track list…", color = Muted) }
                else Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyHint(if (r.tracksFetchedAt == 0L) "No track list saved yet. This needs a connection once." else "No track list for this release yet")
                    TextButton(onClick = { fvm.ensureTracks(releaseId, force = true) }) { Text("Try again") }
                }
            }
            items(tracks, key = { it.position }) { t ->
                val copy = copies[t.position]
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = busy == null) { fvm.playFrom(r, tracks, tracks.indexOf(t), library, onPlay, onEnqueue) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${t.position}", style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.width(28.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (copy != null) MaterialTheme.colorScheme.onSurface else Muted)
                        if (!t.artist.equals(r.artist, ignoreCase = true)) Text(t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                    if (t.durationMs > 0) Text(formatMs(t.durationMs), style = MaterialTheme.typography.bodySmall, color = Muted)
                    Spacer(Modifier.width(8.dp))
                    if (copy != null) Icon(Icons.Default.CheckCircle, contentDescription = "In your library", tint = Teal, modifier = Modifier.size(20.dp))
                    else IconButton(onClick = { fvm.download(t) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.CloudDownload, contentDescription = "Download", tint = Muted) }
                }
            }
            item { Spacer(Modifier.height(120.dp)) }
        }
    }
}
