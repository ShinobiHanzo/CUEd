package dev.cued.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cued.app.ui.LedgerPeriod
import dev.cued.app.ui.LedgerViewModel
import dev.cued.app.ui.components.EmptyHint
import dev.cued.app.ui.components.SectionHeader
import dev.cued.app.ui.theme.Ink3
import dev.cued.app.ui.theme.Muted
import dev.cued.app.ui.theme.Teal
import dev.cued.core.ledger.Ledger
import dev.cued.core.ledger.LedgerEntry
import dev.cued.core.ledger.LedgerSummary
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Your listening ledger: what the play log says about a period, computed on the
 * phone. Spotify shows you this once a year from its servers; here it is any
 * day, for any period, from data that never left the device, exportable as a
 * file you own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerScreen(vm: LedgerViewModel, onBack: () -> Unit, onMessage: (String) -> Unit) {
    val period by vm.period.collectAsState()
    val summary by vm.summary.collectAsState()
    val loading by vm.loading.collectAsState()
    val message by vm.message.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmForget by remember { mutableStateOf(false) }

    LaunchedEffect(message) { message?.let { onMessage(it); vm.message.value = null } }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Your ledger") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            actions = {
                IconButton(onClick = { scope.launch { vm.exportIntent()?.let { context.startActivity(android.content.Intent.createChooser(it, "Export ledger")) } ?: onMessage("Nothing to export yet") } }) { Icon(Icons.Default.Share, contentDescription = "Export JSON") }
                IconButton(onClick = { confirmForget = true }) { Icon(Icons.Default.DeleteSweep, contentDescription = "Forget the play log") }
            },
        )
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                LazyRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(LedgerPeriod.entries) { p -> FilterChip(selected = period == p, onClick = { vm.setPeriod(p) }, label = { Text(p.label) }) }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            }
            val s = summary
            if (s == null) {
                item { EmptyHint("Counting…") }
            } else if (s.plays == 0) {
                item { EmptyHint("No plays in this period. Everything you play from now on is counted here, on the phone only.") }
            } else {
                item { Tiles(s) }
                item { SectionHeader("By hour of day", "When you listen") }
                item { Bars(s.byHour, (0 until 24).map { if (it % 3 == 0) it.toString() else "" }) }
                item { SectionHeader("By weekday") }
                item { Bars(s.byWeekday, listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")) }
                if (s.byDay.size > 1) {
                    item { SectionHeader("Day by day", "${s.byDay.size} active days · busiest ${s.peakDay?.let { Ledger.dayLabel(it) } ?: ""} (${s.peakDayPlays} plays)") }
                    item { Bars(s.byDay.map { it[1].toInt() }, s.byDay.map { dayLabel(it[0]) }, dense = true) }
                }
                top("Top artists", s.topArtists, this)
                top("Top tracks", s.topTracks, this)
                top("Top albums", s.topAlbums, this)
                top("Top genres", s.topGenres, this)
                item {
                    Text("Counted on this phone from its play log. A play that ended before half the track counts as a skip. Export gives you the summary and every play as `cued-ledger/1` JSON; the desktop client exports the same shape, so the two can be merged by hand.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
        }
    }

    if (confirmForget) AlertDialog(
        onDismissRequest = { confirmForget = false },
        title = { Text("Forget the play log?") },
        text = { Text("Every recorded play on this phone is deleted. Play counts on tracks stay. This cannot be undone.") },
        confirmButton = { TextButton(onClick = { confirmForget = false; vm.forgetAll() }) { Text("Forget") } },
        dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Keep") } },
    )
}

private fun dayLabel(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofPattern("d/M"))

private fun top(title: String, entries: List<LedgerEntry>, scope: androidx.compose.foundation.lazy.LazyListScope) {
    if (entries.isEmpty()) return
    scope.item { SectionHeader(title) }
    scope.items(entries.take(10)) { e ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(e.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (e.sub.isNotBlank()) Text(e.sub, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Text("${e.plays} plays", style = MaterialTheme.typography.labelMedium, color = Teal)
            Spacer(Modifier.width(8.dp))
            Text(Ledger.formatHours(e.listenedMs), style = MaterialTheme.typography.labelMedium, color = Muted)
        }
    }
}

@Composable
private fun Tiles(s: LedgerSummary) {
    val tiles = listOf(
        "Plays" to s.plays.toString(), "Listening" to Ledger.formatHours(s.listenedMs), "Tracks" to s.uniqueTracks.toString(), "Artists" to s.uniqueArtists.toString(),
        "New to you" to s.discoveries.toString(), "Active days" to s.activeDays.toString(), "Longest streak" to "${s.longestStreakDays} d", "Current streak" to "${s.currentStreakDays} d",
        "Skip rate" to "${(s.skipRate * 100).toInt()}%", "Skips" to s.skips.toString(),
    )
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        tiles.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (label, value) ->
                    Surface(Modifier.weight(1f), shape = RoundedCornerShape(12.dp), color = Ink3) {
                        Column(Modifier.padding(12.dp)) {
                            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                            Text(label, style = MaterialTheme.typography.labelMedium, color = Muted)
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Bars(values: List<Int>, labels: List<String>, dense: Boolean = false) {
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Canvas(Modifier.fillMaxWidth().height(if (dense) 72.dp else 96.dp)) {
            val n = values.size.coerceAtLeast(1)
            val gap = if (dense) 1.dp.toPx() else 3.dp.toPx()
            val w = (size.width - gap * (n - 1)) / n
            values.forEachIndexed { i, v ->
                val h = size.height * v / max
                drawRoundRect(color = if (v == max && v > 0) Teal else Teal.copy(alpha = 0.55f), topLeft = Offset(i * (w + gap), size.height - h), size = Size(w, h.coerceAtLeast(1f)), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
            }
        }
        if (!dense) Row(Modifier.fillMaxWidth()) { labels.forEach { l -> Text(l, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 1) } }
        else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(labels.firstOrNull() ?: "", style = MaterialTheme.typography.labelSmall, color = Muted); Text(labels.lastOrNull() ?: "", style = MaterialTheme.typography.labelSmall, color = Muted) }
    }
}

@Suppress("unused")
private val transparent = Color.Transparent
