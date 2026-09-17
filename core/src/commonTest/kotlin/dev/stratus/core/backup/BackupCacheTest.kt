package dev.stratus.core.backup

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
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

    private suspend fun cache(): BackupCache =
        BackupCache(BundledSQLiteDriver().open(":memory:")).also { it.migrate() }

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
}
