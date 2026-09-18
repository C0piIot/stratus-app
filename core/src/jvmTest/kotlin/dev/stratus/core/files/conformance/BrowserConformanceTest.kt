package dev.stratus.core.files.conformance

import dev.stratus.core.dav.DavClient
import dev.stratus.core.files.BrowserController
import dev.stratus.core.files.BrowserState
import dev.stratus.core.files.Confirmation
import dev.stratus.core.files.FileHandoff
import dev.stratus.core.net.Credentials
import dev.stratus.core.net.stratusHttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.io.Sink
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Browsing a real server, started by `make conformance`. */
class BrowserConformanceTest {

    private val url: String = requireNotNull(System.getenv("STRATUS_TEST_URL")) {
        "STRATUS_TEST_URL is unset. Run this with `make conformance`, not directly."
    }
    private val credentials = Credentials(
        System.getenv("STRATUS_TEST_USER") ?: "conformance",
        System.getenv("STRATUS_TEST_PASS") ?: "conformance-secret",
    )

    private val dav = DavClient(stratusHttpClient(CIO.create(), credentials), url)
    private val root = "/browser-${Random.nextLong().toULong().toString(16)}"

    private val handoff = object : FileHandoff {
        val received = mutableListOf<String>()
        override suspend fun open(name: String, contentType: String?, body: suspend (Sink) -> Unit) {
            received += name
        }
        override suspend fun save(name: String, contentType: String?, body: suspend (Sink) -> Unit) {
            received += name
        }
    }

    private suspend fun BrowserController.settled(): BrowserState = state.first { !it.busy }

    @Test
    fun listsWhatIsReallyThere() = runTest {
        dav.makeCollection(root)
        dav.makeCollection("$root/photos")
        dav.put("$root/notes.txt", "hello".encodeToByteArray(), "text/plain")

        val browser = BrowserController(dav, handoff, this)
        browser.enter(dav.stat(root))
        val listed = browser.settled()

        // Folders first, then by name: the order somebody expects, proved against
        // a server that returns them in whatever order it likes.
        assertEquals(listOf("photos", "notes.txt"), listed.entries.map { it.name })
        assertEquals(5L, listed.entries.last().size)
    }

    @Test
    fun renamesAFileAndSeesTheResult() = runTest {
        dav.makeCollection(root)
        dav.put("$root/before.txt", "x".encodeToByteArray())

        val browser = BrowserController(dav, handoff, this)
        browser.enter(dav.stat(root))
        val before = browser.settled()

        browser.ask(Confirmation.Rename(before.entries.single()))
        browser.confirmRename("after.txt")
        assertEquals(listOf("after.txt"), browser.settled().entries.map { it.name })
    }

    @Test
    fun renamesAFolderWithThingsInIt() = runTest {
        // Pinned as a refusal until the server learned to rewrite every path
        // under a directory. The app needed no change for it: it only ever
        // reported what came back.
        dav.makeCollection(root)
        dav.makeCollection("$root/full")
        dav.put("$root/full/inside.txt", "x".encodeToByteArray())

        val browser = BrowserController(dav, handoff, this)
        browser.enter(dav.stat(root))
        val listed = browser.settled()

        browser.ask(Confirmation.Rename(listed.entries.single { it.isDirectory }))
        browser.confirmRename("renamed")

        val after = browser.settled()
        assertNull(after.failure, "was ${after.failure}")
        assertEquals(listOf("renamed"), after.entries.map { it.name })
    }
}
