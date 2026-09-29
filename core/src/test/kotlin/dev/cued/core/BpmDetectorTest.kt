package dev.cued.core

import dev.cued.core.dsp.BpmDetector
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertTrue

class BpmDetectorTest {
    private fun click(bpm: Float, seconds: Float, sr: Int = 44_100, offsetSec: Float = 0f): FloatArray {
        val n = (seconds * sr).toInt()
        val pcm = FloatArray(n)
        val period = 60f / bpm * sr
        var t = offsetSec * sr
        while (t < n) {
            val start = t.toInt()
            for (i in 0 until 2000) {
                val idx = start + i
                if (idx >= n) break
                pcm[idx] += (exp(-i / 300.0) * kotlin.math.sin(i * 0.3)).toFloat()
            }
            t += period
        }
        return pcm
    }

    @Test
    fun `detects a 128 bpm click track`() {
        val r = BpmDetector().analyse(click(128f, 20f))
        assertTrue(abs(r.bpm - 128f) < 1.5f, "got ${r.bpm}")
        assertTrue(r.confidence > 0.2f, "confidence ${r.confidence}")
    }

    @Test
    fun `detects 90 bpm and the first beat offset`() {
        val r = BpmDetector().analyse(click(90f, 20f, offsetSec = 0.25f))
        assertTrue(abs(r.bpm - 90f) < 1.5f, "got ${r.bpm}")
        assertTrue(abs(r.firstBeatSec - 0.25f) < 0.05f, "first beat ${r.firstBeatSec}")
    }

    @Test
    fun `silence has no tempo`() {
        val r = BpmDetector().analyse(FloatArray(44_100 * 5))
        assertTrue(r.bpm == 0f)
    }
}

class BpmOctaveTest {
    @Test
    fun `170 bpm is not halved and 70 bpm is not doubled`() {
        val det = BpmDetector()
        fun click(bpm: Float): FloatArray {
            val sr = 44_100; val n = sr * 20
            val pcm = FloatArray(n); val period = 60f / bpm * sr
            var t = 0f
            while (t < n) { val s = t.toInt(); for (i in 0 until 1500) { val idx = s + i; if (idx >= n) break; pcm[idx] += (exp(-i / 250.0) * kotlin.math.sin(i * 0.4)).toFloat() }; t += period }
            return pcm
        }
        val fast = det.analyse(click(170f))
        assertTrue(abs(fast.bpm - 170f) < 2f, "got ${fast.bpm}")
        val slow = det.analyse(click(70f))
        assertTrue(abs(slow.bpm - 70f) < 2f, "got ${slow.bpm}")
    }
}
