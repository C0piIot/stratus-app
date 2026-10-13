package dev.stratus.core.media.conformance

import dev.stratus.core.backup.Connection
import dev.stratus.core.backup.Connections
import dev.stratus.core.cast.conformance.TINY_HEIC
import dev.stratus.core.dav.DavClient
import dev.stratus.core.dav.DavError
import dev.stratus.core.media.Thumbnail
import dev.stratus.core.media.Thumbnails
import dev.stratus.core.net.Credentials
import dev.stratus.core.net.originOf
import dev.stratus.core.net.stratusHttpClient
import dev.stratus.core.server.Server
import dev.stratus.core.server.ServerStore
import dev.stratus.core.store.SecureStore
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private class MemoryStore : SecureStore {
    private val values = mutableMapOf<String, String>()
    override suspend fun read(key: String) = values[key]
    override suspend fun write(key: String, value: String) { values[key] = value }
    override suspend fun delete(key: String) { values.remove(key) }
}

/**
 * A picture asked for the way the picker asks for one.
 *
 * `ShareConformanceTest` proves the token and `CastConformanceTest` proves the
 * address; neither proves the app's own call, which is where this had been
 * wrong -- the link was signed with a clock of zero, so every one of them
 * expired in 1970 and the share gate answered `403` without ever looking at
 * the credentials behind it (stratus-app#130).
 */
class ThumbnailConformanceTest {

    private val baseUrl = requireNotNull(System.getenv("STRATUS_TEST_URL")) { "run with `make conformance`" }
    private val credentials = Credentials(
        System.getenv("STRATUS_TEST_USER") ?: "conformance",
        System.getenv("STRATUS_TEST_PASS") ?: "conformance-secret",
    )

    private val http = stratusHttpClient(CIO.create(), credentials)
    private val dav = DavClient(http, originOf(baseUrl))
    private val root = "/files/thumb-${Random.nextLong().toULong().toString(16)}"

    private val instances = ServerStore(MemoryStore())
    private val thumbs = Thumbnails(instances, Connections { Connection(http, dav) })

    @AfterTest
    fun tearDown() = runTest { runCatching { dav.delete(root) } }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun aPhotographHasAPictureAndATextFileHasNone() = runTest {
        instances.put(Server(originOf(baseUrl), credentials.username), credentials)
        try {
            dav.makeCollection(root)
        } catch (_: DavError.AlreadyExists) {
            // Already there.
        }
        dav.put("$root/IMG_1.HEIC", Base64.decode(TINY_HEIC))
        dav.put("$root/a.txt", "hello".encodeToByteArray())

        val picture = assertIs<Thumbnail.Picture>(thumbs.of("$root/IMG_1.HEIC", 96))
        // JPEG's own two first bytes, because a content type is a claim.
        assertEquals(listOf(0xFF, 0xD8), picture.bytes.take(2).map { it.toInt() and 0xff })

        // And the other answer, which the server gives as a 404: there is no
        // picture of a text file and asking again will not make one.
        assertIs<Thumbnail.None>(thumbs.of("$root/a.txt", 96))
    }
}
