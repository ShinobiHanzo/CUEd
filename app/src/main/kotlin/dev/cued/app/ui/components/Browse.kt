package dev.cued.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cued.app.data.db.TrackEntity
import dev.cued.app.ui.theme.Ink3
import dev.cued.app.ui.theme.Muted
import dev.cued.app.ui.theme.Teal
import dev.cued.core.library.Discography

/** A–Z rail on the right edge of a long list: tap or drag to jump. */
@Composable
fun AlphabetRail(letters: List<Char>, onJump: (Char) -> Unit, modifier: Modifier = Modifier) {
    if (letters.isEmpty()) return
    var heightPx by remember { mutableIntStateOf(1) }
    var active by remember { mutableIntStateOf(-1) }
    fun pick(y: Float) { val i = ((y / heightPx) * letters.size).toInt().coerceIn(0, letters.lastIndex); if (i != active) { active = i; onJump(letters[i]) } }
    Column(
        modifier.fillMaxHeight().width(28.dp).onSizeChanged { heightPx = it.height.coerceAtLeast(1) }
            .pointerInput(letters) { detectDragGestures(onDragStart = { pick(it.y) }, onDragEnd = { active = -1 }, onDragCancel = { active = -1 }) { change, _ -> pick(change.position.y) } },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        letters.forEachIndexed { i, c ->
            Box(Modifier.weight(1f).fillMaxWidth().clickable { onJump(c) }, contentAlignment = Alignment.Center) {
                Text(c.toString(), style = MaterialTheme.typography.labelSmall, color = if (i == active) Teal else Muted)
            }
        }
    }
}

/** Square cover with name and a line underneath; used for albums, singles and the Albums tab. */
@Composable
fun AlbumCard(album: Discography.Album<TrackEntity>, modifier: Modifier = Modifier, showArtist: Boolean = false, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(6.dp)) {
        AlbumArt(album.tracks.firstOrNull(), Modifier.fillMaxWidth().aspectRatio(1f), corner = 10)
        Spacer(Modifier.height(6.dp))
        Text(album.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val line = listOfNotNull(if (showArtist) album.artist else null, Discography.yearLabel(album.year), "${album.tracks.size} track" + if (album.tracks.size == 1) "" else "s").joinToString(" · ")
        Text(line, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** One row of an artist list: round cover, name, counts. */
@Composable
fun ArtistRow(artist: Discography.Artist<TrackEntity>, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        AlbumArt(artist.coverTrack, Modifier.size(48.dp), corner = 24)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(artist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val parts = ArrayList<String>()
            if (artist.releaseCount > 0) parts += "${artist.releaseCount} release" + if (artist.releaseCount == 1) "" else "s"
            if (artist.trackCount > 0) parts += "${artist.trackCount} track" + if (artist.trackCount == 1) "" else "s"
            if (artist.appearsOn.isNotEmpty()) parts += "appears on ${artist.appearsOn.size}"
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}

/** Numbered row for album pages: track number instead of cover. */
@Composable
fun NumberedTrackRow(track: TrackEntity, number: Int, playing: Boolean, onClick: () -> Unit, onMore: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(if (playing) Teal.copy(alpha = 0.2f) else Ink3), contentAlignment = Alignment.Center) {
            Text(if (number > 0) number.toString() else "·", style = MaterialTheme.typography.labelMedium, color = if (playing) Teal else Muted)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (playing) Teal else MaterialTheme.colorScheme.onSurface)
            Text(track.artist, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(formatMs(track.durationMs), style = MaterialTheme.typography.bodySmall, color = Muted)
        androidx.compose.material3.IconButton(onClick = onMore) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.MoreVert, contentDescription = "More") }
    }
}
