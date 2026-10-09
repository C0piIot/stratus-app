package dev.stratus.app.e2e

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals

/**
 * stratus-app#87: a server's details change -- its password, its address --
 * and the app follows without losing the server or its backup record.
 */
class EditServerTest : E2E() {

    private val moved = FaultyLink(Stratus.upstream)

    @After
    fun closeMoved() = moved.close()

    private fun replace(field: String, value: String) {
        val node = hasSetTextAction() and hasText(field, substring = true)
        ui.onNode(node).performTextClearance()
        ui.onNode(node).performTextInput(value)
    }

    private fun shownAs(address: String) =
        ui.onAllNodes(hasText(address, substring = true)).fetchSemanticsNodes().size

    @Test
    fun aPasswordChangedOnTheServerIsFollowedFromTheBrowser() {
        signedIn()
        // Now a server that knows this user by another password.
        link.retarget(Stratus.otherUpstream)
        ui.onAllNodes(hasScrollAction())[0].performTouchInput { swipeDown() }
        see("no longer accepts this sign-in")

        tap("Edit server")
        see("Edit server")
        replace("Password", "still-wrong")
        tap("Save")
        see("did not accept that username and password")

        replace("Password", Stratus.otherPassword)
        tap("Save")
        see("Server")
        gone("no longer accepts this sign-in")

        openServer()
        assertEquals(1, shownAs(link.address), "the server screen shows it more than once")
    }

    // A server moved to a new address is the same server.
    @Test
    fun aNewAddressKeepsTheSameServer() {
        signedIn()
        openServer()
        tap("Edit")
        replace("Server address", moved.address)
        // The same host, so the plain-http answer already given still stands.
        tap("Save")
        see("Server")

        openServer()
        assertEquals(1, shownAs(moved.address))
        assertEquals(0, shownAs(link.address), "the old address is still shown")
    }

    @Test
    fun cancellingAnEditChangesNothing() {
        signedIn()
        openServer()
        tap("Edit")
        replace("Username", "somebody-else")
        tap("Cancel")
        see("Server")

        openServer()
        see(link.address)
        gone("somebody-else", timeoutMs = 1_000)
    }
}
