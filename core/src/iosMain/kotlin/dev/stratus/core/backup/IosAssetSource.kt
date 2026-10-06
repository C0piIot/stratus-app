package dev.stratus.core.backup

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSDate
import platform.Foundation.NSNumber
import platform.Foundation.NSPredicate
import platform.Foundation.NSSelectorFromString
import platform.Foundation.NSSortDescriptor
import platform.Foundation.valueForKey
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import platform.UniformTypeIdentifiers.UTType
import platform.Photos.PHAccessLevelReadWrite
import platform.Photos.PHAsset
import platform.Photos.PHAssetCollection
import platform.Photos.PHAssetCollectionSubtypeAny
import platform.Photos.PHAssetCollectionSubtypeSmartAlbumUserLibrary
import platform.Photos.PHAssetCollectionTypeAlbum
import platform.Photos.PHAssetCollectionTypeSmartAlbum
import platform.Photos.PHAssetMediaTypeImage
import platform.Photos.PHAssetMediaTypeVideo
import platform.Photos.PHAssetResource
import platform.Photos.PHAssetResourceManager
import platform.Photos.PHAssetResourceRequestOptions
import platform.Photos.PHAssetResourceTypeFullSizePhoto
import platform.Photos.PHAssetResourceTypeFullSizeVideo
import platform.Photos.PHAssetResourceTypePairedVideo
import platform.Photos.PHAssetResourceTypePhoto
import platform.Photos.PHAssetResourceTypeVideo
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHFetchOptions
import platform.Photos.PHFetchResult
import platform.Photos.PHPhotoLibrary
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Asked for once rather than built per photograph in a loop over a camera roll. */
@OptIn(ExperimentalForeignApi::class)
private val addedDateSelector = NSSelectorFromString("addedDate")

/**
 * The camera roll, through PhotoKit (stratus-app#20).
 *
 * The Android twin's rule holds here: **nothing in this file decides
 * anything.** It fills in [MediaRow]s and hands them to [assetOf], which is
 * where the one real judgement -- which timestamp to believe -- lives and
 * where a JVM test reaches it. What is left is the part that genuinely needs
 * a device.
 *
 * Originals and no transcoding: a [PHAssetResource] is the file as the camera
 * wrote it, so a HEIC stays a HEIC. **A Live Photo is two resources** and is
 * only a Live Photo with both, so the still carries the movie as its
 * [MotionPart] and the queue sends them one after the other.
 */
@OptIn(ExperimentalForeignApi::class)
class IosAssetSource : AssetSource {

    /**
     * What the library will let us see.
     *
     * `Limited` is reported and not treated as a refusal: somebody who handed
     * over a selection meant to hand over a selection, and a backup that
     * called it `None` would sit there doing nothing over photographs it can
     * read.
     */
    override suspend fun access(): MediaAccess =
        when (PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevelReadWrite)) {
            PHAuthorizationStatusAuthorized -> MediaAccess.Full
            PHAuthorizationStatusLimited -> MediaAccess.Partial
            else -> MediaAccess.None
        }

    /** Asks, which is the platform shell's to trigger and this file's to perform. */
    suspend fun request(): MediaAccess = suspendCancellableCoroutine { waiting ->
        PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelReadWrite) { status ->
            waiting.resume(
                when (status) {
                    PHAuthorizationStatusAuthorized -> MediaAccess.Full
                    PHAuthorizationStatusLimited -> MediaAccess.Partial
                    else -> MediaAccess.None
                },
            )
        }
    }

    /**
     * The albums, with how much is in each.
     *
     * The user library -- what the Photos app calls Recents -- comes first and
     * is the one almost everybody wants; the albums somebody made follow.
     */
    override suspend fun sources(): List<MediaSource> {
        val found = mutableListOf<MediaSource>()
        for (collection in collections()) {
            val count = PHAsset.fetchAssetsInAssetCollection(collection, mediaOnly()).count.toInt()
            if (count == 0) continue
            found += MediaSource(
                id = collection.localIdentifier,
                label = collection.localizedTitle ?: collection.localIdentifier,
                count = count,
            )
        }
        return found
    }

    override suspend fun assets(from: Set<String>, addedAfterEpochMs: Long): List<Asset> {
        // Newest taken first, which is the order a camera roll is read in.
        // Not newest *added*: that is a key iOS 26 introduced and asking an
        // older one to sort by it raises rather than degrades.
        val options = mediaOnly().apply {
            sortDescriptors = listOf(NSSortDescriptor.sortDescriptorWithKey("creationDate", ascending = false))
        }

        val results = if (from.isEmpty()) {
            listOf(PHAsset.fetchAssetsWithOptions(options))
        } else {
            collections().filter { it.localIdentifier in from }
                .map { PHAsset.fetchAssetsInAssetCollection(it, options) }
        }

        // An album and the user library both hold the same photograph, so a
        // selection of several would otherwise queue it twice.
        val seen = mutableSetOf<String>()
        val found = mutableListOf<Asset>()
        for (result in results) {
            for (index in 0UL until result.count) {
                val asset = result.objectAtIndex(index) as? PHAsset ?: continue
                // Skipped and never broken out of: the results are in taken
                // order and a date added does not follow it, so there is no
                // point past which the rest can be assumed old. What it saves
                // is rowOf, which is the expensive half -- it enumerates the
                // asset's resources and asks each one its size.
                val added = addedAtOf(asset)
                if (addedAfterEpochMs > 0 && added in 1..addedAfterEpochMs) continue
                if (!seen.add(asset.localIdentifier)) continue
                rowOf(asset)?.let { found += assetOf(it) }
            }
        }
        return found
    }

    /**
     * The bytes of one part.
     *
     * Written to a file first, because that is what PhotoKit offers and what a
     * background `URLSession` will want anyway -- it uploads from a file and
     * not from a stream. The copy goes when the source is closed.
     */
    override suspend fun open(localId: String, part: AssetPart, from: Long): RawSource {
        val asset = assetWith(localId) ?: error("the library has no $localId any more")
        val resource = resourcesOf(asset).let { if (part == AssetPart.Motion) it.motion else it.still }
            ?: error("$localId has no ${part.name.lowercase()} to send")

        val path = Path(NSTemporaryDirectory() + "stratus-" + resource.assetLocalIdentifier + "-" + part.name)
        SystemFileSystem.delete(path, mustExist = false)
        writeOut(resource, path)

        val source = SystemFileSystem.source(path).buffered()
        if (from > 0) source.skip(from)
        return DeletingSource(source, path)
    }

    /**
     * The bytes straight onto [toPath], without the copy [open] would make.
     *
     * PhotoKit writes a file; a background upload wants a file. Spooling the
     * stream from one into the other would put a four-gigabyte video on this
     * disk twice (stratus-app#20).
     *
     * From an offset it cannot be helped: `writeData` writes the whole
     * resource, so the tail is cut afterwards. Only a resumed upload pays it.
     */
    override suspend fun writeTo(localId: String, part: AssetPart, from: Long, toPath: String) {
        val asset = assetWith(localId) ?: error("the library has no $localId any more")
        val resource = resourcesOf(asset).let { if (part == AssetPart.Motion) it.motion else it.still }
            ?: error("$localId has no ${part.name.lowercase()} to send")

        val destination = Path(toPath)
        SystemFileSystem.delete(destination, mustExist = false)
        if (from == 0L) {
            writeOut(resource, destination)
            return
        }

        val whole = Path(toPath + ".whole")
        SystemFileSystem.delete(whole, mustExist = false)
        writeOut(resource, whole)
        try {
            SystemFileSystem.source(whole).buffered().use { source ->
                source.skip(from)
                SystemFileSystem.sink(destination).buffered().use { sink -> sink.transferFrom(source) }
            }
        } finally {
            SystemFileSystem.delete(whole, mustExist = false)
        }
    }

    // ---- PhotoKit, and nothing else below here ----------------------------

    private fun collections(): List<PHAssetCollection> = buildList {
        addAll(
            PHAssetCollection.fetchAssetCollectionsWithType(
                PHAssetCollectionTypeSmartAlbum,
                PHAssetCollectionSubtypeSmartAlbumUserLibrary,
                null,
            ).all(),
        )
        addAll(
            PHAssetCollection.fetchAssetCollectionsWithType(
                PHAssetCollectionTypeAlbum,
                PHAssetCollectionSubtypeAny,
                null,
            ).all(),
        )
    }

    // Written out rather than bound, because a predicate with arguments is one
    // more thing to be wrong about in a file nothing here can compile.
    private fun mediaOnly() = PHFetchOptions().apply {
        predicate = NSPredicate.predicateWithFormat(
            "mediaType == $PHAssetMediaTypeImage || mediaType == $PHAssetMediaTypeVideo",
        )
    }

    private fun assetWith(localId: String): PHAsset? =
        PHAsset.fetchAssetsWithLocalIdentifiers(listOf(localId), null).all<PHAsset>().firstOrNull()

    /**
     * The still and, for a Live Photo, the movie beside it.
     *
     * An edited photograph has both the original and the rendered version;
     * the original is what gets uploaded, because a backup that keeps the
     * edit and loses what it was made from is not a backup.
     */
    private fun resourcesOf(asset: PHAsset): Resources {
        val all = PHAssetResource.assetResourcesForAsset(asset).filterIsInstance<PHAssetResource>()
        val still = all.firstOrNull { it.type == PHAssetResourceTypePhoto }
            ?: all.firstOrNull { it.type == PHAssetResourceTypeVideo }
            ?: all.firstOrNull { it.type == PHAssetResourceTypeFullSizePhoto }
            ?: all.firstOrNull { it.type == PHAssetResourceTypeFullSizeVideo }
        val motion = all.firstOrNull { it.type == PHAssetResourceTypePairedVideo }
        return Resources(still, motion)
    }

    /**
     * When the library was given this photograph, or 0 where it will not say.
     *
     * `PHAsset.addedDate` is **iOS 26**, and the deployment target is 15, so it
     * is asked for rather than called: on anything older the selector is not
     * there and the answer is zero, which is what [BackupMark] reads as "do not
     * move the bound" (stratus-app#124). `valueForKey` and not the property,
     * because a property this SDK may not know is a compile error and a
     * selector that is not there is a question with an answer.
     *
     * It is the date from the device that added the asset, so one synced down
     * from an iPad carries the iPad's. That is exactly why it bounds the
     * enumeration and decides nothing: a backdated arrival costs a photograph
     * that is enumerated anyway, because the settled record is what says
     * whether it has been sent.
     */
    private fun addedAtOf(asset: PHAsset): Long {
        if (!asset.respondsToSelector(addedDateSelector)) return 0
        val date = asset.valueForKey("addedDate") as? NSDate ?: return 0
        return (date.timeIntervalSince1970 * 1000).toLong()
    }

    private suspend fun rowOf(asset: PHAsset): MediaRow? {
        val (still, motion) = resourcesOf(asset)
        if (still == null) return null
        val size = sizeOf(still) ?: return null
        return MediaRow(
            id = asset.localIdentifier,
            displayName = still.originalFilename,
            sizeBytes = size,
            bucketId = null,
            takenEpochMs = asset.creationDate?.let { (it.timeIntervalSince1970 * 1000).toLong() },
            addedEpochSeconds = addedAtOf(asset) / 1_000,
            mimeType = typeOf(still),
            motion = motion?.let { movie ->
                sizeOf(movie)?.let { MotionPart(movie.originalFilename, it, typeOf(movie)) }
            },
        )
    }

    /**
     * How many bytes the resource is.
     *
     * **Through an undocumented key**, and that is a decision rather than an
     * oversight: PhotoKit publishes no size, and the only documented way to
     * learn one is to read the whole resource -- which on a first pass means
     * reading the entire camera roll to find out how big it is. The size is
     * not cosmetic: it is part of the remote path's digest *and* the length
     * tus declares, so a wrong one is an upload the server refuses.
     *
     * It degrades rather than breaks: a build where the key stops answering
     * measures instead, and pays the read only for the assets it has to.
     */
    private suspend fun sizeOf(resource: PHAssetResource): Long? =
        (resource.valueForKey("fileSize") as? NSNumber)?.longLongValue ?: measure(resource)

    /** The documented way, kept for when the undocumented one stops answering. */
    private suspend fun measure(resource: PHAssetResource): Long? = runCatching {
        var total = 0L
        suspendCancellableCoroutine { waiting ->
            PHAssetResourceManager.defaultManager().requestDataForAssetResource(
                resource,
                options = network(),
                dataReceivedHandler = { data -> total += data?.length?.toLong() ?: 0 },
                completionHandler = { error ->
                    if (error == null) waiting.resume(Unit) else waiting.resumeWithException(PhotoLibraryFailure(error.localizedDescription))
                },
            )
        }
        total
    }.getOrNull()

    private suspend fun writeOut(resource: PHAssetResource, path: Path) =
        suspendCancellableCoroutine { waiting ->
            PHAssetResourceManager.defaultManager().writeDataForAssetResource(
                resource,
                toFile = NSURL.fileURLWithPath(path.toString()),
                options = network(),
                completionHandler = { error ->
                    if (error == null) waiting.resume(Unit) else waiting.resumeWithException(PhotoLibraryFailure(error.localizedDescription))
                },
            )
        }

    /**
     * Network access allowed, which is what makes a library stored in iCloud
     * backable at all: without it an asset that is not on this phone fails
     * with no way to tell that from a corrupt one.
     */
    private fun network() = PHAssetResourceRequestOptions().apply { networkAccessAllowed = true }

    /** What the server is told it is, so tus has nothing to guess from. */
    private fun typeOf(resource: PHAssetResource): String? =
        resource.uniformTypeIdentifier?.let { UTType.typeWithIdentifier(it)?.preferredMIMEType }

    private data class Resources(val still: PHAssetResource?, val motion: PHAssetResource?)

}

class PhotoLibraryFailure(detail: String?) : Exception(detail ?: "the photo library refused")

/** Every element of a fetch result, which has no iterator of its own. */
@Suppress("UNCHECKED_CAST")
private fun <T> PHFetchResult.all(): List<T> =
    (0UL until count).map { objectAtIndex(it) as T }

/**
 * A source over a copy, which goes when the source is closed.
 *
 * The copy is unavoidable -- PhotoKit hands out files and not streams -- so
 * what matters is that it does not accumulate: a camera roll's worth of
 * temporary files is the camera roll a second time.
 */
private class DeletingSource(private val inner: RawSource, private val path: Path) : RawSource by inner {
    override fun close() {
        inner.close()
        SystemFileSystem.delete(path, mustExist = false)
    }
}
