package dev.stratus.core.store

/**
 * The certificates somebody has vouched for, by `host:port`.
 *
 * Keyed by host **and port**, because a different port is a different server and
 * inheriting trust across them is how a pin stops meaning anything.
 *
 * Only a certificate that failed system validation ever reaches this: pinning
 * one that validated would break sign-in every sixty days, when Let's Encrypt
 * renews it, for everybody who has a real certificate.
 */
class TrustStore(private val secure: SecureStore) {

    suspend fun pins(): Map<String, String> =
        secure.read(KEY)?.lineSequence()
            ?.mapNotNull { line ->
                val at = line.lastIndexOf(' ')
                if (at <= 0) null else line.substring(0, at) to line.substring(at + 1)
            }
            ?.toMap()
            ?: emptyMap()

    suspend fun pin(hostPort: String, fingerprint: String) {
        val kept = pins() + (hostPort to fingerprint)
        // A space separates them because neither half can contain one, and an
        // IPv6 host:port is full of the colons a naive split would use.
        secure.write(KEY, kept.entries.joinToString("\n") { "${it.key} ${it.value}" })
    }

    suspend fun forget(hostPort: String) {
        val kept = pins() - hostPort
        if (kept.isEmpty()) secure.delete(KEY)
        else secure.write(KEY, kept.entries.joinToString("\n") { "${it.key} ${it.value}" })
    }

    private companion object {
        const val KEY = "pinned-certificates"
    }
}
