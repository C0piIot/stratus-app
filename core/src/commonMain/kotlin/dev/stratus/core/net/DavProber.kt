package dev.stratus.core.net

import dev.stratus.core.dav.MultiStatus
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType

/**
 * Finds out what is at a candidate by asking it for its own properties.
 *
 * `Depth: 0` on the collection itself, which is the cheapest proof there is and
 * also confirms the target is a collection. A `Depth: 1` against somebody's root
 * would make the first thing they ever see a long pause.
 *
 * This talks to Ktor directly rather than through `DavClient` because the
 * question is different: the client is for using a server known to be WebDAV,
 * while this has to classify something that may be a web page, a redirect or a
 * closed port -- and needs the `Location` header the client has no reason to keep.
 */
class DavProber(private val newClient: (Credentials?) -> HttpClient) : Prober {

    override suspend fun probe(attempt: Candidate, credentials: Credentials?): ProbeOutcome {
        val client = newClient(credentials)
        return try {
            val response = client.request(attempt.baseUrl) {
                method = PROPFIND
                header(HttpHeaders.Depth, "0")
                contentType(ContentType.Application.Xml)
                setBody(ALLPROP)
            }
            classify(attempt, response, authenticated = credentials != null)
        } catch (e: Exception) {
            // Anything thrown here is the connection failing to happen: a refused
            // port, a name that does not resolve, a TLS handshake that ended, a
            // timeout. None of them say anything about the path.
            ProbeOutcome.Unreachable(attempt, e.message ?: e::class.simpleName ?: "no detail")
        } finally {
            client.close()
        }
    }

    private suspend fun classify(
        attempt: Candidate,
        response: HttpResponse,
        authenticated: Boolean,
    ): ProbeOutcome {
        val status = response.status.value
        val location = response.headers[HttpHeaders.Location]
        if (status in 300..399 && location != null) {
            return ProbeOutcome.Redirected(attempt, location)
        }

        // Without credentials the only question asked was "is anything there",
        // so any answer at all is the answer. Reading more into it -- in
        // particular treating a 207 as success -- would accept an anonymous-read
        // server as proof of a password.
        if (!authenticated) return ProbeOutcome.Reachable(attempt)

        if (status == 401) return ProbeOutcome.Rejected(attempt)
        if (status != 207 && status != 200) return ProbeOutcome.NotWebDav(attempt, status)

        // A 200 is tolerated because some servers answer a multistatus with one,
        // but only the body decides: a login page is also a 200.
        //
        // And parsing is not enough on its own -- an HTML page is well-formed XML
        // and comes back as a multistatus with nothing in it. A Depth: 0 against
        // anything that really is WebDAV answers with exactly one response, so an
        // empty list is the tell.
        return try {
            val entries = MultiStatus.parse(response.bodyAsText(), attempt.path.trimEnd('/'))
            if (entries.isEmpty()) ProbeOutcome.NotWebDav(attempt, status) else ProbeOutcome.IsWebDav(attempt)
        } catch (_: Exception) {
            ProbeOutcome.NotWebDav(attempt, status)
        }
    }

    private companion object {
        val PROPFIND = HttpMethod("PROPFIND")
        const val ALLPROP = """<?xml version="1.0" encoding="utf-8"?>
<propfind xmlns="DAV:"><allprop/></propfind>"""
    }
}
