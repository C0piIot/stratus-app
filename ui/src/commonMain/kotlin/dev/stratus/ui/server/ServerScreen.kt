package dev.stratus.ui.server

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.stratus.core.server.Server
import dev.stratus.ui.ActionRow
import dev.stratus.ui.FactRow
import dev.stratus.ui.Glyph
import dev.stratus.ui.Page
import dev.stratus.ui.Rule
import dev.stratus.ui.Section

/**
 * The server, and nothing that merely happens to involve it.
 *
 * One server (stratus-app#131), so there is no list and no "add another". What
 * this screen lost in stratus-app#137 is everything about the backup: which
 * folders feed it and whether it may spend mobile data are questions about the
 * backup, and they are under Backup.
 */
@Composable
fun ServerScreen(
    server: Server,
    onSignOut: () -> Unit,
    /** Opens the sign-in form on this server, filled in, to change any of it. */
    onEdit: () -> Unit,
    onClose: () -> Unit,
) {
    var signingOut by remember { mutableStateOf(false) }

    Page("Server", onBack = onClose) {
        Section("Signed in") {
            FactRow(Glyph.ADDRESS, "Address", server.baseUrl)
            FactRow(Glyph.USERNAME, "Username", server.username)
        }
        Rule()

        ActionRow(
            Glyph.EDIT,
            "Edit server",
            "The sign-in form again, filled in. The backup record is kept.",
            onClick = onEdit,
        )
        // "Sign out" and not "Remove": with one server there is nothing to
        // remove it from, and what it costs is said in the dialog rather than
        // guessed at from the word.
        ActionRow(
            Glyph.SIGN_OUT,
            "Sign out",
            "Forgets the password and what has been backed up here.",
            onClick = { signingOut = true },
        )
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
