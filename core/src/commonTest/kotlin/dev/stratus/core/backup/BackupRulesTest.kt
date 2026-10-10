package dev.stratus.core.backup

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.stratus.core.dav.DavClient
import dev.stratus.core.server.Server
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The three rules a backup obeys, written down because they hold today by
 * construction and nothing was stopping the next feature from breaking them
 * quietly (stratus-app#124).
 *
 * 1. Deleting a photograph on the phone does not delete it on the server.
 * 2. Deleting a file on the server does not delete it on the phone.
 * 3. A file deleted on the server is not uploaded again.
 *
 * The first two are about what a pass *does not do*, which is the kind of
 * property that only a test states: there is no code to point at.
 */
class BackupRulesTest {

    private val database = BackupDatabase(BundledSQLiteDriver().open(":memory:"))
    private val instance = Server("https://host/dav/", "edu", sources = setOf("Camera"))
    private val layout = RemoteLayout(instance.backupRoot)

    private fun asset(day: Int) =
        Asset("local-$day", utcMillis(2026, 9, day), utcMillis(2026, 9, day), "IMG_$day.HEIC", 3)

    /** A camera roll that can lose a photograph, as somebody deleting one does. */
    private class Roll(var photos: List<Asset>) : AssetSource {
        var opened = 0
        override suspend fun access() = MediaAccess.Full
        override suspend fun sources() = listOf(MediaSource("Camera", "Camera", photos.size))
        override suspend fun assets(from: Set<String>, addedAfterEpochMs: Long) =
            photos.filter { addedAfterEpochMs <= 0 || it.addedAtEpochMs <= 0 || it.addedAtEpochMs > addedAfterEpochMs }

        override suspend fun open(localId: String, part: AssetPart, from: Long): RawSource {
            opened++
            return Buffer().apply { write(byteArrayOf(1, 2, 3)) }
        }
    }

    /**
     * A server that holds whatever is put on it, and answers a PROPFIND with
     * what is in that month.
     */
    private class Server {
        val held = mutableSetOf<String>()
        val methods = mutableListOf<Pair<String, String>>()

        val engine = MockEngine { request ->
            // The client is rooted at /dav/, and everything else here talks in
            // paths from the tree, so the prefix comes off once.
            val path = request.url.encodedPath.removePrefix("/dav")
            methods += request.method.value to path
            when (request.method.value) {
                "PROPFIND" -> {
                    val directory = path.trimEnd('/')
                    val here = held.filter { it.substringBeforeLast('/') == directory }
                    if (here.isEmpty()) {
                        respond("", HttpStatusCode.NotFound)
                    } else {
                        respond(multistatus(here), HttpStatusCode.MultiStatus)
                    }
                }
                "PUT" -> {
                    held += path
                    respond("", HttpStatusCode.Created, headersOf("ETag", "\"e\""))
                }
                else -> respond("", HttpStatusCode.Created)
            }
        }

        private fun multistatus(paths: List<String>) = buildString {
            append("""<multistatus xmlns="DAV:">""")
            for (path in paths) {
                append("<response><href>/dav$path</href><propstat><prop>")
                append("<resourcetype/><getcontentlength>3</getcontentlength>")
                append("<getetag>&#34;e&#34;</getetag>")
                append("</prop><status>HTTP/1.1 200 OK</status></propstat></response>")
            }
            append("</multistatus>")
        }

        fun methodsFor(path: String) = methods.filter { it.second == path }.map { it.first }
    }

    private suspend fun passOver(roll: Roll, server: Server): BackupRun {
        database.migrate()
        return BackupRun(
            source = roll,
            prepareFor = {
                val cache = database.cache()
                val dav = DavClient(HttpClient(server.engine), "https://host/dav/")
                ServerBackup(
                    queue = UploadQueue(
                        layout = layout,
                        pending = database.pending(),
                        cache = cache,
                        source = roll,
                        transport = PutTransport(dav),
                        directories = DavDirectoryMaker(dav),
                    ),
                    index = BackupIndex(layout, cache, dav),
                    mark = database.mark(),
                )
            },
            journalFor = { database.journal() },
        )
    }

    @Test
    fun deletingAPhotographOnThePhoneDoesNotDeleteItOnTheServer() = runTest {
        val kept = asset(1)
        val deletedLocally = asset(2)
        val server = Server()
        val roll = Roll(listOf(kept, deletedLocally))
        passOver(roll, server).once(instance)
        assertTrue(layout.pathFor(deletedLocally) in server.held)

        // Somebody deletes it from the camera roll, and a pass runs again.
        roll.photos = listOf(kept)
        passOver(roll, server).once(instance)

        assertFalse(server.methods.any { it.first == "DELETE" }, "a pass sent a DELETE")
        assertTrue(layout.pathFor(deletedLocally) in server.held, "the server lost a file the phone did")
    }

    @Test
    fun deletingAFileOnTheServerDoesNotDeleteItOnThePhone() = runTest {
        val photo = asset(1)
        val server = Server()
        val roll = Roll(listOf(photo))
        passOver(roll, server).once(instance)

        server.held.clear()
        passOver(roll, server).once(instance)

        // The library is untouched: a backup reads it and has no way to write
        // to it, which is a property worth a test because there is no code to
        // point at for it.
        assertEquals(listOf(photo), roll.photos)
    }

    @Test
    fun aFileDeletedOnTheServerIsNotUploadedAgain() = runTest {
        val photo = asset(1)
        val server = Server()
        val roll = Roll(listOf(photo))
        passOver(roll, server).once(instance)
        val path = layout.pathFor(photo)
        assertEquals(listOf("PUT"), server.methodsFor(path))

        // Deleted on the server, deliberately, by whoever owns it.
        server.held.clear()
        passOver(roll, server).once(instance)

        assertEquals(listOf("PUT"), server.methodsFor(path), "the deletion was undone by the next pass")
    }

    @Test
    fun aPhoneThatLostItsRecordAsksBeforeItSends() = runTest {
        // The other half of the same rule: a reinstall costs a listing per
        // month and not the camera roll, which is what the walk is for.
        val photo = asset(1)
        val server = Server()
        val roll = Roll(listOf(photo))
        passOver(roll, server).once(instance)
        assertEquals(1, roll.opened)

        database.clear()
        passOver(roll, server).once(instance)

        assertEquals(1, roll.opened, "it read the photograph again instead of asking the server")
    }
}
