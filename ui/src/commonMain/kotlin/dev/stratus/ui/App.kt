package dev.stratus.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.stratus.core.AppContainer
import dev.stratus.core.session.Ask
import dev.stratus.core.cast.CastController
import dev.stratus.core.files.BrowserController
import dev.stratus.core.signin.SignInState
import dev.stratus.ui.backup.BackupScreen
import dev.stratus.ui.backup.BackupStrip
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
    /** Asks the system for these permissions, in one request; null where nothing can ask yet. */
    onAsk: ((Set<Ask>) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val signIn = remember { container.signIn(scope) }
    val state by signIn.state.collectAsState()
    val editing by signIn.editing.collectAsState()
    val canGoBack by signIn.canGoBack.collectAsState()

    LaunchedEffect(Unit) { signIn.restore() }

    MaterialTheme {
        // Android has drawn edge to edge without asking since targetSdk 35, and
        // iOS has a notch: without this the first thing on a screen sits under
        // the status bar, which is where the sign-in form's server address was.
        // Applied once at the root and consumed here, so the browser's Scaffold
        // does not pad a second time.
        //
        // The top and the sides only. `safeDrawing` counts the keyboard too,
        // and padding the root for it shrinks whatever is on screen instead of
        // letting it scroll under -- which would have traded a field hidden at
        // the top for one squeezed off the bottom.
        Box(
            Modifier.windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            ),
        ) {
            when (val current = state) {
                is SignInState.Done -> SignedIn(
                    container = container,
                    back = back,
                    onBackUpNow = onBackUpNow,
                    onAsk = onAsk,
                    baseUrl = current.baseUrl,
                    // Adding a server is the same screen as the first sign-in,
                    // reached by putting the controller back where it starts.
                    onAddAnother = signIn::addAnother,
                    onEdit = { id -> scope.launch { signIn.edit(id) } },
                    onInstancesChanged = { signIn.restore() },
                )

                else -> {
                    // Opened from inside the app -- editing a server, or adding
                    // one more -- the form has a way back, the system gesture
                    // included.
                    DisposableEffect(canGoBack) {
                        back.onBack = if (canGoBack) { { scope.launch { signIn.back() }; true } } else null
                        onDispose { back.onBack = null }
                    }
                    SignInScreen(
                        current,
                        onSubmit = signIn::submit,
                        onAnswer = signIn::answer,
                        initial = editing?.form,
                        onBack = if (canGoBack) { { scope.launch { signIn.back() } } } else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun SignedIn(
    container: AppContainer,
    back: BackRequests,
    onBackUpNow: (() -> Unit)?,
    onAsk: ((Set<Ask>) -> Unit)?,
    baseUrl: String,
    onAddAnother: () -> Unit,
    onEdit: (String) -> Unit,
    onInstancesChanged: suspend () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val signedIn = remember {
        container.signedIn(onAsk) { on -> if (on) CrashReports.start() else CrashReports.stop() }
    }
    val state by signedIn.state.collectAsState()
    var screen by remember { mutableStateOf<Screen>(Screen.Browser) }
    var browser by remember { mutableStateOf<BrowserController?>(null) }
    var cast by remember { mutableStateOf<CastController?>(null) }

    LaunchedEffect(Unit) { signedIn.start() }

    // Rebuilt when the server being looked at changes, and only then -- which
    // excludes the moment before the controller has said which one it is: built
    // then as well, the root was listed twice on every sign-in and redrawn
    // under the finger of whoever tapped first.
    LaunchedEffect(baseUrl, state.currentId) {
        if (state.currentId == null) return@LaunchedEffect
        browser = container.browser(scope)?.also { it.start() }
        cast = container.cast(scope)
    }

    // Polled rather than pushed: the backup runs in another process, and reading
    // the same tables it writes is the only way this cannot end up claiming
    // something the queue would disagree with.
    //
    // Only while the app is in front. A LaunchedEffect outlives the activity
    // being stopped, so without this it went on polling every second for as
    // long as the process lived. Coming back is also when a permission can have
    // changed -- in the system settings, or the dialog, which pauses the app
    // while it is up -- which is what resumed() is for (stratus-app#75).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            signedIn.resumed()
            while (true) {
                delay(1_000)
                signedIn.refresh()
            }
        }
    }

    DisposableEffect(screen, browser) {
        back.onBack = when (screen) {
            is Screen.Browser -> browser?.let { { it.goUp() } } ?: { false }
            is Screen.Sources -> { { screen = Screen.Servers; true } }
            else -> { { screen = Screen.Browser; true } }
        }
        onDispose { back.onBack = null }
    }

    when (val here = screen) {
        is Screen.Backup -> BackupScreen(
            statuses = state.statuses,
            onBackUpNow = onBackUpNow,
            onClose = { screen = Screen.Browser },
        )

        is Screen.Servers -> ServersScreen(
            servers = state.servers,
            currentId = state.currentId,
            onLookAt = { id -> scope.launch { signedIn.lookAt(id) } },
            onEnableBackup = { id, on -> scope.launch { signedIn.enableBackup(id, on) } },
            onChooseSources = { screen = Screen.Sources(it) },
            onSignOut = { id ->
                scope.launch {
                    signedIn.signOut(id)
                    onInstancesChanged()
                    screen = Screen.Browser
                }
            },
            onAddAnother = onAddAnother,
            reporting = state.reporting.takeIf { CrashReports.available },
            onReporting = { on -> scope.launch { signedIn.setReporting(on) } },
            onEdit = onEdit,
            onClose = { screen = Screen.Browser },
        )

        is Screen.Sources -> {
            LaunchedEffect(Unit) { signedIn.openingFolders() }
            SourcesScreen(
                available = state.folders,
                access = state.access,
                onRequestAccess = onAsk?.let { { signedIn.askForPhotos() } },
                chosen = state.servers.firstOrNull { it.id == here.instanceId }?.sources.orEmpty(),
                onSave = { chosen ->
                    scope.launch {
                        signedIn.chooseSources(here.instanceId, chosen)
                        screen = Screen.Servers
                    }
                },
                onClose = { screen = Screen.Servers },
            )
        }

        is Screen.Browser -> {
            // A condition and not `?: return`, which is what this was.
            //
            // An early return out of a composable is legal and the compiler
            // balances the groups for it, but this one leaves and re-enters a
            // branch holding two `SubcomposeLayout`s -- a Scaffold and a lazy
            // list -- every time the controller is rebuilt, and that is the
            // shape the slot table was found corrupted in at dispose
            // (stratus-app#85). Written this way there is nothing to balance,
            // and it says the same thing.
            val open = browser
            if (open != null) {
                Column {
                    BackupStrip(state.overall) { screen = Screen.Backup }
                    BrowserScreen(
                        open,
                        cast,
                        onOpenServers = { screen = Screen.Servers },
                        onEditServer = { state.currentId?.let(onEdit) },
                    )
                }
            }
        }
    }
}
