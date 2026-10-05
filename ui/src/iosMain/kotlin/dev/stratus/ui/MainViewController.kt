package dev.stratus.ui

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.ComposeUIViewController
import dev.stratus.core.backup.IosAssetSource
import dev.stratus.core.session.Ask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import dev.stratus.core.hostAppContainer
import kotlinx.coroutines.runBlocking

@Suppress("unused", "FunctionName") // Called from Swift, not from Kotlin.
fun MainViewController() = ComposeUIViewController {
    val scope = rememberCoroutineScope { Dispatchers.Main.immediate }
    App(
        container,
        // Asking is the shell's to trigger and the platform's to perform, as
        // on Android. Without it `access()` answers None for ever and the
        // backup sits in front of a camera roll nobody ever offered it
        // (stratus-app#20).
        //
        // Notifications are not asked for here: iOS has nothing to show and
        // the backup is the system's to schedule, so a prompt would be asking
        // for permission to say nothing.
        onAsk = { asks ->
            if (Ask.Photos in asks) {
                scope.launch { (container.assets as? IosAssetSource)?.request() }
            }
        },
    )
}

private val container by lazy {
    hostAppContainer().also {
        // Before the first screen, so a crash drawing it is reported too. A
        // keychain read, which is what blocking here costs.
        if (runBlocking { it.reporting.granted() }) CrashReports.start()
    }
}
