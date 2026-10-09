package dev.stratus.core.backup

import androidx.sqlite.SQLiteConnection
import dev.stratus.core.sql.use
import androidx.sqlite.execSQL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The one database file, and the schema in it.
 *
 * Separate from [BackupCache] because the schema belongs to the file while a
 * cache belongs to the work: the stores over it are narrow views of the same
 * connection.
 */
class BackupDatabase(
    private val connection: SQLiteConnection,
    // One thread at a time, because the connection is shared and SQLite's is
    // not safe to use from two at once: the status poll and a resume reading
    // together was a SIGSEGV in the native library, found by the end-to-end
    // suite (stratus-app#78).
    private val io: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) {
    private val schema = Mutex()
    private var migrated = false

    /**
     * Applies the schema once, before anything is handed out.
     *
     * Every accessor does this rather than trusting a caller to have called
     * [migrate] first. That rule was kept by memory in five places and the sixth
     * would have been a missing table at runtime, on somebody's phone, in a
     * background pass nobody is watching.
     */
    private suspend fun ready() {
        if (migrated) return
        schema.withLock { if (!migrated) { migrate(); migrated = true } }
    }

    suspend fun cache(): BackupCache {
        ready()
        return BackupCache(connection, io)
    }

    suspend fun pending(): PendingStore {
        ready()
        return PendingStore(connection, io)
    }

    suspend fun journal(): BackupJournal {
        ready()
        return BackupJournal(connection, io)
    }

    suspend fun mark(): BackupMark {
        ready()
        return BackupMark(connection, io)
    }

    /**
     * Writes the schema, throwing away anything older.
     *
     * **One schema, rewritten rather than migrated** (stratus-app#131). The six
     * stepped versions this replaces carried an `instance` column through every
     * table, and the app holds one server now -- so there was a choice between
     * keeping that column forever at one value and dropping it. Nothing is
     * deployed anywhere but a test phone, and this file is a cache: what is in
     * it is rebuildable by walking the server, and what is not -- the queue --
     * is a pass away from being queued again. So an older file is dropped.
     *
     * The rule from here is the ordinary one: a migration is added, never
     * edited, and the next number is 8.
     */
    suspend fun migrate() = withContext(io) {
        if (version() >= SCHEMA) return@withContext
        for (table in listOf("uploaded", "settled", "pending", "journal", "marks")) {
            connection.execSQL("DROP TABLE IF EXISTS $table")
        }

        // What has been settled rather than what the server holds
        // (stratus-app#124). The two are not the same question and only one of
        // them can answer "was this deleted on purpose?".
        connection.execSQL("CREATE TABLE settled (path TEXT PRIMARY KEY)")

        // Work that has not finished. It lives in the file rather than in
        // memory because on iOS the system kills the app between transfers and
        // relaunches it to report the result -- a queue that only exists while
        // the app runs is a queue that does not exist. `ticket` is which upload
        // the system is carrying (stratus-app#20): a row with one is in flight
        // and nobody may pick it up until whoever has it says what happened.
        connection.execSQL(
            """
            CREATE TABLE pending (
                path         TEXT    PRIMARY KEY,
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
                ticket       TEXT
            )
            """.trimIndent(),
        )

        // What a pass did, so a screen can tell "stopped" from "broken".
        // Nothing here is a source of truth about work outstanding -- that is
        // `pending`, and a second count would eventually disagree with it.
        //
        // One row, which the primary key is what enforces: a second would be a
        // second opinion about the one pass there is.
        connection.execSQL(
            """
            CREATE TABLE journal (
                id           INTEGER PRIMARY KEY CHECK (id = 0),
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

        // How far into the camera roll a pass has to look. A bound and not an
        // authority -- see BackupMark -- which is why it may sit in a file a
        // migration is allowed to throw away. One row, like the journal.
        connection.execSQL(
            """
            CREATE TABLE marks (
                id            INTEGER PRIMARY KEY CHECK (id = 0),
                added_through INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )

        connection.execSQL("PRAGMA user_version = $SCHEMA")
    }

    /**
     * Drops everything, for when somebody signs out.
     *
     * Signing out is the only way to a different server (stratus-app#131), and
     * a different server has settled nothing -- so carrying this across would
     * be a camera roll that never gets backed up again.
     */
    suspend fun clear() {
        ready()
        withContext(io) {
            for (table in listOf("settled", "pending", "journal", "marks")) {
                connection.prepare("DELETE FROM $table").use { statement -> statement.step() }
            }
        }
    }

    private fun version(): Int =
        connection.prepare("PRAGMA user_version").use { statement ->
            if (statement.step()) statement.getLong(0).toInt() else 0
        }

    private companion object {
        const val SCHEMA = 7
    }
}
