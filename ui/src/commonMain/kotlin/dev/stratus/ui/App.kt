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
import dev.stratus.core.backup.MediaAccess
import dev.stratus.core.backup.MediaSource
import dev.stratus.core.files.BrowserController
import dev.stratus.core.instance.Instance
import dev.stratus.core.signin.SignInState
import dev.stratus.ui.backup.BackupScreen
import dev.stratus.ui.backup.BackupStrip
import dev.stratus.ui.backup.InstanceStatus
import dev.stratus.ui.files.BrowserScreen
import dev.stratus.ui.servers.ServersScreen
import dev.stratus.ui.servers.SourcesScreen
import dev.stratus.ui.signin.SignInScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where in the app somebody is. Four places, so a sealed type rather than flags. */
private sealed interface Screen {
    data object Browser : Screen
    data object Backup : Screen
    data object Servers : Screen
    data class Sources(val instanceId: String) : Screen
}

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
            is SignInState.Done -> SignedIn(
                container = container,
                back = back,
                onBackUpNow = onBackUpNow,
                baseUrl = current.baseUrl,
                // Adding a server is the same screen as the first sign-in,
                // reached by putting the controller back where it starts.
                onAddAnother = signIn::cancel,
                onInstancesChanged = { signIn.restore() },
            )

            else -> SignInScreen(current, onSubmit = signIn::submit, onAnswer = signIn::answer)
        }
    }
}

@Composable
private fun SignedIn(
    container: AppContainer,
    back: BackRequests,
    onBackUpNow: (() -> Unit)?,
    baseUrl: String,
    onAddAnother: () -> Unit,
    onInstancesChanged: suspend () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf<Screen>(Screen.Browser) }
    var browser by remember { mutableStateOf<BrowserController?>(null) }
    var servers by remember { mutableStateOf(emptyList<Instance>()) }
    var currentId by remember { mutableStateOf<String?>(null) }
    var overall by remember { mutableStateOf<BackupState>(BackupState.NeverRun) }
    var statuses by remember { mutableStateOf(emptyList<InstanceStatus>()) }
    var folders by remember { mutableStateOf(emptyList<MediaSource>()) }
    var access by remember { mutableStateOf(MediaAccess.None) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(baseUrl, reload) {
        browser = container.browser(scope)?.also { it.start() }
        currentId = container.current()?.id
        folders = container.availableSources()
        access = container.libraryAccess()
    }

    // Polled rather than pushed: the backup runs in another process, and reading
    // the same tables it writes is the only way this cannot end up claiming
    // something the queue would disagree with.
    LaunchedEffect(reload) {
        while (true) {
            servers = container.instances()
            overall = container.backupStatus.across(servers)
            statuses = servers.map {
                InstanceStatus(it, container.backupStatus.of(it), container.backupFailures(it.id))
            }
            delay(1_000)
        }
    }

    DisposableEffect(screen, browser) {
        back.onBack = when (val here = screen) {
            is Screen.Browser -> browser?.let { { it.goUp() } } ?: { false }
            is Screen.Sources -> { { screen = Screen.Servers; true } }
            else -> { { screen = Screen.Browser; true } }
        }
        onDispose { back.onBack = null }
    }

    when (val here = screen) {
        is Screen.Backup -> BackupScreen(
            statuses = statuses,
            onBackUpNow = onBackUpNow,
            onClose = { screen = Screen.Browser },
        )

        is Screen.Servers -> ServersScreen(
            servers = servers,
            currentId = currentId,
            onLookAt = { id -> scope.launch { container.switchTo(id); reload++ } },
            onEnableBackup = { id, on -> scope.launch { container.setBackupEnabled(id, on); reload++ } },
            onChooseSources = { screen = Screen.Sources(it) },
            onSignOut = { id ->
                scope.launch {
                    container.forget(id)
                    onInstancesChanged()
                    reload++
                    screen = Screen.Browser
                }
            },
            onAddAnother = onAddAnother,
            onClose = { screen = Screen.Browser },
        )

        is Screen.Sources -> SourcesScreen(
            available = folders,
            access = access,
            chosen = servers.firstOrNull { it.id == here.instanceId }?.sources.orEmpty(),
            onSave = { chosen ->
                scope.launch {
                    container.setSources(here.instanceId, chosen)
                    reload++
                    screen = Screen.Servers
                }
            },
            onClose = { screen = Screen.Servers },
        )

        is Screen.Browser -> {
            val open = browser ?: return
            Column {
                BackupStrip(overall) { screen = Screen.Backup }
                BrowserScreen(open, onOpenServers = { screen = Screen.Servers })
            }
        }
    }
}
