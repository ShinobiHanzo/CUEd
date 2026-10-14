package dev.cued.app.ui.screens

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cued.app.party.PartyClient
import dev.cued.app.ui.PartyViewModel
import dev.cued.app.ui.components.EmptyHint
import dev.cued.app.ui.components.SectionHeader
import dev.cued.app.ui.components.formatMs
import dev.cued.app.ui.theme.Muted
import dev.cued.app.ui.theme.Teal

/**
 * Party: a shared queue for friends. Host it from this phone (Wi-Fi and
 * Bluetooth) or join a friend's (or their desktop's) and queue songs from
 * their library. Only friends get through, in both directions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PartyScreen(vm: PartyViewModel, onBack: () -> Unit, onOpenFriends: () -> Unit, onMessage: (String) -> Unit) {
    val host by vm.host.collectAsState()
    val log by vm.log.collectAsState()
    val controls by vm.controls.collectAsState()
    val friends by vm.friends.collectAsState()
    val discovering by vm.discovering.collectAsState()
    val hosts by vm.hosts.collectAsState()
    val session by vm.session.collectAsState()
    val now by vm.now.collectAsState()
    val results by vm.results.collectAsState()
    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()
    var query by remember { mutableStateOf("") }
    val acceptedFriends = friends.count { it.status == "friends" }

    LaunchedEffect(message) { message?.let { onMessage(it); vm.message.value = null } }
    LaunchedEffect(session) { if (session != null) while (true) { kotlinx.coroutines.delay(8_000); vm.refreshNow() } }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (!granted.values.all { it }) onMessage("Without the Nearby devices permission only Wi-Fi hosts can be found")
        vm.discover()
    }
    fun discoverWithPermissions() { if (vm.hasBlePermissions()) vm.discover() else permissions.launch(vm.blePermissions()) }
    val hostPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.setHosting(true) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(session?.let { "Party at ${it.hello.name.ifBlank { "a friend's" }}" } ?: "Party") },
            navigationIcon = { IconButton(onClick = { if (session != null) vm.leave() else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            actions = { if (session != null) IconButton(onClick = { vm.refreshNow() }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") } },
        )
    }) { pad ->
        val s = session
        if (s != null) {
            LazyColumn(Modifier.padding(pad).fillMaxSize()) {
                item {
                    SectionHeader("Playing now", "via ${s.transport.label}" + if (s.hello.controls) "" else " · queue only")
                    val cur = now?.current
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Text(cur?.title ?: "Nothing playing", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(cur?.artist?.takeIf { it.isNotBlank() }, now?.let { "${formatMs(it.positionMs)} in" }, now?.let { if (it.playing) "playing" else "paused" }, now?.let { "${it.queueLength} queued" }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                    if (s.hello.controls) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.Center) {
                        IconButton(onClick = { if (now?.playing == true) vm.pause() else vm.play() }, enabled = !busy) { Icon(if (now?.playing == true) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Play / pause") }
                        IconButton(onClick = { vm.skip() }, enabled = !busy) { Icon(Icons.Default.SkipNext, contentDescription = "Skip") }
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    SectionHeader("Add a song", "Search ${s.hello.name.ifBlank { "the host" }}'s library")
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Title, artist or album") })
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { vm.search(query) }, enabled = query.isNotBlank() && !busy) { Text("Search") }
                    }
                }
                if (results.isEmpty()) item { EmptyHint(if (query.isBlank()) "Type something to search" else "Nothing found yet") }
                items(results, key = { it.sha256.ifEmpty { it.id.toString() } + it.title }) { t ->
                    Row(Modifier.fillMaxWidth().clickable { vm.queue(t) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (t.durationMs > 0) Text(formatMs(t.durationMs), style = MaterialTheme.typography.labelMedium, color = Muted)
                        TextButton(onClick = { vm.queue(t) }, enabled = !busy) { Text("Queue") }
                    }
                }
                val q = now?.queue.orEmpty()
                if (q.isNotEmpty()) {
                    item { SectionHeader("Up next on the host") }
                    items(q, key = { "q" + it.id + it.sha256 + it.title }) { t -> Text("${t.artist} – ${t.title}", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            return@Scaffold
        }

        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                SectionHeader("Host a party on this phone", "Friends on your Wi-Fi or within Bluetooth range queue songs from your library")
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (host.hosting) "Hosting" else "Off", fontWeight = FontWeight.Medium)
                        Text(
                            if (!host.hosting) "Only people in your friends list can join; every request is signed with their key."
                            else listOfNotNull(host.lanUrl?.let { "Wi-Fi: $it" }, "Bluetooth: ${host.ble}", host.error).joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall, color = Muted,
                        )
                    }
                    Switch(checked = host.hosting, onCheckedChange = { on -> if (on && !vm.hasBlePermissions()) hostPermissions.launch(vm.blePermissions()) else vm.setHosting(on) })
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Guests may control playback", fontWeight = FontWeight.Medium)
                        Text("Skip, pause and resume as well as queue.", style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                    Switch(checked = controls, onCheckedChange = { vm.setControls(it) })
                }
                if (acceptedFriends == 0) Text("You have no accepted friends yet, so nobody could join. Add friends over QR or NFC first.", Modifier.padding(16.dp).clickable { onOpenFriends() }, style = MaterialTheme.typography.bodySmall, color = Teal)
            }
            if (host.hosting) {
                item { SectionHeader("Guest activity") }
                if (log.isEmpty()) item { EmptyHint("Nothing yet. Guests open CUEd → Party → Find a party.") }
                items(log, key = { it.at.toString() + it.who }) { e ->
                    Text("${e.who} ${e.what}", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            item {
                SectionHeader("Join a friend's party", "On their Wi-Fi, or next to them over Bluetooth. Their desktop can host too.")
                Row(Modifier.padding(horizontal = 16.dp)) {
                    OutlinedButton(onClick = { discoverWithPermissions() }, enabled = !discovering) { Text(if (discovering) "Looking…" else "Find a party") }
                }
                if (discovering) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
            }
            if (hosts.isEmpty() && !discovering) item { EmptyHint("No hosts found yet") }
            items(hosts, key = { it.key }) { h ->
                val friend = friends.firstOrNull { it.pubkey == h.beacon.pubkey }
                Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { vm.join(h) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (h.device != null) Icons.Default.Bluetooth else Icons.Default.Wifi, contentDescription = null, tint = Muted)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(h.beacon.name.ifBlank { if (h.device != null) "Nearby phone (Bluetooth)" else h.beacon.url ?: "Host" }, fontWeight = FontWeight.Medium)
                        Text(
                            when {
                                h.device != null -> "Tap to say hello; the host must be one of your friends"
                                friend?.status == "friends" -> "Your friend ${friend.label}"
                                friend != null -> "Friend request pending"
                                else -> "Not in your friends list"
                            },
                            style = MaterialTheme.typography.bodySmall, color = if (friend?.status == "friends" || h.device != null) Teal else Muted,
                        )
                    }
                    TextButton(onClick = { vm.join(h) }, enabled = !busy) { Text("Join") }
                }
            }
            item { Text("A party never leaves the room: Wi-Fi traffic stays on the LAN, Bluetooth stays within reach, and nothing is written to any server. Both sides check the other's signature against their own friends list.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = Muted) }
        }
    }
}

@Suppress("unused")
private fun granted(v: Int) = v == PackageManager.PERMISSION_GRANTED

@Suppress("unused")
private val unusedHostType: PartyClient.Host? = null
