package dev.stratus.core.backup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
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

/** What the server is known to hold at a path. */
data class RemoteEntry(val path: String, val etag: String?, val size: Long?)

/**
 * A local note of what is already on the server.
 *
 * **A cache and not the record.** Deleting this file must cost time and nothing
 * else: everything in it can be recovered by asking the server, which is the
 * property that keeps a reinstall from re-uploading somebody's camera roll.
 * Nothing may ever be stored here that cannot be rebuilt from [BackupIndex].
 */
class BackupCache(
    private val connection: SQLiteConnection,
    private val instanceId: String,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) {

    suspend fun has(path: String): Boolean = withContext(io) {
        connection.prepare("SELECT 1 FROM uploaded WHERE instance = ? AND path = ?").use { statement ->
            statement.bindText(1, instanceId)
            statement.bindText(2, path)
            statement.step()
        }
    }

    suspend fun entry(path: String): RemoteEntry? = withContext(io) {
        connection.prepare("SELECT path, etag, size FROM uploaded WHERE instance = ? AND path = ?").use { statement ->
            statement.bindText(1, instanceId)
            statement.bindText(2, path)
            if (!statement.step()) return@withContext null
            RemoteEntry(
                path = statement.getText(0),
                etag = if (statement.isNull(1)) null else statement.getText(1),
                size = if (statement.isNull(2)) null else statement.getLong(2),
            )
        }
    }

    suspend fun record(entry: RemoteEntry) = withContext(io) { recordIn(entry) }

    suspend fun forget(path: String) = withContext(io) {
        connection.prepare("DELETE FROM uploaded WHERE instance = ? AND path = ?").use { statement ->
            statement.bindText(1, instanceId)
            statement.bindText(2, path)
            statement.step()
        }
        Unit
    }

    /**
     * Replaces everything with what the server just said it has.
     *
     * One transaction, because a rebuild interrupted halfway would otherwise
     * leave a cache claiming the server holds less than it does -- and the cost
     * of that mistake is uploading it all again.
     */
    suspend fun replaceAll(entries: List<RemoteEntry>) = withContext(io) {
        connection.execSQL("BEGIN")
        try {
            deleteEverythingForThisInstance()
            for (entry in entries) recordIn(entry)
            connection.execSQL("COMMIT")
        } catch (e: Throwable) {
            connection.execSQL("ROLLBACK")
            throw e
        }
    }

    /**
     * Every path known to be on the server.
     *
     * One query and a set, rather than one query per photograph: asking forty
     * thousand times is how a check meant to be cheap becomes the slow part.
     */
    suspend fun paths(): Set<String> = withContext(io) {
        val found = mutableSetOf<String>()
        connection.prepare("SELECT path FROM uploaded WHERE instance = ?").use { statement ->
            statement.bindText(1, instanceId)
            while (statement.step()) found += statement.getText(0)
        }
        found
    }

    suspend fun size(): Long = withContext(io) {
        connection.prepare("SELECT COUNT(*) FROM uploaded WHERE instance = ?").use { statement ->
            statement.bindText(1, instanceId)
            if (statement.step()) statement.getLong(0) else 0L
        }
    }

    private fun recordIn(entry: RemoteEntry) {
        connection.prepare("INSERT OR REPLACE INTO uploaded (instance, path, etag, size) VALUES (?, ?, ?, ?)")
            .use { statement ->
                statement.bindText(1, instanceId)
                statement.bindText(2, entry.path)
                if (entry.etag == null) statement.bindNull(3) else statement.bindText(3, entry.etag)
                if (entry.size == null) statement.bindNull(4) else statement.bindLong(4, entry.size)
                statement.step()
            }
    }

    private fun deleteEverythingForThisInstance() {
        connection.prepare("DELETE FROM uploaded WHERE instance = ?").use { statement ->
            statement.bindText(1, instanceId)
            statement.step()
        }
    }
}
