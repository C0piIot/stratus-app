package dev.stratus.core.backup

import dev.stratus.core.dav.DavClient
import dev.stratus.core.dav.DavError

/**
 * What the server has, and therefore what is left to send.
 *
 * The rebuild is the reason the rest of this package is shaped the way it is: a
 * phone that lost its cache has to be able to recover it by asking, and that is
 * only possible because a path is a function of the photograph.
 */
class BackupIndex(
    private val layout: RemoteLayout,
    private val cache: BackupCache,
    private val dav: DavClient,
) {
    /**
     * Rebuilds the cache from the server, and returns how much it found.
     *
     * One listing per month rather than one request per photograph: ten years of
     * pictures is a hundred and twenty requests, paid once after a reinstall
     * instead of forty thousand paid always. A folder that is not there yet is
     * not an error -- it is a month nothing has been uploaded for.
     */
    suspend fun rebuild(assets: List<Asset>): Int {
        val found = mutableListOf<RemoteEntry>()
        for (directory in layout.directoriesFor(assets)) {
            val entries = try {
                dav.list(directory)
            } catch (_: DavError.NotFound) {
                continue
            }
            entries.filterNot { it.isDirectory }
                .mapTo(found) { RemoteEntry(it.path, it.etag, it.size) }
        }
        cache.replaceAll(found)
        return found.size
    }

    /**
     * The assets the server has not got, both halves of a Live Photo counting.
     *
     * Half a Live Photo is worse than neither, so one that is missing its movie
     * is missing, whatever happened to the still.
     */
    suspend fun missing(assets: List<Asset>): List<Asset> {
        val known = cache.paths()
        return assets.filter { asset ->
            layout.pathFor(asset) !in known ||
                layout.motionPathFor(asset)?.let { it !in known } == true
        }
    }

    /**
     * Whether what is up there is what is down here, when the server gives enough
     * to say so.
     *
     * A strong ETag from Stratus is a SHA-256 of the stored bytes and settles it.
     * A server offering a weak one, or none, leaves only existence -- and the
     * honest answer is then "cannot verify" rather than a comfortable yes.
     */
    suspend fun verification(asset: Asset): Verification {
        val entry = cache.entry(layout.pathFor(asset)) ?: return Verification.Absent
        return when {
            entry.etag == null -> Verification.PresentButUnverifiable
            entry.size != null && entry.size != asset.sizeBytes -> Verification.Differs
            else -> Verification.Present(entry.etag)
        }
    }
}

sealed interface Verification {
    data object Absent : Verification
    data class Present(val etag: String) : Verification
    data object PresentButUnverifiable : Verification
    data object Differs : Verification
}
