package dev.stratus.core.files

import dev.stratus.core.dav.DavClient
import dev.stratus.core.dav.DavResource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.Sink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class RecordingHandoff : FileHandoff {
    val opened = mutableListOf<String>()
    val saved = mutableListOf<String>()
    override suspend fun open(name: String, contentType: String?, body: suspend (Sink) -> Unit) {
        opened += name
    }
    override suspend fun save(name: String, contentType: String?, body: suspend (Sink) -> Unit) {
        saved += name
    }
}

class BrowserControllerTest {

    private val seen = mutableListOf<HttpRequestData>()
    private val handoff = RecordingHandoff()

    private fun listing(vararg entries: Pair<String, Boolean>) = buildString {
        append("""<multistatus xmlns="DAV:">""")
        for ((href, directory) in entries) {
            append("<response><href>$href</href><propstat><prop><resourcetype>")
            if (directory) append("<collection/>")
            append("</resourcetype></prop><status>HTTP/1.1 200 OK</status></propstat></response>")
        }
        append("</multistatus>")
    }

    private fun controller(
        scope: TestScope,
        answer: (HttpRequestData) -> Pair<HttpStatusCode, String>,
    ): BrowserController {
        val engine = MockEngine { request ->
            seen += request
            val (status, body) = answer(request)
            respond(body, status)
        }
        return BrowserController(DavClient(HttpClient(engine), "http://host/dav/"), handoff, scope)
    }

    // Waiting on the state rather than on the scheduler: the mock engine hops off
    // the test dispatcher, so advanceUntilIdle can return with the request still
    // in flight -- which shows up as an empty listing in a test that looks right.
    private suspend fun BrowserController.settled(): BrowserState = state.first { !it.busy }

    private fun listingOf(vararg entries: Pair<String, Boolean>) =
        { _: HttpRequestData -> HttpStatusCode.MultiStatus to listing(*entries) }

    @Test
    fun putsFoldersFirstAndThenSortsByNameIgnoringCase() = runTest {
        val browser = controller(
            this,
            listingOf("/dav/zebra.txt" to false, "/dav/Apple.txt" to false, "/dav/photos" to true),
        )
        browser.start()
        browser.settled()
        assertEquals(listOf("photos", "Apple.txt", "zebra.txt"), browser.state.value.entries.map { it.name })
    }

    @Test
    fun walksIntoAFolderAndBackOutOfIt() = runTest {
        val browser = controller(this, listingOf("/dav/photos" to true))
        browser.start()
        browser.settled()

        browser.enter(browser.state.value.entries.single())
        browser.settled()
        assertEquals("/photos", browser.state.value.path)

        assertTrue(browser.goUp())
        browser.settled()
        assertEquals("/", browser.state.value.path)
        // And the root is the end of the line, which is what the back button asks.
        assertTrue(!browser.goUp())
    }

    @Test
    fun deletingAsksFirst() = runTest {
        // There is no trash anywhere in Stratus, so the question is the only
        // chance somebody has to have not meant it.
        val browser = controller(this) { request ->
            if (request.method.value == "DELETE") HttpStatusCode.NoContent to ""
            else HttpStatusCode.MultiStatus to listing("/dav/a.txt" to false)
        }
        browser.start()
        browser.settled()
        val target = browser.state.value.entries.single()

        // Nothing happens until it is confirmed.
        browser.ask(Confirmation.Delete(target))
        browser.settled()
        assertTrue(seen.none { it.method.value == "DELETE" })

        browser.confirmDelete()
        browser.settled()
        assertTrue(seen.any { it.method.value == "DELETE" })
        assertNull(browser.state.value.pending)
    }

    @Test
    fun dismissingAConfirmationDoesNothingAtAll() = runTest {
        val browser = controller(this, listingOf("/dav/a.txt" to false))
        browser.start()
        browser.settled()

        browser.ask(Confirmation.Delete(browser.state.value.entries.single()))
        browser.dismiss()
        browser.confirmDelete()
        browser.settled()
        assertTrue(seen.none { it.method.value == "DELETE" })
    }

    @Test
    fun renamingKeepsTheFileWhereItWas() = runTest {
        val browser = controller(this) { request ->
            if (request.method.value == "MOVE") HttpStatusCode.Created to ""
            else HttpStatusCode.MultiStatus to listing("/dav/photos/a.txt" to false)
        }
        browser.start()
        browser.settled()

        browser.ask(Confirmation.Rename(browser.state.value.entries.single()))
        browser.confirmRename("b.txt")
        browser.settled()

        val move = seen.single { it.method.value == "MOVE" }
        assertEquals("http://host/dav/photos/b.txt", move.headers["Destination"])
    }

    @Test
    fun refusesARenameThatWouldBeAMove() = runTest {
        // A typed path would otherwise carry the file across the tree quietly.
        val browser = controller(this, listingOf("/dav/a.txt" to false))
        browser.start()
        browser.settled()

        browser.ask(Confirmation.Rename(browser.state.value.entries.single()))
        browser.confirmRename("../elsewhere/b.txt")
        browser.settled()
        assertTrue(seen.none { it.method.value == "MOVE" })
    }

    @Test
    fun saysWhyAFolderWithThingsInItWillNotRename() = runTest {
        // The server answers a plain 409 here and the same 409 for a missing
        // parent. Only the app knows it asked to rename a directory.
        val browser = controller(this) { request ->
            if (request.method.value == "MOVE") HttpStatusCode.Conflict to "is a directory and is not empty"
            else HttpStatusCode.MultiStatus to listing("/dav/photos" to true)
        }
        browser.start()
        browser.settled()

        browser.ask(Confirmation.Rename(browser.state.value.entries.single()))
        browser.confirmRename("pictures")
        browser.settled()

        assertEquals(
            BrowserFailure.RenameNeedsAnEmptyFolder("photos"),
            browser.state.value.failure,
        )
    }

    @Test
    fun handsAFileToThePlatformForOpeningAndForKeeping() = runTest {
        val browser = controller(this) { request ->
            if (request.method.value == "GET") HttpStatusCode.OK to "some bytes"
            else HttpStatusCode.MultiStatus to listing("/dav/a.txt" to false)
        }
        browser.start()
        browser.settled()
        val file = browser.state.value.entries.single()

        browser.openFile(file)
        browser.settled()
        browser.saveFile(file)
        browser.settled()

        assertEquals(listOf("a.txt"), handoff.opened)
        assertEquals(listOf("a.txt"), handoff.saved)
    }

    @Test
    fun leavesAUsableScreenWhenTheServerFails() = runTest {
        val browser = controller(this) { _ -> HttpStatusCode.InternalServerError to "" }
        browser.start()
        browser.settled()

        assertTrue(browser.state.value.failure is BrowserFailure.Listing)
        assertTrue(!browser.state.value.busy, "left spinning")
    }
}
