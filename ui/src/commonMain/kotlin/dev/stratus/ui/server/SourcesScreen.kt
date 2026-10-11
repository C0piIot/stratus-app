package dev.stratus.ui.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stratus.core.backup.MediaAccess
import dev.stratus.core.backup.MediaSource
import dev.stratus.ui.Footnote
import dev.stratus.ui.Glyph
import dev.stratus.ui.Page

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

    Page(
        "Folders",
        onBack = onClose,
        actions = { TextButton(onClick = { onSave(picked) }) { Text("Save") } },
        scroll = false,
    ) {
        // An if and not an early return, which is what this was until it
        // crashed the emulator suite at dispose. A `return@Page` out of a
        // composable is legal and the compiler balances the groups for it --
        // but it leaves and re-enters a branch holding two `SubcomposeLayout`s,
        // this page's Scaffold and the list below, every time the access
        // changes. That is the exact shape the slot table was found corrupted
        // in before (stratus-app#85), and granting the permission is what
        // flips it.
        if (access == MediaAccess.None) {
            // Not an empty list: a phone with no photographs and a phone that
            // will not show them look identical, and only one of them is
            // something somebody can fix.
            Text(
                "Stratus cannot read your photographs on this device yet, so " +
                    "there are no folders to choose from. On iOS the photo library " +
                    "is not wired up at all; on Android this means the permission " +
                    "has not been given.",
                Modifier.padding(16.dp),
            )
            if (onRequestAccess != null) {
                TextButton(onClick = onRequestAccess, Modifier.padding(horizontal = 8.dp)) {
                    Text("Allow access to photos")
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { picked = available.mapTo(mutableSetOf()) { it.id } }) { Text("Every folder") }
                TextButton(onClick = { picked = emptySet() }) { Text("None") }
            }
            Footnote(
                if (picked.isEmpty()) {
                    "Nothing is backed up. Tick a folder to start."
                } else {
                    "${picked.size} of ${available.size} folders are backed up."
                },
            )

            LazyColumn(Modifier.fillMaxSize()) {
                items(available, key = { it.id }) { source ->
                    val ticked = source.id in picked
                    ListItem(
                        leadingContent = { Text(Glyph.FOLDERS) },
                        headlineContent = { Text(source.label) },
                        supportingContent = { Text("${source.count} photographs") },
                        trailingContent = { Checkbox(checked = ticked, onCheckedChange = null) },
                        modifier = Modifier.toggleable(value = ticked) { on ->
                            picked = if (on) picked + source.id else picked - source.id
                        },
                    )
                }
            }
        }
    }
}
