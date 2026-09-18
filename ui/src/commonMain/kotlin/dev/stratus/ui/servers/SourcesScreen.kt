package dev.stratus.ui.servers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stratus.core.backup.MediaAccess
import dev.stratus.core.backup.MediaSource

/**
 * Which folders feed one server.
 *
 * **"Everything" and "these ones" are a choice, not an empty set.** Stored, an
 * empty selection means every source; offered as a list of tick boxes it could
 * just as easily mean none, and a value that means two opposite things is the
 * kind of ambiguity that is eventually read the wrong way. So the screen asks
 * the question directly, and refuses to save a narrowed choice with nothing in
 * it -- backing nothing up is what turning the server off is for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    available: List<MediaSource>,
    access: MediaAccess,
    chosen: Set<String>,
    onSave: (Set<String>) -> Unit,
    onClose: () -> Unit,
) {
    var everything by remember { mutableStateOf(chosen.isEmpty()) }
    var picked by remember { mutableStateOf(chosen) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Folders") },
                navigationIcon = { TextButton(onClick = onClose) { Text("Back") } },
                actions = {
                    TextButton(
                        onClick = { onSave(if (everything) emptySet() else picked) },
                        enabled = everything || picked.isNotEmpty(),
                    ) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            if (access == MediaAccess.None) {
                // Not an empty list: a phone with no photographs and a phone
                // that will not show them look identical, and only one of them
                // is something somebody can fix.
                Text(
                    "Stratus cannot read your photographs on this device yet, so " +
                        "there are no folders to choose from. On iOS the photo library " +
                        "is not wired up at all; on Android this means the permission " +
                        "was refused.",
                )
                return@Column
            }

            Row(
                Modifier.fillMaxWidth().clickable { everything = true },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = everything, onClick = { everything = true })
                Text("Every folder")
            }
            Row(
                Modifier.fillMaxWidth().clickable { everything = false },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = !everything, onClick = { everything = false })
                Text("Only the ones I choose")
            }

            LazyColumn(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(available, key = { it.id }) { source ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !everything) {
                            picked = if (source.id in picked) picked - source.id else picked + source.id
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = everything || source.id in picked,
                            enabled = !everything,
                            onCheckedChange = { checked ->
                                picked = if (checked) picked + source.id else picked - source.id
                            },
                        )
                        Text("${source.label} (${source.count})")
                    }
                }
            }
        }
    }
}
