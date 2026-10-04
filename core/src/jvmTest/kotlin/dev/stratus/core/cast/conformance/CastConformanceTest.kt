package dev.stratus.core.cast.conformance

import dev.stratus.core.cast.HLS_TYPE
import dev.stratus.core.cast.castItemFor
import dev.stratus.core.dav.DavClient
import dev.stratus.core.dav.DavError
import dev.stratus.core.net.Credentials
import dev.stratus.core.net.originOf
import dev.stratus.core.net.stratusHttpClient
import dev.stratus.core.share.ShareLinks
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The URL a television is given, fetched the way a television fetches it.
 *
 * This is the claim the whole feature rests on and neither side can make alone:
 * that a photograph this app uploaded untouched -- a HEIC, which is what an
 * iPhone records and what no Chromecast can read -- comes back as a JPEG to a
 * request carrying no credentials at all.
 */
class CastConformanceTest {

    private val baseUrl = requireNotNull(System.getenv("STRATUS_TEST_URL")) { "run with `make conformance`" }
    private val credentials = Credentials(
        System.getenv("STRATUS_TEST_USER") ?: "conformance",
        System.getenv("STRATUS_TEST_PASS") ?: "conformance-secret",
    )

    // Both on the origin, as sign-in settles on it: a path then carries the
    // collection it is in, which is what a signature is taken relative to
    // (stratus-backend#279).
    private val dav = DavClient(stratusHttpClient(CIO.create(), credentials), originOf(baseUrl))
    private val links = ShareLinks(originOf(baseUrl), credentials)

    /** No cookie and no Basic, which is a Chromecast's whole position. */
    private val television = HttpClient(CIO) { followRedirects = false }

    private val root = "/files/cast-${Random.nextLong().toULong().toString(16)}"

    @AfterTest
    fun tearDown() = runTest { runCatching { dav.delete(root) } }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun aHeicIsFetchedAsAJpegBySomethingWithNoAccount() = runTest {
        try {
            dav.makeCollection(root)
        } catch (_: DavError.AlreadyExists) {
            // Already there.
        }
        dav.put("$root/IMG_1.HEIC", Base64.decode(TINY_HEIC))

        val entry = dav.stat("$root/IMG_1.HEIC")
        val item = requireNotNull(castItemFor(entry, links, System.currentTimeMillis() / 1000)) {
            "the server called it ${entry.contentType}, which nothing here casts"
        }
        assertEquals("image/jpeg", item.contentType)

        val response = television.get(item.url)
        assertEquals(200, response.status.value, "the television would have been given nothing")
        assertTrue(
            response.headers[HttpHeaders.ContentType]?.startsWith("image/jpeg") == true,
            "was ${response.headers[HttpHeaders.ContentType]}",
        )
        // JPEG's own two first bytes, because a content type is a claim.
        val bytes = response.readRawBytes()
        assertEquals(listOf(0xFF, 0xD8), bytes.take(2).map { it.toInt() and 0xff })
    }

    /**
     * A film cast the way a Chromecast plays it: the playlist and then a
     * segment, both from nothing but the signature the playlist was fetched
     * with. A Matroska file with AC-3 sound, the commonest film a receiver
     * cannot take as it is, and the server's own fixture for it.
     */
    @Test
    fun aFilmIsFetchedAsHlsBySomethingWithNoAccount() = runTest {
        try {
            dav.makeCollection(root)
        } catch (_: DavError.AlreadyExists) {
            // Already there.
        }
        val film = requireNotNull(javaClass.getResourceAsStream("/film.mkv")).readBytes()
        dav.put("$root/film.mkv", film)

        val entry = dav.stat("$root/film.mkv")
        val item = requireNotNull(castItemFor(entry, links, System.currentTimeMillis() / 1000)) {
            "the server called it ${entry.contentType}, which nothing here casts"
        }
        assertEquals(HLS_TYPE, item.contentType)

        // The playlist needs the film to have been read, which the upload
        // starts and does not wait for.
        val playlist = withContext(Dispatchers.IO) {
            withTimeout(60_000) {
                var answer = television.get(item.url)
                while (answer.status.value != 200) {
                    delay(250)
                    answer = television.get(item.url)
                }
                answer.bodyAsText()
            }
        }
        val segment = requireNotNull(playlist.lines().firstOrNull { it.startsWith("?hls=") }) {
            "no segment in:\n$playlist"
        }
        assertTrue(segment.contains("&k="), "a segment the television could not fetch: $segment")

        val response = television.get(item.url.substringBefore('?') + segment)
        assertEquals(200, response.status.value, "the television would have been given nothing")
        // MPEG-TS's sync byte, because a content type is a claim.
        assertEquals(0x47, response.readRawBytes().first().toInt() and 0xff)
    }
}

/** 423 bytes, the same fixture the server's own thumbnail tests use. */
private const val TINY_HEIC =
    "AAAAHGZ0eXBoZWl4AAAAAG1pZjFoZWl4bWlhZgAAAVFtZXRhAAAAAAAAACFoZGxyAAAAAAAAAABw" +
    "aWN0AAAAAAAAAAAAAAAAAAAAACJpbG9jAAAAAERAAAEAAQAAAAABdQABAAAAAAAAADIAAAAjaWlu" +
    "ZgAAAAAAAQAAABVpbmZlAgAAAAABAABodmMxAAAAAA5waXRtAAAAAAABAAAA0WlwcnAAAACyaXBj" +
    "bwAAAHVodmNDAQQIAAAAAAAAAAAAHvAA/P36+gAADwNgAAEAF0ABDAH//wQIAAADAJ24AAADAAAe" +
    "ugJAYQABAClCAQEECAAAAwCduAAAAwAAHqAwgQTZbqSSmubgIaDAgAAADIAAAAMAhGIAAQAHRAHB" +
    "crAiQAAAABNjb2xybmNseAABAA0ABoAAAAAUaXNwZQAAAAAAAABgAAAAQAAAAA5waXhpAAAAAAEK" +
    "AAAAF2lwbWEAAAAAAAAAAQABBIECAwQAAAA6bWRhdAAAAC4oAa9Y+TKcmYxDsEqV6Iy2dq/Gvyf6" +
    "FQZQIVrfpFF3nx46/S7KMqneB94tQBTu"
