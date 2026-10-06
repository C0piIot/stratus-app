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

    private suspend fun cache(instance: String = "instance-a"): BackupCache {
        database.migrate()
        return database.cacheFor(instance)
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
    fun keepsTwoInstancesOutOfEachOtherIsRows() = runTest {
        // The collision that made this necessary: both servers hold the same
        // photographs at the same paths, and without the instance column one
        // would report the other's work as already done.
        val one = cache("instance-a")
        val other = cache("instance-b")

        one.record("/Photos/2026/09/a.heic")
        assertTrue(one.has("/Photos/2026/09/a.heic"))
        assertFalse(other.has("/Photos/2026/09/a.heic"))
    }

    @Test
    fun walkingOneInstanceLeavesTheOtherAlone() = runTest {
        val one = cache("instance-a")
        val other = cache("instance-b")
        one.record("/kept")
        other.record("/also-kept")

        one.add(listOf("/found"))

        assertEquals(setOf("/kept", "/found"), one.paths())
        assertEquals(setOf("/also-kept"), other.paths())
    }

    @Test
    fun carriesTheOldTableOverRatherThanMakingThePhoneUploadItAllAgain() = runTest {
        // The previous shape held the ETag and the size beside the path and
        // nothing read either. Dropping the columns must not drop what the
        // phone had already settled, or the migration itself costs somebody
        // their camera roll a second time.
        val connection = BundledSQLiteDriver().open(":memory:")
        connection.execSQL(
            "CREATE TABLE uploaded (instance TEXT NOT NULL, path TEXT NOT NULL, etag TEXT, size INTEGER," +
                " PRIMARY KEY (instance, path))",
        )
        connection.execSQL("INSERT INTO uploaded (instance, path) VALUES ('a', '/from-the-old-world')")
        connection.execSQL("PRAGMA user_version = 4")

        val database = BackupDatabase(connection)
        database.migrate()

        assertEquals(setOf("/from-the-old-world"), database.cacheFor("a").paths())
        assertEquals(emptySet(), database.cacheFor("b").paths())
    }

    @Test
    fun throwsAwayATableOfTheOlderShapeRatherThanLivingWithIt() = runTest {
        // CREATE TABLE IF NOT EXISTS would have left this file silently on the
        // shape without an instance, where two servers overwrite each other.
        val connection = BundledSQLiteDriver().open(":memory:")
        connection.execSQL("CREATE TABLE uploaded (path TEXT PRIMARY KEY, etag TEXT, size INTEGER)")
        connection.execSQL("INSERT INTO uploaded (path) VALUES ('/from-the-old-world')")

        val database = BackupDatabase(connection)
        database.migrate()

        assertEquals(emptySet(), database.cacheFor("anyone").paths())
        // And it is the new shape, so two instances now fit.
        database.cacheFor("a").record("/x")
        database.cacheFor("b").record("/x")
        assertEquals(1L, database.cacheFor("a").size())
    }
}
