package dev.stratus.core.backup

import androidx.sqlite.SQLiteConnection
import dev.stratus.core.sql.use
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * How far into the library a pass needs to look.
 *
 * **A bound and not an authority** (stratus-app#124). What has actually been
 * sent is [BackupCache]'s answer; this only says where re-reading the camera
 * roll can start, so that a phone with forty thousand photographs on it does
 * not enumerate all of them every hour to find the three that are new. Getting
 * it wrong costs a slow pass and never a photograph, which is the property that
 * lets it be fed from whatever each platform happens to know.
 *
 * It moves to the oldest thing still owed rather than to the newest thing done,
 * so a photograph that failed holds the bound behind it until somebody deals
 * with it. And it never moves at all while any photograph came back without an
 * added date, which is every iOS below 26.
 */
class BackupMark(
    private val connection: SQLiteConnection,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) {
    /** Milliseconds, or 0 for "look at everything", which is where it starts. */
    suspend fun read(): Long = withContext(io) {
        connection.prepare("SELECT added_through FROM marks WHERE id = 0").use { statement ->
            if (statement.step()) statement.getLong(0) else 0L
        }
    }

    /**
     * Moves the bound forward, never back.
     *
     * Backwards would be free -- it only costs a slower pass -- but it would
     * also hide a bug behind an optimisation that quietly stopped optimising.
     */
    suspend fun advanceTo(epochMs: Long) = withContext(io) {
        connection.prepare(
            "INSERT INTO marks (id, added_through) VALUES (0, ?) " +
                "ON CONFLICT (id) DO UPDATE SET added_through = MAX(added_through, excluded.added_through)",
        ).use { statement ->
            statement.bindLong(1, epochMs)
            statement.step()
        }
        Unit
    }
}
