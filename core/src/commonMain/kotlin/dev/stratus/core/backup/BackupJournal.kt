package dev.stratus.core.backup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import dev.stratus.core.sql.use
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the last pass did, and whether one is happening now. */
data class JournalEntry(
    val running: Boolean,
    val startedAt: Long,
    val finishedAt: Long,
    val outcome: StoppedBecause?,
    val uploaded: Int,
    val failed: Int,
    val currentPath: String?,
)

/**
 * When a backup last ran and how it ended.
 *
 * The one thing the queue cannot answer. `pending` says what is left, which is
 * enough to know there is work but not enough to tell a pass that finished from
 * one that never started -- and those look identical to somebody waiting, which
 * is exactly the doubt this whole surface exists to remove.
 *
 * It is written to and never counted against: what is outstanding comes from
 * `pending`, always, so the two cannot drift into disagreeing.
 */
class BackupJournal(
    private val connection: SQLiteConnection,
    private val instanceId: String,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun began(at: Long) = withContext(io) {
        connection.prepare(
            """
            INSERT INTO journal (instance, running, started_at, uploaded, failed, current_path)
            VALUES (?, 1, ?, 0, 0, NULL)
            ON CONFLICT(instance) DO UPDATE SET
                running = 1, started_at = excluded.started_at,
                uploaded = 0, failed = 0, current_path = NULL
            """.trimIndent(),
        ).use { statement ->
            statement.bindText(1, instanceId)
            statement.bindLong(2, at)
            statement.step()
        }
        Unit
    }

    /** One update per file, which at seconds per file costs nothing. */
    suspend fun sending(path: String?) = withContext(io) {
        connection.prepare("UPDATE journal SET current_path = ? WHERE instance = ?").use { statement ->
            if (path == null) statement.bindNull(1) else statement.bindText(1, path)
            statement.bindText(2, instanceId)
            statement.step()
        }
        Unit
    }

    suspend fun ended(at: Long, outcome: StoppedBecause, uploaded: Int, failed: Int) = withContext(io) {
        connection.prepare(
            """
            UPDATE journal SET running = 0, finished_at = ?, outcome = ?,
                uploaded = ?, failed = ?, current_path = NULL
            WHERE instance = ?
            """.trimIndent(),
        ).use { statement ->
            statement.bindLong(1, at)
            statement.bindText(2, outcome.name)
            statement.bindLong(3, uploaded.toLong())
            statement.bindLong(4, failed.toLong())
            statement.bindText(5, instanceId)
            statement.step()
        }
        Unit
    }

    suspend fun read(): JournalEntry? = withContext(io) {
        connection.prepare(
            """
            SELECT running, started_at, finished_at, outcome, uploaded, failed, current_path
            FROM journal WHERE instance = ?
            """.trimIndent(),
        ).use { statement ->
            statement.bindText(1, instanceId)
            if (!statement.step()) return@withContext null
            JournalEntry(
                running = statement.getLong(0) != 0L,
                startedAt = statement.getLong(1),
                finishedAt = statement.getLong(2),
                outcome = if (statement.isNull(3)) {
                    null
                } else {
                    runCatching { StoppedBecause.valueOf(statement.getText(3)) }.getOrNull()
                },
                uploaded = statement.getInt(4),
                failed = statement.getInt(5),
                currentPath = if (statement.isNull(6)) null else statement.getText(6),
            )
        }
    }
}
