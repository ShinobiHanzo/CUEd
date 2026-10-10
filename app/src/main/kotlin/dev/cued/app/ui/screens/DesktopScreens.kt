package dev.cued.app.ui.screens

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.cued.app.data.Desktop
import dev.cued.app.data.db.TrackEntity
import dev.cued.app.ui.DesktopViewModel
import dev.cued.app.ui.components.SectionHeader
import dev.cued.app.ui.theme.Muted
import dev.cued.app.ui.theme.Teal
import dev.cued.core.desktop.PairLink

/** Paired desktops: pair by code, back up, browse, unlink. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopsScreen(vm: DesktopViewModel, onBack: () -> Unit, onScan: () -> Unit, onOpenLibrary: (Desktop) -> Unit, onMessage: (String) -> Unit) {
    val desktops by vm.desktops.collectAsState()
    val progress by vm.progress.collectAsState()
    val pending by vm.pendingPair.collectAsState()
    val message by vm.message.collectAsState()
    val phoneName by vm.phoneName.collectAsState()
    val shared by vm.sharedJson.collectAsState()
    val activity = LocalContext.current as? Activity
    var pasted by remember { mutableStateOf("") }
    var name by remember(phoneName) { mutableStateOf(phoneName) }
    LaunchedEffect(message) { message?.let { onMessage(it); vm.clearMessage() } }

    pending?.let { link -> PairDialog(link, vm.biometricSupported, onDismiss = { vm.pendingPair.value = null }, onPair = { account -> vm.pair(link, account, activity) }) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Desktop") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionHeader("Link a desktop", "On the computer: CUEd desktop → Link a phone → Show a pairing code. Scan it here, or paste the cued://pair link. Pairing works only on the desktop's own Wi-Fi.")
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onScan) { Text("Scan the code") }
                Spacer(Modifier.padding(6.dp))
                Text(if (vm.biometricSupported) "Reads from outside the house will ask for your fingerprint or face." else "This phone cannot sign biometric assertions, so the desktop will be reachable on its Wi-Fi only.", style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(value = pasted, onValueChange = { pasted = it }, singleLine = true, placeholder = { Text("cued://pair?…") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            TextButton(onClick = { if (!vm.offerPair(pasted.trim())) onMessage("Not a pairing code"); pasted = "" }, enabled = pasted.isNotBlank(), modifier = Modifier.padding(horizontal = 8.dp)) { Text("Use this code") }
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("This phone's name on the desktop") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            if (name != phoneName) TextButton(onClick = { vm.setPhoneName(name) }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Save name") }

            SectionHeader("Linked desktops", if (desktops.isEmpty()) "None yet" else "Backup copies every track once, by content; streaming plays the desktop's library here")
            desktops.forEach { d ->
                val p = progress?.takeIf { it.desktopId == d.id }
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(d.label, style = MaterialTheme.typography.titleMedium)
                    Text(listOfNotNull(d.addresses.firstOrNull(), d.funnel?.let { "funnel $it" }, "account: ${d.account}").joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Muted)
                    Text(if (p != null) "${p.phase}${if (p.total > 0) " ${p.done}/${p.total}" else ""}${if (p.note.isNotBlank()) " · ${p.note}" else ""}" else if (d.lastSyncAt > 0) "Last backup: ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(d.lastSyncAt)} · ${d.lastSyncNote}" else d.lastSyncNote.ifBlank { "Not backed up yet" }, style = MaterialTheme.typography.bodySmall, color = if (p != null) Teal else Muted)
                    if (p != null && p.total > 0) LinearProgressIndicator(progress = { p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    Row {
                        TextButton(onClick = { vm.sync(d, activity) }, enabled = p == null) { Text("Back up now") }
                        TextButton(onClick = { onOpenLibrary(d) }) { Text("Browse its library") }
                        TextButton(onClick = { vm.unpair(d) }) { Text("Unlink") }
                    }
                }
            }
            if (desktops.isNotEmpty()) {
                SectionHeader("Shared settings", "Kept in the account chain, the same on every device of the account. Changed on the desktop's Wi-Fi.")
                val relayOn = shared?.contains("\"relayEnabled\":true") == true
                val streamingOn = shared?.contains("\"friendStreaming\":true") != false
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Desktop relay"); Text("Off by default. On: this phone's Stations traffic goes through the desktop's relay.", style = MaterialTheme.typography.bodySmall, color = Muted) }
                    Switch(checked = relayOn, onCheckedChange = { on -> desktops.firstOrNull()?.let { vm.setShared(it, """{"relayEnabled":$on}""") } })
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Let friends stream what I play"); Text("The desktop adds audio, cover and lyrics links to your station's current and next three tracks, for friends only.", style = MaterialTheme.typography.bodySmall, color = Muted) }
                    Switch(checked = streamingOn, onCheckedChange = { on -> desktops.firstOrNull()?.let { vm.setShared(it, """{"friendStreaming":$on}""") } })
                }
            }
            Spacer(Modifier.height(96.dp))
        }
    }
}

/** The account choice a pairing code asks for (protocol §8). */
@Composable
private fun PairDialog(link: PairLink, biometric: Boolean, onDismiss: () -> Unit, onPair: (String) -> Unit) {
    var account by remember { mutableStateOf("join") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link with ${link.name.ifBlank { "this desktop" }}") },
        text = {
            Column {
                Text("One account can live on every device you own. Which way round?", style = MaterialTheme.typography.bodyMedium)
                listOf(
                    "join" to "Use the desktop's account on this phone (the desktop's key moves here, sealed with this code)",
                    "keep" to "Keep this phone's account and put it on the desktop too",
                    "separate" to "Separate accounts, just paired devices",
                ).forEach { (k, label) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = account == k, onClick = { account = k })
                        Text(label, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(if (biometric) "A biometric key is registered so reads from outside the house need your fingerprint or face." else "No biometric key on this phone: the desktop is reachable on its Wi-Fi only.", style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { Button(onClick = { onPair(account) }) { Text("Pair") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Browse and play the desktop's library; each play streams the file into the 48-hour cache. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopLibraryScreen(vm: DesktopViewModel, desktopId: String, onBack: () -> Unit, onPlay: (TrackEntity) -> Unit) {
    val desktops by vm.desktops.collectAsState()
    val d = desktops.firstOrNull { it.id == desktopId }
    val tracks by vm.remoteTracks.collectAsState()
    val busy by vm.remoteBusy.collectAsState()
    val activity = LocalContext.current as? Activity
    var query by remember { mutableStateOf("") }
    LaunchedEffect(d?.id, query) { d?.let { vm.loadLibrary(it, query, activity) } }

    Scaffold(topBar = {
        TopAppBar(title = { Text(d?.label ?: "Desktop") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, placeholder = { Text("Search the desktop") }, modifier = Modifier.fillMaxWidth().padding(16.dp))
            busy?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp)) }
            if (d == null) Text("This desktop is no longer linked.", color = Muted, modifier = Modifier.padding(16.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(tracks, key = { it.sha256 }) { t ->
                    ListItem(
                        headlineContent = { Text(t.title) },
                        supportingContent = { Text(listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" · ") + (t.bpm?.let { " · ${it.toInt()} BPM" } ?: ""), color = Muted) },
                        trailingContent = { OutlinedButton(onClick = { d?.let { vm.playRemote(it, t, activity, onPlay) } }) { Text("Play") } },
                    )
                }
                item { Spacer(Modifier.height(120.dp)) }
            }
        }
    }
}
