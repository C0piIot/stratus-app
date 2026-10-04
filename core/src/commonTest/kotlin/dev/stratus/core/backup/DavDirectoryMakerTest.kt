package dev.stratus.core.backup

import dev.stratus.core.dav.DavClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A server answers a `MKCOL` three ways, and the walk is a function of which.
 *
 * Written against the statuses RFC 4918 9.3.1 names rather than against one
 * server: 201 made it, 405 says it was already there, 409 says its parent is
 * not.
 */
class DavDirectoryMakerTest {

    /** Paths that already exist; everything under a missing one answers 409. */
    private class Server(vararg existing: String) {
        val there = existing.toMutableSet()
        val asked = mutableListOf<String>()

        fun answer(path: String): HttpStatusCode {
            asked += path
            if (path in there) return HttpStatusCode.MethodNotAllowed
            val parent = path.substringBeforeLast('/')
            if (parent.isNotEmpty() && parent !in there) return HttpStatusCode.Conflict
            there += path
            return HttpStatusCode.Created
        }

        fun maker(): DavDirectoryMaker {
            val engine = MockEngine { request -> respond("", answer(request.url.encodedPath)) }
            return DavDirectoryMaker(DavClient(HttpClient(engine), "http://host/"))
        }
    }

    @Test
    fun asksForTheMonthFirstAndStopsThereWhenItIsAlreadyMade() = runTest {
        // The ordinary case, which is every photograph after the first of the
        // month: one request, not one per segment.
        val server = Server("/files", "/files/phone_backup", "/files/phone_backup/2026", "/files/phone_backup/2026/10")
        server.maker().ensure("/files/phone_backup/2026/10/")
        assertEquals(listOf("/files/phone_backup/2026/10"), server.asked)
    }

    @Test
    fun climbsOnlyAsFarAsTheMissingParentAndComesBackDown() = runTest {
        val server = Server("/files")
        server.maker().ensure("/files/phone_backup/2026/10/")
        assertEquals(
            listOf(
                "/files/phone_backup/2026/10",
                "/files/phone_backup/2026",
                "/files/phone_backup",
                "/files/phone_backup/2026",
                "/files/phone_backup/2026/10",
            ),
            server.asked,
        )
    }

    @Test
    fun neverAsksToCreateWhatItWasHandedAsAlreadyThere() = runTest {
        // The whole point. `files/` is the server's own, and asking to create
        // it is what the old walk did on every single photograph -- against a
        // router that owns the subtree the answer is not even a 405.
        val server = Server("/files")
        server.maker().ensure("/files/phone_backup/2026/10/")
        assertTrue("/files" !in server.asked, "it asked to create ${server.asked}")
    }

    @Test
    fun theRootIsNobodysToMake() = runTest {
        val server = Server()
        server.maker().ensure("/")
        assertEquals(emptyList(), server.asked)
    }
}
