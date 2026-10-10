package dev.cued.app.ui.screens

import android.app.Activity
import android.content.Intent
import android.graphics.Color as AColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.cued.app.share.QrCodes
import dev.cued.app.share.nfc.SharePayloadHolder
import dev.cued.app.share.nfc.TagEmulationSession
import dev.cued.app.ui.DesktopViewModel
import dev.cued.app.ui.components.SectionHeader
import dev.cued.app.ui.theme.Muted
import dev.cued.app.ui.theme.Teal

/**
 * Friends (CUEd-desktop `docs/protocol.md` §9): my code as a QR and over NFC
 * (the phone becomes a tag carrying the `cued://friend` link exactly like a
 * share), requests to accept, the list, and listening to a friend's station.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(vm: DesktopViewModel, onBack: () -> Unit, onScan: () -> Unit, onListen: (String) -> Unit, onMessage: (String) -> Unit) {
    val friends by vm.friends.collectAsState()
    val link by vm.myFriendLink.collectAsState()
    val message by vm.message.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycle = LocalLifecycleOwner.current
    var pasted by remember { mutableStateOf("") }
    val tag = remember(activity) { activity?.let { TagEmulationSession(it) } }
    LaunchedEffect(Unit) { vm.loadFriendLink() }
    LaunchedEffect(message) { message?.let { onMessage(it); vm.clearMessage() } }
    val qr = remember(link) { link?.let { QrCodes.encode(it, 640, AColor.BLACK, AColor.WHITE) } }

    // Offer the friend code over NFC only while this screen is in front, like the Share screen does.
    DisposableEffect(lifecycle, tag, link) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { link?.let { SharePayloadHolder.set(cuedUrl = it, webUrl = null) }; tag?.start() }
                Lifecycle.Event.ON_PAUSE -> { tag?.stop(); SharePayloadHolder.set(null, null) }
                else -> {}
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        link?.let { SharePayloadHolder.set(cuedUrl = it, webUrl = null) }
        onDispose { lifecycle.lifecycle.removeObserver(observer); tag?.stop(); SharePayloadHolder.set(null, null) }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Friends") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            SectionHeader("Your friend code", "A friend sees your public key, your name and what your station plays, nothing else. Scan each other's code and you are friends at once; scanning one way sends a request.")
            qr?.let { Image(it.asImageBitmap(), contentDescription = "Friend code", modifier = Modifier.fillMaxWidth(0.7f).aspectRatio(1f)) }
            Text("NFC: hold the phones back-to-back while this screen is open; the other phone reads your code like a tag.", style = MaterialTheme.typography.bodySmall, color = Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
            Row {
                OutlinedButton(onClick = onScan) { Text("Scan a friend's code") }
                Spacer(Modifier.padding(4.dp))
                link?.let { l -> TextButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, l), "Send friend code")) }) { Text("Send as a message") } }
            }
            OutlinedTextField(value = pasted, onValueChange = { pasted = it }, singleLine = true, placeholder = { Text("cued://friend?… or npub1…") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            Button(onClick = { vm.addFriend(pasted.trim()); pasted = "" }, enabled = pasted.isNotBlank()) { Text("Send request") }

            val requests = friends.filter { it.status == "pending_in" }
            SectionHeader("Requests", if (requests.isEmpty()) "No pending requests" else "${requests.size} waiting")
            requests.forEach { f ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(f.label); Text(f.pubkey.take(16) + "…", style = MaterialTheme.typography.bodySmall, color = Muted) }
                    TextButton(onClick = { vm.acceptFriend(f) }) { Text("Accept", color = Teal) }
                    TextButton(onClick = { vm.removeFriend(f) }) { Text("Decline") }
                }
            }
            val rest = friends.filter { it.status != "pending_in" }
            SectionHeader("Friends", if (rest.isEmpty()) "No friends yet. Friends are followed as stations automatically." else "${rest.count { it.status == "friends" }} friends · ${rest.count { it.status == "pending_out" }} requests sent")
            rest.forEach { f ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.label)
                        Text(if (f.status == "friends") "friends" else "request sent", style = MaterialTheme.typography.bodySmall, color = if (f.status == "friends") Teal else Muted)
                    }
                    if (f.status == "friends") TextButton(onClick = { onListen(f.pubkey) }) { Text("Listen") }
                    TextButton(onClick = { vm.removeFriend(f) }) { Text("Remove") }
                }
            }
            Spacer(Modifier.height(96.dp))
        }
    }
}
