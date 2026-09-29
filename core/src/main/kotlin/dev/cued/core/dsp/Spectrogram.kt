package dev.cued.core.dsp

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * A short-time Fourier transform reduced to a fixed number of perceptual
 * (log-spaced) bands. Produces both the per-frame spectrum for the reactive
 * visualiser and the whole-track image for the static scrubber background.
 */
class Spectrogram(
    val fftSize: Int = 2048,
    val hopSize: Int = 1024,
    val bands: Int = 64,
    sampleRate: Int = 44_100,
    minHz: Float = 40f,
    maxHz: Float = 16_000f,
) {
    private val fft = Fft(fftSize)
    private val window = Fft.hann(fftSize)
    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)
    private val mags = FloatArray(fftSize / 2)
    private val bandEdges: IntArray

    init {
        val nyquist = sampleRate / 2f
        val hzPerBin = nyquist / (fftSize / 2)
        val lo = max(minHz, hzPerBin)
        val hi = min(maxHz, nyquist)
        val ratio = (hi / lo).toDouble().pow(1.0 / bands)
        bandEdges = IntArray(bands + 1) { i ->
            val hz = lo * ratio.pow(i.toDouble())
            (hz / hzPerBin).toInt().coerceIn(1, fftSize / 2 - 1)
        }
    }

    /** Computes [bands] dB-scaled (0..1) values for one frame starting at [offset] of [pcm]. */
    fun frame(pcm: FloatArray, offset: Int, out: FloatArray) {
        require(out.size >= bands)
        val end = min(offset + fftSize, pcm.size)
        for (i in 0 until fftSize) {
            val idx = offset + i
            re[i] = if (idx < end) pcm[idx] * window[i] else 0f
            im[i] = 0f
        }
        fft.transform(re, im)
        for (i in mags.indices) mags[i] = re[i] * re[i] + im[i] * im[i]
        for (b in 0 until bands) {
            val from = bandEdges[b]
            val to = max(from + 1, bandEdges[b + 1])
            var acc = 0f
            for (k in from until to) acc += mags[k]
            val power = acc / (to - from)
            out[b] = powerToUnit(power)
        }
    }

    /** Converts a full mono track to a `frames x bands` image, row-major. */
    fun analyse(pcm: FloatArray): SpectrogramImage {
        val frames = max(1, (pcm.size - fftSize) / hopSize + 1)
        val data = FloatArray(frames * bands)
        val tmp = FloatArray(bands)
        for (f in 0 until frames) {
            frame(pcm, f * hopSize, tmp)
            System.arraycopy(tmp, 0, data, f * bands, bands)
        }
        return SpectrogramImage(frames, bands, data)
    }

    companion object {
        private const val FLOOR_DB = -80f

        /** Maps FFT power to a 0..1 range on a dB scale with an -80 dB floor. */
        fun powerToUnit(power: Float): Float {
            if (power <= 1e-12f) return 0f
            val db = 10f * log10(power) - 60f // normalise for windowed FFT of unit signals
            return ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)
        }
    }
}

/** A static spectrogram: [frames] columns of [bands] values in 0..1. */
class SpectrogramImage(val frames: Int, val bands: Int, val data: FloatArray) {
    operator fun get(frame: Int, band: Int): Float = data[frame * bands + band]

    /** Resamples to [columns] by max-pooling frames, useful for drawing at screen width. */
    fun downsample(columns: Int): SpectrogramImage {
        if (columns >= frames) return this
        val out = FloatArray(columns * bands)
        for (c in 0 until columns) {
            val from = c * frames / columns
            val to = max(from + 1, (c + 1) * frames / columns)
            for (b in 0 until bands) {
                var m = 0f
                for (f in from until to) m = max(m, this[f, b])
                out[c * bands + b] = m
            }
        }
        return SpectrogramImage(columns, bands, out)
    }

    /** Per-column loudness in 0..1, the "waveform" view. */
    fun envelope(): FloatArray = FloatArray(frames) { f ->
        var acc = 0f
        for (b in 0 until bands) acc += this[f, b]
        acc / bands
    }
}

/**
 * Feed PCM in arbitrary chunk sizes and receive spectrogram columns as they
 * become available. Lets the analyser decode a whole track without keeping
 * it all in memory, which matters on phones with little RAM.
 */
class StreamingSpectrogram(
    private val spectrogram: Spectrogram,
    private val onFrame: (FloatArray) -> Unit,
) {
    private val fftSize = spectrogram.fftSize
    private val hop = spectrogram.hopSize
    private val buffer = FloatArray(fftSize * 4)
    private var filled = 0
    private val out = FloatArray(spectrogram.bands)
    var framesEmitted = 0
        private set

    fun push(samples: FloatArray, count: Int = samples.size) {
        var offset = 0
        while (offset < count) {
            val n = minOf(count - offset, buffer.size - filled)
            System.arraycopy(samples, offset, buffer, filled, n)
            filled += n
            offset += n
            drain()
        }
    }

    private fun drain() {
        var pos = 0
        while (pos + fftSize <= filled) {
            spectrogram.frame(buffer, pos, out)
            onFrame(out.copyOf())
            framesEmitted++
            pos += hop
        }
        if (pos > 0) {
            System.arraycopy(buffer, pos, buffer, 0, filled - pos)
            filled -= pos
        }
    }

    /** Flushes the tail as a final (zero-padded) frame if anything is left. */
    fun finish() {
        if (filled > hop) {
            spectrogram.frame(buffer.copyOf(filled), 0, out)
            onFrame(out.copyOf())
            framesEmitted++
        }
        filled = 0
    }
}
