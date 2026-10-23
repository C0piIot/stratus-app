package dev.stratus.core.cast.relay

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.Socket
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The relay over real sockets on the loopback, which counts as local and is
 * where a test can be its own renderer.
 */
class SocketRelayTest {

    private val film = ByteArray(1000) { (it % 251).toByte() }
    private val asked = mutableListOf<String>()

    /** The server: the film, honouring a byte range the way Stratus does. */
    private val upstream = HttpClient(
        MockEngine { request ->
            asked += "${request.method.value} ${request.url}"
            val range = request.headers[HttpHeaders.Range]
            val body = if (request.method == HttpMethod.Head) ByteArray(0) else film
            if (range == null) {
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("video/mp4"), HttpHeaders.ContentLength to listOf("${film.size}")))
            } else {
                val (from, to) = range.removePrefix("bytes=").split('-').map { it.toInt() }
                respond(
                    film.copyOfRange(from, to + 1),
                    HttpStatusCode.PartialContent,
                    headersOf(
                        HttpHeaders.ContentType to listOf("video/mp4"),
                        HttpHeaders.ContentRange to listOf("bytes $from-$to/${film.size}"),
                        HttpHeaders.ContentLength to listOf("${to - from + 1}"),
                    ),
                )
            }
        },
    )

    /** One request as a renderer would send it, and the raw answer. */
    private suspend fun fetch(url: String, method: String = "GET", range: String? = null): ByteArray = withContext(Dispatchers.IO) {
        val uri = URI(url)
        Socket(uri.host, uri.port).use { socket ->
            val head = "$method ${uri.rawPath}${uri.rawQuery?.let { "?$it" } ?: ""} HTTP/1.1\r\nHost: ${uri.host}\r\n" +
                (range?.let { "Range: $it\r\n" } ?: "") + "\r\n"
            socket.getOutputStream().write(head.encodeToByteArray())
            socket.getInputStream().readBytes()
        }
    }

    private fun split(answer: ByteArray): Pair<String, ByteArray> {
        val end = answer.decodeToString().indexOf("\r\n\r\n")
        return answer.decodeToString(0, end) to answer.copyOfRange(end + 4, answer.size)
    }

    @Test
    fun aRangeGoesUpstreamAndComesBackAsTheServerAnswered() = runTest {
        val relay = SocketRelay(upstream, backgroundScope) { "127.0.0.1" }
        val local = assertNotNull(relay.publish("https://s.example/files/f.mp4?k=signed", "127.0.0.1"))
        assertTrue(local.startsWith("http://127.0.0.1:"), local)

        val (head, body) = split(fetch(local, range = "bytes=100-199"))
        assertTrue(head.startsWith("HTTP/1.1 206"), head)
        assertTrue("Content-Range: bytes 100-199/1000" in head, head)
        assertTrue("transferMode.dlna.org: Streaming" in head)
        assertEquals(film.copyOfRange(100, 200).toList(), body.toList())
        assertEquals("GET https://s.example/files/f.mp4?k=signed", asked.single())
        relay.withdraw()
    }

    @Test
    fun aHeadIsAnsweredWithoutABody() = runTest {
        val relay = SocketRelay(upstream, backgroundScope) { "127.0.0.1" }
        val local = assertNotNull(relay.publish("https://s.example/files/f.mp4?k=signed", "127.0.0.1"))
        val (head, body) = split(fetch(local, method = "HEAD"))
        assertTrue(head.startsWith("HTTP/1.1 200") && "Content-Length: 1000" in head, head)
        assertEquals(0, body.size)
        relay.withdraw()
    }

    @Test
    fun anythingButTheRendererWithItsTokenIsTurnedAway() = runTest {
        val relay = SocketRelay(upstream, backgroundScope) { "127.0.0.1" }
        val local = assertNotNull(relay.publish("https://s.example/files/f.mp4?k=signed", "127.0.0.1"))

        val wrongToken = local.substringBeforeLast('/') + "/" + "0".repeat(32)
        assertTrue(split(fetch(wrongToken)).first.startsWith("HTTP/1.1 404"))
        assertTrue(split(fetch(local, method = "POST")).first.startsWith("HTTP/1.1 405"))

        // Handed to another device: this one, on the loopback, is not it.
        relay.publish("https://s.example/files/f.mp4?k=signed", "127.0.0.2")
        val elsewhere = assertNotNull(relay.publish("https://s.example/files/f.mp4?k=signed", "192.168.1.40"))
        assertTrue(split(fetch(elsewhere)).first.startsWith("HTTP/1.1 404"))
        assertEquals(emptyList(), asked, "nothing went upstream for a request that was turned away")
        relay.withdraw()
    }

    @Test
    fun withdrawingStopsListening() = runTest {
        val relay = SocketRelay(upstream, backgroundScope) { "127.0.0.1" }
        val local = assertNotNull(relay.publish("https://s.example/files/f.mp4?k=signed", "127.0.0.1"))
        relay.withdraw()
        assertFailsWith<ConnectException> { fetch(local) }
    }

    @Test
    fun noLocalAddressIsNothingToPublish() = runTest {
        val relay = SocketRelay(upstream, backgroundScope) { null }
        assertEquals(null, relay.publish("https://s.example/files/f.mp4?k=signed", "192.168.1.40"))
    }
}
