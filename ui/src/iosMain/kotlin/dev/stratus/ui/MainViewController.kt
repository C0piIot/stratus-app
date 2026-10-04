package dev.stratus.ui

import androidx.compose.ui.window.ComposeUIViewController
import dev.stratus.core.hostAppContainer
import kotlinx.coroutines.runBlocking

@Suppress("unused", "FunctionName") // Called from Swift, not from Kotlin.
fun MainViewController() = ComposeUIViewController { App(container) }

private val container by lazy {
    hostAppContainer().also {
        // Before the first screen, so a crash drawing it is reported too. A
        // keychain read, which is what blocking here costs.
        if (runBlocking { it.reporting.granted() }) CrashReports.start()
    }
}
