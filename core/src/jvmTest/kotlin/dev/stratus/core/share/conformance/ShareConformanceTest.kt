package dev.stratus.core.share.conformance

import dev.stratus.core.dav.DavClient
import dev.stratus.core.dav.DavError
import dev.stratus.core.net.Credentials
import dev.stratus.core.net.originOf
import dev.stratus.core.net.stratusHttpClient
import dev.stratus.core.share.ShareLife
import dev.stratus.core.share.ShareLinks
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The link this app signs, opened at the server that did not sign it.
 *
 * Nothing else can prove this. The token's shape is agreed by two codebases in
 * two languages with no shared artefact between them, so a unit test here can
 * only confirm this side has not changed its mind. The day either one moves a
 * byte, this is what says so.
 */
class ShareConformanceTest {

    private val baseUrl = requireNotNull(System.getenv("STRATUS_TEST_URL")) { "run with `make conformance`" }
    private val credentials = Credentials(
        System.getenv("STRATUS_TEST_USER") ?: "conformance",
        System.getenv("STRATUS_TEST_PASS") ?: "conformance-secret",
    )

    private val dav = DavClient(stratusHttpClient(CIO.create(), credentials), baseUrl)
    private val links = ShareLinks(baseUrl, credentials)

    /** Nothing at all: no cookie, no Basic, which is the visitor's whole position. */
    private val stranger = HttpClient(CIO) { followRedirects = false }

    private val root = "/share-${Random.nextLong().toULong().toString(16)}"

    // The three shapes that break a URL: a space, something outside ASCII, and
    // an ampersand. The DAV suite pins them for hrefs; this pins them signed.
    private val name = "a b & ñ.txt"
    private val contents = "the bytes somebody was sent"

    @AfterTest
    fun tearDown() = runTest {
        runCatching { dav.delete(root) }
    }

    private suspend fun given() {
        try {
            dav.makeCollection(root)
        } catch (_: DavError.Conflict) {
            // Already there.
        }
        dav.put("$root/$name", contents.encodeToByteArray())
    }

    private fun tokenOf(url: String) = url.substringAfter("?k=")

    private fun urlFor(path: String, token: String) =
        URLBuilder(originOf(baseUrl)).apply {
            appendPathSegments(listOf("files") + path.trim('/').split('/'))
            parameters.append("k", token)
        }.buildString()

    @Test
    fun aFileOpensForSomebodyWithNoAccountAtAll() = runTest {
        given()
        val link = links.link("$root/$name", isDirectory = false, life = ShareLife.Forever, nowEpochSeconds = now())

        val response = stranger.get(link)
        assertEquals(200, response.status.value, "the server would not take our signature")
        assertEquals(contents, response.bodyAsText())
    }

    @Test
    fun itStreamsRangesBecauseAReceiverSeeks() = runTest {
        given()
        val link = links.link("$root/$name", false, ShareLife.Forever, now())

        val response = stranger.get(link) { header(HttpHeaders.Range, "bytes=4-7") }
        assertEquals(206, response.status.value, "a film that cannot seek is not a film")
        assertEquals(contents.substring(4, 8), response.bodyAsText())
    }

    @Test
    fun aFolderLinkReachesInsideItAndNothingElse() = runTest {
        given()
        val token = tokenOf(links.link(root, isDirectory = true, life = ShareLife.Forever, nowEpochSeconds = now()))

        assertEquals(200, stranger.get(urlFor("$root/$name", token)).status.value, "did not reach its own child")
        // The same signature offered one level up, which is the mistake that
        // would turn a shared folder into the whole library.
        assertEquals(403, stranger.get(urlFor("/", token)).status.value)
    }

    @Test
    fun aFileLinkIsNotAKeyToItsFolder() = runTest {
        given()
        val token = tokenOf(links.link("$root/$name", isDirectory = false, life = ShareLife.Forever, nowEpochSeconds = now()))
        assertEquals(403, stranger.get(urlFor(root, token)).status.value)
    }

    @Test
    fun aDeadlineInThePastIsRefused() = runTest {
        given()
        // A day from a day ago: a link whose life ran out while nobody looked.
        val link = links.link("$root/$name", false, ShareLife.ADay, nowEpochSeconds = now() - 2 * 86_400)
        assertEquals(403, stranger.get(link).status.value)
    }

    @Test
    fun anEditedSignatureIsRefused() = runTest {
        given()
        val link = links.link("$root/$name", false, ShareLife.Forever, now())
        val tampered = link.dropLast(4) + "aaaa"
        assertTrue(stranger.get(tampered).status.value in listOf(403, 401), "an edited token was accepted")
    }

    private fun now() = System.currentTimeMillis() / 1000
}
