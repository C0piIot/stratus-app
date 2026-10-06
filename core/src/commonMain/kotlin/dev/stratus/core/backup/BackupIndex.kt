package dev.stratus.core.backup

import dev.stratus.core.dav.DavClient
import dev.stratus.core.dav.DavError

/**
 * What the server already holds, asked once when this phone knows nothing.
 *
 * The walk is the reason the rest of this package is shaped the way it is: a
 * phone that lost its record has to be able to recover it by asking, and that
 * is only possible because a path is a function of the photograph.
 *
 * **It adds and never replaces** (stratus-app#124). What the server holds now
 * and what this backup has settled are different questions -- a file deleted
 * there on purpose is settled and not held -- so a walk may only ever teach the
 * record something, never take something away from it.
 */
class BackupIndex(
    private val layout: RemoteLayout,
    private val cache: BackupCache,
    private val dav: DavClient,
) {
    /**
     * Walks the server if this phone has settled nothing, and answers how much
     * it found, or null if there was no reason to look.
     *
     * **Nothing settled is a cold start**, which is a first pass or a phone
     * that lost the file, and both want the same thing: ask before sending, so
     * a reinstall costs a listing per month rather than the whole camera roll
     * over somebody's mobile data.
     */
    suspend fun warmUp(assets: List<Asset>): Int? =
        if (cache.isEmpty()) walk(assets) else null

    /**
     * Asks the server what it holds, settles all of it, and answers how much.
     *
     * One listing per month rather than one request per photograph: ten years of
     * pictures is a hundred and twenty requests, paid once after a reinstall
     * instead of forty thousand uploads paid always. A folder that is not there
     * yet is not an error -- it is a month nothing has been uploaded for.
     */
    suspend fun walk(assets: List<Asset>): Int {
        val found = mutableListOf<String>()
        for (directory in layout.directoriesFor(assets)) {
            val entries = try {
                dav.list(directory)
            } catch (_: DavError.NotFound) {
                continue
            }
            entries.filterNot { it.isDirectory }.mapTo(found) { it.path }
        }
        cache.add(found)
        return found.size
    }

    /**
     * The assets this backup has not settled, both halves of a Live Photo
     * counting.
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
}
