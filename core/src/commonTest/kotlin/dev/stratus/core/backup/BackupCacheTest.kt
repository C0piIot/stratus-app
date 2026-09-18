package dev.stratus.core.backup

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun remembersWhatTheServerHas() = runTest {
        val cache = cache()
        assertFalse(cache.has("/Photos/2026/09/a.heic"))

        cache.record(RemoteEntry("/Photos/2026/09/a.heic", "abc123", 4096))
        assertTrue(cache.has("/Photos/2026/09/a.heic"))
        assertEquals(RemoteEntry("/Photos/2026/09/a.heic", "abc123", 4096), cache.entry("/Photos/2026/09/a.heic"))
    }

    @Test
    fun keepsAServerThatOffersNoEtagUsableAnyway() = runTest {
        val cache = cache()
        cache.record(RemoteEntry("/Photos/2026/09/a.heic", null, null))
        val entry = cache.entry("/Photos/2026/09/a.heic")!!
        assertNull(entry.etag)
        assertNull(entry.size)
    }

    @Test
    fun writingTheSamePathTwiceUpdatesRatherThanDuplicates() = runTest {
        val cache = cache()
        cache.record(RemoteEntry("/a", "first", 1))
        cache.record(RemoteEntry("/a", "second", 2))
        assertEquals(1L, cache.size())
        assertEquals("second", cache.entry("/a")?.etag)
    }

    @Test
    fun forgetsWhatWasRemoved() = runTest {
        val cache = cache()
        cache.record(RemoteEntry("/a", null, null))
        cache.forget("/a")
        assertFalse(cache.has("/a"))
    }

    @Test
    fun replacingLeavesOnlyWhatTheServerJustSaid() = runTest {
        val cache = cache()
        cache.record(RemoteEntry("/gone", null, null))
        cache.replaceAll(listOf(RemoteEntry("/kept", "e", 3), RemoteEntry("/also", null, null)))

        assertFalse(cache.has("/gone"))
        assertEquals(setOf("/kept", "/also"), cache.paths())
    }

    @Test
    fun handsBackEveryPathAtOnce() = runTest {
        // One query and a set: asking per photograph is how a cheap check
        // becomes the slow part of a backup.
        val cache = cache()
        repeat(500) { cache.record(RemoteEntry("/Photos/2026/09/$it.heic", null, null)) }
        assertEquals(500, cache.paths().size)
    }

    @Test
    fun keepsTwoInstancesOutOfEachOtherIsRows() = runTest {
        // The collision that made this necessary: both servers hold the same
        // photographs at the same paths, and without the instance column one
        // would report the other's work as already done.
        val one = cache("instance-a")
        val other = cache("instance-b")

        one.record(RemoteEntry("/Photos/2026/09/a.heic", "etag-one", 10))
        assertTrue(one.has("/Photos/2026/09/a.heic"))
        assertFalse(other.has("/Photos/2026/09/a.heic"))

        other.record(RemoteEntry("/Photos/2026/09/a.heic", "etag-other", 20))
        assertEquals("etag-one", one.entry("/Photos/2026/09/a.heic")?.etag)
        assertEquals("etag-other", other.entry("/Photos/2026/09/a.heic")?.etag)
    }

    @Test
    fun rebuildingOneInstanceLeavesTheOtherAlone() = runTest {
        val one = cache("instance-a")
        val other = cache("instance-b")
        one.record(RemoteEntry("/kept", null, null))
        other.record(RemoteEntry("/also-kept", null, null))

        one.replaceAll(listOf(RemoteEntry("/replaced", null, null)))

        assertEquals(setOf("/replaced"), one.paths())
        assertEquals(setOf("/also-kept"), other.paths())
    }

    @Test
    fun throwsAwayATableOfTheOlderShapeRatherThanLivingWithIt() = runTest {
        // A cache is rebuildable by asking the server, so a schema change costs a
        // rebuild instead of a data migration -- and CREATE TABLE IF NOT EXISTS
        // would have left this file silently on the shape without an instance.
        val connection = BundledSQLiteDriver().open(":memory:")
        connection.execSQL("CREATE TABLE uploaded (path TEXT PRIMARY KEY, etag TEXT, size INTEGER)")
        connection.execSQL("INSERT INTO uploaded (path) VALUES ('/from-the-old-world')")

        val database = BackupDatabase(connection)
        database.migrate()

        assertEquals(emptySet(), database.cacheFor("anyone").paths())
        // And it is the new shape, so two instances now fit.
        database.cacheFor("a").record(RemoteEntry("/x", null, null))
        database.cacheFor("b").record(RemoteEntry("/x", null, null))
        assertEquals(1L, database.cacheFor("a").size())
    }
}
