package dev.cued.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.cued.app.ui.theme.Muted
import dev.cued.app.ui.theme.Teal
import dev.cued.core.changelog.Changelog

/**
 * Patch notes after an update: the changelog sections between the version
 * last run and this one, drawn without a markdown library (headings, bullets,
 * paragraphs). Also used from Settings → Updates → What's new.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewSheet(title: String, markdown: String, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val lines = remember(markdown) { Changelog.lines(markdown) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Everything stays on your phone; these notes shipped inside the app.", style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
            LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
                items(lines) { line ->
                    when (line) {
                        is Changelog.Line.Heading -> Text(
                            if (line.text.firstOrNull()?.isDigit() == true) "Version ${line.text}" else line.text,
                            style = MaterialTheme.typography.titleMedium, color = Teal, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                        )
                        is Changelog.Line.Bullet -> Row(Modifier.padding(vertical = 3.dp)) {
                            Text("•  ", color = Teal)
                            Text(line.text, style = MaterialTheme.typography.bodyMedium)
                        }
                        is Changelog.Line.Para -> Text(line.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 3.dp))
                    }
                }
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) { Text("Got it") }
        }
    }
}
