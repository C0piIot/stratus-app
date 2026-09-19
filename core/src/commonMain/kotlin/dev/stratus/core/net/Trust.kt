package dev.stratus.core.net

import dev.stratus.core.crypto.hexPairs
import dev.stratus.core.crypto.sha256
import io.ktor.http.Url

/**
 * Where a certificate that **failed** system validation is put.
 *
 * The distinction is the whole security property. A fingerprint shown to
 * somebody has to come out of a handshake that was rejected, never out of a
 * successful one made with verification turned off: any design with a "trust
 * everything and show them what we got" mode has already shipped the
 * vulnerability and is relying on the screen to un-ship it.
 */
fun interface CertificateSink {
    fun rejected(leafDer: ByteArray)
}

/**
 * What one client may trust beyond the system roots, and nothing wider.
 *
 * [pin] is the fingerprint somebody has already vouched for at this `host:port`;
 * with it null, only the system roots are accepted. There is no third setting.
 */
class TrustPolicy(val pin: String? = null, val capture: CertificateSink = CertificateSink {})

/**
 * How a pin is keyed, from a base URL rather than a candidate.
 *
 * The same key as [Candidate.hostPort], and it has to stay the same: a pin
 * recorded while signing in is read back by everything that talks to the
 * instance afterwards.
 */
fun hostPortOf(baseUrl: String): String = with(Url(baseUrl)) { "$host:$port" }

/** How a certificate is named to a person: SHA-256 of the DER, in hex pairs. */
fun fingerprintOf(leafDer: ByteArray): String = hexPairs(sha256(leafDer))
