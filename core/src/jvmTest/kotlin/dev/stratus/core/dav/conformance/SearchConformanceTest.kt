package dev.stratus.core.dav.conformance

import dev.stratus.core.dav.DavClient
import dev.stratus.core.net.originOf
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import java.util.Base64
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `SEARCH` against a real stratus-backend, started by `make conformance`.
 *
 * This is the test that settles stratus-app#143, because the query is a wire
 * format two codebases in two languages have to agree on and no mock here can
 * promise that they do -- the same reason the share link is pinned against a
 * real server as well as against a golden value.
 */
class SearchConformanceTest {

    private val baseUrl: String = requireNotNull(System.getenv("STRATUS_TEST_URL")) {
        "STRATUS_TEST_URL is unset. Run this with `make conformance`, not directly."
    }
    private val user: String = System.getenv("STRATUS_TEST_USER") ?: "conformance"
    private val password: String = System.getenv("STRATUS_TEST_PASS") ?: "conformance-secret"

    private val http = HttpClient {
        defaultRequest { header(HttpHeaders.Authorization, basic(user, password)) }
    }

    // Rooted at the origin, which is where the app is rooted: the collection
    // it would send a query to is "/", and a server that only answered at
    // /files/ would be one this client has to know the shape of.
    private val dav = DavClient(http, originOf(baseUrl))

    /** A word no other test's fixtures carry, so a match here is ours. */
    private val word = "zarzaparrilla${Random.nextLong().toULong().toString(16)}"
    private val root = "/files/search-$word"

    @AfterTest
    fun tearDown() {
        http.close()
    }

    @Test
    fun theServerSaysItCanSearch() = runTest {
        assertTrue(dav.canSearch(), "the backend did not advertise DASL at the origin")
    }

    @Test
    fun aWordOfANameFindsTheFileAndTheFolder() = runTest {
        dav.makeCollection(root)
        dav.put("$root/a $word photograph.jpg", "bytes".encodeToByteArray(), "image/jpeg")
        dav.put("$root/unrelated.txt", "bytes".encodeToByteArray(), "text/plain")

        val answer = dav.search(word)
        val paths = answer.found.map { it.path }

        // The folder is named for the word too, and a word in a folder finds
        // the folder rather than everything under it -- which is the promise
        // the server's own port makes and the reason it is asserted here.
        assertContains(paths, root)
        assertContains(paths, "$root/a $word photograph.jpg")
        assertTrue(paths.none { it.endsWith("unrelated.txt") }, "found a file the word is not in: $paths")
        assertEquals(false, answer.cut)
    }

    @Test
    fun aFoundFileCarriesWhatAListingWouldHaveCarried() = runTest {
        dav.makeCollection(root)
        val etag = dav.put("$root/$word.jpg", "bytes".encodeToByteArray(), "image/jpeg")

        val found = dav.search(word).found.single { it.path == "$root/$word.jpg" }

        // The same validator the listing gives, which is what makes a result
        // a row rather than a hint to go and look it up again.
        assertEquals(etag, found.etag)
        assertEquals(dav.stat("$root/$word.jpg").size, found.size)
        assertEquals("image/jpeg", found.contentType)
        assertEquals(false, found.isDirectory)
    }

    @Test
    fun aSearchThatMatchesNothingIsAnEmptyAnswerAndNotAFailure() = runTest {
        assertEquals(emptyList(), dav.search("nothingonthisserveriscalledthis").found)
    }

    private fun basic(user: String, password: String): String =
        "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())
}
