package dev.cued.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * In-place iterative radix-2 complex FFT.
 *
 * Deliberately dependency-free and allocation-light so it can run on the
 * audio thread of an entry-level phone. Sizes must be a power of two.
 */
class Fft(val size: Int) {
    init {
        require(size >= 2 && size and (size - 1) == 0) { "FFT size must be a power of two, got $size" }
    }

    private val levels = Integer.numberOfTrailingZeros(size)
    private val cosTable = FloatArray(size / 2) { cos(2.0 * PI * it / size).toFloat() }
    private val sinTable = FloatArray(size / 2) { sin(2.0 * PI * it / size).toFloat() }
    private val bitRev = IntArray(size) { Integer.reverse(it) ushr (32 - levels) }

    /** Forward transform of [re]/[im] in place. */
    fun transform(re: FloatArray, im: FloatArray) {
        require(re.size == size && im.size == size)
        for (i in 0 until size) {
            val j = bitRev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var half = 1
        while (half < size) {
            val step = size / (half * 2)
            var i = 0
            while (i < size) {
                var k = 0
                for (j in i until i + half) {
                    val l = j + half
                    val tre = re[l] * cosTable[k] + im[l] * sinTable[k]
                    val tim = -re[l] * sinTable[k] + im[l] * cosTable[k]
                    re[l] = re[j] - tre
                    im[l] = im[j] - tim
                    re[j] += tre
                    im[j] += tim
                    k += step
                }
                i += half * 2
            }
            half *= 2
        }
    }

    /**
     * Magnitude spectrum of a real signal. Writes `size/2` bins into [out]
     * (DC .. Nyquist-1). [window] is applied to [input] before the transform.
     */
    fun magnitudes(input: FloatArray, window: FloatArray, re: FloatArray, im: FloatArray, out: FloatArray) {
        for (i in 0 until size) {
            re[i] = if (i < input.size) input[i] * window[i] else 0f
            im[i] = 0f
        }
        transform(re, im)
        val bins = size / 2
        for (i in 0 until bins) {
            out[i] = sqrt(re[i] * re[i] + im[i] * im[i])
        }
    }

    companion object {
        fun hann(size: Int): FloatArray = FloatArray(size) { i ->
            (0.5 * (1.0 - cos(2.0 * PI * i / (size - 1)))).toFloat()
        }
    }
}
