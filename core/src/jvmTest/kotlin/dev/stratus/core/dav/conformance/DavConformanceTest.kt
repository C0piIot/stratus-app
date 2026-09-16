package dev.stratus.core.dav.conformance

import dev.stratus.core.dav.DavClient
import dev.stratus.core.dav.DavError
import dev.stratus.core.dav.Depth
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import java.util.Base64
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The client against a real stratus-backend, started by `make conformance`.
 *
 * Everything else in this module tests the client against our idea of a server.
 * This tests it against the server, which is where a wrong assumption about the
 * protocol actually surfaces. Two of these assert limitations rather than
 * features, and are meant to fail the day the backend loses them -- that is how
 * we find out.
 */
class DavConformanceTest {

    private val baseUrl: String = requireNotNull(System.getenv("STRATUS_TEST_URL")) {
        "STRATUS_TEST_URL is unset. Run this with `make conformance`, not directly."
    }
    private val user: String = System.getenv("STRATUS_TEST_USER") ?: "conformance"
    private val password: String = System.getenv("STRATUS_TEST_PASS") ?: "conformance-secret"

    private val http = HttpClient {
        defaultRequest { header(HttpHeaders.Authorization, basic(user, password)) }
    }
    private val dav = DavClient(http, baseUrl)

    /** Each test owns a subtree, so one leaving a mess cannot fail another. */
    private val root = "/conformance-${Random.nextLong().toULong().toString(16)}"

    @AfterTest
    fun tearDown() {
        http.close()
    }

    private suspend fun givenRoot(): String {
        dav.makeCollection(root)
        return root
    }

    @Test
    fun createsACollectionAndReportsItAsADirectory() = runTest {
        givenRoot()
        val entry = dav.stat(root)
        assertTrue(entry.isDirectory, "expected a directory, got $entry")
    }

    @Test
    fun roundTripsTheBytesOfAFile() = runTest {
        givenRoot()
        val body = "a picture, allegedly".encodeToByteArray()
        dav.put("$root/photo.jpg", body, "image/jpeg")
        val read = dav.read("$root/photo.jpg") { it.readRemaining().readByteArray() }
        assertEquals(body.toList(), read.toList())
    }

    @Test
    fun agreesWithItselfAboutTheEtag() = runTest {
        givenRoot()
        val written = dav.put("$root/a.txt", "hello".encodeToByteArray(), "text/plain")
        assertNotNull(written, "the server returned no ETag on PUT")
        assertEquals(written, dav.stat("$root/a.txt").etag)
    }

    @Test
    fun listsChildrenWithTheirNamesDecoded() = runTest {
        givenRoot()
        // The three shapes that break naive encoding: a space, a character
        // outside ASCII, and an ampersand that also has to survive XML escaping.
        val names = listOf("IMG 0001.jpg", "mañana.txt", "a&b \"c\".txt")
        for (name in names) dav.put("$root/$name", name.encodeToByteArray())

        val listed = dav.list(root).map { it.name }.sorted()
        assertEquals(names.sorted(), listed)
    }

    @Test
    fun reportsSizeAndContentTypeForAFile() = runTest {
        givenRoot()
        dav.put("$root/sized.txt", ByteArray(1234), "text/plain")
        val entry = dav.stat("$root/sized.txt")
        assertEquals(1234L, entry.size)
        assertContains(entry.contentType ?: "", "text/plain")
        assertTrue((entry.lastModifiedEpochMs ?: 0L) > 0L)
    }

    @Test
    fun servesAByteRange() = runTest {
        givenRoot()
        dav.put("$root/range.bin", "0123456789".encodeToByteArray())
        val slice = dav.read("$root/range.bin", 2L..5L) { it.readRemaining().readByteArray() }
        assertEquals("2345", slice.decodeToString())
    }

    @Test
    fun renamesAFile() = runTest {
        givenRoot()
        dav.put("$root/before.txt", "x".encodeToByteArray())
        dav.move("$root/before.txt", "$root/after one.txt")
        assertEquals(listOf("after one.txt"), dav.list(root).map { it.name })
    }

    @Test
    fun renamesAnEmptyDirectory() = runTest {
        givenRoot()
        dav.makeCollection("$root/empty")
        dav.move("$root/empty", "$root/renamed")
        assertEquals(listOf("renamed"), dav.list(root).map { it.name })
    }

    @Test
    fun deletesADirectoryAndEverythingUnderIt() = runTest {
        givenRoot()
        dav.makeCollection("$root/full")
        dav.put("$root/full/inside.txt", "x".encodeToByteArray())
        dav.delete("$root/full")
        assertEquals(emptyList(), dav.list(root).map { it.name })
    }

    @Test
    fun reportsAMissingPathAsNotFound() = runTest {
        val thrown = runCatching { dav.stat("$root/nothing-here") }.exceptionOrNull()
        assertTrue(thrown is DavError.NotFound, "was $thrown")
    }

    @Test
    fun rejectsWrongCredentials() = runTest {
        val wrong = HttpClient {
            defaultRequest { header(HttpHeaders.Authorization, basic(user, "not the password")) }
        }
        try {
            val thrown = runCatching { DavClient(wrong, baseUrl).list("/") }.exceptionOrNull()
            assertTrue(thrown is DavError.Unauthorized, "was $thrown")
        } finally {
            wrong.close()
        }
    }

    @Test
    fun refusesToWriteIntoAMissingParent() = runTest {
        val thrown = runCatching {
            dav.put("$root/no-such-dir/f.txt", "x".encodeToByteArray())
        }.exceptionOrNull()
        assertTrue(thrown is DavError.Conflict, "was $thrown")
    }

    @Test
    fun refusesToCreateACollectionOverSomethingThatExists() = runTest {
        givenRoot()
        val thrown = runCatching { dav.makeCollection(root) }.exceptionOrNull()
        assertTrue(thrown is DavError.Conflict, "was $thrown")
    }

    // ---- Limitations, pinned so that losing them is noticed -----------------

    /**
     * Renaming a directory with anything in it is refused (stratus-backend#101).
     *
     * **When this test fails, the server has learned to do it** and the app can
     * drop the special message it shows for this case. Delete the test then.
     */
    @Test
    fun renamingANonEmptyDirectoryIsStillRefused() = runTest {
        givenRoot()
        dav.makeCollection("$root/full")
        dav.put("$root/full/inside.txt", "x".encodeToByteArray())

        val thrown = runCatching { dav.move("$root/full", "$root/renamed") }.exceptionOrNull()
        assertTrue(thrown is DavError.Conflict, "was $thrown")
        assertContains(thrown.detail, "not empty")
    }

    /**
     * A `Depth: 1` PROPFIND omits the collection itself (stratus-backend#126),
     * where RFC 4918 §9.1 says it covers "the resource and its internal
     * members". `DavClient.list` filters the entry out when a server does send
     * it, so the app works either way.
     *
     * **When this test fails, the server has been fixed** and the filtering in
     * `list` becomes the only thing still needed. Delete the test then.
     */
    @Test
    fun propfindDepthOneStillOmitsTheCollectionItself() = runTest {
        givenRoot()
        dav.put("$root/one.txt", "x".encodeToByteArray())

        val raw = dav.propfind(root, Depth.One)
        assertEquals(listOf("$root/one.txt"), raw.map { it.path })
    }

    private fun basic(user: String, password: String): String =
        "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())
}
