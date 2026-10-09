package dev.stratus.core

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.stratus.core.backup.AssetSource
import dev.stratus.core.backup.Backup
import dev.stratus.core.backup.BackupDatabase
import dev.stratus.core.backup.Connection
import dev.stratus.core.backup.Connections
import dev.stratus.core.backup.Transport
import dev.stratus.core.cast.CastController
import dev.stratus.core.cast.Caster
import dev.stratus.core.dav.DavClient
import dev.stratus.core.documents.DocumentRef
import dev.stratus.core.documents.DocumentTree
import dev.stratus.core.files.BrowserController
import dev.stratus.core.files.FileHandoff
import dev.stratus.core.server.Server
import dev.stratus.core.server.ServerStore
import dev.stratus.core.net.DavProber
import dev.stratus.core.net.TrustPolicy
import dev.stratus.core.net.hostPortOf
import dev.stratus.core.net.stratusHttpClient
import dev.stratus.core.share.LinkSharing
import dev.stratus.core.share.LinkSupport
import dev.stratus.core.share.ShareLinks
import dev.stratus.core.share.Sharing
import dev.stratus.core.session.Ask
import dev.stratus.core.session.AskedOnce
import dev.stratus.core.session.SignedInController
import dev.stratus.core.signin.SignInController
import dev.stratus.core.store.ConsentStore
import dev.stratus.core.store.ReportingConsent
import dev.stratus.core.store.SecureStore
import dev.stratus.core.store.TrustStore
import io.ktor.client.engine.HttpClientEngine
import io.ktor.http.Url
import kotlinx.coroutines.CoroutineScope

/**
 * Everything the app is made of, assembled where the platform pieces are known.
 *
 * It holds the one server somebody has signed in to (stratus-app#131).
 *
 * **Wiring only.** Nothing here can be tested -- it opens a database, builds
 * HTTP clients and reads the keychain -- so anything with a decision in it
 * belongs one level down, where a test can reach it. That is what [backup] is.
 */
class AppContainer(
    private val engine: (TrustPolicy) -> HttpClientEngine,
    private val secure: SecureStore,
    private val handoff: FileHandoff,
    private val sharing: LinkSharing,
    private val caster: Caster,
    databasePath: String,
    /** Where photographs come from on this platform. */
    val assets: AssetSource,
    /**
     * Told when the server changes, for a platform that lists storage
     * locations of its own -- Android's Files app does (stratus-app#104).
     */
    serversChanged: suspend () -> Unit = {},
    /** See [Backup]'s own parameter: the one thing a platform answers differently. */
    private val transports: (suspend (Connection, Server) -> Transport)? = null,
    private val liveTransfers: (suspend () -> Set<String>)? = null,
) {
    private val instances = ServerStore(secure, serversChanged)

    // Opened once and kept: SQLite does not want a connection per question, and
    // the file is a cache, so losing it costs a rebuild and nothing else.
    private val database by lazy {
        BackupDatabase(BundledSQLiteDriver().open(databasePath))
    }

    /** The one place a server turns into something that can make requests. */
    private val trust = TrustStore(secure)

    // Kept because the browser and the caster both ask, and the answer belongs
    // to the server rather than to either of them.
    private var links: LinkSupport? = null

    private val connections = Connections { instance ->
        val credentials = instances.credentials() ?: return@Connections null
        val pinned = TrustPolicy(trust.pins()[hostPortOf(instance.baseUrl)])
        val client = stratusHttpClient(engine(pinned), credentials)
        Connection(client, DavClient(client, instance.baseUrl))
    }

    val backup: Backup by lazy {
        if (transports == null) {
            Backup(instances, database, assets, connections)
        } else {
            Backup(instances, database, assets, connections, transports, liveTransfers)
        }
    }

    /** Whether crash reports may be sent, which the platform reads before anything else runs. */
    val reporting = ReportingConsent(secure)

    fun signIn(scope: CoroutineScope): SignInController = SignInController(
        prober = DavProber { creds, policy -> stratusHttpClient(engine(policy), creds) },
        instances = instances,
        consent = ConsentStore(secure),
        trust = trust,
        scope = scope,
    )

    fun signedIn(ask: ((Set<Ask>) -> Unit)?, reportingChanged: (Boolean) -> Unit) = SignedInController(
        instances, backup, reporting, ::signOut, ask, AskedOnce(secure, "notifications-asked"), reportingChanged,
    )

    suspend fun current(): Server? = instances.instance()

    /**
     * Signs out, forgetting the server and everything cached about it.
     *
     * Both halves together, and the second is not tidying-up: signing out is
     * the only way to a different server (stratus-app#131), and a different
     * server has settled nothing -- carrying the record across would be a
     * camera roll that never gets backed up again.
     */
    suspend fun signOut() {
        instances.clear()
        database.clear()
        links = null
    }

    /**
     * How to reach the server carrying nothing at all.
     *
     * The same trust as everywhere else -- a pinned certificate is still pinned
     * -- and deliberately no credentials: a check for whether a signed link
     * works would succeed against any server if it were authenticated.
     */
    private suspend fun linkSupportFor(instance: Server): LinkSupport {
        links?.let { return it }
        val policy = TrustPolicy(trust.pins()[hostPortOf(instance.baseUrl)])
        return LinkSupport(stratusHttpClient(engine(policy), null)).also { links = it }
    }

    /**
     * Casting for the server, or null when there is none. Built here because
     * only the composition root knows both the certificate it was trusted by
     * and the sender this phone has.
     */
    suspend fun cast(scope: CoroutineScope): CastController? {
        val instance = instances.instance() ?: return null
        val credentials = instances.credentials() ?: return null
        return CastController(
            caster = caster,
            links = ShareLinks(instance.baseUrl, credentials),
            // A television has nobody to ask about a certificate, so a server
            // vouched for by this device alone is one it cannot fetch from.
            certificateIsPinned = Url(instance.baseUrl).protocol.name == "https" &&
                hostPortOf(instance.baseUrl) in trust.pins(),
            support = linkSupportFor(instance),
            scope = scope,
        )
    }

    /**
     * The library as a tree of documents, for the platform's own file picker.
     *
     * One per process and not per request: it holds the listings the picker
     * has been given, and a second copy would answer from an empty cache and
     * fetch everything again.
     */
    fun documents(scope: CoroutineScope, changed: (DocumentRef) -> Unit): DocumentTree =
        DocumentTree(instances, connections, scope, changed)

    /** Null when nobody is signed in, which is the only state it can be built from. */
    suspend fun browser(scope: CoroutineScope): BrowserController? {
        val instance = instances.instance() ?: return null
        val connection = connections.to(instance) ?: return null
        val credentials = instances.credentials() ?: return null
        return BrowserController(
            connection.dav,
            handoff,
            scope,
            Sharing(ShareLinks(instance.baseUrl, credentials), sharing, linkSupportFor(instance)),
        )
    }
}
