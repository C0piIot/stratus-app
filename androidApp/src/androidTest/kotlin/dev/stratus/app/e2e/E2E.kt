package dev.stratus.app.e2e

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import dev.stratus.app.MainActivity
import org.junit.After
import org.junit.Before
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

    // Said once and plainly, rather than as twenty tests each failing to find
    // a dialog that only appears once a server has answered.
    //
    // Retried for a while, because the first test of a run starts on an
    // emulator whose network may not be up yet: ENETUNREACH, not a server.
    @Before
    fun theServerIsThere() {
        var last: Exception? = null
        repeat(30) {
            try {
                Stratus.exists("/")
                return
            } catch (e: Exception) {
                last = e
                Thread.sleep(1_000)
            }
        }
        throw AssertionError("the backend is not reachable at ${Stratus.upstream} from the emulator", last)
    }

    // And through the link, which is the path the app takes: a link that
    // cannot forward would otherwise look like the app failing to sign in.
    @Before
    fun theLinkForwards() {
        val answer = runCatching {
            (java.net.URL("${link.address}/healthz").openConnection() as java.net.HttpURLConnection).run {
                connectTimeout = 5_000
                readTimeout = 5_000
                inputStream.use { it.readBytes().decodeToString() }
            }
        }
        if (answer.getOrNull()?.trim() != "ok") {
            throw AssertionError("the link at ${link.address} does not forward: $answer")
        }
    }

    /** Everything on screen, for a failure to say what it saw instead. */
    fun screen(): String = try {
        ui.onAllNodes(isRoot()).fetchSemanticsNodes().indices.joinToString("\n") {
            ui.onAllNodes(isRoot())[it].printToString()
        }
    } catch (e: Exception) {
        "(no screen: ${e.message})"
    }

    fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario?.close()
        link.close()
        Phone.airplane(false)
        // Every test shares one server, and a root that outgrows the screen
        // hides the next test's folder below the fold.
        Stratus.remove("/$unique/")
        Stratus.remove("/${unique}b/")
    }

    fun see(text: String, timeoutMs: Long = 15_000, substring: Boolean = true): SemanticsNodeInteraction {
        val wanted = hasText(text, substring = substring)
        fun found() = ui.onAllNodes(wanted).fetchSemanticsNodes().isNotEmpty()
        try {
            ui.waitUntil(timeoutMs) {
                if (found()) return@waitUntil true
                // A list only composes what is on screen, so something further
                // down is not there to be seen until the list is scrolled to it.
                runCatching {
                    if (ui.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty()) {
                        ui.onAllNodes(hasScrollToNodeAction())[0].performScrollToNode(wanted)
                    }
                }
                found()
            }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("never saw \"$text\" on screen. It showed:\n${screen()}", e)
        }
        return ui.onAllNodes(wanted)[0]
    }

    fun gone(text: String, timeoutMs: Long = 15_000) = ui.waitUntil(timeoutMs) {
        ui.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isEmpty()
    }

    /** The thing with this label that can be pressed, rather than a title that says the same. */
    fun tap(text: String, timeoutMs: Long = 15_000) {
        val button = hasText(text, substring = false) and hasClickAction()
        try {
            ui.waitUntil(timeoutMs) {
                if (ui.onAllNodes(button).fetchSemanticsNodes().isNotEmpty()) return@waitUntil true
                runCatching {
                    if (ui.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty()) {
                        ui.onAllNodes(hasScrollToNodeAction())[0].performScrollToNode(button)
                    }
                }
                ui.onAllNodes(button).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("nothing to press saying \"$text\". The screen showed:\n${screen()}", e)
        }
        // Once more if it was redrawn between being found and being pressed.
        try {
            ui.onAllNodes(button)[0].performClick()
        } catch (_: AssertionError) {
            ui.waitUntil(timeoutMs) { ui.onAllNodes(button).fetchSemanticsNodes().isNotEmpty() }
            ui.onAllNodes(button)[0].performClick()
        }
    }

    fun type(field: String, value: String) =
        ui.onNode(hasSetTextAction() and hasText(field, substring = true)).performTextInput(value)

    /**
     * A sign-in over plain http, consented to when asked -- and only the first
     * time for a host, since the answer is remembered per host, which is what
     * a second server on the same machine shows.
     */
    fun signIn(address: String = link.address, password: String = Stratus.password, consentAsked: Boolean = true) {
        type("Server address", address)
        type("Username", Stratus.user)
        type("Password", password)
        tap("Sign in")
        if (consentAsked) tap("Send anyway")
    }

    /** Signed in and looking at the root of the tree. */
    fun signedIn() {
        launch()
        signIn()
        see("Servers")
    }

    fun openServers() = tap("Servers")

    /**
     * Turns backup on for the first server and answers what the system asks on
     * the way -- the notification permission the first time (stratus-app#81),
     * and the library too if it was not granted. Left up, a dialog pauses the
     * app and with it the status the tests read.
     */
    fun turnBackupOn(allow: Boolean = true) {
        openServers()
        ui.onAllNodes(isToggleable())[0].performClick()
        Phone.answerAll(allow)
        tap("Back")
    }
}
