package dev.stratus.core.net

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DavProberTest {

    /** Most of these are about classifying an answer, not about trust. */
    private suspend fun Prober.probe(attempt: Candidate, credentials: Credentials?) =
        probe(attempt, credentials, pin = null)

    private val attempt = Candidate(Scheme.Https, "host", 443, "/dav/")
    private val credentials = Credentials("edu", "secret")
    private val seen = mutableListOf<HttpRequestData>()

    private fun proberAnswering(
        status: HttpStatusCode = HttpStatusCode.MultiStatus,
        body: String = MULTISTATUS,
        headers: io.ktor.http.Headers = io.ktor.http.Headers.Empty,
    ) = DavProber { creds, _ ->
        stratusHttpClient(
            MockEngine { request ->
                seen += request
                respond(body, status, headers)
            },
            creds,
        )
    }

    @Test
    fun recognisesAWebDavCollection() = runTest {
        val outcome = proberAnswering().probe(attempt, credentials)
        assertTrue(outcome is ProbeOutcome.IsWebDav, "was $outcome")
    }

    @Test
    fun sendsExactlyOneAuthorizationHeaderAndOnlyWhenAskedTo() = runTest {
        val prober = proberAnswering()
        prober.probe(attempt, null)
        assertNull(seen.last().headers["Authorization"], "a password went out on an anonymous probe")

        prober.probe(attempt, credentials)
        assertEquals(1, seen.last().headers.getAll("Authorization")?.size)
    }

    @Test
    fun neverCallsAnAnonymousAnswerSuccess() = runTest {
        // A server allowing anonymous reads answers 207 without credentials.
        // Taking that as proof would dismiss the screen having checked nothing.
        val outcome = proberAnswering().probe(attempt, null)
        assertTrue(outcome is ProbeOutcome.Reachable, "was $outcome")
    }

    @Test
    fun reportsRefusedCredentials() = runTest {
        val outcome = proberAnswering(HttpStatusCode.Unauthorized, "").probe(attempt, credentials)
        assertTrue(outcome is ProbeOutcome.Rejected, "was $outcome")
    }

    @Test
    fun knowsAWebPageIsNotAWebDavServer() = runTest {
        // A 200 with a login page in it is the single most likely wrong answer:
        // the status says yes and only the body says no.
        val outcome = proberAnswering(HttpStatusCode.OK, "<html><body>Sign in</body></html>")
            .probe(attempt, credentials)
        assertTrue(outcome is ProbeOutcome.NotWebDav, "was $outcome")
    }

    @Test
    fun treatsTheUsualRefusalsAsAWrongPath() = runTest {
        for (status in listOf(HttpStatusCode.NotFound, HttpStatusCode.MethodNotAllowed)) {
            val outcome = proberAnswering(status, "").probe(attempt, credentials)
            assertTrue(outcome is ProbeOutcome.NotWebDav, "$status was $outcome")
        }
    }

    @Test
    fun surfacesARedirectRatherThanFollowingIt() = runTest {
        val outcome = proberAnswering(
            HttpStatusCode.MovedPermanently,
            "",
            headersOf("Location", "/webdav/"),
        ).probe(attempt, credentials)
        assertEquals(ProbeOutcome.Redirected(attempt, "/webdav/"), outcome)
    }

    @Test
    fun aHandshakeThatHandedOverACertificateIsAQuestionAndNotAFailure() = runTest {
        // What a rejecting trust manager does: records the certificate on its
        // way out and lets the failure through. The distinction from an
        // unreachable host is the whole of it -- one has something to show.
        val prober = DavProber { creds, policy ->
            stratusHttpClient(
                MockEngine {
                    policy.capture.rejected(byteArrayOf(1, 2, 3))
                    throw kotlinx.io.IOException("handshake failed")
                },
                creds,
            )
        }

        val outcome = prober.probe(attempt, credentials, pin = null)
        assertTrue(outcome is ProbeOutcome.Untrusted, "was $outcome")
        assertEquals(fingerprintOf(byteArrayOf(1, 2, 3)), outcome.fingerprint)
    }

    @Test
    fun theClientItBuildsIsToldWhatHasBeenVouchedFor() {
        var offered: String? = "not asked"
        val prober = DavProber { creds, policy ->
            offered = policy.pin
            stratusHttpClient(MockEngine { respond(MULTISTATUS, HttpStatusCode.MultiStatus) }, creds)
        }
        runTest { prober.probe(attempt, credentials, pin = "AB:CD") }
        assertEquals("AB:CD", offered)
    }

    @Test
    fun callsAConnectionThatNeverHappenedUnreachable() = runTest {
        val prober = DavProber { creds, _ ->
            stratusHttpClient(MockEngine { throw kotlinx.io.IOException("connection refused") }, creds)
        }
        val outcome = prober.probe(attempt, credentials)
        assertTrue(outcome is ProbeOutcome.Unreachable, "was $outcome")
    }

    private companion object {
        const val MULTISTATUS = """<multistatus xmlns="DAV:"><response><href>/dav/</href>
            <propstat><prop><resourcetype><collection/></resourcetype></prop>
            <status>HTTP/1.1 200 OK</status></propstat></response></multistatus>"""
    }
}
