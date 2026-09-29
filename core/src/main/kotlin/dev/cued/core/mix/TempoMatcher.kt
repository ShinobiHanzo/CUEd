package dev.cued.core.mix

import kotlin.math.abs
import kotlin.math.ln

/**
 * Tempo matching by "closest common factor".
 *
 * Two tracks rarely share a BPM, but they often share a *musical* relation:
 * 85 BPM hip-hop sits happily under 170 BPM drum & bass, and a 3:2 relation
 * (e.g. 96 and 144) still feels locked. Instead of forcing the incoming
 * track to the outgoing BPM, we search a small set of simple ratios and pick
 * the one that needs the least playback-speed change. If even the best ratio
 * needs more than [maxStretch] (default 8%) the plan falls back to a plain
 * crossfade, because time-stretch artefacts become audible past that.
 */
object TempoMatcher {

    /** Musically meaningful tempo ratios, expressed as (numerator, denominator). */
    val RATIOS: List<Pair<Int, Int>> = listOf(
        1 to 1, 2 to 1, 1 to 2, 3 to 2, 2 to 3, 4 to 3, 3 to 4, 3 to 1, 1 to 3,
    )

    data class Plan(
        /** Ratio applied to the incoming BPM so that incomingBpm * ratio ≈ outgoingBpm. */
        val ratioNum: Int,
        val ratioDen: Int,
        /** Playback speed for the incoming track at the start of the blend so its beat grid matches the outgoing one. */
        val incomingStartSpeed: Float,
        /** Playback speed for the outgoing track at the end of the blend (both end on the incoming track's native tempo). */
        val outgoingEndSpeed: Float,
        /** True if the stretch is within tolerance and tempo matching should be applied. */
        val matched: Boolean,
    ) {
        val ratio: Float get() = ratioNum.toFloat() / ratioDen
    }

    fun plan(outgoingBpm: Float, incomingBpm: Float, maxStretch: Float = 0.08f): Plan {
        if (outgoingBpm <= 0f || incomingBpm <= 0f) return NONE
        var best: Pair<Int, Int> = 1 to 1
        var bestCost = Float.MAX_VALUE
        for ((n, d) in RATIOS) {
            val effective = incomingBpm * n / d
            val cost = abs(ln(outgoingBpm / effective))
            if (cost < bestCost) { bestCost = cost; best = n to d }
        }
        val (n, d) = best
        val effectiveIncoming = incomingBpm * n / d
        val startSpeed = outgoingBpm / effectiveIncoming
        val stretch = abs(startSpeed - 1f)
        if (stretch > maxStretch) return Plan(n, d, 1f, 1f, matched = false)
        return Plan(n, d, startSpeed, 1f / startSpeed, matched = true)
    }

    /**
     * Seconds until the next beat boundary after [positionSec] on a grid defined by
     * [firstBeatSec] and [bpm]. [beatsPerBar] > 1 snaps to bar starts instead.
     */
    fun secondsToNextBeat(positionSec: Float, firstBeatSec: Float, bpm: Float, beatsPerBar: Int = 1): Float {
        if (bpm <= 0f) return 0f
        val unit = 60f / bpm * beatsPerBar
        val rel = positionSec - firstBeatSec
        val k = kotlin.math.ceil(rel / unit)
        val next = firstBeatSec + k * unit
        return (next - positionSec).coerceAtLeast(0f)
    }

    val NONE = Plan(1, 1, 1f, 1f, matched = false)
}
