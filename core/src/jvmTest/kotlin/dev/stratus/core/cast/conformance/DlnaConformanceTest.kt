package dev.stratus.core.cast.conformance

import dev.stratus.core.cast.castItemFor
import dev.stratus.core.cast.dlna.DatagramSsdpSocket
import dev.stratus.core.cast.dlna.DlnaControl
import dev.stratus.core.cast.dlna.Renderer
import dev.stratus.core.cast.dlna.Sighting
import dev.stratus.core.cast.dlna.Ssdp
import dev.stratus.core.cast.dlna.pickFor
import dev.stratus.core.dav.DavClient
import dev.stratus.core.dav.DavError
import dev.stratus.core.net.Credentials
import dev.stratus.core.net.originOf
import dev.stratus.core.net.stratusHttpClient
import dev.stratus.core.share.ShareLinks
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/**
 * A renderer found the way a phone finds one -- a real SSDP search on the
 * network -- and handed a signed link the way a phone hands it one, until it
 * says it is playing (stratus-app#153).
 *
 * The renderer is gmrender-resurrect, which `scripts/conformance.sh` starts
 * beside the server with sinks that discard what they play. It is not a
 * television; what it settles is that nothing between the two ends is ours
 * to get wrong.
 */
class DlnaConformanceTest {

    private val baseUrl = requireNotNull(System.getenv("STRATUS_TEST_URL")) { "run with `make conformance`" }
    private val credentials = Credentials(
        System.getenv("STRATUS_TEST_USER") ?: "conformance",
        System.getenv("STRATUS_TEST_PASS") ?: "conformance-secret",
    )
    private val dav = DavClient(stratusHttpClient(CIO.create(), credentials), originOf(baseUrl))
    private val links = ShareLinks(originOf(baseUrl), credentials)
    private val control = DlnaControl(HttpClient(CIO))

    private val root = "/files/dlna-${Random.nextLong().toULong().toString(16)}"

    @AfterTest
    fun tearDown() = runTest { runCatching { dav.delete(root) } }

    @Test
    fun aRendererOnTheNetworkPlaysTheSignedLink() = runTest(timeout = 2.minutes) {
        val renderer = withContext(Dispatchers.IO) { findRenderer() }

        try {
            dav.makeCollection(root)
        } catch (_: DavError.AlreadyExists) {
            // Already there.
        }
        dav.put("$root/silence.wav", silence())
        val entry = dav.stat("$root/silence.wav")
        val item = requireNotNull(castItemFor(entry, links, System.currentTimeMillis() / 1000)) {
            "the server called it ${entry.contentType}, which nothing here casts"
        }

        val pick = requireNotNull(pickFor(control.protocolInfo(renderer), item.choices)) {
            "the renderer plays none of ${item.choices.map { it.contentType }}"
        }
        control.load(renderer, pick)

        // Playing means it fetched the link with nothing but the signature, and
        // read what came back: a link it could not open stops it instead.
        val state = withContext(Dispatchers.IO) {
            withTimeout(30_000) {
                var state = control.transportState(renderer)
                while (state != "PLAYING" && state != "STOPPED") {
                    delay(250)
                    state = control.transportState(renderer)
                }
                state
            }
        }
        control.stop(renderer)
        assertEquals("PLAYING", state, "the renderer was handed ${pick.item.url}")
    }

    /** Searched for more than once, because a datagram is allowed to be lost. */
    private suspend fun findRenderer(): Renderer {
        repeat(5) {
            val seen = mutableListOf<Sighting>()
            DatagramSsdpSocket().search(Ssdp.search(), 3_000) { datagram -> Ssdp.sightingOf(datagram)?.let(seen::add) }
            seen.firstNotNullOfOrNull { control.describe(it.location) }?.let { return it }
        }
        error("no renderer answered a search on this network")
    }

    /** Two seconds of 8 kHz mono silence, which every GStreamer reads without a decoder. */
    private fun silence(): ByteArray {
        val samples = 16_000
        val data = samples * 2
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(8_000); putInt(16_000)
            putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data)
        }
        return header.array() + ByteArray(data)
    }
}
