package dev.stratus.core.backup

/**
 * One row as a media library reports it, before anything is decided about it.
 *
 * Pure, so the part that actually chooses -- which timestamp to believe, what
 * counts as a name -- is covered by the fast loop rather than by an emulator.
 * The platform's job is to fill this in from a cursor and nothing else.
 */
data class MediaRow(
    val id: String,
    val displayName: String?,
    val sizeBytes: Long,
    val bucketId: String?,
    val takenEpochMs: Long?,
    val addedEpochSeconds: Long,
)

/**
 * Turns a library row into an asset.
 *
 * The timestamp is the decision here. `DATE_TAKEN` is when the picture was
 * taken and is what should decide where it files, but it is absent for anything
 * the camera did not write -- a screenshot, a download, a video from a messaging
 * app -- and `DATE_ADDED` is then the closest honest answer. Preferring the
 * wrong one silently files half a library under the day somebody installed the
 * app.
 */
fun assetOf(row: MediaRow): Asset = Asset(
    localId = row.id,
    capturedAtEpochMs = row.takenEpochMs?.takeIf { it > 0 } ?: (row.addedEpochSeconds * 1_000),
    // A row with no name still has to land somewhere findable rather than be
    // dropped, and the digest keeps it from colliding with the next one.
    originalName = row.displayName?.takeIf { it.isNotBlank() } ?: "untitled",
    sizeBytes = row.sizeBytes,
)
