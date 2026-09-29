package dev.cued.core.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Tempo and beat-phase estimation from mono PCM.
 *
 * Classic, transparent signal processing rather than a learned model:
 *  1. onset strength = half-wave rectified difference of a smoothed energy envelope
 *  2. autocorrelation of the onset curve within the 60..200 BPM lag range
 *  3. the strongest lag, refined by parabolic interpolation, gives the period
 *  4. beat phase = onset-curve offset that best aligns with a comb at that period
 *
 * Accuracy is comparable to what a DJ would eyeball; octave errors (half/double
 * tempo) are possible, which is exactly why [dev.cued.core.mix.TempoMatcher]
 * treats tempos as equivalent up to simple ratios.
 */
class BpmDetector(
    private val sampleRate: Int = 44_100,
    private val minBpm: Float = 60f,
    private val maxBpm: Float = 200f,
    private val hopSize: Int = 512,
    /** Centre of the tempo prior; most popular music sits around here. */
    private val priorBpm: Float = 120f,
    /** Width of the prior in octaves; 1.0 is permissive, 0.5 strongly prefers 85..170. */
    private val priorWidthOctaves: Float = 1.0f,
) {
    data class Result(
        val bpm: Float,
        /** Confidence in 0..1, derived from the autocorrelation peak prominence. */
        val confidence: Float,
        /** Position of the first detected beat in seconds. */
        val firstBeatSec: Float,
    ) {
        val beatPeriodSec: Float get() = 60f / bpm
    }

    fun analyse(pcm: FloatArray): Result {
        val frames = max(1, pcm.size / hopSize)
        val energy = FloatArray(frames)
        for (f in 0 until frames) {
            var acc = 0f
            val start = f * hopSize
            val end = minOf(start + hopSize, pcm.size)
            for (i in start until end) acc += pcm[i] * pcm[i]
            energy[f] = sqrt(acc / max(1, end - start))
        }
        // Onset strength: positive change in energy, lightly smoothed.
        val onset = FloatArray(frames)
        var smooth = 0f
        for (f in 1 until frames) {
            val diff = energy[f] - energy[f - 1]
            smooth = 0.6f * smooth + 0.4f * max(0f, diff)
            onset[f] = smooth
        }
        val mean = onset.average().toFloat()
        for (f in onset.indices) onset[f] -= mean

        val framesPerSec = sampleRate.toFloat() / hopSize
        val minLag = max(1, (framesPerSec * 60f / maxBpm).roundToInt())
        val maxLag = minOf(frames - 1, (framesPerSec * 60f / minBpm).roundToInt())
        if (maxLag <= minLag) return Result(0f, 0f, 0f)

        val ac = FloatArray(maxLag * 2 + 2)
        val acMax = minOf(frames - 1, maxLag * 2)
        for (lag in minLag..acMax) {
            var acc = 0f
            for (f in lag until frames) acc += onset[f] * onset[f - lag]
            ac[lag] = acc / (frames - lag)
        }
        // Score each candidate lag: raw autocorrelation, reinforced by its
        // double (a true beat period also correlates at two beats) and
        // weighted by a broad log-Gaussian prior centred on 120 BPM so that
        // half/double-tempo ambiguities resolve towards the usual range.
        // True beat periods are rarely a whole number of frames, so a raw
        // integer-lag value under-reads peaks that fall between lags. Use the
        // parabolic-interpolated height at local maxima instead.
        val peak = FloatArray(ac.size)
        for (lag in 1 until acMax) {
            val y0 = ac[lag - 1]; val y1 = ac[lag]; val y2 = ac[lag + 1]
            peak[lag] = if (y1 >= y0 && y1 >= y2) {
                val denom = y0 - 2f * y1 + y2
                if (abs(denom) > 1e-9f) y1 - (y0 - y2) * (y0 - y2) / (8f * denom) else y1
            } else y1
        }
        fun peakNear(lag: Int): Float {
            var m = 0f
            for (l in (lag - 1)..(lag + 1)) if (l in 1 until acMax) m = max(m, peak[l])
            return m
        }
        val score = FloatArray(maxLag + 1)
        var best = minLag
        for (lag in minLag..maxLag) {
            val bpmAtLag = 60f * framesPerSec / lag
            val octaves = ln(bpmAtLag / priorBpm) / ln(2f)
            val prior = exp(-0.5f * (octaves / priorWidthOctaves) * (octaves / priorWidthOctaves))
            val harmonic = if (lag * 2 <= acMax) 0.5f * peakNear(lag * 2) else 0f
            score[lag] = prior * (max(0f, peak[lag]) + harmonic)
            if (score[lag] > score[best]) best = lag
        }
        if (score[best] <= 0f || ac[best] <= 0f) return Result(0f, 0f, 0f)
        // Tie-break towards the fundamental: a two-beat lag scores about the
        // same as the beat itself, so take the shortest lag that is nearly as
        // good as the best one.
        val threshold = score[best] * 0.85f
        for (lag in minLag..maxLag) {
            if (score[lag] >= threshold) { best = lag; break }
        }

        // Parabolic interpolation for sub-frame lag precision.
        val refined = if (best in (minLag + 1) until maxLag) {
            val y0 = ac[best - 1]; val y1 = ac[best]; val y2 = ac[best + 1]
            val denom = y0 - 2f * y1 + y2
            if (abs(denom) > 1e-9f) best + 0.5f * (y0 - y2) / denom else best.toFloat()
        } else best.toFloat()

        val bpm = 60f * framesPerSec / refined
        // Confidence: how much the chosen peak stands out from the strongest
        // rival that is neither adjacent nor a simple multiple of it.
        var second = 0f
        for (lag in minLag..maxLag) {
            if (abs(lag - best) <= 2) continue
            if (abs(lag - best * 2) <= 2 || abs(lag * 2 - best) <= 2) continue
            if (score[lag] > second) second = score[lag]
        }
        val confidence = ((score[best] - second) / score[best]).coerceIn(0f, 1f)

        // Beat phase: pick the offset whose comb of impulses best matches onsets.
        val period = refined
        var bestPhase = 0
        var bestScore = Float.NEGATIVE_INFINITY
        val phaseSteps = max(1, period.roundToInt())
        for (phase in 0 until phaseSteps) {
            var score = 0f
            var t = phase.toFloat()
            while (t < frames) {
                score += onset[t.roundToInt().coerceAtMost(frames - 1)]
                t += period
            }
            if (score > bestScore) { bestScore = score; bestPhase = phase }
        }
        return Result(bpm, confidence, bestPhase / framesPerSec)
    }
}
