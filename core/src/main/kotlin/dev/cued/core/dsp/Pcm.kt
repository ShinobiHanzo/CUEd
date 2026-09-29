package dev.cued.core.dsp

/** Helpers for converting interleaved 16-bit PCM into mono float samples. */
object Pcm {
    fun shortsToMonoFloat(src: ShortArray, channels: Int, dst: FloatArray, frames: Int = src.size / channels) {
        val scale = 1f / 32768f
        for (f in 0 until frames) {
            var acc = 0
            val base = f * channels
            for (c in 0 until channels) acc += src[base + c]
            dst[f] = acc * scale / channels
        }
    }

    /** Decimates by an integer factor with a simple box filter. Used to keep whole-track analysis cheap. */
    fun decimate(src: FloatArray, factor: Int): FloatArray {
        if (factor <= 1) return src
        val out = FloatArray(src.size / factor)
        for (i in out.indices) {
            var acc = 0f
            val base = i * factor
            for (k in 0 until factor) acc += src[base + k]
            out[i] = acc / factor
        }
        return out
    }
}
