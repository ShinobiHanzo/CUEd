package dev.cued.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cued.app.data.db.LyricsEntity
import dev.cued.app.data.db.TrackEntity
import dev.cued.app.playback.SpectrumBus
import dev.cued.app.ui.PlayerUiState
import dev.cued.app.ui.theme.Ink2
import dev.cued.app.ui.theme.Mist
import dev.cued.app.ui.theme.Muted
import dev.cued.app.ui.theme.Teal
import dev.cued.app.ui.theme.spectrumColor
import dev.cued.core.lyrics.Lrc

/**
 * The floating mini player: a 150×150 dp card in the bottom-right corner with
 * the cover as background and the live spectrograph drawn over it at 30 %
 * opacity, pinned to the bottom edge. The italic *L* next to the close
 * button grows it to 150×400 dp: the cover blurs into the background and
 * the lyrics scroll in sync, the current line centred. Tap the cover to open
 * the full player; × hides the card until the next track.
 */
@Composable
fun FloatingMiniPlayer(
    state: PlayerUiState,
    track: TrackEntity?,
    bus: SpectrumBus,
    visualDelayMs: Int,
    lyrics: LyricsEntity?,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.trackId == null) return
    var expanded by remember { mutableStateOf(false) }
    val height = if (expanded) 400.dp else 150.dp
    Box(
        modifier.width(150.dp).height(height).animateContentSize().clip(RoundedCornerShape(14.dp)).background(Ink2),
    ) {
        // Cover: crisp behind the compact card, blurred behind the lyrics.
        Box(Modifier.fillMaxSize().then(if (expanded) Modifier.blur(18.dp) else Modifier)) {
            AlbumArt(track, Modifier.fillMaxSize(), corner = 0, px = Artwork.MEDIUM)
        }
        if (expanded) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
        // The spectrograph, 30 % over the cover, pinned to the bottom.
        Spectrograph(bus, visualDelayMs, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(if (expanded) 48.dp else 60.dp).alpha(0.3f))
        // Top-right: italic L, then close.
        Row(Modifier.align(Alignment.TopEnd).padding(2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(28.dp)) {
                Text("L", fontStyle = FontStyle.Italic, fontWeight = FontWeight.Bold, color = if (expanded) Teal else Mist, style = MaterialTheme.typography.titleMedium)
            }
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, contentDescription = "Hide", tint = Mist) }
        }
        if (expanded) LyricsSync(lyrics, state.positionMs, Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 8.dp, vertical = 36.dp).height(260.dp))
        // Bottom: title, artist, controls, over the spectrograph.
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge, color = Mist)
            Text(state.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = Muted)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggle, modifier = Modifier.size(28.dp)) { Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Play/pause", tint = Mist) }
                IconButton(onClick = onNext, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = Mist) }
                Spacer(Modifier.weight(1f))
                Text(formatShort(state.positionMs), style = MaterialTheme.typography.labelSmall, color = Muted)
            }
        }
    }
}

/** Live bars from the spectrum bus, redrawn every frame. */
@Composable
private fun Spectrograph(bus: SpectrumBus, visualDelayMs: Int, modifier: Modifier) {
    val frame = remember(bus) { FloatArray(bus.bands) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(bus) { while (true) { withFrameMillis { }; if (bus.read(visualDelayMs.toLong(), frame)) tick++ } }
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") tick
        val n = frame.size
        if (n == 0) return@Canvas
        val w = size.width / n
        for (i in 0 until n) {
            val v = frame[i].coerceIn(0f, 1f)
            val h = size.height * v
            drawRect(spectrumColor(v), topLeft = Offset(i * w, size.height - h), size = Size((w - 1f).coerceAtLeast(1f), h))
        }
    }
}

/** Synced lyrics centred on the current line; plain lyrics scroll; none says so. */
@Composable
private fun LyricsSync(lyrics: LyricsEntity?, positionMs: Long, modifier: Modifier) {
    val text = lyrics?.synced ?: lyrics?.plain
    val lines = remember(text) { text?.let { Lrc.parse(it) } ?: emptyList() }
    val synced = lines.any { it.timeMs != null }
    if (lines.isEmpty()) { Box(modifier, contentAlignment = Alignment.Center) { Text("No lyrics", color = Muted, style = MaterialTheme.typography.bodySmall) }; return }
    if (!synced) {
        Column(modifier.verticalScroll(rememberScrollState())) { lines.forEach { Text(it.text, color = Mist, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) } }
        return
    }
    val current = lines.indexOfLast { (it.timeMs ?: Long.MAX_VALUE) <= positionMs }.coerceAtLeast(0)
    Column(modifier, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        for (i in (current - 2)..(current + 2)) {
            val l = lines.getOrNull(i) ?: continue
            Text(
                l.text, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (i == current) Teal else Mist.copy(alpha = 0.6f),
                style = if (i == current) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            )
        }
    }
}

private fun formatShort(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }
