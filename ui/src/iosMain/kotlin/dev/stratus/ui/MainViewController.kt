package dev.stratus.ui

import androidx.compose.ui.window.ComposeUIViewController
import dev.stratus.core.signin.signInController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

@Suppress("unused", "FunctionName") // Called from Swift, not from Kotlin.
fun MainViewController() = ComposeUIViewController {
    App(controller = remembered)
}

private val remembered by lazy {
    signInController(CoroutineScope(SupervisorJob() + Dispatchers.Main))
}
