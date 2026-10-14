package dev.cued.core.dsp

import kotlin.math.PI
import kotlin.math.exp

/**
 * Karaoke / vocal cut for stereo PCM. Lead vocals sit in the centre of nearly
 * every mix, so the difference of the two channels cancels them. Doing only
 * that also removes kick and bass, which are centred too, so the centre's low
 * band (below [bassHz]) is put back. [strength] blends between the original
 * (0) and the full cut (1); half-way leaves a quiet guide vocal.
 *
 * Limits, said plainly: anything panned wide survives (backing vocals,
 * doubled guitars), anything centred besides the voice goes with it (snare,
 * centred synths), and a mono file comes out silent apart from the bass.
 *
 * One instance per stream: it keeps a one-pole low-pass state between calls.
 */
class VocalCut(sampleRate: Int = 44_100, var bassHz: Float = 160f) {
    var sampleRate: Int = sampleRate
        set(value) { field = value; recompute() }

    @Volatile var strength: Float = 1f
        set(value) { field = value.coerceIn(0f, 1f) }

    private var alpha = 0f
    // Two cascaded one-pole stages: 12 dB/octave, so a 1 kHz voice leaks through the bass path at -32 dB, not -16.
    private var lp1 = 0f
    private var lp2 = 0f

    init { recompute() }

    private fun recompute() {
        // One-pole low-pass: y += a * (x - y), a = 1 - e^(-2π f / sr).
        alpha = (1.0 - exp(-2.0 * PI * bassHz / sampleRate.coerceAtLeast(1))).toFloat().coerceIn(0f, 1f)
    }

    fun reset() { lp1 = 0f; lp2 = 0f }

    /** In place on interleaved 16-bit stereo; [frames] frames starting at [offset] samples. Non-stereo is left alone. */
    fun process(pcm: ShortArray, frames: Int, channels: Int = 2, offset: Int = 0) {
        if (channels != 2 || strength <= 0f) return
        val s = strength
        val keep = 1f - s
        var i = offset
        for (f in 0 until frames) {
            val l = pcm[i].toFloat()
            val r = pcm[i + 1].toFloat()
            val mid = 0.5f * (l + r)
            val side = 0.5f * (l - r)
            lp1 += alpha * (mid - lp1)
            lp2 += alpha * (lp1 - lp2)
            val kl = side + lp2
            val kr = -side + lp2
            pcm[i] = clamp(l * keep + kl * s)
            pcm[i + 1] = clamp(r * keep + kr * s)
            i += 2
        }
    }

    /** Same thing on interleaved float stereo in -1..1. */
    fun process(pcm: FloatArray, frames: Int, channels: Int = 2, offset: Int = 0) {
        if (channels != 2 || strength <= 0f) return
        val s = strength
        val keep = 1f - s
        var i = offset
        for (f in 0 until frames) {
            val l = pcm[i]; val r = pcm[i + 1]
            val mid = 0.5f * (l + r)
            val side = 0.5f * (l - r)
            lp1 += alpha * (mid - lp1)
            lp2 += alpha * (lp1 - lp2)
            pcm[i] = l * keep + (side + lp2) * s
            pcm[i + 1] = r * keep + (-side + lp2) * s
            i += 2
        }
    }

    private fun clamp(v: Float): Short = if (v > 32767f) 32767 else if (v < -32768f) -32768 else v.toInt().toShort()
}
