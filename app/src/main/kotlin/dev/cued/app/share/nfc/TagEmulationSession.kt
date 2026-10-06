package dev.cued.app.share.nfc

import android.app.Activity
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.os.Build
import android.util.Log

/**
 * Owns the phone's NFC behaviour while the Share screen is in front, so that
 * tap-to-share works on phones crowded with other NFC services (wallets, car
 * keys, Nearby) and the sender never reacts to the receiver.
 *
 * Three things, all undone in [stop]:
 *
 * 1. The NDEF tag AID is claimed only now, dynamically, and CUEd is made the
 *    preferred card-emulation service for this foreground activity. Several
 *    services on a Samsung phone register the same standard NDEF AID; without
 *    a preferred service Android asks the user which one to use on every tap.
 * 2. The sender's own tag polling is switched off (Android 14+), or every tag
 *    it finds is swallowed by foreground dispatch on older versions. Otherwise
 *    the sender sees the receiver (whose wallet also looks like a card) as an
 *    unknown NFC-A tag and launches whatever tag-reader app is installed.
 * 3. Reader mode is never used here: it disables card emulation.
 */
class TagEmulationSession(private val activity: Activity) {
    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    private val component = ComponentName(activity, CuedHceService::class.java)
    private var active = false

    /** Null when everything went through; otherwise a short note for the screen. */
    var problem: String? = null
        private set

    fun start() {
        val a = adapter ?: return
        if (active || !a.isEnabled) return
        active = true
        val notes = ArrayList<String>()
        runCatching {
            val ce = CardEmulation.getInstance(a)
            if (!ce.registerAidsForService(component, CardEmulation.CATEGORY_OTHER, AIDS)) notes += "could not claim the NFC tag slot"
            if (!ce.setPreferredService(activity, component)) notes += "another NFC service keeps priority"
        }.onFailure { Log.w(TAG, "card emulation setup failed", it); notes += "card emulation unavailable" }
        runCatching {
            val launch = Intent(activity, activity.javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            a.enableForegroundDispatch(activity, PendingIntent.getActivity(activity, 0, launch, flags), null, null)
        }.onFailure { Log.w(TAG, "foreground dispatch failed", it) }
        if (Build.VERSION.SDK_INT >= 34) runCatching {
            a.setDiscoveryTechnology(activity, NfcAdapter.FLAG_READER_DISABLE, NfcAdapter.FLAG_LISTEN_KEEP)
        }.onFailure { Log.w(TAG, "could not stop polling", it) }
        problem = notes.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    fun stop() {
        val a = adapter ?: return
        if (!active) return
        active = false
        if (Build.VERSION.SDK_INT >= 34) runCatching { a.resetDiscoveryTechnology(activity) }
        runCatching { a.disableForegroundDispatch(activity) }
        runCatching {
            val ce = CardEmulation.getInstance(a)
            ce.unsetPreferredService(activity)
            ce.removeAidsForService(component, CardEmulation.CATEGORY_OTHER)
        }.onFailure { Log.w(TAG, "card emulation teardown failed", it) }
    }

    companion object {
        private const val TAG = "TagEmulation"
        /** Standard NFC Forum Type 4 NDEF application, plus the CUEd-only AID for old receivers. */
        val AIDS = listOf("D2760000850101", "F043554544")

        /** Drops a dynamic AID claim left behind by a crash, so CUEd does not sit on the NDEF slot when nothing is being shared. */
        fun release(context: Context) {
            val a = NfcAdapter.getDefaultAdapter(context) ?: return
            runCatching { CardEmulation.getInstance(a).removeAidsForService(ComponentName(context, CuedHceService::class.java), CardEmulation.CATEGORY_OTHER) }
        }
    }
}
