package dev.stratus.ui

import androidx.compose.ui.window.ComposeUIViewController
import dev.stratus.core.appContainer

@Suppress("unused", "FunctionName") // Called from Swift, not from Kotlin.
fun MainViewController() = ComposeUIViewController { App(container) }

private val container by lazy { appContainer() }
