package dev.stratus.ui.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import dev.stratus.core.server.Server

/**
 * The server, and what the app is allowed to do with it.
 *
 * One server (stratus-app#131), so there is no list, no radio button and no
 * "add another": what was a screen about choosing between servers is a screen
 * about the one there is. Backup has no switch of its own either -- choosing
 * folders is what turns it on, which is the one thing somebody is actually
 * deciding when they open it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(
    server: Server,
    onChooseSources: () -> Unit,
    onSignOut: () -> Unit,
    /** Opens the sign-in form on this server, filled in, to change any of it. */
    onEdit: () -> Unit,
    /** Whether crash reports are on, or null in a build that cannot send any. */
    reporting: Boolean?,
    onReporting: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    var signingOut by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Server") },
                navigationIcon = { TextButton(onClick = onClose) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(server.baseUrl, style = MaterialTheme.typography.bodyLarge)
                Text(server.username, style = MaterialTheme.typography.bodySmall)

                // What is backed up, said as a sentence rather than as the
                // button's label: a label that changes with the state is one
                // nobody can look for, here or in a test.
                Text(
                    if (server.sources.isEmpty()) {
                        "Nothing is backed up."
                    } else {
                        "${server.sources.size} folders are backed up."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onChooseSources) { Text("Choose folders") }
                    TextButton(onClick = onEdit) { Text("Edit") }
                    // "Sign out" and not "Remove": with one server there is
                    // nothing to remove it from, and what it costs is said in
                    // the dialog rather than guessed at from the word.
                    TextButton(onClick = { signingOut = true }) { Text("Sign out") }
                }
            }
            Divider()

            // Here rather than on a screen of its own: it is one switch, and
            // the menu is the third of the three surfaces this app has.
            if (reporting != null) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Send crash reports")
                        Switch(checked = reporting, onCheckedChange = onReporting)
                    }
                    // What leaves the phone, said before the switch is on.
                    Text(
                        "When the app crashes, the error and which phone it was on go " +
                            "to Sentry, a service that is not your server. Never a " +
                            "photograph or a password, but an error can name a server " +
                            "address or a file.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    if (signingOut) {
        AlertDialog(
            onDismissRequest = { signingOut = false },
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
                TextButton(onClick = { onSignOut(); signingOut = false }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { signingOut = false }) { Text("Cancel") } },
        )
    }
}
