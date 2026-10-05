package dev.cued.app.ui.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.cued.app.BuildConfig
import dev.cued.app.data.db.TrackEntity
import dev.cued.app.download.native.Tagger
import dev.cued.app.tagging.TrackFile
import dev.cued.app.ui.LibraryViewModel
import dev.cued.app.ui.LocalGraph
import dev.cued.app.ui.components.AlbumArt
import dev.cued.app.ui.components.Artwork
import dev.cued.app.ui.components.SectionHeader
import dev.cued.app.ui.components.formatMs
import dev.cued.app.ui.theme.Muted
import dev.cued.app.ui.theme.Teal
import kotlinx.coroutines.launch

/**
 * Everything CUEd knows about one file, editable: the tags in the library,
 * the tags actually inside the file, a cover fetcher, a MusicBrainz lookup,
 * and a way to write it all back or download the track again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackDetailsScreen(vm: LibraryViewModel, trackId: Long, onBack: () -> Unit, onRedownload: (String) -> Unit) {
    val graph = LocalGraph.current
    val flow = remember(trackId) { vm.track(trackId) }
    val track by flow.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val files = remember { TrackFile(graph.app, "CUEd/${BuildConfig.VERSION_NAME} (https://github.com/ShinobiHanzo/CUEd)") }

    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var album by remember { mutableStateOf("") }
    var albumArtist by remember { mutableStateOf("") }
    var trackNo by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var fileTags by remember { mutableStateOf<TrackFile.FileTags?>(null) }
    var newCover by remember { mutableStateOf<ByteArray?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var pendingWrite by remember { mutableStateOf<(() -> Unit)?>(null) }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val again = pendingWrite; pendingWrite = null
        if (res.resultCode == Activity.RESULT_OK) again?.invoke() else status = "Permission not granted; the file was left unchanged"
    }

    fun refreshFile(t: TrackEntity) { scope.launch { fileTags = files.read(android.net.Uri.parse(t.uri)) } }
    LaunchedEffect(track?.id) {
        val t = track ?: return@LaunchedEffect
        if (!loaded) {
            title = t.title; artist = t.artist; album = t.album; albumArtist = t.albumArtist.orEmpty()
            trackNo = t.trackNo.takeIf { it > 0 }?.toString().orEmpty(); year = t.year.takeIf { it > 0 }?.toString().orEmpty(); source = t.sourceLink.orEmpty()
            loaded = true
        }
        refreshFile(t)
    }

    fun meta(): Tagger.Meta = Tagger.Meta(
        title = title.trim(), artists = artist.split(",").map { it.trim() }.filter { it.isNotBlank() }, album = album.trim().ifBlank { null },
        albumArtist = albumArtist.trim().ifBlank { null }, trackNumber = trackNo.trim().toIntOrNull(), year = year.trim().ifBlank { null },
        genres = emptyList(), coverUrl = null, lyrics = null, comment = source.trim().ifBlank { null }, cover = newCover,
    )

    fun save(writeFile: Boolean) {
        val t = track ?: return
        val m = meta()
        busy = if (writeFile) "Writing tags…" else "Saving…"
        scope.launch {
            // Library row first: this is what every screen shows, file or not.
            graph.library.updateDetails(t.id, m.title, m.artists.joinToString(", "), m.album ?: "", m.albumArtist, m.trackNumber ?: 0, m.year?.take(4)?.toIntOrNull() ?: 0, m.comment)
            if (!writeFile) { busy = null; status = "Saved to the library (file untouched)"; return@launch }
            when (val r = files.write(t, m, keepCover = newCover == null)) {
                is TrackFile.WriteResult.Done -> { status = "Tags written to the file"; newCover = null; Artwork.forget(t.uri); graph.library.rescanAsync(); refreshFile(t) }
                is TrackFile.WriteResult.NeedsConsent -> { status = "Android is asking for permission to change this file…"; pendingWrite = { save(true) }; consent.launch(IntentSenderRequest.Builder(r.sender).build()) }
                is TrackFile.WriteResult.Failed -> status = "Couldn't write the file: ${r.reason}"
            }
            busy = null
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Track details") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } })
    }) { pad ->
        val t = track
        if (t == null) { Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { Text("Track not found", color = Muted) }; return@Scaffold }
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val nc = newCover
                if (nc != null) {
                    val bmp = remember(nc) { android.graphics.BitmapFactory.decodeByteArray(nc, 0, nc.size) }
                    if (bmp != null) Image(bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(120.dp).clip(RoundedCornerShape(14.dp)))
                } else AlbumArt(t, Modifier.size(120.dp), corner = 14, px = Artwork.LARGE)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    val ft = fileTags
                    Text(if (ft == null) "Reading the file…" else if (ft.coverBytes > 0) "Cover in file: ${ft.coverBytes / 1024} KB" else "No cover inside the file", style = MaterialTheme.typography.bodyMedium, color = if ((ft?.coverBytes ?: 0) > 0) Teal else Muted)
                    if (nc != null) Text("New cover ready (${nc.size / 1024} KB): tap \"Write to file\" to keep it", style = MaterialTheme.typography.bodySmall, color = Teal)
                    Row {
                        TextButton(onClick = {
                            busy = "Finding a cover…"
                            scope.launch { val c = files.findCover(t.copy(sourceLink = source.ifBlank { null }), artist, title); newCover = c; status = if (c == null) "No cover found online for this title and artist" else "Cover found"; busy = null }
                        }, enabled = busy == null) { Text("Find cover") }
                        if (nc != null) TextButton(onClick = { newCover = null }) { Text("Discard") }
                    }
                }
            }

            SectionHeader("Details", "As CUEd shows them. \"Write to file\" also puts them in the tags.")
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = artist, onValueChange = { artist = it }, label = { Text("Artist") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            OutlinedTextField(value = album, onValueChange = { album = it }, label = { Text("Album") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            OutlinedTextField(value = albumArtist, onValueChange = { albumArtist = it }, label = { Text("Album artist") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = trackNo, onValueChange = { trackNo = it.filter { c -> c.isDigit() } }, label = { Text("Track #") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(value = year, onValueChange = { year = it.filter { c -> c.isDigit() }.take(4) }, label = { Text("Year") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(value = source, onValueChange = { source = it }, label = { Text("Source link (Spotify / YouTube)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))

            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    busy = "Asking MusicBrainz…"
                    scope.launch {
                        val r = files.lookup(artist, title)
                        if (r == null) status = "MusicBrainz has no confident match for \"$artist - $title\""
                        else { title = r.title; artist = r.artist; r.album?.let { album = it }; r.albumArtist?.let { albumArtist = it }; if (r.trackNo > 0) trackNo = r.trackNo.toString(); if (r.year > 0) year = r.year.toString(); status = "Filled from MusicBrainz (match ${r.score}%). Check it, then write." }
                        busy = null
                    }
                }, enabled = busy == null) { Text("Look up online") }
                OutlinedButton(onClick = {
                    val ft = fileTags
                    if (ft != null) { ft.title?.let { title = it }; ft.artist?.let { artist = it }; ft.album?.let { album = it }; ft.albumArtist?.let { albumArtist = it }; if (ft.trackNo > 0) trackNo = ft.trackNo.toString(); if (ft.year > 0) year = ft.year.toString(); status = "Fields reloaded from the file's tags" }
                }, enabled = busy == null && fileTags != null) { Text("Reload from file") }
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { save(true) }, enabled = busy == null) { Text("Write to file") }
                OutlinedButton(onClick = { save(false) }, enabled = busy == null) { Text("Save in CUEd only") }
            }
            busy?.let { Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text(it, color = Muted) } }
            status?.let { Text(it, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium, color = Teal) }

            SectionHeader("Inside the file", "What Android's tag reader sees right now")
            val ft = fileTags
            if (ft == null) Text("Reading…", color = Muted) else Column {
                FactRow("Title", ft.title); FactRow("Artist", ft.artist); FactRow("Album", ft.album); FactRow("Album artist", ft.albumArtist)
                FactRow("Track / year", listOfNotNull(ft.trackNo.takeIf { it > 0 }?.toString(), ft.year.takeIf { it > 0 }?.toString()).joinToString(" / ").ifBlank { null })
                FactRow("Genre", ft.genre); FactRow("Cover", if (ft.coverBytes > 0) "${ft.coverBytes / 1024} KB" else "none")
                FactRow("Format", listOfNotNull(ft.mime, ft.bitrateKbps.takeIf { it > 0 }?.let { "$it kbps" }, formatMs(ft.durationMs), (ft.sizeBytes / 1_048_576.0).let { "%.1f MB".format(it) }).joinToString(" · "))
                FactRow("Path", t.path)
            }

            SectionHeader("Download again", if (source.isBlank()) "No source link: the search below uses the title and artist" else "Fetches a fresh copy from the source link")
            OutlinedButton(onClick = { onRedownload(source.trim().ifBlank { "$artist - $title" }) }) { Text("Re-download") }
            Text("The new copy arrives as a separate file. Delete this one from its menu once you're happy.", style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(96.dp))
        }
    }
}

@Composable
private fun FactRow(label: String, value: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, Modifier.width(110.dp), style = MaterialTheme.typography.bodySmall, color = Muted)
        Text(value ?: "—", style = MaterialTheme.typography.bodySmall)
    }
}
