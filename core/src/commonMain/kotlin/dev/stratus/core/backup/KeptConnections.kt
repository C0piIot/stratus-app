package dev.stratus.core.backup

import dev.stratus.core.net.Credentials
import dev.stratus.core.server.Server
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One client for the server, kept (stratus-app#151).
 *
 * A [Connection] carries an `HttpClient`, and an `HttpClient` carries a
 * connection pool -- so building one per call, which is what the composition
 * root used to do, means no two requests can share a socket, a TLS session or
 * anything else a pool is for. A folder of forty rows with a picture beside
 * each is forty handshakes against one host.
 *
 * **What it is keyed by is the whole of the design.** The client holds the
 * credentials in a header and the pin in its engine, so keeping one is only
 * safe while all three of the things it was built from are the same: the
 * address, the credentials and the fingerprint vouched for that host. Editing
 * a server keeps its backup record (stratus-app#131) and changes any of them,
 * which is exactly the case a client cached by instance alone would get wrong
 * -- quietly, by going on authenticating as whoever signed in before.
 *
 * The one it replaces is closed, because what made it stale also made every
 * request still on it wrong.
 */
class KeptConnections(
    private val credentials: suspend (Server) -> Credentials?,
    /** The fingerprint this host is trusted by, where somebody vouched for one. */
    private val pinOf: suspend (Server) -> String?,
    private val build: (Server, Credentials, String?) -> Connection,
) : Connections {

    private val lock = Mutex()
    private var kept: Pair<List<String?>, Connection>? = null

    override suspend fun to(instance: Server): Connection? {
        val credentials = credentials(instance) ?: return null
        val pin = pinOf(instance)
        val key = listOf(instance.baseUrl, credentials.username, credentials.password, pin)
        return lock.withLock {
            kept?.let { (was, connection) -> if (was == key) return@withLock connection }
            build(instance, credentials, pin).also {
                kept?.second?.http?.close()
                kept = key to it
            }
        }
    }

    /** Signing out: the client is holding the password, so it goes with it. */
    suspend fun clear() = lock.withLock {
        kept?.second?.http?.close()
        kept = null
    }
}
