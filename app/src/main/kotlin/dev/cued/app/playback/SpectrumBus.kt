package dev.cued.app.playback

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Where the audio taps publish their spectra and the UI reads them.
 *
 * Two decks may be playing during a crossfade; their frames are combined
 * weighted by each deck's current gain. Frames are kept in a short ring with
 * timestamps so the UI can read the frame from `visualDelayMs` ago and line
 * the picture up with what the speaker is actually playing (the tap sees
 * audio before it has crossed the output buffer).
 */
class SpectrumBus(val bands: Int) {
    private class Slot(var at: Long = 0L, val frame: FloatArray)

    private val ring = Array(64) { Slot(frame = FloatArray(bands)) }
    private var head = 0
    private val deckFrames = arrayOf(FloatArray(bands), FloatArray(bands))
    private val deckAt = longArrayOf(0L, 0L)
    private val deckGain = floatArrayOf(1f, 0f)

    private val _latest = MutableStateFlow(FloatArray(bands))
    /** Newest combined frame; mostly useful for tests and a cheap "is anything playing" signal. */
    val latest: StateFlow<FloatArray> = _latest

    @Synchronized
    fun setGain(deck: Int, gain: Float) { deckGain[deck] = gain }

    @Synchronized
    fun publish(deck: Int, frame: FloatArray) {
        if (frame.size != bands) return
        System.arraycopy(frame, 0, deckFrames[deck], 0, bands)
        val now = SystemClock.elapsedRealtime()
        deckAt[deck] = now
        val slot = ring[head]
        head = (head + 1) % ring.size
        slot.at = now
        val other = 1 - deck
        val otherFresh = now - deckAt[other] < 200L
        for (i in 0 until bands) {
            val a = deckFrames[deck][i] * deckGain[deck]
            val b = if (otherFresh) deckFrames[other][i] * deckGain[other] else 0f
            slot.frame[i] = maxOf(a, b)
        }
        _latest.value = slot.frame.copyOf()
    }

    /** Copies the frame closest to `now - delayMs` into [out]. Returns false if nothing recent exists. */
    @Synchronized
    fun read(delayMs: Long, out: FloatArray): Boolean {
        val target = SystemClock.elapsedRealtime() - delayMs
        var best: Slot? = null
        var bestDiff = Long.MAX_VALUE
        for (s in ring) {
            if (s.at == 0L) continue
            val d = kotlin.math.abs(s.at - target)
            if (d < bestDiff) { bestDiff = d; best = s }
        }
        val b = best ?: return false
        if (SystemClock.elapsedRealtime() - b.at > 500L) return false
        System.arraycopy(b.frame, 0, out, 0, bands)
        return true
    }
}
