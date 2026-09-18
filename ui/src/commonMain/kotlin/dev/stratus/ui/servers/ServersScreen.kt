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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import dev.stratus.core.instance.Instance

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(
    servers: List<Instance>,
    currentId: String?,
    onLookAt: (String) -> Unit,
    onEnableBackup: (String, Boolean) -> Unit,
    onChooseSources: (String) -> Unit,
    onSignOut: (String) -> Unit,
    onAddAnother: () -> Unit,
    onClose: () -> Unit,
) {
    var signingOutOf by remember { mutableStateOf<Instance?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Servers") },
                navigationIcon = { TextButton(onClick = onClose) { Text("Back") } },
                actions = { TextButton(onClick = onAddAnother) { Text("Add") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            items(servers, key = { it.id }) { server ->
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clickable { onLookAt(server.id) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Which one the browser shows. Switching is only about
                        // what you are looking at -- backup goes to every server
                        // that is turned on, whichever one is on screen.
                        RadioButton(selected = server.id == currentId, onClick = { onLookAt(server.id) })
                        Column {
                            Text(server.baseUrl, style = MaterialTheme.typography.bodyLarge)
                            Text(server.username, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Back up to this server")
                        Switch(
                            checked = server.backupEnabled,
                            onCheckedChange = { onEnableBackup(server.id, it) },
                        )
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { onChooseSources(server.id) }) {
                            Text(if (server.sources.isEmpty()) "All folders" else "${server.sources.size} folders")
                        }
                        TextButton(onClick = { signingOutOf = server }) { Text("Sign out") }
                    }
                }
                Divider()
            }
        }
    }

    signingOutOf?.let { server ->
        AlertDialog(
            onDismissRequest = { signingOutOf = null },
            title = { Text("Sign out of ${server.baseUrl}?") },
            // What it actually costs, rather than a generic are-you-sure. The
            // cache is rebuildable by walking the server, which is the whole
            // point of how it was designed -- but that walk is not free.
            text = {
                Text(
                    "The password is forgotten and so is the record of what has " +
                        "already been backed up there. Nothing is lost on the server, but " +
                        "signing in again means looking through all of it once more to " +
                        "work out what is already there.",
                )
            },
            confirmButton = {
                TextButton(onClick = { onSignOut(server.id); signingOutOf = null }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { signingOutOf = null }) { Text("Cancel") } },
        )
    }
}
