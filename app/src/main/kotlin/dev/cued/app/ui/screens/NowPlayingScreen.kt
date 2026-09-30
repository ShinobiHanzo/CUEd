package dev.cued.app.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.cued.app.ui.LibraryViewModel
import dev.cued.app.ui.PlayerViewModel
import dev.cued.app.ui.components.AlbumArt
import dev.cued.app.ui.components.LyricsPanel
import dev.cued.app.ui.components.PositionScrubber
import dev.cued.app.ui.components.formatMs
import dev.cued.app.ui.theme.Amber
import dev.cued.app.ui.theme.Ink
import dev.cued.app.ui.theme.Muted
import dev.cued.app.ui.theme.Teal
import dev.cued.core.lyrics.Lrc
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Layout, top to bottom: header · crossfade status · artwork (gestures,
 * sing-along overlay) · scrubber · current lyric line · title/artist ·
 * transport. Podcasts and audiobooks swap the transport row for ±10/30 s
 * and speed. The scrubber look is chosen in Settings.
 */
@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
fun NowPlayingScreen(pvm: PlayerViewModel, lvm: LibraryViewModel, onClose: () -> Unit, onMore: (Long) -> Unit) {
    val state by pvm.state.collectAsState()
    val track by pvm.currentTrack.collectAsState()
    val transition by pvm.transition.collectAsState()
    val tempo by pvm.currentTempo.collectAsState()
    val ui by pvm.uiSettings.collectAsState()
    val lyrics by pvm.lyrics.collectAsState()
    val lyricsBusy by pvm.lyricsBusy.collectAsState()
    val singAlong by pvm.showLyrics.collectAsState()
    val lyricsSettings by pvm.lyricsSettings.collectAsState()
    var showQueue by remember { mutableStateOf(false) }
    val isLong = track?.isLong == true

    // Artwork gestures: horizontal swipe changes track, vertical swipe opens/closes sing-along,
    // tap play/pause, double-tap favourite, hold for the menu.
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val artOffset by animateFloatAsState(targetValue = dragX, label = "artSwipe")
    val artBlur by animateDpAsState(targetValue = if (singAlong) 14.dp else 0.dp, label = "artBlur")
    val artAlpha by animateFloatAsState(targetValue = if (singAlong) 0.35f else 1f, label = "artAlpha")

    val lyricText = lyrics?.synced ?: lyrics?.plain
    val lines = remember(lyricText) { lyricText?.let { Lrc.parse(it) } ?: emptyList() }
    val synced = lines.isNotEmpty() && lines.first().timeMs != null
    val currentLine = if (synced) Lrc.currentIndex(lines, state.positionMs) else -1

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Close") }
            Spacer(Modifier.weight(1f))
            Text(if (isLong) "Podcast / audiobook" else "Now playing", style = MaterialTheme.typography.labelLarge, color = Muted)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showQueue = true }) { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Queue") }
            IconButton(onClick = { state.trackId?.let(onMore) }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
        }

        // Crossfade / tempo status sits above the artwork.
        if (!isLong) TempoLine(track?.bpm ?: tempo?.bpm, track?.bpmConfidence, transition) else Spacer(Modifier.height(20.dp))
        Spacer(Modifier.height(6.dp))

        Box(
            Modifier.fillMaxWidth(0.82f).aspectRatio(1f)
                .offset { IntOffset(artOffset.roundToInt(), 0) }
                .clip(RoundedCornerShape(20.dp))
                .pointerInput(state.trackId) {
                    detectTapGestures(
                        onTap = { if (!singAlong) pvm.togglePlay() },
                        onDoubleTap = { if (!singAlong) track?.let { lvm.toggleFavourite(it) } },
                        onLongPress = { state.trackId?.let(onMore) },
                    )
                }
                .pointerInput(state.trackId) {
                    detectDragGestures(
                        onDragEnd = {
                            if (abs(dragY) > abs(dragX)) {
                                if (dragY < -threshold) pvm.showLyrics.value = true
                                else if (dragY > threshold) pvm.showLyrics.value = false
                            } else if (!singAlong) {
                                if (dragX < -threshold) pvm.next() else if (dragX > threshold) pvm.previous()
                            }
                            dragX = 0f; dragY = 0f
                        },
                        onDragCancel = { dragX = 0f; dragY = 0f },
                    ) { change, drag ->
                        if (!singAlong) dragX += drag.x
                        dragY += drag.y
                        change.consume()
                    }
                },
        ) {
            AlbumArt(track, Modifier.fillMaxSize().blur(artBlur).alpha(artAlpha), corner = 20)
            androidx.compose.animation.AnimatedVisibility(
                visible = singAlong,
                enter = slideInVertically { it / 2 } + fadeIn(),
                exit = slideOutVertically { it / 2 } + fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(Modifier.fillMaxSize().background(Ink.copy(alpha = 0.45f))) {
                    LyricsPanel(
                        lyrics = lyrics, positionMs = state.positionMs, busy = lyricsBusy, onlineAllowed = lyricsSettings.fetchOnline,
                        onSeek = { pvm.seekTo(it) }, onFetch = { pvm.fetchLyricsNow() }, modifier = Modifier.fillMaxSize(),
                    )
                    IconButton(onClick = { pvm.showLyrics.value = false }, modifier = Modifier.align(Alignment.TopCenter)) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Close lyrics", tint = Muted)
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        PositionScrubber(
            mode = ui.scrubberMode, positionMs = state.positionMs, durationMs = state.durationMs,
            bus = pvm.spectrumBus, visualDelayMs = ui.visualDelayMs, onSeek = { pvm.seekTo(it) }, height = 56,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatMs(state.positionMs), style = MaterialTheme.typography.labelSmall, color = Muted)
            Text(formatMs(state.durationMs), style = MaterialTheme.typography.labelSmall, color = Muted)
        }

        // One lyric line, live, between the scrubber and the title. Hidden while sing-along is open.
        Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center) {
            if (!singAlong) {
                val text = when {
                    synced && currentLine >= 0 -> lines[currentLine].text.ifBlank { "♪" }
                    synced -> "♪"
                    lines.isNotEmpty() -> "Lyrics available · swipe up"
                    else -> ""
                }
                Text(
                    text, color = if (synced && currentLine >= 0) Teal else Muted, textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { pvm.showLyrics.value = true },
                )
            }
        }

        Text(state.title.ifBlank { "Nothing playing" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(state.artist, style = MaterialTheme.typography.bodyMedium, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(14.dp))

        if (isLong) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                SpeedChip(state.speed) { pvm.setSpeed(it) }
                IconButton(onClick = { pvm.seekBy(-10_000) }) { Icon(Icons.Default.Replay10, contentDescription = "Back 10 s", modifier = Modifier.size(36.dp)) }
                FilledIconButton(onClick = { pvm.togglePlay() }, modifier = Modifier.size(72.dp)) {
                    Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Play/pause", modifier = Modifier.size(40.dp))
                }
                IconButton(onClick = { pvm.seekBy(30_000) }) { Icon(Icons.Default.Forward30, contentDescription = "Forward 30 s", modifier = Modifier.size(36.dp)) }
                IconButton(onClick = { pvm.next() }) { Icon(Icons.Default.SkipNext, contentDescription = "Next", modifier = Modifier.size(32.dp)) }
            }
        } else {
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
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = { track?.let { lvm.toggleFavourite(it) } }) {
                Icon(if (track?.favourite == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = "Favourite", tint = if (track?.favourite == true) Amber else Muted)
            }
            if (!isLong) IconButton(onClick = { pvm.blendNow() }, enabled = transition == null && state.queueIndex + 1 < state.queue.size) {
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
private fun SpeedChip(speed: Float, onChange: (Float) -> Unit) {
    val steps = listOf(0.8f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    val next = steps.firstOrNull { it > speed + 0.01f } ?: steps.first()
    AssistChip(onClick = { onChange(next) }, label = { Text("${"%.2g".format(speed).trimEnd('0').trimEnd('.')}×") })
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
