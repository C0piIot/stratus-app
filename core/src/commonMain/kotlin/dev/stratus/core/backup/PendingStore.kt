package dev.stratus.core.backup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Closes the statement whatever happens, which SQLite needs and nothing enforces. */
private inline fun <T> SQLiteStatement.use(block: (SQLiteStatement) -> T): T =
    try {
        block(this)
    } finally {
        close()
    }

/** One outstanding piece of work: one part of one asset, for one instance. */
data class PendingUpload(
    val path: String,
    val localId: String,
    val part: AssetPart,
    val size: Long,
    val contentType: String?,
    val takenAt: String,
    val offset: Long = 0,
    val handle: String? = null,
    val attempts: Int = 0,
    val lastError: String? = null,
)

/**
 * The work still to do for one instance, kept where a restart cannot lose it.
 *
 * Scoped to an instance like [BackupCache], for the same reason: a photograph
 * owed to two servers is two pieces of work that succeed and fail apart.
 */
class PendingStore(
    private val connection: SQLiteConnection,
    private val instanceId: String,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) {
    /**
     * Adds work, leaving alone anything already queued.
     *
     * `INSERT OR IGNORE` rather than `REPLACE`: a second enqueue must not throw
     * away the offset an upload already reached, or a large video restarts every
     * time the app looks at the camera roll.
     */
    suspend fun add(upload: PendingUpload) = withContext(io) {
        connection.prepare(
            """
            INSERT OR IGNORE INTO pending
                (instance, path, local_id, part, size, content_type, taken_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.bindText(1, instanceId)
            statement.bindText(2, upload.path)
            statement.bindText(3, upload.localId)
            statement.bindText(4, upload.part.name)
            statement.bindLong(5, upload.size)
            if (upload.contentType == null) statement.bindNull(6) else statement.bindText(6, upload.contentType)
            statement.bindText(7, upload.takenAt)
            statement.step()
        }
        Unit
    }

    /**
     * The next thing worth attempting, newest photograph first.
     *
     * Newest first because the picture somebody just took is the one they check
     * for; a first backup that starts in 2014 looks broken for days. Anything
     * waiting out a backoff is skipped rather than blocking what is behind it.
     */
    suspend fun next(now: Long): PendingUpload? = withContext(io) {
        connection.prepare(
            """
            SELECT path, local_id, part, size, content_type, taken_at, offset_at, handle, attempts, last_error
            FROM pending WHERE instance = ? AND next_at <= ?
            ORDER BY taken_at DESC, path ASC LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.bindText(1, instanceId)
            statement.bindLong(2, now)
            if (!statement.step()) return@withContext null
            read(statement)
        }
    }

    suspend fun recordProgress(path: String, resume: Resume) = withContext(io) {
        connection.prepare("UPDATE pending SET offset_at = ?, handle = ? WHERE instance = ? AND path = ?")
            .use { statement ->
                statement.bindLong(1, resume.offset)
                if (resume.handle == null) statement.bindNull(2) else statement.bindText(2, resume.handle)
                statement.bindText(3, instanceId)
                statement.bindText(4, path)
                statement.step()
            }
        Unit
    }

    suspend fun recordFailure(path: String, detail: String, nextAt: Long) = withContext(io) {
        connection.prepare(
            "UPDATE pending SET attempts = attempts + 1, last_error = ?, next_at = ? WHERE instance = ? AND path = ?",
        ).use { statement ->
            statement.bindText(1, detail)
            statement.bindLong(2, nextAt)
            statement.bindText(3, instanceId)
            statement.bindText(4, path)
            statement.step()
        }
        Unit
    }

    suspend fun remove(path: String) = withContext(io) {
        connection.prepare("DELETE FROM pending WHERE instance = ? AND path = ?").use { statement ->
            statement.bindText(1, instanceId)
            statement.bindText(2, path)
            statement.step()
        }
        Unit
    }

    suspend fun all(): List<PendingUpload> = withContext(io) {
        val found = mutableListOf<PendingUpload>()
        connection.prepare(
            """
            SELECT path, local_id, part, size, content_type, taken_at, offset_at, handle, attempts, last_error
            FROM pending WHERE instance = ? ORDER BY taken_at DESC, path ASC
            """.trimIndent(),
        ).use { statement ->
            statement.bindText(1, instanceId)
            while (statement.step()) found += read(statement)
        }
        found
    }

    suspend fun size(): Long = withContext(io) {
        connection.prepare("SELECT COUNT(*) FROM pending WHERE instance = ?").use { statement ->
            statement.bindText(1, instanceId)
            if (statement.step()) statement.getLong(0) else 0L
        }
    }

    private fun read(statement: SQLiteStatement) = PendingUpload(
        path = statement.getText(0),
        localId = statement.getText(1),
        part = AssetPart.valueOf(statement.getText(2)),
        size = statement.getLong(3),
        contentType = if (statement.isNull(4)) null else statement.getText(4),
        takenAt = statement.getText(5),
        offset = statement.getLong(6),
        handle = if (statement.isNull(7)) null else statement.getText(7),
        attempts = statement.getInt(8),
        lastError = if (statement.isNull(9)) null else statement.getText(9),
    )
}
