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
    var nfcStatus by remember { mutableStateOf<String?>(null) }
    val nfc = remember(activity) { activity?.let { NfcReader(it) } }

    LaunchedEffect(trackId) { vm.startSharing(trackId) }
    DisposableEffect(Unit) { onDispose { vm.stopSharing(); nfc?.stopReading() } }

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
            Spacer(Modifier.height(16.dp))
            Text(
                when {
                    nfc == null || !nfc.available -> "This phone has no NFC."
                    !nfc.enabled -> "NFC is off. Turn it on in system settings for tap-to-share."
                    else -> "NFC ready: hold the phones back-to-back while the other one is on its Receive screen."
                }, style = MaterialTheme.typography.bodySmall, color = if (nfc?.enabled == true) Teal else Muted, textAlign = TextAlign.Center,
            )
            if (nfc?.enabled == true) {
                OutlinedButton(onClick = {
                    val p = state.payload ?: return@OutlinedButton
                    nfcStatus = "Tap a blank NFC tag to write it…"
                    nfc.startWriting(p) { r -> nfcStatus = r.fold({ "Tag written" }, { "Write failed: ${it.message}" }); nfc.stopReading() }
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

/** Receiver side: camera QR scanner + NFC reader mode. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveScreen(vm: ShareViewModel, onBack: () -> Unit, onReceived: (String) -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycle = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var status by remember { mutableStateOf("Point the camera at a CUEd QR, or tap phones together") }
    var handled by remember { mutableStateOf(false) }
    val nfc = remember(activity) { activity?.let { NfcReader(it) } }

    fun handle(text: String) {
        if (handled) return
        val p = SharePayload.decode(text)
        if (p == null) { status = "Not a CUEd share code"; return }
        handled = true
        status = vm.receive(p)
        onReceived(status)
    }

    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    DisposableEffect(nfc) {
        nfc?.startReading(onPayload = { handle(it) }, onError = { status = it })
        onDispose { nfc?.stopReading() }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Receive") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (granted) {
                Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
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
            } else {
                Spacer(Modifier.height(40.dp))
                Text("Camera permission needed to scan QR codes", color = Muted)
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.padding(8.dp)) { Text("Grant") }
            }
            Spacer(Modifier.height(16.dp))
            Text(status, color = if (handled) Teal else Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
            Text(
                when {
                    nfc == null || !nfc.available -> "No NFC on this phone; QR still works."
                    !nfc.enabled -> "NFC is off; QR still works."
                    else -> "NFC reader active."
                }, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp),
            )
        }
    }
}
