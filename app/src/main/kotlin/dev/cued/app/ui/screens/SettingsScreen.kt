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
