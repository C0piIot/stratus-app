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
fun appContainer(): AppContainer = AppContainer(
    engine = { policy -> Darwin.create { handleChallenge(darwinTrust(policy)) } },
    secure = KeychainSecureStore(accessGroup = sharedKeychainGroup()),
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
