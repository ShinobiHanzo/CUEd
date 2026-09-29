package dev.cued.app.playback

import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Reports whether Android itself thinks we're in a car (car dock, head unit
 * that switches the UI mode). Android Auto proper talks to [PlaybackService]
 * over the media session instead and draws its own UI, so this only drives
 * the in-app big-button screen.
 */
class CarModeDetector(context: Context) {
    private val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
    private val _inCar = MutableStateFlow(uiMode.currentModeType == Configuration.UI_MODE_TYPE_CAR)
    val inCar: StateFlow<Boolean> = _inCar

    init {
        val filter = IntentFilter().apply {
            addAction(UiModeManager.ACTION_ENTER_CAR_MODE)
            addAction(UiModeManager.ACTION_EXIT_CAR_MODE)
        }
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                _inCar.value = intent?.action == UiModeManager.ACTION_ENTER_CAR_MODE
            }
        }, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}
