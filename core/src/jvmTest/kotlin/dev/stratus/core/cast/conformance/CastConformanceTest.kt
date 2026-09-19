package dev.stratus.core.cast.conformance

import dev.stratus.core.cast.castItemFor
import dev.stratus.core.dav.DavClient
import dev.stratus.core.dav.DavError
import dev.stratus.core.net.Credentials
import dev.stratus.core.net.stratusHttpClient
import dev.stratus.core.share.ShareLinks
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
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

    private val dav = DavClient(stratusHttpClient(CIO.create(), credentials), baseUrl)
    private val links = ShareLinks(baseUrl, credentials)

    /** No cookie and no Basic, which is a Chromecast's whole position. */
    private val television = HttpClient(CIO) { followRedirects = false }

    private val root = "/cast-${Random.nextLong().toULong().toString(16)}"

    @AfterTest
    fun tearDown() = runTest { runCatching { dav.delete(root) } }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun aHeicIsFetchedAsAJpegBySomethingWithNoAccount() = runTest {
        try {
            dav.makeCollection(root)
        } catch (_: DavError.Conflict) {
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
