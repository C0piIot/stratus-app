package dev.stratus.core.backup

import kotlinx.io.RawSource

/**
 * When a photograph was taken, as the device reports it.
 *
 * Wall-clock with no zone, deliberately. The alternative is converting to UTC,
 * which moves a picture into the previous evening's folder for anybody who
 * shoots after dark east of Greenwich, and changes the answer for the same photo
 * depending on where the phone was when it was asked. What somebody means by
 * "September the 17th" is what the clock said.
 */
data class CaptureTime(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
)

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
    val capturedAt: CaptureTime,
    val originalName: String,
    val sizeBytes: Long,
    /** The movie half of a Live Photo. Only a Live Photo with it, so they travel together. */
    val motion: MotionPart? = null,
)

data class MotionPart(val originalName: String, val sizeBytes: Long)

/** Which half of an asset. A Live Photo has both; everything else has a still. */
enum class AssetPart { Still, Motion }

/** Where the camera roll comes from. Implemented per platform in #19 and #20. */
interface AssetSource {
    /** Everything in the sources somebody chose to back up. */
    suspend fun assets(): List<Asset>

    /**
     * The bytes of one part, skipping the first [from] of them.
     *
     * The offset is what makes a resumed upload worth resuming: the platform
     * seeks rather than the queue reading and discarding four gigabytes to get
     * back to where it stopped. A source that cannot seek may read and discard,
     * but it has to look the same from here.
     */
    suspend fun open(localId: String, part: AssetPart, from: Long = 0): RawSource
}
