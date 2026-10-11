package dev.stratus.ui.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stratus.core.backup.AttentionReason
import dev.stratus.core.backup.BackupState
import dev.stratus.core.backup.PendingUpload
import dev.stratus.core.backup.WaitingReason
import dev.stratus.ui.Footnote
import dev.stratus.ui.Glyph
import dev.stratus.ui.NavRow
import dev.stratus.ui.Page
import dev.stratus.ui.Rule
import dev.stratus.ui.Section
import dev.stratus.ui.SwitchRow

/**
 * Whether the backup is working, and what it has been told to do.
 *
 * Both, on one screen, which is the opposite of how this was split before
 * (stratus-app#137): the status was here and the settings were under Server,
 * so finding out that nothing was queued and finding out that no folder was
 * ticked were two screens in different parts of the app. The question the
 * answer belongs to is the same question.
 */
@Composable
fun BackupScreen(
    state: BackupState,
    failures: List<PendingUpload>,
    /** How many folders feed it, which is also whether it is on (stratus-app#131). */
    chosenFolders: Int,
    onChooseFolders: () -> Unit,
    onlyOnWifi: Boolean,
    onOnlyOnWifi: (Boolean) -> Unit,
    onBackUpNow: (() -> Unit)?,
    onClose: () -> Unit,
) {
    Page("Backup", onBack = onClose) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(oneLine(state), style = MaterialTheme.typography.titleMedium)
            Text(
                explain(state),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onBackUpNow != null) {
                Button(onClick = onBackUpNow, Modifier.padding(top = 8.dp)) { Text("Back up now") }
            }
        }
        Rule()

        Section("Settings") {
            NavRow(Glyph.FOLDERS, "Folders", folders(chosenFolders), onClick = onChooseFolders)
            SwitchRow(Glyph.WIFI, "Only back up on wifi", checked = onlyOnWifi, onChange = onOnlyOnWifi)
        }
        Footnote(
            "A camera roll is the one thing on a phone big enough to matter on a " +
                "data plan. With this on, a pass on mobile data still looks for new " +
                "photographs and queues them — it just waits for wifi to send them.",
        )

        // The failures, with what the server said about each. A count on its
        // own tells somebody they have a problem and nothing about which one.
        if (failures.isNotEmpty()) {
            Rule()
            Section("Will not upload") {
                failures.take(20).forEach { failure ->
                    ListItem(
                        headlineContent = { Text(failure.path.substringAfterLast('/')) },
                        supportingContent = { Text(failure.lastError ?: "no reason given") },
                    )
                }
            }
        }
    }
}

/** What the Folders row says underneath itself, and nothing is said as nothing. */
internal fun folders(chosen: Int): String = when (chosen) {
    0 -> "None chosen, so nothing is backed up"
    1 -> "1 folder"
    else -> "$chosen folders"
}

/**
 * The sentence under the headline, and the one that has to be different for a
 * pause that ends itself and one that does not.
 */
internal fun explain(state: BackupState): String = when (state) {
    BackupState.NeverRun ->
        "Choose a folder below and photographs will start going to your server."

    is BackupState.Idle ->
        "Nothing is waiting. Last run finished at ${state.lastRunAt}."

    is BackupState.Working ->
        "${state.done} sent so far in this pass."

    is BackupState.Waiting -> when (state.reason) {
        WaitingReason.ForTheNextPass ->
            "Waiting for the next pass. Nothing is wrong; the phone decides when."
        WaitingReason.ForWifi ->
            "Waiting for wifi. New photographs are queued as they are taken; " +
                "they go the moment you are on wifi, or turn off \"only back " +
                "up on wifi\" below."
        WaitingReason.ForARetry ->
            "Something did not go through and will be tried again by itself. " +
                "Nothing needs doing."
        WaitingReason.InTheSystemsHands ->
            "Sending in the background. The phone decides when it finishes, " +
                "and will carry on with the app closed."
    }

    is BackupState.NeedsYou -> when (val reason = state.reason) {
        is AttentionReason.TheLibraryIsNotReadable ->
            "Stratus has no permission to read your photographs, so nothing can " +
                "be backed up. Granting it in Settings is all this needs."

        // The one that must not read like a pause: it will not fix itself.
        is AttentionReason.SomethingWillNotSend ->
            "This will not be retried on its own." +
                (reason.detail?.let { " The server said: $it" } ?: "")
    }
}
