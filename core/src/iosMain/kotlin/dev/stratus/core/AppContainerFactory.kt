package dev.stratus.core

import dev.stratus.core.backup.IosAssetSource
import dev.stratus.core.files.IosFileHandoff
import dev.stratus.core.net.darwinTrust
import dev.stratus.core.net.basicAuthHeader
import dev.stratus.core.backup.negotiateTus
import dev.stratus.core.backup.BackgroundUploads
import dev.stratus.core.backup.BackgroundTusTransport
import dev.stratus.core.backup.BackgroundPutTransport
import dev.stratus.core.instance.InstanceStore
import dev.stratus.core.documents.rootOf
import dev.stratus.core.documents.refreshDomains
import dev.stratus.core.cast.NoCaster
import dev.stratus.core.share.IosLinkSharing
import dev.stratus.core.store.KeychainSecureStore
import io.ktor.client.engine.darwin.Darwin
import kotlin.concurrent.Volatile
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

/**
 * See the Android twin.
 *
 * The trust policy is honoured here as it is there (stratus-app#58): the
 * system judges first, and only a certificate it refused is compared against
 * what somebody has vouched for. See [darwinTrust] for the two places the
 * platform forces a different shape.
 */
/**
 * One per process, like the Android twin and for the same reason it gives:
 * two containers are two connections to one SQLite file, and the status poll
 * met the backup's writes as `database is locked`. On iOS there are now three
 * callers in one process -- the interface, the File Provider and the delegate
 * a background transfer relaunches us to run -- so it matters more, not less.
 */
@Volatile private var shared: AppContainer? = null

/**
 * The session this process is carrying transfers in, for the Swift side to
 * hand the relaunch over to. Set when the container is built, which is the
 * only moment there is one.
 */
@Volatile var uploadSession: BackgroundUploads? = null
    internal set

fun appContainer(): AppContainer = container(registersDomains = false)

/**
 * The app's own, which is the one that keeps the Files locations in step
 * (stratus-app#105).
 *
 * Apart from [appContainer] because the File Provider extension builds one
 * too, and an extension has no business registering domains -- it is the
 * thing inside them.
 */
fun hostAppContainer(): AppContainer = container(registersDomains = true)

private fun container(registersDomains: Boolean): AppContainer =
    shared ?: build(registersDomains).also { shared = it }

private fun build(registersDomains: Boolean): AppContainer {
    val secure = KeychainSecureStore(accessGroup = sharedKeychainGroup())
    // Built before the container because the container's transports close over
    // it, and because there is exactly one session per process.
    lateinit var holder: AppContainer
    val uploads = BackgroundUploads { ticket, answer -> holder.backup.settle(ticket, answer) }
    uploadSession = uploads
    return AppContainer(
    engine = { policy -> Darwin.create { handleChallenge(darwinTrust(policy)) } },
    secure = secure,
    handoff = IosFileHandoff(),
    sharing = IosLinkSharing(),
    // No Cast sender here: Google's iOS SDK is a proprietary binary framework
    // needing a Mac to link and the local-network permission to find anything.
    // The same answer a phone without Play Services gets, and the app is whole
    // without it.
    caster = NoCaster(),
    databasePath = databasePath(),
    assets = IosAssetSource(),
    // A store of its own rather than the container's, which does not exist
    // yet here. It is a view of the keychain and holds nothing, so a second
    // one costs nothing either.
    serversChanged = {
        if (registersDomains) refreshDomains(InstanceStore(secure).all().map(::rootOf))
    },
    // The bytes leave through the system, not through this process: a
    // transfer has to survive the app being suspended or killed, which is
    // the whole of stratus-app#20.
    transports = { connection, instance ->
        // On the request and not in a plugin: the session runs outside this
        // process and cannot ask anything of ours for a header.
        val credentials = InstanceStore(secure).credentials(instance.id)?.let(::basicAuthHeader)
        negotiateTus(connection.http, instance.baseUrl)
            ?.let { BackgroundTusTransport(connection.http, it, uploads, credentials) }
            ?: BackgroundPutTransport(instance.baseUrl, uploads, credentials)
    },
    liveTransfers = { uploads.live() },
    ).also { holder = it }
}

/**
 * The keychain group the app and its File Provider extension share
 * (stratus-app#105), or null where there is none to share.
 *
 * Read from the bundle rather than passed in from Swift, because a group has
 * to carry the team's prefix and only the build knows it: both targets put
 * `$(AppIdentifierPrefix)` in their Info.plist under this key, and each
 * process reads its own. Null before there is a developer programme
 * (stratus-app#5), which leaves every process on its own default group --
 * today's behaviour, and the reason this degrades rather than breaks.
 */
private fun sharedKeychainGroup(): String? {
    val prefix = NSBundle.mainBundle.objectForInfoDictionaryKey("AppIdentifierPrefix") as? String
    return prefix?.takeIf { it.isNotBlank() }?.let { it + SHARED_KEYCHAIN }
}

private const val SHARED_KEYCHAIN = "dev.stratus.shared"

/**
 * Application Support rather than Caches or the temporary directory: the file is
 * rebuildable, but having the system delete it under a phone that is mid-backup
 * would mean walking the whole server again for no reason.
 */
@OptIn(ExperimentalForeignApi::class)
private fun databasePath(): String {
    val manager = NSFileManager.defaultManager
    val directory = manager.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask)
        .firstOrNull() as? platform.Foundation.NSURL
    val folder = directory?.URLByAppendingPathComponent("Stratus", isDirectory = true)
    if (folder != null) {
        manager.createDirectoryAtURL(folder, withIntermediateDirectories = true, attributes = null, error = null)
    }
    return folder?.URLByAppendingPathComponent("backup.db")?.path ?: "backup.db"
}
