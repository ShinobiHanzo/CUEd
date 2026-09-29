package dev.cued.core.mix

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Shapes for the gain curves used during a crossfade. */
enum class CrossfadeCurve {
    /** Both tracks at -3 dB in the middle; constant perceived loudness. Default. */
    EQUAL_POWER,
    /** Straight lines; slight dip in the middle, but transparent for very short fades. */
    LINEAR,
    /** Outgoing holds then drops late, incoming rises early; good for beat-matched blends. */
    SMOOTH_STEP,
}

/** Gain pair for a progress value in 0..1. */
data class Gains(val outgoing: Float, val incoming: Float)

object Crossfade {
    fun gains(progress: Float, curve: CrossfadeCurve = CrossfadeCurve.EQUAL_POWER): Gains {
        val t = progress.coerceIn(0f, 1f)
        return when (curve) {
            CrossfadeCurve.LINEAR -> Gains(1f - t, t)
            CrossfadeCurve.EQUAL_POWER -> {
                val a = t * (PI / 2).toFloat()
                Gains(cos(a), sin(a))
            }
            CrossfadeCurve.SMOOTH_STEP -> {
                val s = t * t * (3f - 2f * t)
                Gains(1f - s, s)
            }
        }
    }

    /**
     * Speed pair for a tempo-matched blend at [progress] in 0..1.
     * Incoming glides from [TempoMatcher.Plan.incomingStartSpeed] to 1.0; outgoing from 1.0 to
     * [TempoMatcher.Plan.outgoingEndSpeed]. Both are geometric interpolations so the
     * tempo ratio between the two stays constant throughout, keeping beats locked.
     */
    fun speeds(progress: Float, plan: TempoMatcher.Plan): Pair<Float, Float> {
        if (!plan.matched) return 1f to 1f
        val t = progress.coerceIn(0f, 1f)
        val incoming = geometricLerp(plan.incomingStartSpeed, 1f, t)
        val outgoing = geometricLerp(1f, plan.outgoingEndSpeed, t)
        return outgoing to incoming
    }

    private fun geometricLerp(a: Float, b: Float, t: Float): Float =
        (a.toDouble() * Math.pow((b / a).toDouble(), t.toDouble())).toFloat()
}

/** Everything the playback engine needs to execute one transition. */
data class TransitionPlan(
    /** Position on the outgoing track (ms) at which the blend starts. */
    val startAtOutgoingMs: Long,
    /** Position on the incoming track (ms) at which it starts playing. */
    val incomingStartMs: Long,
    val durationMs: Long,
    val curve: CrossfadeCurve,
    val tempo: TempoMatcher.Plan,
)

/** Inputs to [TransitionPlanner]: what we know about a track. */
data class TrackTempo(val durationMs: Long, val bpm: Float, val firstBeatSec: Float, val confidence: Float)

/**
 * Builds a [TransitionPlan] for a pair of tracks. Pure function so it can be
 * unit-tested away from ExoPlayer.
 */
object TransitionPlanner {
    fun plan(
        outgoing: TrackTempo,
        incoming: TrackTempo?,
        crossfadeMs: Long,
        curve: CrossfadeCurve = CrossfadeCurve.EQUAL_POWER,
        tempoMatch: Boolean = true,
        minConfidence: Float = 0.25f,
        maxStretch: Float = 0.08f,
    ): TransitionPlan {
        val duration = crossfadeMs.coerceIn(0L, outgoing.durationMs)
        val nominalStart = (outgoing.durationMs - duration).coerceAtLeast(0L)
        val canMatch = tempoMatch && incoming != null &&
            outgoing.confidence >= minConfidence && incoming.confidence >= minConfidence &&
            outgoing.bpm > 0f && incoming.bpm > 0f
        if (!canMatch) {
            return TransitionPlan(nominalStart, 0L, duration, curve, TempoMatcher.NONE)
        }
        val tempo = TempoMatcher.plan(outgoing.bpm, incoming!!.bpm, maxStretch)
        if (!tempo.matched) {
            return TransitionPlan(nominalStart, 0L, duration, curve, tempo)
        }
        // Snap the blend start to a beat of the outgoing track (search backwards so we never overrun the end).
        val beat = 60f / outgoing.bpm
        val nominalSec = nominalStart / 1000f
        val rel = nominalSec - outgoing.firstBeatSec
        val snappedSec = outgoing.firstBeatSec + kotlin.math.floor(rel / beat) * beat
        val startMs = (snappedSec * 1000f).toLong().coerceIn(0L, nominalStart)
        // Incoming starts on its first beat so the two grids line up at t=0 of the blend.
        val incomingStart = (incoming.firstBeatSec * 1000f).toLong().coerceAtLeast(0L)
        return TransitionPlan(startMs, incomingStart, duration, curve, tempo)
    }
}
