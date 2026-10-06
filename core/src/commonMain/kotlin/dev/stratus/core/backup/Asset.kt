package dev.stratus.core.backup

import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * One thing in the camera roll.
 *
 * [localId] is whatever the platform calls it and is **never** part of its
 * identity: MediaStore ids change on a rescan and `PHAsset` identifiers do not
 * survive restoring onto a new phone. It is here to ask the platform for the
 * bytes again, and for nothing else.
 */
data class Asset(
    val localId: String,
    /**
     * When it was taken, as both platforms report it: milliseconds since the
     * epoch. Split into a date in UTC by [utcPartsOf], never by a local calendar.
     */
    val capturedAtEpochMs: Long,
    /**
     * When it entered this photo library, which is not when it was taken: a
     * shoot imported from a camera today is years old, and a screenshot is
     * both at once. Zero means the platform would not say -- `DATE_ADDED` on
     * Android always does, `PHAsset.addedDate` only from iOS 26 -- and zero is
     * what stops a pass bounding itself by something it does not know
     * (stratus-app#124).
     */
    val addedAtEpochMs: Long = 0,
    val originalName: String,
    val sizeBytes: Long,
    /** The movie half of a Live Photo. Only a Live Photo with it, so they travel together. */
    val motion: MotionPart? = null,
    /**
     * What the platform says it is. Sent with the upload so that a server has
     * no need to guess -- which tus, unlike a PUT, gives it nothing to guess from.
     */
    val mimeType: String? = null,
)

data class MotionPart(val originalName: String, val sizeBytes: Long, val mimeType: String? = null)

/** Which half of an asset. A Live Photo has both; everything else has a still. */
enum class AssetPart { Still, Motion }

/**
 * A place photographs come from: a folder on Android, an album on iOS.
 *
 * A list of sources rather than a list of paths, because an album has no path
 * and never will -- that difference is the whole reason this type exists.
 */
data class MediaSource(val id: String, val label: String, val count: Int)

/**
 * How much of the library the platform will let us see.
 *
 * [Partial] is real and not a corner: Android 13 and up lets somebody grant a
 * selection rather than everything, and iOS has done the same for years. Told as
 * an exception it becomes a backup that reports itself working over a camera
 * roll it cannot read; told as a state, the screen can say so.
 */
enum class MediaAccess { Full, Partial, None }

/** Where the camera roll comes from. Implemented per platform in #19 and #20. */
interface AssetSource {
    suspend fun access(): MediaAccess

    /** What there is to choose from, with how much is in each. */
    suspend fun sources(): List<MediaSource>

    /**
     * Everything in the chosen sources.
     *
     * [addedAfterEpochMs] is an optimisation and not a rule: it skips re-reading
     * rows that were already seen, while [BackupCache] remains the only
     * authority on what has actually been uploaded. Getting it wrong costs a
     * slow pass, never a lost photograph -- which is what lets it be fed from
     * `DATE_ADDED` on Android and from an `addedDate` that only iOS 26 has,
     * with zero everywhere else (stratus-app#124).
     *
     * It is read *here*, when the library is enumerated, and never inside
     * [UploadQueue.enqueue]: an explicit list of assets must always be
     * queueable regardless of it, so that forcing a photograph the bound
     * passed over stays possible.
     */
    suspend fun assets(from: Set<String>, addedAfterEpochMs: Long = 0): List<Asset>

    /**
     * The bytes of one part, skipping the first [from] of them.
     *
     * The offset is what makes a resumed upload worth resuming: the platform
     * seeks rather than the queue reading and discarding four gigabytes to get
     * back to where it stopped. A source that cannot seek may read and discard,
     * but it has to look the same from here.
     */
    suspend fun open(localId: String, part: AssetPart, from: Long = 0): RawSource

    /**
     * The same bytes, written to [toPath].
     *
     * For a transport that uploads **a file and not a stream**, which a
     * background `URLSession` does and cannot do otherwise (stratus-app#20).
     *
     * The caller chooses the path and owns what lands there, so there is no
     * question about who deletes it. The default spools [open], which is the
     * honest answer wherever the library hands out streams; a platform whose
     * bytes are already a file overrides this rather than copying twice --
     * eight gigabytes of temporary space for a four-gigabyte video.
     */
    suspend fun writeTo(localId: String, part: AssetPart, from: Long, toPath: String) {
        open(localId, part, from).use { source ->
            SystemFileSystem.sink(Path(toPath)).buffered().use { sink ->
                sink.transferFrom(source)
            }
        }
    }
}
