package dev.cued.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.cued.app.data.ScrubberMode
import dev.cued.app.ui.LibraryViewModel
import dev.cued.app.ui.PlayerViewModel
import dev.cued.app.ui.components.AlbumArt
import dev.cued.app.ui.components.PositionScrubber
import dev.cued.app.ui.components.formatMs
import dev.cued.app.ui.theme.Amber
import dev.cued.app.ui.theme.Muted
import dev.cued.app.ui.theme.Teal
import dev.cued.core.dsp.SpectrogramImage

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
fun NowPlayingScreen(pvm: PlayerViewModel, lvm: LibraryViewModel, onClose: () -> Unit, onMore: (Long) -> Unit) {
    val state by pvm.state.collectAsState()
    val track by pvm.currentTrack.collectAsState()
    val transition by pvm.transition.collectAsState()
    val tempo by pvm.currentTempo.collectAsState()
    val ui by pvm.uiSettings.collectAsState()
    var spectro by remember { mutableStateOf<SpectrogramImage?>(null) }
    var showQueue by remember { mutableStateOf(false) }

    // Load (or request) the static spectrogram whenever the track or its analysis changes.
    LaunchedEffect(state.trackId, track?.analysedAt, ui.scrubberMode) {
        val id = state.trackId
        spectro = if (id != null && ui.scrubberMode == ScrubberMode.STATIC_SPECTROGRAM) pvm.spectrogram(id) else null
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Close") }
            Spacer(Modifier.weight(1f))
            Text("Now playing", style = MaterialTheme.typography.labelLarge, color = Muted)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showQueue = true }) { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Queue") }
            IconButton(onClick = { state.trackId?.let(onMore) }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
        }
        Spacer(Modifier.height(12.dp))
        AlbumArt(track?.albumId, Modifier.fillMaxWidth(0.8f).aspectRatio(1f), corner = 20)
        Spacer(Modifier.height(20.dp))
        Text(state.title.ifBlank { "Nothing playing" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(state.artist, style = MaterialTheme.typography.bodyMedium, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        TempoLine(track?.bpm ?: tempo?.bpm, track?.bpmConfidence, transition)
        Spacer(Modifier.height(12.dp))

        PositionScrubber(
            mode = ui.scrubberMode,
            positionMs = state.positionMs,
            durationMs = state.durationMs,
            spectrogram = spectro,
            bus = pvm.spectrumBus,
            visualDelayMs = ui.visualDelayMs,
            onSeek = { pvm.seekTo(it) },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatMs(state.positionMs), style = MaterialTheme.typography.labelSmall, color = Muted)
            Text(formatMs(state.durationMs), style = MaterialTheme.typography.labelSmall, color = Muted)
        }
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val modes = ScrubberMode.entries
            modes.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = ui.scrubberMode == m, onClick = { pvm.setScrubberMode(m) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = modes.size),
                    label = { Text(when (m) { ScrubberMode.STANDARD -> "Bar"; ScrubberMode.STATIC_SPECTROGRAM -> "Static"; ScrubberMode.REACTIVE_SPECTROGRAM -> "Live" }, maxLines = 1) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { pvm.toggleShuffle() }) { Icon(Icons.Default.Shuffle, contentDescription = "Shuffle", tint = if (state.shuffle) Teal else Muted) }
            IconButton(onClick = { pvm.previous() }) { Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(36.dp)) }
            FilledIconButton(onClick = { pvm.togglePlay() }, modifier = Modifier.size(72.dp)) {
                Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Play/pause", modifier = Modifier.size(40.dp))
            }
            IconButton(onClick = { pvm.next() }) { Icon(Icons.Default.SkipNext, contentDescription = "Next", modifier = Modifier.size(36.dp)) }
            IconButton(onClick = { pvm.cycleRepeat() }) {
                Icon(
                    if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    contentDescription = "Repeat", tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) Teal else Muted,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = { track?.let { lvm.toggleFavourite(it) } }) {
                Icon(if (track?.favourite == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = "Favourite", tint = if (track?.favourite == true) Amber else Muted)
            }
            IconButton(onClick = { pvm.blendNow() }, enabled = transition == null && state.queueIndex + 1 < state.queue.size) {
                Icon(Icons.Default.Bolt, contentDescription = "Blend into next now", tint = if (transition == null) Teal else Muted)
            }
        }
    }

    if (showQueue) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showQueue = false }, sheetState = sheet) {
            Text("Queue", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            LazyColumn(Modifier.fillMaxWidth().height(480.dp)) {
                itemsIndexed(state.queue) { i, item ->
                    val active = i == state.queueIndex
                    ListItem(
                        headlineContent = { Text(item.mediaMetadata.title?.toString().orEmpty(), color = if (active) Teal else Color.Unspecified, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(item.mediaMetadata.artist?.toString().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Text("${i + 1}", color = Muted) },
                        trailingContent = { if (!active) IconButton(onClick = { pvm.removeQueueItem(i) }) { Icon(Icons.Default.Delete, contentDescription = "Remove") } },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { pvm.seekToQueueItem(i); showQueue = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun TempoLine(bpm: Float?, confidence: Float?, transition: dev.cued.app.playback.TransitionInfo?) {
    val text = when {
        transition != null -> {
            val t = transition.plan.tempo
            val pct = (transition.progress * 100).toInt()
            if (t.matched) "Blending $pct%  ·  tempo-matched ${t.ratioNum}:${t.ratioDen}  ·  ${transition.outgoingBpm?.toInt() ?: "?"} → ${transition.incomingBpm?.toInt() ?: "?"} BPM"
            else "Blending $pct%  ·  ${transition.plan.curve.name.lowercase().replace('_', ' ')} crossfade"
        }
        bpm != null && bpm > 0f -> "${"%.1f".format(bpm)} BPM" + (confidence?.let { if (it < 0.25f) "  ·  low confidence" else "" } ?: "")
        else -> "Tempo not analysed yet"
    }
    Box(Modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (transition != null) Amber else Muted, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
