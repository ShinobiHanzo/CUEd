@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.cued.app.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cued.app.data.db.TrackEntity
import dev.cued.app.ui.LibraryViewModel
import dev.cued.app.ui.components.AlbumArt
import dev.cued.app.ui.components.EmptyHint
import dev.cued.app.ui.components.SectionHeader
import dev.cued.app.ui.components.formatMs
import dev.cued.app.ui.theme.Amber
import dev.cued.app.ui.theme.Ink3
import dev.cued.app.ui.theme.Muted
import dev.cued.app.ui.theme.Teal

/**
 * Podcasts, audiobooks, DJ mixes: anything over 12 minutes lands here instead
 * of the music library, remembers where you stopped, and never gets
 * crossfaded or recommended as a "song".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LongPlaysScreen(vm: LibraryViewModel, playingId: Long?, onBack: () -> Unit, onPlay: (List<TrackEntity>, Int) -> Unit, onTrackMore: (TrackEntity) -> Unit) {
    val items by vm.longPlays.collectAsState()
    val inProgress = items.filter { it.resumeMs > 0L }
    val rest = items.filter { it.resumeMs == 0L }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Podcasts & audiobooks") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
        )
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item { SectionHeader("Long plays", "Files over 12 minutes. Move anything wrongly sorted from its menu.") }
            if (items.isEmpty()) item { EmptyHint("Nothing over 12 minutes in the library") }
            if (inProgress.isNotEmpty()) {
                item { SectionHeader("Continue listening") }
                items(inProgress, key = { "p${it.id}" }) { t -> LongRow(t, t.id == playingId, onClick = { onPlay(listOf(t), 0) }, onMore = { onTrackMore(t) }) }
            }
            if (rest.isNotEmpty()) {
                item { SectionHeader("All") }
                items(rest, key = { it.id }) { t -> LongRow(t, t.id == playingId, onClick = { onPlay(listOf(t), 0) }, onMore = { onTrackMore(t) }) }
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }
}

@Composable
private fun LongRow(t: TrackEntity, playing: Boolean, onClick: () -> Unit, onMore: () -> Unit) {
    Column(Modifier.fillMaxWidth().androidx.compose.foundation.combinedClickable(onClick = onClick, onLongClick = onMore).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AlbumArt(t, Modifier.size(56.dp), corner = 10, px = dev.cued.app.ui.components.Artwork.SMALL)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (playing) Teal else MaterialTheme.colorScheme.onSurface, fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal)
                val left = (t.durationMs - t.resumeMs).coerceAtLeast(0L)
                Text(
                    buildString {
                        append(t.artist); append("  ·  ")
                        if (t.resumeMs > 0L) append("${formatMs(left)} left of ${formatMs(t.durationMs)}") else append(formatMs(t.durationMs))
                    },
                    style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onMore) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
        }
        if (t.resumeMs > 0L && t.durationMs > 0L) {
            LinearProgressIndicator(
                progress = { (t.resumeMs.toFloat() / t.durationMs).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp), color = Amber, trackColor = Ink3,
            )
        }
    }
}
