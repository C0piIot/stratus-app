package dev.stratus.core.dav

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.request.HttpRequestData
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DavClientTest {

    private var lastRequest: HttpRequestData? = null

    private fun clientAnswering(
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = "",
        headers: Headers = Headers.Empty,
    ): DavClient {
        val engine = MockEngine { request ->
            lastRequest = request
            respond(body, status, headers)
        }
        return DavClient(HttpClient(engine), "http://host:8080/dav/")
    }

    private fun multistatus(vararg hrefs: String) = buildString {
        append("""<multistatus xmlns="DAV:">""")
        for (href in hrefs) {
            append("""<response><href>$href</href><propstat><prop><resourcetype/>""")
            append("""</prop><status>HTTP/1.1 200 OK</status></propstat></response>""")
        }
        append("</multistatus>")
    }

    @Test
    fun asksForACollectionWithTheSlashRfc4918PutsOnIt() = runTest {
        // A path is held without one, so this is the only place it can go back
        // on -- and a server asked for a collection without it answers a
        // redirect rather than a listing. Go's ServeMux does, which is what a
        // tree mounted at `/files` taught us.
        val client = clientAnswering(HttpStatusCode.MultiStatus, multistatus("/dav/Photos/"))
        client.list("/Photos")
        assertEquals("http://host:8080/dav/Photos/", lastRequest?.url.toString())

        // The root is already a slash and does not get a second one.
        client.list("/")
        assertEquals("http://host:8080/dav/", lastRequest?.url.toString())

        // A file is asked for as itself: a slash there is a different resource.
        client.stat("/Photos/one.txt")
        assertEquals("http://host:8080/dav/Photos/one.txt", lastRequest?.url.toString())
    }

    @Test
    fun dropsTheCollectionItselfFromAListing() = runTest {
        // A spec-compliant server includes the collection in a Depth: 1 answer.
        val client = clientAnswering(
            HttpStatusCode.MultiStatus,
            multistatus("/dav/Photos", "/dav/Photos/one.txt", "/dav/Photos/two.txt"),
        )
        assertEquals(listOf("/Photos/one.txt", "/Photos/two.txt"), client.list("/Photos").map { it.path })
    }

    @Test
    fun listsTheSameWhenTheServerOmitsTheCollection() = runTest {
        // Stratus used to do this -- stratus-backend#126, since fixed -- and
        // other servers still might. The client must not care either way.
        val client = clientAnswering(
            HttpStatusCode.MultiStatus,
            multistatus("/dav/Photos/one.txt", "/dav/Photos/two.txt"),
        )
        assertEquals(listOf("/Photos/one.txt", "/Photos/two.txt"), client.list("/Photos").map { it.path })
    }

    @Test
    fun toleratesAMultistatusSentAsPlainTwoHundred() = runTest {
        val client = clientAnswering(HttpStatusCode.OK, multistatus("/dav/Photos/one.txt"))
        assertEquals(1, client.list("/Photos").size)
    }

    @Test
    fun percentEncodesThePathItAsksFor() = runTest {
        val client = clientAnswering(HttpStatusCode.MultiStatus, multistatus())
        client.list("/Photos/Hello World & co")
        assertEquals(
            "/dav/Photos/Hello%20World%20%26%20co/",
            lastRequest?.url?.encodedPath,
        )
    }

    @Test
    fun asksForTheDepthItMeans() = runTest {
        val client = clientAnswering(HttpStatusCode.MultiStatus, multistatus("/dav/a"))
        client.stat("/a")
        assertEquals("0", lastRequest?.headers?.get("Depth"))
        client.list("/")
        assertEquals("1", lastRequest?.headers?.get("Depth"))
    }

    @Test
    fun sendsAByteRangeWhenAskedForOne() = runTest {
        val client = clientAnswering(HttpStatusCode.PartialContent, "0123456789")
        val bytes = client.read("/big.bin", 10L..19L) { it.readRemaining().readByteArray() }
        assertEquals("bytes=10-19", lastRequest?.headers?.get("Range"))
        assertEquals(10, bytes.size)
    }

    @Test
    fun readsWholeFilesWithNoRangeHeader() = runTest {
        val client = clientAnswering(HttpStatusCode.OK, "hello")
        val bytes = client.read("/a.txt") { it.readRemaining().readByteArray() }
        assertNull(lastRequest?.headers?.get("Range"))
        assertEquals("hello", bytes.decodeToString())
    }

    @Test
    fun moveCarriesADestinationAndRefusesToOverwriteByDefault() = runTest {
        val client = clientAnswering(HttpStatusCode.Created)
        client.move("/a.txt", "/b c.txt")
        assertEquals("http://host:8080/dav/b%20c.txt", lastRequest?.headers?.get("Destination"))
        assertEquals("F", lastRequest?.headers?.get("Overwrite"))
        assertEquals("MOVE", lastRequest?.method?.value)
    }

    @Test
    fun putReturnsTheEtagWithoutItsQuotes() = runTest {
        val client = clientAnswering(HttpStatusCode.Created, headers = headersOf("ETag", "\"abc123\""))
        assertEquals("abc123", client.put("/a.txt", byteArrayOf(1, 2, 3), "image/jpeg"))
    }

    @Test
    fun reportsRejectedCredentialsAsSuch() = runTest {
        val client = clientAnswering(HttpStatusCode.Unauthorized)
        val thrown = runCatching { client.list("/") }.exceptionOrNull()
        assertTrue(thrown is DavError.Unauthorized, "was $thrown")
    }

    @Test
    fun reportsAMissingPathAsNotFound() = runTest {
        val client = clientAnswering(HttpStatusCode.NotFound)
        val thrown = runCatching { client.stat("/nope") }.exceptionOrNull()
        assertTrue(thrown is DavError.NotFound, "was $thrown")
    }

    @Test
    fun reportsAResourceThatWillNotAnswerAsMethodNotAllowed() = runTest {
        // Not an `Unexpected`: a server that keeps its files under a longer path
        // answers 405 on the shallower one, and only that tells the difference
        // between nothing being there and the wrong floor of the right building.
        val client = clientAnswering(HttpStatusCode.MethodNotAllowed)
        val thrown = runCatching { client.list("/") }.exceptionOrNull()
        assertTrue(thrown is DavError.MethodNotAllowed, "was $thrown")
    }

    @Test
    fun conflictKeepsWhateverTheServerSaid() = runTest {
        // Measured from a real Stratus: renaming a folder with anything in it.
        // The status alone cannot distinguish that from a missing parent, so the
        // server's own words travel with the error.
        val client = clientAnswering(
            HttpStatusCode.Conflict,
            """409 Conflict: db: already exists: "Full" is a directory and is not empty""",
        )
        val thrown = runCatching { client.move("/Full", "/Renamed") }.exceptionOrNull()
        assertTrue(thrown is DavError.Conflict, "was $thrown")
        assertTrue(thrown.detail.contains("is not empty"), "detail was ${thrown.detail}")
    }

    @Test
    fun theTwoWaysAMkcolIsRefusedAreTwoErrors() = runTest {
        // RFC 4918 9.3.1: 405 is "already there" and 409 is a missing parent.
        // One flattened into the other is what sent the directory maker up a
        // tree it had no business creating.
        val taken = clientAnswering(HttpStatusCode.MethodNotAllowed)
        assertTrue(
            runCatching { taken.makeCollection("/Photos") }.exceptionOrNull() is DavError.AlreadyExists,
        )

        val orphan = clientAnswering(HttpStatusCode.Conflict, "no such parent")
        assertTrue(
            runCatching { orphan.makeCollection("/a/b") }.exceptionOrNull() is DavError.Conflict,
        )
    }

    @Test
    fun anythingElseKeepsItsStatusForTheLog() = runTest {
        val client = clientAnswering(HttpStatusCode.InternalServerError)
        val thrown = runCatching { client.delete("/a.txt") }.exceptionOrNull()
        assertTrue(thrown is DavError.Unexpected, "was $thrown")
        assertEquals(500, thrown.status)
    }

    @Test
    fun aTransferHasNoDeadlineAndAListingDoes() = runTest {
        // Twenty seconds for a whole request cut every upload longer than that,
        // and a PUT cannot resume (STRATUS-BACKEND-3).
        val client = clientAnswering(HttpStatusCode.Created)
        client.put("/clip.mp4", 3, Buffer().apply { write(byteArrayOf(1, 2, 3)) })
        assertEquals(HttpTimeoutConfig.INFINITE_TIMEOUT_MS, requestDeadline())

        clientAnswering(body = "abc").read("/clip.mp4") { it.readRemaining() }
        assertEquals(HttpTimeoutConfig.INFINITE_TIMEOUT_MS, requestDeadline())

        clientAnswering(HttpStatusCode.MultiStatus, multistatus("/dav/")).list("/")
        assertNull(requestDeadline(), "a listing lost the client's own deadline")
    }

    private fun requestDeadline() = lastRequest?.getCapabilityOrNull(HttpTimeoutCapability)?.requestTimeoutMillis
}
