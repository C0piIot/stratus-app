package dev.stratus.ui.signin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.stratus.core.signin.PlaintextReason
import dev.stratus.core.signin.Question
import dev.stratus.core.signin.SignInForm
import dev.stratus.core.signin.SignInState

/**
 * The screen, and nothing but the screen.
 *
 * Every decision behind it lives in `:core`, where a JVM test can reach it. What
 * is here is a `when` over a sealed type, which the compiler keeps exhaustive.
 */
@Composable
fun SignInScreen(
    state: SignInState,
    onSubmit: (SignInForm) -> Unit,
    onAnswer: (Question, Boolean) -> Unit,
) {
    var address by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val busy = state is SignInState.Probing

    Column(
        // Scrollable and padded for the keyboard: three fields and a button fit
        // on any phone until the keyboard is up, and then they do not.
        modifier = Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Sign in to your server")

        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text("Server address") },
            // Whatever their browser shows them is a good enough answer.
            placeholder = { Text("stratus.example.com") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = { onSubmit(SignInForm(address, username, password)) },
            enabled = !busy && address.isNotBlank() && username.isNotBlank(),
        ) {
            Text("Sign in")
        }

        if (busy) CircularProgressIndicator()
        if (state is SignInState.Failed) Text(explain(state.reason))
    }

    if (state is SignInState.Asking) {
        when (val question = state.question) {
            is Question.AcceptPlaintext -> AlertDialog(
                onDismissRequest = { onAnswer(question, false) },
                title = { Text("This connection is not encrypted") },
                text = { Text(plaintextWarning(question)) },
                confirmButton = {
                    TextButton(onClick = { onAnswer(question, true) }) { Text("Send anyway") }
                },
                dismissButton = {
                    TextButton(onClick = { onAnswer(question, false) }) { Text("Cancel") }
                },
            )

            // The fingerprint is the whole question, so it is the whole dialog:
            // there is nothing to decide here except whether it matches what the
            // server prints, and a paragraph of reassurance would only crowd it.
            is Question.AcceptCertificate -> AlertDialog(
                onDismissRequest = { onAnswer(question, false) },
                title = { Text("Nothing vouches for this certificate") },
                text = { Text(certificateWarning(question)) },
                confirmButton = {
                    TextButton(onClick = { onAnswer(question, true) }) { Text("It matches") }
                },
                dismissButton = {
                    TextButton(onClick = { onAnswer(question, false) }) { Text("Cancel") }
                },
            )
        }
    }
}

private fun certificateWarning(question: Question.AcceptCertificate): String =
    "${question.hostPort} presented a certificate no authority on this device " +
        "vouches for, which is normal for a server you run yourself and is also what " +
        "somebody intercepting the connection would look like.\n\n" +
        "Accept it only if this matches what your server prints:\n\n${question.fingerprint}"

private fun plaintextWarning(question: Question.AcceptPlaintext): String {
    val lead = when (question.reason) {
        PlaintextReason.Typed -> "You asked for an http address."
        PlaintextReason.HttpsUnreachable -> "${question.host} does not answer over https."
    }
    return "$lead Your password would be sent in clear text, readable by anyone " +
        "between this phone and the server. On your own network that may be fine. " +
        "You will only be asked this once for ${question.host}."
}
