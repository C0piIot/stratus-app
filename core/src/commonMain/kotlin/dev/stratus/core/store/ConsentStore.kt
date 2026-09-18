package dev.stratus.core.store

/**
 * The hosts somebody has agreed to send a password to in clear text.
 *
 * Keyed by host and **not** by instance, deliberately: the risk belongs to the
 * machine at the other end, so two instances on the same host share the answer
 * with good reason. Remembering it is what keeps the warning from becoming
 * noise -- a warning shown every time is a warning nobody reads.
 */
class ConsentStore(private val secure: SecureStore) {

    suspend fun consentedHosts(): Set<String> =
        secure.read(KEY)?.split('\n')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    suspend fun remember(host: String) {
        secure.write(KEY, (consentedHosts() + host).joinToString("\n"))
    }

    suspend fun forget() = secure.delete(KEY)

    private companion object {
        // A host cannot contain a newline, so this one may be delimited.
        const val KEY = "plaintext-consent"
    }
}
