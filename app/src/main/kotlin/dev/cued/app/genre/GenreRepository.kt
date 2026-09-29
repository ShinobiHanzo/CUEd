package dev.cued.app.genre

import android.content.Context
import android.net.Uri
import dev.cued.app.data.Settings
import dev.cued.app.data.db.CuedDatabase
import dev.cued.core.genre.GenreNormalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps genre labels correct and consistent:
 *  - [refreshFromTags]: re-read the file tag for tracks (all, or only unlabelled) and normalise it,
 *  - [tidyLabels]: normalise every existing label and merge duplicates,
 *  - [lookupOnline]: MusicBrainz for tracks that still have nothing (opt-in).
 * Tracks whose genres were edited by hand are locked and never touched.
 */
class GenreRepository(
    private val context: Context,
    private val db: CuedDatabase,
    private val settings: Settings,
    private val scope: CoroutineScope,
    appVersion: String,
) {
    private val reader = TagGenreReader(context)
    private val mb = MusicBrainzClient("CUEd/$appVersion (https://github.com/ShinobiHanzo/CUEd)")

    data class Progress(val phase: String, val done: Int, val total: Int, val labelled: Int, val running: Boolean)
    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress
    private var job: Job? = null

    val unlabelled: Flow<List<dev.cued.app.data.db.TrackEntity>> = db.tracks().observeUnlabelled()
    val unlabelledCount: Flow<Int> = db.tracks().observeUnlabelledCount()

    /** Re-read tags. [onlyMissing] = only tracks with no labels; otherwise every unlocked music track. */
    fun refreshFromTagsAsync(onlyMissing: Boolean) = start("Reading tags") { refreshFromTags(onlyMissing) }

    /** Normalise and merge existing labels on unlocked tracks (no file access). */
    fun tidyLabelsAsync() = start("Tidying labels") { tidyLabels() }

    /** MusicBrainz for still-unlabelled tracks. Honours the online setting. */
    fun lookupOnlineAsync() = start("MusicBrainz") { lookupOnline() }

    fun cancel() { job?.cancel(); _progress.value = _progress.value?.copy(running = false) }

    private fun start(phase: String, block: suspend () -> Unit) {
        if (job?.isActive == true) return
        job = scope.launch {
            _progress.value = Progress(phase, 0, 0, 0, running = true)
            runCatching { block() }
            _progress.value = _progress.value?.copy(running = false)
        }
    }

    suspend fun refreshFromTags(onlyMissing: Boolean) = withContext(Dispatchers.IO) {
        val ids = if (onlyMissing) db.tracks().unlabelledMusicIds() else db.tracks().unlockedMusicIds()
        var labelled = 0
        _progress.value = Progress("Reading tags", 0, ids.size, 0, true)
        for ((i, id) in ids.withIndex()) {
            val t = db.tracks().byId(id) ?: continue
            if (!t.genresLocked) {
                val genres = reader.read(Uri.parse(t.uri))
                if (genres.isNotEmpty()) { db.tracks().setGenres(id, genres); labelled++ }
                else if (!onlyMissing) {
                    // Nothing in the tag: keep whatever was there, but tidy it.
                    val existing = db.tracks().genresOf(id).flatMap { GenreNormalizer.normalize(it) }.distinct()
                    if (existing.isNotEmpty()) db.tracks().setGenres(id, existing)
                }
            }
            _progress.value = Progress("Reading tags", i + 1, ids.size, labelled, true)
        }
    }

    suspend fun tidyLabels() = withContext(Dispatchers.IO) {
        val rows = db.tracks().allGenreRows().groupBy({ it.trackId }, { it.genre })
        val locked = db.tracks().lockedIds().toSet()
        var changed = 0
        _progress.value = Progress("Tidying labels", 0, rows.size, 0, true)
        for ((i, e) in rows.entries.withIndex()) {
            val (id, labels) = e
            if (id !in locked) {
                val clean = labels.flatMap { GenreNormalizer.normalize(it) }.distinct()
                if (clean != labels) { db.tracks().setGenres(id, clean); changed++ }
            }
            _progress.value = Progress("Tidying labels", i + 1, rows.size, changed, true)
        }
    }

    suspend fun lookupOnline() {
        if (!settings.genresNow().online) return
        val ids = db.tracks().unlabelledMusicIds()
        var labelled = 0
        _progress.value = Progress("MusicBrainz", 0, ids.size, 0, true)
        for ((i, id) in ids.withIndex()) {
            val t = db.tracks().byId(id) ?: continue
            if (!t.genresLocked && t.artist != "Unknown artist") {
                val genres = runCatching { mb.genresFor(t.artist, t.title) }.getOrDefault(emptyList())
                if (genres.isNotEmpty()) { db.tracks().setGenres(id, genres); labelled++ }
                delay(MusicBrainzClient.RATE_MS)
            }
            _progress.value = Progress("MusicBrainz", i + 1, ids.size, labelled, true)
        }
    }
}
