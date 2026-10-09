package dev.stratus.ui.server

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
 * Which folders are backed up, which is also whether anything is
 * (stratus-app#131).
 *
 * **The set stored is the set ticked, and nothing is nothing.** It used to be
 * that an empty selection meant *every* source and a switch elsewhere turned
 * backup off -- two ways to say "not now", and an empty set that meant the
 * opposite of what a list of empty tick boxes looks like. There is no switch
 * now, so empty means what it looks like, and this screen is where backup is
 * turned on and off.
 *
 * What that costs is worth knowing: "every folder" is a button that ticks what
 * exists today rather than a standing instruction, so a folder made next month
 * is not backed up until somebody says so. The alternative was keeping a value
 * that means "whatever appears", which is the ambiguity this just removed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    available: List<MediaSource>,
    access: MediaAccess,
    onRequestAccess: (() -> Unit)?,
    chosen: Set<String>,
    onSave: (Set<String>) -> Unit,
    onClose: () -> Unit,
) {
    var picked by remember { mutableStateOf(chosen) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Folders") },
                navigationIcon = { TextButton(onClick = onClose) { Text("Back") } },
                actions = { TextButton(onClick = { onSave(picked) }) { Text("Save") } },
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
                        "has not been given.",
                )
                if (onRequestAccess != null) {
                    TextButton(onClick = onRequestAccess) { Text("Allow access to photos") }
                }
                return@Column
            }

            Text(
                if (picked.isEmpty()) {
                    "Nothing is backed up. Tick a folder to start."
                } else {
                    "${picked.size} of ${available.size} folders are backed up."
                },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { picked = available.mapTo(mutableSetOf()) { it.id } }) {
                    Text("Every folder")
                }
                TextButton(onClick = { picked = emptySet() }) { Text("None") }
            }

            LazyColumn(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(available, key = { it.id }) { source ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            picked = if (source.id in picked) picked - source.id else picked + source.id
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = source.id in picked,
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
