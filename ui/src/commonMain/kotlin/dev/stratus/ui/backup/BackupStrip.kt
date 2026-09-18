package dev.stratus.ui.backup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stratus.core.backup.BackupState

/**
 * One line, always visible, above whatever else is on screen.
 *
 * A strip rather than a screen on its own because "is my backup working?" is
 * asked far more often than it is investigated, and an answer costing a
 * navigation is one nobody checks until they already distrust the app.
 */
@Composable
fun BackupStrip(state: BackupState, onOpen: () -> Unit) {
    // Colour carries the same distinction the type does: something that will
    // resolve itself must not look like something that will not.
    val background = when (state) {
        is BackupState.NeedsYou -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Surface(color = background, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(oneLine(state), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

internal fun oneLine(state: BackupState): String = when (state) {
    BackupState.NeverRun -> "Backup has not run yet"

    is BackupState.Idle ->
        if (state.uploaded > 0) "Backed up ${state.uploaded} ${things(state.uploaded)}" else "Everything is backed up"

    is BackupState.Working ->
        "Backing up — ${state.left} to go" + (state.current?.let { ": ${it.substringAfterLast('/')}" } ?: "")

    is BackupState.Waiting -> "${state.left} waiting, ${size(state.bytesLeft)}"

    is BackupState.NeedsYou -> when (state.reason) {
        is dev.stratus.core.backup.AttentionReason.TheLibraryIsNotReadable ->
            "Stratus cannot read your photographs"
        is dev.stratus.core.backup.AttentionReason.SomethingWillNotSend ->
            "${state.affected} ${things(state.affected)} will not upload"
    }
}

private fun things(count: Int) = if (count == 1) "photograph" else "photographs"

internal fun size(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    else -> "${bytes / (1024 * 1024 * 1024)} GB"
}
