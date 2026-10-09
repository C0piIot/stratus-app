package dev.stratus.core.backup.conformance

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.stratus.core.backup.Asset
import dev.stratus.core.backup.BackupDatabase
import dev.stratus.core.backup.BackupIndex
import dev.stratus.core.backup.utcMillis
import dev.stratus.core.backup.MotionPart
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
import kotlin.test.assertTrue

/**
 * The claim the whole design rests on, against a real server: a phone that has
 * lost everything can find out what it already uploaded by asking.
 */
class RebuildConformanceTest {

    private val url: String = requireNotNull(System.getenv("STRATUS_TEST_URL")) {
        "STRATUS_TEST_URL is unset. Run this with `make conformance`, not directly."
    }
    private val dav = DavClient(
        stratusHttpClient(
            CIO.create(),
            Credentials(
                System.getenv("STRATUS_TEST_USER") ?: "conformance",
                System.getenv("STRATUS_TEST_PASS") ?: "conformance-secret",
            ),
        ),
        url,
    )

    private val layout = RemoteLayout("rebuild-${Random.nextLong().toULong().toString(16)}")

    private suspend fun emptyIndex() = BackupIndex(
        layout,
        BackupDatabase(BundledSQLiteDriver().open(":memory:")).also { it.migrate() }.cache(),
        dav,
    )

    private suspend fun putAt(path: String) {
        // MKCOL makes one level at a time, which is also what the rebuild's
        // per-month listing assumes.
        val parts = path.trim('/').split('/').dropLast(1)
        var at = ""
        for (part in parts) {
            at += "/$part"
            try {
                dav.makeCollection(at)
            } catch (_: DavError.AlreadyExists) {
                // Already there, which is the ordinary case on the second file.
            }
        }
        dav.put(path, "pretend this is a photograph".encodeToByteArray(), "image/heic")
    }

    private fun asset(day: Int, month: Int = 9, name: String = "IMG_$day.HEIC", motion: MotionPart? = null) =
        Asset("local-$day-$month", utcMillis(2026, month, day, 12, 0, day), originalName = name, sizeBytes = 28, motion = motion)

    @Test
    fun findsOutWhatItAlreadyUploadedWithNoLocalStateAtAll() = runTest {
        val assets = listOf(asset(1), asset(2), asset(3, month = 8))
        for (a in assets) putAt(layout.pathFor(a))

        val index = emptyIndex()
        assertEquals(3, index.walk(assets))
        assertEquals(emptyList(), index.missing(assets))
    }

    @Test
    fun reportsWhatIsGenuinelyNotThere() = runTest {
        val uploaded = asset(10)
        val never = asset(11)
        putAt(layout.pathFor(uploaded))

        val index = emptyIndex()
        index.walk(listOf(uploaded, never))
        assertEquals(listOf(never), index.missing(listOf(uploaded, never)))
    }

    @Test
    fun aPhotographDeletedOnTheServerStaysSettled() = runTest {
        // The rule stratus-app#124 is about, against a real server: somebody
        // deletes a file in the web UI, and the next pass must not put it back.
        val photo = asset(20)
        putAt(layout.pathFor(photo))

        val index = emptyIndex()
        index.walk(listOf(photo))
        assertEquals(emptyList(), index.missing(listOf(photo)))

        dav.delete(layout.pathFor(photo))
        index.walk(listOf(photo))

        assertEquals(emptyList(), index.missing(listOf(photo)), "a deletion was undone by the next walk")
    }

    @Test
    fun countsALivePhotoAsMissingUntilItsMovieIsThereToo() = runTest {
        val live = asset(25, motion = MotionPart("IMG_25.MOV", 28))
        putAt(layout.pathFor(live))

        val index = emptyIndex()
        index.walk(listOf(live))
        assertEquals(listOf(live), index.missing(listOf(live)))

        putAt(layout.motionPathFor(live)!!)
        index.walk(listOf(live))
        assertEquals(emptyList(), index.missing(listOf(live)))
    }
}
