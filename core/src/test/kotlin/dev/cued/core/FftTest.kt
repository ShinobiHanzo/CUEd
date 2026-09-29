package dev.cued.core

import dev.cued.core.dsp.Fft
import dev.cued.core.dsp.Spectrogram
import dev.cued.core.dsp.StreamingSpectrogram
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FftTest {
    @Test
    fun `pure tone peaks in the right bin`() {
        val n = 1024
        val fft = Fft(n)
        val sr = 44_100f
        val freq = sr / n * 100 // exactly bin 100
        val input = FloatArray(n) { sin(2.0 * PI * freq * it / sr).toFloat() }
        val out = FloatArray(n / 2)
        fft.magnitudes(input, FloatArray(n) { 1f }, FloatArray(n), FloatArray(n), out)
        val peak = out.indices.maxByOrNull { out[it] }!!
        assertEquals(100, peak)
        assertTrue(out[100] > 10 * out[50])
    }

    @Test
    fun `spectrogram of silence is zero and of noise is not`() {
        val spec = Spectrogram(fftSize = 512, hopSize = 256, bands = 16)
        val silence = FloatArray(4096)
        val img = spec.analyse(silence)
        assertTrue(img.data.all { it == 0f })
        val rnd = java.util.Random(1)
        val noise = FloatArray(4096) { (rnd.nextFloat() * 2f - 1f) * 0.5f }
        val img2 = spec.analyse(noise)
        assertTrue(img2.data.any { it > 0.2f })
        val down = img2.downsample(4)
        assertEquals(4, down.frames)
        assertEquals(16, down.bands)
    }
}

class StreamingSpectrogramTest {
    @Test
    fun `streaming matches batch analysis`() {
        val spec = Spectrogram(fftSize = 256, hopSize = 128, bands = 8)
        val rnd = java.util.Random(7)
        val pcm = FloatArray(10_000) { (rnd.nextFloat() * 2f - 1f) * 0.3f }
        val batch = spec.analyse(pcm)
        val frames = ArrayList<FloatArray>()
        val streaming = StreamingSpectrogram(spec) { frames += it }
        var i = 0
        while (i < pcm.size) {
            val n = minOf(333, pcm.size - i)
            streaming.push(pcm.copyOfRange(i, i + n), n)
            i += n
        }
        assertEquals(batch.frames, frames.size)
        for (f in 0 until batch.frames) for (b in 0 until 8) {
            assertEquals(batch[f, b], frames[f][b], 1e-5f)
        }
    }
}
