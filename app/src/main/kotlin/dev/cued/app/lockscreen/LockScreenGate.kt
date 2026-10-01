package dev.cued.app.lockscreen

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import androidx.media3.common.Player
import dev.cued.app.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Decides when to put [LockScreenActivity] up. Lives in the playback service
 * because that is the only component alive while the screen is off.
 *
 * The trick every lock-screen player uses: when the screen turns *off* while
 * music plays, start the activity right away. It is flagged showWhenLocked,
 * so it is already drawn when the user presses the power button, sitting
 * above the keyguard. Android 10+ only lets a background service start an
 * activity when the app holds "display over other apps"; without that
 * permission this gate stays quiet and Settings explains what to enable.
 */
class LockScreenGate(private val context: Context, private val player: Player, enabled: Flow<Boolean>, scope: CoroutineScope) {
    @Volatile private var enabled = false
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> maybeShow(reason = "screen off")
                Intent.ACTION_SCREEN_ON -> if (keyguard()?.isKeyguardLocked == true) maybeShow(reason = "screen on, locked")
            }
        }
    }

    init {
        enabled.onEach { on -> this.enabled = on; if (on) register() else unregister() }.launchIn(scope)
    }

    private fun keyguard() = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

    private fun maybeShow(reason: String) {
        if (!enabled || LockScreenActivity.visible) return
        if (!(player.playWhenReady && player.mediaItemCount > 0)) return
        if (!canStart(context)) { DebugLog.d(TAG, "no overlay permission; not showing lock screen player"); return }
        DebugLog.d(TAG, "showing lock screen player ($reason)")
        runCatching {
            context.startActivity(Intent(context, LockScreenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }.onFailure { DebugLog.w(TAG, "could not start lock screen player", it) }
    }

    private fun register() {
        if (registered) return
        val f = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED) else context.registerReceiver(receiver, f)
        registered = true
    }

    private fun unregister() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    fun release() = unregister()

    companion object {
        private const val TAG = "lockscreen"
        /** Android 10+ needs "display over other apps" for a service to start an activity. */
        fun canStart(context: Context): Boolean = Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(context)
        fun overlaySettingsIntent(context: Context): Intent =
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}"))
    }
}
