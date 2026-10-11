package dev.stratus.core.backup

import dev.stratus.core.dav.DavClient
import dev.stratus.core.server.Server
import dev.stratus.core.server.ServerStore
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
    suspend fun to(instance: Server): Connection?
}

/** One instance's half of a pass: what to send, what the server has, how far to look. */
class ServerBackup(val queue: UploadQueue, val index: BackupIndex, val mark: BackupMark)

/** Whether the system should bring us back, decided here rather than per platform. */
enum class PassOutcome {
    Finished,
    ComeBackLater,

    /**
     * Parked until the connection stops costing money (stratus-app#133).
     *
     * Its own answer and not [ComeBackLater], because coming back later means
     * coming back to the same metered connection: what this asks for is to be
     * woken by wifi, which both platforms can watch for and neither does by
     * retrying.
     */
    WhenThereIsWifi,
}

/**
 * Everything about backing a camera roll up, assembled.
 *
 * Split out of `AppContainer` because that class cannot be tested -- it opens
 * SQLite, builds HTTP clients and reads the keychain -- so every decision that
 * drifted into it left the fast loop. What is here is the same code with a seam
 * in front of the part that needs a device, and tests around the rest.
 */
class Backup(
    private val instances: ServerStore,
    private val database: BackupDatabase,
    private val assets: AssetSource,
    private val connections: Connections,
    /**
     * How bytes leave this device, which is the one thing a platform may
     * answer differently.
     *
     * Android negotiates between the two Ktor transports and is done. iOS
     * hands the body to the system instead, because a transfer there has to
     * outlive the app (stratus-app#20) -- so the composition root passes one
     * that does, and nothing else here changes.
     */
    private val transports: suspend (Connection, Server) -> Transport =
        { connection, instance -> transportFor(connection, instance.baseUrl) },
    /**
     * What the platform says it is still carrying, or null where nothing is.
     *
     * Asked at the start of a pass. Null and empty are not the same: empty
     * means "nothing is in flight, release everything", which is right after
     * a reinstall and wrong on a platform that has no such notion at all.
     */
    private val liveTransfers: (suspend () -> Set<String>)? = null,
    /** What the connection costs, for the server that asked not to pay it. */
    private val network: Network = Network { false },
) {
    /** What to say about the backup, assembled from what the queue actually holds. */
    val status: BackupStatus by lazy { BackupStatus(database, assets, parked = ::parked) }

    /** The server a pass is owed to, if there is one and it has sources. */
    suspend fun enabled(): Server? = instances.instance()?.takeIf { it.backupEnabled }

    /**
     * One pass over the camera roll.
     *
     * It lives here rather than in each platform's scheduler, which is where
     * it was: whether a pass happens at all and whether to ask to be woken
     * again are decisions, and a decision in a `Worker` is a decision no test
     * can reach. The platform is left holding a notification and a return
     * value.
     */
    suspend fun pass(
        keepGoing: () -> Boolean = { true },
        onStep: (QueueStep) -> Unit = {},
    ): PassOutcome {
        liveTransfers?.let { live -> reconcile(live()) }
        val instance = enabled() ?: return PassOutcome.Finished
        if (!keepGoing()) return PassOutcome.Finished
        val report = run().once(instance, keepGoing, onStep)
        // InFlight too, and not because the system needs reminding about what
        // it is carrying -- it does not. A pass that ended with something in
        // flight may also have left rows serving out a backoff, and that
        // reason is the one that got reported.
        return when (report.stopped) {
            StoppedBecause.WaitingForWifi -> PassOutcome.WhenThereIsWifi
            StoppedBecause.WaitingToRetry, StoppedBecause.InFlight -> PassOutcome.ComeBackLater
            else -> PassOutcome.Finished
        }
    }

    /**
     * A pass, assembled.
     *
     * Built here rather than by each platform's scheduler, so that what a pass
     * is made of is decided once: the platforms contribute only when it runs.
     */
    suspend fun run(): BackupRun = BackupRun(
        source = assets,
        prepareFor = { prepare() },
        journalFor = { database.journal() },
        network = network,
    )

    /**
     * Whether the backup is parked for wifi right now.
     *
     * The same two facts the pass weighs, asked again for the screen rather
     * than remembered from the last pass: the connection changes between
     * passes, and a screen saying "waiting for wifi" over a phone that is on
     * wifi is the kind of lie this surface exists to avoid.
     */
    suspend fun parked(): Boolean {
        val instance = enabled() ?: return false
        return instance.onlyOnWifi && network.metered()
    }

    /**
     * Everything a pass needs, assembled from the server's own settings.
     *
     * The index comes out of the same connection rather than a second one,
     * because building one is an HTTP client and an engine.
     */
    suspend fun prepare(): ServerBackup? {
        val instance = instances.instance() ?: return null
        val connection = connections.to(instance) ?: return null
        val layout = RemoteLayout(instance.backupRoot)
        val cache = database.cache()
        return ServerBackup(
            queue = UploadQueue(
                layout = layout,
                pending = database.pending(),
                cache = cache,
                source = assets,
                transport = transports(connection, instance),
                directories = DavDirectoryMaker(connection.dav),
            ),
            index = BackupIndex(layout, cache, connection.dav),
            mark = database.mark(),
        )
    }

    /**
     * The answer to a transfer the system was carrying, whoever it belongs to.
     *
     * **Built from the database alone**, with no connection: the upload has
     * already happened, and refusing to write it down because a client could
     * not be constructed -- the phone being in a tunnel, say -- would throw
     * away the only record that it did.
     */
    suspend fun settle(ticket: String, answer: TransferAnswer): QueueStep =
        records().settle(ticket, answer)

    /** Puts back everything the platform has forgotten it was carrying. */
    suspend fun reconcile(live: Set<String>): Int = records().reconcile(live)

    private suspend fun records() = UploadRecords(database.pending(), database.cache())

    /** What has gone wrong, with what the server said about each, bounded. */
    suspend fun failures(limit: Int = 20): List<PendingUpload> =
        database.pending().failures(limit)

    /**
     * Which sources feed the backup, which is also the switch.
     *
     * **Empty means no backup at all.** There is no separate flag beside it:
     * two ways to say "not now" is one of them eventually disagreeing with the
     * other, and the question somebody is actually answering on that screen is
     * which photographs they want kept.
     */
    suspend fun setSources(sources: Set<String>) {
        val instance = instances.instance() ?: return
        instances.update(instance.copy(sources = sources))
    }

    /** Whether a pass may spend somebody's data plan (stratus-app#133). */
    suspend fun setOnlyOnWifi(only: Boolean) {
        val instance = instances.instance() ?: return
        instances.update(instance.copy(onlyOnWifi = only))
    }

    /** What there is to choose from on this platform, with how much is in each. */
    suspend fun sources() = assets.sources()

    /** Whether the photo library can be read at all, which decides what to say. */
    suspend fun access() = assets.access()
}
