package dev.stratus.core.backup

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.stratus.core.dav.DavClient
import dev.stratus.core.server.Server
import dev.stratus.core.server.ServerStore
import dev.stratus.core.net.Credentials
import dev.stratus.core.store.SecureStore
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
import kotlin.test.assertTrue

private class MemoryStore : SecureStore {
    private val kept = mutableMapOf<String, String>()
    override suspend fun read(key: String) = kept[key]
    override suspend fun write(key: String, value: String) { kept[key] = value }
    override suspend fun delete(key: String) { kept.remove(key) }
}

private class OnePhoto : AssetSource {
    override suspend fun access() = MediaAccess.Full
    override suspend fun sources() = listOf(MediaSource("camera", "Camera", 1))
    override suspend fun assets(from: Set<String>, addedAfterEpochMs: Long) =
        listOf(Asset("l-1", 1_600_000_000_000, originalName = "IMG_1.jpg", sizeBytes = 3))
    override suspend fun open(localId: String, part: AssetPart, from: Long): RawSource =
        Buffer().apply { write(byteArrayOf(1, 2, 3)) }
}

class BackupTest {

    private val store = MemoryStore()
    private val instances = ServerStore(store)
    private val database = BackupDatabase(BundledSQLiteDriver().open(":memory:"))
    private val seen = mutableListOf<Pair<String, String>>()

    /** Which hosts speak tus, changeable between passes. */
    private val speaksTus = mutableSetOf<String>()

    /** Hosts that answer everything with an error, for the one that is down. */
    private val broken = mutableSetOf<String>()

    private val connections = Connections { instance ->
        val engine = MockEngine { request ->
            val host = request.url.host
            val method = request.method.value
            seen += host to method
            when {
                host in broken -> respond("", HttpStatusCode.InternalServerError)
                method == "OPTIONS" && host in speaksTus ->
                    respond("", HttpStatusCode.NoContent, headersOf("Tus-Resumable", "1.0.0"))

                method == "OPTIONS" -> respond("", HttpStatusCode.MethodNotAllowed)
                // The cold-start walk, against a server with nothing on it yet
                // (stratus-app#124): a month that is not there is not an error.
                method == "PROPFIND" -> respond("", HttpStatusCode.NotFound)
                method == "POST" ->
                    respond("", HttpStatusCode.Created, headersOf("Location", "/tus/abc"))

                method == "PATCH" ->
                    respond("", HttpStatusCode.NoContent, headersOf("Upload-Offset", "3"))

                else -> respond("", HttpStatusCode.Created, headersOf("ETag", "\"e\""))
            }
        }
        val http = HttpClient(engine)
        Connection(http, DavClient(http, instance.baseUrl))
    }

    private fun methodsAt(host: String) = seen.filter { it.first == host }.map { it.second }

    private val backup = Backup(instances, database, OnePhoto(), connections)

    private suspend fun signedIn(host: String, sources: Set<String> = setOf("camera")): Server {
        val instance = Server("https://$host/dav/", "edu", sources = sources)
        instances.put(instance, Credentials("edu", "secret"))
        return instance
    }

    @Test
    fun takesTusFromAServerThatOffersIt() = runTest {
        speaksTus += "tusser"
        signedIn("tusser")

        backup.pass()

        assertTrue("PATCH" in methodsAt("tusser"), "did not take the tus on offer: ${methodsAt("tusser")}")
        assertTrue("PUT" !in methodsAt("tusser"))
    }

    @Test
    fun takesPutFromAServerThatDoesNot() = runTest {
        signedIn("plain")

        backup.pass()

        assertTrue("PUT" in methodsAt("plain"), "was ${methodsAt("plain")}")
        assertTrue("PATCH" !in methodsAt("plain"), "resumed against a server that never offered it")
    }

    @Test
    fun asksAgainOnEveryPassRatherThanRememberingTheAnswer() = runTest {
        // A server that gains tus tomorrow should be used tomorrow, and there is
        // no cache to invalidate because nothing is cached.
        signedIn("late")

        backup.pass()
        backup.pass()

        assertEquals(2, methodsAt("late").count { it == "OPTIONS" }, "the first answer was remembered")
    }

    @Test
    fun aQueueIsBuiltFromTheServersOwnBackupRoot() = runTest {
        val server = signedIn("one")
        instances.update(server.copy(backupRoot = "/Pictures"))

        backup.prepare()!!.queue.enqueue(OnePhoto().assets(emptySet(), 0))

        val queued = database.pending().all().single().path
        assertTrue(queued.startsWith("/Pictures/"), "was $queued")
    }

    @Test
    fun aServerWithNoSourcesChosenGetsNoPass() = runTest {
        // Sources are the switch (stratus-app#131): choosing none is choosing
        // no backup, and a pass that ran anyway would be the app deciding for
        // somebody who had said not to.
        signedIn("notwanted", sources = emptySet())

        backup.pass()

        assertEquals(emptyList(), methodsAt("notwanted"))
    }

    @Test
    fun signingOutLeavesNothingSettledSoTheNextServerGetsEverything() = runTest {
        // The reason the record is dropped rather than carried (stratus-app#131):
        // a different server has settled nothing, and `settled` only ever grows
        // -- so keeping it would be a camera roll that never gets backed up.
        signedIn("first")
        backup.pass()
        assertEquals(1L, database.cache().size())

        // What AppContainer.signOut does, in the order it does it.
        instances.clear()
        database.clear()
        assertEquals(0L, database.cache().size())

        signedIn("second")
        backup.pass()

        assertTrue("PUT" in methodsAt("second"), "was ${methodsAt("second")}")
    }

    @Test
    fun theDatabaseIsReadyWithoutAnybodyRememberingToMigrateIt() = runTest {
        // Every accessor migrates, so the first thing that touches a fresh file
        // works -- including signing out of a server that never backed anything up.
        signedIn("fine")
        assertEquals(emptyList(), backup.failures())
        database.clear()
    }
}
