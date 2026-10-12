package dev.stratus.core.dav

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Searching, which is RFC 5323 and therefore a question before it is a
 * request: most WebDAV servers do not have the method at all.
 */
class SearchTest {

    private var lastRequest: HttpRequestData? = null

    private fun clientAnswering(
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = "",
        headers: Headers = Headers.Empty,
        base: String = "http://host:8080/",
    ): DavClient {
        val engine = MockEngine { request ->
            lastRequest = request
            respond(body, status, headers)
        }
        return DavClient(HttpClient(engine), base)
    }

    private suspend fun sentBody(): String = lastRequest?.sentBody() ?: ""

    @Test
    fun theServerIsAskedWhetherItCanSearchAtAll() = runTest {
        val can = clientAnswering(headers = headersOf("DASL", "<DAV:basicsearch>"))
        assertTrue(can.canSearch())
        assertEquals("OPTIONS", lastRequest?.method?.value)

        // A server that answers the method and names some other grammar is
        // one this client cannot write a query for.
        val other = clientAnswering(headers = headersOf("DASL", "<http://example.test/syntax>"))
        assertFalse(other.canSearch())

        // And the ordinary case, which is every WebDAV server that is not
        // ours: no header at all.
        assertFalse(clientAnswering().canSearch())
    }

    @Test
    fun theQueryIsABasicsearchOverTheCollectionTheAppIsRootedAt() = runTest {
        val client = clientAnswering(HttpStatusCode.MultiStatus, answer())
        client.search("lisbon", limit = 25)

        assertEquals("SEARCH", lastRequest?.method?.value)
        assertEquals("http://host:8080/", lastRequest?.url.toString())

        val body = sentBody()
        for (part in listOf(
            "<searchrequest xmlns=\"DAV:\">",
            "<contains>lisbon</contains>",
            "<href>/</href>",
            "<depth>infinity</depth>",
            "<nresults>25</nresults>",
            // The same properties a listing asks for: a result is drawn the
            // way a row is, so asking for less would mean two kinds of row.
            "<getetag/>",
        )) {
            assertTrue(part in body, "the query does not carry $part:\n$body")
        }
    }

    @Test
    fun aFolderIsAScopeOfItsOwn() = runTest {
        // stratus-app#146: searching where somebody is standing. The href is
        // a URL, so the folder goes in encoded -- a scope read as a different
        // path is a search of somewhere else.
        val client = clientAnswering(HttpStatusCode.MultiStatus, answer())
        client.search("lisbon", under = "holiday/last summer")
        assertTrue("<href>/holiday/last%20summer/</href>" in sentBody(), sentBody())

        // And a leading or trailing slash is not a second path: it is the
        // same folder written the way a browser's state writes it.
        client.search("lisbon", under = "/holiday/")
        assertTrue("<href>/holiday/</href>" in sentBody(), sentBody())

        // No folder is the collection, which is every caller that has none.
        client.search("lisbon")
        assertTrue("<href>/</href>" in sentBody(), sentBody())
    }

    @Test
    fun aTermWithXmlInItIsStillATerm() = runTest {
        // An ampersand in a filename is ordinary, and a body that was not
        // valid XML would come back as a 400 nobody could explain.
        val client = clientAnswering(HttpStatusCode.MultiStatus, answer())
        client.search("rock & roll <2024>")
        assertTrue("<contains>rock &amp; roll &lt;2024&gt;</contains>" in sentBody(), sentBody())
    }

    @Test
    fun theResultsAreTheSameRowsAListingIsMadeOf() = runTest {
        val client = clientAnswering(HttpStatusCode.MultiStatus, answer())
        val answer = client.search("lisbon")

        assertEquals(
            listOf("/files/holiday/lisbon tram.jpg", "/files/lisbon"),
            answer.found.map { it.path },
        )
        assertEquals(listOf(false, true), answer.found.map { it.isDirectory })
        assertEquals(1234L, answer.found[0].size)
        assertFalse(answer.cut)
    }

    @Test
    fun anAnswerTheServerCutSaysSo() = runTest {
        // 2.3.1: the arbiter's own href at 507, beside the partial results.
        // Without this a hundred results out of a thousand look like all of
        // them, which is the one thing a search must not do.
        val client = clientAnswering(HttpStatusCode.MultiStatus, answer(cut = true))
        val answer = client.search("a")

        assertTrue(answer.cut)
        // And the arbiter is not one of the results.
        assertEquals(2, answer.found.size)
    }

    @Test
    fun aServerWithoutTheMethodSaysSoRatherThanFailing() = runTest {
        val client = clientAnswering(HttpStatusCode.MethodNotAllowed)
        assertFailsWith<DavError.MethodNotAllowed> { client.search("lisbon") }
    }

    private fun answer(cut: Boolean = false) = buildString {
        append("""<multistatus xmlns="DAV:">""")
        append(
            """<response><href>/files/holiday/lisbon%20tram.jpg</href><propstat><prop>""" +
                """<resourcetype/><getcontentlength>1234</getcontentlength>""" +
                """</prop><status>HTTP/1.1 200 OK</status></propstat></response>""",
        )
        append(
            """<response><href>/files/lisbon/</href><propstat><prop>""" +
                """<resourcetype><collection/></resourcetype>""" +
                """</prop><status>HTTP/1.1 200 OK</status></propstat></response>""",
        )
        if (cut) {
            append("""<response><href>/</href><status>HTTP/1.1 507 Insufficient Storage</status>""")
            append("""<responsedescription>too many</responsedescription></response>""")
        }
        append("</multistatus>")
    }
}
