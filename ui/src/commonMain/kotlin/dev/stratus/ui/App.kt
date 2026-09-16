package dev.stratus.ui

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
import dev.stratus.core.files.BrowserController
import dev.stratus.core.signin.SignInState
import dev.stratus.ui.files.BrowserScreen
import dev.stratus.ui.signin.SignInScreen
import kotlinx.coroutines.launch

@Composable
fun App(container: AppContainer, back: BackRequests = BackRequests()) {
    val scope = rememberCoroutineScope()
    val signIn = remember { container.signIn(scope) }
    val state by signIn.state.collectAsState()

    LaunchedEffect(Unit) { signIn.restore() }

    MaterialTheme {
        when (val current = state) {
            is SignInState.Done -> {
                var browser by remember { mutableStateOf<BrowserController?>(null) }
                LaunchedEffect(current.session.baseUrl) {
                    browser = container.browser(scope)?.also { it.start() }
                }

                val open = browser
                if (open != null) {
                    DisposableEffect(open) {
                        back.onBack = { open.goUp() }
                        onDispose { back.onBack = null }
                    }
                    BrowserScreen(open, onSignOut = { scope.launch { signIn.signOut() } })
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
