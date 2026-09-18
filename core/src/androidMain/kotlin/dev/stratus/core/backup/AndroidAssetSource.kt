package dev.stratus.core.backup

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.RawSource
import kotlinx.io.asSource

/**
 * The camera roll, through `MediaStore`.
 *
 * Nothing here decides anything: it fills in [MediaRow]s from a cursor and hands
 * them to [assetOf], which is where the one real judgement -- which timestamp to
 * believe -- lives, and where a JVM test can reach it. What is left is the part
 * that genuinely needs a device.
 */
class AndroidAssetSource(private val context: Context) : AssetSource {

    override suspend fun access(): MediaAccess = when {
        Build.VERSION.SDK_INT < 33 -> if (has(Manifest.permission.READ_EXTERNAL_STORAGE)) {
            MediaAccess.Full
        } else {
            MediaAccess.None
        }

        has(Manifest.permission.READ_MEDIA_IMAGES) && has(Manifest.permission.READ_MEDIA_VIDEO) ->
            MediaAccess.Full

        // Android 14 lets somebody hand over a selection instead of the library.
        // It is a state to report, not a failure: a roll that looks empty because
        // of it would be a backup quietly doing nothing.
        Build.VERSION.SDK_INT >= 34 && has(READ_MEDIA_VISUAL_USER_SELECTED) -> MediaAccess.Partial

        else -> MediaAccess.None
    }

    override suspend fun sources(): List<MediaSource> = withContext(Dispatchers.IO) {
        val counts = mutableMapOf<String, Pair<String, Int>>()
        query(
            projection = arrayOf(BUCKET_ID, BUCKET_NAME),
            selection = MEDIA_SELECTION,
            arguments = MEDIA_ARGUMENTS,
        ) { cursor ->
            val bucket = cursor.getString(0) ?: return@query
            val label = cursor.getString(1) ?: bucket
            val seen = counts[bucket]
            counts[bucket] = label to ((seen?.second ?: 0) + 1)
        }
        counts.map { (id, it) -> MediaSource(id, it.first, it.second) }.sortedByDescending { it.count }
    }

    override suspend fun assets(from: Set<String>, addedAfterEpochMs: Long): List<Asset> =
        withContext(Dispatchers.IO) {
            val conditions = mutableListOf(MEDIA_SELECTION)
            val arguments = MEDIA_ARGUMENTS.toMutableList()
            if (from.isNotEmpty()) {
                conditions += "$BUCKET_ID IN (${from.joinToString(",") { "?" }})"
                arguments += from
            }
            if (addedAfterEpochMs > 0) {
                // DATE_ADDED is in seconds, which is a difference worth a bug if
                // it goes unnoticed: milliseconds here would skip everything.
                conditions += "$DATE_ADDED > ?"
                arguments += (addedAfterEpochMs / 1_000).toString()
            }

            val found = mutableListOf<Asset>()
            query(
                projection = arrayOf(ID, NAME, SIZE, BUCKET_ID, DATE_TAKEN, DATE_ADDED),
                selection = conditions.joinToString(" AND "),
                arguments = arguments.toTypedArray(),
                order = "$DATE_ADDED DESC",
            ) { cursor ->
                found += assetOf(
                    MediaRow(
                        id = cursor.getLong(0).toString(),
                        displayName = cursor.getString(1),
                        sizeBytes = cursor.getLong(2),
                        bucketId = cursor.getString(3),
                        takenEpochMs = if (cursor.isNull(4)) null else cursor.getLong(4),
                        addedEpochSeconds = cursor.getLong(5),
                    ),
                )
            }
            found
        }

    override suspend fun open(localId: String, part: AssetPart, from: Long): RawSource =
        withContext(Dispatchers.IO) {
            val uri = ContentUris.withAppendedId(CONTENT_URI, localId.toLong())
            val stream = requireNotNull(context.contentResolver.openInputStream(uri)) {
                "the library would not open $localId"
            }
            // Skipping rather than reading and discarding where the stream can,
            // which is what makes resuming a large video worth anything.
            var left = from
            while (left > 0) {
                val skipped = stream.skip(left)
                if (skipped <= 0) break
                left -= skipped
            }
            stream.asSource()
        }

    private fun has(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private inline fun query(
        projection: Array<String>,
        selection: String,
        arguments: Array<String>,
        order: String? = null,
        row: (android.database.Cursor) -> Unit,
    ) {
        context.contentResolver.query(CONTENT_URI, projection, selection, arguments, order)?.use { cursor ->
            while (cursor.moveToNext()) row(cursor)
        }
    }

    private companion object {
        val CONTENT_URI = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

        const val ID = MediaStore.Files.FileColumns._ID
        const val NAME = MediaStore.Files.FileColumns.DISPLAY_NAME
        const val SIZE = MediaStore.Files.FileColumns.SIZE
        const val BUCKET_ID = MediaStore.Files.FileColumns.BUCKET_ID
        const val BUCKET_NAME = MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME
        const val DATE_TAKEN = MediaStore.Files.FileColumns.DATE_TAKEN
        const val DATE_ADDED = MediaStore.Files.FileColumns.DATE_ADDED

        // Pictures and video, and nothing else a file provider happens to hold.
        const val MEDIA_SELECTION = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
        val MEDIA_ARGUMENTS = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )

        // Named rather than referenced: the constant only exists from API 34 and
        // the check above is what keeps it from being asked for below that.
        const val READ_MEDIA_VISUAL_USER_SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
    }
}
