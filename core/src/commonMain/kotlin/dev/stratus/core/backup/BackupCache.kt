package dev.stratus.core.backup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import dev.stratus.core.sql.use
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Every path this instance has settled, and will not send again.
 *
 * **It only ever grows, and that is the point** (stratus-app#124). A path goes
 * in when an upload finishes and when the server is found to hold it, and
 * nothing takes one out -- so a file somebody deletes on the server stays
 * settled and is not uploaded again on the next pass. The alternative, a record
 * of what the server holds *now*, cannot tell "never sent" from "sent and
 * deleted", which is the one question this has to answer.
 *
 * It holds the path and nothing else. It used to carry the ETag and the size as
 * well, read by exactly one function that nothing called; what they cost was
 * half the table on a phone with a camera roll on it.
 *
 * **A cache and not the record.** Deleting this file must cost time and nothing
 * else: what is in it is recovered by asking the server, which is what keeps a
 * reinstall from re-uploading somebody's camera roll. What that cannot recover
 * is the deletions -- a file deleted on the server before the file was lost
 * comes back once -- and that is the accepted price of keeping all of this on
 * the phone.
 */
class BackupCache(
    private val connection: SQLiteConnection,
    private val instanceId: String,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) {

    suspend fun has(path: String): Boolean = withContext(io) {
        connection.prepare("SELECT 1 FROM settled WHERE instance = ? AND path = ?").use { statement ->
            statement.bindText(1, instanceId)
            statement.bindText(2, path)
            statement.step()
        }
    }

    /** One path, settled because an upload of it finished. */
    suspend fun record(path: String) = withContext(io) { recordIn(path) }

    /**
     * Everything the server was just found to hold, added to what is already
     * known.
     *
     * **Added and never substituted.** A path this already holds stays, whether
     * or not the walk found it: the walk says what is there now, and what is
     * there now is not what has been dealt with. Replacing would turn every
     * deliberate deletion on the server back into a missing file.
     *
     * One transaction, because a walk interrupted halfway would otherwise leave
     * a record claiming less than it knows, and the cost of that mistake is
     * uploading it all again.
     */
    suspend fun add(paths: Collection<String>) = withContext(io) {
        connection.execSQL("BEGIN")
        try {
            for (path in paths) recordIn(path)
            connection.execSQL("COMMIT")
        } catch (e: Throwable) {
            connection.execSQL("ROLLBACK")
            throw e
        }
    }

    /**
     * Every settled path.
     *
     * One query and a set, rather than one query per photograph: asking forty
     * thousand times is how a check meant to be cheap becomes the slow part.
     */
    suspend fun paths(): Set<String> = withContext(io) {
        val found = mutableSetOf<String>()
        connection.prepare("SELECT path FROM settled WHERE instance = ?").use { statement ->
            statement.bindText(1, instanceId)
            while (statement.step()) found += statement.getText(0)
        }
        found
    }

    /**
     * Whether this instance has settled anything at all.
     *
     * What a cold start is: nothing settled means either a first run or a phone
     * that lost the file, and both want the server walked before anything is
     * sent (see [BackupIndex]).
     */
    suspend fun isEmpty(): Boolean = size() == 0L

    suspend fun size(): Long = withContext(io) {
        connection.prepare("SELECT COUNT(*) FROM settled WHERE instance = ?").use { statement ->
            statement.bindText(1, instanceId)
            if (statement.step()) statement.getLong(0) else 0L
        }
    }

    private fun recordIn(path: String) {
        connection.prepare("INSERT OR IGNORE INTO settled (instance, path) VALUES (?, ?)")
            .use { statement ->
                statement.bindText(1, instanceId)
                statement.bindText(2, path)
                statement.step()
            }
    }
}
