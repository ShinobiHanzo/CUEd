package dev.cued.core

import dev.cued.core.model.TrackStats
import dev.cued.core.reco.PlayEvent
import dev.cued.core.reco.Recommender
import dev.cued.core.reco.SmartLists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecoTest {
    private val now = 1_800_000_000_000L
    private val day = SmartLists.DAY_MS

    private fun t(id: Long, genres: Set<String>, artist: String = "a$id", bpm: Float? = null, plays: Int = 0, last: Long? = null, added: Long = now - 100 * day, fav: Boolean = false) =
        TrackStats(id, "t$id", artist, genres, bpm, plays, last, added, fav)

    @Test
    fun `trending prefers recent plays`() {
        val tracks = listOf(t(1, setOf("house")), t(2, setOf("dnb")), t(3, setOf("jazz")))
        val plays = listOf(
            PlayEvent(1, now - 30 * day), PlayEvent(1, now - 30 * day), PlayEvent(1, now - 30 * day),
            PlayEvent(2, now - 1 * day), PlayEvent(2, now),
        )
        val tr = SmartLists.trending(tracks, plays, now)
        assertEquals(listOf(2L, 1L), tr.map { it.id })
    }

    @Test
    fun `unplayed and forgotten`() {
        val tracks = listOf(
            t(1, setOf("house"), plays = 0, added = now - 10 * day),
            t(2, setOf("house"), plays = 5, last = now - 90 * day),
            t(3, setOf("house"), plays = 5, last = now - 2 * day),
            t(4, setOf("house"), plays = 1, last = now - 90 * day),
        )
        assertEquals(listOf(1L), SmartLists.unplayed(tracks).map { it.id })
        assertEquals(listOf(2L), SmartLists.forgotten(tracks, now).map { it.id })
    }

    @Test
    fun `recommendations follow genre affinity and tempo`() {
        val tracks = listOf(
            t(1, setOf("house"), bpm = 124f, plays = 10, last = now - day),
            t(2, setOf("house"), bpm = 126f),
            t(3, setOf("jazz"), bpm = 90f),
            t(4, setOf("house"), bpm = 62f), // half-tempo house, still fits
        )
        val plays = (1..10).map { PlayEvent(1, now - it * day) }
        val rec = Recommender().recommend(tracks, plays, now = now)
        val ids = rec.map { it.track.id }
        assertTrue(ids.indexOf(2L) < ids.indexOf(3L), "house should beat jazz: $ids")
        assertTrue(rec.first { it.track.id == 4L }.reasons.contains("tempo fits"))
    }

    @Test
    fun `seeded recommendations share genre`() {
        val seed = t(1, setOf("techno"), artist = "x", bpm = 130f)
        val tracks = listOf(seed, t(2, setOf("techno"), artist = "y", bpm = 131f), t(3, setOf("folk"), bpm = 80f))
        val rec = Recommender().recommend(tracks, emptyList(), now = now, seed = seed)
        assertEquals(2L, rec.first().track.id)
        assertTrue(rec.none { it.track.id == 1L })
    }
}
