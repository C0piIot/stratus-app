package dev.stratus.core.cast.relay

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * [Relay] over a `ServerSocket`, plain JDK so the conformance suite runs the
 * same one a phone does.
 *
 * It listens on [bindTo] -- the phone's address on the wifi, never every
 * interface -- and only while something is published. It speaks just enough
 * HTTP for a renderer: one `GET` or `HEAD` per connection, `Range` passed
 * upstream and the answer's status and headers passed back, the body streamed
 * through and never stored.
 */
class SocketRelay(
    private val http: HttpClient,
    private val scope: CoroutineScope,
    private val bindTo: () -> String?,
) : Relay {
    private val routes = RelayRoutes()
    private val lock = Mutex()
    private var server: ServerSocket? = null
    private var accepting: Job? = null

    override suspend fun publish(upstream: String, renderer: String): String? = lock.withLock {
        val address = bindTo() ?: return@withLock null
        val listening = server?.takeIf { !it.isClosed && it.inetAddress.hostAddress == address } ?: listen(address)
        val token = routes.open(upstream, renderer)
        "http://${host(address)}:${listening.localPort}/$token"
    }

    override suspend fun withdraw() = lock.withLock {
        routes.close()
        stop()
    }

    private suspend fun listen(address: String): ServerSocket = withContext(Dispatchers.IO) {
        stop()
        val socket = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName(address), 0)) }
        server = socket
        accepting = scope.launch(Dispatchers.IO) {
            while (isActive && !socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                launch { serve(client) }
            }
        }
        socket
    }

    private fun stop() {
        accepting?.cancel()
        accepting = null
        server?.close()
        server = null
    }

    private suspend fun serve(client: Socket) = client.use {
        try {
            // The origin is judged before a byte is read: a connection from
            // anything that is not the renderer is closed unanswered.
            val remote = client.inetAddress.hostAddress ?: return
            client.soTimeout = READ_TIMEOUT_MS
            val request = readHead(client.getInputStream()) ?: return
            val out = client.getOutputStream()
            if (request.method != "GET" && request.method != "HEAD") return answer(out, 405, "Method Not Allowed")
            val token = request.path.removePrefix("/").substringBefore('?')
            val query = request.path.substringAfter('?', "")
            val upstream = routes.resolve(token, query, remote) ?: return answer(out, 404, "Not Found")
            relay(request, upstream, out)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The renderer hung up, or the server did: a stream that ends.
        }
    }

    private suspend fun relay(request: Head, upstream: String, out: OutputStream) {
        http.prepareRequest(upstream) {
            method = if (request.method == "HEAD") HttpMethod.Head else HttpMethod.Get
            request.headers[HttpHeaders.Range.lowercase()]?.let { header(HttpHeaders.Range, it) }
        }.execute { response ->
            val head = StringBuilder("HTTP/1.1 ${response.status.value} ${response.status.description}\r\n")
            for (name in PASSED_BACK) response.headers[name]?.let { head.append("$name: $it\r\n") }
            // What a DLNA renderer is told to expect: a stream it can seek in,
            // rather than something it must take in one piece.
            head.append("transferMode.dlna.org: Streaming\r\n")
            head.append("Connection: close\r\n\r\n")
            withContext(Dispatchers.IO) {
                out.write(head.toString().encodeToByteArray())
                if (request.method == "GET") response.bodyAsChannel().toInputStream().copyTo(out)
                out.flush()
            }
        }
    }

    private fun answer(out: OutputStream, status: Int, reason: String) {
        out.write("HTTP/1.1 $status $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".encodeToByteArray())
        out.flush()
    }

    private class Head(val method: String, val path: String, val headers: Map<String, String>)

    /** The request line and headers, up to the empty line; null for anything else. */
    private fun readHead(input: InputStream): Head? {
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        var size = 0
        while (true) {
            val byte = input.read()
            if (byte < 0 || ++size > MAX_HEAD) return null
            if (byte == '\n'.code) {
                val text = line.toString().trimEnd('\r')
                if (text.isEmpty()) break
                lines += text
                line.clear()
            } else {
                line.append(byte.toChar())
            }
        }
        val parts = lines.firstOrNull()?.split(' ') ?: return null
        if (parts.size < 2) return null
        val headers = lines.drop(1).mapNotNull { header ->
            val colon = header.indexOf(':')
            if (colon <= 0) null else header.substring(0, colon).trim().lowercase() to header.substring(colon + 1).trim()
        }.toMap()
        return Head(parts[0], parts[1], headers)
    }

    private fun host(address: String) = if (':' in address) "[$address]" else address

    private companion object {
        val PASSED_BACK = listOf(
            HttpHeaders.ContentType,
            HttpHeaders.ContentLength,
            HttpHeaders.ContentRange,
            HttpHeaders.AcceptRanges,
            HttpHeaders.LastModified,
            HttpHeaders.ETag,
        )

        /** A request head is a line and a few headers; anything longer is not a renderer. */
        const val MAX_HEAD = 16 * 1024

        /** How long a renderer may take to send its request, not to read the answer. */
        const val READ_TIMEOUT_MS = 10_000
    }
}
