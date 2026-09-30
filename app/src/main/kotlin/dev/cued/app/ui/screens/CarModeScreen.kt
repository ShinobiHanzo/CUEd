package dev.cued.app.ui.screens

import android.app.Activity
import android.view.WindowManager
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import dev.cued.app.data.SmartList
import dev.cued.app.ui.LibraryViewModel
import dev.cued.app.ui.PlayerViewModel
import dev.cued.app.ui.components.AlbumArt
import dev.cued.app.ui.theme.Amber
import dev.cued.app.ui.theme.Ink
import dev.cued.app.ui.theme.Ink3
import dev.cued.app.ui.theme.Muted
import dev.cued.app.ui.theme.Teal

/**
 * Big targets, high contrast, nothing fiddly: what you can hit at a glance
 * with the phone in a dash mount. Screen stays on while this is up.
 */
@OptIn(UnstableApi::class)
@Composable
fun CarModeScreen(pvm: PlayerViewModel, lvm: LibraryViewModel, onVoice: () -> Unit, onExit: () -> Unit) {
    val state by pvm.state.collectAsState()
    val track by pvm.currentTrack.collectAsState()
    val smart by lvm.smartLists.collectAsState()
    val playlists by lvm.playlists.collectAsState()
    val context = LocalContext.current

    DisposableEffect(Unit) {
        val w = (context as? Activity)?.window
        w?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    Column(Modifier.fillMaxSize().background(Ink).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("CAR MODE", color = Muted, style = MaterialTheme.typography.labelLarge, letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onExit, modifier = Modifier.size(56.dp)) { Icon(Icons.Default.Close, contentDescription = "Exit car mode", modifier = Modifier.size(32.dp)) }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AlbumArt(track, Modifier.size(96.dp), corner = 16)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(state.title.ifBlank { "Nothing playing" }, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 32.sp)
                Text(state.artist, fontSize = 20.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(onClick = { pvm.previous() }, modifier = Modifier.size(96.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Ink3)) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(56.dp))
            }
            FilledIconButton(onClick = { pvm.togglePlay() }, modifier = Modifier.size(140.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Teal, contentColor = Ink)) {
                Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Play/pause", modifier = Modifier.size(84.dp))
            }
            FilledIconButton(onClick = { pvm.next() }, modifier = Modifier.size(96.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Ink3)) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next", modifier = Modifier.size(56.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
        FilledIconButton(onClick = onVoice, modifier = Modifier.fillMaxWidth().height(80.dp), shape = RoundedCornerShape(24.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Amber, contentColor = Ink)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(40.dp))
                Spacer(Modifier.width(12.dp))
                Text("Voice", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("Quick play", color = Muted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
        Spacer(Modifier.height(8.dp))
        val tiles = buildList<Pair<String, () -> Unit>> {
            for (k in listOf(SmartList.FAVOURITES, SmartList.TRENDING, SmartList.RECOMMENDED, SmartList.NEW)) {
                val t = smart[k].orEmpty(); if (t.isNotEmpty()) add(k.title to { pvm.play(t) })
            }
            for (p in playlists.take(8)) add(p.name to { lvm.viewModelScopePlay(p.id, pvm) })
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(tiles) { (label, action) ->
                Box(
                    Modifier.size(width = 168.dp, height = 96.dp).clip(RoundedCornerShape(20.dp)).background(Ink3).clickable(onClick = action).padding(16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) { Text(label, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 26.sp) }
            }
        }
    }
}
