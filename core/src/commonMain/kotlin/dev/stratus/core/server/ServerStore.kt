package dev.stratus.core.server

import dev.stratus.core.net.Credentials
import dev.stratus.core.store.SecureStore

/**
 * The server somebody has signed in to, and its password.
 *
 * One record over a key-value store. [SecureStore] is the only part of this
 * that cannot be tested in CI, and it stays exactly as narrow as it was -- "put
 * this string somewhere safe and give it back".
 *
 * There is no way to hold a second (stratus-app#131). Signing in is refused
 * while one is here, so getting to a different server means [clear], which is
 * also what drops the backup record -- a different server has settled nothing.
 */
class ServerStore(
    private val secure: SecureStore,
    /**
     * Told whenever the server changes.
     *
     * For a platform that keeps a list of storage locations of its own:
     * Android caches the roots a `DocumentsProvider` offers hard, so a server
     * signed in to or signed out of would not appear in Files until something
     * made it ask again (stratus-app#104); iOS registers a File Provider
     * domain from the same hook (stratus-app#105). A no-op everywhere else.
     */
    private val changed: suspend () -> Unit = {},
) {

    suspend fun instance(): Server? = load()?.first

    suspend fun credentials(): Credentials? = load()?.second

    /** Signs in. The caller is what refuses to replace one that is there. */
    suspend fun put(instance: Server, credentials: Credentials) {
        secure.write(KEY, ServerBlob.encode(instance, credentials))
        changed()
    }

    /** Updates settings without touching the password, which it does not have. */
    suspend fun update(instance: Server) {
        val existing = load() ?: return
        secure.write(KEY, ServerBlob.encode(instance, existing.second))
    }

    /**
     * Forgets the server.
     *
     * The caller is responsible for what is cached about it -- see
     * `AppContainer.signOut`, which drops the backup rows in the same breath.
     */
    suspend fun clear() {
        secure.delete(KEY)
        changed()
    }

    private suspend fun load(): Pair<Server, Credentials>? =
        secure.read(KEY)?.let(ServerBlob::decode)

    private companion object {
        const val KEY = "server"
    }
}
