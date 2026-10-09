package dev.stratus.core.documents

import dev.stratus.core.backup.Connection
import dev.stratus.core.backup.Connections
import dev.stratus.core.dav.DavClient
import dev.stratus.core.server.Server
import dev.stratus.core.server.ServerStore
import dev.stratus.core.net.Credentials
import dev.stratus.core.store.SecureStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class MemoryStore : SecureStore {
    val values = mutableMapOf<String, String>()
    override suspend fun read(key: String) = values[key]
    override suspend fun write(key: String, value: String) { values[key] = value }
    override suspend fun delete(key: String) { values.remove(key) }
}

class DocumentTreeTest {

    // Appended to from the tree's background fetches as well as from the test,
    // which is why every case below settles before it reads: with nothing in
    // flight there is one writer at a time, and without that
    // `eachOperationIsTheWebDavVerbItShouldBe` failed about one run in twenty
    // with a listing landing under its assertion.
    private val seen = mutableListOf<HttpRequestData>()
    private val told = mutableListOf<DocumentRef>()

    /** A server with `files/`, `photos/` and one folder with one file in it. */
    private fun answer(request: HttpRequestData): Pair<HttpStatusCode, String> = when {
        request.method.value == "PROPFIND" && request.url.encodedPath == "/" ->
            HttpStatusCode.MultiStatus to multistatus("/" to true, "/files/" to true, "/photos/" to true)
        request.method.value == "PROPFIND" && request.url.encodedPath == "/files/" ->
            HttpStatusCode.MultiStatus to multistatus("/files/" to true, "/files/holiday/" to true, "/files/a.txt" to false)
        request.method.value == "PROPFIND" && request.url.encodedPath == "/files/holiday/" ->
            HttpStatusCode.MultiStatus to multistatus("/files/holiday/" to true)
        request.method.value == "PROPFIND" && request.url.encodedPath == "/files/gone/" ->
            HttpStatusCode.NotFound to "no"
        else -> HttpStatusCode.Created to ""
    }

    private fun multistatus(vararg entries: Pair<String, Boolean>) = buildString {
        append("""<multistatus xmlns="DAV:">""")
        for ((href, isDir) in entries) {
            append("<response><href>$href</href><propstat><prop>")
            append(if (isDir) "<resourcetype><collection/></resourcetype>" else "<resourcetype/><getcontentlength>7</getcontentlength><getcontenttype>text/plain</getcontenttype><getetag>\"abc123\"</getetag>")
            append("</prop><status>HTTP/1.1 200 OK</status></propstat></response>")
        }
        append("</multistatus>")
    }

    // A real dispatcher and not the test one: the mock engine hops off it, so
    // the virtual clock cannot say when a fetch is done. Joining the jobs can.
    private val jobs = SupervisorJob()
    private val treeScope = CoroutineScope(Dispatchers.Default + jobs)

    /** Waits for whatever the tree started, which is the only thing to wait for. */
    private suspend fun settle() {
        while (true) {
            val running = jobs.children.toList()
            if (running.isEmpty()) return
            running.forEach { it.join() }
        }
    }

    private suspend fun tree(): DocumentTree {
        val engine = MockEngine { request ->
            seen += request
            val (status, body) = answer(request)
            respond(body, status, headersOf("Content-Type", "application/xml"))
        }
        val http = HttpClient(engine)
        val secure = MemoryStore()
        val instances = ServerStore(secure)
        instances.put(Server("http://host/", "edu"), Credentials("edu", "secret"))
        val connections = Connections { instance ->
            Connection(http, DavClient(http, instance.baseUrl))
        }
        return DocumentTree(instances, connections, treeScope) { told += it }
    }

    @Test
    fun theServerIsTheRoot() = runTest {
        // One server (stratus-app#131), so one row in the sidebar -- and none
        // at all when nobody is signed in, which is an answer and not an error.
        val tree = tree()
        assertEquals("host", tree.root()?.summary)
        assertEquals(DocumentRef.ROOT_ID, tree.root()?.document?.id)
    }

    @Test
    fun aListingIsAskedForOnceAndAnsweredFromThenOn() = runTest {
        val tree = tree()
        val ref = DocumentRef("files")

        // It comes back at once, because a picker that waited for a round trip
        // would look frozen.
        assertIs<Listing.Loading>(tree.children(ref))
        settle()

        val loaded = assertIs<Listing.Loaded>(tree.children(ref))
        assertEquals(listOf("holiday", "a.txt"), loaded.rows.map { it.name })
        assertTrue(loaded.rows.first().isDirectory, "folders come first")
        assertEquals(listOf(ref), told, "the platform was not told the answer arrived")

        // And asked for again it is not fetched again. **Not a cache with an
        // age**: a provider notifies and is queried, so a listing that
        // refetched when it looked old would notify, be queried and refetch
        // for ever.
        val before = seen.size
        assertIs<Listing.Loaded>(tree.children(ref))
        settle()
        assertEquals(before, seen.size, "a second query went back to the server")
    }

    @Test
    fun refreshingIsWhatAsksAgain() = runTest {
        val tree = tree()
        val ref = DocumentRef("files")
        tree.children(ref)
        settle()
        val before = seen.size

        tree.refresh(ref)
        settle()
        assertTrue(seen.size > before, "refresh did not go back to the server")
        assertIs<Listing.Loaded>(tree.children(ref))
    }

    @Test
    fun aWriteIsWhatElseAsksAgain() = runTest {
        val tree = tree()
        val parent = DocumentRef("files")
        tree.children(parent)
        settle()
        val before = seen.size

        tree.create(parent, DocumentTree.MIME_DIRECTORY, "new")
        settle()

        assertEquals("MKCOL", seen[before].method.value)
        assertEquals("/files/new", seen[before].url.encodedPath)
        assertTrue(seen.size > before + 1, "the folder it was added to was not asked for again")
    }

    @Test
    fun eachOperationIsTheWebDavVerbItShouldBe() = runTest {
        val tree = tree()
        val parent = DocumentRef("files")

        // Settled after each one: every write invalidates its folder, so the
        // listing that follows would otherwise land on the request log in the
        // middle of the next assertion.
        tree.create(parent, "text/plain", "note.txt")
        settle()
        assertEquals("PUT", seen.last { it.method.value != "PROPFIND" }.method.value)

        tree.rename(DocumentRef("files/note.txt"), "other.txt")
        settle()
        val moved = seen.last { it.method.value == "MOVE" }
        assertEquals("http://host/files/other.txt", moved.headers["Destination"])

        tree.delete(DocumentRef("files/other.txt"))
        settle()
        assertEquals("DELETE", seen.last { it.method.value != "PROPFIND" }.method.value)
    }

    @Test
    fun aServerThatRefusesIsSaidAndNotDrawnAsAnEmptyFolder() = runTest {
        // The mistake this is here to prevent: a listing that failed and a
        // folder with nothing in it look identical to whoever is looking.
        val tree = tree()
        tree.children(DocumentRef("files/gone"))
        settle()
        val failed = assertIs<Listing.Failed>(tree.children(DocumentRef("files/gone")))
        assertTrue(failed.message.isNotBlank())
    }

    @Test
    fun oneDocumentComesOutOfItsParentsListingRatherThanAskingAgain() = runTest {
        val tree = tree()
        tree.children(DocumentRef("files"))
        settle()
        val before = seen.size

        val row = tree.one(DocumentRef("files/a.txt"))
        settle()

        assertEquals("a.txt", row?.name)
        assertEquals(7L, row?.size)
        // Carried for iOS, where a File Provider item needs a version and this
        // is the only honest one WebDAV gives (stratus-app#105).
        assertEquals("abc123", row?.etag)
        assertEquals(before, seen.size, "asking about a row it had just been given cost a request")
    }
}
