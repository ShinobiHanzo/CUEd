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
import androidx.compose.material3.MaterialTheme
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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionHeader("Crossfade", "How the next track blends in")
        LabeledSlider("Duration", "${pb.crossfadeMs / 1000}s" + if (pb.crossfadeMs == 0L) " (off, gapless)" else "", pb.crossfadeMs / 1000f, 0f..20f, 19) { vm.setCrossfadeMs((it * 1000).toLong()) }
        Text("Curve", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            CrossfadeCurve.entries.forEachIndexed { i, c ->
                SegmentedButton(selected = pb.curve == c, onClick = { vm.setCurve(c) }, shape = SegmentedButtonDefaults.itemShape(i, CrossfadeCurve.entries.size)) {
                    Text(when (c) { CrossfadeCurve.EQUAL_POWER -> "Equal power"; CrossfadeCurve.LINEAR -> "Linear"; CrossfadeCurve.SMOOTH_STEP -> "Smooth" }, maxLines = 1)
                }
            }
        }

        SectionHeader("Tempo match", "Closest-common-factor beat matching during the blend")
        ToggleRow("Tempo-match crossfades", "Lines the incoming beat grid up with the outgoing one when their tempos relate by 1:1, 2:1, 3:2, 4:3…", pb.tempoMatch) { vm.setTempoMatch(it) }
        LabeledSlider("Max stretch", "±${pb.maxStretchPercent.toInt()}%", pb.maxStretchPercent, 1f..16f, 14, enabled = pb.tempoMatch) { vm.setMaxStretch(it) }
        LabeledSlider("Min BPM confidence", "${(pb.minBpmConfidence * 100).toInt()}%", pb.minBpmConfidence, 0f..0.9f, 8, enabled = pb.tempoMatch) { vm.setMinConfidence(it) }
        Text("Beyond the max stretch the blend falls back to a plain crossfade; time-stretch artefacts get audible past ~8%.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = Muted)

        SectionHeader("Scrubber", "Song-position control style")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            ScrubberMode.entries.forEachIndexed { i, m ->
                SegmentedButton(selected = ui.scrubberMode == m, onClick = { vm.setScrubberMode(m) }, shape = SegmentedButtonDefaults.itemShape(i, ScrubberMode.entries.size)) {
                    Text(when (m) { ScrubberMode.STANDARD -> "Bar"; ScrubberMode.STATIC_SPECTROGRAM -> "Static"; ScrubberMode.REACTIVE_SPECTROGRAM -> "Live" }, maxLines = 1)
                }
            }
        }
        LabeledSlider("Visual delay", "${ui.visualDelayMs} ms", ui.visualDelayMs.toFloat(), 0f..400f, 39) { vm.setVisualDelay(it.toInt()) }
        Text("The live spectrograph taps audio before it reaches the speaker; raise this if the bars lead the sound (Bluetooth adds a lot).", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = Muted)

        SectionHeader("Library", if (pending > 0) "$pending track(s) waiting for analysis" else "Tempo + spectrogram analysis runs on-device, one track at a time")
        Row(Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = { vm.rescan() }) { Text("Rescan music") }
            TextButton(onClick = { vm.analyseAll() }) { Text("Analyse all tracks") }
        }

        SectionHeader("Receive a share", "Scan a CUEd QR or tap phones")
        TextButton(onClick = onOpenReceive, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Open receiver") }

        SectionHeader("About", "CUEd: offline music player with spectrograph, crossfade and local sharing. No accounts, no telemetry, no models: every recommendation is a rule you can read in the source.")
        Spacer(Modifier.height(96.dp))
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
