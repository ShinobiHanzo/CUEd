package dev.cued.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cued.app.data.db.LyricsEntity
import dev.cued.app.lyrics.LyricsRepository
import dev.cued.app.ui.theme.Muted
import dev.cued.app.ui.theme.Teal
import dev.cued.core.lyrics.Lrc

/**
 * Lyrics in place of the artwork. Synced text follows the song and seeks on
 * tap; plain text just scrolls.
 */
@Composable
fun LyricsPanel(
    lyrics: LyricsEntity?,
    positionMs: Long,
    busy: Boolean,
    onlineAllowed: Boolean,
    onSeek: (Long) -> Unit,
    onFetch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = lyrics?.synced ?: lyrics?.plain
    val lines = remember(text) { text?.let { Lrc.parse(it) } ?: emptyList() }
    val synced = lines.isNotEmpty() && lines.first().timeMs != null
    val current = if (synced) Lrc.currentIndex(lines, positionMs) else -1
    val listState = rememberLazyListState()

    LaunchedEffect(current) {
        if (current >= 0) listState.animateScrollToItem((current - 2).coerceAtLeast(0))
    }

    Column(modifier.fillMaxWidth()) {
        if (lines.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (busy) CircularProgressIndicator()
                    else {
                        Text(
                            when {
                                lyrics == null -> "No lyrics yet"
                                lyrics.source == LyricsRepository.SOURCE_NONE && !onlineAllowed -> "Nothing embedded or beside the file. Online lookup is off in Settings."
                                lyrics.source == LyricsRepository.SOURCE_NONE -> "Couldn't find lyrics for this track"
                                else -> "No lyrics"
                            }, color = Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp),
                        )
                        TextButton(onClick = onFetch) { Text("Look up now") }
                    }
                }
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                item { Spacer(Modifier.height(96.dp)) }
                itemsIndexed(lines) { i, line ->
                    val active = i == current
                    Text(
                        line.text.ifBlank { "♪" },
                        fontSize = if (synced) 22.sp else 18.sp,
                        lineHeight = if (synced) 30.sp else 26.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        color = when { active -> Teal; synced && current >= 0 && i < current -> Muted.copy(alpha = 0.6f); else -> MaterialTheme.colorScheme.onSurface },
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                            .then(if (line.timeMs != null) Modifier.clickable { onSeek(line.timeMs) } else Modifier),
                    )
                }
                item { Spacer(Modifier.height(160.dp)) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (lyrics?.source) {
                        LyricsRepository.SOURCE_EMBEDDED -> "from the file's tag"
                        LyricsRepository.SOURCE_SIDECAR -> "from a .lrc beside the file"
                        LyricsRepository.SOURCE_LRCLIB -> "from lrclib.net"
                        else -> ""
                    } + if (synced) "  ·  synced, tap a line to jump" else "",
                    style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onFetch, enabled = !busy) { Text("Refresh") }
            }
        }
    }
}
