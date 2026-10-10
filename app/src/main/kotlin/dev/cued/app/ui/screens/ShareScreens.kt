package dev.cued.app.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.cued.app.share.QrCodes
import dev.cued.app.share.nfc.NfcReader
import dev.cued.app.share.nfc.TagEmulationSession
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.cued.app.ui.ShareViewModel
import dev.cued.app.ui.theme.Muted
import dev.cued.app.ui.theme.Teal
import dev.cued.core.share.SharePayload
import java.util.concurrent.Executors

/** Sender side: QR on screen, NFC card emulation active, local server running. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(vm: ShareViewModel, trackId: Long, trackTitle: String, onBack: () -> Unit) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycle = LocalLifecycleOwner.current
    var nfcStatus by remember { mutableStateOf<String?>(null) }
    var nfcProblem by remember { mutableStateOf<String?>(null) }
    val nfc = remember(activity) { activity?.let { NfcReader(it) } }
    val tag = remember(activity) { activity?.let { TagEmulationSession(it) } }

    LaunchedEffect(trackId) { vm.startSharing(trackId) }
    DisposableEffect(Unit) { onDispose { vm.stopSharing(); nfc?.stopReading() } }
    // Hold the NFC slot only while this screen is actually in front.
    DisposableEffect(lifecycle, tag) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { tag?.start(); nfcProblem = tag?.problem }
                Lifecycle.Event.ON_PAUSE -> tag?.stop()
                else -> {}
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); tag?.stop() }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Share") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(trackTitle, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            state.qr?.let { Image(it.asImageBitmap(), contentDescription = "QR code", modifier = Modifier.fillMaxWidth(0.8f).aspectRatio(1f)) }
            Spacer(Modifier.height(12.dp))
            Text(
                if (state.serverUrl != null) "Local server: ${state.serverUrl}\nAnother phone on this Wi-Fi/hotspot scans the QR (or taps NFC) to grab the file and the app." else (state.error ?: "Starting…"),
                style = MaterialTheme.typography.bodySmall, color = Muted, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Include the audio file (local transfer)", Modifier.weight(1f)); Switch(checked = state.includeFile, onCheckedChange = vm::setIncludeFile, enabled = state.serverUrl != null)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Include a link to install CUEd", Modifier.weight(1f)); Switch(checked = state.includeApk, onCheckedChange = vm::setIncludeApk, enabled = state.serverUrl != null)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Send a friend request with it")
                    Text("Adds your public key and name. They see what your station plays; your private key stays here.", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                Switch(checked = state.includeFriend, onCheckedChange = vm::setIncludeFriend)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                when {
                    nfc == null || !nfc.available -> "This phone has no NFC."
                    !nfc.enabled -> "NFC is off. Turn it on in system settings for tap-to-share."
                    else -> "NFC ready: hold the phones back-to-back. The other phone needs CUEd installed and its screen on; it reads this one like a tag and opens the share straight in CUEd. No CUEd on that phone yet? Let it scan the QR code instead: that opens the download page."
                }, style = MaterialTheme.typography.bodySmall, color = if (nfc?.enabled == true) Teal else Muted, textAlign = TextAlign.Center,
            )
            nfcProblem?.let { Text("NFC note: $it. If the phone asks which NFC service to use, pick CUEd.", style = MaterialTheme.typography.bodySmall, color = Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp)) }
            if (nfc?.enabled == true) {
                OutlinedButton(onClick = {
                    val p = state.payload ?: return@OutlinedButton
                    nfcStatus = "Tap a blank NFC tag to write it…"
                    tag?.stop() // writing needs reader mode, which suspends card emulation
                    nfc.startWriting(p) { r -> nfcStatus = r.fold({ "Tag written" }, { "Write failed: ${it.message}" }); nfc.stopReading(); tag?.start() }
                }, modifier = Modifier.padding(top = 8.dp)) { Text("Write to an NFC sticker instead") }
                nfcStatus?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                val p = state.payload ?: return@Button
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, p)
                context.startActivity(Intent.createChooser(send, "Send link"))
            }) { Text("Send as a message instead") }
        }
    }
}

/**
 * Receiver side. NFC needs nothing from this screen: the other phone is a
 * tag, Android reads it and opens the share in CUEd by itself. The camera
 * stays off until "Scan a QR code" is tapped, because an open camera blocks
 * NFC on some phones. A paste box covers links sent as messages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveScreen(vm: ShareViewModel, onBack: () -> Unit, onReceived: (String) -> Unit, onStation: (String) -> Unit = {}, onPair: (String) -> Unit = {}, onFriend: (String) -> Unit = {}) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycle = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var scanning by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; if (it) scanning = true }
    var status by remember { mutableStateOf<String?>(null) }
    var handled by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    val nfc = remember(activity) { activity?.let { NfcReader(it) } }

    fun handle(text: String) {
        if (handled) return
        // Desktop pairing and friend codes (CUEd-desktop protocol §6 and §9) come through the same scanner.
        if (dev.cued.core.desktop.PairLink.decode(text) != null) { handled = true; onPair(text.trim()); return }
        if (dev.cued.core.desktop.FriendLink.decode(text) != null) { handled = true; onFriend(text.trim()); return }
        if (dev.cued.core.station.StationLink.decode(text) != null || dev.cued.core.crypto.Nip19.parse(text)?.first == "npub") { handled = true; onStation(text.trim()); return }
        val p = SharePayload.decode(text)
        if (p == null) { status = "Not a CUEd share code"; return }
        handled = true
        val msg = vm.receive(p)
        status = msg
        onReceived(msg)
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Receive") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(16.dp))
            Text("NFC", style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    nfc == null || !nfc.available -> "This phone has no NFC. Use the QR code or a pasted link."
                    !nfc.enabled -> "NFC is off. Turn it on in system settings; then a tap works from any screen."
                    else -> "Ready. Keep the screen on and hold the phones back-to-back while the other one shows its Share screen. The share opens in CUEd on its own; you do not need to stay here."
                },
                style = MaterialTheme.typography.bodyMedium, color = if (nfc?.enabled == true) Teal else Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(24.dp))
            Text("QR code", style = MaterialTheme.typography.titleMedium)
            if (!scanning) {
                Text("The camera stays off until you ask, since an open camera blocks NFC on some phones.", style = MaterialTheme.typography.bodySmall, color = Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                Button(onClick = { if (granted) scanning = true else launcher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.padding(top = 8.dp)) { Text("Scan a QR code") }
            } else {
                Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).padding(top = 8.dp)) {
                    AndroidView(factory = { ctx ->
                        val view = PreviewView(ctx)
                        val executor = Executors.newSingleThreadExecutor()
                        val providerFuture = ProcessCameraProvider.getInstance(ctx)
                        providerFuture.addListener({
                            val provider = providerFuture.get()
                            val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                            analysis.setAnalyzer(executor) { image ->
                                try { QrCodes.decode(image)?.let { text -> view.post { handle(text) } } } finally { image.close() }
                            }
                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                        }, ContextCompat.getMainExecutor(ctx))
                        view
                    }, modifier = Modifier.fillMaxSize())
                }
                DisposableEffect(Unit) { onDispose { runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() } } }
                OutlinedButton(onClick = { scanning = false; runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() } }, modifier = Modifier.padding(top = 8.dp)) { Text("Stop the camera") }
            }
            Spacer(Modifier.height(24.dp))
            Text("Pasted link (a share, or a station link / npub to follow)", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = pasted, onValueChange = { pasted = it }, singleLine = true, placeholder = { Text("cued://share?... or https://shinobihanzo.github.io/CUEd/#...") }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            OutlinedButton(onClick = { handle(pasted.trim()) }, enabled = pasted.isNotBlank(), modifier = Modifier.padding(top = 6.dp)) { Text("Use this link") }
            status?.let { Text(it, color = if (handled) Teal else Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp)) }
            Spacer(Modifier.height(96.dp))
        }
    }
}
