package dev.stratus.core.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders

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
 */
fun stratusHttpClient(engine: HttpClientEngine, credentials: Credentials?): HttpClient =
    HttpClient(engine) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 20_000
        }
        if (credentials != null) {
            defaultRequest { header(HttpHeaders.Authorization, basicAuthHeader(credentials)) }
        }
    }
