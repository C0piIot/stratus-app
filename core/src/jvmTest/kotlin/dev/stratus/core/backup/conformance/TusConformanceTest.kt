package dev.stratus.core.backup.conformance

import dev.stratus.core.backup.Resume
import dev.stratus.core.backup.TusTransport
import dev.stratus.core.backup.UploadOutcome
import dev.stratus.core.backup.UploadTarget
import dev.stratus.core.backup.negotiateTus
import dev.stratus.core.dav.DavClient
import dev.stratus.core.dav.DavError
import dev.stratus.core.net.Credentials
import dev.stratus.core.net.stratusHttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The first time a tus client has ever been pointed at this server.
 *
 * The backend's own suite cuts an upload in half and resumes it with curl, which
 * proves the protocol. This proves the two of them together, which until now
 * nobody had: its README says in so many words that no tus client library had
 * been tried against it.
 */
class TusConformanceTest {

    private val credentials = Credentials(
        System.getenv("STRATUS_TEST_USER") ?: "conformance",
        System.getenv("STRATUS_TEST_PASS") ?: "conformance-secret",
    )
    private val baseUrl = requireNotNull(System.getenv("STRATUS_TEST_URL")) {
        "run this with `make conformance`"
    }

    private val http = stratusHttpClient(CIO.create(), credentials)
    private val dav = DavClient(http, baseUrl)

    private val folder = "/tus-${Random.nextLong().toULong().toString(16)}"
    private val bytes = ByteArray(64 * 1024) { (it % 251).toByte() }

    private suspend fun folderExists() {
        try {
            dav.makeCollection(folder)
        } catch (_: DavError.Conflict) {
            // Already there.
        }
    }

    private fun source(from: Long) = Buffer().apply { write(bytes, from.toInt(), bytes.size) }

    @Test
    fun theServerSaysItSpeaksTus() = runTest {
        // The protocol's own question, answered without anything specific to
        // this server being assumed.
        assertNotNull(negotiateTus(http, baseUrl), "no Tus-Resumable came back")
    }

    @Test
    fun sendsAWholeFileAndTheBytesAreThere() = runTest {
        folderExists()
        val endpoint = requireNotNull(negotiateTus(http, baseUrl))
        val transport = TusTransport(http, endpoint) { runCatching { dav.stat(it).etag }.getOrNull() }
        val target = UploadTarget("$folder/whole.bin", bytes.size.toLong(), null)

        val outcome = transport.send(target, null, ::source)
        assertTrue(outcome is UploadOutcome.Done, "was $outcome")

        // tus reports no ETag of its own; the one that comes back is the
        // SHA-256 WebDAV gives for what was actually stored.
        assertEquals(dav.stat(target.path).etag, outcome.etag)
        val read = dav.read(target.path) { it.readRemaining().readByteArray() }
        assertTrue(read.contentEquals(bytes), "a file went out and came back different")
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun picksUpAnUploadThatWasLeftHalfFinished() = runTest {
        folderExists()
        val endpoint = requireNotNull(negotiateTus(http, baseUrl))
        val path = "$folder/resumed.bin"
        val half = bytes.size / 2

        // Left half-done by hand, which is what a dropped connection leaves
        // behind -- and what nothing but a real server can produce faithfully.
        val created: HttpResponse = http.request(endpoint) {
            method = HttpMethod.Post
            header("Tus-Resumable", "1.0.0")
            header("Upload-Length", bytes.size.toString())
            header("Upload-Metadata", "filename " + Base64.encode(path.trimStart('/').encodeToByteArray()))
        }
        assertEquals(201, created.status.value)
        val handle = requireNotNull(created.headers[HttpHeaders.Location])

        val partial: HttpResponse = http.request(originOf(endpoint) + handle) {
            method = HttpMethod.Patch
            header("Tus-Resumable", "1.0.0")
            header("Upload-Offset", "0")
            header(HttpHeaders.ContentType, "application/offset+octet-stream")
            setBody(bytes.copyOfRange(0, half))
        }
        assertEquals(half.toString(), partial.headers["Upload-Offset"])

        // And now the client, told only where the upload lives.
        val transport = TusTransport(http, endpoint) { runCatching { dav.stat(it).etag }.getOrNull() }
        val outcome = transport.send(
            UploadTarget(path, bytes.size.toLong(), null),
            Resume(originOf(endpoint) + handle, half.toLong()),
            ::source,
        )

        assertTrue(outcome is UploadOutcome.Done, "was $outcome")
        val read = dav.read(path) { it.readRemaining().readByteArray() }
        assertTrue(read.contentEquals(bytes), "the resumed half did not join up with the first")
    }

    private fun originOf(endpoint: String) = endpoint.removeSuffix("/tus/")
}
