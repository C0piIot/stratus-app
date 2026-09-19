package dev.stratus.core.backup

import dev.stratus.core.dav.DavClient
import dev.stratus.core.instance.Instance
import dev.stratus.core.instance.InstanceStore
import io.ktor.client.HttpClient

/** An instance, reached: both clients, because tus is negotiated off the raw one. */
class Connection(val http: HttpClient, val dav: DavClient)

/**
 * How an instance is reached.
 *
 * An interface for one reason: it is the only part of assembling a backup that
 * needs an HTTP engine and a keychain, so putting it behind this is what lets
 * everything else here be built in a test. The composition root implements it;
 * `commonTest` implements it with a mock engine and no keychain at all.
 */
fun interface Connections {
    suspend fun to(instance: Instance): Connection?
}

/** Whether the system should bring us back, decided here rather than per platform. */
enum class PassOutcome { Finished, ComeBackLater }

/**
 * Everything about backing a camera roll up, assembled.
 *
 * Split out of `AppContainer` because that class cannot be tested -- it opens
 * SQLite, builds HTTP clients and reads the keychain -- so every decision that
 * drifted into it left the fast loop. What is here is the same code with a seam
 * in front of the part that needs a device, and tests around the rest.
 */
class Backup(
    private val instances: InstanceStore,
    private val database: BackupDatabase,
    private val assets: AssetSource,
    private val connections: Connections,
) {
    /** What to say about the backup, assembled from what the queue actually holds. */
    val status: BackupStatus by lazy { BackupStatus(database, assets) }

    /** The instances a pass is owed to. Backup is per instance and opt-in. */
    suspend fun enabled(): List<Instance> = instances.all().filter { it.backupEnabled }

    /**
     * One pass over every instance that wants one.
     *
     * The loop lives here rather than in each platform's scheduler, which is
     * where it was: which instances get a pass, in what order, and whether to
     * ask to be woken again are decisions, and a decision in a `Worker` is a
     * decision no test can reach. The platform is left holding a notification
     * and a return value.
     *
     * An instance that is unreachable does not stop the others: it produces a
     * queue whose work is waiting to be retried, which is what asks for the
     * next pass.
     */
    suspend fun pass(
        keepGoing: () -> Boolean = { true },
        onStep: (QueueStep) -> Unit = {},
    ): PassOutcome {
        val run = run()
        var again = false
        for (instance in enabled()) {
            if (!keepGoing()) break
            val report = run.once(instance, keepGoing, onStep)
            if (report.stopped == StoppedBecause.WaitingToRetry) again = true
        }
        return if (again) PassOutcome.ComeBackLater else PassOutcome.Finished
    }

    /**
     * A pass, assembled.
     *
     * Built here rather than by each platform's scheduler, so that what a pass
     * is made of is decided once: the platforms contribute only when it runs.
     */
    suspend fun run(): BackupRun = BackupRun(
        source = assets,
        queueFor = { queueFor(it.id) },
        journalFor = { database.journalFor(it.id) },
    )

    /**
     * The queue for one instance, assembled from its own settings.
     *
     * A queue per instance and never a shared one: a photograph owed to two
     * servers is two pieces of work, and an instance that is down must not hold
     * up the other.
     */
    suspend fun queueFor(instanceId: String): UploadQueue? {
        val instance = instances.instance(instanceId) ?: return null
        val connection = connections.to(instance) ?: return null
        return UploadQueue(
            layout = RemoteLayout(instance.backupRoot),
            pending = database.pendingFor(instance.id),
            cache = database.cacheFor(instance.id),
            source = assets,
            transport = transportFor(instance, connection),
            directories = DavDirectoryMaker(connection.dav),
        )
    }

    /**
     * tus where it is offered, `PUT` where it is not.
     *
     * Asked every pass rather than remembered: a server that gains tus tomorrow
     * should be used tomorrow, and there is nothing to invalidate.
     */
    private suspend fun transportFor(instance: Instance, connection: Connection): Transport =
        negotiateTus(connection.http, instance.baseUrl)
            ?.let { endpoint ->
                TusTransport(connection.http, endpoint) { path ->
                    // tus reports no ETag, and the ETag is what makes a later
                    // check a real check. One request per file buys it back.
                    runCatching { connection.dav.stat(path).etag }.getOrNull()
                }
            }
            ?: PutTransport(connection.dav)

    /** Null when nobody is signed in, or for one instance in particular. */
    suspend fun index(instanceId: String? = null): BackupIndex? {
        val instance = (instanceId?.let { instances.instance(it) } ?: instances.current()) ?: return null
        return BackupIndex(
            layout = RemoteLayout(instance.backupRoot),
            cache = database.cacheFor(instance.id),
            dav = connections.to(instance)?.dav ?: return null,
        )
    }

    /** What has gone wrong, with what the server said about each, bounded. */
    suspend fun failures(instanceId: String, limit: Int = 20): List<PendingUpload> =
        database.pendingFor(instanceId).failures(limit)

    /**
     * Turns backup on or off for one instance.
     *
     * Separate from being signed in, because an instance can be worth browsing
     * without being worth sending a camera roll to.
     */
    suspend fun setEnabled(id: String, enabled: Boolean) {
        val instance = instances.instance(id) ?: return
        instances.update(instance.copy(backupEnabled = enabled))
    }

    /**
     * Which sources feed this instance.
     *
     * **Empty means every source**, and that is the only meaning it has: to back
     * up nothing, turn backup off. The screen offering the choice is what keeps
     * the other reading -- "chosen: none" -- from being representable at all,
     * because a set that meant both would be a rule somebody eventually breaks.
     */
    suspend fun setSources(id: String, sources: Set<String>) {
        val instance = instances.instance(id) ?: return
        instances.update(instance.copy(sources = sources))
    }

    /** What there is to choose from on this platform, with how much is in each. */
    suspend fun sources() = assets.sources()

    /** Whether the photo library can be read at all, which decides what to say. */
    suspend fun access() = assets.access()
}
