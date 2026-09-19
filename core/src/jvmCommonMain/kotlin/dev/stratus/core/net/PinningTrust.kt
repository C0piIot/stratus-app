package dev.stratus.core.net

import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager

/**
 * The system's judgement first, and a pin only where it said no.
 *
 * Note what this is *not*: there is no mode in which verification is off.
 * Validation runs exactly as it always did, and only a certificate it rejected
 * is compared against what somebody has vouched for. That ordering is the
 * security property -- capturing a fingerprint from a handshake made with
 * checking disabled would mean the vulnerability ships and the screen is what
 * stands between it and a user.
 *
 * Lives in a source set the JVM target can see, not in `androidMain`, because
 * there is no Android API in it and the fast loop can therefore prove it.
 */
class PinningTrustManager(
    private val policy: TrustPolicy,
    private val delegate: X509ExtendedTrustManager = systemTrustManager(),
) : X509ExtendedTrustManager() {

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
        judge(chain) { delegate.checkServerTrusted(chain, authType) }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
        judge(chain) { delegate.checkServerTrusted(chain, authType, socket) }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        judge(chain) { delegate.checkServerTrusted(chain, authType, engine) }

    // Nothing here is ever a TLS server, so these are the system's business alone.
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        delegate.checkClientTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
        delegate.checkClientTrusted(chain, authType, socket)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        delegate.checkClientTrusted(chain, authType, engine)

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

    private fun judge(chain: Array<X509Certificate>, validate: () -> Unit) {
        try {
            return validate()
        } catch (refused: CertificateException) {
            val leaf = chain.firstOrNull()?.encoded ?: throw refused
            if (policy.pin != null && fingerprintOf(leaf) == policy.pin) return
            policy.capture.rejected(leaf)
            throw refused
        }
    }
}

/**
 * The same judgement for the name on the certificate.
 *
 * Without this the commonest case of all -- a home server whose certificate says
 * `localhost` or an old address -- arrives as a bare failure with no certificate
 * attached and nothing to show anybody. A mismatched name is a failed validation
 * as much as an unknown issuer is; the JDK just reports it somewhere else.
 *
 * A pin made here is tied to that one certificate, so its renewal asks again.
 * That is the right answer rather than a cost: a certificate whose name does not
 * match was never going to be accepted silently.
 */
class PinnedHostnameVerifier(
    private val policy: TrustPolicy,
    private val delegate: HostnameVerifier,
) : HostnameVerifier {
    override fun verify(host: String, session: SSLSession): Boolean {
        if (delegate.verify(host, session)) return true
        val leaf = (session.peerCertificates.firstOrNull() as? X509Certificate)?.encoded ?: return false
        if (policy.pin != null && fingerprintOf(leaf) == policy.pin) return true
        policy.capture.rejected(leaf)
        return false
    }
}

fun pinnedSocketFactory(manager: X509ExtendedTrustManager): SSLSocketFactory =
    SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }.socketFactory

/** The platform's own, which is what everything is measured against first. */
fun systemTrustManager(): X509ExtendedTrustManager {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as KeyStore?)
    return factory.trustManagers.filterIsInstance<X509ExtendedTrustManager>().first()
}
