package dev.stratus.core.backup

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Against real SQLite, on every target -- the bundled driver ships the engine,
 * so what the fast loop runs is what the phone runs.
 */
class BackupCacheTest {

    private val database = BackupDatabase(BundledSQLiteDriver().open(":memory:"))

    private suspend fun cache(): BackupCache {
        database.migrate()
        return database.cache()
    }

    @Test
    fun remembersWhatHasBeenSettled() = runTest {
        val cache = cache()
        assertFalse(cache.has("/Photos/2026/09/a.heic"))

        cache.record("/Photos/2026/09/a.heic")
        assertTrue(cache.has("/Photos/2026/09/a.heic"))
        assertEquals(setOf("/Photos/2026/09/a.heic"), cache.paths())
    }

    @Test
    fun writingTheSamePathTwiceLeavesOneRow() = runTest {
        val cache = cache()
        cache.record("/a")
        cache.record("/a")
        assertEquals(1L, cache.size())
    }

    @Test
    fun whatTheServerHoldsIsAddedToWhatIsAlreadySettled() = runTest {
        // The rule this table exists for (stratus-app#124): a walk says what is
        // on the server now, and what is on the server now is not what has been
        // dealt with. Replacing would turn every deliberate deletion back into
        // a missing file.
        val cache = cache()
        cache.record("/deleted-on-the-server")
        cache.add(listOf("/kept", "/also"))

        assertTrue(cache.has("/deleted-on-the-server"))
        assertEquals(setOf("/deleted-on-the-server", "/kept", "/also"), cache.paths())
    }

    @Test
    fun aPassOverTheSameServerTwiceAddsNothing() = runTest {
        val cache = cache()
        cache.add(listOf("/a", "/b"))
        cache.add(listOf("/a", "/b"))
        assertEquals(2L, cache.size())
    }

    @Test
    fun nothingSettledIsHowAColdStartIsRecognised() = runTest {
        val cache = cache()
        assertTrue(cache.isEmpty())
        cache.record("/a")
        assertFalse(cache.isEmpty())
    }

    @Test
    fun handsBackEveryPathAtOnce() = runTest {
        // One query and a set: asking per photograph is how a cheap check
        // becomes the slow part of a backup.
        val cache = cache()
        repeat(500) { cache.record("/Photos/2026/09/$it.heic") }
        assertEquals(500, cache.paths().size)
    }

    @Test
    fun walkingAddsToWhatIsAlreadySettled() = runTest {
        val cache = cache()
        cache.record("/kept")

        cache.add(listOf("/found"))

        assertEquals(setOf("/kept", "/found"), cache.paths())
    }

    @Test
    fun throwsAwayAFileOfAnOlderShapeRatherThanMigratingIt() = runTest {
        // One schema, rewritten rather than migrated (stratus-app#131): the
        // six stepped versions this replaces carried an `instance` column
        // through every table, and there is one server now. Nothing is
        // deployed anywhere but a test phone, and this file is a cache -- what
        // is in it is recovered by walking the server.
        val connection = BundledSQLiteDriver().open(":memory:")
        connection.execSQL(
            "CREATE TABLE settled (instance TEXT NOT NULL, path TEXT NOT NULL, PRIMARY KEY (instance, path))",
        )
        connection.execSQL("INSERT INTO settled (instance, path) VALUES ('a', '/from-the-old-world')")
        connection.execSQL("PRAGMA user_version = 6")

        val database = BackupDatabase(connection)
        database.migrate()

        assertEquals(emptySet(), database.cache().paths())
        // And it is the new shape, with the path as the whole key.
        database.cache().record("/x")
        database.cache().record("/x")
        assertEquals(1L, database.cache().size())
    }
}
