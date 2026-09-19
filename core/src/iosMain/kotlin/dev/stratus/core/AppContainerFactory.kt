package dev.stratus.core

import dev.stratus.core.backup.IosAssetSource
import dev.stratus.core.files.IosFileHandoff
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
 * The trust policy is ignored here, which is the honest state of it: the Darwin
 * half of accepting a self-signed certificate is stratus-app#58. Until then iOS
 * behaves as it always has -- a certificate nothing vouches for is a server that
 * cannot be reached -- rather than pretending to offer a choice it cannot make.
 */
fun appContainer(): AppContainer = AppContainer(
    engine = { _ -> Darwin.create() },
    secure = KeychainSecureStore(),
    handoff = IosFileHandoff(),
    sharing = IosLinkSharing(),
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
