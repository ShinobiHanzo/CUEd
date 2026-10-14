package dev.cued.core

import dev.cued.core.dsp.VocalCut
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VocalCutTest {
    private val sr = 48_000
    private fun stereo(frames: Int, left: (Int) -> Double, right: (Int) -> Double): ShortArray {
        val out = ShortArray(frames * 2)
        for (i in 0 until frames) { out[2 * i] = (left(i) * 20000).toInt().toShort(); out[2 * i + 1] = (right(i) * 20000).toInt().toShort() }
        return out
    }
    private fun rms(pcm: ShortArray, channel: Int, from: Int = 0): Double {
        var acc = 0.0; var n = 0
        var i = from * 2 + channel
        while (i < pcm.size) { acc += pcm[i].toDouble() * pcm[i]; n++; i += 2 }
        return sqrt(acc / n)
    }

    @Test fun centredVocalIsCancelledButCentredBassStays() {
        val frames = sr
        val voice = { i: Int -> sin(2 * PI * 1000 * i / sr) }
        val bass = { i: Int -> 0.5 * sin(2 * PI * 60 * i / sr) }
        val pcm = stereo(frames, { voice(it) + bass(it) }, { voice(it) + bass(it) })
        val before = rms(pcm, 0)
        VocalCut(sr).process(pcm, frames)
        // The 1 kHz voice (gain 1) is gone; the 60 Hz bass (gain 0.5) is left, settled after the filter warms up.
        val after = rms(pcm, 0, from = sr / 10)
        val bassOnly = 0.5 * 20000 / sqrt(2.0)
        assertTrue(after < before * 0.4, "after $after before $before")
        assertTrue(after > bassOnly * 0.7 && after < bassOnly * 1.1, "after $after expected ~$bassOnly")
    }

    @Test fun wideStereoSurvivesAndStrengthBlends() {
        val frames = sr / 2
        val leftOnly = stereo(frames, { sin(2 * PI * 440 * it / sr) }, { 0.0 })
        val original = leftOnly.copyOf()
        VocalCut(sr).process(leftOnly, frames)
        // A hard-left source has side = mid = L/2: left keeps most of it, right gets the inverted side (no longer silent).
        val l = rms(leftOnly, 0, from = sr / 10); val r = rms(leftOnly, 1, from = sr / 10)
        assertTrue(l > rms(original, 0) * 0.4, "left $l")
        assertTrue(r > rms(original, 0) * 0.3, "right $r")

        val voice = stereo(frames, { sin(2 * PI * 1000 * it / sr) }, { sin(2 * PI * 1000 * it / sr) })
        val half = VocalCut(sr).apply { strength = 0.5f }
        val ref = rms(voice, 0)
        half.process(voice, frames)
        val got = rms(voice, 0, from = sr / 10)
        assertTrue(got > ref * 0.4 && got < ref * 0.6, "half strength left $got of $ref")

        val off = VocalCut(sr).apply { strength = 0f }
        val untouched = stereo(10, { 0.3 }, { -0.2 })
        val copy = untouched.copyOf()
        off.process(untouched, 10)
        assertTrue(copy.contentEquals(untouched))
    }

    @Test fun nonStereoIsLeftAloneAndFloatPathMatches() {
        val mono = ShortArray(100) { 1000 }
        VocalCut(sr).process(mono, 100, channels = 1)
        assertTrue(mono.all { it == 1000.toShort() })

        val frames = 1000
        val shorts = stereo(frames, { sin(2 * PI * 500 * it / sr) }, { 0.5 * sin(2 * PI * 500 * it / sr) })
        val floats = FloatArray(frames * 2) { shorts[it] / 32768f }
        VocalCut(sr).process(shorts, frames)
        VocalCut(sr).process(floats, frames)
        for (i in 0 until frames * 2 step 97) assertTrue(kotlin.math.abs(shorts[i].toInt() - (floats[i] * 32768f).toInt()) <= 2)
    }
}
