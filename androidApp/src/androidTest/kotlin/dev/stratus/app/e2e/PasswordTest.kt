package dev.stratus.app.e2e

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import org.junit.Test
import kotlin.test.assertEquals

/** stratus-app#87: a server whose password changed, followed without losing the server. */
class PasswordTest : E2E() {

    private fun typePassword(value: String) {
        val field = hasSetTextAction() and hasText("New password", substring = true)
        ui.onNode(field).performTextClearance()
        ui.onNode(field).performTextInput(value)
    }

    @Test
    fun aServerThatStoppedAcceptingThePasswordIsFollowedFromTheBrowser() {
        signedIn()
        // The server is now one that knows this user by another password.
        link.retarget(Stratus.otherUpstream)
        ui.onAllNodes(hasScrollAction())[0].performTouchInput { swipeDown() }
        see("no longer accepts this sign-in")

        tap("Change password")
        typePassword("still-wrong")
        tap("Save")
        see("did not accept it")

        typePassword(Stratus.otherPassword)
        tap("Save")
        gone("New password")
        tap("Back")
        see("Servers")
        gone("no longer accepts this sign-in")

        // The same server, not a second one beside it.
        openServers()
        assertEquals(1, ui.onAllNodes(hasText(link.address, substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun aWrongNewPasswordIsRefusedAndTheOldOneStillWorks() {
        Stratus.folder("/$unique/")
        signedIn()
        openServers()

        tap("Change password")
        typePassword("not-it")
        tap("Save")
        see("The old one is kept")
        tap("Cancel")
        tap("Back")

        tap(unique)
        see("/$unique")
    }
}
