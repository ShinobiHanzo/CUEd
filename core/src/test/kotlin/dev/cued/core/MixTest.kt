package dev.cued.core

import dev.cued.core.mix.Crossfade
import dev.cued.core.mix.CrossfadeCurve
import dev.cued.core.mix.TempoMatcher
import dev.cued.core.mix.TrackTempo
import dev.cued.core.mix.TransitionPlanner
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MixTest {
    @Test
    fun `equal power crossfade keeps power constant`() {
        for (i in 0..10) {
            val g = Crossfade.gains(i / 10f, CrossfadeCurve.EQUAL_POWER)
            assertTrue(abs(g.outgoing * g.outgoing + g.incoming * g.incoming - 1f) < 1e-4f)
        }
        assertEquals(1f, Crossfade.gains(0f).outgoing)
        assertEquals(1f, Crossfade.gains(1f).incoming)
    }

    @Test
    fun `half tempo track matches via 2 to 1 ratio`() {
        val p = TempoMatcher.plan(outgoingBpm = 170f, incomingBpm = 86f)
        assertTrue(p.matched)
        assertEquals(2, p.ratioNum); assertEquals(1, p.ratioDen)
        // 86*2 = 172, so incoming needs to slow by 170/172
        assertTrue(abs(p.incomingStartSpeed - 170f / 172f) < 1e-4f)
    }

    @Test
    fun `three to two relation is recognised`() {
        val p = TempoMatcher.plan(outgoingBpm = 144f, incomingBpm = 97f)
        assertTrue(p.matched)
        assertEquals(3, p.ratioNum); assertEquals(2, p.ratioDen)
    }

    @Test
    fun `too far apart falls back to plain crossfade`() {
        val p = TempoMatcher.plan(outgoingBpm = 120f, incomingBpm = 138f)
        assertFalse(p.matched)
        assertEquals(1f, p.incomingStartSpeed)
    }

    @Test
    fun `speeds glide to native tempo and keep the ratio`() {
        val p = TempoMatcher.plan(120f, 124f)
        val (o0, i0) = Crossfade.speeds(0f, p)
        val (o1, i1) = Crossfade.speeds(1f, p)
        assertEquals(1f, o0, 1e-5f); assertEquals(p.incomingStartSpeed, i0, 1e-5f)
        assertEquals(p.outgoingEndSpeed, o1, 1e-5f); assertEquals(1f, i1, 1e-5f)
        for (k in 0..10) {
            val (o, i) = Crossfade.speeds(k / 10f, p)
            // outgoing tempo 120*o should equal incoming tempo 124*i throughout
            assertEquals(120f * o, 124f * i, 0.01f)
        }
    }

    @Test
    fun `transition snaps to a beat and starts incoming on its first beat`() {
        val out = TrackTempo(durationMs = 200_000, bpm = 120f, firstBeatSec = 0.1f, confidence = 0.9f)
        val inc = TrackTempo(durationMs = 180_000, bpm = 122f, firstBeatSec = 0.4f, confidence = 0.9f)
        val plan = TransitionPlanner.plan(out, inc, crossfadeMs = 8_000)
        assertTrue(plan.tempo.matched)
        assertEquals(400L, plan.incomingStartMs)
        assertTrue(plan.startAtOutgoingMs <= 192_000)
        // on the grid: (start - 100ms) divisible by 500ms
        assertEquals(0L, (plan.startAtOutgoingMs - 100) % 500)
        assertEquals(8_000L, plan.durationMs)
    }

    @Test
    fun `low confidence disables tempo match`() {
        val out = TrackTempo(200_000, 120f, 0f, 0.1f)
        val inc = TrackTempo(180_000, 121f, 0f, 0.9f)
        val plan = TransitionPlanner.plan(out, inc, 5_000)
        assertFalse(plan.tempo.matched)
        assertEquals(195_000L, plan.startAtOutgoingMs)
    }

    @Test
    fun `seconds to next beat`() {
        val s = TempoMatcher.secondsToNextBeat(positionSec = 10.3f, firstBeatSec = 0f, bpm = 120f)
        assertEquals(0.2f, s, 1e-4f)
        val bar = TempoMatcher.secondsToNextBeat(10.3f, 0f, 120f, beatsPerBar = 4)
        assertEquals(1.7f, bar, 1e-4f)
    }
}
