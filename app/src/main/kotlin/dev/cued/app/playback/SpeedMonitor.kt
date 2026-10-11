package dev.cued.app.playback

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import dev.cued.app.util.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Ground speed from GPS, for "switch to car mode when moving". Plain
 * LocationManager, no Play services; fixes stay on the phone and only the
 * smoothed speed is exposed. Runs only while [start] has been called by a
 * screen that is in front, and asks for a fix every few seconds.
 */
class SpeedMonitor(private val context: Context) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val _speedKmh = MutableStateFlow<Float?>(null)
    /** Median of the last three usable fixes, km/h; null when not running or no fix yet. */
    val speedKmh: StateFlow<Float?> = _speedKmh
    private var last: Location? = null
    private val recent = ArrayDeque<Float>()
    private var running = false

    // A full object, not a lambda: on Android 8 and 9 the other callbacks are abstract and the framework calls them.
    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) { onFix(loc) }
        @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) { _speedKmh.value = null }
    }

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    @Synchronized fun start() {
        if (running || !hasPermission()) return
        running = runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, INTERVAL_MS, 0f, listener, Looper.getMainLooper())
            true
        }.onFailure { DebugLog.w(TAG, "speed monitor", it) }.getOrDefault(false)
    }

    @Synchronized fun stop() {
        if (!running) return
        running = false
        runCatching { lm.removeUpdates(listener) }
        _speedKmh.value = null
        recent.clear()
        last = null
    }

    private fun onFix(loc: Location) {
        if (loc.hasAccuracy() && loc.accuracy > MAX_ACCURACY_M) return
        val prev = last
        last = loc
        val kmh = when {
            loc.hasSpeed() -> loc.speed * 3.6f
            prev != null && loc.time > prev.time -> loc.distanceTo(prev) / ((loc.time - prev.time) / 1000f) * 3.6f
            else -> return
        }
        if (kmh.isNaN() || kmh < 0f || kmh > 400f) return
        recent.addLast(kmh)
        while (recent.size > 3) recent.removeFirst()
        _speedKmh.value = recent.sorted()[recent.size / 2]
    }

    companion object {
        private const val TAG = "speed"
        private const val INTERVAL_MS = 4_000L
        private const val MAX_ACCURACY_M = 50f
    }
}
