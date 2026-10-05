package dev.stratus.core.backup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import dev.stratus.core.sql.use
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Everything outstanding, counted in one query rather than by reading it all. */
data class PendingSummary(
    val total: Int,
    /** Given up on: a permanent failure, which will never be picked up again. */
    val givenUp: Int,
    /**
     * Handed to the system and not yet answered for.
     *
     * Still outstanding and counted in [total], because it is owed until the
     * server has it -- but not waiting on us, which is the difference a screen
     * has to be able to say (stratus-app#20).
     */
    val inFlight: Int = 0,
    val bytesLeft: Long,
    /** When the earliest waiting item becomes runnable, or null if one already is. */
    val nextAttemptAt: Long?,
    /** What the server said about something given up on, for a screen to quote. */
    val givenUpDetail: String? = null,
)

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
    /**
     * What the platform calls the transfer it is carrying, where one is.
     *
     * Set only by a transport that hands work to the system and answers later
     * (stratus-app#20). A row with a ticket is not offered again -- see
     * [PendingStore.next] -- because the alternative is uploading the same
     * photograph twice.
     */
    val ticket: String? = null,
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
            SELECT path, local_id, part, size, content_type, taken_at, offset_at, handle, attempts, last_error, ticket
            FROM pending WHERE instance = ? AND next_at <= ? AND ticket IS NULL
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

    /**
     * Notes that the system is carrying this one, so nothing picks it up again.
     *
     * The offset goes in with it: a transport that hands over says where it
     * believes the server is, and the row has to agree or a resume starts from
     * the wrong place.
     */
    suspend fun recordHandover(path: String, ticket: String, resume: Resume?) = withContext(io) {
        connection.prepare(
            "UPDATE pending SET ticket = ?, offset_at = ?, handle = ? WHERE instance = ? AND path = ?",
        ).use { statement ->
            statement.bindText(1, ticket)
            statement.bindLong(2, resume?.offset ?: 0)
            if (resume?.handle == null) statement.bindNull(3) else statement.bindText(3, resume.handle)
            statement.bindText(4, instanceId)
            statement.bindText(5, path)
            statement.step()
        }
        Unit
    }

    /** Whoever the system has just reported about, or null if nobody. */
    suspend fun byTicket(ticket: String): PendingUpload? = withContext(io) {
        connection.prepare(
            """
            SELECT path, local_id, part, size, content_type, taken_at, offset_at, handle, attempts, last_error, ticket
            FROM pending WHERE instance = ? AND ticket = ? LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.bindText(1, instanceId)
            statement.bindText(2, ticket)
            if (!statement.step()) return@withContext null
            read(statement)
        }
    }

    /** Back on the queue, for a result that is never going to arrive. */
    suspend fun release(path: String) = withContext(io) {
        connection.prepare("UPDATE pending SET ticket = NULL WHERE instance = ? AND path = ?").use { statement ->
            statement.bindText(1, instanceId)
            statement.bindText(2, path)
            statement.step()
        }
        Unit
    }

    /**
     * Frees every row the platform has forgotten about, and answers how many.
     *
     * Done by reading rather than by a `NOT IN` list, because the set comes
     * from the platform and may be empty -- and an empty `IN ()` is a syntax
     * error in SQLite, which would turn "nothing is in flight" into a crash.
     */
    suspend fun releaseExcept(live: Set<String>): Int {
        val stale = all().filter { it.ticket != null && it.ticket !in live }
        for (upload in stale) release(upload.path)
        return stale.size
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
            SELECT path, local_id, part, size, content_type, taken_at, offset_at, handle, attempts, last_error, ticket
            FROM pending WHERE instance = ? ORDER BY taken_at DESC, path ASC
            """.trimIndent(),
        ).use { statement ->
            statement.bindText(1, instanceId)
            while (statement.step()) found += read(statement)
        }
        found
    }

    /**
     * The whole outstanding picture in one query.
     *
     * Counted here rather than by reading every row, because the status strip
     * asks this every second while somebody is looking at it and there may be
     * forty thousand rows behind the answer.
     */
    suspend fun summary(now: Long): PendingSummary = withContext(io) {
        connection.prepare(
            """
            SELECT COUNT(*),
                   SUM(CASE WHEN next_at >= ? THEN 1 ELSE 0 END),
                   SUM(CASE WHEN ticket IS NOT NULL THEN 1 ELSE 0 END),
                   COALESCE(SUM(size - offset_at), 0),
                   MIN(CASE WHEN next_at < ? THEN next_at ELSE NULL END),
                   MAX(CASE WHEN next_at >= ? THEN last_error ELSE NULL END)
            FROM pending WHERE instance = ?
            """.trimIndent(),
        ).use { statement ->
            statement.bindLong(1, NEVER)
            statement.bindLong(2, NEVER)
            statement.bindLong(3, NEVER)
            statement.bindText(4, instanceId)
            if (!statement.step()) return@withContext PendingSummary(0, 0, 0, 0, null)
            PendingSummary(
                total = statement.getInt(0),
                givenUp = statement.getInt(1),
                inFlight = statement.getInt(2),
                bytesLeft = statement.getLong(3),
                nextAttemptAt = if (statement.isNull(4)) null else statement.getLong(4).takeIf { it > now },
                givenUpDetail = if (statement.isNull(5)) null else statement.getText(5),
            )
        }
    }

    /**
     * What has been given up on, bounded.
     *
     * A screen shows a handful and a count; reading forty thousand rows to
     * display twenty of them is how a status display becomes the slow part of
     * an app whose whole job is elsewhere.
     */
    suspend fun failures(limit: Int): List<PendingUpload> = withContext(io) {
        val found = mutableListOf<PendingUpload>()
        connection.prepare(
            """
            SELECT path, local_id, part, size, content_type, taken_at, offset_at, handle, attempts, last_error, ticket
            FROM pending WHERE instance = ? AND next_at >= ?
            ORDER BY taken_at DESC LIMIT ?
            """.trimIndent(),
        ).use { statement ->
            statement.bindText(1, instanceId)
            statement.bindLong(2, NEVER)
            statement.bindLong(3, limit.toLong())
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
        ticket = if (statement.isNull(10)) null else statement.getText(10),
    )

    private companion object {
        /** The marker the queue writes for work it will not try again. */
        const val NEVER = Long.MAX_VALUE
    }
}
