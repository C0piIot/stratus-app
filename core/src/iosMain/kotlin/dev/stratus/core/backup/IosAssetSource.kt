package dev.stratus.core.backup

import kotlinx.io.RawSource

/**
 * The hole #20 fills.
 *
 * Deliberately a class that reports having no access rather than one that throws
 * or is missing: the rest of the app then behaves on iOS exactly as it does on a
 * phone where somebody refused the permission, which is a state it already has
 * to handle. A `TODO()` here would be a crash waiting for a screen to reach it.
 */
class IosAssetSource : AssetSource {
    override suspend fun access() = MediaAccess.None
    override suspend fun sources(): List<MediaSource> = emptyList()
    override suspend fun assets(from: Set<String>, addedAfterEpochMs: Long): List<Asset> = emptyList()
    override suspend fun open(localId: String, part: AssetPart, from: Long): RawSource =
        error("the photo library is not wired up on iOS yet: see stratus-app#20")
}
