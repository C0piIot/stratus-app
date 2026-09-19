package dev.stratus.ui.files

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stratus.core.dav.DavResource
import dev.stratus.core.files.BrowserController
import dev.stratus.core.cast.CastController
import dev.stratus.core.cast.CastState
import dev.stratus.core.files.Confirmation
import dev.stratus.core.share.ShareLife

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    controller: BrowserController,
    cast: CastController?,
    onOpenServers: () -> Unit,
) {
    val state by controller.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.path) },
                navigationIcon = {
                    if (state.path != "/") TextButton(onClick = { controller.goUp() }) { Text("Up") }
                },
                actions = { TextButton(onClick = onOpenServers) { Text("Servers") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.failure?.let { Text(explain(it), Modifier.padding(16.dp)) }

            if (cast != null) CastBar(cast)

            LazyColumn(Modifier.fillMaxSize()) {
                items(state.entries, key = { it.path }) { entry ->
                    Row(entry, controller, cast)
                    Divider()
                }
            }
        }
    }

    when (val pending = state.pending) {
        is Confirmation.Delete -> DeleteDialog(pending.target, controller)
        is Confirmation.Rename -> RenameDialog(pending.target, controller)
        is Confirmation.Share -> ShareDialog(pending.target, controller)
        null -> {}
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Row(entry: DavResource, controller: BrowserController, cast: CastController?) {
    var menuOpen by remember { mutableStateOf(false) }

    Box {
        ListItem(
            headlineContent = { Text(entry.name) },
            supportingContent = { Text(if (entry.isDirectory) "Folder" else describe(entry)) },
            // A folder's menu had no way of opening at all, because tapping one
            // walks into it -- so renaming or deleting a folder was unreachable.
            // Holding is the gesture for "not the obvious thing", everywhere.
            modifier = Modifier.combinedClickable(
                onClick = { if (entry.isDirectory) controller.enter(entry) else menuOpen = true },
                onLongClick = { menuOpen = true },
            ),
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (!entry.isDirectory) {
                // Two different things: one shows the file, the other keeps it.
                DropdownMenuItem(
                    text = { Text("Open") },
                    onClick = { menuOpen = false; controller.openFile(entry) },
                )
                DropdownMenuItem(
                    text = { Text("Download") },
                    onClick = { menuOpen = false; controller.saveFile(entry) },
                )
            }
            if (cast?.canCast(entry) == true) {
                DropdownMenuItem(
                    text = { Text("Cast to a screen") },
                    onClick = { menuOpen = false; cast.offer(entry) },
                )
            }
            if (controller.canShare) {
                DropdownMenuItem(
                    text = { Text("Share a link") },
                    onClick = { menuOpen = false; controller.ask(Confirmation.Share(entry)) },
                )
            }
            DropdownMenuItem(
                text = { Text("Rename") },
                onClick = { menuOpen = false; controller.ask(Confirmation.Rename(entry)) },
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                onClick = { menuOpen = false; controller.ask(Confirmation.Delete(entry)) },
            )
        }
    }
}

@Composable
private fun DeleteDialog(target: DavResource, controller: BrowserController) {
    AlertDialog(
        onDismissRequest = controller::dismiss,
        title = { Text("Delete ${target.name}?") },
        // There is no trash anywhere in Stratus, so this is the only chance.
        text = { Text("This cannot be undone. There is no trash to recover it from.") },
        confirmButton = { TextButton(onClick = controller::confirmDelete) { Text("Delete") } },
        dismissButton = { TextButton(onClick = controller::dismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RenameDialog(target: DavResource, controller: BrowserController) {
    var name by remember(target.path) { mutableStateOf(target.name) }
    AlertDialog(
        onDismissRequest = controller::dismiss,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = { controller.confirmRename(name) }) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = controller::dismiss) { Text("Cancel") } },
    )
}

private fun describe(entry: DavResource): String {
    val size = entry.size ?: return entry.contentType ?: ""
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${size / 1024} KB"
        size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
        else -> "${size / (1024 * 1024 * 1024)} GB"
    }
}

/**
 * The four lifetimes, and the sentence that has to be said out loud.
 *
 * None of this can be taken back one link at a time, so the screen that gives a
 * link away is the only place somebody can be told so.
 */
@Composable
private fun ShareDialog(target: DavResource, controller: BrowserController) {
    AlertDialog(
        onDismissRequest = controller::dismiss,
        title = { Text("Share \"${target.name}\"") },
        text = {
            Text(
                "Anyone with the link can open it, without signing in" +
                    (if (target.isDirectory) ", and everything inside it" else "") + ".\n\n" +
                    "There is no list of what you have shared and no way to withdraw one link: " +
                    "changing your password withdraws them all. Renaming this breaks its link.",
            )
        },
        confirmButton = {
            Column {
                for (life in ShareLife.entries) {
                    TextButton(onClick = { controller.confirmShare(life) }) { Text(label(life)) }
                }
            }
        },
        dismissButton = { TextButton(onClick = controller::dismiss) { Text("Cancel") } },
    )
}

private fun label(life: ShareLife) = when (life) {
    ShareLife.ADay -> "For a day"
    ShareLife.AWeek -> "For a week"
    ShareLife.AMonth -> "For a month"
    ShareLife.Forever -> "Until I change my password"
}

/**
 * Everything casting has to say, which is a warning, a list of screens, or what
 * is playing.
 */
@Composable
private fun CastBar(cast: CastController) {
    val state by cast.state.collectAsState()
    val devices by cast.devices.collectAsState()

    when (val here = state) {
        CastState.Idle -> {}

        is CastState.Playing -> ListItem(
            headlineContent = { Text("Playing on ${here.device.name}") },
            supportingContent = { Text(here.item.title) },
            trailingContent = { TextButton(onClick = cast::stop) { Text("Stop") } },
        )

        // Told rather than refused: a pin is only consulted when the system's
        // own check fails, so a server given a real certificate since would be
        // warned about for nothing.
        is CastState.CertificateIsOnlyTrustedHere -> AlertDialog(
            onDismissRequest = cast::stop,
            title = { Text("A television cannot check this certificate") },
            text = {
                Text(
                    "You accepted this server's certificate on this phone, and nothing else " +
                        "vouches for it. A television has nobody to ask, so it will most likely " +
                        "fetch nothing and sit there.\n\n" +
                        "What fixes it: a certificate from a real authority — Let's Encrypt is " +
                        "free — or reaching the server over plain http on your own network.",
                )
            },
            confirmButton = { TextButton(onClick = cast::goOnAnyway) { Text("Cast anyway") } },
            dismissButton = { TextButton(onClick = cast::stop) { Text("Cancel") } },
        )

        is CastState.Choosing -> AlertDialog(
            onDismissRequest = cast::stop,
            title = { Text("Cast \"${here.item.title}\"") },
            text = {
                Column {
                    if (devices.isEmpty()) {
                        Text("Looking for screens on this network…")
                    } else {
                        for (device in devices) {
                            TextButton(onClick = { cast.playOn(device) }) { Text(device.name) }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = cast::stop) { Text("Cancel") } },
        )
    }
}
