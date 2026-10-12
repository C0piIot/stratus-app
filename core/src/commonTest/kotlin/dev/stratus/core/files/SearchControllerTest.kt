package dev.stratus.core.files

import dev.stratus.core.dav.DavClient
import dev.stratus.core.dav.DavResource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchControllerTest {

    // Waiting on the state rather than on the scheduler, for the reason
    // BrowserControllerTest gives: the mock engine hops off the test
    // dispatcher, so advanceUntilIdle can return with the request in flight.
    private suspend fun SearchController.searched(): SearchState = state.first { it.ran != null && !it.busy }

    private suspend fun SearchController.asked(): SearchState = state.first { it.offered != null }

    private fun controller(
        scope: TestScope,
        status: HttpStatusCode = HttpStatusCode.MultiStatus,
        body: String = multistatus(found("/files/lisbon tram.jpg")),
        headers: Headers = headersOf("DASL", "<DAV:basicsearch>"),
    ): SearchController {
        val engine = MockEngine { respond(body, status, headers) }
        return SearchController(DavClient(HttpClient(engine), "http://host:8080/"), scope)
    }

    @Test
    fun whetherTheServerCanSearchIsAStateAndNotAFailure() = runTest {
        // The ordinary answer for a WebDAV server that is not a Stratus, and
        // a screen that called it an error would be calling the normal case
        // broken.
        val plain = controller(this, headers = Headers.Empty)
        assertNull(plain.state.value.offered, "nothing is known before the server is asked")

        plain.start()
        assertEquals(false, plain.asked().offered)

        val ours = controller(this)
        ours.start()
        assertEquals(true, ours.asked().offered)
    }

    @Test
    fun anEmptyBoxIsNotASearch() = runTest {
        val controller = controller(this)
        controller.typed("   ")
        controller.submit()

        assertNull(controller.state.value.ran, "a box with nothing in it asked nothing")
        assertFalse(controller.state.value.busy)
    }

    @Test
    fun theResultsAreTheTermThatRanRatherThanTheOneBeingTyped() = runTest {
        val controller = controller(this)
        controller.typed("lisbon")
        controller.submit()
        val settled = controller.searched()

        assertEquals("lisbon", settled.ran)
        assertEquals(listOf("/files/lisbon tram.jpg"), settled.found.map { it.path })

        // Typing the next one does not make the results on screen a lie about
        // what they are the answer to.
        controller.typed("lisb")
        assertEquals("lisbon", controller.state.value.ran)
    }

    @Test
    fun foldersComeFirstHereAsTheyDoInAListing() = runTest {
        val controller = controller(
            this,
            body = multistatus(found("/files/zebra.jpg") + collection("/files/albums/") + found("/files/apple.jpg")),
        )
        controller.typed("a")
        controller.submit()

        assertEquals(
            listOf("/files/albums", "/files/apple.jpg", "/files/zebra.jpg"),
            controller.searched().found.map { it.path },
        )
    }

    @Test
    fun aCutAnswerIsCarriedAndNotSmoothedOver() = runTest {
        val controller = controller(
            this,
            body = multistatus(
                found("/files/a.jpg") + """<response><href>/</href>""" +
                    """<status>HTTP/1.1 507 Insufficient Storage</status></response>""",
            ),
        )
        controller.typed("a")
        controller.submit()
        val settled = controller.searched()

        assertTrue(settled.cut)
        assertEquals(1, settled.found.size)
    }

    @Test
    fun aServerThatRefusesTheMethodIsOneThatCannotSearch() = runTest {
        // The probe's answer arriving late, which is what a server behind a
        // proxy that strips the header looks like. It is not a failure to
        // report: the screen says the server cannot search.
        val controller = controller(this, status = HttpStatusCode.MethodNotAllowed, body = "")
        controller.typed("lisbon")
        controller.submit()
        val settled = controller.asked()

        assertEquals(false, settled.offered)
        assertNull(settled.failure)
    }

    @Test
    fun aSearchThatFoundNothingSaysSoRatherThanLookingBroken() = runTest {
        val controller = controller(this, body = multistatus(""))
        controller.typed("nothing at all")
        controller.submit()

        assertTrue(controller.searched().empty)
        assertNull(controller.state.value.failure)
    }

    private fun multistatus(responses: String) = """<multistatus xmlns="DAV:">$responses</multistatus>"""

    private fun found(href: String) = """<response><href>$href</href><propstat><prop>""" +
        """<resourcetype/></prop><status>HTTP/1.1 200 OK</status></propstat></response>"""

    private fun collection(href: String) = """<response><href>$href</href><propstat><prop>""" +
        """<resourcetype><collection/></resourcetype></prop>""" +
        """<status>HTTP/1.1 200 OK</status></propstat></response>"""
}
