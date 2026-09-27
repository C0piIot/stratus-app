package dev.stratus.app.e2e

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import dev.stratus.app.MainActivity
import org.junit.After
import org.junit.Rule
import kotlin.random.Random

/**
 * What every end-to-end test shares: the app as somebody opens it, the server
 * behind a link the test can break, and the few gestures every flow starts with.
 *
 * Every test begins from an app that has never run -- the orchestrator clears
 * its data between them -- so nothing here depends on the order they run in.
 */
abstract class E2E {

    @get:Rule
    val ui = createEmptyComposeRule()

    val link = FaultyLink(Stratus.upstream)

    /** A name nobody else's test uses, for folders and files on the shared server. */
    val unique = "e2e${Random.nextInt(1_000_000)}"

    private var scenario: ActivityScenario<MainActivity>? = null

    fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario?.close()
        link.close()
        Phone.airplane(false)
    }

    fun see(text: String, timeoutMs: Long = 15_000, substring: Boolean = true): SemanticsNodeInteraction {
        try {
            ui.waitUntil(timeoutMs) {
                ui.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("never saw \"$text\" on screen", e)
        }
        return ui.onAllNodes(hasText(text, substring = substring))[0]
    }

    fun gone(text: String, timeoutMs: Long = 15_000) = ui.waitUntil(timeoutMs) {
        ui.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isEmpty()
    }

    /** The thing with this label that can be pressed, rather than a title that says the same. */
    fun tap(text: String, timeoutMs: Long = 15_000) {
        val button = hasText(text, substring = false) and hasClickAction()
        try {
            ui.waitUntil(timeoutMs) { ui.onAllNodes(button).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("nothing to press saying \"$text\"", e)
        }
        ui.onAllNodes(button)[0].performClick()
    }

    fun type(field: String, value: String) =
        ui.onNode(hasSetTextAction() and hasText(field, substring = true)).performTextInput(value)

    /** The first sign-in, over plain http and consented to, which is how a home server is reached. */
    fun signIn(address: String = link.address, password: String = Stratus.password) {
        type("Server address", address)
        type("Username", Stratus.user)
        type("Password", password)
        tap("Sign in")
        tap("Send anyway")
    }

    /** Signed in and looking at the root of the tree. */
    fun signedIn() {
        launch()
        signIn()
        see("Servers")
    }

    fun openServers() = tap("Servers")
}
