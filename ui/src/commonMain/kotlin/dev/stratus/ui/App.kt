package dev.stratus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stratus.core.signin.SignInController
import dev.stratus.core.signin.SignInState
import dev.stratus.ui.signin.SignInScreen
import kotlinx.coroutines.launch

@Composable
fun App(controller: SignInController) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { controller.restore() }

    MaterialTheme {
        when (val current = state) {
            is SignInState.Done -> Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Signed in to ${current.session.baseUrl}")
                Text("as ${current.session.username}")
                // Temporary. The menu in #22 is where this belongs; until then
                // there is no way to sign out and try a second server.
                Button(onClick = { scope.launch { controller.signOut() } }) { Text("Sign out") }
            }

            else -> SignInScreen(
                state = current,
                onSubmit = controller::submit,
                onAnswer = controller::answer,
            )
        }
    }
}
