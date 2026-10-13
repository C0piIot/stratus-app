package dev.stratus.core.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders

/**
 * How many requests one host may be answering at once.
 *
 * It is a number this app did not have to have while every request built a
 * client of its own; one client for everything (stratus-app#151) makes the
 * engine's own default the app's ceiling, and both platforms set theirs
 * around five. A `RangeReader` holds a response open for as long as another
 * app keeps the descriptor, so five would be two paused films, the four
 * thumbnails of a grid, and a listing waiting behind them.
 */
const val MOST_AT_ONCE_PER_HOST = 16

/**
 * The client every request goes through.
 *
 * **Redirects are off, and that is a security decision rather than a preference.**
 * `defaultRequest` puts the `Authorization` header on every request the client
 * makes, and a redirect followed automatically is one of them -- so a server
 * answering `302 Location: http://elsewhere/` would be handed the password, in
 * clear, by us. Whether a redirect is safe to follow depends on where it points,
 * which only the caller can judge, so they are surfaced instead of followed.
 *
 * The timeouts are what make an unreachable host fail in seconds instead of
 * leaving somebody looking at a spinner wondering whether they typed it wrong.
 * A transfer lifts the whole-request one with [asTransfer] and keeps the socket
 * one, so a stalled upload still fails and a long one is not cut.
 */
fun stratusHttpClient(engine: HttpClientEngine, credentials: Credentials?): HttpClient =
    HttpClient(engine) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 20_000
            socketTimeoutMillis = 20_000
        }
        if (credentials != null) {
            defaultRequest { header(HttpHeaders.Authorization, basicAuthHeader(credentials)) }
        }
    }

/**
 * No deadline for the request as a whole, because a file takes as long as it is
 * big: twenty seconds cut every upload and download longer than that, and a PUT,
 * which cannot resume, never finished at all (STRATUS-BACKEND-3).
 */
fun HttpRequestBuilder.asTransfer() = timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS }
