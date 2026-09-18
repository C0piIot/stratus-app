package dev.stratus.core

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.stratus.core.backup.BackupDatabase
import dev.stratus.core.backup.BackupIndex
import dev.stratus.core.backup.RemoteLayout
import dev.stratus.core.dav.DavClient
import dev.stratus.core.files.BrowserController
import dev.stratus.core.files.FileHandoff
import dev.stratus.core.instance.Instance
import dev.stratus.core.instance.InstanceStore
import dev.stratus.core.net.DavProber
import dev.stratus.core.net.stratusHttpClient
import dev.stratus.core.signin.SignInController
import dev.stratus.core.store.ConsentStore
import dev.stratus.core.store.SecureStore
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope

/**
 * Everything the app is made of, assembled where the platform pieces are known.
 *
 * It holds the several instances somebody has signed in to and which of them is
 * being looked at. Nothing below it assumes there is only one.
 */
class AppContainer(
    private val engine: () -> HttpClientEngine,
    private val secure: SecureStore,
    private val handoff: FileHandoff,
    databasePath: String,
) {
    private val instances = InstanceStore(secure)

    // Opened once and kept: SQLite does not want a connection per question, and
    // the file is a cache, so losing it costs a rebuild and nothing else.
    private val database by lazy {
        BackupDatabase(BundledSQLiteDriver().open(databasePath))
    }

    fun signIn(scope: CoroutineScope): SignInController = SignInController(
        prober = DavProber { creds -> stratusHttpClient(engine(), creds) },
        instances = instances,
        consent = ConsentStore(secure),
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
        return BrowserController(davFor(instance) ?: return null, handoff, scope)
    }

    /** Null when nobody is signed in, or for one instance in particular. */
    suspend fun backupIndex(id: String? = null): BackupIndex? {
        val instance = (id?.let { instances.instance(it) } ?: instances.current()) ?: return null
        database.migrate()
        return BackupIndex(
            layout = RemoteLayout(instance.backupRoot),
            cache = database.cacheFor(instance.id),
            dav = davFor(instance) ?: return null,
        )
    }

    private suspend fun davFor(instance: Instance): DavClient? {
        val credentials = instances.credentials(instance.id) ?: return null
        return DavClient(stratusHttpClient(engine(), credentials), instance.baseUrl)
    }
}
