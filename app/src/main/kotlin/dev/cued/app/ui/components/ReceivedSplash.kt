package dev.cued.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.cued.app.data.db.TrackEntity
import dev.cued.app.share.OfferedFile
import dev.cued.app.ui.LocalGraph
import dev.cued.app.ui.ReceiveStatus
import dev.cued.app.ui.Received
import dev.cued.app.ui.theme.Muted
import dev.cued.app.ui.theme.Teal

/**
 * Full-screen "this just arrived" card shown after a tap, a QR scan or a pasted
 * link. It stays until dismissed so the person sees the transfer land instead
 * of tapping again and again; a repeat tap only adds a note here.
 */
@Composable
fun ReceivedSplash(
    received: Received,
    onPlay: (TrackEntity) -> Unit,
    onOpenDownloads: () -> Unit,
    onFetchAnyway: () -> Unit,
    onDismiss: () -> Unit,
    /** Starts the phone-to-phone copy: Wi-Fi Direct, else Bluetooth (asking for the permission first if it is missing). */
    onDirect: () -> Unit = {},
    /** Starts (or restarts) the NFC copy: this phone becomes the reader and pulls the file from the other phone's Share screen. */
    onNfc: () -> Unit = {},
    /** "Download from the source instead" when the other phone is out of reach. */
    onQueueLink: () -> Unit = {},
    /** Why the phone-to-phone copy did not start by itself (a radio switched off, a permission), for the card's text. */
    directBlocker: String? = null,
) {
    val graph = LocalGraph.current
    val p = received.payload
    val st = received.status
    /** The share offers a copy over Wi-Fi Direct or Bluetooth. */
    val direct = p.hasP2p || p.btToken != null
    val trackId = (st as? ReceiveStatus.Done)?.trackId ?: (st as? ReceiveStatus.AlreadyHave)?.trackId
    var track by remember(trackId) { mutableStateOf<TrackEntity?>(null) }
    LaunchedEffect(trackId) { track = trackId?.let { graph.library.track(it) } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.weight(1f))
                Text(
                    when (st) {
                        is ReceiveStatus.Done -> "Received"
                        is ReceiveStatus.AlreadyHave -> "Already here"
                        is ReceiveStatus.Queued -> "Queued"
                        is ReceiveStatus.Fetching -> "Receiving"
                        is ReceiveStatus.Failed -> "Didn't make it"
                        is ReceiveStatus.DirectOffered -> "Offered phone to phone"
                        is ReceiveStatus.Direct -> if (!st.running) "Received" else "Receiving over ${st.via}"
                    },
                    style = MaterialTheme.typography.labelLarge, color = if (st is ReceiveStatus.Failed) MaterialTheme.colorScheme.error else Teal,
                )
                Spacer(Modifier.height(16.dp))
                if (track != null) AlbumArt(track, Modifier.size(200.dp), corner = 20, px = dev.cued.app.ui.components.Artwork.LARGE)
                else when (st) {
                    is ReceiveStatus.Fetching -> CircularProgressIndicator(Modifier.size(72.dp), color = Teal, strokeWidth = 5.dp)
                    is ReceiveStatus.Queued -> Icon(Icons.Default.Download, null, Modifier.size(96.dp), tint = Teal)
                    is ReceiveStatus.Failed -> Icon(Icons.Default.ErrorOutline, null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.error)
                    is ReceiveStatus.DirectOffered -> Icon(if (p.hasP2p) Icons.Default.Wifi else if (p.btToken != null) Icons.Default.Bluetooth else Icons.Default.Nfc, null, Modifier.size(96.dp), tint = Teal)
                    is ReceiveStatus.Direct -> Icon(
                        when (st.via) { OfferedFile.VIA_NFC -> Icons.Default.Nfc; OfferedFile.VIA_BLUETOOTH -> Icons.Default.Bluetooth; else -> Icons.Default.Wifi },
                        null, Modifier.size(96.dp), tint = Teal,
                    )
                    else -> Icon(Icons.Default.LibraryMusic, null, Modifier.size(96.dp), tint = Teal)
                }
                Spacer(Modifier.height(20.dp))
                Text(p.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text(p.artist, style = MaterialTheme.typography.titleMedium, color = Muted, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (p.fileUrl != null || p.nfcFile || direct) AssistChip(onClick = {}, label = { Text("Audio file") })
                    if (p.link != null) AssistChip(onClick = {}, label = { Text("Source link") })
                    p.bpm?.let { AssistChip(onClick = {}, label = { Text("${it.toInt()} BPM") }) }
                }
                if (p.genres.isNotEmpty()) Text(p.genres.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (st) {
                        is ReceiveStatus.Done, is ReceiveStatus.AlreadyHave -> Icon(Icons.Default.CheckCircle, null, tint = Teal)
                        is ReceiveStatus.Fetching -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Teal)
                        is ReceiveStatus.Direct -> if (st.running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Teal) else Icon(Icons.Default.CheckCircle, null, tint = Teal)
                        else -> {}
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (st) {
                            is ReceiveStatus.Fetching -> "Copying the file from the other phone. Keep both phones on the same Wi-Fi or hotspot."
                            is ReceiveStatus.Done -> "Added to your library."
                            is ReceiveStatus.AlreadyHave -> "This track is already in your library, so nothing was copied."
                            is ReceiveStatus.Queued -> "No file came with this share, so it is downloading from the source link. Follow it under Downloads."
                            is ReceiveStatus.Failed -> st.reason
                            is ReceiveStatus.DirectOffered ->
                                if (direct) "The other phone hands this file over directly: no router, hotspot or internet needed." + (directBlocker?.let { " $it." } ?: " Tap the button to start.")
                                else "The other phone has no Wi-Fi address to fetch from, but it offers the file over NFC alone. Nothing else is needed: no Wi-Fi, no hotspot, no internet."
                            is ReceiveStatus.Direct -> st.note
                        },
                        style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                    )
                }
                if (st is ReceiveStatus.Direct) {
                    val f = st.fraction
                    if (f != null) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().padding(top = 14.dp), color = Teal)
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 14.dp), color = Teal)
                    if (st.total > 0) Text(
                        "${st.have / 1024} of ${st.total / 1024} KB" + if (st.via == OfferedFile.VIA_NFC) " · NFC alone is slow: a song takes a few minutes. The phones must stay touching; a slip only pauses it." else "",
                        style = MaterialTheme.typography.bodySmall, color = Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (received.repeats > 0) Text(
                    "Tapped again (${received.repeats + 1}×). One tap is enough; nothing more was copied.",
                    style = MaterialTheme.typography.bodySmall, color = Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp),
                )
                Spacer(Modifier.weight(1f))
                when (st) {
                    is ReceiveStatus.Done, is ReceiveStatus.AlreadyHave -> {
                        Button(onClick = { track?.let(onPlay) }, enabled = track != null, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Play now")
                        }
                        if (st is ReceiveStatus.AlreadyHave) TextButton(onClick = onFetchAnyway) { Text("Copy it again anyway") }
                    }
                    is ReceiveStatus.Queued -> OutlinedButton(onClick = onOpenDownloads, modifier = Modifier.fillMaxWidth()) { Text("Open Downloads") }
                    is ReceiveStatus.Failed -> {
                        if (direct) Button(onClick = onDirect, modifier = Modifier.fillMaxWidth()) { Icon(if (p.hasP2p) Icons.Default.Wifi else Icons.Default.Bluetooth, null); Spacer(Modifier.width(6.dp)); Text("Copy phone to phone") }
                        if (p.nfcFile) OutlinedButton(onClick = onNfc, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Nfc, null); Spacer(Modifier.width(6.dp)); Text("Copy over NFC instead (slow)") }
                        if (p.fileUrl != null || p.link != null) OutlinedButton(onClick = onFetchAnyway, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
                    }
                    is ReceiveStatus.DirectOffered -> {
                        if (direct) Button(onClick = onDirect, modifier = Modifier.fillMaxWidth()) { Icon(if (p.hasP2p) Icons.Default.Wifi else Icons.Default.Bluetooth, null); Spacer(Modifier.width(6.dp)); Text("Copy phone to phone") }
                        if (p.nfcFile) OutlinedButton(onClick = onNfc, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Nfc, null); Spacer(Modifier.width(6.dp)); Text(if (direct) "Copy over NFC instead (slow)" else "Copy over NFC") }
                        if (p.link != null) TextButton(onClick = onQueueLink) { Text("Download from the source link instead") }
                    }
                    is ReceiveStatus.Direct -> if (st.running && st.via == OfferedFile.VIA_NFC) Text(
                        "Reader mode is on: tapping the other phone again carries on.",
                        style = MaterialTheme.typography.bodySmall, color = Muted, textAlign = TextAlign.Center,
                    )
                    is ReceiveStatus.Fetching -> {}
                }
                Spacer(Modifier.height(8.dp))
                val nfcRunning = st is ReceiveStatus.Direct && st.running && st.via == OfferedFile.VIA_NFC
                val keepsCopying = st is ReceiveStatus.Fetching || (st is ReceiveStatus.Direct && st.running && !nfcRunning)
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(if (keepsCopying) "Hide (keeps copying)" else if (nfcRunning) "Stop and close" else "Close") }
            }
        }
    }
}
