package dev.stratus.core.backup

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TusTransportTest {

    private val seen = mutableListOf<HttpRequestData>()
    private val target = UploadTarget("/Photos/2026/09/clip.mov", 10, null)

    private fun transport(
        etag: String? = "the-etag",
        answer: (HttpRequestData) -> Pair<HttpStatusCode, io.ktor.http.Headers>,
    ): TusTransport {
        val engine = MockEngine { request ->
            seen += request
            val (status, headers) = answer(request)
            respond("", status, headers)
        }
        return TusTransport(HttpClient(engine), "http://host/tus/") { etag }
    }

    private fun bytes(from: Long) = Buffer().apply { write(ByteArray(10), from.toInt(), 10) }

    @Test
    fun createsThenSendsAndIsDoneWhenItHasSentItAll() = runTest {
        val transport = transport { request ->
            when (request.method.value) {
                "POST" -> HttpStatusCode.Created to headersOf("Location", "/tus/abc")
                else -> HttpStatusCode.NoContent to headersOf("Upload-Offset", "10")
            }
        }
        // There is no "finish" in the protocol: the file exists once the upload
        // is as long as it said it would be.
        assertEquals(UploadOutcome.Done("the-etag"), transport.send(target, null, ::bytes))
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun namesTheDestinationAsAPathWithoutItsLeadingSlash() = runTest {
        // The server treats filename as a path and puts the file there, which is
        // why the queue creates the month's folder before every attempt.
        val transport = transport { request ->
            when (request.method.value) {
                "POST" -> HttpStatusCode.Created to headersOf("Location", "/tus/abc")
                else -> HttpStatusCode.NoContent to headersOf("Upload-Offset", "10")
            }
        }
        transport.send(target, null, ::bytes)

        val metadata = seen.first { it.method.value == "POST" }.headers["Upload-Metadata"]!!
        assertEquals(
            "filename " + Base64.encode("Photos/2026/09/clip.mov".encodeToByteArray()),
            metadata,
        )
        assertEquals("10", seen.first().headers["Upload-Length"])
        assertEquals("1.0.0", seen.first().headers["Tus-Resumable"])
    }

    @Test
    fun carriesOnFromWhereTheServerSaysItGotTo() = runTest {
        val transport = transport { request ->
            when (request.method.value) {
                "HEAD" -> HttpStatusCode.OK to headersOf("Upload-Offset", "4")
                else -> HttpStatusCode.NoContent to headersOf("Upload-Offset", "10")
            }
        }
        assertEquals(
            UploadOutcome.Done("the-etag"),
            transport.send(target, Resume("http://host/tus/abc", 4), ::bytes),
        )
        // Asked, not assumed -- and the bytes go out from the answer.
        assertEquals("4", seen.single { it.method.value == "PATCH" }.headers["Upload-Offset"])
    }

    @Test
    fun believesTheServerOverItselfWhenTheyDisagree() = runTest {
        // A 409 is what a retry after a timeout produces: the client thinks it is
        // at one offset and the server is at another.
        var patches = 0
        val transport = transport { request ->
            when {
                request.method.value == "PATCH" && patches++ == 0 -> HttpStatusCode.Conflict to headersOf()
                request.method.value == "HEAD" -> HttpStatusCode.OK to headersOf("Upload-Offset", "7")
                else -> HttpStatusCode.NoContent to headersOf("Upload-Offset", "10")
            }
        }
        val outcome = transport.send(target, Resume("http://host/tus/abc", 7), ::bytes)
        assertEquals(UploadOutcome.Interrupted(Resume("http://host/tus/abc", 7)), outcome)
    }

    @Test
    fun anUploadThatIsGoneMeansDoneWhenTheFileIsThere() = runTest {
        // A HEAD after completion answers 404. So does an upload that expired
        // after twelve hours, and the ETag is what tells the two apart.
        val transport = transport(etag = "already-there") { HttpStatusCode.NotFound to headersOf() }
        assertEquals(
            UploadOutcome.Done("already-there"),
            transport.send(target, Resume("http://host/tus/abc", 4), ::bytes),
        )
    }

    @Test
    fun anUploadThatExpiredStartsAgain() = runTest {
        val transport = transport(etag = null) { HttpStatusCode.NotFound to headersOf() }
        val outcome = transport.send(target, Resume("http://host/tus/abc", 4), ::bytes)
        // No handle and no offset: the next attempt creates a fresh upload.
        assertEquals(UploadOutcome.Interrupted(Resume(null, 0)), outcome)
    }

    @Test
    fun reportsHowFarItGotWhenItStopsPartWay() = runTest {
        val transport = transport { request ->
            when (request.method.value) {
                "POST" -> HttpStatusCode.Created to headersOf("Location", "/tus/abc")
                else -> HttpStatusCode.NoContent to headersOf("Upload-Offset", "6")
            }
        }
        val outcome = transport.send(target, null, ::bytes)
        assertEquals(UploadOutcome.Interrupted(Resume("http://host/tus/abc", 6)), outcome)
    }

    @Test
    fun rejectedCredentialsAreNotWorthRetrying() = runTest {
        val transport = transport { request ->
            when (request.method.value) {
                "POST" -> HttpStatusCode.Created to headersOf("Location", "/tus/abc")
                else -> HttpStatusCode.Unauthorized to headersOf()
            }
        }
        val outcome = transport.send(target, null, ::bytes)
        assertTrue(outcome is UploadOutcome.Failed, "was $outcome")
        assertEquals(FailureKind.Permanent, outcome.kind)
    }
}

class NegotiateTusTest {

    private fun clientAnswering(headers: io.ktor.http.Headers) =
        HttpClient(MockEngine { respond("", HttpStatusCode.NoContent, headers) })

    @Test
    fun findsTusWithTheProtocolsOwnQuestion() = runTest {
        // Nothing Stratus-specific: every tus server answers OPTIONS like this.
        val endpoint = negotiateTus(clientAnswering(headersOf("Tus-Resumable", "1.0.0")), "http://host:8080/dav/")
        assertEquals("http://host:8080/tus/", endpoint)
    }

    @Test
    fun leavesTheDefaultPortOffWhereItIsImplied() = runTest {
        val endpoint = negotiateTus(clientAnswering(headersOf("Tus-Resumable", "1.0.0")), "https://host/dav/")
        assertEquals("https://host/tus/", endpoint)
    }

    @Test
    fun aServerWithoutItIsNotAFailure() = runTest {
        // Degrading has to be silent: a plain WebDAV server still works, only
        // slower to recover from a dropped connection.
        assertEquals(null, negotiateTus(clientAnswering(headersOf()), "http://host/dav/"))
    }

    @Test
    fun aServerThatDoesNotAnswerAtAllIsNotAFailureEither() = runTest {
        val client = HttpClient(MockEngine { throw kotlinx.io.IOException("refused") })
        assertEquals(null, negotiateTus(client, "http://host/dav/"))
    }
}
