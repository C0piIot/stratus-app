package dev.stratus.core

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.stratus.core.backup.AssetSource
import dev.stratus.core.backup.BackupDatabase
import dev.stratus.core.backup.BackupIndex
import dev.stratus.core.backup.BackupRun
import dev.stratus.core.backup.BackupStatus
import dev.stratus.core.backup.PendingUpload
import dev.stratus.core.backup.DavDirectoryMaker
import dev.stratus.core.backup.PutTransport
import dev.stratus.core.backup.UploadQueue
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
    /** Where photographs come from on this platform. */
    val assets: AssetSource,
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

    /**
     * A pass over the camera roll, assembled.
     *
     * Built here rather than by each platform's scheduler, so that what a pass
     * is made of is decided once: the platforms contribute only when it runs.
     */
    suspend fun backupRun(): BackupRun {
        database.migrate()
        return BackupRun(
            source = assets,
            queueFor = { backupQueue(it.id) },
            journalFor = { database.journalFor(it.id) },
        )
    }

    /** What has gone wrong, with what the server said about each. */
    suspend fun backupFailures(instanceId: String): List<PendingUpload> {
        database.migrate()
        return database.pendingFor(instanceId).all().filter { it.lastError != null }
    }

    /** What to say about the backup, assembled from what the queue actually holds. */
    val backupStatus: BackupStatus by lazy { BackupStatus(database, assets) }

    /**
     * Turns backup on or off for one instance.
     *
     * Separate from being signed in, because an instance can be worth browsing
     * without being worth sending a camera roll to.
     */
    suspend fun setBackupEnabled(id: String, enabled: Boolean) {
        val instance = instances.instance(id) ?: return
        instances.update(instance.copy(backupEnabled = enabled))
    }

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

    /**
     * The queue for one instance, assembled from its own settings.
     *
     * A queue per instance and never a shared one: a photograph owed to two
     * servers is two pieces of work, and an instance that is down must not hold
     * up the other.
     */
    suspend fun backupQueue(instanceId: String): UploadQueue? {
        val instance = instances.instance(instanceId) ?: return null
        val dav = davFor(instance) ?: return null
        database.migrate()
        return UploadQueue(
            layout = RemoteLayout(instance.backupRoot),
            pending = database.pendingFor(instance.id),
            cache = database.cacheFor(instance.id),
            source = assets,
            // Plain PUT until tus is negotiated in #18.
            transport = PutTransport(dav),
            directories = DavDirectoryMaker(dav),
        )
    }

    private suspend fun davFor(instance: Instance): DavClient? {
        val credentials = instances.credentials(instance.id) ?: return null
        return DavClient(stratusHttpClient(engine(), credentials), instance.baseUrl)
    }
}
