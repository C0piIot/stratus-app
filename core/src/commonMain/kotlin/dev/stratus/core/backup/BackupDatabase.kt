package dev.stratus.core.backup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import dev.stratus.core.sql.use
import androidx.sqlite.execSQL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    fun pendingFor(instanceId: String): PendingStore = PendingStore(connection, instanceId, io)

    fun journalFor(instanceId: String): BackupJournal = BackupJournal(connection, instanceId, io)

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
        if (version() < 2) {
            // Work that has not finished. It lives in the file rather than in
            // memory because on iOS the system kills the app between transfers
            // and relaunches it to report the result -- a queue that only exists
            // while the app runs is a queue that does not exist.
            connection.execSQL(
                """
                CREATE TABLE pending (
                    instance     TEXT    NOT NULL,
                    path         TEXT    NOT NULL,
                    local_id     TEXT    NOT NULL,
                    part         TEXT    NOT NULL,
                    size         INTEGER NOT NULL,
                    content_type TEXT,
                    taken_at     TEXT    NOT NULL,
                    offset_at    INTEGER NOT NULL DEFAULT 0,
                    handle       TEXT,
                    attempts     INTEGER NOT NULL DEFAULT 0,
                    next_at      INTEGER NOT NULL DEFAULT 0,
                    last_error   TEXT,
                    PRIMARY KEY (instance, path)
                )
                """.trimIndent(),
            )
            connection.execSQL("PRAGMA user_version = 2")
        }
        if (version() < 3) {
            // What a pass did, so a screen can tell "stopped" from "broken".
            // Nothing here is a source of truth about work outstanding -- that
            // is `pending`, and a second count would eventually disagree with it.
            connection.execSQL(
                """
                CREATE TABLE journal (
                    instance     TEXT PRIMARY KEY,
                    running      INTEGER NOT NULL DEFAULT 0,
                    started_at   INTEGER NOT NULL DEFAULT 0,
                    finished_at  INTEGER NOT NULL DEFAULT 0,
                    outcome      TEXT,
                    uploaded     INTEGER NOT NULL DEFAULT 0,
                    failed       INTEGER NOT NULL DEFAULT 0,
                    current_path TEXT
                )
                """.trimIndent(),
            )
            connection.execSQL("PRAGMA user_version = 3")
        }
    }

    /** Drops everything an instance knew, for when it is forgotten. */
    suspend fun forget(instanceId: String) = withContext(io) {
        for (table in listOf("uploaded", "pending", "journal")) {
            connection.prepare("DELETE FROM $table WHERE instance = ?").use { statement ->
                statement.bindText(1, instanceId)
                statement.step()
            }
        }
    }

    private fun version(): Int =
        connection.prepare("PRAGMA user_version").use { statement ->
            if (statement.step()) statement.getLong(0).toInt() else 0
        }
}
