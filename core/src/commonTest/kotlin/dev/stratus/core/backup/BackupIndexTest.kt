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
import kotlin.test.assertTrue

class BackupIndexTest {

    private val layout = RemoteLayout()

    private fun asset(
        at: CaptureTime = CaptureTime(2026, 9, 17, 14, 30, 22),
        name: String = "IMG_0001.HEIC",
        size: Long = 4_012_345,
        motion: MotionPart? = null,
    ) = Asset("local", at, name, size, motion)

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
        val cache = BackupCache(BundledSQLiteDriver().open(":memory:")).also { it.migrate() }
        return BackupIndex(layout, cache, DavClient(HttpClient(engine), "http://host/dav/")) to cache
    }

    @Test
    fun rebuildsWhatItLostByAsking() = runTest {
        // The property the whole design exists for: no local state at all, and
        // the answer to "have I already uploaded this" comes back anyway.
        val photo = asset()
        val (index, cache) = indexOver(serverHolding(layout.pathFor(photo)))

        assertEquals(1, index.rebuild(listOf(photo)))
        assertEquals(emptyList(), index.missing(listOf(photo)))
        // And the ETag comes back with it, so a later check has something real
        // to compare against rather than only a name.
        assertEquals(
            "etag-of-" + layout.pathFor(photo).substringAfterLast('/'),
            cache.entry(layout.pathFor(photo))?.etag,
        )
    }

    @Test
    fun asksOncePerMonthAndNotOncePerPhotograph() = runTest {
        val september = List(20) { asset(at = CaptureTime(2026, 9, it + 1, 0, 0, 0), name = "IMG_$it.HEIC") }
        val august = List(20) { asset(at = CaptureTime(2026, 8, it + 1, 0, 0, 0), name = "IMG_$it.HEIC") }
        val engine = serverHolding()
        val (index, _) = indexOver(engine)

        index.rebuild(september + august)
        assertEquals(2, engine.requestHistory.size)
    }

    @Test
    fun treatsAMonthWithNothingInItAsAMonthWithNothingInIt() = runTest {
        // A folder that does not exist is not a failure; it is a month nobody has
        // uploaded anything for yet.
        val photo = asset()
        val (index, _) = indexOver(serverHolding())
        assertEquals(0, index.rebuild(listOf(photo)))
        assertEquals(listOf(photo), index.missing(listOf(photo)))
    }

    @Test
    fun countsALivePhotoAsMissingUntilBothHalvesAreThere() = runTest {
        // Half a Live Photo is worse than neither.
        val live = asset(motion = MotionPart("IMG_0001.MOV", 1_200_000))
        val (index, _) = indexOver(serverHolding(layout.pathFor(live)))

        index.rebuild(listOf(live))
        assertEquals(listOf(live), index.missing(listOf(live)))
    }

    @Test
    fun countsALivePhotoAsDoneWhenBothAre() = runTest {
        val live = asset(motion = MotionPart("IMG_0001.MOV", 1_200_000))
        val (index, _) = indexOver(serverHolding(layout.pathFor(live), layout.motionPathFor(live)!!))

        index.rebuild(listOf(live))
        assertEquals(emptyList(), index.missing(listOf(live)))
    }

    @Test
    fun saysCannotVerifyRatherThanYesWhenTheServerOffersNoEtag() = runTest {
        val photo = asset()
        val (index, cache) = indexOver(serverHolding())
        cache.record(RemoteEntry(layout.pathFor(photo), null, null))
        assertEquals(Verification.PresentButUnverifiable, index.verification(photo))

        cache.record(RemoteEntry(layout.pathFor(photo), "sha", photo.sizeBytes))
        assertEquals(Verification.Present("sha"), index.verification(photo))

        cache.record(RemoteEntry(layout.pathFor(photo), "sha", 1))
        assertEquals(Verification.Differs, index.verification(photo))
    }
}
