package dev.stratus.app.e2e

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import org.junit.Test

/**
 * Signing out, which is the only way to a different server (stratus-app#131).
 *
 * What was `ServersTest` -- adding a second and switching between them -- is
 * gone with the concept. What is left is the one door out, and it is worth a
 * test because of what it costs: the backup record goes with the password.
 */
class SignOutTest : E2E() {

    @Test
    fun signingOutAsksFirstAndThenForgetsTheServer() {
        signedIn()
        openServer()
        see(link.address)

        tap("Sign out")
        see("Sign out of http")
        tap("Cancel")
        see(link.address)

        tap("Sign out")
        see("Sign out of http")
        // The confirm button, which the dialog puts after the one on the page.
        val confirm = hasText("Sign out", substring = false) and hasClickAction()
        val buttons = ui.onAllNodes(confirm).fetchSemanticsNodes().size
        ui.onAllNodes(confirm)[buttons - 1].performClick()

        // Back at the sign-in form, with nothing filled in.
        see("Server address")
    }
}
