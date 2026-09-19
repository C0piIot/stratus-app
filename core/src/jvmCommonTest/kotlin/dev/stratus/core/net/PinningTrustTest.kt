package dev.stratus.core.net

import java.io.ByteArrayInputStream
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Answers however the test needs the platform to have answered. */
private class Delegate(private val refuse: Boolean) : X509ExtendedTrustManager() {
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        if (refuse) throw CertificateException("unknown issuer")
    }
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
        checkServerTrusted(chain, authType)
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        checkServerTrusted(chain, authType)
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) = Unit
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

class PinningTrustTest {

    private var captured: ByteArray? = null

    private fun manager(pin: String? = null, refuse: Boolean = true) =
        PinningTrustManager(TrustPolicy(pin) { captured = it }, Delegate(refuse))

    @Test
    fun acceptsWhatTheSystemAcceptsAndKeepsNothing() = with(manager(refuse = false)) {
        checkServerTrusted(chain, "RSA")
        assertNull(captured, "captured a certificate the system was happy with")
    }

    @Test
    fun refusesWhatTheSystemRefusesAndKeepsItToShow() {
        // Both halves matter: the handshake still fails -- there is no moment at
        // which an unvouched-for certificate is used -- and the fingerprint is
        // available afterwards for somebody to be asked about.
        assertFailsWith<CertificateException> { manager().checkServerTrusted(chain, "RSA") }
        assertEquals(FINGERPRINT, fingerprintOf(captured!!))
    }

    @Test
    fun acceptsTheOneCertificateThatWasVouchedFor() {
        manager(pin = FINGERPRINT).checkServerTrusted(chain, "RSA")
        assertNull(captured, "a pinned certificate is not a question")
    }

    @Test
    fun aPinForSomethingElseIsNoHelpAtAll() {
        // The failure mode this rules out is a pin being treated as "this host is
        // fine" rather than "this certificate is".
        val other = FINGERPRINT.replaceFirst("79", "7A")
        assertFailsWith<CertificateException> { manager(pin = other).checkServerTrusted(chain, "RSA") }
        assertEquals(FINGERPRINT, fingerprintOf(captured!!))
    }

    @Test
    fun theSystemsOwnJudgementIsWhatIsAskedFirst() {
        // Not a fake delegate: the real one, which has no reason to trust this.
        val real = PinningTrustManager(TrustPolicy(null) { captured = it })
        assertFailsWith<CertificateException> { real.checkServerTrusted(chain, "RSA") }
        assertTrue(real.acceptedIssuers.isNotEmpty(), "no system roots to measure against")
    }

    private companion object {
        /**
         * A self-signed certificate and nothing else -- no key, so there is no
         * secret here and no server to start. What is under test is the decision,
         * and the decision needs a certificate rather than a handshake.
         */
        @OptIn(ExperimentalEncodingApi::class)
        val chain: Array<X509Certificate> = arrayOf(
            CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(Base64.decode(SELF_SIGNED))) as X509Certificate,
        )

        const val FINGERPRINT =
            "79:AC:73:12:E3:06:5B:23:3C:A2:D2:05:27:E5:42:C7:8F:E6:0B:E4:F5:66:8C:C3:96:46:15:82:D3:54:77:6A"
    }
}

private const val SELF_SIGNED =
    "MIIDETCCAfmgAwIBAgIUR7PecESRoqRZ9K4BhM2xaqRntTcwDQYJKoZIhvcNAQELBQAwFzEVMBMG" +
        "A1UEAwwMc3RyYXR1cy50ZXN0MCAXDTI2MDkxOTA3MjY0MFoYDzIxMjYwODI2MDcyNjQwWjAXMRUw" +
        "EwYDVQQDDAxzdHJhdHVzLnRlc3QwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQDIRs6c" +
        "gZEPin0Ntso2zpbjt/2fC82znfMTnqKUVlkPesf9JlJzDuNxRuH2jghmCvVrODttS+cw2rbgWQ23" +
        "HuZEraCsQB2lvgdmXaHNoHAOXYpS4zqA+6oXt6pULq3OZDKvFXbgiC1+fE/6Q8iZvbZ3RvKU5NPn" +
        "LA9vam5vsMyVaq88lw1ys5+gtRIJ0eJpuF1RkAf6Ai6iCSbL4xSVehPJ5M6D50k6M4oMNXw5simw" +
        "8+S0SEkiO126qo/P+oxkKj7yqpOecu5osvu6a7Nd2uVBO0/29IH/5Al8EPSnKpTgMp83iIsjpiap" +
        "KHBxIrH36s9rpktgyc7R6KxAIbMjc4XVAgMBAAGjUzBRMB0GA1UdDgQWBBQx5vn897Gc3xsqmtMM" +
        "gj+OPHX/wTAfBgNVHSMEGDAWgBQx5vn897Gc3xsqmtMMgj+OPHX/wTAPBgNVHRMBAf8EBTADAQH/" +
        "MA0GCSqGSIb3DQEBCwUAA4IBAQC022kofiJjABToKUyquyzJI6HgQIbRO2RkYk0UhVLrFn/2lSfE" +
        "/OlxPcxILsNovClqff6ffVVaCAxliYJT3EErKARVIkQoxtCRNY9W0MkehLJSlKNBuYEahFAQl/Hk" +
        "qAT86KvzGHcKfKOrzXnErNIflyRZgIJ8vqciuwNFwMXMb8tvWVD9jZMJwjT5yJXsyLi/ChHAgrMr" +
        "O96fYe+XF/WzEfWzXwhYf52ovVYf3I8494Xs0KR9h+89M+MNi4xfLV53s1v7Gf+PV0zKS8jCm80A" +
        "EQI88GV4Qz5XZGtrCTiXnqK84pAqj61zqbxS0sK6wl59elNOhkSF0AgB1J6odv0U"
