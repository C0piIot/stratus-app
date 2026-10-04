package dev.stratus.core

import dev.stratus.core.backup.IosAssetSource
import dev.stratus.core.files.IosFileHandoff
import dev.stratus.core.net.darwinTrust
import dev.stratus.core.cast.NoCaster
import dev.stratus.core.share.IosLinkSharing
import dev.stratus.core.store.KeychainSecureStore
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
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
fun appContainer(): AppContainer = AppContainer(
    engine = { policy -> Darwin.create { handleChallenge(darwinTrust(policy)) } },
    secure = KeychainSecureStore(),
    handoff = IosFileHandoff(),
    sharing = IosLinkSharing(),
    // No Cast sender here: Google's iOS SDK is a proprietary binary framework
    // needing a Mac to link and the local-network permission to find anything.
    // The same answer a phone without Play Services gets, and the app is whole
    // without it.
    caster = NoCaster(),
    databasePath = databasePath(),
    assets = IosAssetSource(),
)

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
