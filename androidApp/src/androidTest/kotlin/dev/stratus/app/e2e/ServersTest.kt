package dev.stratus.app.e2e

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Test

class ServersTest : E2E() {

    private val second = FaultyLink(Stratus.upstream)

    @After
    fun closeSecond() = second.close()

    // Same host, so the consent given to the first is the answer for the second.
    @Test
    fun aSecondServerIsAddedAndEitherCanBeLookedAt() {
        signedIn()
        openServers()
        tap("Add")
        signIn(address = second.address, consentAsked = false)
        see("Servers")

        openServers()
        see(link.address)
        see(second.address)
        tap("${link.address}/dav/")
        tap("Back")
        see("Servers")
    }

    @Test
    fun removingAsksFirstAndThenForgetsTheServer() {
        signedIn()
        openServers()
        tap("Add")
        signIn(address = second.address, consentAsked = false)
        openServers()

        tap("Remove")
        see("Remove http")
        tap("Cancel")
        see(second.address)

        tap("Remove")
        see("Remove http")
        // The confirm button, which the dialog puts after the one in the row.
        val confirm = hasText("Remove", substring = false) and hasClickAction()
        val buttons = ui.onAllNodes(confirm).fetchSemanticsNodes().size
        ui.onAllNodes(confirm)[buttons - 1].performClick()
        see("Servers")
        openServers()
        gone(link.address)
    }
}
