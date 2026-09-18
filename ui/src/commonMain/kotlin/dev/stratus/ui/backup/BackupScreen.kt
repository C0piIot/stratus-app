package dev.stratus.ui.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stratus.core.backup.AttentionReason
import dev.stratus.core.backup.BackupState
import dev.stratus.core.backup.PendingUpload
import dev.stratus.core.backup.WaitingReason
import dev.stratus.core.instance.Instance

/** One server and what its backup is doing. */
data class InstanceStatus(
    val instance: Instance,
    val state: BackupState,
    val failures: List<PendingUpload>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    statuses: List<InstanceStatus>,
    onBackUpNow: (() -> Unit)?,
    onClose: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup") },
                navigationIcon = { TextButton(onClick = onClose) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (onBackUpNow != null) {
                Button(onClick = onBackUpNow) { Text("Back up now") }
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(statuses, key = { it.instance.id }) { status ->
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // No switch here. This screen answers "is it working";
                        // what it should be doing is the servers screen, and one
                        // question per surface is what keeps either readable.
                        Text(status.instance.baseUrl)
                        Text(oneLine(status.state))
                        Text(explain(status.state))

                        // The failures, with what the server said about each. A
                        // count on its own tells somebody they have a problem
                        // and nothing about which one.
                        for (failure in status.failures.take(20)) {
                            Text("${failure.path.substringAfterLast('/')} — ${failure.lastError ?: "no reason given"}")
                        }
                    }
                    Divider()
                }
            }
        }
    }
}

/**
 * The sentence under the headline, and the one that has to be different for a
 * pause that ends itself and one that does not.
 */
internal fun explain(state: BackupState): String = when (state) {
    BackupState.NeverRun ->
        "Turn backup on for a server and photographs will start going there."

    is BackupState.Idle ->
        "Nothing is waiting. Last run finished at ${state.lastRunAt}."

    is BackupState.Working ->
        "${state.done} sent so far in this pass."

    is BackupState.Waiting -> when (state.reason) {
        WaitingReason.ForTheNextPass ->
            "Waiting for the next pass. Nothing is wrong; the phone decides when."
        WaitingReason.ForARetry ->
            "Something did not go through and will be tried again by itself. " +
                "Nothing needs doing."
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
