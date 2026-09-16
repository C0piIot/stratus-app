package dev.stratus.core.store

import dev.stratus.core.net.Credentials
import dev.stratus.core.signin.Session

/** The signed-in session, kept wherever the platform keeps secrets. */
class CredentialStore(private val secure: SecureStore) {

    suspend fun save(session: Session, credentials: Credentials) {
        secure.write(KEY, SessionBlob.encode(session, credentials))
    }

    suspend fun load(): Pair<Session, Credentials>? =
        secure.read(KEY)?.let(SessionBlob::decode)

    suspend fun clear() = secure.delete(KEY)

    private companion object {
        const val KEY = "session"
    }
}

/**
 * The hosts somebody has agreed to send a password to in clear text.
 *
 * Remembering the answer is what keeps the warning from becoming noise: the
 * risk is a property of the host, not of this particular sign-in, and being
 * asked the same question every time is how a warning stops being read.
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
