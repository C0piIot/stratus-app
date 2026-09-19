package dev.stratus.core.backup

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.stratus.core.dav.DavClient
import dev.stratus.core.instance.Instance
import dev.stratus.core.instance.InstanceStore
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
        listOf(Asset("l-1", 1_600_000_000_000, "IMG_1.jpg", 3))
    override suspend fun open(localId: String, part: AssetPart, from: Long): RawSource =
        Buffer().apply { write(byteArrayOf(1, 2, 3)) }
}

class BackupTest {

    private val store = MemoryStore()
    private val instances = InstanceStore(store)
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

    private suspend fun signedIn(id: String, host: String, enabled: Boolean = true): Instance {
        val instance = Instance(id, "https://$host/dav/", "edu", backupEnabled = enabled)
        instances.put(instance, Credentials("edu", "secret"))
        return instance
    }

    @Test
    fun takesTusFromAServerThatOffersItAndPutFromOneThatDoesNot() = runTest {
        speaksTus += "tusser"
        signedIn("i-1", "tusser")
        signedIn("i-2", "plain")

        backup.pass()

        assertTrue("PATCH" in methodsAt("tusser"), "did not take the tus on offer: ${methodsAt("tusser")}")
        assertTrue("PUT" !in methodsAt("tusser"))
        assertTrue("PUT" in methodsAt("plain"), "was ${methodsAt("plain")}")
        assertTrue("PATCH" !in methodsAt("plain"), "resumed against a server that never offered it")
    }

    @Test
    fun asksAgainOnEveryPassRatherThanRememberingTheAnswer() = runTest {
        // A server that gains tus tomorrow should be used tomorrow, and there is
        // no cache to invalidate because nothing is cached.
        signedIn("i-1", "late")

        backup.pass()
        backup.pass()

        assertEquals(2, methodsAt("late").count { it == "OPTIONS" }, "the first answer was remembered")
    }

    @Test
    fun aQueueIsBuiltFromItsOwnInstanceAndNotFromWhicheverIsCurrent() = runTest {
        val first = signedIn("i-1", "one")
        signedIn("i-2", "two")
        instances.update(first.copy(backupRoot = "/Pictures"))

        backup.queueFor("i-1")!!.enqueue(OnePhoto().assets(emptySet(), 0))
        val queued = database.pendingFor("i-1").all().single().path
        assertTrue(queued.startsWith("/Pictures/"), "was $queued")
        assertEquals(emptyList(), database.pendingFor("i-2").all())
    }

    @Test
    fun onlyTheInstancesThatAskedForOneGetAPass() = runTest {
        signedIn("i-1", "wanted")
        signedIn("i-2", "notwanted", enabled = false)

        backup.pass()

        assertTrue(seen.any { it.first == "wanted" })
        assertTrue(seen.none { it.first == "notwanted" }, "backed up an instance that had it turned off")
    }

    @Test
    fun oneInstanceBeingDownDoesNotCostTheOthersTheirPass() = runTest {
        // The whole reason a queue is per instance. A server answering nothing
        // but errors must not take the working one down with it.
        broken += "broken"
        signedIn("i-1", "broken")
        signedIn("i-2", "working")

        val outcome = backup.pass()

        assertTrue(seen.any { it.first == "working" }, "never reached the second instance")
        // Work left waiting is what asks for the next pass.
        assertEquals(PassOutcome.ComeBackLater, outcome)
    }

    @Test
    fun aPassOverNothingLeftToSendDoesNotAskToBeWokenAgain() = runTest {
        signedIn("i-1", "fine")
        assertEquals(PassOutcome.Finished, backup.pass())
    }

    @Test
    fun theDatabaseIsReadyWithoutAnybodyRememberingToMigrateIt() = runTest {
        // Every accessor migrates, so the first thing that touches a fresh file
        // works -- including forgetting an instance that never backed anything up.
        signedIn("i-1", "fine")
        assertEquals(emptyList(), backup.failures("i-1"))
        database.forget("i-1")
    }
}
