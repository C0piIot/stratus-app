package dev.stratus.core.backup

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.stratus.core.dav.DavClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupIndexTest {

    private val layout = RemoteLayout()

    private fun asset(
        at: Long = utcMillis(2026, 9, 17, 14, 30, 22),
        name: String = "IMG_0001.HEIC",
        size: Long = 4_012_345,
        motion: MotionPart? = null,
    ) = Asset("local", at, originalName = name, sizeBytes = size, motion = motion)

    /** Answers a PROPFIND for whatever the server is pretending to hold. */
    private fun serverHolding(vararg paths: String) = MockEngine { request ->
        val directory = request.url.encodedPath.removePrefix("/dav").trimEnd('/')
        val here = paths.filter { it.substringBeforeLast('/') == directory }
        if (here.isEmpty()) {
            respond("", HttpStatusCode.NotFound)
        } else {
            respond(
                buildString {
                    append("""<multistatus xmlns="DAV:">""")
                    for (path in here) {
                        append("<response><href>/dav$path</href><propstat><prop>")
                        append("<resourcetype/><getcontentlength>10</getcontentlength>")
                        append("<getetag>&#34;etag-of-" + path.substringAfterLast('/') + "&#34;</getetag>")
                        append("</prop><status>HTTP/1.1 200 OK</status></propstat></response>")
                    }
                    append("</multistatus>")
                },
                HttpStatusCode.MultiStatus,
            )
        }
    }

    private suspend fun indexOver(engine: MockEngine): Pair<BackupIndex, BackupCache> {
        val database = BackupDatabase(BundledSQLiteDriver().open(":memory:")).also { it.migrate() }
        val cache = database.cacheFor("instance-a")
        return BackupIndex(layout, cache, DavClient(HttpClient(engine), "http://host/dav/")) to cache
    }

    @Test
    fun rebuildsWhatItLostByAsking() = runTest {
        // The property the whole design exists for: no local state at all, and
        // the answer to "have I already uploaded this" comes back anyway.
        val photo = asset()
        val (index, _) = indexOver(serverHolding(layout.pathFor(photo)))

        assertEquals(1, index.walk(listOf(photo)))
        assertEquals(emptyList(), index.missing(listOf(photo)))
    }

    @Test
    fun aWalkAddsToWhatIsSettledAndTakesNothingAway() = runTest {
        // A photograph deleted on the server stays settled, or the next pass
        // uploads it again and the deletion never sticks (stratus-app#124).
        val deleted = asset(name = "IMG_DELETED.HEIC")
        val kept = asset(name = "IMG_KEPT.HEIC")
        val (index, cache) = indexOver(serverHolding(layout.pathFor(kept)))
        cache.record(layout.pathFor(deleted))

        index.walk(listOf(deleted, kept))

        assertTrue(cache.has(layout.pathFor(deleted)))
        assertEquals(emptyList(), index.missing(listOf(deleted, kept)))
    }

    @Test
    fun onlyWalksWhenThisPhoneKnowsNothing() = runTest {
        val photo = asset()
        val engine = serverHolding(layout.pathFor(photo))
        val (index, cache) = indexOver(engine)

        assertEquals(1, index.warmUp(listOf(photo)))
        val asked = engine.requestHistory.size

        // Settled now, so there is nothing to ask about: a pass must not walk
        // the server every time it runs.
        assertNull(index.warmUp(listOf(photo)))
        assertEquals(asked, engine.requestHistory.size)
        assertTrue(cache.has(layout.pathFor(photo)))
    }

    @Test
    fun asksOncePerMonthAndNotOncePerPhotograph() = runTest {
        val september = List(20) { asset(at = utcMillis(2026, 9, it + 1, 0, 0, 0), name = "IMG_$it.HEIC") }
        val august = List(20) { asset(at = utcMillis(2026, 8, it + 1, 0, 0, 0), name = "IMG_$it.HEIC") }
        val engine = serverHolding()
        val (index, _) = indexOver(engine)

        index.walk(september + august)
        assertEquals(2, engine.requestHistory.size)
    }

    @Test
    fun treatsAMonthWithNothingInItAsAMonthWithNothingInIt() = runTest {
        // A folder that does not exist is not a failure; it is a month nobody has
        // uploaded anything for yet.
        val photo = asset()
        val (index, _) = indexOver(serverHolding())
        assertEquals(0, index.walk(listOf(photo)))
        assertEquals(listOf(photo), index.missing(listOf(photo)))
    }

    @Test
    fun countsALivePhotoAsMissingUntilBothHalvesAreThere() = runTest {
        // Half a Live Photo is worse than neither.
        val live = asset(motion = MotionPart("IMG_0001.MOV", 1_200_000))
        val (index, _) = indexOver(serverHolding(layout.pathFor(live)))

        index.walk(listOf(live))
        assertEquals(listOf(live), index.missing(listOf(live)))
    }

    @Test
    fun countsALivePhotoAsDoneWhenBothAre() = runTest {
        val live = asset(motion = MotionPart("IMG_0001.MOV", 1_200_000))
        val (index, _) = indexOver(serverHolding(layout.pathFor(live), layout.motionPathFor(live)!!))

        index.walk(listOf(live))
        assertEquals(emptyList(), index.missing(listOf(live)))
    }

}
