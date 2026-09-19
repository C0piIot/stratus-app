package dev.stratus.core

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.stratus.core.backup.AssetSource
import dev.stratus.core.backup.Backup
import dev.stratus.core.backup.BackupDatabase
import dev.stratus.core.backup.Connection
import dev.stratus.core.backup.Connections
import dev.stratus.core.dav.DavClient
import dev.stratus.core.files.BrowserController
import dev.stratus.core.files.FileHandoff
import dev.stratus.core.instance.Instance
import dev.stratus.core.instance.InstanceStore
import dev.stratus.core.net.DavProber
import dev.stratus.core.net.TrustPolicy
import dev.stratus.core.net.hostPortOf
import dev.stratus.core.net.stratusHttpClient
import dev.stratus.core.share.LinkSharing
import dev.stratus.core.share.ShareLinks
import dev.stratus.core.share.Sharing
import dev.stratus.core.signin.SignInController
import dev.stratus.core.store.ConsentStore
import dev.stratus.core.store.SecureStore
import dev.stratus.core.store.TrustStore
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope

/**
 * Everything the app is made of, assembled where the platform pieces are known.
 *
 * It holds the several instances somebody has signed in to and which of them is
 * being looked at. Nothing below it assumes there is only one.
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
    databasePath: String,
    /** Where photographs come from on this platform. */
    val assets: AssetSource,
) {
    private val instances = InstanceStore(secure)

    // Opened once and kept: SQLite does not want a connection per question, and
    // the file is a cache, so losing it costs a rebuild and nothing else.
    private val database by lazy {
        BackupDatabase(BundledSQLiteDriver().open(databasePath))
    }

    /** The one place an instance turns into something that can make requests. */
    private val trust = TrustStore(secure)

    private val connections = Connections { instance ->
        val credentials = instances.credentials(instance.id) ?: return@Connections null
        val pinned = TrustPolicy(trust.pins()[hostPortOf(instance.baseUrl)])
        val client = stratusHttpClient(engine(pinned), credentials)
        Connection(client, DavClient(client, instance.baseUrl))
    }

    val backup: Backup by lazy { Backup(instances, database, assets, connections) }

    fun signIn(scope: CoroutineScope): SignInController = SignInController(
        prober = DavProber { creds, policy -> stratusHttpClient(engine(policy), creds) },
        instances = instances,
        consent = ConsentStore(secure),
        trust = trust,
        scope = scope,
    )

    suspend fun instances(): List<Instance> = instances.all()

    suspend fun current(): Instance? = instances.current()

    suspend fun switchTo(id: String) = instances.switchTo(id)

    /**
     * Forgets an instance and everything cached about it.
     *
     * Both halves together, because an id is never handed out twice: rows left
     * behind would be read by nobody and freed by nobody either.
     */
    suspend fun forget(id: String) {
        instances.remove(id)
        database.forget(id)
    }

    /** Null when nobody is signed in, which is the only state it can be built from. */
    suspend fun browser(scope: CoroutineScope): BrowserController? {
        val instance = instances.current() ?: return null
        val connection = connections.to(instance) ?: return null
        val credentials = instances.credentials(instance.id) ?: return null
        return BrowserController(
            connection.dav,
            handoff,
            scope,
            Sharing(ShareLinks(instance.baseUrl, credentials), sharing),
        )
    }
}
