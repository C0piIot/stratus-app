package dev.stratus.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.stratus.core.AppContainer
import dev.stratus.core.backup.BackupState
import dev.stratus.core.files.BrowserController
import dev.stratus.core.signin.SignInState
import dev.stratus.ui.backup.BackupScreen
import dev.stratus.ui.backup.BackupStrip
import dev.stratus.ui.backup.InstanceStatus
import dev.stratus.ui.files.BrowserScreen
import dev.stratus.ui.signin.SignInScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun App(
    container: AppContainer,
    back: BackRequests = BackRequests(),
    onBackUpNow: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val signIn = remember { container.signIn(scope) }
    val state by signIn.state.collectAsState()

    LaunchedEffect(Unit) { signIn.restore() }

    MaterialTheme {
        when (val current = state) {
            is SignInState.Done -> SignedIn(container, back, onBackUpNow, current.baseUrl) {
                scope.launch {
                    container.current()?.let { container.forget(it.id) }
                    signIn.restore()
                }
            }

            else -> SignInScreen(
                state = current,
                onSubmit = signIn::submit,
                onAnswer = signIn::answer,
            )
        }
    }
}

@Composable
private fun SignedIn(
    container: AppContainer,
    back: BackRequests,
    onBackUpNow: (() -> Unit)?,
    baseUrl: String,
    onSignOut: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var browser by remember { mutableStateOf<BrowserController?>(null) }
    var showingBackup by remember { mutableStateOf(false) }
    var overall by remember { mutableStateOf<BackupState>(BackupState.NeverRun) }
    var statuses by remember { mutableStateOf(emptyList<InstanceStatus>()) }

    LaunchedEffect(baseUrl) {
        browser = container.browser(scope)?.also { it.start() }
    }

    // Polled rather than pushed: the backup runs in another process entirely,
    // and reading the same tables it writes is the only way the screen cannot
    // end up claiming something the queue would disagree with.
    LaunchedEffect(Unit) {
        while (true) {
            val instances = container.instances()
            overall = container.backupStatus.across(instances)
            statuses = instances.map {
                InstanceStatus(it, container.backupStatus.of(it), container.backupFailures(it.id))
            }
            delay(1_000)
        }
    }

    if (showingBackup) {
        DisposableEffect(Unit) {
            back.onBack = { showingBackup = false; true }
            onDispose { back.onBack = null }
        }
        BackupScreen(
            statuses = statuses,
            onEnable = { id, enabled -> scope.launch { container.setBackupEnabled(id, enabled) } },
            onBackUpNow = onBackUpNow,
            onClose = { showingBackup = false },
        )
        return
    }

    val open = browser ?: return
    DisposableEffect(open) {
        back.onBack = { open.goUp() }
        onDispose { back.onBack = null }
    }

    Column {
        BackupStrip(overall) { showingBackup = true }
        BrowserScreen(open, onSignOut = onSignOut)
    }
}
