package dev.stratus.core.net

import io.ktor.client.engine.darwin.ChallengeHandler
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import platform.CoreFoundation.CFArrayGetCount
import platform.CoreFoundation.CFArrayGetValueAtIndex
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFRelease
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLCredential
import platform.Foundation.credentialForTrust
import platform.Foundation.NSURLSessionAuthChallengeCancelAuthenticationChallenge
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeUseCredential
import platform.Foundation.serverTrust
import platform.Security.SecCertificateCopyData
import platform.Security.SecCertificateRef
import platform.Security.SecTrustCopyCertificateChain
import platform.Security.SecTrustEvaluateWithError
import platform.Security.SecTrustRef

/**
 * The system's judgement first, and a pin only where it said no.
 *
 * The Darwin twin of [PinningTrustManager], and it keeps the same order for
 * the same reason: validation runs exactly as it always did, and only a
 * certificate it rejected is compared against what somebody has vouched for.
 * There is no mode here in which checking is off.
 *
 * **Two differences from the Android half, both forced by the platform.**
 *
 * It records rather than throws. A `TrustManager` can raise an exception that
 * Ktor propagates; nothing can be thrown across the `URLSession` boundary, so
 * a certificate the system refused goes into the policy's sink and the
 * challenge is cancelled. What reaches [DavProber] is an ordinary connection
 * failure with the sink already filled, which is exactly what its `catch`
 * reads.
 *
 * And it needs no twin of [PinnedHostnameVerifier]. `SecTrustEvaluateWithError`
 * evaluates against the policy the protection space carries, name included, so
 * a certificate that says `localhost` when the address says otherwise fails
 * here rather than somewhere else. On the JVM the name check lives in a second
 * place and had to be wrapped separately.
 */
@OptIn(ExperimentalForeignApi::class)
fun darwinTrust(policy: TrustPolicy): ChallengeHandler =
    handler@{ _, _, challenge, completion ->
        val space = challenge.protectionSpace
        if (space.authenticationMethod != NSURLAuthenticationMethodServerTrust) {
            // Basic and the rest are not this function's business; Ktor sends
            // credentials the ordinary way.
            completion(NSURLSessionAuthChallengePerformDefaultHandling.convert(), null)
            return@handler
        }
        val trust = space.serverTrust
        if (trust == null) {
            completion(NSURLSessionAuthChallengePerformDefaultHandling.convert(), null)
            return@handler
        }

        if (SecTrustEvaluateWithError(trust, null)) {
            completion(NSURLSessionAuthChallengePerformDefaultHandling.convert(), null)
            return@handler
        }

        val leaf = leafOf(trust)
        if (leaf == null) {
            // Refused and with nothing to show for it: there is no question to
            // ask, so it is a plain failure.
            completion(NSURLSessionAuthChallengeCancelAuthenticationChallenge.convert(), null)
            return@handler
        }

        if (policy.pin != null && fingerprintOf(leaf) == policy.pin) {
            completion(NSURLSessionAuthChallengeUseCredential.convert(), NSURLCredential.credentialForTrust(trust))
            return@handler
        }

        policy.capture.rejected(leaf)
        completion(NSURLSessionAuthChallengeCancelAuthenticationChallenge.convert(), null)
    }

/**
 * The DER of the certificate the server presented, or null if there is none.
 *
 * Both `Copy` calls hand back a reference this function owns, hence the
 * releases: a handshake that fails on every request would otherwise leak one
 * array and one data object per attempt.
 */
@OptIn(ExperimentalForeignApi::class)
private fun leafOf(trust: SecTrustRef): ByteArray? {
    val chain = SecTrustCopyCertificateChain(trust) ?: return null
    try {
        if (CFArrayGetCount(chain) < 1) return null
        val certificate: SecCertificateRef = CFArrayGetValueAtIndex(chain, 0)?.reinterpret() ?: return null
        val der = SecCertificateCopyData(certificate) ?: return null
        try {
            val bytes = CFDataGetBytePtr(der) ?: return null
            return bytes.readBytes(CFDataGetLength(der).toInt())
        } finally {
            CFRelease(der)
        }
    } finally {
        CFRelease(chain)
    }
}
