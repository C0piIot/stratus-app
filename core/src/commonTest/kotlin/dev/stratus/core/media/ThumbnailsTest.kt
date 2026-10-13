package dev.stratus.core.media

import dev.stratus.core.backup.Connection
import dev.stratus.core.backup.Connections
import dev.stratus.core.dav.DavClient
import dev.stratus.core.net.Credentials
import dev.stratus.core.server.Server
import dev.stratus.core.server.ServerStore
import dev.stratus.core.store.SecureStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.isSuccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class MemoryStore : SecureStore {
    val values = mutableMapOf<String, String>()
    override suspend fun read(key: String) = values[key]
    override suspend fun write(key: String, value: String) { values[key] = value }
    override suspend fun delete(key: String) { values.remove(key) }
}

class ThumbnailsTest {

    private val seen = mutableListOf<HttpRequestData>()
    private var clock = 1_700_000_000_000L

    private suspend fun thumbnails(
        type: String = "image/jpeg",
        answer: suspend (HttpRequestData) -> Pair<HttpStatusCode, String>,
    ): Thumbnails {
        val engine = MockEngine { request ->
            seen += request
            val (status, body) = answer(request)
            val headers = when {
                status == HttpStatusCode.ServiceUnavailable -> headersOf("Retry-After", body)
                status.isSuccess() -> headersOf("Content-Type", type)
                else -> headersOf("Content-Type", "text/plain")
            }
            respond(body, status, headers)
        }
        val http = HttpClient(engine)
        val instances = ServerStore(MemoryStore())
        instances.put(Server("http://host/", "edu"), Credentials("edu", "secret"))
        val connections = Connections { Connection(http, DavClient(http, it.baseUrl)) }
        return Thumbnails(instances, connections) { clock }
    }

    @Test
    fun aPictureIsTheOrdinaryAnswer() = runTest {
        val thumbs = thumbnails { HttpStatusCode.OK to "jpeg" }
        assertEquals("jpeg", assertIs<Thumbnail.Picture>(thumbs.of("/files/a.jpg", 96)).bytes.decodeToString())

        // Signed for now, not for the epoch: a token that expired in 1970 is a
        // 403 from the share gate, which never looks at the credentials behind
        // it -- so every picture in the picker was an icon (stratus-app#130).
        val token = seen.single().url.parameters["k"]
        assertEquals("${clock / 1000 + 86_400}", token?.split('.')?.get(4))
    }

    @Test
    fun whatIsNotAPictureIsAnAbsence() = runTest {
        // A file that has none answers 404, and a server that is not a Stratus
        // answers something that is not an image at all. Both are an icon, and
        // neither is worth asking about again.
        assertIs<Thumbnail.None>(thumbnails { HttpStatusCode.NotFound to "no" }.of("/files/a.txt", 96))
        assertIs<Thumbnail.None>(
            thumbnails(type = "text/html") { HttpStatusCode.OK to "<html>" }.of("/files/a.txt", 96),
        )
    }

    @Test
    fun aRefusalIsNotAnAbsenceAndCostsOneRequest() = runTest {
        var full = true
        val thumbs = thumbnails {
            if (full) HttpStatusCode.ServiceUnavailable to "5" else HttpStatusCode.OK to "jpeg"
        }

        val refused = assertIs<Thumbnail.NotNow>(thumbs.of("/files/a.jpg", 96))
        assertEquals(5_000, refused.afterMs, "the server's own Retry-After was not honoured")

        // The point of the door: the next thirty-nine rows do not each go and
        // learn the same thing.
        repeat(5) { assertIs<Thumbnail.NotNow>(thumbs.of("/files/$it.jpg", 96)) }
        assertEquals(1, seen.size, "a closed door still sent requests")

        // And it opens by itself, with nothing to clear and nobody to tell.
        clock += 5_000
        full = false
        assertIs<Thumbnail.Picture>(thumbs.of("/files/a.jpg", 96))
    }

    @Test
    fun aWaitNobodyCanReadIsStillAWaitAndNoneIsAnAfternoon() = runTest {
        // RFC 9110 allows a date there, and a server may send no header at all.
        val dated = thumbnails { HttpStatusCode.ServiceUnavailable to "Wed, 21 Oct 2026 07:28:00 GMT" }
        assertEquals(5_000, assertIs<Thumbnail.NotNow>(dated.of("/files/a.jpg", 96)).afterMs)

        // Honoured, but not to the point where one bad answer turns pictures
        // off for the rest of the afternoon.
        val long = thumbnails { HttpStatusCode.ServiceUnavailable to "86400" }
        assertEquals(30_000, assertIs<Thumbnail.NotNow>(long.of("/files/a.jpg", 96)).afterMs)
    }

    @Test
    fun aServerThatCannotBeReachedIsNotAnAbsenceEither() = runTest {
        val thumbs = thumbnails { throw IllegalStateException("the network went away") }
        assertIs<Thumbnail.NotNow>(thumbs.of("/files/a.jpg", 96))

        // It does not shut the door, which is about the server being full
        // rather than about one request failing.
        assertIs<Thumbnail.NotNow>(thumbs.of("/files/b.jpg", 96))
        assertEquals(2, seen.size)
    }

    @Test
    fun onlySoManyGoOutAtOnce() = runTest {
        val arrived = Channel<Unit>(Channel.UNLIMITED)
        val held = CompletableDeferred<Unit>()
        val thumbs = thumbnails {
            arrived.trySend(Unit)
            held.await()
            HttpStatusCode.OK to "jpeg"
        }

        // A real dispatcher, because what is being measured is what is in
        // flight at once and the test clock would step straight over it.
        withContext(Dispatchers.Default) {
            val asking = (1..12).map { async { thumbs.of("/files/$it.jpg", 96) } }
            repeat(Thumbnails.AT_ONCE) { arrived.receive() }
            delay(200)
            assertTrue(arrived.tryReceive().isFailure, "the app put more than its share on the wire")

            held.complete(Unit)
            assertTrue(asking.awaitAll().all { it is Thumbnail.Picture })
        }
    }
}
