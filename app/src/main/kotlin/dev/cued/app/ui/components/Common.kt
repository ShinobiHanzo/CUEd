@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.cued.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.cued.app.data.LibraryRepository
import dev.cued.app.data.db.TrackEntity
import dev.cued.app.ui.theme.Amber
import dev.cued.app.ui.theme.Ink3
import dev.cued.app.ui.theme.Muted
import dev.cued.app.ui.theme.Teal

fun formatMs(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val m = total / 60; val s = total % 60
    return "%d:%02d".format(m, s)
}

@Composable
fun AlbumArt(albumId: Long?, modifier: Modifier = Modifier, corner: Int = 8, trackUri: String? = null, px: Int = Artwork.MEDIUM) {
    val context = LocalContext.current
    var storeFailed by remember(albumId) { mutableStateOf(albumId == null) }
    val embedded by produceState<android.graphics.Bitmap?>(initialValue = null, key1 = trackUri, key2 = storeFailed) {
        value = if (storeFailed && trackUri != null) Artwork.embedded(context, trackUri, px) else null
    }
    Box(modifier.clip(RoundedCornerShape(corner.dp)).background(Ink3), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.MusicNote, contentDescription = null, tint = Muted)
        val uri = LibraryRepository.albumArtUri(albumId)
        if (uri != null && !storeFailed) {
            AsyncImage(model = uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize(), onError = { storeFailed = true })
        }
        embedded?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize()) }
    }
}

/** Convenience overload: album art from MediaStore with an embedded-picture fallback. */
@Composable
fun AlbumArt(track: dev.cued.app.data.db.TrackEntity?, modifier: Modifier = Modifier, corner: Int = 8, px: Int = Artwork.MEDIUM) =
    AlbumArt(track?.albumId, modifier, corner, track?.uri, px)

@Composable
fun TrackRow(
    track: TrackEntity,
    genres: List<String> = emptyList(),
    playing: Boolean = false,
    subtitle: String? = null,
    onClick: () -> Unit,
    onMore: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onMore).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(track, Modifier.size(48.dp), px = Artwork.SMALL)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (playing) Teal else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal,
            )
            val line = buildString {
                append(track.artist)
                track.bpm?.let { append("  ·  ${it.toInt()} BPM") }
                if (genres.isNotEmpty()) append("  ·  ${genres.take(2).joinToString(", ")}")
                subtitle?.let { append("  ·  $it") }
            }
            Text(line, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        if (track.favourite) Icon(Icons.Default.Favorite, contentDescription = null, tint = Amber, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(formatMs(track.durationMs), style = MaterialTheme.typography.bodySmall, color = Muted)
        if (onMore != null) IconButton(onClick = onMore) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
    }
}

@Composable
fun GenreChips(genres: List<String>, selected: String? = null, onClick: (String) -> Unit) {
    LazyRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        items(genres) { g ->
            AssistChip(
                onClick = { onClick(g) }, label = { Text(g) },
                modifier = Modifier.padding(horizontal = 4.dp),
                colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
                    containerColor = if (g == selected) Teal.copy(alpha = 0.25f) else Ink3,
                ),
            )
        }
    }
}

@Composable
fun SectionHeader(title: String, subtitle: String? = null, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        action?.invoke()
    }
}

@Composable
fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Muted, style = MaterialTheme.typography.bodyMedium)
    }
}
