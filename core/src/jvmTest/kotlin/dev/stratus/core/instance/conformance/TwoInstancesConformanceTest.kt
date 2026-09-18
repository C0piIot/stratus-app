package dev.stratus.core.instance.conformance

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.stratus.core.backup.Asset
import dev.stratus.core.backup.BackupDatabase
import dev.stratus.core.backup.BackupIndex
import dev.stratus.core.backup.CaptureTime
import dev.stratus.core.backup.RemoteLayout
import dev.stratus.core.dav.DavClient
import dev.stratus.core.dav.DavError
import dev.stratus.core.net.Credentials
import dev.stratus.core.net.stratusHttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Two real servers, one database.
 *
 * The claim worth proving with containers rather than doubles: a photograph
 * uploaded to one instance is not reported as done by the other, even though
 * both give it exactly the same path. Nothing about that is visible with a
 * single server, which is how the collision went unnoticed until #38.
 */
class TwoInstancesConformanceTest {

    private val credentials = Credentials(
        System.getenv("STRATUS_TEST_USER") ?: "conformance",
        System.getenv("STRATUS_TEST_PASS") ?: "conformance-secret",
    )

    private fun davAt(url: String) = DavClient(stratusHttpClient(CIO.create(), credentials), url)

    private val one = davAt(
        requireNotNull(System.getenv("STRATUS_TEST_URL")) { "run this with `make conformance`" },
    )
    private val two = davAt(
        requireNotNull(System.getenv("STRATUS_TEST_URL_2")) { "run this with `make conformance`" },
    )

    private val layout = RemoteLayout("two-${Random.nextLong().toULong().toString(16)}")
    private val database = BackupDatabase(BundledSQLiteDriver().open(":memory:"))

    private val asset = Asset("local", CaptureTime(2026, 9, 18, 10, 0, 0), "IMG_1.HEIC", 28)

    private suspend fun indexFor(id: String, dav: DavClient): BackupIndex {
        database.migrate()
        return BackupIndex(layout, database.cacheFor(id), dav)
    }

    private suspend fun putAt(dav: DavClient, path: String) {
        var at = ""
        for (part in path.trim('/').split('/').dropLast(1)) {
            at += "/$part"
            try {
                dav.makeCollection(at)
            } catch (_: DavError.Conflict) {
                // Already there.
            }
        }
        dav.put(path, "pretend this is a photograph".encodeToByteArray(), "image/heic")
    }

    @Test
    fun oneInstanceDoesNotAnswerForTheOther() = runTest {
        putAt(one, layout.pathFor(asset))

        val first = indexFor("instance-one", one)
        val second = indexFor("instance-two", two)
        assertEquals(1, first.rebuild(listOf(asset)))
        assertEquals(0, second.rebuild(listOf(asset)))

        assertEquals(emptyList(), first.missing(listOf(asset)))
        assertEquals(listOf(asset), second.missing(listOf(asset)))
    }

    @Test
    fun rebuildingOneLeavesWhatTheOtherKnew() = runTest {
        putAt(one, layout.pathFor(asset))
        putAt(two, layout.pathFor(asset))

        val first = indexFor("instance-one", one)
        val second = indexFor("instance-two", two)
        first.rebuild(listOf(asset))
        second.rebuild(listOf(asset))

        // A rebuild replaces everything it knows, and must stop at its own rows.
        first.rebuild(listOf(asset))
        assertEquals(emptyList(), second.missing(listOf(asset)))
    }
}
