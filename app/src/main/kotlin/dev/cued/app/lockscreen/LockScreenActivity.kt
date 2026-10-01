package dev.cued.app.lockscreen

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.cued.app.CuedApp
import dev.cued.app.MainActivity
import dev.cued.app.ui.CuedVmFactory
import dev.cued.app.ui.LocalGraph
import dev.cued.app.ui.PlayerViewModel
import dev.cued.app.ui.components.AlbumArt
import dev.cued.app.ui.components.formatMs
import dev.cued.app.ui.theme.CuedTheme
import dev.cued.app.ui.theme.Mist
import dev.cued.app.ui.theme.Muted
import dev.cued.app.ui.theme.Teal
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CUEd's own lock-screen player: a full-screen Now Playing drawn above the
 * keyguard. Started by [LockScreenGate] when the screen goes off during
 * playback; it goes away on unlock, on swipe-down, when the queue ends, or
 * via the unlock button (which asks the keyguard to dismiss and then opens
 * the app). It never keeps the screen on and never turns it on.
 */
@UnstableApi
class LockScreenActivity : ComponentActivity() {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_USER_PRESENT -> finishQuietly()
                // Screen came on without a keyguard (no lock set, or already dismissed): nothing to sit above.
                Intent.ACTION_SCREEN_ON -> window.decorView.postDelayed({ if (!isFinishing && keyguard()?.isKeyguardLocked != true) finishQuietly() }, 400)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val filter = IntentFilter().apply { addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_SCREEN_ON) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(receiver, filter)

        val graph = CuedApp.graph(this)
        setContent {
            CuedTheme {
                CompositionLocalProvider(LocalGraph provides graph) {
                    val vm: PlayerViewModel = viewModel(factory = CuedVmFactory(graph))
                    LockScreenPlayer(vm, onDismiss = { finishQuietly() }, onUnlock = { unlockAndOpen() })
                }
            }
        }
    }

    override fun onStart() { super.onStart(); visible = true }
    override fun onStop() { super.onStop(); visible = false }
    override fun onDestroy() { runCatching { unregisterReceiver(receiver) }; visible = false; super.onDestroy() }

    private fun keyguard() = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

    private fun finishQuietly() { if (!isFinishing) { finish(); @Suppress("DEPRECATION") overridePendingTransition(0, 0) } }

    private fun unlockAndOpen() {
        val open = { startActivity(Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_NOW_PLAYING).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); finishQuietly() }
        val km = keyguard()
        if (km == null || !km.isKeyguardLocked) { open(); return }
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = open()
            override fun onDismissCancelled() {}
            override fun onDismissError() {}
        })
    }

    companion object {
        /** True while the activity is on screen, so the gate does not stack a second one. */
        @Volatile var visible = false
    }
}

@UnstableApi
@Composable
private fun LockScreenPlayer(vm: PlayerViewModel, onDismiss: () -> Unit, onUnlock: () -> Unit) {
    val st by vm.state.collectAsState()
    val track by vm.currentTrack.collectAsState()
    var clock by remember { mutableStateOf(now()) }
    LaunchedEffect(Unit) { while (true) { clock = now(); delay(60_000L - System.currentTimeMillis() % 60_000L) } }
    // Queue ran out or was cleared: nothing to control.
    LaunchedEffect(st.connected, st.playbackState, st.queue.size) {
        if (st.connected && (st.queue.isEmpty() || st.playbackState == Player.STATE_ENDED)) { delay(1_500); onDismiss() }
    }
    var drag by remember { mutableFloatStateOf(0f) }

    Box(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
            detectVerticalDragGestures(
                onDragStart = { drag = 0f },
                onDragEnd = { if (drag > 180f) onDismiss(); drag = 0f },
                onVerticalDrag = { _, dy -> drag += dy },
            )
        },
    ) {
        AlbumArt(track, Modifier.fillMaxSize().blur(28.dp).alpha(0.45f), corner = 0)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent, Color.Black.copy(alpha = 0.85f)))))

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(24.dp))
            Text(clock.first, fontSize = 64.sp, fontWeight = FontWeight.Light, color = Mist)
            Text(clock.second, style = MaterialTheme.typography.bodyLarge, color = Muted)
            Spacer(Modifier.weight(1f))
            AlbumArt(track, Modifier.size(220.dp), corner = 18)
            Spacer(Modifier.height(22.dp))
            Text(st.title.ifBlank { "Nothing playing" }, style = MaterialTheme.typography.titleLarge, color = Mist, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Text(st.artist, style = MaterialTheme.typography.bodyLarge, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            var dragging by remember { mutableStateOf<Float?>(null) }
            val fraction = dragging ?: if (st.durationMs > 0) (st.positionMs.toFloat() / st.durationMs).coerceIn(0f, 1f) else 0f
            Slider(
                value = fraction,
                onValueChange = { dragging = it },
                onValueChangeFinished = { dragging?.let { vm.seekTo((it * st.durationMs).toLong()) }; dragging = null },
                colors = SliderDefaults.colors(thumbColor = Teal, activeTrackColor = Teal, inactiveTrackColor = Mist.copy(alpha = 0.2f)),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatMs((fraction * st.durationMs).toLong()), style = MaterialTheme.typography.labelMedium, color = Muted)
                Text(formatMs(st.durationMs), style = MaterialTheme.typography.labelMedium, color = Muted)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = { vm.previous() }, Modifier.size(64.dp)) { Icon(Icons.Default.SkipPrevious, "Previous", Modifier.size(40.dp), tint = Mist) }
                Spacer(Modifier.width(12.dp))
                IconButton(onClick = { vm.togglePlay() }, Modifier.size(84.dp).background(Teal, androidx.compose.foundation.shape.CircleShape)) {
                    Icon(if (st.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (st.isPlaying) "Pause" else "Play", Modifier.size(48.dp), tint = Color.Black)
                }
                Spacer(Modifier.width(12.dp))
                IconButton(onClick = { vm.next() }, Modifier.size(64.dp)) { Icon(Icons.Default.SkipNext, "Next", Modifier.size(40.dp), tint = Mist) }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onUnlock) { Icon(Icons.Default.LockOpen, null, Modifier.size(18.dp), tint = Muted); Spacer(Modifier.width(6.dp)); Text("Unlock and open", color = Muted) }
            }
            Text("Swipe down to hide", style = MaterialTheme.typography.labelSmall, color = Muted.copy(alpha = 0.7f))
        }
    }
}

private fun now(): Pair<String, String> {
    val d = Date()
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(d) to SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(d)
}
