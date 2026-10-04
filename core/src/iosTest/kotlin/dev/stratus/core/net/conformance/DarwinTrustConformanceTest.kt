package dev.stratus.core.net.conformance

import dev.stratus.core.net.Candidate
import dev.stratus.core.net.DavProber
import dev.stratus.core.net.ProbeOutcome
import dev.stratus.core.net.Scheme
import dev.stratus.core.net.TrustPolicy
import dev.stratus.core.net.darwinTrust
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.test.runTest
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The Darwin half of accepting a self-signed certificate, against one
 * (stratus-app#58).
 *
 * There is no honest way to fake an `NSURLAuthenticationChallenge`, so this is
 * the only kind of test this code can have: a real TLS server with a
 * certificate nothing vouches for, reached from the simulator through the same
 * [DavProber] sign-in uses, sink and all. `scripts/` does not start it —
 * the macOS job does, because this is the one place in the project that needs
 * a Mac.
 *
 * Not part of the ordinary run, for the reason the filter in
 * `core/build.gradle.kts` gives: `make test` stays offline and quick.
 */
class DarwinTrustConformanceTest {

    private val baseUrl = environment("STRATUS_TLS_URL")
    private val fingerprint = environment("STRATUS_TLS_FINGERPRINT")

    /** Where the server is, as sign-in would address it. */
    private val attempt: Candidate
        get() {
            val hostPort = baseUrl.substringAfter("://").trimEnd('/')
            return Candidate(
                scheme = Scheme.Https,
                host = hostPort.substringBefore(':'),
                port = hostPort.substringAfter(':', "443").toInt(),
                path = "/",
            )
        }

    private fun proberWith() = DavProber { _, policy ->
        HttpClient(Darwin.create { handleChallenge(darwinTrust(policy)) })
    }

    @Test
    fun aCertificateNobodyVouchesForIsAQuestionAndNotAFailure() = runTest {
        // The property the whole feature rests on: the fingerprint shown to
        // somebody comes out of a handshake that was **rejected**. If this ever
        // came back Unreachable, iOS would be back to where #58 found it.
        val outcome = proberWith().probe(attempt, credentials = null, pin = null)
        val untrusted = assertIs<ProbeOutcome.Untrusted>(outcome, "was $outcome")
        assertEquals(fingerprint, untrusted.fingerprint, "the fingerprint is not this server's certificate")
    }

    @Test
    fun theSameCertificateGetsThroughOnceItIsPinned() = runTest {
        val outcome = proberWith().probe(attempt, credentials = null, pin = fingerprint)
        assertTrue(
            outcome !is ProbeOutcome.Untrusted && outcome !is ProbeOutcome.Unreachable,
            "a pinned certificate did not get through: $outcome",
        )
    }

    @Test
    fun somebodyElsesFingerprintOpensNothing() = runTest {
        // A pin is for one certificate at one address. Were this to pass, a pin
        // taken anywhere would open everything, which is the whole of what the
        // feature promises not to do.
        val wrong = fingerprint.reversed()
        val outcome = proberWith().probe(attempt, credentials = null, pin = wrong)
        assertIs<ProbeOutcome.Untrusted>(outcome, "a wrong pin was accepted: $outcome")
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun environment(name: String): String =
        requireNotNull(getenv(name)?.toKString()) { "$name is unset. This runs from the macOS job, not by hand." }
}
