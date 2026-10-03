package dev.cued.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cued.app.ui.PlayerUiState
import dev.cued.app.ui.theme.Ink2
import dev.cued.app.ui.theme.Muted
import dev.cued.app.ui.theme.Teal

@Composable
fun MiniPlayer(state: PlayerUiState, track: dev.cued.app.data.db.TrackEntity?, onOpen: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit = {}) {
    if (state.trackId == null) return
    // Swipe left/right on the strip to skip; tap opens the player.
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
    var dragX by remember { mutableFloatStateOf(0f) }
    Column(
        Modifier.fillMaxWidth().background(Ink2).clickable(onClick = onOpen)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { if (dragX < -threshold) onNext() else if (dragX > threshold) onPrevious(); dragX = 0f },
                    onDragCancel = { dragX = 0f },
                ) { change, dx -> dragX += dx; change.consume() }
            },
    ) {
        LinearProgressIndicator(
            progress = { if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f },
            modifier = Modifier.fillMaxWidth().height(2.dp), color = Teal, trackColor = Ink2,
        )
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AlbumArt(track, Modifier.size(40.dp), px = dev.cued.app.ui.components.Artwork.SMALL)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(state.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            IconButton(onClick = onToggle) { Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Play/pause") }
            IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, contentDescription = "Next") }
        }
    }
}
