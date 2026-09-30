package dev.cued.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalContext
import dev.cued.app.update.UpdateManager
import dev.cued.app.ui.theme.Teal
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.cued.app.data.ScrubberMode
import dev.cued.app.ui.SettingsViewModel
import dev.cued.app.ui.components.SectionHeader
import dev.cued.app.ui.theme.Muted
import dev.cued.core.mix.CrossfadeCurve

@Composable
fun SettingsScreen(vm: SettingsViewModel, onOpenReceive: () -> Unit) {
    val pb by vm.playback.collectAsState()
    val ui by vm.ui.collectAsState()
    val pending by vm.analysisPending.collectAsState()
    val ly by vm.lyrics.collectAsState()
    val bulk by vm.lyricsBulk.collectAsState()
    val lyricsCount by vm.lyricsCount.collectAsState()
    val gs by vm.genreSettings.collectAsState()
    val genreProgress by vm.genreProgress.collectAsState()
    val unlabelledCount by vm.unlabelledCount.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionHeader("Crossfade", "Standard volume blend between tracks")
        ToggleRow("Crossfade", "Off = gapless cut", pb.crossfadeEnabled) { vm.setCrossfadeEnabled(it) }
        LabeledSlider("Duration", "${pb.crossfadeMs / 1000}s", pb.crossfadeMs / 1000f, 1f..20f, 18, enabled = pb.crossfadeEnabled) { vm.setCrossfadeMs((it * 1000).toLong()) }
        Text("Curve", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            CrossfadeCurve.entries.forEachIndexed { i, c ->
                SegmentedButton(selected = pb.curve == c, onClick = { vm.setCurve(c) }, enabled = pb.crossfadeEnabled, shape = SegmentedButtonDefaults.itemShape(i, CrossfadeCurve.entries.size)) {
                    Text(when (c) { CrossfadeCurve.EQUAL_POWER -> "Equal power"; CrossfadeCurve.LINEAR -> "Linear"; CrossfadeCurve.SMOOTH_STEP -> "Smooth" }, maxLines = 1)
                }
            }
        }

        SectionHeader("Tempo-match crossfade", "Separate mode: beat-aligned, speed-matched blend. Off by default.")
        ToggleRow("Tempo-match crossfade", "Lines the incoming beat grid up with the outgoing one when their tempos relate by 1:1, 2:1, 3:2, 4:3… Needs both tracks analysed.", pb.tempoMatch) { vm.setTempoMatch(it) }
        LabeledSlider("Blend length", "${pb.tempoMatchMs / 1000}s", pb.tempoMatchMs / 1000f, 2f..32f, 29, enabled = pb.tempoMatch) { vm.setTempoMatchMs((it * 1000).toLong()) }
        LabeledSlider("Max stretch", "±${pb.maxStretchPercent.toInt()}%", pb.maxStretchPercent, 1f..16f, 14, enabled = pb.tempoMatch) { vm.setMaxStretch(it) }
        LabeledSlider("Min BPM confidence", "${(pb.minBpmConfidence * 100).toInt()}%", pb.minBpmConfidence, 0f..0.9f, 8, enabled = pb.tempoMatch) { vm.setMinConfidence(it) }
        Text(
            "When the two tracks don't share a simple tempo ratio within the max stretch, the blend falls back to the standard crossfade above (if on) or a gapless cut. Time-stretch artefacts get audible past ~8%.",
            Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = Muted,
        )

        SectionHeader("Silence", "Skip the quiet bits")
        ToggleRow("Skip silence", "Cuts long quiet stretches (silent intros, outros, hidden-track gaps) as the track plays. Applies instantly.", pb.skipSilence) { vm.setSkipSilence(it) }
        LabeledSlider("Threshold", "${pb.silenceThresholdDb.toInt()} dBFS", pb.silenceThresholdDb, -80f..-20f, 11, enabled = pb.skipSilence) { vm.setSilenceThresholdDb(it) }
        LabeledSlider("Tolerate up to", "${pb.silenceToleranceMs} ms", pb.silenceToleranceMs.toFloat(), 100f..3000f, 28, enabled = pb.skipSilence) { vm.setSilenceToleranceMs(it.toInt()) }
        Text("Quiet stretches shorter than the tolerance are kept so phrases still breathe. Raise the threshold (towards -20) to be more aggressive; lower it (towards -80) if fade-outs get chopped.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = Muted)

        SectionHeader("Scrubber", "Song-position control style")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            ScrubberMode.entries.forEachIndexed { i, m ->
                SegmentedButton(selected = ui.scrubberMode == m, onClick = { vm.setScrubberMode(m) }, shape = SegmentedButtonDefaults.itemShape(i, ScrubberMode.entries.size)) {
                    Text(when (m) { ScrubberMode.STANDARD -> "Bar"; ScrubberMode.REACTIVE_SPECTROGRAM -> "Live spectrograph" }, maxLines = 1)
                }
            }
        }
        LabeledSlider("Visual delay", "${ui.visualDelayMs} ms", ui.visualDelayMs.toFloat(), 0f..400f, 39) { vm.setVisualDelay(it.toInt()) }
        Text("The live spectrograph taps audio before it reaches the speaker; raise this if the bars lead the sound (Bluetooth adds a lot).", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = Muted)

        SectionHeader("Genres", if (unlabelledCount > 0) "$unlabelledCount music track(s) have no genre yet" else "Every music track has a genre")
        Text(
            "Labels come from the file's own genre tag (spotdl writes Spotify's genres there), normalised so \"Hip-Hop\", \"hiphop\" and \"(7)\" all become \"hip hop\". Tracks you edit by hand are locked and never re-labelled automatically.",
            Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = Muted,
        )
        val gp = genreProgress
        if (gp?.running == true) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.cancelGenres() }) { Text("Stop") }
                Text("${gp.phase}: ${gp.done}/${gp.total}  ·  ${gp.labelled} labelled", color = Muted)
            }
        } else {
            Row(Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = { vm.readTagsMissing() }) { Text("Label unlabelled from tags") }
                TextButton(onClick = { vm.readTagsAll() }) { Text("Re-read all tags") }
                TextButton(onClick = { vm.tidyGenres() }) { Text("Tidy labels") }
            }
            gp?.let { Text("last run: ${it.phase}, ${it.labelled} of ${it.total} changed", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = Muted) }
        }
        ToggleRow("Look up missing genres on MusicBrainz", "Open data, no account. Only for tracks whose files carry no genre at all; one request a second by their rules. Sends artist and title.", gs.online) { vm.setGenresOnline(it) }
        if (gs.online && gp?.running != true) TextButton(onClick = { vm.lookupGenresOnline() }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Look up unlabelled tracks now") }

        SectionHeader("Lyrics", "Embedded tag → .lrc beside the file → lrclib.net")
        ToggleRow("Fetch from lrclib.net", "Open, key-less lyrics database. The only host CUEd contacts outside your LAN, and only for tracks with nothing embedded.", ly.fetchOnline) { vm.setLyricsOnline(it) }
        ToggleRow("Look up automatically", "When a track starts playing. Off = only when you tap Lyrics in the player.", ly.autoFetch) { vm.setLyricsAuto(it) }
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val b = bulk
            if (b?.running == true) {
                TextButton(onClick = { vm.cancelBulkLyrics() }) { Text("Stop") }
                Text("${b.done}/${b.total}  ·  ${b.found} found", color = Muted)
            } else {
                TextButton(onClick = { vm.fetchAllLyrics() }) { Text("Download lyrics for whole library") }
                Text(if (b != null) "done: ${b.found} of ${b.total} found" else "$lyricsCount tracks have lyrics", color = Muted)
            }
        }

        SectionHeader("Library", if (pending > 0) "$pending track(s) waiting for analysis" else "Tempo + spectrogram analysis runs on-device, one track at a time")
        Row(Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = { vm.rescan() }) { Text("Rescan music") }
            TextButton(onClick = { vm.analyseAll() }) { Text("Analyse all tracks") }
        }

        SectionHeader("Receive a share", "Scan a CUEd QR or tap phones")
        TextButton(onClick = onOpenReceive, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Open receiver") }

        SectionHeader("Updates", "Straight from GitHub Releases: check, download, verify the SHA-256, install")
        UpdateBlock(vm)

        SectionHeader("Debug log", "Off by default. On: downloads, playback errors, updates and crashes are written to a private file you can share.")
        DebugBlock(vm)

        SectionHeader("About", "CUEd: offline music player with spectrograph, crossfade and local sharing. No accounts, no telemetry, no models: every recommendation is a rule you can read in the source.")
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun DebugBlock(vm: SettingsViewModel) {
    val on by vm.debugLog.collectAsState()
    val context = LocalContext.current
    var preview by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(horizontal = 16.dp)) {
        ToggleRow("Debug logging", "Nothing leaves the phone unless you share the file yourself.", on) { vm.setDebugLog(it) }
        Row {
            TextButton(onClick = { context.startActivity(android.content.Intent.createChooser(vm.logShareIntent(context), "Share debug log")) }) { Text("Share log") }
            TextButton(onClick = { preview = vm.logRecent() }) { Text("Show recent") }
            TextButton(onClick = { vm.clearLog(); preview = null }) { Text("Clear") }
        }
        Text("${vm.logSize() / 1024} KB on disk", style = MaterialTheme.typography.bodySmall, color = Muted)
        preview?.let { Text(it.ifBlank { "(empty)" }, style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 6.dp)) }
    }
}

@Composable
private fun UpdateBlock(vm: SettingsViewModel) {
    val st by vm.update.collectAsState()
    val us by vm.updateSettings.collectAsState()
    val context = LocalContext.current
    var token by remember(us.githubToken) { mutableStateOf(us.githubToken.orEmpty()) }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text("Installed: v${vm.currentVersion}", style = MaterialTheme.typography.labelLarge)
        when (val s = st) {
            UpdateManager.State.Idle -> TextButton(onClick = { vm.checkForUpdate() }) { Text("Check for updates") }
            UpdateManager.State.Checking -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text("  Checking GitHub…", color = Muted) }
            is UpdateManager.State.UpToDate -> Row(verticalAlignment = Alignment.CenterVertically) { Text("You're on the latest version.", color = Muted); TextButton(onClick = { vm.checkForUpdate() }) { Text("Check again") } }
            is UpdateManager.State.Available -> {
                Text("v${s.release.version} is available (${s.release.apkSize / 1024 / 1024} MB)", color = Teal, style = MaterialTheme.typography.titleSmall)
                if (s.release.notes.isNotBlank()) Text(s.release.notes.lines().take(12).joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(vertical = 4.dp))
                Row { Button(onClick = { vm.downloadUpdate() }) { Text("Download") }; TextButton(onClick = { vm.dismissUpdate() }) { Text("Later") } }
            }
            is UpdateManager.State.Downloading -> {
                Text("Downloading v${s.release.version}…", color = Muted)
                LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
            }
            is UpdateManager.State.Ready -> {
                Text("v${s.release.version} downloaded and verified.", color = Teal)
                if (vm.canInstall()) Button(onClick = { context.startActivity(vm.installIntent(s.file)) }) { Text("Install") }
                else Column {
                    Text("Android needs a one-time permission for CUEd to install updates.", style = MaterialTheme.typography.bodySmall, color = Muted)
                    Button(onClick = { context.startActivity(vm.unknownSourcesIntent()) }) { Text("Allow installs from CUEd") }
                }
            }
            is UpdateManager.State.Error -> {
                Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Row { TextButton(onClick = { vm.checkForUpdate() }) { Text("Retry") }; if (s.release != null) TextButton(onClick = { vm.downloadUpdate() }) { Text("Download again") } }
            }
        }
        ToggleRow("Check automatically", "Once a day when the app opens. One request to api.github.com.", us.autoCheck) { vm.setUpdatesAuto(it) }
        OutlinedTextField(value = token, onValueChange = { token = it }, singleLine = true, label = { Text("GitHub token (only while the repo is private)") }, modifier = Modifier.fillMaxWidth(),
            trailingIcon = { TextButton(onClick = { vm.setGithubToken(token) }) { Text("Save") } })
        Text("Manual downloads: ${UpdateManager.RELEASES_URL}", style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun LabeledSlider(label: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, steps: Int, enabled: Boolean = true, onChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth()) { Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge); Text(value, color = Muted) }
        Slider(value = current, onValueChange = onChange, valueRange = range, steps = steps, enabled = enabled)
    }
}

@Composable
private fun ToggleRow(label: String, help: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.labelLarge); Text(help, style = MaterialTheme.typography.bodySmall, color = Muted) }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
