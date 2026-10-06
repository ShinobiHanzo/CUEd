package dev.cued.app.ui.screens

import android.app.Activity
import android.content.Intent
import android.graphics.Color as AColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import dev.cued.app.data.db.TrackEntity
import dev.cued.app.share.QrCodes
import dev.cued.app.share.nfc.SharePayloadHolder
import dev.cued.app.share.nfc.TagEmulationSession
import dev.cued.app.station.Follow
import dev.cued.app.station.StationCache
import dev.cued.app.ui.LocalGraph
import dev.cued.app.ui.StationViewModel
import dev.cued.app.ui.components.AlbumArt
import dev.cued.app.ui.theme.Muted
import dev.cued.app.ui.theme.Teal
import dev.cued.core.station.Station
import dev.cued.core.station.StationState
import dev.cued.core.station.StationTrack

private fun statusLine(st: StationState?): String = when {
    st == null -> "No signal yet"
    st.status == Station.STATUS_ON_AIR && st.now != null -> "On air · ${st.now!!.artist} – ${st.now!!.title}"
    st.status == Station.STATUS_PAUSED && st.now != null -> "Paused · ${st.now!!.artist} – ${st.now!!.title}"
    st.status == Station.STATUS_OFF -> "Off air"
    else -> "On air, nothing playing"
}

/** Host side: name, the on-air switch, what is being announced, the follow link as QR/NFC, identity and relay settings. */
@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun StationScreen(vm: StationViewModel, onBack: () -> Unit) {
    val identity by vm.identity.collectAsState()
    val name by vm.name.collectAsState()
    val onAir by vm.onAir.collectAsState()
    val state by vm.hostState.collectAsState()
    val link by vm.stationLink.collectAsState()
    val relays by vm.relays.collectAsState()
    val relayStatus by vm.relayStatus.collectAsState()
    val prefetchMobile by vm.prefetchOnMobile.collectAsState()
    val cacheMb by vm.cacheMb.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycle = LocalLifecycleOwner.current
    val clipboard = LocalClipboardManager.current
    var nameDraft by remember(name) { mutableStateOf(name) }
    var relayDraft by remember(relays) { mutableStateOf(relays.joinToString("\n")) }
    var showSecret by remember { mutableStateOf(false) }
    var importDraft by remember { mutableStateOf("") }
    var importMsg by remember { mutableStateOf<String?>(null) }
    val qr = remember(link) { link?.let { QrCodes.encode(it, 720, AColor.BLACK, AColor.WHITE) } }
    val tag = remember(activity) { activity?.let { TagEmulationSession(it) } }

    // While this screen is in front the phone is an NFC tag holding the follow link, exactly like the Share screen.
    DisposableEffect(link) { SharePayloadHolder.set(link, null); onDispose { SharePayloadHolder.set(null, null) } }
    DisposableEffect(lifecycle, tag) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) { Lifecycle.Event.ON_RESUME -> tag?.start(); Lifecycle.Event.ON_PAUSE -> tag?.stop(); else -> {} }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); tag?.stop() }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Your station") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            OutlinedTextField(nameDraft, onValueChange = { nameDraft = it; vm.setName(it) }, label = { Text("Station name (what followers see)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (onAir) "On air" else "Off air", style = MaterialTheme.typography.titleMedium, color = if (onAir) Teal else MaterialTheme.colorScheme.onSurface)
                    Text("While on air, whatever you play is announced to your followers: title, artist, album, source link and the next three in your queue. No audio leaves this phone; each listener fetches their own copy.", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                Switch(checked = onAir, onCheckedChange = { if (it) vm.goOnAir() else vm.goOffAir() })
            }
            if (onAir) {
                Spacer(Modifier.height(12.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(statusLine(state), style = MaterialTheme.typography.titleSmall, color = Teal)
                        state?.next?.takeIf { it.isNotEmpty() }?.let { next ->
                            Spacer(Modifier.height(6.dp))
                            Text("Next: " + next.joinToString(" · ") { "${it.artist} – ${it.title}" }, style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(relays.joinToString("   ") { r -> (if (relayStatus[r] == true) "● " else "○ ") + r.removePrefix("wss://") }, style = MaterialTheme.typography.labelSmall, color = Muted)
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 20.dp))
            Text("Let people follow you", style = MaterialTheme.typography.titleMedium)
            if (identity == null) {
                Text("Your station needs a key pair: it is your identity, made on this phone and never sent anywhere. Followers only learn the public half.", style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 4.dp))
                Button(onClick = vm::createIdentity, modifier = Modifier.padding(top = 8.dp)) { Text("Create my key") }
            } else {
                qr?.let { Image(it.asImageBitmap(), contentDescription = "Follow QR code", modifier = Modifier.fillMaxWidth(0.7f).aspectRatio(1f).align(Alignment.CenterHorizontally).padding(top = 8.dp)) }
                Text("Scan this in CUEd (Following → Scan), or hold the phones back-to-back while this screen is open.", style = MaterialTheme.typography.bodySmall, color = Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
                    OutlinedButton(onClick = { link?.let { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, it), "Send station link")) } }) { Text("Send the link") }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 20.dp))
            Text("Identity", style = MaterialTheme.typography.titleMedium)
            identity?.let { id ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Text("Public key: ${id.npub}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { clipboard.setText(AnnotatedString(id.npub)) }) { Icon(Icons.Default.ContentCopy, contentDescription = "Copy") }
                }
                TextButton(onClick = { showSecret = !showSecret }) { Text(if (showSecret) "Hide secret key" else "Show secret key (to move to another phone)") }
                if (showSecret) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(id.nsec, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    IconButton(onClick = { clipboard.setText(AnnotatedString(id.nsec)) }) { Icon(Icons.Default.ContentCopy, contentDescription = "Copy secret") }
                }
                if (showSecret) Text("Anyone with this key can broadcast as you. Paste it into CUEd on your other phone, then clear your clipboard.", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            OutlinedTextField(importDraft, onValueChange = { importDraft = it }, label = { Text("Import a key (nsec… or hex)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { vm.importSecret(importDraft) { importMsg = it; if (it.startsWith("Key imported")) importDraft = "" } }, enabled = importDraft.isNotBlank()) { Text("Import") }
            }
            importMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Muted) }
            HorizontalDivider(Modifier.padding(vertical = 20.dp))
            Text("Relays", style = MaterialTheme.typography.titleMedium)
            Text("Dumb mailboxes that carry the signed states between phones; any public one works, or run your own (one wss:// URL per line). Nothing on them can be forged or read beyond what you publish.", style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 4.dp))
            OutlinedTextField(relayDraft, onValueChange = { relayDraft = it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), minLines = 3)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = { vm.setRelays(relayDraft) }) { Text("Save relays") } }
            HorizontalDivider(Modifier.padding(vertical = 20.dp))
            Text("Listening", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Fetch station tracks on mobile data")
                    Text("Off means tracks only download on Wi-Fi; the station will wait.", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                Switch(checked = prefetchMobile, onCheckedChange = vm::setPrefetchOnMobile)
            }
            Text("Station cache: $cacheMb MB (oldest tracks go first; \"Keep\" moves one into your library)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            Slider(value = cacheMb.toFloat(), onValueChange = { vm.setCacheMb(it.toInt()) }, valueRange = 50f..2000f, steps = 38)
            TextButton(onClick = vm::clearCache) { Text("Clear the station cache") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** The stations this phone follows, with their live status, and ways to add one. */
@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun FollowingScreen(vm: StationViewModel, onBack: () -> Unit, onOpen: (Follow) -> Unit, onScan: () -> Unit, onMessage: (String) -> Unit) {
    val follows by vm.follows.collectAsState()
    val live by vm.live.collectAsState()
    val tuned by vm.tuned.collectAsState()
    var draft by remember { mutableStateOf("") }
    DisposableEffect(Unit) { vm.watchFollowing(); onDispose { vm.unwatchFollowing() } }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Following") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                OutlinedTextField(draft, onValueChange = { draft = it }, label = { Text("Station link or npub") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.follow(draft) { onMessage(it); if (it.startsWith("Following")) draft = "" } }, enabled = draft.isNotBlank()) { Text("Follow") }
                    OutlinedButton(onClick = onScan) { Text("Scan a QR / tap a phone") }
                }
            }
            if (follows.isEmpty()) {
                Text("Nobody yet. Ask a friend to open Your station in CUEd and scan their code, tap phones, or paste the link they send you.", style = MaterialTheme.typography.bodyMedium, color = Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(32.dp))
            } else LazyColumn(Modifier.fillMaxSize()) {
                items(follows, key = { it.pubkey }) { f ->
                    val st = live[f.pubkey]
                    val on = st?.status == Station.STATUS_ON_AIR
                    ListItem(
                        headlineContent = { Text(f.label) },
                        supportingContent = { Text(statusLine(st), color = if (on) Teal else Muted) },
                        leadingContent = { Icon(Icons.Default.Radio, null, tint = if (on) Teal else Muted) },
                        trailingContent = {
                            if (tuned?.pubkey == f.pubkey) AssistChip(onClick = { onOpen(f) }, label = { Text("Listening") })
                            else IconButton(onClick = { vm.unfollow(f.pubkey) }) { Icon(Icons.Default.Delete, contentDescription = "Unfollow") }
                        },
                        modifier = Modifier.clickable { onOpen(f) },
                    )
                }
            }
        }
    }
}

private fun fetchText(f: StationCache.Fetch?): String = when (f) {
    null, StationCache.Fetch.Pending -> "Queued"
    is StationCache.Fetch.Fetching -> "Downloading ${(f.progress * 100).toInt()}%"
    is StationCache.Fetch.Ready -> if (f.fromLibrary) "In your library" else "Ready"
    is StationCache.Fetch.Failed -> "Failed: ${f.reason}"
}

/** One station: what it is playing, how each entry stands on this phone, tune in / leave / keep. */
@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun ListenScreen(vm: StationViewModel, pubkey: String, onBack: () -> Unit, onOpenPlayer: () -> Unit, onMessage: (String) -> Unit) {
    val follows by vm.follows.collectAsState()
    val live by vm.live.collectAsState()
    val tuned by vm.tuned.collectAsState()
    val listenState by vm.listenState.collectAsState()
    val fetch by vm.fetch.collectAsState()
    val playingId by vm.playingTrackId.collectAsState()
    val note by vm.note.collectAsState()
    val graph = LocalGraph.current
    val f = follows.firstOrNull { it.pubkey == pubkey }
    val isTuned = tuned?.pubkey == pubkey
    val st = if (isTuned) listenState else live[pubkey]
    DisposableEffect(Unit) { vm.watchFollowing(); onDispose { vm.unwatchFollowing() } }
    var playingTrack by remember(playingId) { mutableStateOf<TrackEntity?>(null) }
    LaunchedEffect(playingId) { playingTrack = playingId?.let { graph.library.track(it) } }

    Scaffold(topBar = {
        TopAppBar(title = { Text(f?.label ?: "Station") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (f == null) { Text("You are not following this station.", color = Muted); return@Column }
            Text(statusLine(st), style = MaterialTheme.typography.titleSmall, color = if (st?.status == Station.STATUS_ON_AIR) Teal else Muted, textAlign = TextAlign.Center)
            note?.let { n ->
                Card(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(n, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = vm::clearNote) { Text("OK") }
                    }
                }
            }
            val now = st?.now
            Spacer(Modifier.height(16.dp))
            if (now != null) {
                val nowFetch = fetch[graph.station.cache.keyOf(now)]
                if (playingTrack != null && isTuned) AlbumArt(playingTrack, Modifier.size(180.dp), corner = 16, px = dev.cued.app.ui.components.Artwork.LARGE)
                else if (now.cover != null) AsyncImage(model = now.cover, contentDescription = null, modifier = Modifier.size(180.dp))
                else Icon(Icons.Default.Radio, null, Modifier.size(96.dp), tint = Teal)
                Spacer(Modifier.height(12.dp))
                Text(now.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text(now.artist + (now.album.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodyMedium, color = Muted, textAlign = TextAlign.Center)
                if (isTuned) {
                    Text(fetchText(nowFetch), style = MaterialTheme.typography.bodySmall, color = if (nowFetch is StationCache.Fetch.Failed) MaterialTheme.colorScheme.error else Muted, modifier = Modifier.padding(top = 6.dp))
                    (nowFetch as? StationCache.Fetch.Fetching)?.let { LinearProgressIndicator(progress = { it.progress }, modifier = Modifier.fillMaxWidth(0.6f).padding(top = 4.dp)) }
                    if (nowFetch is StationCache.Fetch.Failed) TextButton(onClick = { vm.retryFetch(now) }) { Text("Try again") }
                }
            } else Text("Nothing announced yet.", color = Muted)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!isTuned) Button(onClick = { vm.tuneIn(f) }, enabled = st != null && st.status != Station.STATUS_OFF) { Text("Tune in") }
                else {
                    OutlinedButton(onClick = vm::leave) { Text("Leave") }
                    Button(onClick = onOpenPlayer) { Text("Open player") }
                }
            }
            if (isTuned) {
                val pid = playingId
                val canKeep = pid != null && now != null && (fetch[graph.station.cache.keyOf(now)] as? StationCache.Fetch.Ready)?.let { !it.fromLibrary && it.trackId == pid } == true
                if (canKeep) TextButton(onClick = { vm.keep(pid!!) { onMessage(it) } }, modifier = Modifier.padding(top = 4.dp)) { Text("Keep this track in my library") }
                Text("Tracks are fetched to a temporary cache as the host announces them; playing anything else leaves the station.", style = MaterialTheme.typography.bodySmall, color = Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            }
            st?.next?.takeIf { it.isNotEmpty() }?.let { next ->
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                Text("Up next", style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth())
                for (t in next) {
                    val fs = fetch[graph.station.cache.keyOf(t)]
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${t.artist} – ${t.title}", style = MaterialTheme.typography.bodyMedium)
                            if (isTuned) Text(fetchText(fs), style = MaterialTheme.typography.bodySmall, color = if (fs is StationCache.Fetch.Failed) MaterialTheme.colorScheme.error else Muted)
                        }
                        if (isTuned && fs is StationCache.Fetch.Failed) TextButton(onClick = { vm.retryFetch(t) }) { Text("Retry") }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
