package dev.stratus.ui

import androidx.compose.ui.window.ComposeUIViewController
import dev.stratus.core.appContainer
import kotlinx.coroutines.runBlocking

@Suppress("unused", "FunctionName") // Called from Swift, not from Kotlin.
fun MainViewController() = ComposeUIViewController { App(container) }

private val container by lazy {
    appContainer().also {
        // Before the first screen, so a crash drawing it is reported too. A
        // keychain read, which is what blocking here costs.
        if (runBlocking { it.reporting.granted() }) CrashReports.start()
    }
}
