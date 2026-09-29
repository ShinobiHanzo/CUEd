package dev.cued.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import dev.cued.app.ui.CuedRoot
import dev.cued.app.ui.Inbound
import dev.cued.app.ui.LocalGraph
import dev.cued.app.ui.theme.CuedTheme
import dev.cued.core.share.SharePayload
import dev.cued.core.share.SourceLinks
import kotlinx.coroutines.flow.MutableStateFlow

@UnstableApi
class MainActivity : ComponentActivity() {
    private val inbound = MutableStateFlow<Inbound?>(null)

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.entries.any { it.key.contains("MEDIA_AUDIO") || it.key.contains("EXTERNAL_STORAGE") } && hasAudioPermission()) {
            CuedApp.graph(this).library.rescanAsync()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = CuedApp.graph(this)
        handleIntent(intent)
        requestPermissionsIfNeeded()
        if (hasAudioPermission()) graph.library.rescanAsync()
        setContent {
            CuedTheme {
                CompositionLocalProvider(LocalGraph provides graph) {
                    CuedRoot(graph, inbound, onInboundHandled = { inbound.value = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            ACTION_NOW_PLAYING -> inbound.value = Inbound.NowPlaying
            Intent.ACTION_VIEW, android.nfc.NfcAdapter.ACTION_NDEF_DISCOVERED -> {
                val text = intent.dataString ?: return
                SharePayload.decode(text)?.let { inbound.value = Inbound.Share(it) }
            }
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim() ?: return
                val payload = text.lines().firstNotNullOfOrNull { SharePayload.decode(it.trim()) }
                if (payload != null) inbound.value = Inbound.Share(payload)
                else if (SourceLinks.classify(text) != SourceLinks.Kind.UNKNOWN) inbound.value = Inbound.Download(text)
            }
        }
    }

    private fun hasAudioPermission(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissionsIfNeeded() {
        val wanted = ArrayList<String>()
        if (!hasAudioPermission()) wanted += if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) wanted += Manifest.permission.POST_NOTIFICATIONS
        if (wanted.isNotEmpty()) permissions.launch(wanted.toTypedArray())
    }

    companion object {
        const val ACTION_NOW_PLAYING = "dev.cued.app.NOW_PLAYING"
    }
}
