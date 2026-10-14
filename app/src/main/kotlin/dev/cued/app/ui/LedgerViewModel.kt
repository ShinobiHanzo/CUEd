package dev.cued.app.ui

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.cued.app.Graph
import dev.cued.core.ledger.Ledger
import dev.cued.core.ledger.LedgerPlay
import dev.cued.core.ledger.LedgerSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId

/** Period presets for the ledger screen. */
enum class LedgerPeriod(val label: String) { THIS_MONTH("This month"), LAST_MONTH("Last month"), DAYS_30("30 days"), DAYS_7("7 days"), THIS_YEAR("This year"), ALL("All time") }

/**
 * Your listening ledger: the play log summarised on the phone. Nothing is sent
 * anywhere; Export writes a `cued-ledger/1` JSON file and hands it to the share
 * sheet, the same shape the desktop exports.
 */
class LedgerViewModel(private val graph: Graph) : ViewModel() {
    val period = MutableStateFlow(LedgerPeriod.THIS_MONTH)
    private val _summary = MutableStateFlow<LedgerSummary?>(null)
    val summary: StateFlow<LedgerSummary?> = _summary
    val loading = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    private var plays: List<LedgerPlay> = emptyList()

    init { refresh() }

    fun setPeriod(p: LedgerPeriod) { period.value = p; refresh() }

    fun refresh() {
        viewModelScope.launch {
            loading.value = true
            plays = withContext(Dispatchers.IO) { loadPlays() }
            _summary.value = withContext(Dispatchers.Default) { summarise(plays) }
            loading.value = false
        }
    }

    private suspend fun loadPlays(): List<LedgerPlay> {
        val db = graph.db
        val events = db.playEvents().since(0L)
        val tracks = db.tracks().allIncludingMissing().associateBy { it.id }
        val genres = db.tracks().allGenreRows().groupBy({ it.trackId }, { it.genre })
        return events.mapNotNull { e ->
            val t = tracks[e.trackId] ?: return@mapNotNull null
            LedgerPlay(trackKey = e.trackId.toString(), at = e.at, skipped = e.skipped, title = t.title, artist = t.artist, album = t.album, durationMs = t.durationMs, genres = genres[e.trackId] ?: emptyList(), addedAt = t.addedAt)
        }
    }

    private fun range(now: Long, zone: ZoneId): Pair<Long, Long> = when (period.value) {
        LedgerPeriod.THIS_MONTH -> Ledger.thisMonth(now, zone)
        LedgerPeriod.LAST_MONTH -> Ledger.lastMonth(now, zone)
        LedgerPeriod.DAYS_30 -> Ledger.lastDays(now, 30, zone)
        LedgerPeriod.DAYS_7 -> Ledger.lastDays(now, 7, zone)
        LedgerPeriod.THIS_YEAR -> Ledger.thisYear(now, zone)
        LedgerPeriod.ALL -> 0L to Long.MAX_VALUE / 4
    }

    private fun summarise(all: List<LedgerPlay>): LedgerSummary {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val (from, to) = range(now, zone)
        return Ledger.summarise(all, from, to, zone, now = now)
    }

    /** Writes the export to the cache folder and returns a share intent for it. */
    suspend fun exportIntent(): Intent? = withContext(Dispatchers.IO) {
        val s = _summary.value ?: return@withContext null
        val dir = File(graph.app.cacheDir, "ledger").apply { mkdirs() }
        val f = File(dir, "cued-ledger.json")
        val inRange = plays.filter { it.at >= s.fromMs && it.at < s.toMs }
        f.writeText(Ledger.export(s, inRange))
        val uri = FileProvider.getUriForFile(graph.app, graph.app.packageName + ".files", f)
        Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "CUEd listening ledger")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Deletes the play log (the ledger's source). Play counts on tracks are left alone. */
    fun forgetAll() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { graph.db.playEvents().deleteAll() }
            message.value = "Play log cleared"
            refresh()
        }
    }

    suspend fun friendsCount(): Int = graph.desktop.store.friends.first().size
}
