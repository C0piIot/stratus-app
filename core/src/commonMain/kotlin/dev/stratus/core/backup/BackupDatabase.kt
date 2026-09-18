package dev.stratus.core.backup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
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

/**
 * The one database file, and the schema in it.
 *
 * Separate from [BackupCache] because the schema belongs to the file while a
 * cache belongs to an instance: several instances share one connection and each
 * sees only its own rows.
 */
class BackupDatabase(
    private val connection: SQLiteConnection,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) {
    fun cacheFor(instanceId: String): BackupCache = BackupCache(connection, instanceId, io)

    /**
     * Brings the file up to the current schema.
     *
     * Versioned through `PRAGMA user_version` rather than
     * `CREATE TABLE IF NOT EXISTS`, which cannot alter a table that already
     * exists and would leave an older file silently on an older shape -- here,
     * one where two instances overwrite each other's rows.
     *
     * **A migration is allowed to throw the table away**, and that is worth
     * saying out loud: this is a cache, rebuildable by walking the server, so a
     * schema change costs a rebuild rather than a data migration. It is the
     * first thing that property buys.
     */
    suspend fun migrate() = withContext(io) {
        if (version() < 1) {
            connection.execSQL("DROP TABLE IF EXISTS uploaded")
            connection.execSQL(
                """
                CREATE TABLE uploaded (
                    instance TEXT NOT NULL,
                    path     TEXT NOT NULL,
                    etag     TEXT,
                    size     INTEGER,
                    PRIMARY KEY (instance, path)
                )
                """.trimIndent(),
            )
            connection.execSQL("PRAGMA user_version = 1")
        }
    }

    /** Drops everything an instance knew, for when it is forgotten. */
    suspend fun forget(instanceId: String) = withContext(io) {
        connection.prepare("DELETE FROM uploaded WHERE instance = ?").use { statement ->
            statement.bindText(1, instanceId)
            statement.step()
        }
        Unit
    }

    private fun version(): Int =
        connection.prepare("PRAGMA user_version").use { statement ->
            if (statement.step()) statement.getLong(0).toInt() else 0
        }
}
